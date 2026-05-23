package com.wdtt.client

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.wdtt.client.ui.AppUpdateDialog
import com.wdtt.client.ui.FloatingToolbar
import com.wdtt.client.ui.LogsTab
import com.wdtt.client.ui.SettingsTab
import com.wdtt.client.ui.DeployTab
import com.wdtt.client.ui.ExceptionsTab
import com.wdtt.client.ui.InfoTab
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // VPN permission dialog finished
    }

    private val batteryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkAndRequestVpn()
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        checkAndRequestBattery()
    }

    companion object {
        var activeActivities = 0
        var isForeground: Boolean
            get() = activeActivities > 0
            set(value) {}
    }

    override fun onStart() {
        super.onStart()
        activeActivities++
        ManlCaptchaWebViewManager.checkAndShowPendingCaptcha(this)
    }

    override fun onStop() {
        super.onStop()
        activeActivities--
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkAndRequestNotifications()

        setContent {
            val settingsStore = remember { SettingsStore(this) }
            val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            val isDynamicColor by settingsStore.isDynamicColor.collectAsStateWithLifecycle(initialValue = false)
            val themePalette by settingsStore.themePalette.collectAsStateWithLifecycle(initialValue = "indigo")
            val scope = rememberCoroutineScope()

            WDTTTheme(themeMode = themeMode, dynamicColor = isDynamicColor, themePalette = themePalette) {
                MainScreen(
                    settingsStore = settingsStore,
                    themeMode = themeMode,
                    onThemeChange = { mode ->
                        scope.launch {
                            settingsStore.saveThemeMode(mode)
                        }
                    },
                    isDynamicColor = isDynamicColor,
                    onDynamicColorChange = { enabled ->
                        scope.launch { settingsStore.saveDynamicColor(enabled) }
                    },
                    currentPalette = themePalette,
                    onPaletteChange = { palette ->
                        scope.launch { settingsStore.saveThemePalette(palette) }
                    }
                )
            }
        }
    }

    private fun checkAndRequestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                checkAndRequestBattery()
            }
        } else {
            checkAndRequestBattery()
        }
    }

    private fun checkAndRequestBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batteryLauncher.launch(intent)
            } catch (e: Exception) {
                checkAndRequestVpn()
            }
        } else {
            checkAndRequestVpn()
        }
    }

    private fun checkAndRequestVpn() {
        try {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnLauncher.launch(vpnIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// ═══ Навигация ═══

private data class NavItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val navItems = listOf(
    NavItem("Туннель", Icons.Filled.VpnKey, Icons.Outlined.VpnKey),
    NavItem("Деплой", Icons.Filled.Cloud, Icons.Outlined.Cloud),
    NavItem("Исключ.", Icons.Filled.FilterList, Icons.Outlined.FilterList),
    NavItem("Логи", Icons.Filled.Terminal, Icons.Outlined.Terminal),
    NavItem("Инфо", Icons.Filled.Info, Icons.Outlined.Info),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settingsStore: SettingsStore,
    themeMode: String = "system",
    onThemeChange: (String) -> Unit = {},
    isDynamicColor: Boolean = false,
    onDynamicColorChange: (Boolean) -> Unit = {},
    currentPalette: String = "indigo",
    onPaletteChange: (String) -> Unit = {}
) {
    val unreadErrors by TunnelManager.unreadErrorCount.collectAsStateWithLifecycle()
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val updateCheckIntervalHours by settingsStore.updateCheckIntervalHours.collectAsStateWithLifecycle(
        initialValue = DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
    )
    var pendingRelease by remember { mutableStateOf<AppReleaseInfo?>(null) }
    val currentVersion = remember { "v${BuildConfig.VERSION_NAME.removePrefix("v")}" }
    val safeBottomInset = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
    val navOverlayReserve = safeBottomInset + 96.dp

    LaunchedEffect(selectedTab) {
        if (selectedTab == 3) TunnelManager.clearUnreadErrors()
    }

    LaunchedEffect(updateCheckIntervalHours) {
        if (updateCheckIntervalHours == UPDATE_CHECK_NEVER) return@LaunchedEffect

        val intervalMillis = updateIntervalHoursToMillis(updateCheckIntervalHours)
            ?: updateIntervalHoursToMillis(DEFAULT_UPDATE_CHECK_INTERVAL_HOURS)
            ?: 12L * 60L * 60L * 1000L

        suspend fun runUpdateCheck(reason: String) {
            val checkedAt = System.currentTimeMillis()
            val release = fetchLatestReleaseInfo(currentVersion)
            settingsStore.saveUpdateState(
                lastCheckAt = checkedAt,
                latestVersion = release?.versionTag ?: "",
                error = if (release == null) "Не удалось проверить" else ""
            )

            if (release == null) {
                Log.w("WDTT", "[WARN] Update check: no release info, local=$currentVersion reason=$reason")
                return
            }

            val hasUpdate = isNewerVersion(currentVersion, release.versionTag)
            val postponeVer = settingsStore.updatePostponeVersion.first()
            val postponeUntil = settingsStore.updatePostponeUntil.first()
            val isPostponed = postponeVer == release.versionTag && checkedAt < postponeUntil
            Log.i(
                "WDTT",
                "Update check: local=$currentVersion remote=${release.versionTag} newer=$hasUpdate postponed=$isPostponed reason=$reason"
            )

            if (hasUpdate && !isPostponed) {
                settingsStore.saveUpdateDialogShown(release.versionTag, checkedAt)
                pendingRelease = release
            }
        }

        runUpdateCheck("startup")

        while (isActive) {
            val now = System.currentTimeMillis()
            val lastCheck = settingsStore.updateLastCheckAt.first()
            val nextCheckAt = lastCheck + intervalMillis
            val waitMs = (nextCheckAt - now).coerceAtLeast(intervalMillis)
            delay(waitMs)
            if (isActive) {
                runUpdateCheck("periodic")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppBackdrop(modifier = Modifier.matchParentSize())

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            containerColor = Color.Transparent,
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .pointerInput(selectedTab) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                totalDrag = 0f
                                dragTargetIndex = -1
                                dragProgress = 0f
                            },
                            onDragCancel = {
                                dragTargetIndex = -1
                                dragProgress = 0f
                            },
                            onDragEnd = {
                                if (dragTargetIndex in navItems.indices && dragProgress >= 0.5f) {
                                    selectedTab = dragTargetIndex
                                    if (selectedTab == 3) TunnelManager.clearUnreadErrors()
                                }
                                dragTargetIndex = -1
                                dragProgress = 0f
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            totalDrag += dragAmount
                            if (abs(totalDrag) < 12f) {
                                dragTargetIndex = -1
                                dragProgress = 0f
                                return@detectHorizontalDragGestures
                            }

                            val candidate = if (totalDrag < 0f) selectedTab + 1 else selectedTab - 1
                            if (candidate !in navItems.indices) {
                                dragTargetIndex = -1
                                dragProgress = 0f
                                return@detectHorizontalDragGestures
                            }

                            dragTargetIndex = candidate
                            dragProgress = (abs(totalDrag) / 180f).coerceIn(0f, 1f)
                        }
                    }
            ) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn(tween(300)) togetherWith fadeOut(tween(225))
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = navOverlayReserve),
                    label = "tab_content"
                ) { tab ->
                    when (tab) {
                        0 -> SettingsTab()
                        1 -> DeployTab()
                        2 -> ExceptionsTab()
                        3 -> LogsTab()
                        4 -> InfoTab()
                    }
                }

                ProxyNavigationBar(
                    navItems = navItems,
                    selectedTab = selectedTab,
                    dragTargetIndex = dragTargetIndex,
                    dragProgress = dragProgress,
                    unreadErrors = unreadErrors,
                    tunnelRunning = tunnelRunning,
                    onTabSelected = { index ->
                        if (selectedTab != index) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            selectedTab = index
                            if (index == 3) TunnelManager.clearUnreadErrors()
                        }
                        dragTargetIndex = -1
                        dragProgress = 0f
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // Floating theme toolbar overlay
        FloatingToolbar(
            currentTheme = themeMode,
            onThemeChange = onThemeChange,
            isDynamicColor = isDynamicColor,
            onDynamicColorChange = onDynamicColorChange,
            currentPalette = currentPalette,
            onPaletteChange = onPaletteChange
        )
    }

    pendingRelease?.let { release ->
        AppUpdateDialog(
            release = release,
            onPostpone = {
                pendingRelease = null
                Toast.makeText(context, "Обновление отложено на 24 часа.", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val now = System.currentTimeMillis()
                    settingsStore.saveUpdatePostpone(
                        version = release.versionTag,
                        until = now + 24L * 60L * 60L * 1000L
                    )
                    settingsStore.saveUpdateDialogAction(
                        version = release.versionTag,
                        action = UPDATE_DIALOG_ACTION_POSTPONED,
                        actedAt = now
                    )
                }
            },
            onUpdate = {
                pendingRelease = null
                scope.launch {
                    settingsStore.saveUpdateDialogAction(
                        version = release.versionTag,
                        action = UPDATE_DIALOG_ACTION_UPDATE,
                        actedAt = System.currentTimeMillis()
                    )
                    openReleaseUrl(context, release.releaseUrl)
                }
            }
        )
    }
}

@Composable
private fun ProxyNavigationBar(
    navItems: List<NavItem>,
    selectedTab: Int,
    dragTargetIndex: Int,
    dragProgress: Float,
    unreadErrors: Int,
    tunnelRunning: Boolean,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val selectedColor = colors.primary
    val unselectedColor = colors.onSurfaceVariant.copy(alpha = 0.55f)
    val shellColor = if (isDark) {
        colors.surface.copy(alpha = 0.78f)
    } else {
        lerp(colors.surface, colors.surfaceVariant, 0.48f).copy(alpha = 0.95f)
    }
    val shellBorder = if (isDark) {
        colors.outlineVariant.copy(alpha = 0.42f)
    } else {
        colors.outline.copy(alpha = 0.16f)
    }
    val indicatorColor = if (isDark) {
        colors.primaryContainer.copy(alpha = 0.84f)
    } else {
        lerp(colors.primaryContainer, colors.surface, 0.18f).copy(alpha = 0.97f)
    }
    val indicatorIndex = remember { Animatable(selectedTab.toFloat()) }
    val dragVisualIndex = indicatorIndex.value

    LaunchedEffect(selectedTab) {
        if (dragTargetIndex !in navItems.indices) {
            indicatorIndex.animateTo(
                targetValue = selectedTab.toFloat(),
                animationSpec = tween(
                    durationMillis = 720,
                    easing = CubicBezierEasing(0.2f, 0.9f, 0.24f, 1f)
                )
            )
        }
    }

    LaunchedEffect(selectedTab, dragTargetIndex, dragProgress) {
        if (dragTargetIndex in navItems.indices) {
            val target = selectedTab.toFloat() + (dragTargetIndex - selectedTab) * dragProgress
            indicatorIndex.snapTo(target)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        val trackPadding = 8.dp
        val itemWidth = (maxWidth - trackPadding * 2) / navItems.size
        val indicatorOffset = trackPadding + itemWidth * dragVisualIndex

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = shellColor,
            border = BorderStroke(1.dp, shellBorder),
            tonalElevation = 0.dp,
            shadowElevation = if (isDark) 10.dp else 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = indicatorColor,
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .padding(vertical = 6.dp)
                        .width(itemWidth)
                        .fillMaxHeight()
                ) {}

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = trackPadding, vertical = 6.dp)
                ) {
                    navItems.forEachIndexed { index, item ->
                        val emphasis = (1f - abs(index - dragVisualIndex)).coerceIn(0f, 1f)
                        val iconColor = lerp(unselectedColor, selectedColor, emphasis)

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(22.dp))
                                .clickable { onTabSelected(index) },
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    imageVector = if (emphasis > 0.55f) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label,
                                    modifier = Modifier.size(22.dp),
                                    tint = iconColor
                                )
                                if (index == 3 && unreadErrors > 0) {
                                    Badge(
                                        containerColor = if (tunnelRunning) colors.primary else WDTTColors.warning,
                                        contentColor = colors.onPrimary,
                                        modifier = Modifier.offset(x = 12.dp, y = (-8).dp)
                                    ) {
                                        Text("$unreadErrors")
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (emphasis > 0.55f) FontWeight.SemiBold else FontWeight.Medium,
                                color = iconColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun openReleaseUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
    }
}

private fun android16OrbShape(points: Int, innerRatio: Float): Shape = GenericShape { size, _ ->
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val outerRadius = min(size.width, size.height) / 2f
    val innerRadius = outerRadius * innerRatio

    for (i in 0 until points * 2) {
        val angle = (-PI / 2.0) + (i * PI / points)
        val radius = if (i % 2 == 0) outerRadius else innerRadius
        val x = centerX + (radius * cos(angle)).toFloat()
        val y = centerY + (radius * sin(angle)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private val Android16OrbLarge: Shape = android16OrbShape(points = 18, innerRatio = 0.90f)
private val Android16OrbMedium: Shape = android16OrbShape(points = 20, innerRatio = 0.92f)
private val Android16OrbSmall: Shape = android16OrbShape(points = 16, innerRatio = 0.88f)

@Composable
private fun AppBackdrop(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val baseBrush = remember(colors.background, colors.surface, colors.surfaceVariant) {
        Brush.verticalGradient(
            colors = if (isDark) {
                listOf(
                    lerp(colors.background, colors.surface, 0.18f),
                    colors.background,
                    lerp(colors.surfaceVariant, colors.background, 0.72f)
                )
            } else {
                listOf(
                    lerp(colors.background, colors.surface, 0.78f),
                    colors.background,
                    lerp(colors.surfaceVariant, colors.background, 0.30f)
                )
            }
        )
    }
    val topGlow = colors.primary.copy(alpha = if (isDark) 0.055f else 0.09f)
    val leftGlow = if (isDark) {
        colors.tertiary.copy(alpha = 0.045f)
    } else {
        lerp(colors.tertiary, colors.secondaryContainer, 0.74f).copy(alpha = 0.24f)
    }
    val bottomGlow = if (isDark) {
        colors.primary.copy(alpha = 0.04f)
    } else {
        lerp(colors.secondary, colors.primaryContainer, 0.70f).copy(alpha = 0.22f)
    }
    val lightOrbOutline = colors.outlineVariant.copy(alpha = 0.26f)
    val topOrbGlow = if (isDark) {
        topGlow
    } else {
        lerp(colors.primary, colors.primaryContainer, 0.72f).copy(alpha = 0.32f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseBrush)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-86).dp, y = (-126).dp)
                .size(258.dp)
                .clip(Android16OrbLarge)
                .background(topOrbGlow)
                .then(
                    if (isDark) Modifier else Modifier.border(1.dp, lightOrbOutline, Android16OrbLarge)
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-44).dp, y = 28.dp)
                .size(146.dp)
                .clip(Android16OrbSmall)
                .background(leftGlow)
                .then(
                    if (isDark) Modifier else Modifier.border(1.dp, lightOrbOutline.copy(alpha = 0.22f), Android16OrbSmall)
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 62.dp, y = (-208).dp)
                .size(198.dp)
                .clip(Android16OrbMedium)
                .background(bottomGlow)
                .then(
                    if (isDark) Modifier else Modifier.border(1.dp, lightOrbOutline.copy(alpha = 0.20f), Android16OrbMedium)
                )
        )
    }
}
