package com.wdtt.client

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

import androidx.compose.runtime.Stable

@Stable
data class LogEntry(
    val key: String,
    val message: String,
    val count: Int = 1,
    val priority: Int = 99, // 0 - Creds, 1 - DTLS, 2 - Ready, 3 - Stats, 99 - Errors/Other
    val isError: Boolean = false
)

object TunnelManager {
    // 100% защита от утечек: единый управляемый глобальный Scope
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var process: Process? = null
    private var readerJob: Job? = null
    private var watchdogJob: Job? = null
    private var wgHelper: WireGuardHelper? = null

    // Error counters for circuit breaker
    private var floodCount = 0
    private var mismatchCount = 0
    private var refusedCount = 0
    private var currentHashErrorCount = 0
    private var activeHashIndex = 0 // 0: primary, 1: secondary
    private var currentParams: TunnelParams? = null
    private var lastContext: Context? = null

    val running = MutableStateFlow(false)
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val unreadErrorCount = MutableStateFlow(0)
    val config = MutableStateFlow<String?>(null)
    val stats = MutableStateFlow("Ожидание данных...")
    val activeWorkers = MutableStateFlow(0)

    fun clearUnreadErrors() {
        unreadErrorCount.value = 0
    }

    // Добавляем лог с Деплоя
    fun addDeployErrorLog(message: String) {
        val hash = message.hashCode().toString()
        updateLog("deploy_err_$hash", "[ДЕПЛОЙ] $message", 99, true)
    }

    fun addDeploySuccessLog(message: String) {
        val hash = message.hashCode().toString() + System.currentTimeMillis()
        updateLog("deploy_ok_$hash", message, 2, false)
    }

    private fun updateLog(key: String, message: String, priority: Int, isError: Boolean = false) {
        if (isError) {
            val list = logs.value
            if (list.none { it.key == key }) {
                unreadErrorCount.value++
            }
        }
        logs.update { currentList ->
            val current = currentList.toMutableList()
            val index = current.indexOfFirst { it.key == key }
            
            if (index != -1) {
                val entry = current[index]
                current[index] = entry.copy(count = entry.count + 1, message = message)
            } else {
                current.add(LogEntry(key, message, 1, priority, isError))
            }
            
            // Сортировка: сначала по приоритету, потом ошибки в самый низ
            val sorted = current.sortedWith(compareBy({ it.priority }, { if (it.isError) 1 else 0 }, { it.key }))
            
            // Защита от утечки памяти: лимит 100 записей (самые важные сверху)
            if (sorted.size > 100) sorted.take(100) else sorted
        }
    }

