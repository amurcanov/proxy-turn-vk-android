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
        private val CAPTCHA_MODE = stringPreferencesKey("captcha_mode") // "auto", "wv", or "rjs"
        private val CAPTCHA_SOLVE_METHOD = stringPreferencesKey("captcha_solve_method") // "manual" or "auto"
        private val CAPTCHA_WBV_SOLVE_METHOD = stringPreferencesKey("captcha_wbv_solve_method") // "manual" or "auto"
        
        // ═══ VPN Exclusions Mode ═══
        private val IS_WHITELIST = booleanPreferencesKey("is_whitelist")

        // ═══ Theme Mode ═══
        private val THEME_MODE = stringPreferencesKey("theme_mode") // "system", "light", "dark"
        private val IS_DYNAMIC_COLOR = booleanPreferencesKey("is_dynamic_color")
        private val THEME_PALETTE = stringPreferencesKey("theme_palette")

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
    }

    private val dataStore = appContext.dataStore
    private val secureStore = SecureStringStore(appContext)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            migrateSecretsToKeystore()
        }
    }

    val peer: Flow<String> = dataStore.data.map { it[PEER] ?: "" }
    val vkHashes: Flow<String> = dataStore.data.map { it[VK_HASHES] ?: "" }
    val secondaryVkHash: Flow<String> = dataStore.data.map { it[SECONDARY_VK_HASH] ?: "" }
    val workersPerHash: Flow<Int> = dataStore.data.map { it[WORKERS_PER_HASH] ?: 16 }
    val protocol: Flow<String> = dataStore.data.map { it[PROTOCOL] ?: "udp" }
    val listenPort: Flow<Int> = dataStore.data.map { it[LISTEN_PORT] ?: 9000 }
    val manualPortsEnabled: Flow<Boolean> = dataStore.data.map { it[MANUAL_PORTS_ENABLED] ?: false }
    val serverDtlsPort: Flow<Int> = dataStore.data.map { it[SERVER_DTLS_PORT] ?: 56000 }
    val serverWgPort: Flow<Int> = dataStore.data.map { it[SERVER_WG_PORT] ?: 56001 }
    val sni: Flow<String> = dataStore.data.map { it[SNI] ?: "" }
    val noDns: Flow<Boolean> = dataStore.data.map { it[NO_DNS] ?: false }
    val userAgent: Flow<String> = dataStore.data.map { it[USER_AGENT] ?: "" }

    val deployIp: Flow<String> = dataStore.data.map { it[DEPLOY_IP] ?: "" }
    val deployLogin: Flow<String> = dataStore.data.map { it[DEPLOY_LOGIN] ?: "" }
    val deployPassword: Flow<String> = dataStore.data.map {
        readSecret(it, DEPLOY_PASSWORD_ENCRYPTED, DEPLOY_PASSWORD)
    }
    val deploySshPort: Flow<String> = dataStore.data.map { it[DEPLOY_SSH_PORT] ?: "" }
    val excludedApps: Flow<String> = dataStore.data.map { it[EXCLUDED_APPS] ?: "" }
    
    val detailedLogs: Flow<Boolean> = dataStore.data.map { it[DETAILED_LOGS] ?: false }
    
    // ═══ Пароли и Управление ═══
    val connectionPassword: Flow<String> = dataStore.data.map {
        readSecret(it, CONNECTION_PASSWORD_ENCRYPTED, CONNECTION_PASSWORD)
    }
    val deployMainPassword: Flow<String> = dataStore.data.map {
        readSecret(it, DEPLOY_MAIN_PASSWORD_ENCRYPTED, DEPLOY_MAIN_PASSWORD)
    }
    val deployAdminId: Flow<String> = dataStore.data.map {
        readSecret(it, DEPLOY_ADMIN_ID_ENCRYPTED, DEPLOY_ADMIN_ID)
    }
    val deployBotToken: Flow<String> = dataStore.data.map {
        readSecret(it, DEPLOY_BOT_TOKEN_ENCRYPTED, DEPLOY_BOT_TOKEN)
    }

    // ═══ Proxy Mode ═══
    val proxyMode: Flow<String> = dataStore.data.map { it[PROXY_MODE] ?: "tun" }
    val proxyHost: Flow<String> = dataStore.data.map { it[PROXY_HOST] ?: "127.0.0.1" }
    val proxyPort: Flow<Int> = dataStore.data.map { it[PROXY_PORT] ?: 1080 }

    // ═══ Captcha Solve Mode ═══
    val captchaMode: Flow<String> = dataStore.data.map { it[CAPTCHA_MODE] ?: "auto" }
    val captchaSolveMethod: Flow<String> = dataStore.data.map { it[CAPTCHA_SOLVE_METHOD] ?: "auto" }
    val captchaWbvSolveMethod: Flow<String> = dataStore.data.map { it[CAPTCHA_WBV_SOLVE_METHOD] ?: "auto" }

    // ═══ VPN Exclusions Mode ═══
    val isWhitelist: Flow<Boolean> = dataStore.data.map { it[IS_WHITELIST] ?: false }

    // ═══ Theme Mode ═══
    val themeMode: Flow<String> = dataStore.data.map { it[THEME_MODE] ?: "system" }
    val isDynamicColor: Flow<Boolean> = dataStore.data.map { it[IS_DYNAMIC_COLOR] ?: false }
    val themePalette: Flow<String> = dataStore.data.map { it[THEME_PALETTE] ?: "indigo" }

    val updateLastCheckAt: Flow<Long> = dataStore.data.map { it[UPDATE_LAST_CHECK_AT] ?: 0L }
    val updateLatestVersion: Flow<String> = dataStore.data.map { it[UPDATE_LATEST_VERSION] ?: "" }
    val updateLastError: Flow<String> = dataStore.data.map { it[UPDATE_LAST_ERROR] ?: "" }
    val updateCheckIntervalHours: Flow<Int> = dataStore.data.map { it[UPDATE_CHECK_INTERVAL_HOURS] ?: DEFAULT_UPDATE_CHECK_INTERVAL_HOURS }
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

    suspend fun saveManualPortsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[MANUAL_PORTS_ENABLED] = enabled
        }
    }

    suspend fun savePorts(serverDtlsPort: Int, serverWgPort: Int, listenPort: Int) {
        dataStore.edit { prefs ->
            prefs[SERVER_DTLS_PORT] = serverDtlsPort
            prefs[SERVER_WG_PORT] = serverWgPort
            prefs[LISTEN_PORT] = listenPort
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
            prefs.putSecret(DEPLOY_PASSWORD_ENCRYPTED, DEPLOY_PASSWORD, pass)
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
            prefs.putSecret(CONNECTION_PASSWORD_ENCRYPTED, CONNECTION_PASSWORD, password)
        }
    }
    
    // ═══ Сохранение секретов деплоя ═══
    suspend fun saveDeploySecrets(mainPass: String, adminId: String, botToken: String, sshPort: String) {
        dataStore.edit { prefs ->
            prefs.putSecret(DEPLOY_MAIN_PASSWORD_ENCRYPTED, DEPLOY_MAIN_PASSWORD, mainPass)
            prefs.putSecret(DEPLOY_ADMIN_ID_ENCRYPTED, DEPLOY_ADMIN_ID, adminId)
            prefs.putSecret(DEPLOY_BOT_TOKEN_ENCRYPTED, DEPLOY_BOT_TOKEN, botToken)
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

    suspend fun saveCaptchaSolveMethod(method: String) {
        dataStore.edit { prefs ->
            prefs[CAPTCHA_SOLVE_METHOD] = method
        }
    }

    suspend fun saveWbvCaptchaSolveMethod(method: String) {
        dataStore.edit { prefs ->
            prefs[CAPTCHA_WBV_SOLVE_METHOD] = method
            if (prefs[CAPTCHA_MODE] == "wv") {
                prefs[CAPTCHA_SOLVE_METHOD] = method
            }
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

    private suspend fun migrateSecretsToKeystore() {
        dataStore.edit { prefs ->
            prefs.migrateSecret(DEPLOY_PASSWORD_ENCRYPTED, DEPLOY_PASSWORD)
            prefs.migrateSecret(CONNECTION_PASSWORD_ENCRYPTED, CONNECTION_PASSWORD)
            prefs.migrateSecret(DEPLOY_MAIN_PASSWORD_ENCRYPTED, DEPLOY_MAIN_PASSWORD)
            prefs.migrateSecret(DEPLOY_ADMIN_ID_ENCRYPTED, DEPLOY_ADMIN_ID)
            prefs.migrateSecret(DEPLOY_BOT_TOKEN_ENCRYPTED, DEPLOY_BOT_TOKEN)
        }
    }

    private fun readSecret(
        prefs: Preferences,
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>
    ): String {
        return secureStore.decrypt(prefs[encryptedKey]) ?: prefs[legacyKey] ?: ""
    }

    private fun MutablePreferences.putSecret(
        encryptedKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>,
        value: String
    ) {
        if (value.isBlank()) {
            remove(encryptedKey)
            remove(legacyKey)
        } else {
            this[encryptedKey] = secureStore.encrypt(value)
            remove(legacyKey)
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
