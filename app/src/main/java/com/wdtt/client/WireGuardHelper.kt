package com.wdtt.client

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class WireGuardHelper(context: Context) {
    private val appContext = context.applicationContext
    private val backend = (appContext as WdttApplication).backend
    private var tunnel: WgTunnel? = null

    class WgTunnel : Tunnel {
        override fun getName() = "wdtt"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    suspend fun startTunnel(configString: String) = withContext(Dispatchers.IO) {
        try {
            val parsedConfig = Config.parse(ByteArrayInputStream(configString.toByteArray(Charsets.UTF_8)))
            
            val builder = Interface.Builder()
                .parseAddresses(parsedConfig.`interface`.addresses.joinToString(", ") { it.toString() })
            
            if (parsedConfig.`interface`.dnsServers.isNotEmpty()) {
                builder.parseDnsServers(parsedConfig.`interface`.dnsServers.joinToString(", ") { it.hostAddress ?: "" })
            }
            if (parsedConfig.`interface`.listenPort.isPresent) {
                builder.parseListenPort(parsedConfig.`interface`.listenPort.get().toString())
            }
            if (parsedConfig.`interface`.mtu.isPresent) {
                // Принудительно 1280 для стабильности на мобильных сетях
                builder.parseMtu("1280")
            } else {
                builder.parseMtu("1280")
            }
            builder.parsePrivateKey(parsedConfig.`interface`.keyPair.privateKey.toBase64())

            // Exclude our app and VK apps (Defaults)
            val excludedApps = mutableSetOf(appContext.packageName, "com.vkontakte.android", "com.vk.calls")
            
            // Add user selected exceptions
            val settingsStore = SettingsStore(appContext)
            val savedExcluded = settingsStore.excludedApps.first()
            if (savedExcluded.isNotEmpty()) {
                savedExcluded.split(",").forEach { 
                    excludedApps.add(it)
                }
            }

            builder.excludeApplications(excludedApps)

            val newInterface = builder.build()

            val peerBuilder = Peer.Builder()
            parsedConfig.peers.firstOrNull()?.let { peer ->
                peerBuilder.parsePublicKey(peer.publicKey.toBase64())
                if (peer.preSharedKey.isPresent) peerBuilder.parsePreSharedKey(peer.preSharedKey.get().toBase64())
                if (peer.endpoint.isPresent) peerBuilder.parseEndpoint(peer.endpoint.get().toString())
                if (peer.persistentKeepalive.isPresent) peerBuilder.parsePersistentKeepalive(peer.persistentKeepalive.get().toString())
            }
            // Override AllowedIPs
            peerBuilder.parseAllowedIPs("0.0.0.0/0")
            
            val finalConfig = Config.Builder()
                .setInterface(newInterface)
                .addPeer(peerBuilder.build())
                .build()

            tunnel = WgTunnel()
            backend.setState(tunnel!!, Tunnel.State.UP, finalConfig)
            Log.d("WG", "WireGuard tunnel started successfully")
        } catch (e: Exception) {
            Log.e("WG", "Failed to start WireGuard: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    suspend fun reloadTunnel() = withContext(Dispatchers.IO) {
        val currentTunnel = tunnel ?: return@withContext
        try {
            val configFlow = TunnelManager.config.first() ?: return@withContext
            // Перезапускаем туннель с новым конфигом и свежими настройками исключений
            backend.setState(currentTunnel, Tunnel.State.DOWN, null)
            startTunnel(configFlow)
            Log.d("WG", "WireGuard tunnel reloaded for new exceptions")
        } catch (e: Exception) {
            Log.e("WG", "Failed to reload WireGuard: ${e.message}")
        }
    }

    suspend fun stopTunnel() = withContext(Dispatchers.IO) {
        try {
            tunnel?.let {
                backend.setState(it, Tunnel.State.DOWN, null)
                tunnel = null
                Log.d("WG", "WireGuard tunnel stopped")
            }
        } catch (e: Exception) {
            Log.e("WG", "Failed to stop WireGuard: ${e.message}")
        }
    }
}
