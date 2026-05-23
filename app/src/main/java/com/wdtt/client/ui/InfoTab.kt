package com.wdtt.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.BuildConfig
import com.wdtt.client.R
import com.wdtt.client.SettingsStore
import com.wdtt.client.UPDATE_DIALOG_ACTION_POSTPONED
import com.wdtt.client.UPDATE_DIALOG_ACTION_UPDATE
import com.wdtt.client.WDTTColors
import com.wdtt.client.fetchLatestReleaseInfo
import com.wdtt.client.isNewerVersion
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val ReleasesUrl = "https://github.com/amurcanov/proxy-turn-vk-android/releases"
private const val IssuesUrl = "https://github.com/amurcanov/proxy-turn-vk-android/issues/new"
private const val DeveloperProfileUrl = "https://github.com/amurcanov"
private const val RepositoryUrl = "https://github.com/amurcanov/proxy-turn-vk-android"
private const val DonateUrl = ""
private val DonateActionButtonColor = Color(0xFF00AEA5)

private val browserPackages = listOf(
    "com.android.chrome",
    "com.google.android.googlequicksearchbox",
    "org.mozilla.firefox",
    "com.yandex.browser",
    "ru.yandex.searchplugin",
    "com.yandex.browser.lite",
    "com.opera.browser",
    "com.opera.mini.native",
    "com.microsoft.emmx",
    "com.brave.browser",
    "com.duckduckgo.mobile.android",
    "com.sec.android.app.sbrowser",
    "com.vivaldi.browser",
    "com.kiwibrowser.browser",
)

