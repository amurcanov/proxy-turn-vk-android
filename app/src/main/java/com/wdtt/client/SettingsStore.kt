package com.wdtt.client

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

import android.os.Build

class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    companion object {
        private val Context.dataStore by preferencesDataStore("settings")
        private val ACTIVE_PROFILE = intPreferencesKey("active_profile")
        private val SHOW_SYSTEM_APPS = booleanPreferencesKey("show_system_apps")
        private val LOGGING_ENABLED = booleanPreferencesKey("logging_enabled")
        private val WDTT_LINK = stringPreferencesKey("wdtt_link")
        private val WDTT_LINK_MODE = booleanPreferencesKey("wdtt_link_mode")

        private val PEER = stringPreferencesKey("peer")
        private val VK_HASHES = stringPreferencesKey("vk_hashes")
        private val SECONDARY_VK_HASH = stringPreferencesKey("secondary_vk_hash")
        private val WORKERS_PER_HASH = intPreferencesKey("workers_per_hash")
        private val PROTOCOL = stringPreferencesKey("protocol")
        private val LISTEN_PORT = intPreferencesKey("listen_port")
        private val MANUAL_PORTS_ENABLED = booleanPreferencesKey("manual_ports_enabled")
        private val SERVER_DTLS_PORT = intPreferencesKey("server_dtls_port")
        private val SERVER_WG_PORT = intPreferencesKey("server_wg_port")
        private val SNI = stringPreferencesKey("sni")
        private val NO_DTLS = booleanPreferencesKey("no_dtls")
        private val NO_DNS = booleanPreferencesKey("no_dns")

        private val USER_AGENT = stringPreferencesKey("user_agent")

        private val DEPLOY_IP = stringPreferencesKey("deploy_ip")
        private val DEPLOY_LOGIN = stringPreferencesKey("deploy_login")
        private val DEPLOY_PASSWORD = stringPreferencesKey("deploy_password")
        private val DEPLOY_PASSWORD_ENCRYPTED = stringPreferencesKey("deploy_password_encrypted")
        private val DEPLOY_SSH_PORT = stringPreferencesKey("deploy_ssh_port")
        private val DEPLOY_DNS1 = stringPreferencesKey("deploy_dns1")
        private val DEPLOY_DNS2 = stringPreferencesKey("deploy_dns2")
        private val EXCLUDED_APPS = stringPreferencesKey("excluded_apps")
        
        private val DETAILED_LOGS = booleanPreferencesKey("detailed_logs")
        
        // ═══ Пароли и Управление ═══
        private val CONNECTION_PASSWORD = stringPreferencesKey("connection_password")
        private val CONNECTION_PASSWORD_ENCRYPTED = stringPreferencesKey("connection_password_encrypted")
        private val DEPLOY_MAIN_PASSWORD = stringPreferencesKey("deploy_main_password")
        private val DEPLOY_MAIN_PASSWORD_ENCRYPTED = stringPreferencesKey("deploy_main_password_encrypted")
        private val DEPLOY_ADMIN_ID = stringPreferencesKey("deploy_admin_id")
        private val DEPLOY_ADMIN_ID_ENCRYPTED = stringPreferencesKey("deploy_admin_id_encrypted")
        private val DEPLOY_BOT_TOKEN = stringPreferencesKey("deploy_bot_token")
        private val DEPLOY_BOT_TOKEN_ENCRYPTED = stringPreferencesKey("deploy_bot_token_encrypted")

        // ═══ Proxy Mode ═══
        private val PROXY_MODE = stringPreferencesKey("proxy_mode") // "tun" or "socks5"
        private val PROXY_HOST = stringPreferencesKey("proxy_host")
        private val PROXY_PORT = intPreferencesKey("proxy_port")

        // ═══ Captcha Solve Mode ═══
        private val VK_AUTH_MODE = stringPreferencesKey("vk_auth_mode") // "vkcalls" or "legacy"
        private val CAPTCHA_MODE = stringPreferencesKey("captcha_mode") // "auto", "wv", or "rjs"
        private val CAPTCHA_SOLVE_METHOD = stringPreferencesKey("captcha_solve_method") // "manual" or "auto"
        private val CAPTCHA_WBV_SOLVE_METHOD = stringPreferencesKey("captcha_wbv_solve_method") // "manual" or "auto"
        
