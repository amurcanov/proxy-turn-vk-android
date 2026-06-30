package com.wdtt.client

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class QuickToggleTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        // Реактивно подписываемся на статус активности туннеля.
        // Плитка будет строго отражать РЕАЛЬНОЕ состояние туннеля на 100% без рассинхронизаций.
        stateJob?.cancel()
        stateJob = scope.launch {
            try {
                TunnelManager.running.collect { running ->
                    updateTile(running)
                }
            } catch (e: Exception) {
                Log.e("QuickToggleTile", "Error collecting running state", e)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        runCatching {
            if (TunnelManager.running.value) {
                // Если запущен — останавливаем. Состояние плитки изменится автоматически,
                // когда TunnelManager остановит процессы и обновит статус running в false.
                val stopIntent = Intent(this, TunnelService::class.java).apply { action = "STOP" }
                startService(stopIntent)
                return
            }

            // Проверяем наличие выданного разрешения VPN перед стартом
            if (VpnService.prepare(this) != null) {
                Toast.makeText(this, "Откройте WDTT и выдайте VPN-разрешение", Toast.LENGTH_LONG).show()
                openMainActivity()
                return
            }

            // Запускаем старт туннеля в фоне
            scope.launch {
                try {
                    val intent = buildStartIntent()
                    if (intent == null) {
                        Toast.makeText(this@QuickToggleTileService, "Заполните настройки подключения в WDTT", Toast.LENGTH_LONG).show()
                        openMainActivity()
                        return@launch
                    }

                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                } catch (e: Exception) {
                    Log.e("QuickToggleTile", "Failed to start tunnel via QS tile", e)
                    Toast.makeText(this@QuickToggleTileService, "Ошибка запуска: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }.onFailure { e ->
            Log.e("QuickToggleTile", "Crash prevented in onClick", e)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun buildStartIntent(): Intent? {
        return runCatching {
            val store = SettingsStore(applicationContext)
            val basePeer = store.peer.first()
            val hashes = store.vkHashes.first()
            val password = store.connectionPassword.first()
            if (basePeer.isBlank() || hashes.isBlank() || password.isBlank()) return null

            val manualPortsEnabled = store.manualPortsEnabled.first()
            val serverDtlsPort = if (manualPortsEnabled) store.serverDtlsPort.first() else 56000
            val localPort = if (manualPortsEnabled) store.listenPort.first() else 9000
            val peerWithPort = if (basePeer.contains(":")) basePeer else "$basePeer:$serverDtlsPort"

            Intent(this, TunnelService::class.java).apply {
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
        }.getOrNull()
    }

    private fun updateTile(running: Boolean) {
        runCatching {
            qsTile?.apply {
                label = "WDTT"
                icon = Icon.createWithResource(this@QuickToggleTileService, R.drawable.ic_tile_logo_w)
                state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= 29) {
                    subtitle = if (running) "Подключено" else "Отключено"
                }
                updateTile()
            }
        }.onFailure { e ->
            Log.e("QuickToggleTile", "Failed to update QS tile state", e)
        }
    }

    private fun openMainActivity() {
        runCatching {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    100,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }.onFailure { e ->
            Log.e("QuickToggleTile", "Failed to open MainActivity", e)
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
