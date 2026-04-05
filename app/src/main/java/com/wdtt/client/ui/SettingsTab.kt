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
import androidx.compose.material.icons.filled.Tag

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
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.SettingsStore
import com.wdtt.client.TunnelManager
import com.wdtt.client.TunnelService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.Intent
import android.os.Build

private const val WORKERS_PER_GROUP = 12

// Coffee theme colors for protocol chips
private val CoffeeBrown = Color(0xFF6D4C41)
private val DarkCoffee = Color(0xFF3E2723)
private val SoftLatte = Color(0xFFD7CCC8)
private val WarmMocha = Color(0xFF8D6E63)
private val CreamBeige = Color(0xFFF5F0EB)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }

    // Игнорируем системный масштаб шрифта ( accessibility font scale )
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
    val savedPeer by settingsStore.peer.collectAsStateWithLifecycle(initialValue = "")
    val savedVkHashes by settingsStore.vkHashes.collectAsStateWithLifecycle(initialValue = "")
    val savedWorkersPerHash by settingsStore.workersPerHash.collectAsStateWithLifecycle(initialValue = 16)
    val savedProtocol by settingsStore.protocol.collectAsStateWithLifecycle(initialValue = "udp")
    val savedListenPort by settingsStore.listenPort.collectAsStateWithLifecycle(initialValue = 9000)
    val savedSni by settingsStore.sni.collectAsStateWithLifecycle(initialValue = "")
    
    // Пароль подключения
    val savedConnectionPassword by settingsStore.connectionPassword.collectAsStateWithLifecycle(initialValue = "")

    // Режим обхода капчи
    val savedCaptchaMode by settingsStore.captchaMode.collectAsStateWithLifecycle(initialValue = "rjs")

    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val activeWorkers by TunnelManager.activeWorkers.collectAsStateWithLifecycle()


    var cooldownSeconds by remember { mutableIntStateOf(0) }
    var wasRunning by remember { mutableStateOf(false) }

    LaunchedEffect(tunnelRunning) {
        if (wasRunning && !tunnelRunning) {
            cooldownSeconds = 5
            while (cooldownSeconds > 0) {
                kotlinx.coroutines.delay(1000)
                cooldownSeconds--
            }
        }
        wasRunning = tunnelRunning
    }

    var peerInput by rememberSaveable { mutableStateOf("") }
    var vkHash1 by rememberSaveable { mutableStateOf("") }
    var vkHash2 by rememberSaveable { mutableStateOf("") }
    var vkHash3 by rememberSaveable { mutableStateOf("") }
    var workersInput by rememberSaveable { mutableFloatStateOf(24f) }
    var useTcp by rememberSaveable { mutableStateOf(false) }
    var showHashesDialog by rememberSaveable { mutableStateOf(false) }
    var useWVCaptcha by rememberSaveable { mutableStateOf(false) }

    val allHashes = listOf(vkHash1, vkHash2, vkHash3)
    val validHashes = allHashes.filter { it.isNotBlank() && it.length >= 16 }
    val uniqueHashes = validHashes.distinct()
    val filledHashCount = uniqueHashes.size
    val combinedHashes = uniqueHashes.joinToString(",")
    val dynamicMaxWorkers = (filledHashCount.coerceAtLeast(1) * 24).toFloat()
    var portInput by rememberSaveable { mutableStateOf("9000") }
    var sniInput by rememberSaveable { mutableStateOf("") }

    // Авто-клэмп: если убрали хеш и воркеров стало больше макс — снизить
    LaunchedEffect(dynamicMaxWorkers) {
        if (workersInput > dynamicMaxWorkers) {
            workersInput = dynamicMaxWorkers
        }
    }

    val currentWorkers = workersInput.coerceIn(WORKERS_PER_GROUP.toFloat(), dynamicMaxWorkers)

    // Проверка ошибок хешей (per-field)
    val hashErrors = buildList {
        allHashes.forEachIndexed { i, h ->
            if (h.isNotBlank() && h.length < 16) add("Хеш ${i + 1} — короткий")
        }
        // Проверка дублей
        val filled = allHashes.filter { it.isNotBlank() && it.length >= 16 }
        if (filled.size != filled.distinct().size) add("Есть дубликаты хешей")
    }
    val hasInputHashErrors = hashErrors.isNotEmpty()

    // Состояние модальных окон
    var showSecretsDialog by rememberSaveable { mutableStateOf(false) }
    var showImportantInfoDialog by rememberSaveable { mutableStateOf(false) }

    var initialized by remember { mutableStateOf(false) }

    fun parseHashes(raw: String) {
        val parts = raw.split(Regex("[,\\s\\n]+")).map { stripVkUrlStatic(it) }.filter { it.isNotEmpty() }
        vkHash1 = parts.getOrElse(0) { "" }
        vkHash2 = parts.getOrElse(1) { "" }
        vkHash3 = parts.getOrElse(2) { "" }
    }

    LaunchedEffect(savedPeer, savedVkHashes, savedWorkersPerHash, savedProtocol, savedListenPort, savedSni) {
        if (!initialized) {
            val hasData = savedPeer.isNotEmpty() || savedVkHashes.isNotEmpty() ||
                    savedWorkersPerHash != 16 || savedSni.isNotEmpty()
            if (hasData) {
                peerInput = savedPeer
                parseHashes(savedVkHashes)
                workersInput = roundToGroup(savedWorkersPerHash.toFloat(), (listOf(vkHash1, vkHash2, vkHash3).count { it.isNotBlank() }.coerceAtLeast(1) * 32).toFloat())
                portInput = savedListenPort.toString()
                sniInput = savedSni
                useTcp = savedProtocol == "tcp"
                useWVCaptcha = savedCaptchaMode == "wv"
                initialized = true
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(500)
        if (!initialized) {
            peerInput = savedPeer
            parseHashes(savedVkHashes)
            workersInput = roundToGroup(savedWorkersPerHash.toFloat(), (listOf(vkHash1, vkHash2, vkHash3).count { it.isNotBlank() }.coerceAtLeast(1) * 32).toFloat())
            portInput = savedListenPort.toString()
            sniInput = savedSni
            useTcp = savedProtocol == "tcp"
            useWVCaptcha = savedCaptchaMode == "wv"
            initialized = true
        }
    }

    var saveJob by remember { mutableStateOf<Job?>(null) }

    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            settingsStore.save(
                peerInput, combinedHashes, "",
                workersInput.toInt(), if (useTcp) "tcp" else "udp", 9000, sniInput, false
            )
        }
    }

    val scrollState = rememberScrollState()

    val isPeerValid = peerInput.isNotBlank() && !peerInput.contains(":")
    val isHashesValid = combinedHashes.isNotBlank()
    val isValid = isPeerValid && isHashesValid && savedConnectionPassword.isNotBlank() && !hasInputHashErrors

    // ═══ Модальное окно секретов ═══
    if (showSecretsDialog) {
        SecretsDialog(
            settingsStore = settingsStore,
            initialPassword = savedConnectionPassword,
            onDismiss = { showSecretsDialog = false }
        )
    }

    // ═══ Модальное окно хешей ═══
    if (showHashesDialog) {
        HashesDialog(
            hash1 = vkHash1,
            hash2 = vkHash2,
            hash3 = vkHash3,
            onSave = { h1, h2, h3 ->
                vkHash1 = h1
                vkHash2 = h2
                vkHash3 = h3
                scheduleSave()
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
        // ═══ Статус бар (как в DeployTab — одинаковый стиль) ═══
        if (tunnelRunning) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF4CAF50).copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Активно (Потоков: $activeWorkers / ${currentWorkers.toInt()})",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF2E7D32)
                    )
                }
            }
        }
        


        // ═══ Настройки туннеля (шрифты как в DeployTab) ═══
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Настройки туннеля", 
                style = MaterialTheme.typography.titleMedium, 
                color = MaterialTheme.colorScheme.primary, 
                fontWeight = FontWeight.SemiBold
            )

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
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            // Кнопка "Хеши" → открывает модалку
            OutlinedButton(
                onClick = { showHashesDialog = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (hasInputHashErrors) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            ) {
                Icon(Icons.Default.Tag, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Настройка VK Хешей", fontWeight = FontWeight.SemiBold)
            }

            // Ошибки хешей (дубли и тд)
            val errorTexts = hashErrors.filter { !it.contains("короткий") }
            if (errorTexts.isNotEmpty()) {
                Text(
                    text = "⚠️ ${errorTexts.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // ═══ Мощность ═══
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Мощность", 
                style = MaterialTheme.typography.titleMedium, 
                color = MaterialTheme.colorScheme.primary, 
                fontWeight = FontWeight.SemiBold
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    val numGroups = (currentWorkers.toInt() / WORKERS_PER_GROUP).coerceAtLeast(1)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Потоки (всего)", 
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${currentWorkers.toInt()} ($numGroups гр. × $WORKERS_PER_GROUP)",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    val maxWorkers = dynamicMaxWorkers
                    val minWorkers = WORKERS_PER_GROUP.toFloat()
                    // steps = кол-во промежуточных точек МЕЖДУ min и max
                    // Для 8,16,24 (3 позиции) нужно steps=1, т.к. 16 единственная промежуточная
                    val totalPositions = ((maxWorkers - minWorkers) / WORKERS_PER_GROUP).toInt() + 1
                    val numSteps = (totalPositions - 2).coerceAtLeast(0)
                    val currentWorkersVal = roundToGroup(currentWorkers.coerceIn(minWorkers, maxWorkers), maxWorkers)

                    Slider(
                        value = currentWorkersVal,
                        onValueChange = { raw ->
                            workersInput = roundToGroup(raw, maxWorkers)
                            scheduleSave()
                        },
                        valueRange = minWorkers..maxWorkers,
                        steps = numSteps,
                        enabled = !tunnelRunning,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }

        // ═══ TCP/UDP выбор — в кофейных тонах ═══
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Протокол TURN", 
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (useTcp) "TCP — выше стабильность" else "UDP — рекомендуется",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // TCP Chip — кофейный стиль
                    FilterChip(
                        selected = useTcp,
                        onClick = { useTcp = true; scheduleSave() },
                        label = { 
                            Text(
                                "TCP", 
                                fontWeight = if (useTcp) FontWeight.Bold else FontWeight.Medium,
                                color = if (useTcp) Color.White else DarkCoffee
                            ) 
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CoffeeBrown,
                            selectedLabelColor = Color.White,
                            containerColor = SoftLatte,
                            labelColor = DarkCoffee
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = useTcp,
                            borderColor = WarmMocha.copy(alpha = 0.4f),
                            selectedBorderColor = CoffeeBrown
                        )
                    )
                    // UDP Chip — кофейный стиль
                    FilterChip(
                        selected = !useTcp,
                        onClick = { useTcp = false; scheduleSave() },
                        label = { 
                            Text(
                                "UDP", 
                                fontWeight = if (!useTcp) FontWeight.Bold else FontWeight.Medium,
                                color = if (!useTcp) Color.White else DarkCoffee
                            ) 
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CoffeeBrown,
                            selectedLabelColor = Color.White,
                            containerColor = SoftLatte,
                            labelColor = DarkCoffee
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = !useTcp,
                            borderColor = WarmMocha.copy(alpha = 0.4f),
                            selectedBorderColor = CoffeeBrown
                        )
                    )
                }
            }
        }

        // ═══ Обход капчи ВК — WV / RJS ═══
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Обход капчи ВК",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (useWVCaptcha) "WebView — нативный браузер" else "ReverseJS — эмуляция бота",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = useWVCaptcha,
                        onClick = {
                            useWVCaptcha = true
                            scope.launch { settingsStore.saveCaptchaMode("wv") }
                        },
                        label = {
                            Text(
                                "WV",
                                fontWeight = if (useWVCaptcha) FontWeight.Bold else FontWeight.Medium,
                                color = if (useWVCaptcha) Color.White else DarkCoffee
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CoffeeBrown,
                            selectedLabelColor = Color.White,
                            containerColor = SoftLatte,
                            labelColor = DarkCoffee
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = useWVCaptcha,
                            borderColor = WarmMocha.copy(alpha = 0.4f),
                            selectedBorderColor = CoffeeBrown
                        )
                    )
                    FilterChip(
                        selected = !useWVCaptcha,
                        onClick = {
                            useWVCaptcha = false
                            scope.launch { settingsStore.saveCaptchaMode("rjs") }
                        },
                        label = {
                            Text(
                                "RJS",
                                fontWeight = if (!useWVCaptcha) FontWeight.Bold else FontWeight.Medium,
                                color = if (!useWVCaptcha) Color.White else DarkCoffee
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CoffeeBrown,
                            selectedLabelColor = Color.White,
                            containerColor = SoftLatte,
                            labelColor = DarkCoffee
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = !useWVCaptcha,
                            borderColor = WarmMocha.copy(alpha = 0.4f),
                            selectedBorderColor = CoffeeBrown
                        )
                    )
                }
            }
        }

        // ═══ Кнопки: Секреты + Подключить (шрифты как в DeployTab) ═══
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Кнопка "Секреты"
            OutlinedButton(
                onClick = { showSecretsDialog = true },
                modifier = Modifier.height(50.dp),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Секреты", fontWeight = FontWeight.SemiBold)
            }

            // Кнопка "Подключить" — неактивна при ошибках хешей
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
                                peerInput, combinedHashes, "",
                                workersInput.toInt(), if (useTcp) "tcp" else "udp", 9000, sniInput, false
                            )
                        }
                        val intent = Intent(context, TunnelService::class.java).apply {
                            action = "START"
                            putExtra("peer", "$peerInput:56000")
                            putExtra("vk_hashes", combinedHashes)
                            putExtra("secondary_vk_hash", "")
                            putExtra("workers_per_hash", workersInput.toInt())
                            putExtra("port", 9000)
                            putExtra("sni", sniInput)
                            putExtra("connection_password", savedConnectionPassword)
                            putExtra("protocol", if (useTcp) "tcp" else "udp")
                            putExtra("captcha_mode", if (useWVCaptcha) "wv" else "rjs")
                        }
                        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                        else context.startService(intent)
                    }
                },
                enabled = (isValid && cooldownSeconds == 0) || tunnelRunning,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(12.dp),
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
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        // ═══ Кнопка Важной информации (Full Width) ═══
        OutlinedButton(
            onClick = { showImportantInfoDialog = true },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent,
                contentColor = CoffeeBrown
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
        ) {
            Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = CoffeeBrown)
            Spacer(Modifier.width(8.dp))
            Text("Важно — ознакомьтесь!", fontWeight = FontWeight.SemiBold, color = CoffeeBrown)
        }

        // ═══════════════════════════════════════════════════════════════
        // Модальное окно с важной информацией
        // ═══════════════════════════════════════════════════════════════
        if (showImportantInfoDialog) {
            Dialog(
                onDismissRequest = { showImportantInfoDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.95f).padding(8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Важная информация", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showImportantInfoDialog = false }) {
                                Icon(Icons.Default.Close, null)
                            }
                        }
                        
                        Spacer(Modifier.height(16.dp))
                        
                        Text(
                            "🌐 Капча ВК", 
                            style = MaterialTheme.typography.titleMedium, 
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Режим WebView рекомендуется как основной, так как показывает лучшую стабильность по сравнению с ReverseJS. ReverseJS можно использовать как запасной.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "🛡️ Сетевое окружение", 
                            style = MaterialTheme.typography.titleMedium, 
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Перед использованием советуем отключать другие VPN или Прокси на устройстве. Также стоит выключить «Приватный DNS» в настройках Android.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "⚡ Рекомендации по потокам", 
                            style = MaterialTheme.typography.titleMedium, 
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Выбирайте количество воркеров в зависимости от скорости вашего интернета:\n" +
                            "• 12-24: при 8-35+ Мбит/с\n" +
                            "• 24-48: при 35-60+ Мбит/с\n" +
                            "• Выше 48: при 70+ Мбит/с",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Важно: чем больше воркеров, тем меньше гарантируется стабильность.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "📊 Факторы скорости", 
                            style = MaterialTheme.typography.titleMedium, 
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Если скорость ниже ожидаемой, на это влияют:\n" +
                            "1. Производительность устройства (слабее = медленнее крипто-операции).\n" +
                            "2. Расстояние до VPS (чем дальше от VK TURN, тем выше задержки и потери).\n" +
                            "3. Нагрузка серверов ВК (может варьироваться в разное время).",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { showImportantInfoDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Понятно")
                        }
                    }
                }
            }
        }
    }
}

