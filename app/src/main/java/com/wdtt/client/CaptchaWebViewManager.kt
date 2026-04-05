package com.wdtt.client

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * Управляет «невидимым» WebView для автоматического прохождения VK Smart Captcha.
 *
 * Один запрос = один свежий WebView:
 * 1. Создаёт WebView с рандомизированным fingerprint (UA, viewport)
 * 2. Загружает redirect_uri, ждёт ~2.7с загрузки
 * 3. Находит чекбокс "Я не робот" (label.vkc__Checkbox-module__Checkbox)
 * 4. Кликает в рандомную точку внутри label (3-6с "раздумий")
 * 5. JS-interceptor перехватывает captchaNotRobot.check → success_token
 * 6. Уничтожает WebView
 */
object CaptchaWebViewManager {

    private const val TAG = "CaptchaWV"
    private const val CAPTCHA_TIMEOUT_MS = 45_000L
    private const val WV_CREATE_TIMEOUT_MS = 3000L

    // Рандомизируемые параметры viewport (чтобы VK не видел одинаковый size)
    private val VIEWPORT_WIDTHS = intArrayOf(356, 358, 360, 362, 364, 366, 368)
    private val VIEWPORT_HEIGHTS = intArrayOf(376, 378, 380, 382, 384, 386, 388)