private val Android16BlobShape: Shape = GenericShape { size, _ ->
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val outerRadius = min(size.width, size.height) / 2f
    val innerRadius = outerRadius * 0.92f
    val points = 14

    for (i in 0 until points * 2) {
        val angle = (-PI / 2.0) + (i * PI / points)
        val radius = if (i % 2 == 0) outerRadius else innerRadius
        val x = centerX + (radius * cos(angle)).toFloat()
        val y = centerY + (radius * sin(angle)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun openUrlInBrowser(context: Context, url: String) {
    try {
        val pm = context.packageManager
        val uri = Uri.parse(url)
        for (pkg in browserPackages) {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                setPackage(pkg)
            }
            if (intent.resolveActivity(pm) != null) {
                context.startActivity(intent)
                return
            }
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { addCategory(Intent.CATEGORY_BROWSABLE) }
        if (intent.resolveActivity(pm) != null) context.startActivity(intent)
    } catch (_: Exception) {
    }
}

@Composable
fun InfoTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    val currentVersion = remember { "v${BuildConfig.VERSION_NAME.removePrefix("v")}" }
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var pendingManualRelease by remember { mutableStateOf<com.wdtt.client.AppReleaseInfo?>(null) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showDonateDialog by remember { mutableStateOf(false) }
    var actionsExpanded by rememberSaveable { mutableStateOf(true) }
    var projectExpanded by rememberSaveable { mutableStateOf(true) }
    val updateLatestVersion by settingsStore.updateLatestVersion.collectAsStateWithLifecycle(initialValue = "")
    val updateLastError by settingsStore.updateLastError.collectAsStateWithLifecycle(initialValue = "")
    val updateStatus = remember(isCheckingUpdates, updateLatestVersion, updateLastError, currentVersion) {
        when {
            isCheckingUpdates -> "Проверяем GitHub releases..."
            updateLatestVersion.isNotBlank() && isNewerVersion(currentVersion, updateLatestVersion) ->
                "На GitHub доступна версия $updateLatestVersion"
            updateLatestVersion.isNotBlank() -> "Последняя версия: $updateLatestVersion"
            updateLastError.isNotBlank() -> "Последняя проверка завершилась ошибкой"
            else -> "Проверить GitHub вручную"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Информация",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        InfoHeroCard(currentVersion = currentVersion, onSupportClick = { showDonateDialog = true })

        ExpandableSectionCard(
            title = "Действия",
            itemCount = "4 пункта",
            expanded = actionsExpanded,
            onToggle = { actionsExpanded = !actionsExpanded },
            icon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoActionTile(
                    title = "Поднять вопрос",
                    subtitle = "Открыть GitHub issue",
                    modifier = Modifier.weight(1f),
                    onClick = { openUrlInBrowser(context, IssuesUrl) },
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                )

                InfoActionTile(
                    title = "Собрать отчёт",
                    subtitle = "Android, ABI, версия, устройство",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("WDTT Report", buildSupportReport()))
                        Toast.makeText(context, "Отчёт сформирован и скопирован", Toast.LENGTH_SHORT).show()
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                )
            }

            WideActionTile(
                title = "Справка",
                subtitle = "Коротко про VPN, исключения, капчу и запуск",
                onClick = { showHelpDialog = true },
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            )

            WideActionTile(
                title = "Проверить обновления",
                subtitle = updateStatus,
                onClick = {
                    if (isCheckingUpdates) return@WideActionTile
                    isCheckingUpdates = true
                    scope.launch {
                        val checkedAt = System.currentTimeMillis()
                        val release = fetchLatestReleaseInfo(currentVersion)
                        val latest = release?.versionTag
                        settingsStore.saveUpdateState(
                            lastCheckAt = checkedAt,
                            latestVersion = latest ?: "",
                            error = if (release == null) "Не удалось проверить" else ""
                        )
                        isCheckingUpdates = false

                        if (release == null) {
                            val message = if (updateLatestVersion.isNotBlank()) {
                                "Не удалось проверить. Последняя известная версия: $updateLatestVersion"
                            } else {
                                "Не удалось проверить обновления"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        if (isNewerVersion(currentVersion, release.versionTag)) {
                            settingsStore.saveUpdateDialogShown(release.versionTag, checkedAt)
                            pendingManualRelease = release
                        } else {
                            Toast.makeText(
                                context,
                                "У вас уже последняя версия: ${release.versionTag}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Update,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            )
        }

        pendingManualRelease?.let { release ->
            AppUpdateDialog(
                release = release,
                onPostpone = {
                    pendingManualRelease = null
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
                    pendingManualRelease = null
                    scope.launch {
                        settingsStore.saveUpdateDialogAction(
                            version = release.versionTag,
                            action = UPDATE_DIALOG_ACTION_UPDATE,
                            actedAt = System.currentTimeMillis()
                        )
                        openUrlInBrowser(context, release.releaseUrl)
                    }
                }
            )
        }

        ExpandableSectionCard(
            title = "О проекте",
            itemCount = "3 ссылки",
            expanded = projectExpanded,
            onToggle = { projectExpanded = !projectExpanded },
            icon = {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        ) {
            ProjectLinkRow(
                title = "Автор Android-версии",
                subtitle = "GitHub профиль amurcanov",
                onClick = { openUrlInBrowser(context, DeveloperProfileUrl) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

            ProjectLinkRow(
                title = "Репозиторий WDTT",
                subtitle = "Исходники и релизы приложения",
                onClick = { openUrlInBrowser(context, RepositoryUrl) },
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_github),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

            ProjectLinkRow(
                title = "Актуальные релизы",
                subtitle = "Страница загрузки APK",
                onClick = { openUrlInBrowser(context, ReleasesUrl) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Update,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
    }

    if (showHelpDialog) ImportantInfoDialog(onDismiss = { showHelpDialog = false })
    if (showDonateDialog) DonateDialog(onDismiss = { showDonateDialog = false })
}

@Composable
private fun InfoHeroCard(currentVersion: String, onSupportClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.22f
    val heroBrush = remember(colors.primaryContainer, colors.secondaryContainer, colors.surfaceVariant) {
        Brush.linearGradient(
            listOf(
                colors.primaryContainer,
                colors.secondaryContainer,
                colors.surfaceVariant
            )
        )
    }
    val glassColor = if (isDark) colors.surface.copy(alpha = 0.46f) else Color.White.copy(alpha = 0.54f)
    val glassBorder = colors.outlineVariant.copy(alpha = if (isDark) 0.50f else 0.32f)

    Surface(
        shape = RoundedCornerShape(32.dp),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 10.dp,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(heroBrush)
                .padding(22.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 30.dp, y = (-34).dp)
                    .size(138.dp)
                    .clip(Android16BlobShape)
                    .background(colors.primary.copy(alpha = 0.10f))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 26.dp, y = 30.dp)
                    .size(112.dp)
                    .clip(Android16BlobShape)
                    .background(colors.secondary.copy(alpha = 0.12f))
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeroMetaPill(
                        text = "WDTT",
                        containerColor = glassColor,
                        borderColor = glassBorder,
                        modifier = Modifier.weight(1f)
                    )
                    HeroMetaPill(
                        text = currentVersion,
                        containerColor = colors.primary.copy(alpha = if (isDark) 0.18f else 0.10f),
                        borderColor = colors.primary.copy(alpha = if (isDark) 0.22f else 0.14f),
                        modifier = Modifier.weight(1f)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "WDTT VPN Tunnel",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 30.sp,
                            lineHeight = 34.sp
                        ),
                        color = colors.onSurface
                    )
                    Text(
                        text = "Android-клиент для TURN/VK туннеля с WireGuard, капчей и управлением сервером.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        lineHeight = 21.sp
                    )
                }

                Button(
                    onClick = onSupportClick,
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DonateActionButtonColor,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Поддержать проект", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun HeroMetaPill(
    text: String,
    containerColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ExpandableSectionCard(
    title: String,
    itemCount: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    icon: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "section_arrow_rotation"
    )

    AppSectionCard(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            MetaChip(text = itemCount)

            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(arrowRotation)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f))
                content()
            }
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoActionTile(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 116.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun WideActionTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ProjectLinkRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(
                    modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    icon()
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun DonateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 10.dp,
            shadowElevation = 14.dp,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Поддержка проекта",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalIconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть")
                    }
                }

                Text(
                    text = "Если приложение реально помогает, можно поддержать Android-версию проекта.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Button(
                    onClick = { openUrlInBrowser(context, DonateUrl) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WDTTColors.donate,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_yoomoney),
                        contentDescription = "ЮMoney",
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .width(126.dp)
                            .height(28.dp)
                    )
                }
            }
        }
    }
}

private fun buildSupportReport(): String {
    val androidVersion = Build.VERSION.RELEASE ?: "?"
    val sdkInt = Build.VERSION.SDK_INT
    val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" }
    val supportedAbis = Build.SUPPORTED_ABIS.joinToString().ifBlank { "unknown" }
    val manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "unknown" }
    val brand = Build.BRAND.orEmpty().ifBlank { "unknown" }
    val model = Build.MODEL.orEmpty().ifBlank { "unknown" }
    val device = Build.DEVICE.orEmpty().ifBlank { "unknown" }
    val product = Build.PRODUCT.orEmpty().ifBlank { "unknown" }
    val hardware = Build.HARDWARE.orEmpty().ifBlank { "unknown" }
    val board = Build.BOARD.orEmpty().ifBlank { "unknown" }
    val romDisplay = Build.DISPLAY.orEmpty().ifBlank { "unknown" }
    val buildId = Build.ID.orEmpty().ifBlank { "unknown" }
    val buildFingerprint = Build.FINGERPRINT.orEmpty().ifBlank { "unknown" }
    val buildType = Build.TYPE.orEmpty().ifBlank { "unknown" }
    val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MANUFACTURER.orEmpty().ifBlank { "unknown" }
    } else {
        "n/a"
    }
    val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL.orEmpty().ifBlank { "unknown" }
    } else {
        "n/a"
    }

    return buildString {
        appendLine("Версия приложения: ${BuildConfig.VERSION_NAME}")
        appendLine("Андроид: $androidVersion (SDK $sdkInt)")
        appendLine("Устройство: $manufacturer / $brand / $model")
        appendLine("Код устройства: $device")
        appendLine("Продукт: $product")
        appendLine("ABI: $primaryAbi")
        appendLine("Все ABI: $supportedAbis")
        appendLine("SoC: $socManufacturer / $socModel")
        appendLine("Hardware: $hardware")
        appendLine("Board: $board")
        appendLine("ROM: $romDisplay")
        appendLine("Build ID: $buildId")
        appendLine("Build type: $buildType")
        appendLine("Fingerprint: $buildFingerprint")
    }.trim()
}
