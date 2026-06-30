package com.wdtt.client

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

import androidx.compose.runtime.Stable

@Stable
data class LogEntry(
    val key: String,
    val message: String,
    val count: Int = 1,
    val priority: Int = 99, // 0 - Creds, 1 - DTLS, 2 - Ready, 3 - Stats, 99 - Errors/Other
    val isError: Boolean = false
)

object TunnelManager {
    // 100% защита от утечек: единый управляемый глобальный Scope
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var process: Process? = null
    private var readerJob: Job? = null
    private var watchdogJob: Job? = null
    private var wgHelper: WireGuardHelper? = null
    
    private val startStopMutex = kotlinx.coroutines.sync.Mutex()

    // Error counters for circuit breaker
    private var floodCount = 0
    private var mismatchCount = 0
    private var refusedCount = 0
    private var currentHashErrorCount = 0
    private var wrapAuthTimeoutCount = 0
    var processStartedAtMs = 0L
    private var lastActiveAtMs = 0L
    private var activeHashIndex = 0 // 0: primary, 1: secondary
    private var currentParams: TunnelParams? = null
    private var lastContext: Context? = null
    private var forceRegenerateUA = false // принудительная перегенерация UA при ошибках
    private var currentCaptchaMode = "wv" // режим обхода капчи: "wv" или "rjs"
    private var currentCaptchaSolveMethod = "auto" // "manual" или "auto"

    @Volatile
    var isLoggingEnabled = true

    val running = MutableStateFlow(false)
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val unreadErrorCount = MutableStateFlow(0)
    val config = MutableStateFlow<String?>(null)
    val stats = MutableStateFlow("Ожидание данных...")
    val activeWorkers = MutableStateFlow(0)
    
    val cooldownActive = MutableStateFlow(false)
    private var cooldownJob: Job? = null

    fun clearUnreadErrors() {
        unreadErrorCount.value = 0
    }

    private var observersInitialized = false