    fun start(context: Context, params: TunnelParams, isSwitching: Boolean = false) {
        if (running.value && !isSwitching) return
        
        val appContext = context.applicationContext // Защита от Memory Leak
        
        if (!isSwitching) {
            clearLogs()
            floodCount = 0
            mismatchCount = 0
            refusedCount = 0
            currentHashErrorCount = 0
            activeHashIndex = 0
            currentParams = params
            lastContext = appContext
        }
        
        wgHelper = WireGuardHelper(appContext)

        scope.launch {
            try {
                val targetHash = if (activeHashIndex == 0) params.vkHashes else params.secondaryVkHash
                
                // Robust hash parsing: split by comma, newline, or whitespace
                val hashList = targetHash
                    .split(Regex("[,\\s\\n]+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(3)

                if (hashList.isEmpty()) {
                    updateLog("hash_error", "Ошибка: Хеш не указан", 99, true)
                    running.value = false
                    return@launch
                }

                val hashCount = hashList.size.coerceIn(1, 3)
                val totalWorkers = params.workersPerHash.coerceIn(1, 128) // Максимум ограничивается UI (80), но тут ставим хард-лимит побольше на случай запаса
                
                val hashMode = if (activeHashIndex == 0) "Основной" else "Запасной"
                updateLog("config_info", "[$hashMode] Хешей=$hashCount, Потоков=$totalWorkers", 50)


                // CRITICAL FIX: Use nativeLibraryDir with extractNativeLibs="true"
                val binaryPath = context.applicationInfo.nativeLibraryDir + "/libclient.so"
                val binaryFile = File(binaryPath)
                
                if (!binaryFile.exists()) {
                    updateLog("binary_error", "Ошибка: Бинарный файл не найден", 99, true)
                    return@launch
                }

                val cmd = mutableListOf(
                    binaryPath,
                    "-peer", params.peer,
                    "-vk", hashList.joinToString(","),
                    "-n", totalWorkers.toString(),
                    "-listen", "127.0.0.1:${params.port}"
                )

                if (params.sni.isNotEmpty()) {
                    cmd.add("-sni")
                    cmd.add(params.sni)
                }

                // Передаем ANDROID_ID
                val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
                cmd.add("-device-id")
                cmd.add(androidId)

                if (params.connectionPassword.isNotEmpty()) {
                   cmd.add("-password")
                   cmd.add(params.connectionPassword)
                }

                // Always UDP, protocol selection removed
                cmd.add("-udp")

                val pb = ProcessBuilder(cmd)
                pb.directory(context.filesDir) // Устанавливаем рабочую директорию
                pb.redirectErrorStream(true)
                
                // Set LD_LIBRARY_PATH
                val env = pb.environment()
                env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir

                process = pb.start()
                running.value = true
                startLogReader()
                startWatchdog(appContext, params)

            } catch (e: Exception) {
                updateLog("critical_start_error", "Критическая ошибка запуска: ${e.message}", 99, true)
                e.printStackTrace()
                running.value = false
            }
        }
    }

    private fun startLogReader() {
        readerJob = scope.launch {
            val reader = process?.inputStream?.bufferedReader() ?: return@launch
            var collectingConfig = false
            val configBuilder = StringBuilder()

            try {
                var lastResetTime = System.currentTimeMillis()

                reader.forEachLine { line ->
                    // Периодический сброс счетчиков ошибок (раз в 60 сек)
                    val now = System.currentTimeMillis()
                    if (now - lastResetTime > 60000) {
                        refusedCount = 0
                        floodCount = 0
                        mismatchCount = 0
                        currentHashErrorCount = 0
                        lastResetTime = now
                    }

                    // Чистим лог от даты из Go (например, "2023/10/24 12:34:56.123456 [ВОРКЕР...")
                    val msgPrefixReplaced = line.replace(Regex("^\\d{4}/\\d{2}/\\d{2}\\s\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\s"), "")
                    val lineTrim = msgPrefixReplaced.trim()

                    val isError = lineTrim.contains("Ошибка", true) || lineTrim.contains("error", true) || lineTrim.contains("FAIL", true) || lineTrim.contains("timeout", true) || lineTrim.contains("refused", true) || lineTrim.contains("FATAL_AUTH", true)

                    // 0. FATAL AUTH — мгновенная остановка
                    if (lineTrim.contains("FATAL_AUTH")) {
                        val reason = when {
                            lineTrim.contains("неверный пароль") -> "Неверный пароль подключения"
                            lineTrim.contains("истёк") -> "Срок действия пароля истёк"
                            lineTrim.contains("другому устройству") -> "Пароль привязан к другому устройству"
                            else -> "Ошибка авторизации"
                        }
                        handleCriticalError("\uD83D\uDD12 $reason. Воркеры остановлены.")
                        return@forEachLine
                    }

                    // 1. ПРЕДОХРАНИТЕЛЬ (Circuit Breaker)
                    if (isError) {
                        when {
                            lineTrim.contains("Flood control", true) -> {
                                floodCount++
                                if (floodCount >= 5) {
                                    handleCriticalError("Flood Control (ВК ограничил ваш IP). Попробуйте позже.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("ip mismatch", true) -> {
                                mismatchCount++
                                if (mismatchCount >= 5) {
                                    handleCriticalError("IP Mismatch (IP утерян). Попробуйте переподключиться.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("connection refused", true) || lineTrim.contains("timeout", true) -> {
                                // Огромный лимит, потому что каждый воркер кидает эту ошибку при смене сети
                                refusedCount++
                                if (refusedCount >= 400) {
                                    handleCriticalError("Критическое отсутствие сети (400+ таймаутов). Отключение.")
                                    return@forEachLine
                                }
                            }
                            lineTrim.contains("9000") || lineTrim.contains("Call not found", true) -> {
                                currentHashErrorCount++
                                // Нужно больше попыток, так как 1 воркер может спамить
                                if (currentHashErrorCount >= 10) {
                                    handleHashError()
                                    return@forEachLine
                                }
                            }
                        }
                    }

                    // 1. Статистика (Обновляемая строка)
                    if (lineTrim.contains("[СТАТИСТИКА]")) {
                        val msg = lineTrim.substringAfter("[СТАТИСТИКА]").trim()
                        stats.value = msg
                        
                        val match = Regex("Активных:\\s*(\\d+)").find(msg)
                        if (match != null) {
                            activeWorkers.value = match.groupValues[1].toIntOrNull() ?: 0
                        }
                        
                        updateLog("stats", "[СТАТИСТИКА] $msg", 3, false)
                        return@forEachLine
                    }

                    // 2. Этапы подключения и Ошибки
                    when {
                        lineTrim.contains("Старт") -> 
                            updateLog("creds_start", "[ВК] Получение учетных данных...", 0, false)
                        lineTrim.contains("Креды OK") || lineTrim.contains("Первые креды") -> 
                            updateLog("creds_ok", "[ВК] Учетные данные получены ✓", 0, false)
                        lineTrim.contains("Relay:") -> 
                            updateLog("dtls_start", "[DTLS] Рукопожатие (Handshake)...", 1, false)
                        lineTrim.contains("DTLS ОК") -> 
                            updateLog("dtls_ok", "[DTLS] Соединение установлено ✓", 1, false)
                        lineTrim.contains("Активна ✓") -> 
                            updateLog("ready", "[READY] Туннель готов к работе ✓", 2, false)
                        
                        // Ошибки (в конец)
                        isError -> {
                            // Формируем уникальный ключ ошибки на основе её типа (группируем по типу ошибки)
                            val errorKey = when {
                                lineTrim.contains("connection refused") -> "err_conn_refused"
                                lineTrim.contains("timeout") -> "err_timeout"
                                lineTrim.contains("кредов") -> "err_creds"
                                lineTrim.contains("DTLS") -> "err_dtls"
                                else -> "general_error_" + lineTrim.take(15).hashCode()
                            }
                            updateLog(errorKey, lineTrim, 99, true)
                        }
                    }

                    // 3. Обработка конфига (Скрываем от пользователя)
                    if (line.contains("╔") && line.contains("WireGuard")) {
                        collectingConfig = true
                        configBuilder.clear()
                        return@forEachLine
                    } else if (collectingConfig) {
                        if (line.contains("╚")) {
                            collectingConfig = false
                            val configStr = configBuilder.toString().trim()
                            config.value = configStr
                            
                            scope.launch(Dispatchers.Main) {
                                try {
                                    wgHelper?.startTunnel(configStr)
                                } catch (e: Exception) {
                                    updateLog("vpn_start_error", "Ошибка запуска VPN: ${e.message}", 99, true)
                                }
                            }
                        } else if (line.contains("║")) {
                            val content = line.replace("║", "").trim()
                            if (content.isNotEmpty()) {
                                configBuilder.appendLine(content)
                            }
                        }
                        return@forEachLine
                    }
                }
            } catch (e: Exception) {
                updateLog("sys_error", "Процесс остановлен: ${e.message}", -1, true)
            } finally {
                running.value = false
                process = null
            }
        }
    }

    private fun handleCriticalError(message: String) {
        updateLog("circuit_breaker", "[СТОП] $message", -1, true)
        stop()
    }

    private fun handleHashError() {
        val params = currentParams ?: return
        val context = lastContext ?: return
        
        currentHashErrorCount = 0
        
        if (params.secondaryVkHash.isNotEmpty() && activeHashIndex == 0) {
            updateLog("hash_switch", "Основной хеш мертв. Переключение на запасной...", 50, true)
            activeHashIndex = 1
            stopOnlyProcess()
            start(context, params, isSwitching = true)
        } else {
            val msg = if (activeHashIndex == 1) "Запасной хеш тоже мертв. Отключение." else "Хеш умер, запасного нет. Отключение."
            handleCriticalError(msg)
        }
    }

    // ==================== WATCHDOG ====================
    // Проверяет, жив ли Go-процесс. Если умер — перезапускает.
    // Если процесс жив, но 0 воркеров уже 30 сек — тоже перезапуск (зомби).
    private fun startWatchdog(context: Context, params: TunnelParams) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var zeroWorkersSince = 0L
            delay(10_000) // Даём 10 сек на старт
            while (isActive && running.value) {
                val proc = process
                if (proc == null || !proc.isAlive) {
                    // Go-процесс мёртв!
                    updateLog("watchdog", "⚠ Процесс упал. Перезапуск...", 50, true)
                    activeWorkers.value = 0
                    killProcess()
                    delay(2000)
                    if (running.value) {
                        start(context, params, isSwitching = true)
                    }
                    return@launch // startWatchdog будет перезапущен из start()
                }

                // Детекция зомби: процесс жив, но 0 воркеров
                val workers = activeWorkers.value
                if (workers <= 0) {
                    if (zeroWorkersSince == 0L) {
                        zeroWorkersSince = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - zeroWorkersSince > 30_000) {
                        updateLog("watchdog", "⚠ Зомби-процесс (0 воркеров 30с). Перезапуск...", 50, true)
                        killProcess()
                        delay(2000)
                        if (running.value) {
                            start(context, params, isSwitching = true)
                        }
                        return@launch
                    }
                } else {
                    zeroWorkersSince = 0L
                }

                delay(5_000)
            }
        }
    }

    fun restartTransport() {
        val params = currentParams ?: return
        val context = lastContext ?: return
        updateLog("network_restart", "[СЕТЬ] Перезапуск транспорта из-за смены сети...", 50, false)
        killProcess() // Только убиваем процесс, running не трогаем!
        scope.launch {
            delay(1500)
            start(context, params, isSwitching = true)
        }
    }

    fun pause() {
        if (!running.value) return
        killProcess() // Не ставим running=false, чтоб сервис не умер
        activeWorkers.value = 0
    }

    fun resume() {
        if (currentParams != null && lastContext != null) {
            scope.launch {
                start(lastContext!!, currentParams!!, isSwitching = true)
            }
        }
    }

    // Убивает процесс без изменения running
    private fun killProcess() {
        watchdogJob?.cancel()
        readerJob?.cancel()
        val proc = process
        process = null
        if (proc != null) {
            try { proc.destroy() } catch (_: Exception) {}
            // Даём 500мс на graceful shutdown
            try { proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            if (proc.isAlive) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
                try { proc.waitFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            }
        }
    }

    private fun stopOnlyProcess() {
        killProcess()
        running.value = false
    }

    private fun log(message: String) {
        updateLog("internal_${message.hashCode()}", message, 50, false)
    }

    fun stop() {
        scope.launch(Dispatchers.Main) {
            wgHelper?.stopTunnel()
        }
        killProcess()
        running.value = false
        activeWorkers.value = 0
        currentParams = null
    }

    // Suspend-версия: гарантирует что процесс мёртв и порт свободен
    suspend fun stopAndWait() {
        withContext(Dispatchers.IO) {
            scope.launch(Dispatchers.Main) {
                wgHelper?.stopTunnel()
            }
            killProcess()
            running.value = false
            activeWorkers.value = 0
            currentParams = null
            // Ждём освобождения порта 9000 (до 3 секунд)
            repeat(30) {
                try {
                    java.net.ServerSocket(9000, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.close() }
                    return@withContext // Порт свободен!
                } catch (_: Exception) {
                    delay(100)
                }
            }
        }
    }

    fun reloadWireGuard() {
        if (running.value) {
            scope.launch {
                wgHelper?.reloadTunnel()
            }
        }
    }

    fun clearLogs() {
        logs.value = emptyList()
        activeWorkers.value = 0
    }
}

data class TunnelParams(
    val peer: String,
    val vkHashes: String,
    val secondaryVkHash: String = "",
    val workersPerHash: Int,
    val port: Int,
    val sni: String = "",
    val connectionPassword: String = ""
)
