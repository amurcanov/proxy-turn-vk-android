package com.wdtt.client

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VpnWidgetProvider : AppWidgetProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val ACTION_WIDGET_TOGGLE = "com.wdtt.client.ACTION_WIDGET_TOGGLE"

        fun updateAllWidgets(context: Context) {
            runCatching {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisWidget = ComponentName(context, VpnWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                if (appWidgetIds.isNotEmpty()) {
                    val intent = Intent(context, VpnWidgetProvider::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                    }
                    context.sendBroadcast(intent)
                }
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val running = TunnelManager.running.value
        for (appWidgetId in appWidgetIds) {
            updateWidgetState(context, appWidgetManager, appWidgetId, running)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_TOGGLE) {
            runCatching {
                if (TunnelManager.running.value) {
                    // Останавливаем туннель
                    val stopIntent = Intent(context, TunnelService::class.java).apply { action = "STOP" }
                    context.startService(stopIntent)
                    updateAllWidgets(context)
                    return
                }

                if (VpnService.prepare(context) != null) {
                    Toast.makeText(context, "Откройте WDTT и выдайте VPN-разрешение", Toast.LENGTH_LONG).show()
                    openMainActivity(context)
                    return
                }

                // Запуск туннеля в фоне
                scope.launch {
                    try {
                        val startIntent = buildStartIntent(context)
                        if (startIntent == null) {
                            Toast.makeText(context, "Заполните настройки подключения в WDTT", Toast.LENGTH_LONG).show()
                            openMainActivity(context)
                            return@launch
                        }

                        if (Build.VERSION.SDK_INT >= 26) {
                            context.startForegroundService(startIntent)
                        } else {
                            context.startService(startIntent)
                        }
                    } catch (e: Exception) {
                        Log.e("VpnWidget", "Failed to start tunnel from widget", e)
                        Toast.makeText(context, "Ошибка запуска: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { e ->
                Log.e("VpnWidget", "Error handling widget click", e)
            }
        }
    }

    private fun updateWidgetState(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        running: Boolean
    ) {
        val views = RemoteViews(context.packageName, R.layout.vpn_widget)

        // Обновляем текст статуса и неоновую иконку кнопки
        if (running) {
            views.setTextViewText(R.id.widget_status, "Подключено")
            views.setTextColor(R.id.widget_status, 0xFF00E5FF.toInt()) // Неоновый голубой
            views.setInt(R.id.widget_toggle_btn, "setBackgroundResource", R.drawable.bg_widget_button_active)
        } else {
            views.setTextViewText(R.id.widget_status, "Отключено")
            views.setTextColor(R.id.widget_status, 0xFF888888.toInt()) // Матовый серый
            views.setInt(R.id.widget_toggle_btn, "setBackgroundResource", R.drawable.bg_widget_button_inactive)
        }

        // Клик по всей карточке открывает приложение
        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_container, openPendingIntent)

        // Клик по кнопке запускает/останавливает VPN
        val toggleIntent = Intent(context, VpnWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_TOGGLE
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId + 1000,
            toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_toggle_btn, togglePendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private suspend fun buildStartIntent(context: Context): Intent? {
        val store = SettingsStore(context.applicationContext)
        val basePeer = store.peer.first()
        val hashes = store.vkHashes.first()
        val password = store.connectionPassword.first()
        if (basePeer.isBlank() || hashes.isBlank() || password.isBlank()) return null

        val manualPortsEnabled = store.manualPortsEnabled.first()
        val serverDtlsPort = if (manualPortsEnabled) store.serverDtlsPort.first() else 56000
        val localPort = if (manualPortsEnabled) store.listenPort.first() else 9000
        val peerWithPort = if (basePeer.contains(":")) basePeer else "$basePeer:$serverDtlsPort"

        return Intent(context, TunnelService::class.java).apply {
            action = "START"
            putExtra("peer", peerWithPort)
            putExtra("vk_hashes", hashes)
            putExtra("secondary_vk_hash", store.secondaryVkHash.first())
            putExtra("workers_per_hash", store.workersPerHash.first())
            putExtra("port", localPort)
            putExtra("sni", store.sni.first())
            putExtra("connection_password", store.connectionPassword.first())
            putExtra("protocol", store.protocol.first())
            putExtra("vk_auth_mode", store.vkAuthMode.first())
            putExtra("captcha_mode", store.captchaMode.first())
            putExtra("captcha_solve_method", store.captchaSolveMethod.first())
            putExtra("fingerprint", store.selectedFingerprint.first())
            putExtra("client_ids", store.activeClientIds.first())
        }
    }

    private fun openMainActivity(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            val pendingIntent = PendingIntent.getActivity(
                context,
                200,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            pendingIntent.send()
        }.onFailure {
            context.startActivity(intent)
        }
    }

    private fun sanitizeCaptchaMode(mode: String?): String {
        return when (mode?.lowercase()) {
            "auto" -> "auto"
            "rjs" -> "rjs"
            "wv" -> "wv"
            else -> "auto"
        }
    }
}