        // ═══ VPN Exclusions Mode ═══
        private val IS_WHITELIST = booleanPreferencesKey("is_whitelist")

        // ═══ Theme Mode ═══
        private val THEME_MODE = stringPreferencesKey("theme_mode") // "system", "light", "dark"
        private val IS_DYNAMIC_COLOR = booleanPreferencesKey("is_dynamic_color")
        private val THEME_PALETTE = stringPreferencesKey("theme_palette")

        // ═══ Fingerprint & Client IDs ═══
        private val SELECTED_FINGERPRINT = stringPreferencesKey("selected_fingerprint")
        private val ACTIVE_CLIENT_IDS = stringPreferencesKey("active_client_ids")

        private val UPDATE_LAST_CHECK_AT = longPreferencesKey("update_last_check_at")
        private val UPDATE_LATEST_VERSION = stringPreferencesKey("update_latest_version")
        private val UPDATE_LAST_ERROR = stringPreferencesKey("update_last_error")
        private val UPDATE_CHECK_INTERVAL_HOURS = intPreferencesKey("update_check_interval_hours")
        private val UPDATE_POSTPONE_UNTIL = longPreferencesKey("update_postpone_until")
        private val UPDATE_POSTPONE_VERSION = stringPreferencesKey("update_postpone_version")
        private val UPDATE_DIALOG_LAST_SHOWN_VERSION = stringPreferencesKey("update_dialog_last_shown_version")
        private val UPDATE_DIALOG_LAST_SHOWN_AT = longPreferencesKey("update_dialog_last_shown_at")
        private val UPDATE_DIALOG_LAST_ACTION_VERSION = stringPreferencesKey("update_dialog_last_action_version")
        private val UPDATE_DIALOG_LAST_ACTION = stringPreferencesKey("update_dialog_last_action")
        private val UPDATE_DIALOG_LAST_ACTION_AT = longPreferencesKey("update_dialog_last_action_at")

        private val CLIENT_ID_CHECK_RESULTS = stringPreferencesKey("client_id_check_results")

