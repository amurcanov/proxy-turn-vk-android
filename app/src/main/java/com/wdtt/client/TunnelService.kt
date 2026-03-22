package com.wdtt.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TunnelService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var updateJob: Job? = null
    
    // Network Monitoring
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkChangeTime = 0L
    private val activeNetworks = mutableSetOf<Network>()
    private var isTunnelPaused = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Сразу берем лок при создании
        acquireWakeLock()
        setupNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            restoreTunnel()
            return START_STICKY
        }

        when (intent.action) {
            "START" -> {
                val notification = createNotification("Запуск...")
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(1, notification)
                }

                val params = TunnelParams(
                    peer = intent.getStringExtra("peer") ?: "",
                    vkHashes = intent.getStringExtra("vk_hashes") ?: "",
                    secondaryVkHash = intent.getStringExtra("secondary_vk_hash") ?: "",
                    workersPerHash = intent.getIntExtra("workers_per_hash", 16),
                    port = intent.getIntExtra("port", 9000),
                    sni = intent.getStringExtra("sni") ?: "",
                    connectionPassword = intent.getStringExtra("connection_password") ?: ""
                )
                startTunnel(params)
            }
            "STOP" -> stopTunnel()
            "DEPLOY_START" -> {
                val notification = createNotification("Установка на сервер...", "DEPLOY_CANCEL", "Отменить")
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(1, notification)
                }
                acquireWakeLock()
            }
            "DEPLOY_CANCEL" -> {
                com.wdtt.client.DeployManager.writeError("[!] ❌ Установка отменена пользователем")
                com.wdtt.client.DeployManager.stopDeploy("error: Отменена пользователем")
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            "DEPLOY_STOP" -> {
                if (!TunnelManager.running.value) {
                    stopTunnel()
                } else {
                    updateNotification(TunnelManager.stats.value)
                }
            }
        }
        return START_STICKY
    }

    private fun restoreTunnel() {
        val notification = createNotification("Восстановление соединения...")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
        
        val appContext = applicationContext
        TunnelManager.scope.launch {
            try {
                val store = SettingsStore(appContext)
                val params = TunnelParams(
                    peer = store.peer.first(),
                    vkHashes = store.vkHashes.first(),
                    secondaryVkHash = store.secondaryVkHash.first(),
                    workersPerHash = store.workersPerHash.first(),
                    port = store.listenPort.first(),
                    sni = store.sni.first(),
                    connectionPassword = store.connectionPassword.first()
                )
                if (params.peer.isNotEmpty() && params.vkHashes.isNotEmpty()) {
                    launch(Dispatchers.Main) {
                        startTunnel(params)
                    }
                } else {
                    launch(Dispatchers.Main) {
                        stopTunnel()
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    stopTunnel()
                }
            }
        }
    }

    private fun startTunnel(params: TunnelParams) {
        updateNotification("Подключение...")
        acquireWakeLock()
        acquireWifiLock()
        TunnelManager.start(this, params)
        startStatsUpdater()
    }

    private fun stopTunnel() {
        updateJob?.cancel()
        TunnelManager.stop()
        releaseWakeLock()
        releaseWifiLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setupNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        activeNetworks.clear()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val wasEmpty = activeNetworks.isEmpty()
                activeNetworks.add(network)
                if (wasEmpty) {
                    if (isTunnelPaused) {
                        isTunnelPaused = false
                        Log.d("TunnelService", "Сеть появилась, возобновляем туннель")
                        TunnelManager.resume()
                        updateNotification("Подключение...")
                    } else {
                        handleNetworkChange()
                    }
                } else {
                    handleNetworkChange()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                activeNetworks.remove(network)
                if (activeNetworks.isEmpty() && TunnelManager.running.value && !isTunnelPaused) {
                    isTunnelPaused = true
                    Log.d("TunnelService", "Сеть потеряна, приостанавливаем туннель")
                    TunnelManager.pause()
                    updateNotification("Ожидание сети (Фоновый сон)")
                }
            }
        }

        // ВАЖНО: Слушаем только реальные (не VPN) сети с доступом в интернет.
        // Иначе интерфейс VPN (tun0) считается активной сетью, и при "Режиме полёта" activeNetworks не падает до 0.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
            
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }
    
    private fun handleNetworkChange() {
        val now = System.currentTimeMillis()
        if (now - lastNetworkChangeTime < 5000) return
        lastNetworkChangeTime = now

        if (TunnelManager.running.value && !isTunnelPaused) {
            Log.d("TunnelService", "Сеть изменилась, мягкий перезапуск Go-клиента")
            TunnelManager.restartTransport()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "wdtt:tunnel_cpu"
        ).apply { 
            setReferenceCounted(false)
            acquire() 
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        
        // Используем WIFI_MODE_FULL_LOW_LATENCY для Android 10+, 
        // это предотвращает отключение радиомодуля при выключенном экране
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        
        wifiLock = wm.createWifiLock(mode, "wdtt:wifi_perf").apply { 
            setReferenceCounted(false)
            acquire() 
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun releaseWifiLock() {
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        wifiLock = null
    }

    private fun startStatsUpdater() {
        updateJob?.cancel()
        updateJob = TunnelManager.scope.launch(Dispatchers.Main) {
            delay(1000)
            while (isActive) {
                if (!TunnelManager.running.value && !isTunnelPaused) {
                    // Туннель полностью остановлен (не на паузе) — убиваем сервис
                    stopSelf()
                    break
                }
                if (!isTunnelPaused) {
                    val stats = TunnelManager.stats.value
                    updateNotification(stats)
                }
                delay(2000)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "wdtt_tunnel_v3",
            "WDTT Туннель",
            NotificationManager.IMPORTANCE_DEFAULT // Возвращаем DEFAULT, чтобы было на локскрине
        ).apply {
            description = "Уведомление о работе туннеля"
            setShowBadge(false)
            // ВАЖНО: Разрешаем показывать на экране блокировки
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(text: String, actionName: String = "STOP", actionTitle: String = "Отключить"): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = PendingIntent.getService(
            this, if (actionName == "STOP") 1 else 2,
            Intent(this, TunnelService::class.java).apply { action = actionName },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, "wdtt_tunnel_v3")
            .setContentTitle("WDTT Client")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, actionTitle, stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // ВАЖНО: Делаем уведомление публичным (видимым на локскрине)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Категория SERVICE помогает системе понять важность
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true) // Не издавать звук и не будить экран при обновлении статистики!
            .setSilent(true) // Делаем тихим само уведомление
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        getSystemService(NotificationManager::class.java).notify(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            connectivityManager?.unregisterNetworkCallback(it)
        }
        stopTunnel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}