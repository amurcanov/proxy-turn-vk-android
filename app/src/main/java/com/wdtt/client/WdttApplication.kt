package com.wdtt.client

import android.app.Application
import android.content.Context
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WdttApplication : Application() {
    @Volatile
    private var backendInstance: GoBackend? = null

    val backend: GoBackend
        get() = getBackend(this)

    override fun onCreate() {
        super.onCreate()
        DeployManager.init(this)
        
        // Очищаем фантомный VPN при холодном старте приложения (например, после перезагрузки телефона).
        // Если телефон перезагрузился, система Android пытается сама восстановить VpnService, 
        // что приводит к фантомному ключу без интернета. Этот код мгновенно сбрасывает статус в DOWN.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val backend = getBackend(this@WdttApplication)
                val tunnel = WireGuardHelper.WgTunnel()
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                Log.d("WdttApp", "Успешно очищен фантомный VPN при холодном старте")
            }.onFailure {
                Log.w("WdttApp", "Не удалось очистить фантомный VPN: ${it.message}")
            }
        }

        // Реактивно обновляем все виджеты на домашнем экране при изменении состояния туннеля
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                TunnelManager.running.collect {
                    VpnWidgetProvider.updateAllWidgets(this@WdttApplication)
                }
            } catch (e: Exception) {
                Log.e("WdttApp", "Не удалось обновить виджеты: ${e.message}")
            }
        }

        // Реактивно отслеживаем флаг логирования
        val settingsStore = SettingsStore(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                settingsStore.loggingEnabled.collect { enabled ->
                    TunnelManager.isLoggingEnabled = enabled
                }
            } catch (e: Exception) {
                Log.e("WdttApp", "Не удалось отслеживать флаг логирования: ${e.message}")
            }
        }
    }

    fun getBackend(context: Context): GoBackend {
        return backendInstance ?: synchronized(this) {
            backendInstance ?: GoBackend(context.applicationContext).also { backendInstance = it }
        }
    }
}