    fun initObservers(context: Context) {
        if (observersInitialized) return
        observersInitialized = true
        val appContext = context.applicationContext
        scope.launch {
            running.collect { running ->
                try {
                    VpnWidgetProvider.updateAllWidgets(appContext)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        android.service.quicksettings.TileService.requestListeningState(
                            appContext,
                            android.content.ComponentName(appContext, QuickToggleTileService::class.java)
                        )
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    // Добавляем лог с Деплоя
    fun addDeployErrorLog(message: String) {
        val hash = message.hashCode().toString()
        updateLog("deploy_err_$hash", "[ДЕПЛОЙ] $message", 99, true)
    }

    fun addDeploySuccessLog(message: String) {
        val hash = message.hashCode().toString() + System.currentTimeMillis()
        updateLog("deploy_ok_$hash", message, 2, false)
    }

    private fun updateLog(key: String, message: String, priority: Int, isError: Boolean = false) {
        if (!isLoggingEnabled) return
        if (isError) {
            val list = logs.value
            if (list.none { it.key == key }) {
                unreadErrorCount.value++
            }
        }
        logs.update { currentList ->
            val current = currentList.toMutableList()
            val index = current.indexOfFirst { it.key == key }

            if (index != -1) {
                // Обновляем текст и счётчик НА МЕСТЕ
                val entry = current[index]
                current[index] = entry.copy(count = entry.count + 1, message = message, priority = priority, isError = isError)
            } else {
                // Новая запись
                current.add(LogEntry(key, message, 1, priority, isError))
            }

            // Сортировка: по приоритету (наименьший сверху), затем ошибки
            // Приоритеты: Основной=1, Капча=5, Готов=10, Статы=100, Ошибки=200
            val sorted = current.sortedWith(compareBy({ it.priority }, { if (it.isError) 1 else 0 }, { it.key }))

            // Лимит 100 записей
            if (sorted.size > 100) sorted.takeLast(100) else sorted
        }
    }

    fun start(context: Context, params: TunnelParams, isSwitching: Boolean = false) {
        scope.launch {
            startStopMutex.lock()
            try {
                if (running.value && !isSwitching) return@launch
        
                val appContext = context.applicationContext // Защита от Memory Leak
                
                if (!isSwitching) {
                    clearLogs()
                    config.value = null
                    stats.value = "Ожидание данных..."
                    floodCount = 0
                    mismatchCount = 0
                    refusedCount = 0
                    currentHashErrorCount = 0
                    wrapAuthTimeoutCount = 0
                    processStartedAtMs = 0L
                    lastActiveAtMs = 0L
                    activeHashIndex = 0
                    currentParams = params
                    lastContext = appContext
                    forceRegenerateUA = false
                    currentCaptchaMode = params.captchaMode
                    currentCaptchaSolveMethod = params.captchaSolveMethod
                }
                
                wgHelper = WireGuardHelper(appContext)

                val targetHash = if (activeHashIndex == 0) params.vkHashes else params.secondaryVkHash
                
                // Robust hash parsing: split by comma, newline, or whitespace
                val hashList = targetHash
                    .split(Regex("[,\\s\\n]+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(3)

                if (hashList.isEmpty()) {
                    updateLog("hash_error", "Ошибка: Хеш не указан", 99, true)
                    running.value = false
                    return@launch
                }
                if (params.connectionPassword.isBlank()) {
                    updateLog("password_error", "Ошибка: пароль подключения не указан", 99, true)
                    running.value = false
                    return@launch
                }

                val hashCount = hashList.size.coerceIn(1, 3)
                val totalWorkers = params.workersPerHash.coerceIn(1, 128)
                
                val hashMode = if (activeHashIndex == 0) "Основной" else "Запасной"
                updateLog("config_info", "[$hashMode] Хешей=$hashCount, Потоков=$totalWorkers", 1)

                val binaryPath = context.applicationInfo.nativeLibraryDir + "/libclient.so"
                val binaryFile = File(binaryPath)
                
                if (!binaryFile.exists()) {
                    updateLog("binary_error", "Ошибка: Бинарный файл не найден", 99, true)
                    return@launch
                }

                val cmd = mutableListOf(
                    binaryPath,
                    "-peer", params.peer,
                    "-vk", hashList.joinToString(","),
                    "-n", totalWorkers.toString(),
                    "-listen", "127.0.0.1:${params.port}"
                )

                if (params.fingerprint.isNotEmpty()) {
                    cmd.add("-fingerprint")
                    cmd.add(params.fingerprint)
                }

                if (params.clientIds.isNotEmpty()) {
                    cmd.add("-client-ids")
                    cmd.add(params.clientIds)
                }
                cmd.add("-vk-auth-mode")
                cmd.add(params.vkAuthMode)

                val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
                cmd.add("-device-id")
                cmd.add(androidId)

                cmd.add("-password")
                cmd.add(params.connectionPassword)

                cmd.add("-captcha-mode")
                cmd.add(params.captchaMode)

                val pb = ProcessBuilder(cmd)
                pb.directory(context.filesDir)
                pb.redirectErrorStream(true)
                
                val env = pb.environment()
                env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir

                process = pb.start()
                processStartedAtMs = System.currentTimeMillis()
                wrapAuthTimeoutCount = 0
                lastActiveAtMs = 0L
                running.value = true
                startLogReader()
                startWatchdog(appContext, params)

            } catch (e: Exception) {
                updateLog("critical_start_error", "Критическая ошибка запуска: ${e.message}", 99, true)
                e.printStackTrace()
                running.value = false
            } finally {
                startStopMutex.unlock()
            }
        }
    }

    private fun startLogReader() {
        readerJob = scope.launch {
            val reader = process?.inputStream?.bufferedReader() ?: return@launch
            var collectingConfig = false
            val configBuilder = StringBuilder()

            try {
                var lastResetTime = System.currentTimeMillis()

                reader.forEachLine { line ->
                    val now = System.currentTimeMillis()
                    if (now - lastResetTime > 60000) {
                        refusedCount = 0
                        floodCount = 0
                        mismatchCount = 0
                        currentHashErrorCount = 0
                        lastResetTime = now
                    }

                    val msgPrefixReplaced = line.replace(Regex("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\s"), "")
                    val lineTrim = msgPrefixReplaced.trim()

                    val isError = lineTrim.contains("Ошибка", true) || lineTrim.contains("error", true) || lineTrim.contains("FAIL", true) || lineTrim.contains("timeout", true) || lineTrim.contains("refused", true) || lineTrim.contains("FATAL_AUTH", true)

                    if (lineTrim.contains("FATAL_AUTH")) {
                        val isWrapHandshakeTimeout = lineTrim.contains("DTLS timeout", true) ||
                            lineTrim.contains("WRAP_AUTH_TIMEOUT", true)
                        if (isWrapHandshakeTimeout) {
                            if (activeWorkers.value > 0) {
                                wrapAuthTimeoutCount = 0
                                updateLog(
                                    "wrap_timeout_recovered",
                                    "[WRAP] Один поток не прошёл handshake, активных=${activeWorkers.value}; повторяем",
                                    50,
                                    true
                                )
                            } else {
                                wrapAuthTimeoutCount++
                                updateLog(
                                    "wrap_timeout_wait",
                                    "[WRAP] Handshake не подтвердился, проверяем пароль/сеть ($wrapAuthTimeoutCount)",
                                    50,
                                    true
                                )
                            }
                            return@forEachLine
                        }

                        val reason = when {
                            lineTrim.contains("неверный пароль") -> "Неверный пароль подключения"
                            lineTrim.contains("истёк") -> "Срок действия пароля истёк"
                            lineTrim.contains("другому устройству") -> "Пароль привязан к другому устройству"
                            else -> "Ошибка авторизации"
                        }
                        handleCriticalError("\uD83D\uDD12 $reason. Воркеры остановлены.")
                        return@forEachLine
                    }

                    if (lineTrim.contains("WRAP_AUTH_TIMEOUT", true)) {
                        if (activeWorkers.value > 0) {
                            wrapAuthTimeoutCount = 0
                            updateLog(
                                "wrap_timeout_recovered",
                                "[WRAP] Один поток не прошёл handshake, активных=${activeWorkers.value}; повторяем",
                                    50,
                                    true
                            )
                        } else {
                            wrapAuthTimeoutCount++
                            updateLog(
                                "wrap_timeout_wait",
                                "[WRAP] Handshake не подтвердился, проверяем пароль/сеть ($wrapAuthTimeoutCount)",
                                    50,
                                    true
                            )
                        }
                        return@forEachLine
                    }

                    if (lineTrim.startsWith("CAPTCHA_SOLVE|")) {
                        val payload = lineTrim.substringAfter("CAPTCHA_SOLVE|")
                        val parts = payload.split("|", limit = 3)
                        when (parts.size) {
                            3 -> {
                                val requestMode = parts[0]
                                val redirectUri = parts[1]
                                val sessionToken = parts[2]
                                scope.launch {
                                    handleCaptchaSolve(requestMode, redirectUri, sessionToken)
                                }
                            }
                            2 -> {
                                val redirectUri = parts[0]
                                val sessionToken = parts[1]
                                scope.launch {
                                    handleCaptchaSolve("selected", redirectUri, sessionToken)
                                }
                            }
                            else -> {
                                writeCaptchaResult("error:invalid CAPTCHA_SOLVE format")
                            }
                        }
                        return@forEachLine
                    }

                    if (isError) {
                        when {
                            lineTrim.contains("Flood control", true) -> {
                                floodCount++
                                if (floodCount >= 5) {
                                    handleCriticalError("Flood Control (ВК ограничил ваш IP). Попробуйте позже.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("ip mismatch", true) -> {
                                mismatchCount++
                                if (mismatchCount >= 5) {
                                    handleCriticalError("IP Mismatch (IP утерян). Попробуйте переподключиться.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("connection refused", true) || lineTrim.contains("timeout", true) -> {
                                refusedCount++
                                if (refusedCount >= 400) {
                                    handleCriticalError("Критическое отсутствие сети (400+ таймаутов). Отключение.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("9000") || lineTrim.contains("Call not found", true) -> {
                                currentHashErrorCount++
                                if (currentHashErrorCount >= 10) {
                                    handleHashError()
                                    return@forEachLine
                                }
                            }
                        }
                    }

                    if (lineTrim.contains("[СТАТИСТИКА]")) {
                        val msg = lineTrim.substringAfter("[СТАТИСТИКА]").trim()
                        stats.value = msg

                        val match = Regex("Активных:\\s*(\\d+)").find(msg)
                        if (match != null) {
                            val active = match.groupValues[1].toIntOrNull() ?: 0
                            activeWorkers.value = active
                            if (active > 0) {
                                lastActiveAtMs = now
                                wrapAuthTimeoutCount = 0
                            }
                        }

                        updateLog("stats", "[СТАТИСТИКА] $msg", 3, false)
                        return@forEachLine
                    }

                    when {
                        lineTrim.contains("[КАПЧА] AUTO:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] AUTO:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()

                            val isErr = text.contains("ошибка", true) ||
                                text.contains("timeout", true) ||
                                text.contains("не решил", true)
                            val stableKey = when {
                                text.contains("старт") -> "captcha_auto_1"
                                text.contains("Go v2") && text.contains("2 попыт") -> "captcha_auto_2"
                                text.contains("WBV Auto попытка") -> "captcha_auto_3"
                                text.contains("финальная") -> "captcha_auto_4"
                                text.contains("ручной WebView") -> "captcha_auto_5"
                                text.contains("решил") || text.contains("решила") -> "captcha_auto_done"
                                else -> "captcha_auto_${text.take(18).hashCode()}"
                            }
                            updateLog(stableKey, "[КАПЧА AUTO] $text", 5, isErr)
                        }

                        lineTrim.contains("[КАПЧА] RJS:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] RJS:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()
                            
                            val stableKey = when {
                                text.contains("Загрузка") || text.contains("fetch") -> "captcha_rjs_1"
                                text.contains("PoW") -> "captcha_rjs_2"
                                text.contains("осматривает") || text.contains("человек") -> "captcha_rjs_3"
                                text.contains("captchaNotRobot") || text.contains("Отправка") -> "captcha_rjs_4"
                                text.contains("endSession") -> "captcha_rjs_5"
                                text.contains("решена") -> "captcha_rjs_6"
                                else -> "captcha_rjs_${text.take(15).hashCode()}"
                            }
                            updateLog(stableKey, "[КАПЧА RJS] $text", 5, false)
                        }

                        lineTrim.contains("[КАПЧА] WBV:") -> {
                            var text = lineTrim.substringAfter("[КАПЧА] WBV:").trim()
                            text = text.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()
                            
                            val isErr = text.contains("Ошибка")
                            val stableKey = when {
                                text.contains("Запрос") -> "captcha_wv_step_2"
                                text.contains("Токен") -> "captcha_wv_step_5"
                                isErr -> "captcha_wv_err"
                                else -> "captcha_wv_go_other"
                            }
                            updateLog(stableKey, "[КАПЧА WBV] $text", 5, isErr)
                        }

                        lineTrim.contains("Старт") || lineTrim.contains("Ожидайте") ->
                            updateLog("creds_start", "[ВК] Получение учетных данных...", 2, false)
                        lineTrim.contains("Креды получены") ->
                            updateLog("creds_lifetime", lineTrim, 2, false)
                        lineTrim.contains("Креды OK") || lineTrim.contains("Первые креды") ->
                            updateLog("creds_ok", "[ВК] Учетные данные проверены ✓", 2, false)
                        lineTrim.contains("Решаю VK Smart Captcha") ->
                            updateLog("captcha_start", "[КАПЧА] Решение капчи...", 5, false)
                        lineTrim.contains("Smart Captcha решена") ->
                            updateLog("captcha_done", "[КАПЧА] Капча решена ✓", 5, false)
                        lineTrim.contains("капча не решена") || lineTrim.contains("ошибка решения капчи") ->
                            updateLog("captcha_failed", "[КАПЧА] Ошибка решения капчи", 5, true)
                        lineTrim.contains("[WRAP]") -> {
                            val text = lineTrim.substringAfter("[WRAP]").trim()
                            updateLog("wrap_status", "[WRAP] $text", 1, false)
                        }
                        lineTrim.contains("[TURN]") -> {
                            val text = lineTrim.substringAfter("[TURN]").trim()
                            val turnError = text.contains("Ошибка", true) ||
                                text.contains("не удалось", true) ||
                                text.contains("неполный ответ", true)
                            updateLog("turn_${text.take(32).hashCode()}", "[TURN] $text", 2, turnError)
                        }
                        lineTrim.contains("Relay:") ->
                            updateLog("dtls_start", "[DTLS] Рукопожатие (Handshake)...", 1, false)
                        lineTrim.contains("DTLS ОК") ->
                            updateLog("dtls_ok", "[DTLS] Соединение установлено ✓", 1, false)
                        lineTrim.contains("Активна ✓") ->
                            updateLog("ready", "[READY] Туннель готов к работе ✓", 2, false)
                        
                        isError -> {
                            val errorKey = when {
                                lineTrim.contains("lookup login.vk.ru", true) -> "err_vk_dns"
                                lineTrim.contains("connection refused") -> "err_conn_refused"
                                lineTrim.contains("timeout") -> "err_timeout"
                                lineTrim.contains("кредов") -> "err_creds"
                                lineTrim.contains("DTLS") -> "err_dtls"
                                else -> "general_error_" + lineTrim.take(15).hashCode()
                            }
                            val errorMessage = if (errorKey == "err_vk_dns") {
                                "[СЕТЬ] DNS до VK недоступен: login.vk.ru"
                            } else {
                                lineTrim
                            }
                            updateLog(errorKey, errorMessage, 99, true)
                        }
                    }

                    if (line.contains("╔") && line.contains("WireGuard")) {
                        collectingConfig = true
                        configBuilder.clear()
                        return@forEachLine
                    } else if (collectingConfig) {
                        if (line.contains("╚")) {
                            collectingConfig = false
                            val configStr = configBuilder.toString().trim()
                            config.value = configStr
                            
                            scope.launch(Dispatchers.Main) {
                                try {
                                    wgHelper?.startTunnel(configStr)
                                } catch (e: Exception) {
                                    updateLog("vpn_start_error", "Ошибка запуска VPN: ${e.readableMessage()}", 99, true)
                                }
                            }
                        } else if (line.contains("║")) {
                            val content = line.replace("║", "").trim()
                            if (content.isNotEmpty()) {
                                configBuilder.appendLine(content)
                            }
                        }
                        return@forEachLine
                    }
                }
            } catch (e: Exception) {
                if (!e.message.toString().contains("read interrupted by close", ignoreCase = true)) {
                    updateLog("sys_error", "Системная ошибка: ${e.message}", -1, true)
                }
            } finally {
                running.value = false
                process = null
            }
        }
    }

    private fun handleCriticalError(message: String) {
        updateLog("circuit_breaker", "[СТОП] $message", -1, true)
        stop()
    }

    private fun handleHashError() {
        val params = currentParams ?: return
        val context = lastContext ?: return

        currentHashErrorCount = 0
        forceRegenerateUA = true

        if (params.secondaryVkHash.isNotEmpty() && activeHashIndex == 0) {
            updateLog("hash_switch", "Основной хеш мертв. Переключение на запасной...", 50, true)
            activeHashIndex = 1
            stopOnlyProcess()
            start(context, params, isSwitching = true)
        } else {
            val msg = if (activeHashIndex == 1) "Запасной хеш тоже мертв. Отключение." else "Хеш умер, запасного нет. Отключение."
            handleCriticalError(msg)
        }
    }

    private fun startWatchdog(context: Context, params: TunnelParams) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var zeroWorkersSince = 0L
            delay(10_000)
            while (isActive && running.value) {
                val proc = process
                if (proc == null || !proc.isAlive) {
                    updateLog("watchdog", "⚠ Процесс упал. Перезапуск...", 50, true)
                    activeWorkers.value = 0
                    forceRegenerateUA = true
                    killProcess()
                    delay(2000)
                    if (running.value) {
                        start(context, params, isSwitching = true)
                    }
                    return@launch
                }

                val workers = activeWorkers.value
                if (workers <= 0) {
                    if (zeroWorkersSince == 0L) {
                        zeroWorkersSince = System.currentTimeMillis()
                    } else if (
                        wrapAuthTimeoutCount >= 3 &&
                        processStartedAtMs > 0L &&
                        System.currentTimeMillis() - processStartedAtMs > 30_000 &&
                        lastActiveAtMs == 0L &&
                        !ManlCaptchaWebViewManager.isCaptchaPending
                    ) {
                        handleCriticalError("\uD83D\uDD12 Неверный пароль подключения или несовместимый WRAP. Воркеры остановлены.")
                        return@launch
                    } else if (System.currentTimeMillis() - zeroWorkersSince > 90_000 && !ManlCaptchaWebViewManager.isCaptchaPending) {
                        updateLog("watchdog", "⚠ Зомби-процесс (0 воркеров 90с). Перезапуск...", 50, true)
                        forceRegenerateUA = true
                        killProcess()
                        delay(2000)
                        if (running.value) {
                            start(context, params, isSwitching = true)
                        }
                        return@launch
                    }
                } else {
                    zeroWorkersSince = 0L
                }

                delay(5_000)
            }
        }
    }

    fun restartTransport() {
        val params = currentParams ?: return
        val context = lastContext ?: return
        updateLog("network_restart", "[СЕТЬ] Перезапуск транспорта из-за смены сети...", 50, false)
        killProcess()
        scope.launch {
            delay(1500)
            start(context, params, isSwitching = true)
        }
    }

    fun pause() {
        if (!running.value) return
        killProcess()
        activeWorkers.value = 0
    }

    fun resume() {
        if (currentParams != null && lastContext != null) {
            scope.launch {
                start(lastContext!!, currentParams!!, isSwitching = true)
            }
        }
    }

    private fun killProcess() {
        watchdogJob?.cancel()
        readerJob?.cancel()
        val proc = process
        process = null
        if (proc != null) {
            try { proc.destroy() } catch (_: Exception) {}
            try { proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            if (proc.isAlive) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
                try { proc.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            }
        }
    }

    private fun stopOnlyProcess() {
        killProcess()
        running.value = false
    }

    fun stop() {
        scope.launch {
            startStopMutex.lock()
            try {
                withContext(Dispatchers.Main) {
                    wgHelper?.stopTunnel()
                }
                killProcess()
                running.value = false
                activeWorkers.value = 0
                currentParams = null
                ManlCaptchaWebViewManager.cancelCaptcha()
            } finally {
                startStopMutex.unlock()
            }
        }
    }

    suspend fun stopAndWait() {
        startStopMutex.lock()
        try {
            withContext(Dispatchers.Main) {
                wgHelper?.stopTunnel()
            }
            withContext(Dispatchers.IO) {
                killProcess()
                running.value = false
                activeWorkers.value = 0
                currentParams = null
                ManlCaptchaWebViewManager.cancelCaptcha()
            }
        } finally {
            startStopMutex.unlock()
        }
    }

    fun reloadWireGuard() {
        if (running.value) {
            scope.launch {
                wgHelper?.reloadTunnel()
            }
        }
    }

    private suspend fun handleCaptchaSolve(requestMode: String, redirectUri: String, sessionToken: String) {
        val ctx = lastContext ?: run {
            writeCaptchaResult("error:context is null")
            return
        }
        val mode = requestMode.lowercase()

        try {
            val token = when (mode) {
                "auto" -> solveSingleAutoWebViewCaptcha(redirectUri, sessionToken)
                "manual" -> {
                    updateLog("captcha_wv_step_1", "[КАПЧА WBV] Создание ручного WebView...", 5, false)
                    ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
                else -> {
                    if (currentCaptchaSolveMethod == "auto") {
                        solveAutoWebViewCaptcha(ctx, redirectUri, sessionToken)
                    } else {
                        updateLog("captcha_wv_step_1", "[КАПЧА WBV] Создание ручного WebView...", 5, false)
                        ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                    }
                }
            }
            updateLog("captcha_wv_step_4", "[КАПЧА WBV] Капча решена ✓", 5, false)
            writeCaptchaResult(token)
        } catch (e: IllegalStateException) {
            val errorMsg = e.message ?: "WV state error"
            updateLog("captcha_wv_err", "[КАПЧА WBV] $errorMsg", 5, true)
            writeCaptchaResult("error:$errorMsg")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            updateLog("captcha_wv_err", "[КАПЧА WBV] Таймаут WebView", 5, true)
            writeCaptchaResult("error:timeout")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            updateLog("captcha_wv_err", "[КАПЧА WBV] Отменено", 5, true)
            writeCaptchaResult("error:cancelled")
        } catch (e: Exception) {
            val errorMsg = e.message ?: "${e::class.simpleName}"
            if (errorMsg != "tunnel stopped") {
                updateLog("captcha_wv_err", "[КАПЧА WBV] Ошибка — $errorMsg", 5, true)
            }
            writeCaptchaResult("error:$errorMsg")
        }

        updateLog("captcha_wv_step_6", "[КАПЧА WBV] WebView уничтожен", 5, false)
    }

    private suspend fun solveSingleAutoWebViewCaptcha(
        redirectUri: String,
        sessionToken: String
    ): String {
        updateLog("captcha_wv_step_1", "[КАПЧА WBV] Авто WebView попытка 10с...", 5, false)
        return CaptchaWebViewManager.solveCaptchaAsync(redirectUri, sessionToken) { step ->
            updateLog("captcha_wv_auto_step", "[КАПЧА WBV] $step", 5, false)
        }
    }

    private suspend fun solveAutoWebViewCaptcha(
        ctx: Context,
        redirectUri: String,
        sessionToken: String
    ): String {
        for (attempt in 1..2) {
            updateLog("captcha_wv_step_1", "[КАПЧА WBV] Авто WebView попытка $attempt/2...", 5, false)
            try {
                return CaptchaWebViewManager.solveCaptchaAsync(redirectUri, sessionToken) { step ->
                    updateLog("captcha_wv_auto_step", "[КАПЧА WBV] $step", 5, false)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                updateLog("captcha_wv_timeout_$attempt", "[КАПЧА WBV] Авто таймаут 10с ($attempt/2)", 5, attempt == 2)
                if (attempt == 2) {
                    updateLog("captcha_wv_fallback", "[КАПЧА WBV] 2 таймаута авто, открыт ручной WebView", 5, false)
                    return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
            } catch (e: IllegalStateException) {
                if (e.message == CaptchaWebViewManager.ERROR_SLIDER_DETECTED) {
                    updateLog("captcha_wv_fallback", "[КАПЧА WBV] Обнаружен слайдер, открыт ручной WebView", 5, false)
                    return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
                }
                throw e
            }
        }
        return ManlCaptchaWebViewManager.solveCaptchaAsync(ctx, redirectUri, sessionToken)
    }

    private fun writeCaptchaResult(result: String) {
        val proc = process
        if (proc == null || !proc.isAlive) return
        try {
            val line = "CAPTCHA_RESULT|$result\n"
            proc.outputStream.write(line.toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
        } catch (e: Exception) {
            updateLog("captcha_write_err", "[КАПЧА] Ошибка записи: ${e.message}", 200, true)
        }
    }

    fun clearLogs() {
        logs.value = emptyList()
        if (!running.value) {
            activeWorkers.value = 0
        }
    }

    fun startCooldown(millis: Long) {
        cooldownJob?.cancel()
        cooldownActive.value = true
        cooldownJob = scope.launch(Dispatchers.Main) {
            delay(millis)
            cooldownActive.value = false
        }
    }

    private fun Throwable.readableMessage(): String {
        val text = message ?: localizedMessage
        return if (text.isNullOrBlank()) this::class.java.simpleName else "${this::class.java.simpleName}: $text"
    }
}

data class TunnelParams(
    val peer: String,
    val vkHashes: String,
    val secondaryVkHash: String = "",
    val workersPerHash: Int,
    val port: Int,
    val sni: String = "",
    val connectionPassword: String = "",
    val protocol: String = "udp",
    val vkAuthMode: String = "vkcalls",
    val captchaMode: String = "auto",
    val captchaSolveMethod: String = "auto",
    val fingerprint: String = "chrome",
    val clientIds: String = "6287487,8202606"
)
