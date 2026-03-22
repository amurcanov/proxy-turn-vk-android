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

        private val DEPLOY_IP = stringPreferencesKey("deploy_ip")
        private val DEPLOY_LOGIN = stringPreferencesKey("deploy_login")
        private val DEPLOY_PASSWORD = stringPreferencesKey("deploy_password")
        private val EXCLUDED_APPS = stringPreferencesKey("excluded_apps")
        
        private val DETAILED_LOGS = booleanPreferencesKey("detailed_logs")
        
        // ═══ Пароли и Управление ═══
        private val CONNECTION_PASSWORD = stringPreferencesKey("connection_password")
        private val DEPLOY_MAIN_PASSWORD = stringPreferencesKey("deploy_main_password")
        private val DEPLOY_ADMIN_ID = stringPreferencesKey("deploy_admin_id")
        private val DEPLOY_BOT_TOKEN = stringPreferencesKey("deploy_bot_token")
    }

    private val dataStore = appContext.dataStore

    val peer: Flow<String> = dataStore.data.map { it[PEER] ?: "" }
    val vkHashes: Flow<String> = dataStore.data.map { it[VK_HASHES] ?: "" }
    val secondaryVkHash: Flow<String> = dataStore.data.map { it[SECONDARY_VK_HASH] ?: "" }
    val workersPerHash: Flow<Int> = dataStore.data.map { it[WORKERS_PER_HASH] ?: 16 }
    val protocol: Flow<String> = dataStore.data.map { it[PROTOCOL] ?: "tcp" }
    val listenPort: Flow<Int> = dataStore.data.map { it[LISTEN_PORT] ?: 9000 }
    val sni: Flow<String> = dataStore.data.map { it[SNI] ?: "" }
    val noDns: Flow<Boolean> = dataStore.data.map { it[NO_DNS] ?: false }

    val deployIp: Flow<String> = dataStore.data.map { it[DEPLOY_IP] ?: "" }
    val deployLogin: Flow<String> = dataStore.data.map { it[DEPLOY_LOGIN] ?: "" }
    val deployPassword: Flow<String> = dataStore.data.map { it[DEPLOY_PASSWORD] ?: "" }
    val excludedApps: Flow<String> = dataStore.data.map { it[EXCLUDED_APPS] ?: "" }
    
    val detailedLogs: Flow<Boolean> = dataStore.data.map { it[DETAILED_LOGS] ?: false }
    
    // ═══ Пароли и Управление ═══
    val connectionPassword: Flow<String> = dataStore.data.map { it[CONNECTION_PASSWORD] ?: "" }
    val deployMainPassword: Flow<String> = dataStore.data.map { it[DEPLOY_MAIN_PASSWORD] ?: "" }
    val deployAdminId: Flow<String> = dataStore.data.map { it[DEPLOY_ADMIN_ID] ?: "" }
    val deployBotToken: Flow<String> = dataStore.data.map { it[DEPLOY_BOT_TOKEN] ?: "" }

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

    suspend fun saveDeploy(ip: String, login: String, pass: String) {
        dataStore.edit { prefs ->
            prefs[DEPLOY_IP] = ip
            prefs[DEPLOY_LOGIN] = login
            prefs[DEPLOY_PASSWORD] = pass
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
    suspend fun saveDeploySecrets(mainPass: String, adminId: String, botToken: String) {
        dataStore.edit { prefs ->
            prefs[DEPLOY_MAIN_PASSWORD] = mainPass
            prefs[DEPLOY_ADMIN_ID] = adminId
            prefs[DEPLOY_BOT_TOKEN] = botToken
        }
    }
}