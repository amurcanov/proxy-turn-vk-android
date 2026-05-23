package com.wdtt.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import com.wdtt.client.TunnelService
import com.wdtt.client.WDTTColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.roundToInt

private const val WORKERS_PER_GROUP = 9

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }

    val currentDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(currentDensity.density, fontScale = 1f)
    ) {
        SettingsTabContent(context, scope, settingsStore)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabContent(context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, settingsStore: SettingsStore) {
    val savedConnectionPassword by settingsStore.connectionPassword.collectAsStateWithLifecycle(initialValue = "")
    val savedManualPortsEnabled by settingsStore.manualPortsEnabled.collectAsStateWithLifecycle(initialValue = false)
    val savedServerDtlsPort by settingsStore.serverDtlsPort.collectAsStateWithLifecycle(initialValue = 56000)
    val savedServerWgPort by settingsStore.serverWgPort.collectAsStateWithLifecycle(initialValue = 56001)
    val savedListenPort by settingsStore.listenPort.collectAsStateWithLifecycle(initialValue = 9000)

    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()

    val cooldownSeconds by TunnelManager.cooldownSeconds.collectAsStateWithLifecycle()
    var wasRunning by remember { mutableStateOf(false) }

    LaunchedEffect(tunnelRunning) {
        if (wasRunning && !tunnelRunning) {
            TunnelManager.startCooldown(5)
        }
        wasRunning = tunnelRunning
    }

    var peerInput by rememberSaveable { mutableStateOf("") }
    var vkHash1 by rememberSaveable { mutableStateOf("") }
    var vkHash2 by rememberSaveable { mutableStateOf("") }
    var vkHash3 by rememberSaveable { mutableStateOf("") }
    var vkHash4 by rememberSaveable { mutableStateOf("") }
    var workersInput by rememberSaveable { mutableFloatStateOf(18f) }
    var showHashesDialog by rememberSaveable { mutableStateOf(false) }
    var autoCaptchaEnabled by rememberSaveable { mutableStateOf(true) }
    var useWVCaptcha by rememberSaveable { mutableStateOf(false) }
    var isManualMode by rememberSaveable { mutableStateOf(true) }
    var wbvManualMode by rememberSaveable { mutableStateOf(true) }
    var manualPortsEnabled by rememberSaveable { mutableStateOf(false) }
    var serverDtlsPortInput by rememberSaveable { mutableStateOf("56000") }
    var serverWgPortInput by rememberSaveable { mutableStateOf("56001") }

    val allHashes = remember(vkHash1, vkHash2, vkHash3, vkHash4) { listOf(vkHash1, vkHash2, vkHash3, vkHash4) }
    val uniqueHashes = remember(vkHash1, vkHash2, vkHash3, vkHash4) { allHashes.filter { it.isNotBlank() && it.length >= 16 }.distinct() }
    val filledHashCount = remember(vkHash1, vkHash2, vkHash3, vkHash4) { uniqueHashes.size }
    val combinedHashes = remember(vkHash1, vkHash2, vkHash3, vkHash4) { uniqueHashes.joinToString(",") }
    val dynamicMaxWorkers = remember(filledHashCount) { (filledHashCount.coerceAtLeast(1) * 27).toFloat() }
    var portInput by rememberSaveable { mutableStateOf("9000") }
    var sniInput by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(dynamicMaxWorkers) {
        if (workersInput > dynamicMaxWorkers) {
            workersInput = dynamicMaxWorkers
        }
    }

    val currentWorkers = workersInput.coerceIn(WORKERS_PER_GROUP.toFloat(), dynamicMaxWorkers)

    val hashErrors = remember(vkHash1, vkHash2, vkHash3, vkHash4) {
        buildList {
            allHashes.forEachIndexed { i, h ->
                if (h.isNotBlank() && h.length < 16) add("Хеш ${i + 1} — короткий")
            }
            val filled = allHashes.filter { it.isNotBlank() && it.length >= 16 }
            if (filled.size != filled.distinct().size) add("Есть дубликаты хешей")
        }
    }
    val hasInputHashErrors = remember(vkHash1, vkHash2, vkHash3, vkHash4) { hashErrors.isNotEmpty() }

    var showSecretsDialog by rememberSaveable { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }

    fun parseHashes(raw: String) {
        val parts = raw.split(Regex("[,\\s\\n]+")).map { stripVkUrlStatic(it) }.filter { it.isNotEmpty() }
        vkHash1 = parts.getOrElse(0) { "" }
        vkHash2 = parts.getOrElse(1) { "" }
        vkHash3 = parts.getOrElse(2) { "" }
        vkHash4 = parts.getOrElse(3) { "" }
    }

    fun normalizeHashes(vararg hashes: String): String {
        return hashes
            .map { stripVkUrlStatic(it) }
            .filter { it.isNotBlank() && it.length >= 16 }
            .distinct()
            .joinToString(",")
    }

    LaunchedEffect(Unit) {
        val peer = settingsStore.peer.first()
        val hashes = settingsStore.vkHashes.first()
        val workers = settingsStore.workersPerHash.first()
        val port = settingsStore.listenPort.first()
        val manualPorts = settingsStore.manualPortsEnabled.first()
        val serverDtlsPort = settingsStore.serverDtlsPort.first()
        val serverWgPort = settingsStore.serverWgPort.first()
        val sni = settingsStore.sni.first()
        val captchaMode = settingsStore.captchaMode.first()
        val captchaMethod = settingsStore.captchaSolveMethod.first()
        val wbvCaptchaMethod = settingsStore.captchaWbvSolveMethod.first()
        
        peerInput = peer
        parseHashes(hashes)
        workersInput = roundToGroup(workers.toFloat(), (listOf(vkHash1, vkHash2, vkHash3, vkHash4).count { it.isNotBlank() }.coerceAtLeast(1) * 27).toFloat())
        portInput = port.toString()
        manualPortsEnabled = manualPorts
        serverDtlsPortInput = serverDtlsPort.toString()
        serverWgPortInput = serverWgPort.toString()
        sniInput = sni
        autoCaptchaEnabled = captchaMode == "auto"
        useWVCaptcha = captchaMode != "rjs"
        wbvManualMode = wbvCaptchaMethod != "auto"
        isManualMode = if (captchaMode == "wv") wbvManualMode else captchaMethod != "auto"
        
        initialized = true
    }

    LaunchedEffect(savedManualPortsEnabled) {
        manualPortsEnabled = savedManualPortsEnabled
    }

    LaunchedEffect(savedServerDtlsPort) {
        serverDtlsPortInput = savedServerDtlsPort.toString()
    }

    LaunchedEffect(savedServerWgPort) {
        serverWgPortInput = savedServerWgPort.toString()
    }

    LaunchedEffect(savedListenPort) {
        portInput = savedListenPort.toString()
    }

    if (!initialized) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    var saveJob by remember { mutableStateOf<Job?>(null) }

    fun saveTunnelSettingsNow(hashes: String = combinedHashes, onSaved: (() -> Unit)? = null) {
        saveJob?.cancel()
        scope.launch {
            val savedLocalPort = if (manualPortsEnabled) portInput.toIntOrNull()?.coerceIn(1, 65535) ?: 9000 else 9000
            settingsStore.save(
                peerInput, hashes, "",
                workersInput.toInt(), "udp", savedLocalPort, sniInput, false
            )
            onSaved?.invoke()
        }
    }

    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            val savedLocalPort = if (manualPortsEnabled) portInput.toIntOrNull()?.coerceIn(1, 65535) ?: 9000 else 9000
            settingsStore.save(
                peerInput, combinedHashes, "",
                workersInput.toInt(), "udp", savedLocalPort, sniInput, false
            )
        }
    }

    val scrollState = rememberScrollState()

    val isPeerValid = peerInput.isNotBlank() && !peerInput.contains(":")
    val isHashesValid = combinedHashes.isNotBlank()
    val isValid = isPeerValid && isHashesValid && savedConnectionPassword.isNotBlank() && !hasInputHashErrors
    val effectiveServerDtlsPort = if (manualPortsEnabled) serverDtlsPortInput.toIntOrNull()?.coerceIn(1, 65535) ?: 56000 else 56000
    val effectiveLocalPort = if (manualPortsEnabled) portInput.toIntOrNull()?.coerceIn(1, 65535) ?: 9000 else 9000
    var pendingStartAfterVpnPermission by remember { mutableStateOf(false) }

    fun startTunnelService() {
        val effectiveCaptchaMode = if (autoCaptchaEnabled) "auto" else if (useWVCaptcha) "wv" else "rjs"
        val effectiveCaptchaSolveMethod = if (!autoCaptchaEnabled && effectiveCaptchaMode == "wv" && isManualMode) "manual" else "auto"
        saveJob?.cancel()
        scope.launch {
            settingsStore.save(
                peerInput, combinedHashes, "",
                workersInput.toInt(), "udp", effectiveLocalPort, sniInput, false
            )
            settingsStore.saveCaptchaMode(effectiveCaptchaMode)
            settingsStore.saveCaptchaSolveMethod(effectiveCaptchaSolveMethod)
        }
        val intent = Intent(context, TunnelService::class.java).apply {
            action = "START"
            putExtra("peer", "$peerInput:$effectiveServerDtlsPort")
            putExtra("vk_hashes", combinedHashes)
            putExtra("secondary_vk_hash", "")
            putExtra("workers_per_hash", workersInput.toInt())
            putExtra("port", effectiveLocalPort)
            putExtra("sni", sniInput)
            putExtra("connection_password", savedConnectionPassword)
            putExtra("captcha_mode", effectiveCaptchaMode)
            putExtra("captcha_solve_method", effectiveCaptchaSolveMethod)
        }
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
        else context.startService(intent)
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingStartAfterVpnPermission) {
            pendingStartAfterVpnPermission = false
            if (VpnService.prepare(context) == null) {
                startTunnelService()
            } else {
                Toast.makeText(context, "VPN-разрешение не выдано", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun requestVpnAndStart() {
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            pendingStartAfterVpnPermission = true
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startTunnelService()
        }
    }

    // ═══ Dialogs ═══
    if (showSecretsDialog) {
        SecretsDialog(
            settingsStore = settingsStore,
            initialPassword = savedConnectionPassword,
            manualPortsEnabled = manualPortsEnabled,
            initialServerDtlsPort = serverDtlsPortInput,
            initialServerWgPort = serverWgPortInput,
            initialLocalPort = portInput,
            onSaved = { dtls, wg, local ->
                serverDtlsPortInput = dtls
                serverWgPortInput = wg
                portInput = local
            },
            onDismiss = { showSecretsDialog = false }
        )
    }

    if (showHashesDialog) {
        HashesDialog(
            hash1 = vkHash1,
            hash2 = vkHash2,
            hash3 = vkHash3,
            hash4 = vkHash4,
            onSave = { h1, h2, h3, h4 ->
                val cleaned1 = stripVkUrlStatic(h1)
                val cleaned2 = stripVkUrlStatic(h2)
                val cleaned3 = stripVkUrlStatic(h3)
                val cleaned4 = stripVkUrlStatic(h4)
                vkHash1 = cleaned1
                vkHash2 = cleaned2
                vkHash3 = cleaned3
                vkHash4 = cleaned4
                saveTunnelSettingsNow(normalizeHashes(cleaned1, cleaned2, cleaned3, cleaned4)) {
                    showHashesDialog = false
                }
            },
            onDismiss = { showHashesDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ═══ Заголовок раздела ═══
        Text(
            "Настройки туннеля",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        // ═══ Настройки туннеля ═══
        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = peerInput,
                onValueChange = {
                    peerInput = it.filter { c -> c != ' ' }
                    scheduleSave()
                },
                label = { Text("IP сервера или домен (без порта)") },
                placeholder = { Text("1.2.3.4 (или test.com)") },
                singleLine = true,
                isError = !isPeerValid && peerInput.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                )
            )

            OutlinedButton(
                onClick = { showHashesDialog = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(
                    1.dp,
                    if (hasInputHashErrors) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            ) {
                Icon(Icons.Default.Tag, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Настройка VK Хешей ($filledHashCount/4)", fontWeight = FontWeight.SemiBold)
            }

            val errorTexts = hashErrors.filter { !it.contains("короткий") }
            if (errorTexts.isNotEmpty()) {
                Text(
                    text = errorTexts.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // ═══ Мощность + Капча ═══
        AppSectionCard(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
                // — Мощность —
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Мощность",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${currentWorkers.toInt()}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(4.dp))

                val maxWorkers = dynamicMaxWorkers
                val minWorkers = WORKERS_PER_GROUP.toFloat()
                val currentWorkersVal = roundToGroup(currentWorkers.coerceIn(minWorkers, maxWorkers), maxWorkers)

                CompactSteppedSlider(
                    value = currentWorkersVal,
                    onValueChange = { raw ->
                        workersInput = roundToGroup(raw, maxWorkers)
                        scheduleSave()
                    },
                    valueRange = minWorkers..maxWorkers,
                    stepSize = WORKERS_PER_GROUP.toFloat(),
                    enabled = !tunnelRunning,
                    modifier = Modifier.fillMaxWidth()
                )

                // — Разделитель —
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // — Авто капча —
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (autoCaptchaEnabled) "Авто капча" else "Ручная капча",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = autoCaptchaEnabled,
                        onCheckedChange = { enabled ->
                            autoCaptchaEnabled = enabled
                            scope.launch {
                                if (enabled) {
                                    settingsStore.saveCaptchaMode("auto")
                                    settingsStore.saveCaptchaSolveMethod("auto")
                                } else {
                                    val mode = if (useWVCaptcha) "wv" else "rjs"
                                    settingsStore.saveCaptchaMode(mode)
                                    settingsStore.saveCaptchaSolveMethod(if (mode == "wv" && isManualMode) "manual" else "auto")
                                }
                            }
                        }
                    )
                }

                AnimatedVisibility(
                    visible = !autoCaptchaEnabled,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        // — Разделитель —
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // — Метод обхода капчи —
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Метод обхода капчи",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProtocolChip("WBV", useWVCaptcha, enabled = true) {
                                    useWVCaptcha = true
                                    isManualMode = wbvManualMode
                                    scope.launch {
                                        settingsStore.saveCaptchaMode("wv")
                                        settingsStore.saveCaptchaSolveMethod(if (wbvManualMode) "manual" else "auto")
                                    }
                                }
                                ProtocolChip("RJS", !useWVCaptcha, enabled = true, isError = false) {
                                    useWVCaptcha = false
                                    isManualMode = false
                                    scope.launch {
                                        settingsStore.saveCaptchaMode("rjs")
                                        settingsStore.saveCaptchaSolveMethod("auto")
                                    }
                                }
                            }
                        }

                        // — Разделитель —
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // — Режим обхода —
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Режим обхода",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (useWVCaptcha) {
                                    ProtocolChip(
                                        "РУЧ",
                                        isManualMode,
                                        enabled = true,
                                        isError = false
                                    ) {
                                        isManualMode = true
                                        wbvManualMode = true
                                        scope.launch { settingsStore.saveWbvCaptchaSolveMethod("manual") }
                                    }
                                    ProtocolChip(
                                        "АВТ",
                                        !isManualMode,
                                        enabled = true,
                                        isError = false
                                    ) {
                                        isManualMode = false
                                        wbvManualMode = false
                                        scope.launch { settingsStore.saveWbvCaptchaSolveMethod("auto") }
                                    }
                                } else {
                                    ProtocolChip(
                                        "АВТ",
                                        selected = true,
                                        enabled = true,
                                        isError = false
                                    ) {}
                                }
                            }
                        }
                    }
                }
        }

        // ═══ Кнопки: Секреты + Подключить ═══
        val tunnelSecretsMissing = savedConnectionPassword.isBlank()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { showSecretsDialog = true },
                modifier = Modifier.height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (tunnelSecretsMissing) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
                    contentColor = if (tunnelSecretsMissing) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(
                    1.dp,
                    if (tunnelSecretsMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            ) {
                Icon(imageVector = Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Секреты", fontWeight = FontWeight.SemiBold)
            }

            val buttonColor by animateColorAsState(
                targetValue = if (tunnelRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                animationSpec = tween(400),
                label = "btn_color"
            )

            Button(
                onClick = {
                    if (tunnelRunning) {
                        context.startService(
                            Intent(context, TunnelService::class.java).apply { action = "STOP" }
                        )
                    } else {
                        requestVpnAndStart()
                    }
                },
                enabled = (isValid && cooldownSeconds == 0) || tunnelRunning,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = if (tunnelRunning) Icons.Default.Stop else Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        tunnelRunning -> "Остановить"
                        cooldownSeconds > 0 -> "Подождите ($cooldownSeconds)"
                        else -> "Подключить"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }

    }
}

// ═══ Reusable mode chip ═══
@Composable
private fun ProtocolChip(label: String, selected: Boolean, enabled: Boolean = true, isError: Boolean = false, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (isError) MaterialTheme.colorScheme.error else (if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            )
        },
        shape = RoundedCornerShape(16.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurface,
            disabledLabelColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            selectedBorderColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
private fun CompactSteppedSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    stepSize: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val activeColor = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f)
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.55f)
    val thumbStrokeColor = MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { 9.dp.toPx() }
    val trackWidthPx = with(density) { 5.dp.toPx() }

    fun snap(raw: Float): Float {
        val min = valueRange.start
        val max = valueRange.endInclusive
        val snapped = (((raw - min) / stepSize).roundToInt() * stepSize) + min
        return snapped.coerceIn(min, max)
    }

    fun positionToValue(x: Float, width: Float): Float {
        val left = thumbRadiusPx
        val right = (width - thumbRadiusPx).coerceAtLeast(left + 1f)
        val fraction = ((x.coerceIn(left, right) - left) / (right - left)).coerceIn(0f, 1f)
        return snap(valueRange.start + fraction * (valueRange.endInclusive - valueRange.start))
    }

    Canvas(
        modifier = modifier
            .height(34.dp)
            .pointerInput(enabled, valueRange, stepSize) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onValueChange(positionToValue(offset.x, size.width.toFloat()))
                }
            }
            .pointerInput(enabled, valueRange, stepSize) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, _ ->
                    onValueChange(positionToValue(change.position.x, size.width.toFloat()))
                }
            }
    ) {
        val centerY = size.height / 2f
        val left = thumbRadiusPx
        val right = size.width - thumbRadiusPx
        val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1f)
        val fraction = ((value - valueRange.start) / range).coerceIn(0f, 1f)
        val thumbX = left + (right - left) * fraction

        drawLine(
            color = inactiveColor,
            start = Offset(left, centerY),
            end = Offset(right, centerY),
            strokeWidth = trackWidthPx,
            cap = StrokeCap.Round
        )
        drawLine(
            color = activeColor,
            start = Offset(left, centerY),
            end = Offset(thumbX, centerY),
            strokeWidth = trackWidthPx,
            cap = StrokeCap.Round
        )

        val tickCount = (((valueRange.endInclusive - valueRange.start) / stepSize).roundToInt()).coerceAtLeast(1)
        repeat(tickCount + 1) { index ->
            val tickFraction = index / tickCount.toFloat()
            val tickX = left + (right - left) * tickFraction
            drawCircle(
                color = if (tickX <= thumbX) activeColor else inactiveColor,
                radius = 2.dp.toPx(),
                center = Offset(tickX, centerY)
            )
        }

        drawCircle(
            color = activeColor,
            radius = thumbRadiusPx,
            center = Offset(thumbX, centerY)
        )
        drawCircle(
            color = thumbStrokeColor,
            radius = thumbRadiusPx,
            center = Offset(thumbX, centerY),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
        )
    }
}

