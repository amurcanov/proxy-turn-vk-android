package com.wdtt.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import com.wdtt.client.TunnelService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.Intent
import android.os.Build

private const val WORKERS_PER_GROUP = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }

    val savedPeer by settingsStore.peer.collectAsStateWithLifecycle(initialValue = "")
    val savedVkHashes by settingsStore.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    val savedWorkersPerHash by settingsStore.workersPerHash.collectAsStateWithLifecycle(initialValue = 16)
    val savedListenPort by settingsStore.listenPort.collectAsStateWithLifecycle(initialValue = 9000)
    val savedSni by settingsStore.sni.collectAsStateWithLifecycle(initialValue = "")
    
    // Пароль подключения
    val savedConnectionPassword by settingsStore.connectionPassword.collectAsStateWithLifecycle(initialValue = "")

    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val activeWorkers by TunnelManager.activeWorkers.collectAsStateWithLifecycle()

    var cooldownSeconds by remember { mutableIntStateOf(0) }
    var wasRunning by remember { mutableStateOf(false) }

    LaunchedEffect(tunnelRunning) {
        if (wasRunning && !tunnelRunning) {
            cooldownSeconds = 2
            while (cooldownSeconds > 0) {
                kotlinx.coroutines.delay(1000)
                cooldownSeconds--
            }
        }
        wasRunning = tunnelRunning
    }

    var peerInput by rememberSaveable { mutableStateOf("") }
    var vkHashesInput by rememberSaveable { mutableStateOf("") }
    var workersInput by rememberSaveable { mutableFloatStateOf(64f) }
    var portInput by rememberSaveable { mutableStateOf("9000") }
    var sniInput by rememberSaveable { mutableStateOf("") }

    val currentWorkers = workersInput

    // Состояние модального окна секретов
    var showSecretsDialog by rememberSaveable { mutableStateOf(false) }

    var initialized by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(savedPeer, savedVkHashes, savedWorkersPerHash, savedListenPort, savedSni) {
        if (!initialized) {
            val hasData = savedPeer.isNotEmpty() || savedVkHashes.isNotEmpty() ||
                    savedWorkersPerHash != 16 || savedSni.isNotEmpty()
            if (hasData) {
                peerInput = savedPeer
                vkHashesInput = savedVkHashes
                workersInput = roundToGroup(savedWorkersPerHash.toFloat())
                portInput = savedListenPort.toString()
                sniInput = savedSni
                initialized = true
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(500)
        if (!initialized) {
            peerInput = savedPeer
            vkHashesInput = savedVkHashes
            workersInput = roundToGroup(savedWorkersPerHash.toFloat())
            portInput = savedListenPort.toString()
            sniInput = savedSni
            initialized = true
        }
    }

    var saveJob by remember { mutableStateOf<Job?>(null) }

    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            settingsStore.save(
                peerInput, vkHashesInput, "",
                workersInput.toInt(), "udp", 9000, sniInput, false
            )
        }
    }

    val scrollState = rememberScrollState()

    val isPeerValid = peerInput.isNotBlank() && !peerInput.contains(":")
    val isHashesValid = vkHashesInput.isNotBlank()
    val isValid = isPeerValid && isHashesValid && savedConnectionPassword.isNotBlank()


    if (showSecretsDialog) {
        SecretsDialog(
            settingsStore = settingsStore,
            initialPassword = savedConnectionPassword,
            onDismiss = { showSecretsDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (tunnelRunning) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(40.dp),
                color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Активно (Потоков: $activeWorkers / ${currentWorkers.toInt()})",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF2E7D32)
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Настройки туннеля", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = peerInput,
                onValueChange = {
                    peerInput = it.filter { c -> c != ' ' }
                    scheduleSave()
                },
                label = { Text("IP сервера (без порта)", fontSize = 12.sp) },
                placeholder = { Text("1.2.3.4") },
                singleLine = true,
                isError = !isPeerValid && peerInput.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            OutlinedTextField(
                value = vkHashesInput,
                onValueChange = {
                    vkHashesInput = it
                    scheduleSave()
                },
                label = { Text("VK Хеш", fontSize = 12.sp) },
                placeholder = { Text("hash1") },
                singleLine = true,
                isError = !isHashesValid && vkHashesInput.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Мощность", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    val numGroups = (currentWorkers.toInt() / WORKERS_PER_GROUP).coerceAtLeast(1)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Потоки (всего)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${currentWorkers.toInt()} ($numGroups гр. × $WORKERS_PER_GROUP)",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    val maxWorkers = 80f
                    val minWorkers = WORKERS_PER_GROUP.toFloat()
                    val numSteps = ((maxWorkers - minWorkers) / WORKERS_PER_GROUP).toInt() - 1
                    val currentWorkersVal = roundToGroup(currentWorkers.coerceIn(minWorkers, maxWorkers))

                    Slider(
                        value = currentWorkersVal,
                        onValueChange = { raw ->
                            workersInput = roundToGroup(raw)
                            scheduleSave()
                        },
                        valueRange = minWorkers..maxWorkers,
                        steps = numSteps.coerceAtLeast(0),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }




        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Кнопка "Секреты"
            OutlinedButton(
                onClick = { showSecretsDialog = true },
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Секреты", fontWeight = FontWeight.SemiBold)
            }

            // Кнопка "Подключить"
            Button(
                onClick = {
                    if (tunnelRunning) {
                        context.startService(
                            Intent(context, TunnelService::class.java).apply { action = "STOP" }
                        )
                    } else {
                        saveJob?.cancel()
                        scope.launch {
                            settingsStore.save(
                                peerInput, vkHashesInput, "",
                                workersInput.toInt(), "udp", 9000, sniInput, false
                            )
                        }
                        val intent = Intent(context, TunnelService::class.java).apply {
                            action = "START"
                            putExtra("peer", "$peerInput:56000")
                            putExtra("vk_hashes", vkHashesInput)
                            putExtra("secondary_vk_hash", "")
                            putExtra("workers_per_hash", workersInput.toInt())
                            putExtra("port", 9000)
                            putExtra("sni", sniInput)
                            putExtra("connection_password", savedConnectionPassword)
                        }
                        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                        else context.startService(intent)
                    }
                },
                enabled = (isValid && cooldownSeconds == 0) || tunnelRunning,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (tunnelRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = when {
                        tunnelRunning -> "Остановить"
                        cooldownSeconds > 0 -> "Подождите ($cooldownSeconds)"
                        else -> "Подключить"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

// Округление до ближайшего кратного WORKERS_PER_GROUP
private fun roundToGroup(value: Float): Float {
    val maxW = 80f
    val rounded = (Math.round(value / WORKERS_PER_GROUP) * WORKERS_PER_GROUP).toFloat()
    return rounded.coerceIn(WORKERS_PER_GROUP.toFloat(), maxW)
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretsDialog(
    settingsStore: SettingsStore,
    initialPassword: String,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var passwordInput by remember { mutableStateOf(initialPassword) }
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
            ) {
                // Заголовок с крестиком
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Секреты",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Пароль туннеля (любой)") },
                    placeholder = { Text("Придумайте надежный пароль") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = {
                        scope.launch {
                            settingsStore.saveConnectionPassword(passwordInput)
                        }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = passwordInput.isNotEmpty()
                ) {
                    Text("Сохранить")
                }
            }
        }
    }
}