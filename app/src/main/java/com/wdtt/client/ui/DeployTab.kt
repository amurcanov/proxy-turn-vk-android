package com.wdtt.client.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.wdtt.client.TunnelService
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.wdtt.client.DeployManager
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

private const val CMD_TIMEOUT = 900000L



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeployTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }

    // Инициализация DeployManager (idempotent)
    LaunchedEffect(Unit) { DeployManager.init(context) }

    val savedIp by settingsStore.deployIp.collectAsStateWithLifecycle(initialValue = "")
    val savedLogin by settingsStore.deployLogin.collectAsStateWithLifecycle(initialValue = "")
    val savedPassword by settingsStore.deployPassword.collectAsStateWithLifecycle(initialValue = "")

    var ip by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val savedMainPass by settingsStore.deployMainPassword.collectAsStateWithLifecycle(initialValue = "")
    val savedAdminId by settingsStore.deployAdminId.collectAsStateWithLifecycle(initialValue = "")
    val savedBotToken by settingsStore.deployBotToken.collectAsStateWithLifecycle(initialValue = "")

    var showSecretsDialog by remember { mutableStateOf(false) }
    var showUninstallDialog by remember { mutableStateOf(false) }

    val isDeploying by DeployManager.isDeploying.collectAsStateWithLifecycle()
    val deployProgress by DeployManager.deployProgress.collectAsStateWithLifecycle()
    val currentStep by DeployManager.currentStep.collectAsStateWithLifecycle()

    LaunchedEffect(savedIp) { if (savedIp.isNotEmpty()) ip = savedIp }
    LaunchedEffect(savedLogin) { if (savedLogin.isNotEmpty()) login = savedLogin }
    LaunchedEffect(savedPassword) { if (savedPassword.isNotEmpty()) password = savedPassword }

    val animatedProgress by animateFloatAsState(
        targetValue = deployProgress,
        animationSpec = tween(durationMillis = 500),
        label = "progress"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Настройки сервера", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)

        // --- Поля ввода ---
        OutlinedTextField(
            value = ip,
            onValueChange = {
                ip = it
                scope.launch { settingsStore.saveDeploy(it, login, password) }
            },
            label = { Text("IP сервера (без порта)") },
            placeholder = { Text("1.2.3.4 (без порта)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            enabled = !isDeploying,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = login,
                onValueChange = {
                    login = it
                    scope.launch { settingsStore.saveDeploy(ip, it, password) }
                },
                label = { Text("Логин") },
                placeholder = { Text("root") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                enabled = !isDeploying,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    scope.launch { settingsStore.saveDeploy(ip, login, it) }
                },
                label = { Text("Пароль SSH") },
                placeholder = { Text("password") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                enabled = !isDeploying,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }

        if (showSecretsDialog) {
            DeploySecretsDialog(
                settingsStore = settingsStore,
                initialMainPass = savedMainPass,
                initialAdminId = savedAdminId,
                initialBotToken = savedBotToken,
                onDismiss = { showSecretsDialog = false }
            )
        }

        // --- Прогресс ---
        if (isDeploying) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentStep,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${(animatedProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }

        // --- Кнопки ---
        OutlinedButton(
            onClick = { showSecretsDialog = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Key, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Секреты (Telegram Bot и Пароли)", fontWeight = FontWeight.SemiBold)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (ip.isBlank() || password.isBlank() || savedMainPass.isBlank()) return@Button
                    val effectiveLogin = if (login.isBlank()) "root" else login
                    val appContext = context.applicationContext
                    DeployManager.scope.launch {
                        try {
                            DeployManager.startDeploy()
                            val intent = Intent(appContext, TunnelService::class.java).apply { action = "DEPLOY_START" }
                            if (Build.VERSION.SDK_INT >= 26) appContext.startForegroundService(intent)
                            else appContext.startService(intent)

                            performDeploy(
                                context = appContext,
                                host = ip, user = effectiveLogin, pass = password,
                                mainPass = savedMainPass, adminId = savedAdminId, botToken = savedBotToken,
                                onProgress = { p, s -> DeployManager.updateProgress(p, s) }
                            )
                        } finally {
                            try { appContext.startService(Intent(appContext, TunnelService::class.java).apply { action = "DEPLOY_STOP" }) } catch (_: Exception) {}
                            // stopDeploy вызывается внутри performDeploy
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !isDeploying && ip.isNotBlank() && password.isNotBlank() && savedMainPass.isNotBlank()
            ) {
                if (isDeploying) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (isDeploying) "Установка..." else "Установить", fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = {
                    if (ip.isBlank() || password.isBlank() || savedMainPass.isBlank()) return@Button
                    showUninstallDialog = true
                },
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                enabled = !isDeploying && ip.isNotBlank() && password.isNotBlank()
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Удалить", fontWeight = FontWeight.Bold)
            }
        }

        if (showUninstallDialog) {
            UninstallConfirmDialog(
                onDismiss = { showUninstallDialog = false },
                onConfirm = {
                    showUninstallDialog = false
                    val effectiveLogin = if (login.isBlank()) "root" else login
                    DeployManager.scope.launch {
                        try {
                            DeployManager.startDeploy()
                            performUninstall(
                                host = ip, user = effectiveLogin, pass = password,
                                onProgress = { p, s -> DeployManager.updateProgress(p, s) }
                            )
                        } catch (_: Exception) {}
                    }
                }
            )
        }

        if (isDeploying) {
            AutoDeploySnake(Modifier.weight(1f).fillMaxWidth())
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}



private class SSHClient(private val session: Session, private val pass: String) {

    fun exec(command: String, timeout: Long = CMD_TIMEOUT): String {
        if (!session.isConnected) {
            DeployManager.writeError("SSH exec: сессия разорвана перед командой: ${command.take(80)}")
            return "error: session is down"
        }

        var channel: ChannelExec? = null
        val result = StringBuilder()

        return try {
            channel = session.openChannel("exec") as ChannelExec
            val cmd = if (command.contains("sudo") && !command.contains("sudo -S")) {
                command.replace("sudo ", "sudo -S ")
            } else command

            channel.setCommand(cmd)
            val outStream = channel.outputStream
            val input = channel.inputStream
            val err = channel.errStream
            channel.connect(15000)

            if (cmd.contains("sudo -S")) {
                outStream.write("$pass\n".toByteArray())
                outStream.flush()
            }

            val reader = input.bufferedReader()
            val errReader = err.bufferedReader()
            val startTime = System.currentTimeMillis()
            val progressRegex = Regex("^WDTT_PROGRESS\\|(\\d+\\.?\\d*)\\|(.+)$")

            while (!channel.isClosed || reader.ready() || errReader.ready()) {
                if (System.currentTimeMillis() - startTime > timeout) {
                    DeployManager.writeError("SSH timeout (${timeout/1000}s): ${command.take(80)}")
                    try { channel.disconnect() } catch (_: Exception) {}
                    return "error: timeout"
                }

                if (reader.ready()) {
                    val line = reader.readLine()
                    if (line != null) {
                        val match = progressRegex.find(line.trim())
                        if (match != null) {
                            val p = match.groupValues[1].toFloatOrNull() ?: 0f
                            DeployManager.updateProgress(p, match.groupValues[2])
                        } else if (!line.contains("WDTT_PROGRESS")) {
                            val clean = line.replace(Regex("\u001B\\[[;\\d]*m"), "")
                            result.appendLine(clean)
                            // Пишем ошибки из скрипта в файл
                            if (clean.contains("[✗]") || clean.contains("FAIL") ||
                                (clean.contains("error", true) && !clean.contains("2>/dev/null"))) {
                                DeployManager.writeError("REMOTE: $clean")
                                TunnelManager.addDeployErrorLog("REMOTE: $clean")
                            }
                        }
                    }
                }
                if (errReader.ready()) {
                    val line = errReader.readLine()
                    if (line != null && !line.contains("password for")) {
                        val clean = line.replace(Regex("\u001B\\[[;\\d]*m"), "")
                        result.appendLine(clean)
                        if (clean.isNotBlank() && !clean.startsWith("Warning:")) {
                            DeployManager.writeError("STDERR: $clean")
                            TunnelManager.addDeployErrorLog("STDERR: $clean")
                        }
                    }
                }
                if (!reader.ready() && !errReader.ready()) Thread.sleep(100)
            }

            result.toString()
        } catch (e: Exception) {
            DeployManager.writeError("SSH exec error: ${e.message} | cmd: ${command.take(80)}")
            TunnelManager.addDeployErrorLog("SSH exec error: ${e.message}")
            "error: ${e.message}"
        } finally {
            try { channel?.disconnect() } catch (_: Exception) {}
        }
    }

    fun upload(localFile: File, remotePath: String) {
        if (!session.isConnected) {
            DeployManager.writeError("SSH upload: сессия разорвана")
            throw Exception("Session is down")
        }
        var sftp: ChannelSftp? = null
        try {
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(15000)
            sftp.put(localFile.absolutePath, remotePath)
        } catch (e: Exception) {
            DeployManager.writeError("SFTP upload error: ${e.message} | file: ${localFile.name}")
            throw e
        } finally {
            try { sftp?.disconnect() } catch (_: Exception) {}
        }
    }
}

/** Создать SSH-сессию с максимальной надёжностью */
private fun createSSHSession(host: String, user: String, pass: String): Session {
    val jsch = JSch()
    val session = jsch.getSession(user, host, 22)
    session.setPassword(pass)
    session.setConfig(Properties().apply {
        put("StrictHostKeyChecking", "no")
        put("ServerAliveInterval", "10")      // Пинг каждые 10 сек
        put("ServerAliveCountMax", "6")        // 6 пропусков = 60 сек до обрыва
        put("ConnectTimeout", "15000")
        put("PreferredAuthentications", "password,keyboard-interactive")
    })
    session.connect(20000)
    return session
}



private suspend fun performDeploy(
    context: Context,
    host: String, user: String, pass: String,
    mainPass: String, adminId: String, botToken: String,
    onProgress: (Float, String) -> Unit
) = withContext(Dispatchers.IO) {
    var session: Session? = null
    try {
        onProgress(0.02f, "Подключение...")
        session = createSSHSession(host, user, pass)
        DeployManager.activeSession = session
        val ssh = SSHClient(session, pass)

        onProgress(0.05f, "Подготовка файлов...")
        val passArg = if (mainPass.isNotBlank()) "-password \"$mainPass\" " else ""
        val adminArg = if (adminId.isNotBlank()) "-admin \"$adminId\" " else ""
        val botArg = if (botToken.isNotBlank()) "-bot-token \"$botToken\" " else ""
        val args = "$passArg$adminArg$botArg".trim()

        // Извлекаем файлы из assets
        val scriptFile = File(context.cacheDir, "deploy.sh")
        val serverFile = File(context.cacheDir, "server")
        try {
            context.assets.open("deploy.sh").use { inp -> FileOutputStream(scriptFile).use { out -> inp.copyTo(out) } }
            context.assets.open("server").use { inp -> FileOutputStream(serverFile).use { out -> inp.copyTo(out) } }
        } catch (e: Exception) {
            DeployManager.writeError("Assets extraction failed: ${e.message}")
            DeployManager.stopDeploy("Ошибка: файлы не найдены в assets")
            return@withContext
        }

        onProgress(0.06f, "Загрузка на сервер...")
        ssh.upload(scriptFile, "/tmp/deploy.sh")
        ssh.upload(serverFile, "/tmp/wdtt-server")
        scriptFile.delete()
        serverFile.delete()

        onProgress(0.08f, "Установка...")
        val output = ssh.exec("sudo env WDTT_ARGS='$args' bash /tmp/deploy.sh", timeout = CMD_TIMEOUT)

        // Проверяем результат
        if (output.contains("✅") || output.contains("Деплой успешно") || output.contains("active")) {
            DeployManager.stopDeploy("success")
            TunnelManager.addDeploySuccessLog("🟢 Деплой успешно завершен. Сервис активен.")
        } else if (output.contains("error:")) {
            DeployManager.writeError("Deploy script output contains error")
            DeployManager.stopDeploy("Ошибка выполнения скрипта (см. errors.log)")
        } else {
            DeployManager.stopDeploy("success")
            TunnelManager.addDeploySuccessLog("🟢 Деплой завершён. (Проверьте подключение)")
        }

    } catch (e: Exception) {
        DeployManager.writeError("Deploy critical: ${e.message}\n${e.stackTraceToString().take(500)}")
        DeployManager.stopDeploy("Ошибка: ${e.message?.take(100)}")
    } finally {
        try { session?.disconnect() } catch (_: Exception) {}
        DeployManager.activeSession = null
    }
}




private suspend fun performUninstall(
    host: String, user: String, pass: String,
    onProgress: (Float, String) -> Unit
) = withContext(Dispatchers.IO) {
    var session: Session? = null
    try {
        onProgress(0.05f, "Подключение...")
        session = createSSHSession(host, user, pass)
        DeployManager.activeSession = session
        val ssh = SSHClient(session, pass)

        onProgress(0.15f, "Остановка сервиса...")
        ssh.exec("sudo systemctl unmask wdtt 2>/dev/null || true", timeout = 10000L)
        ssh.exec("sudo systemctl stop wdtt 2>/dev/null || true", timeout = 15000L)
        ssh.exec("sudo systemctl disable wdtt 2>/dev/null || true", timeout = 15000L)
        ssh.exec("sudo rm -f /etc/systemd/system/wdtt.service", timeout = 10000L)
        ssh.exec("sudo systemctl daemon-reload", timeout = 10000L)

        onProgress(0.30f, "Удаление бинарника...")
        ssh.exec("sudo pkill -9 -f wdtt-server 2>/dev/null || true", timeout = 10000L)
        ssh.exec("sudo rm -f /usr/local/bin/wdtt-server", timeout = 10000L)

        onProgress(0.45f, "Очистка iptables...")
        ssh.exec("sudo bash -c 'for i in 1 2 3 4 5; do iptables -t nat -D POSTROUTING -s 10.66.66.0/24 -j FULLCONENAT 2>/dev/null || true; iptables -t nat -D PREROUTING -j FULLCONENAT 2>/dev/null || true; iptables -t nat -D POSTROUTING -s 10.66.66.0/24 -j MASQUERADE 2>/dev/null || true; iptables -D INPUT -p udp --dport 56000 -j ACCEPT 2>/dev/null || true; iptables -D INPUT -p udp --dport 51820 -j ACCEPT 2>/dev/null || true; iptables -D FORWARD -j ACCEPT 2>/dev/null || true; done'", timeout = 15000L)

        onProgress(0.60f, "Удаление WireGuard...")
        ssh.exec("sudo ip link del wg0 2>/dev/null || true", timeout = 10000L)
        ssh.exec("sudo rm -rf /etc/wireguard/wg-keys.dat /etc/wireguard/passwords.json /etc/wireguard/server.log", timeout = 10000L)
        ssh.exec("sudo fuser -k 51820/udp 56000/udp 2>/dev/null || true", timeout = 10000L)

        onProgress(0.75f, "Удаление Full Cone NAT...")
        ssh.exec("sudo bash /tmp/deploy.sh uninstall 2>/dev/null || true", timeout = 30000L)

        onProgress(0.90f, "Очистка sysctl...")
        ssh.exec("sudo rm -f /etc/sysctl.d/99-wdtt.conf /etc/sysctl.d/99-vpn.conf", timeout = 10000L)
        ssh.exec("sudo sysctl --system >/dev/null 2>&1 || true", timeout = 15000L)

        onProgress(1.0f, "Готово!")
        DeployManager.stopDeploy("success")

    } catch (e: Exception) {
        DeployManager.writeError("Uninstall error: ${e.message}")
        DeployManager.stopDeploy("Ошибка: ${e.message?.take(100)}")
    } finally {
        try { session?.disconnect() } catch (_: Exception) {}
        DeployManager.activeSession = null
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeploySecretsDialog(
    settingsStore: SettingsStore,
    initialMainPass: String,
    initialAdminId: String,
    initialBotToken: String,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var passInput by remember { mutableStateOf(initialMainPass) }
    var adminIdInput by remember { mutableStateOf(initialAdminId) }
    var botTokenInput by remember { mutableStateOf(initialBotToken) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(20.dp).fillMaxWidth()) {
                Text("Секреты Деплоя", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = passInput, onValueChange = { passInput = it }, label = { Text("Пароль туннеля (любой) *") }, placeholder = { Text("Придумайте надежный пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Телеграм бот для управления", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = adminIdInput, onValueChange = { adminIdInput = it }, label = { Text("ID Админа (Опционально)") }, placeholder = { Text("ID из @getmyid_bot") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = botTokenInput, onValueChange = { botTokenInput = it }, label = { Text("Токен Бота (Опционально)") }, placeholder = { Text("Токен от BotFather") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        scope.launch { settingsStore.saveDeploySecrets(passInput, adminIdInput, botTokenInput) }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = passInput.isNotBlank()
                ) { Text("Сохранить") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UninstallConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var confirmText by remember { mutableStateOf("") }
    val isConfirmed = confirmText.trim().lowercase() == "да"

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Удаление WDTT с сервера", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Text("Будут удалены: бинарник, systemd-сервис, бот, конфигурация WireGuard, правила iptables и модуль Full Cone NAT.\n\nЭто действие необратимо.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = confirmText, onValueChange = { confirmText = it }, label = { Text("Введите «да» для подтверждения") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.error, unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp)) { Text("Отмена") }
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp), enabled = isConfirmed, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Удалить", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun String.containsAny(vararg items: String) = items.any { contains(it, ignoreCase = true) }



data class SnakePoint(val x: Int, val y: Int)

@Composable
fun AutoDeploySnake(modifier: Modifier = Modifier) {
    val gridSize = 15 // лог. размер сетки
    val updateIntervalMs = 70L // быстрее!

    var snake by remember { mutableStateOf(listOf(SnakePoint(5, 5), SnakePoint(5, 4), SnakePoint(5, 3))) }
    var food by remember { mutableStateOf(SnakePoint(10, 10)) }
    var score by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val random = java.util.Random()
        while (true) {
            kotlinx.coroutines.delay(updateIntervalMs)
            
            val head = snake.first()

            // Простое AI (ищет кратчайший путь)
            val dirs = listOf(
                Pair(1, 0), Pair(-1, 0), Pair(0, 1), Pair(0, -1)
            )

            var bestDist = Int.MAX_VALUE
            var bestPt: SnakePoint? = null

            for (d in dirs) {
                val nx = head.x + d.first
                val ny = head.y + d.second
                val p = SnakePoint(nx, ny)

                if (nx in 0 until gridSize && ny in 0 until gridSize) {
                    if (!snake.contains(p) || (snake.last() == p)) {
                        val dist = Math.abs(nx - food.x) + Math.abs(ny - food.y)
                        if (dist < bestDist) {
                            bestDist = dist
                            bestPt = p
                        }
                    }
                }
            }

            if (bestPt == null) {
                // Если застряла или умерла - рестарт
                snake = listOf(SnakePoint(5, 5), SnakePoint(5, 4), SnakePoint(5, 3))
                food = SnakePoint(random.nextInt(gridSize), random.nextInt(gridSize))
                score = 0
            } else {
                val newHead = bestPt
                if (newHead == food) {
                    snake = listOf(newHead) + snake
                    score++
                    var nf = SnakePoint(random.nextInt(gridSize), random.nextInt(gridSize))
                    while (snake.contains(nf)) {
                        nf = SnakePoint(random.nextInt(gridSize), random.nextInt(gridSize))
                    }
                    food = nf
                } else {
                    snake = listOf(newHead) + snake.dropLast(1)
                }
            }
        }
    }

    Surface(
        modifier = modifier.padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = androidx.compose.ui.graphics.Color(0xFF1E1E1E) // Темный фон "консоли"
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Счёт: $score (AI ест яблоки)", 
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp), 
                style = MaterialTheme.typography.labelMedium, 
                color = androidx.compose.ui.graphics.Color.Gray
            )
            
            val snakeColor = androidx.compose.ui.graphics.Color(0xFF81C784) // Приятный светло-зеленый
            
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                // Делаем ячейки 1:1, квадратиками
                val cellW = size.width / gridSize
                val cellH = size.height / gridSize
                val cellActualSize = kotlin.math.min(cellW, cellH)
                
                // Центрируем игровое поле
                val offsetX = (size.width - cellActualSize * gridSize) / 2f
                val offsetY = (size.height - cellActualSize * gridSize) / 2f
                
                // Draw Food
                drawRoundRect(
                    color = androidx.compose.ui.graphics.Color.Red,
                    topLeft = androidx.compose.ui.geometry.Offset(offsetX + food.x * cellActualSize + 2f, offsetY + food.y * cellActualSize + 2f),
                    size = androidx.compose.ui.geometry.Size(cellActualSize - 4f, cellActualSize - 4f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                )

                // Draw Snake
                snake.forEachIndexed { i, p ->
                    val isHead = i == 0
                    drawRoundRect(
                        color = if (isHead) snakeColor else snakeColor.copy(alpha = 0.7f),
                        topLeft = androidx.compose.ui.geometry.Offset(offsetX + p.x * cellActualSize + 2f, offsetY + p.y * cellActualSize + 2f),
                        size = androidx.compose.ui.geometry.Size(cellActualSize - 4f, cellActualSize - 4f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                    )
                }
            }
        }
    }
}