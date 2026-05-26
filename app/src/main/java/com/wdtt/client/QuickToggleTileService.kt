package com.wdtt.client

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class QuickToggleTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (TunnelManager.running.value) {
            startService(Intent(this, TunnelService::class.java).apply { action = "STOP" })
            qsTile?.state = Tile.STATE_INACTIVE
            qsTile?.updateTile()
            return
        }

        if (VpnService.prepare(this) != null) {
            Toast.makeText(this, "Откройте WDTT и выдайте VPN-разрешение", Toast.LENGTH_LONG).show()
            openMainActivity()
            updateTile()
            return
        }

        qsTile?.state = Tile.STATE_UNAVAILABLE
        qsTile?.updateTile()
        scope.launch {
            val intent = buildStartIntent()
            if (intent == null) {
                Toast.makeText(this@QuickToggleTileService, "Заполните настройки подключения в WDTT", Toast.LENGTH_LONG).show()
                openMainActivity()
                updateTile()
                return@launch
            }
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            qsTile?.state = Tile.STATE_ACTIVE
            qsTile?.updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun buildStartIntent(): Intent? {
        val store = SettingsStore(applicationContext)
        val basePeer = store.peer.first()
        val hashes = store.vkHashes.first()
        val password = store.connectionPassword.first()
        if (basePeer.isBlank() || hashes.isBlank() || password.isBlank()) return null

        val manualPortsEnabled = store.manualPortsEnabled.first()
        val serverDtlsPort = if (manualPortsEnabled) store.serverDtlsPort.first() else 56000
        val localPort = if (manualPortsEnabled) store.listenPort.first() else 9000
        val peerWithPort = if (basePeer.contains(":")) basePeer else "$basePeer:$serverDtlsPort"

        return Intent(this, TunnelService::class.java).apply {
            action = "START"
            putExtra("peer", peerWithPort)
            putExtra("vk_hashes", hashes)
            putExtra("secondary_vk_hash", store.secondaryVkHash.first())
            putExtra("workers_per_hash", store.workersPerHash.first())
            putExtra("port", localPort)
            putExtra("sni", store.sni.first())
            putExtra("connection_password", password)
            putExtra("captcha_mode", sanitizeCaptchaMode(store.captchaMode.first()))
            putExtra("captcha_solve_method", store.captchaSolveMethod.first())
        }
    }

    private fun updateTile() {
        qsTile?.apply {
            label = "WDTT"
            state = if (TunnelManager.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    private fun openMainActivity() {
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
