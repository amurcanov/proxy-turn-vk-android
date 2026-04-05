package com.wdtt.client

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    companion object {
        private val Context.dataStore by preferencesDataStore("settings")
        private val PEER = stringPreferencesKey("peer")
        private val VK_HASHES = stringPreferencesKey("vk_hashes")
        private val SECONDARY_VK_HASH = stringPreferencesKey("secondary_vk_hash")
        private val WORKERS_PER_HASH = intPreferencesKey("workers_per_hash")
        private val PROTOCOL = stringPreferencesKey("protocol")
        private val LISTEN_PORT = intPreferencesKey("listen_port")
        private val SNI = stringPreferencesKey("sni")
        private val NO_DTLS = booleanPreferencesKey("no_dtls")
        private val NO_DNS = booleanPreferencesKey("no_dns")

        private val USER_AGENT = stringPreferencesKey("user_agent")

        private val DEPLOY_IP = stringPreferencesKey("deploy_ip")
        private val DEPLOY_LOGIN = stringPreferencesKey("deploy_login")
        private val DEPLOY_PASSWORD = stringPreferencesKey("deploy_password")
        private val DEPLOY_SSH_PORT = stringPreferencesKey("deploy_ssh_port")
        private val EXCLUDED_APPS = stringPreferencesKey("excluded_apps")
        
        private val DETAILED_LOGS = booleanPreferencesKey("detailed_logs")
        
        // ═══ Пароли и Управление ═══
        private val CONNECTION_PASSWORD = stringPreferencesKey("connection_password")
        private val DEPLOY_MAIN_PASSWORD = stringPreferencesKey("deploy_main_password")
        private val DEPLOY_ADMIN_ID = stringPreferencesKey("deploy_admin_id")
        private val DEPLOY_BOT_TOKEN = stringPreferencesKey("deploy_bot_token")

        // ═══ Proxy Mode ═══
        private val PROXY_MODE = stringPreferencesKey("proxy_mode") // "tun" or "socks5"
        private val PROXY_HOST = stringPreferencesKey("proxy_host")
        private val PROXY_PORT = intPreferencesKey("proxy_port")

        // ═══ Captcha Solve Mode ═══
        private val CAPTCHA_MODE = stringPreferencesKey("captcha_mode") // "webview" or "reverse_js"
        
        // ═══ VPN Exclusions Mode ═══
        private val IS_WHITELIST = booleanPreferencesKey("is_whitelist")
    }

    private val dataStore = appContext.dataStore

    val peer: Flow<String> = dataStore.data.map { it[PEER] ?: "" }
    val vkHashes: Flow<String> = dataStore.data.map { it[VK_HASHES] ?: "" }
    val secondaryVkHash: Flow<String> = dataStore.data.map { it[SECONDARY_VK_HASH] ?: "" }
    val workersPerHash: Flow<Int> = dataStore.data.map { it[WORKERS_PER_HASH] ?: 16 }
    val protocol: Flow<String> = dataStore.data.map { it[PROTOCOL] ?: "udp" }
    val listenPort: Flow<Int> = dataStore.data.map { it[LISTEN_PORT] ?: 9000 }
    val sni: Flow<String> = dataStore.data.map { it[SNI] ?: "" }
    val noDns: Flow<Boolean> = dataStore.data.map { it[NO_DNS] ?: false }
    val userAgent: Flow<String> = dataStore.data.map { it[USER_AGENT] ?: "" }

    val deployIp: Flow<String> = dataStore.data.map { it[DEPLOY_IP] ?: "" }
    val deployLogin: Flow<String> = dataStore.data.map { it[DEPLOY_LOGIN] ?: "" }
    val deployPassword: Flow<String> = dataStore.data.map { it[DEPLOY_PASSWORD] ?: "" }
    val deploySshPort: Flow<String> = dataStore.data.map { it[DEPLOY_SSH_PORT] ?: "" }
    val excludedApps: Flow<String> = dataStore.data.map { it[EXCLUDED_APPS] ?: "" }
    
    val detailedLogs: Flow<Boolean> = dataStore.data.map { it[DETAILED_LOGS] ?: false }
    
    // ═══ Пароли и Управление ═══
    val connectionPassword: Flow<String> = dataStore.data.map { it[CONNECTION_PASSWORD] ?: "" }
    val deployMainPassword: Flow<String> = dataStore.data.map { it[DEPLOY_MAIN_PASSWORD] ?: "" }
    val deployAdminId: Flow<String> = dataStore.data.map { it[DEPLOY_ADMIN_ID] ?: "" }
    val deployBotToken: Flow<String> = dataStore.data.map { it[DEPLOY_BOT_TOKEN] ?: "" }

    // ═══ Proxy Mode ═══
    val proxyMode: Flow<String> = dataStore.data.map { it[PROXY_MODE] ?: "tun" }
    val proxyHost: Flow<String> = dataStore.data.map { it[PROXY_HOST] ?: "127.0.0.1" }
    val proxyPort: Flow<Int> = dataStore.data.map { it[PROXY_PORT] ?: 1080 }

    // ═══ Captcha Solve Mode ═══
    val captchaMode: Flow<String> = dataStore.data.map { it[CAPTCHA_MODE] ?: "rjs" }

    // ═══ VPN Exclusions Mode ═══
    val isWhitelist: Flow<Boolean> = dataStore.data.map { it[IS_WHITELIST] ?: false }

    suspend fun save(
        peer: String,
        vkHashes: String,
        secondaryVkHash: String,
        workersPerHash: Int,
        protocol: String,
        listenPort: Int,
        sni: String = "",
        noDns: Boolean = false
    ) {
        dataStore.edit { prefs ->
            prefs[PEER] = peer
            prefs[VK_HASHES] = vkHashes
            prefs[SECONDARY_VK_HASH] = secondaryVkHash
            prefs[WORKERS_PER_HASH] = workersPerHash
            prefs[PROTOCOL] = protocol
            prefs[LISTEN_PORT] = listenPort
            prefs[SNI] = sni
            prefs[NO_DNS] = noDns
        }
    }

    suspend fun saveUserAgent(ua: String) {
        dataStore.edit { prefs ->
            prefs[USER_AGENT] = ua
        }
    }

    suspend fun saveDeploy(ip: String, login: String, pass: String, sshPort: String) {
        dataStore.edit { prefs ->
            prefs[DEPLOY_IP] = ip
            prefs[DEPLOY_LOGIN] = login
            prefs[DEPLOY_PASSWORD] = pass
            prefs[DEPLOY_SSH_PORT] = sshPort
        }
    }

    suspend fun saveExcludedApps(packages: String) {
        dataStore.edit { prefs ->
            prefs[EXCLUDED_APPS] = packages
        }
    }
    
    suspend fun saveDetailedLogs(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[DETAILED_LOGS] = enabled
        }
    }
    
    // ═══ Сохранение пароля подключения ═══
    suspend fun saveConnectionPassword(password: String) {
        dataStore.edit { prefs ->
            prefs[CONNECTION_PASSWORD] = password
        }
    }
    
    // ═══ Сохранение секретов деплоя ═══
    suspend fun saveDeploySecrets(mainPass: String, adminId: String, botToken: String, sshPort: String) {
        dataStore.edit { prefs ->
            prefs[DEPLOY_MAIN_PASSWORD] = mainPass
            prefs[DEPLOY_ADMIN_ID] = adminId
            prefs[DEPLOY_BOT_TOKEN] = botToken
            prefs[DEPLOY_SSH_PORT] = sshPort
        }
    }

    // ═══ Сохранение proxy mode ═══
    suspend fun saveProxyMode(mode: String, host: String, port: Int) {
        dataStore.edit { prefs ->
            prefs[PROXY_MODE] = mode
            prefs[PROXY_HOST] = host
            prefs[PROXY_PORT] = port
        }
    }

    // ═══ Сохранение режима обхода капчи ═══
    suspend fun saveCaptchaMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[CAPTCHA_MODE] = mode
        }
    }

    // ═══ Сохранение режима списка (ЧС/БС) ═══
    suspend fun saveIsWhitelist(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[IS_WHITELIST] = enabled
        }
    }

    // Атомарное сохранение обоих параметров для исключения гонки при перезагрузке
    suspend fun saveExceptionsMode(packages: String, isWhitelist: Boolean) {
        dataStore.edit { prefs ->
            prefs[EXCLUDED_APPS] = packages
            prefs[IS_WHITELIST] = isWhitelist
        }
    }
}