// ═══ Important Info Dialog ═══
@Composable
fun ImportantInfoDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).padding(8.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Важная информация", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null)
                    }
                }

                Spacer(Modifier.height(16.dp))

                InfoSection("Капча ВК",
                    "По умолчанию в приложении установлен ручной режим (WBV + РУЧ), но его можно заменить на RJS-АВТ. Это продвинутый автоматический метод решения капчи без всплывающих окон и участия человека, основанный на реверс-инжиниринге JS-кода капчи. Он имитирует действия пользователя в фоновом режиме, обеспечивая бесперебойную работу.\n\nВАЖНО: Если в вашем случае RJS не проходит капчу или выдает ошибки (проблемы со связью или изменения на стороне ВК) — переключитесь обратно в ручной режим."
                )
                InfoSection("Как решать капчу",
                    "Она не сложная: нужно просто потянуть слайдер вправо так, чтобы все элементы (обычно это 3 слова) идеально сошлись в пазле."
                )
                InfoSection("Сетевое окружение",
                    "Отключите другие VPN/Прокси и «Приватный DNS» перед использованием."
                )
                InfoSection("Связь потоков и капч",
                    "Рекомендую выбирать 12-36 потока для меньшего количества капч. Если вам всё равно на частоту ввода капчи в фоне — ставьте 48 и более ради скорости."
                )

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    Text("Понятно")
                }
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, body: String) {
    Spacer(Modifier.height(12.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(4.dp))
}

// Округление до ближайшего кратного WORKERS_PER_GROUP
private fun roundToGroup(value: Float, maxW: Float = 96f): Float {
    val rounded = (Math.round(value / WORKERS_PER_GROUP) * WORKERS_PER_GROUP).toFloat()
    return rounded.coerceIn(WORKERS_PER_GROUP.toFloat(), maxW)
}

/** Извлекает хеш из VK ссылки */
private fun stripVkUrlStatic(input: String): String {
    var s = input.trim()
    val lower = s.lowercase()
    val prefixes = listOf(
        "https://vk.com/call/join/",
        "http://vk.com/call/join/",
        "https://m.vk.com/call/join/",
        "http://m.vk.com/call/join/",
        "m.vk.com/call/join/",
        "vk.com/call/join/"
    )
    for (prefix in prefixes) {
        if (lower.startsWith(prefix)) {
            s = s.substring(prefix.length)
            break
        }
    }
    val qIdx = s.indexOf('?')
    if (qIdx != -1) s = s.substring(0, qIdx)
    val hIdx = s.indexOf('#')
    if (hIdx != -1) s = s.substring(0, hIdx)
    return s.trimEnd('/')
}

// ═══ Модальное окно хешей ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HashesDialog(
    hash1: String,
    hash2: String,
    hash3: String,
    hash4: String,
    onSave: (String, String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var h1 by remember { mutableStateOf(hash1) }
    var h2 by remember { mutableStateOf(hash2) }
    var h3 by remember { mutableStateOf(hash3) }
    var h4 by remember { mutableStateOf(hash4) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tag, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("VK Хеши", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                Text(
                    text = "Больше хешей — выше лимит потоков и лучшее распределение нагрузки.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                listOf(
                    Triple("VK Хеш 1 *", h1) { v: String -> h1 = v },
                    Triple("VK Хеш 2", h2) { v: String -> h2 = v },
                    Triple("VK Хеш 3", h3) { v: String -> h3 = v },
                    Triple("VK Хеш 4", h4) { v: String -> h4 = v }
                ).forEachIndexed { idx, (label, value, onChange) ->
                    val isShort = value.isNotBlank() && value.length < 16
                    OutlinedTextField(
                        value = value,
                        onValueChange = { raw ->
                            val cleaned = raw.filter { c -> c != ' ' && c != '\n' }
                            onChange(stripVkUrlStatic(cleaned))
                        },
                        label = { Text(label) },
                        placeholder = { Text("Ссылка звонка или хеш") },
                        singleLine = true,
                        isError = isShort,
                        supportingText = if (isShort) {
                            { Text("Хеш ${idx + 1} — короткий (мин. 16)", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                }

                Button(
                    onClick = {
                        onSave(h1, h2, h3, h4)
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = h1.isNotBlank() && h1.length >= 16,
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    Text("Сохранить", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ═══ Модальное окно секретов ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretsDialog(
    settingsStore: SettingsStore,
    initialPassword: String,
    manualPortsEnabled: Boolean,
    initialServerDtlsPort: String,
    initialServerWgPort: String,
    initialLocalPort: String,
    onSaved: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var passwordInput by rememberSaveable { mutableStateOf(initialPassword) }
    var serverDtlsPort by rememberSaveable { mutableStateOf(initialServerDtlsPort.ifBlank { "56000" }) }
    var serverWgPort by rememberSaveable { mutableStateOf(initialServerWgPort.ifBlank { "56001" }) }
    var localPort by rememberSaveable { mutableStateOf(initialLocalPort.ifBlank { "9000" }) }

    fun normalizePort(value: String, fallback: String): String {
        return value.toIntOrNull()?.takeIf { it in 1..65535 }?.toString() ?: fallback
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
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
                        Text("Секреты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Заданный пароль туннеля") },
                    placeholder = { Text("Придумайте надежный пароль") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )

                if (manualPortsEnabled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Порты", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverDtlsPort,
                        onValueChange = { serverDtlsPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Порт сервера DTLS") },
                        placeholder = { Text("56000") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverWgPort,
                        onValueChange = { serverWgPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Порт сервера WireGuard") },
                        placeholder = { Text("56001") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = localPort,
                        onValueChange = { localPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Локальный порт VPN") },
                        placeholder = { Text("9000") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        val finalDtls = normalizePort(serverDtlsPort, "56000")
                        val finalWg = normalizePort(serverWgPort, "56001")
                        val finalLocal = normalizePort(localPort, "9000")
                        scope.launch {
                            settingsStore.saveConnectionPassword(passwordInput)
                            settingsStore.savePorts(finalDtls.toInt(), finalWg.toInt(), finalLocal.toInt())
                            onSaved(finalDtls, finalWg, finalLocal)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = passwordInput.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(contentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    Text("Сохранить", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// extension
private fun androidx.compose.ui.graphics.Color.luminance(): Float {
    val r = red
    val g = green
    val b = blue
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}