// Округление до ближайшего кратного WORKERS_PER_GROUP
private fun roundToGroup(value: Float, maxW: Float = 96f): Float {
    val rounded = (Math.round(value / WORKERS_PER_GROUP) * WORKERS_PER_GROUP).toFloat()
    return rounded.coerceIn(WORKERS_PER_GROUP.toFloat(), maxW)
}

/** Извлекает хеш из VK ссылки */
private fun stripVkUrlStatic(input: String): String {
    var s = input.trim()
    s = s.removePrefix("https://vk.com/call/join/")
        .removePrefix("http://vk.com/call/join/")
        .removePrefix("vk.com/call/join/")
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
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var h1 by remember { mutableStateOf(hash1) }
    var h2 by remember { mutableStateOf(hash2) }
    var h3 by remember { mutableStateOf(hash3) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Заголовок
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
                    text = "С каждым добавленным хешем, лимит на потоки увеличивается. Не единичное кол-во хешей помогает лучше распределять потоки — группы, по структуре.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // 3 поля хешей
                listOf(
                    Triple("VK Хеш 1 *", h1) { v: String -> h1 = v },
                    Triple("VK Хеш 2", h2) { v: String -> h2 = v },
                    Triple("VK Хеш 3", h3) { v: String -> h3 = v }
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
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }

                // Кнопка сохранить
                Button(
                    onClick = {
                        onSave(h1, h2, h3)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = h1.isNotBlank() && h1.length >= 16
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
                    label = { Text("Заданный пароль туннеля") },
                    placeholder = { Text("Придумайте надежный пароль") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

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
                    Text("Сохранить", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}