        private fun <T> getProfileKey(baseKey: Preferences.Key<T>, profile: Int): Preferences.Key<T> {
            if (profile == 0) return baseKey
            val newName = "${baseKey.name}_$profile"
            @Suppress("UNCHECKED_CAST")
            return when (baseKey) {
                PEER, VK_HASHES, SECONDARY_VK_HASH, PROTOCOL, SNI, USER_AGENT, DEPLOY_IP, DEPLOY_LOGIN, DEPLOY_PASSWORD, DEPLOY_PASSWORD_ENCRYPTED, DEPLOY_SSH_PORT, DEPLOY_DNS1, DEPLOY_DNS2, EXCLUDED_APPS, CONNECTION_PASSWORD, CONNECTION_PASSWORD_ENCRYPTED, DEPLOY_MAIN_PASSWORD, DEPLOY_MAIN_PASSWORD_ENCRYPTED, DEPLOY_ADMIN_ID, DEPLOY_ADMIN_ID_ENCRYPTED, DEPLOY_BOT_TOKEN, DEPLOY_BOT_TOKEN_ENCRYPTED, PROXY_MODE, PROXY_HOST, VK_AUTH_MODE, CAPTCHA_MODE, CAPTCHA_SOLVE_METHOD, CAPTCHA_WBV_SOLVE_METHOD, WDTT_LINK, SELECTED_FINGERPRINT, ACTIVE_CLIENT_IDS -> stringPreferencesKey(newName) as Preferences.Key<T>
                WORKERS_PER_HASH, LISTEN_PORT, SERVER_DTLS_PORT, SERVER_WG_PORT, PROXY_PORT -> intPreferencesKey(newName) as Preferences.Key<T>
                MANUAL_PORTS_ENABLED, NO_DTLS, NO_DNS, IS_WHITELIST, WDTT_LINK_MODE -> booleanPreferencesKey(newName) as Preferences.Key<T>
                else -> throw IllegalArgumentException("Unsupported key type: ${baseKey.name}")
            }
        }
    }

    private val dataStore = appContext.dataStore
    private val secureStore = SecureStringStore(appContext)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            migrateSecretsToKeystore()
        }
    }

    val activeProfile: Flow<Int> = dataStore.data.map { it[ACTIVE_PROFILE] ?: 0 }
    val showSystemApps: Flow<Boolean> = dataStore.data.map { it[SHOW_SYSTEM_APPS] ?: true }
    val loggingEnabled: Flow<Boolean> = dataStore.data.map { it[LOGGING_ENABLED] ?: true }
    val wdttLink: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(WDTT_LINK, profile)] ?: ""
    }
    val wdttLinkMode: Flow<Boolean> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(WDTT_LINK_MODE, profile)] ?: false
    }

    val peer: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(PEER, profile)] ?: ""
    }
    val vkHashes: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(VK_HASHES, profile)] ?: ""
    }
    val secondaryVkHash: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(SECONDARY_VK_HASH, profile)] ?: ""
    }
    val workersPerHash: Flow<Int> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(WORKERS_PER_HASH, profile)] ?: 16
    }
    val protocol: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(PROTOCOL, profile)] ?: "udp"
    }
    val listenPort: Flow<Int> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(LISTEN_PORT, profile)] ?: 9000
    }
    val manualPortsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(MANUAL_PORTS_ENABLED, profile)] ?: false
    }
    val serverDtlsPort: Flow<Int> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(SERVER_DTLS_PORT, profile)] ?: 56000
    }
    val serverWgPort: Flow<Int> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(SERVER_WG_PORT, profile)] ?: 56001
    }
    val sni: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(SNI, profile)] ?: ""
    }
    val noDns: Flow<Boolean> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(NO_DNS, profile)] ?: false
    }
    val userAgent: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(USER_AGENT, profile)] ?: ""
    }

    val deployIp: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(DEPLOY_IP, profile)] ?: ""
    }
    val deployLogin: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(DEPLOY_LOGIN, profile)] ?: ""
    }
    val deployPassword: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        readSecret(prefs, DEPLOY_PASSWORD_ENCRYPTED, DEPLOY_PASSWORD, profile)
    }
    val deploySshPort: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(DEPLOY_SSH_PORT, profile)] ?: ""
    }
    val deployDns1: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(DEPLOY_DNS1, profile)] ?: "1.1.1.1"
    }
    val deployDns2: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(DEPLOY_DNS2, profile)] ?: "1.0.0.1"
    }
    val excludedApps: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(EXCLUDED_APPS, profile)] ?: ""
    }
    
    val detailedLogs: Flow<Boolean> = dataStore.data.map { it[DETAILED_LOGS] ?: false }
    
    // ═══ Пароли и Управление ═══
    val connectionPassword: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        readSecret(prefs, CONNECTION_PASSWORD_ENCRYPTED, CONNECTION_PASSWORD, profile)
    }
    val deployMainPassword: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        readSecret(prefs, DEPLOY_MAIN_PASSWORD_ENCRYPTED, DEPLOY_MAIN_PASSWORD, profile)
    }
    val deployAdminId: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        readSecret(prefs, DEPLOY_ADMIN_ID_ENCRYPTED, DEPLOY_ADMIN_ID, profile)
    }
    val deployBotToken: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        readSecret(prefs, DEPLOY_BOT_TOKEN_ENCRYPTED, DEPLOY_BOT_TOKEN, profile)
    }

    // ═══ Proxy Mode ═══
    val proxyMode: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(PROXY_MODE, profile)] ?: "tun"
    }
    val proxyHost: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(PROXY_HOST, profile)] ?: "127.0.0.1"
    }
    val proxyPort: Flow<Int> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(PROXY_PORT, profile)] ?: 1080
    }

    // ═══ Captcha Solve Mode ═══
    val vkAuthMode: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(VK_AUTH_MODE, profile)] ?: "vkcalls"
    }
    val captchaMode: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(CAPTCHA_MODE, profile)] ?: "auto"
    }
    val captchaSolveMethod: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(CAPTCHA_SOLVE_METHOD, profile)] ?: "auto"
    }
    val captchaWbvSolveMethod: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(CAPTCHA_WBV_SOLVE_METHOD, profile)] ?: "auto"
    }

    // ═══ VPN Exclusions Mode ═══
    val isWhitelist: Flow<Boolean> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(IS_WHITELIST, profile)] ?: false
    }

    // ═══ Theme Mode ═══
    val themeMode: Flow<String> = dataStore.data.map { it[THEME_MODE] ?: "system" }
    val isDynamicColor: Flow<Boolean> = dataStore.data.map { it[IS_DYNAMIC_COLOR] ?: (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) }
    val themePalette: Flow<String> = dataStore.data.map { it[THEME_PALETTE] ?: "indigo" }

    // ═══ Fingerprint & Client IDs ═══
    val selectedFingerprint: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(SELECTED_FINGERPRINT, profile)] ?: "chrome"
    }
    val activeClientIds: Flow<String> = dataStore.data.map { prefs ->
        val profile = prefs[ACTIVE_PROFILE] ?: 0
        prefs[getProfileKey(ACTIVE_CLIENT_IDS, profile)] ?: "6287487,8202606"
    }
    val clientIdCheckResults: Flow<String> = dataStore.data.map { prefs ->
        prefs[CLIENT_ID_CHECK_RESULTS] ?: "{}"
    }

    val updateLastCheckAt: Flow<Long> = dataStore.data.map { it[UPDATE_LAST_CHECK_AT] ?: 0L }
    val updateLatestVersion: Flow<String> = dataStore.data.map { it[UPDATE_LATEST_VERSION] ?: "" }
    val updateLastError: Flow<String> = dataStore.data.map { it[UPDATE_LAST_ERROR] ?: "" }
    val updateCheckIntervalHours: Flow<Int> = dataStore.data.map { it[UPDATE_CHECK_INTERVAL_HOURS] ?: 24 }
    val updatePostponeUntil: Flow<Long> = dataStore.data.map { it[UPDATE_POSTPONE_UNTIL] ?: 0L }
    val updatePostponeVersion: Flow<String> = dataStore.data.map { it[UPDATE_POSTPONE_VERSION] ?: "" }
    val updateDialogLastShownVersion: Flow<String> = dataStore.data.map { it[UPDATE_DIALOG_LAST_SHOWN_VERSION] ?: "" }
    val updateDialogLastShownAt: Flow<Long> = dataStore.data.map { it[UPDATE_DIALOG_LAST_SHOWN_AT] ?: 0L }
    val updateDialogLastActionVersion: Flow<String> = dataStore.data.map { it[UPDATE_DIALOG_LAST_ACTION_VERSION] ?: "" }
    val updateDialogLastAction: Flow<String> = dataStore.data.map { it[UPDATE_DIALOG_LAST_ACTION] ?: "" }
    val updateDialogLastActionAt: Flow<Long> = dataStore.data.map { it[UPDATE_DIALOG_LAST_ACTION_AT] ?: 0L }

    suspend fun saveThemeMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[THEME_MODE] = mode
        }
    }

    suspend fun saveDynamicColor(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[IS_DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun saveThemePalette(palette: String) {
        dataStore.edit { prefs ->
            prefs[THEME_PALETTE] = palette
        }
    }

    suspend fun saveFingerprint(fingerprint: String) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(SELECTED_FINGERPRINT, profile)] = fingerprint
        }
    }

    suspend fun saveActiveClientIds(clientIds: String) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(ACTIVE_CLIENT_IDS, profile)] = clientIds
        }
    }

    suspend fun saveClientIdCheckResults(resultsJson: String) {
        dataStore.edit { prefs ->
            prefs[CLIENT_ID_CHECK_RESULTS] = resultsJson
        }
    }

    suspend fun saveUpdateState(lastCheckAt: Long, latestVersion: String, error: String) {
        dataStore.edit { prefs ->
            prefs[UPDATE_LAST_CHECK_AT] = lastCheckAt
            prefs[UPDATE_LATEST_VERSION] = latestVersion
            prefs[UPDATE_LAST_ERROR] = error
        }
    }

    suspend fun saveUpdateCheckIntervalHours(hours: Int) {
        dataStore.edit { prefs ->
            prefs[UPDATE_CHECK_INTERVAL_HOURS] = hours
        }
    }

    suspend fun saveUpdatePostpone(version: String, until: Long) {
        dataStore.edit { prefs ->
            prefs[UPDATE_POSTPONE_VERSION] = version
            prefs[UPDATE_POSTPONE_UNTIL] = until
        }
    }

    suspend fun saveUpdateDialogShown(version: String, shownAt: Long) {
        dataStore.edit { prefs ->
            prefs[UPDATE_DIALOG_LAST_SHOWN_VERSION] = version
            prefs[UPDATE_DIALOG_LAST_SHOWN_AT] = shownAt
        }
    }

    suspend fun saveUpdateDialogAction(version: String, action: String, actedAt: Long) {
        dataStore.edit { prefs ->
            prefs[UPDATE_DIALOG_LAST_ACTION_VERSION] = version
            prefs[UPDATE_DIALOG_LAST_ACTION] = action
            prefs[UPDATE_DIALOG_LAST_ACTION_AT] = actedAt
        }
    }

    suspend fun saveActiveProfile(profile: Int) {
        dataStore.edit { prefs ->
            prefs[ACTIVE_PROFILE] = profile
        }
    }

    suspend fun saveShowSystemApps(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[SHOW_SYSTEM_APPS] = enabled
        }
    }

    suspend fun saveLoggingEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[LOGGING_ENABLED] = enabled
        }
    }

    suspend fun saveWdttLink(link: String) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(WDTT_LINK, profile)] = link
        }
    }

    suspend fun saveWdttLinkMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(WDTT_LINK_MODE, profile)] = enabled
        }
    }

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
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(PEER, profile)] = peer
            prefs[getProfileKey(VK_HASHES, profile)] = vkHashes
            prefs[getProfileKey(SECONDARY_VK_HASH, profile)] = secondaryVkHash
            prefs[getProfileKey(WORKERS_PER_HASH, profile)] = workersPerHash
            prefs[getProfileKey(PROTOCOL, profile)] = protocol
            prefs[getProfileKey(LISTEN_PORT, profile)] = listenPort
            prefs[getProfileKey(SNI, profile)] = sni
            prefs[getProfileKey(NO_DNS, profile)] = noDns
        }
    }

    suspend fun saveManualPortsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(MANUAL_PORTS_ENABLED, profile)] = enabled
        }
    }

    suspend fun savePorts(serverDtlsPort: Int, serverWgPort: Int, listenPort: Int) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(SERVER_DTLS_PORT, profile)] = serverDtlsPort
            prefs[getProfileKey(SERVER_WG_PORT, profile)] = serverWgPort
            prefs[getProfileKey(LISTEN_PORT, profile)] = listenPort
        }
    }

    suspend fun saveUserAgent(ua: String) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(USER_AGENT, profile)] = ua
        }
    }

    suspend fun saveDeploy(ip: String, login: String, pass: String, sshPort: String, dns1: String, dns2: String) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(DEPLOY_IP, profile)] = ip
            prefs[getProfileKey(DEPLOY_LOGIN, profile)] = login
            prefs.putSecret(DEPLOY_PASSWORD_ENCRYPTED, DEPLOY_PASSWORD, pass, profile)
            prefs[getProfileKey(DEPLOY_SSH_PORT, profile)] = sshPort
            prefs[getProfileKey(DEPLOY_DNS1, profile)] = dns1
            prefs[getProfileKey(DEPLOY_DNS2, profile)] = dns2
        }
    }

    suspend fun saveExcludedApps(packages: String) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(EXCLUDED_APPS, profile)] = packages
        }
    }
    
    suspend fun saveDetailedLogs(enabled: Boolean) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(DETAILED_LOGS, profile)] = enabled
        }
    }
    
    // ═══ Сохранение пароля подключения ═══
    suspend fun saveConnectionPassword(password: String) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs.putSecret(CONNECTION_PASSWORD_ENCRYPTED, CONNECTION_PASSWORD, password, profile)
        }
    }
    
    // ═══ Сохранение секретов деплоя ═══
    suspend fun saveDeploySecrets(mainPass: String, adminId: String, botToken: String, sshPort: String) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs.putSecret(DEPLOY_MAIN_PASSWORD_ENCRYPTED, DEPLOY_MAIN_PASSWORD, mainPass, profile)
            prefs.putSecret(DEPLOY_ADMIN_ID_ENCRYPTED, DEPLOY_ADMIN_ID, adminId, profile)
            prefs.putSecret(DEPLOY_BOT_TOKEN_ENCRYPTED, DEPLOY_BOT_TOKEN, botToken, profile)
            prefs[getProfileKey(DEPLOY_SSH_PORT, profile)] = sshPort
        }
    }

    // ═══ Сохранение proxy mode ═══
    suspend fun saveProxyMode(mode: String, host: String, port: Int) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(PROXY_MODE, profile)] = mode
            prefs[getProfileKey(PROXY_HOST, profile)] = host
            prefs[getProfileKey(PROXY_PORT, profile)] = port
        }
    }

    // ═══ Сохранение режима обхода капчи ═══
    suspend fun saveVkAuthMode(mode: String) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(VK_AUTH_MODE, profile)] = if (mode == "legacy") "legacy" else "vkcalls"
        }
    }

    suspend fun saveCaptchaMode(mode: String) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(CAPTCHA_MODE, profile)] = mode
        }
    }

    suspend fun saveCaptchaSolveMethod(method: String) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(CAPTCHA_SOLVE_METHOD, profile)] = method
        }
    }

    suspend fun saveWbvCaptchaSolveMethod(method: String) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(CAPTCHA_WBV_SOLVE_METHOD, profile)] = method
            if (prefs[getProfileKey(CAPTCHA_MODE, profile)] == "wv") {
                prefs[getProfileKey(CAPTCHA_SOLVE_METHOD, profile)] = method
            }
        }
    }

    // ═══ Сохранение режима списка (ЧС/БС) ═══
    suspend fun saveIsWhitelist(enabled: Boolean) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(IS_WHITELIST, profile)] = enabled
        }
    }

    // Атомарное сохранение обоих параметров для исключения гонки при перезагрузке
    suspend fun saveExceptionsMode(packages: String, isWhitelist: Boolean) {
        dataStore.edit { prefs ->
            val profile = prefs[ACTIVE_PROFILE] ?: 0
            prefs[getProfileKey(EXCLUDED_APPS, profile)] = packages
            prefs[getProfileKey(IS_WHITELIST, profile)] = isWhitelist
        }
    }

    private suspend fun migrateSecretsToKeystore() {
        dataStore.edit { prefs ->
            for (profile in 0..2) {
                prefs.migrateSecret(getProfileKey(DEPLOY_PASSWORD_ENCRYPTED, profile), getProfileKey(DEPLOY_PASSWORD, profile))
                prefs.migrateSecret(getProfileKey(CONNECTION_PASSWORD_ENCRYPTED, profile), getProfileKey(CONNECTION_PASSWORD, profile))
                prefs.migrateSecret(getProfileKey(DEPLOY_MAIN_PASSWORD_ENCRYPTED, profile), getProfileKey(DEPLOY_MAIN_PASSWORD, profile))
                prefs.migrateSecret(getProfileKey(DEPLOY_ADMIN_ID_ENCRYPTED, profile), getProfileKey(DEPLOY_ADMIN_ID, profile))
                prefs.migrateSecret(getProfileKey(DEPLOY_BOT_TOKEN_ENCRYPTED, profile), getProfileKey(DEPLOY_BOT_TOKEN, profile))
            }
        }
    }

    private fun readSecret(
        prefs: Preferences,
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>,
        profile: Int
    ): String {
        val profEncryptedKey = getProfileKey(encryptedKey, profile)
        val profLegacyKey = getProfileKey(legacyKey, profile)
        return secureStore.decrypt(prefs[profEncryptedKey]) ?: prefs[profLegacyKey] ?: ""
    }

    private fun MutablePreferences.putSecret(
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>,
        value: String,
        profile: Int
    ) {
        val profEncryptedKey = getProfileKey(encryptedKey, profile)
        val profLegacyKey = getProfileKey(legacyKey, profile)
        if (value.isBlank()) {
            remove(profEncryptedKey)
            remove(profLegacyKey)
        } else {
            this[profEncryptedKey] = secureStore.encrypt(value)
            remove(profLegacyKey)
        }
    }

    private fun MutablePreferences.migrateSecret(
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>
    ) {
        val legacyValue = this[legacyKey]
        val encryptedValue = this[encryptedKey]
        if (!encryptedValue.isNullOrBlank()) {
            remove(legacyKey)
            return
        }
        if (!legacyValue.isNullOrBlank()) {
            runCatching {
                this[encryptedKey] = secureStore.encrypt(legacyValue)
                remove(legacyKey)
            }
        }
    }
}