    // Пул Chrome-версий (minor builds) для варьирования
    private val CHROME_BUILDS = arrayOf(
        "146.0.0.0", "145.0.6422.60", "145.0.6422.53",
        "144.0.6367.78", "144.0.6367.61", "143.0.6312.99"
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captchaMutex = Mutex()

    @Volatile
    private var isTunnelActive = false

    @Volatile
    private var appContext: Context? = null

    private val pendingResult = AtomicReference<CompletableDeferred<Result<String>>?>(null)

    @Volatile
    private var currentWebView: WebView? = null

    // Interceptor: перехватывает ответ captchaNotRobot.check → достаёт success_token
    private val interceptorJSCode = """
        (function() {
            if (window.__wdtt_interceptor_installed) return;
            window.__wdtt_interceptor_installed = true;

            const origFetch = window.fetch;
            window.fetch = async function() {
                const args = arguments;
                const url = args[0] || '';
                if (typeof url === 'string' && url.includes('captchaNotRobot.check')) {
                    const response = await origFetch.apply(this, args);
                    const clone = response.clone();
                    try {
                        const data = await clone.json();
                        if (data.response && data.response.success_token) {
                            window.WdttCaptcha.onSuccess(data.response.success_token);
                        } else if (data.error) {
                            window.WdttCaptcha.onError(JSON.stringify(data.error));
                        }
                    } catch(e) {}
                    return response;
                }
                return origFetch.apply(this, args);
            };

            const origXHROpen = XMLHttpRequest.prototype.open;
            const origXHRSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(method, url) {
                this._wdtt_url = url;
                return origXHROpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                const xhr = this;
                if (xhr._wdtt_url && xhr._wdtt_url.includes('captchaNotRobot.check')) {
                    xhr.addEventListener('load', function() {
                        try {
                            const data = JSON.parse(xhr.responseText);
                            if (data.response && data.response.success_token) {
                                window.WdttCaptcha.onSuccess(data.response.success_token);
                            } else if (data.error) {
                                window.WdttCaptcha.onError(JSON.stringify(data.error));
                            }
                        } catch(e) {}
                    });
                }
                return origXHRSend.apply(this, arguments);
            };
        })();
    """.trimIndent()

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

    fun onTunnelStart(context: Context) {
        appContext = context.applicationContext
        isTunnelActive = true
        Log.d(TAG, "Туннель активен")
    }

    fun onTunnelStop() {
        isTunnelActive = false
        cancelPendingResult("tunnel stopped")
        destroyCurrentWebView()
        appContext = null
        Log.d(TAG, "Туннель остановлен")
    }

    // ═══════════════════════════════════════════════════════════════
    // Публичный API
    // ═══════════════════════════════════════════════════════════════

    suspend fun solveCaptchaAsync(redirectUri: String, sessionToken: String, onStep: (String) -> Unit = {}): String {
        if (!isTunnelActive) throw IllegalStateException("WV не готов — туннель не активен")
        val ctx = appContext ?: throw IllegalStateException("WV не готов — контекст null")

        // Используем Mutex вместо AtomicBoolean: если запрашивается вторая капча до закрытия первой,
        // она просто подождет в очереди (несколько секунд), вместо того чтобы вылетать с ошибкой.
        return captchaMutex.withLock {
            try {
                withTimeout(CAPTCHA_TIMEOUT_MS) {
                    doSolveCaptcha(ctx, redirectUri, onStep)
                }
            } finally {
                pendingResult.set(null)
                destroyCurrentWebView()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Внутренняя логика
    // ═══════════════════════════════════════════════════════════════

    private suspend fun doSolveCaptcha(context: Context, redirectUri: String, onStep: (String) -> Unit): String {
        val deferred = CompletableDeferred<Result<String>>()
        pendingResult.set(deferred)

        val webView = createWebViewSync(context, onStep)
            ?: throw IllegalStateException("Не удалось создать WebView")

        Log.d(TAG, "WebView создан ✓")

        // Загружаем страницу капчи
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(interceptorJSCode, null)
            kotlinx.coroutines.delay(80)
            webView.loadUrl(redirectUri)
        }

        // Ждём success_token от JS-bridge
        try {
            val token = deferred.await().getOrThrow()
            Log.d(TAG, "Капча решена ✓")
            return token
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка: ${e::class.simpleName} — ${e.message}")
            throw e
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Создание WebView с рандомизированным fingerprint
    // ═══════════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebViewSync(context: Context, onStep: (String) -> Unit): WebView? {
        // Рандомизируем параметры для КАЖДОГО запроса
        val vw = VIEWPORT_WIDTHS[Random.Default.nextInt(VIEWPORT_WIDTHS.size)]
        val vh = VIEWPORT_HEIGHTS[Random.Default.nextInt(VIEWPORT_HEIGHTS.size)]
        val chromeBuild = CHROME_BUILDS[Random.Default.nextInt(CHROME_BUILDS.size)]
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeBuild Safari/537.36"

        Log.d(TAG, "Fingerprint: ${vw}x${vh}, Chrome/$chromeBuild")

        val latch = CountDownLatch(1)
        var webView: WebView? = null

        val createAction = Runnable {
            try {
                val wv = WebView(context.applicationContext)
                wv.apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        blockNetworkLoads = false
                        cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        userAgentString = ua
                    }

                    addJavascriptInterface(CaptchaJSBridge(), "WdttCaptcha")

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView, url: String?, favicon: android.graphics.Bitmap?
                        ) {
                            super.onPageStarted(view, url, favicon)
                            view.evaluateJavascript(interceptorJSCode, null)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)

                            val isCaptchaPage = url?.let {
                                it.contains("not_robot_captcha") ||
                                it.contains("id.vk.ru/captcha") ||
                                it.contains("not_robot")
                            } ?: false

                            if (isCaptchaPage) {
                                Log.d(TAG, "Страница капчи загружена")
                                view.evaluateJavascript(interceptorJSCode, null)

                                if (currentWebView === view && isTunnelActive) {
                                    // Ждём 2.5-3.5с — реалистичная загрузка страницы
                                    val pageLoadDelay = 2500L + Random.Default.nextLong(0, 1000)
                                    mainHandler.postDelayed({
                                        if (currentWebView === view && isTunnelActive) {
                                            solveCaptchaAutomatedSync(view)
                                        }
                                    }, pageLoadDelay)
                                }
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView, request: WebResourceRequest
                        ): WebResourceResponse? {
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onReceivedSslError(
                            view: WebView,
                            handler: android.webkit.SslErrorHandler,
                            error: android.net.http.SslError
                        ) {
                            handler.proceed()
                        }
                    }

                    webChromeClient = WebChromeClient()

                    measure(
                        View.MeasureSpec.makeMeasureSpec(vw, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(vh, View.MeasureSpec.EXACTLY)
                    )
                    layout(0, 0, vw, vh)
                    onResume()
                }
                webView = wv
                currentWebView = wv
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка создания WebView: ${e.message}")
                webView = null
            } finally {
                latch.countDown()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            createAction.run()
        } else {
            mainHandler.post(createAction)
        }

        val ok = latch.await(WV_CREATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!ok) {
            Log.e(TAG, "Таймаут создания WebView")
            return null
        }
        return webView
    }

    private fun destroyCurrentWebView() {
        val wv = currentWebView ?: return
        currentWebView = null

        val destroyAction = Runnable {
            try {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                try { wv.removeJavascriptInterface("WdttCaptcha") } catch (_: Exception) {}
                wv.webViewClient = WebViewClient()
                wv.webChromeClient = null
                wv.onPause()
                wv.removeAllViews()
                wv.destroy()
                Log.d(TAG, "WebView уничтожен ✓")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка уничтожения: ${e.message}")
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyAction.run()
        } else {
            val latch = CountDownLatch(1)
            mainHandler.post {
                try { destroyAction.run() } finally { latch.countDown() }
            }
            latch.await(2000, TimeUnit.MILLISECONDS)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Авто-решение: клик по чекбоксу «Я не робот»
    //
    // Структура VK капчи (из HTML):
    //   label.vkc__Checkbox-module__Checkbox          ← КЛИКАБЕЛЬНЫЙ label (~200x32px)
    //     input#not-robot-captcha-checkbox             ← скрытый checkbox
    //     div.vkc__Checkbox-module__Checkbox__iconBlock ← иконка чекбокса
    //     div.vkc__Checkbox-module__Checkbox__title     ← текст "Я не робот"
    //
    // Стратегия: находим ВЕСЬ label, получаем его размеры,
    // кликаем в РАНДОМНУЮ точку внутри него (не в центр).
    // ═══════════════════════════════════════════════════════════════

    private fun solveCaptchaAutomatedSync(webView: WebView) {
        if (currentWebView !== webView || !isTunnelActive) return

        // Ищем LABEL целиком (он большой, ~200x32px — как человек кликает)
        val findLabelJS = """
            (function() {
                // Приоритет: label обёртка (самый большой кликабельный элемент)
                var el = document.querySelector('label.vkc__Checkbox-module__Checkbox');
                // Fallback: прямой поиск по ID
                if (!el) el = document.querySelector('label[for="not-robot-captcha-checkbox"]');
                // Fallback: сам чекбокс
                if (!el) el = document.getElementById('not-robot-captcha-checkbox');
                if (!el) return 'not_found';
                
                var rect = el.getBoundingClientRect();
                var style = window.getComputedStyle(el);
                if (rect.width < 5 || rect.height < 5 ||
                    style.display === 'none' || style.visibility === 'hidden') {
                    return 'not_found';
                }
                // Возвращаем left,top,width,height — чтобы кликнуть в РАНДОМНУЮ точку
                return rect.left + ',' + rect.top + ',' + rect.width + ',' + rect.height;
            })();
        """.trimIndent()

        webView.evaluateJavascript(findLabelJS) { rawValue ->
            val result = rawValue?.replace("\"", "") ?: ""
            Log.d(TAG, "Label чекбокса: $result")

            if (currentWebView !== webView || !isTunnelActive) return@evaluateJavascript

            if (result == "not_found" || result.split(",").size < 4) {
                // Fallback: JS .click() — не идеально, но лучше чем ничего
                Log.w(TAG, "Label не найден — JS-клик (fallback)")
                val jsClick = """
                    (function() {
                        var el = document.querySelector('label.vkc__Checkbox-module__Checkbox');
                        if (!el) el = document.getElementById('not-robot-captcha-checkbox');
                        if (el) { el.click(); return 'clicked'; }
                        return 'nothing';
                    })();
                """.trimIndent()
                webView.evaluateJavascript(jsClick, null)
                return@evaluateJavascript
            }

            val parts = result.split(",")
            val left = parts[0].toFloatOrNull() ?: return@evaluateJavascript
            val top = parts[1].toFloatOrNull() ?: return@evaluateJavascript
            val width = parts[2].toFloatOrNull() ?: return@evaluateJavascript
            val height = parts[3].toFloatOrNull() ?: return@evaluateJavascript

            // Рандомная точка внутри label (60-90% ширины, 25-75% высоты)
            // Человек кликает не ровно в центр, а примерно туда
            val randX = left + width * (0.15f + Random.Default.nextFloat() * 0.7f)
            val randY = top + height * (0.25f + Random.Default.nextFloat() * 0.5f)

            Log.d(TAG, "Клик: (${randX.toInt()}, ${randY.toInt()}) в зоне ${width.toInt()}x${height.toInt()}")

            // «Раздумье» перед кликом: 1.5-3.5 секунды (как человек)
            val thinkDelay = 1500L + Random.Default.nextLong(0, 2000)

            mainHandler.postDelayed({
                if (currentWebView === webView && isTunnelActive) {
                    simulateHumanTouch(webView, randX, randY)
                }
            }, thinkDelay)
        }
    }

    /**
     * Имитирует нативный тач как от пальца:
     * - ACTION_DOWN с рандомным pressure (0.5-0.9)
     * - Удержание 80-180мс (как палец на экране)
     * - ACTION_UP с лёгким смещением (палец дрожит)
     */
    private fun simulateHumanTouch(webView: WebView, cssX: Float, cssY: Float) {
        if (currentWebView !== webView) return

        val density = webView.resources.displayMetrics.density
        val physX = cssX * density
        val physY = cssY * density
        val downTime = SystemClock.uptimeMillis()

        // Рандомный pressure — палец нажимает с разной силой
        val pressure = 0.5f + Random.Default.nextFloat() * 0.4f

        val downEvent = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, physX, physY, pressure, 1f, 0, 1f, 1f, 0, 0
        )
        downEvent.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        // Удержание пальца: 80-180мс
        val holdTime = 80L + Random.Default.nextLong(0, 100)

        mainHandler.postDelayed({
            if (currentWebView === webView) {
                // Лёгкое смещение при отпускании (палец не стоит идеально на месте)
                val jitterX = physX + (-1f + Random.Default.nextFloat() * 2f) * density
                val jitterY = physY + (-0.5f + Random.Default.nextFloat() * 1f) * density

                val upEvent = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                    jitterX, jitterY, 0f, 1f, 0, 1f, 1f, 0, 0
                )
                upEvent.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                webView.dispatchTouchEvent(upEvent)
                upEvent.recycle()
            }
        }, holdTime)
    }

    // ═══════════════════════════════════════════════════════════════
    // JS Bridge — вызывается из JavaScript background thread
    // ═══════════════════════════════════════════════════════════════

    private class CaptchaJSBridge {
        @JavascriptInterface
        fun onSuccess(token: String) {
            Log.d(TAG, "JS: success_token получен (${token.length} символов)")
            notifyResult(Result.success(token))
        }

        @JavascriptInterface
        fun onError(error: String) {
            Log.e(TAG, "JS: ошибка — $error")
            notifyResult(Result.failure(Exception("VK: $error")))
        }
    }

    private fun notifyResult(result: Result<String>) {
        val deferred = pendingResult.getAndSet(null) ?: return
        if (!deferred.isCompleted) {
            deferred.complete(result)
        }
    }

    private fun cancelPendingResult(reason: String) {
        val deferred = pendingResult.getAndSet(null) ?: return
        if (!deferred.isCompleted) {
            deferred.complete(Result.failure(CancellationException(reason)))
        }
    }
}
