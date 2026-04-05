package com.wdtt.client.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wdtt.client.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.runtime.Stable
import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp

// Coffee theme colors
private val CoffeeBrown = Color(0xFF6D4C41)
private val DarkCoffee = Color(0xFF3E2723)
private val SoftLatte = Color(0xFFD7CCC8)
private val WarmMocha = Color(0xFF8D6E63)
private val CreamBeige = Color(0xFFF5F0EB)

@Stable
data class AppItem(
    val name: String,
    val packageName: String,
    val icon: ImageBitmap?
)

object AppCache {
    var cachedList: List<AppItem>? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExceptionsTab() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore(context) }
    
    val savedExcluded by settingsStore.excludedApps.collectAsStateWithLifecycle(initialValue = "")
    val selectedPackages = remember(savedExcluded) { 
        savedExcluded.split(",").filter { it.isNotEmpty() }.toSet() 
    }

    var appsList by remember { mutableStateOf<List<AppItem>>(AppCache.cachedList ?: emptyList()) }
    var isLoading by remember { mutableStateOf(AppCache.cachedList == null) }
    var searchQuery by remember { mutableStateOf("") }

    val isWhitelist by settingsStore.isWhitelist.collectAsStateWithLifecycle(initialValue = false)
    
    // Load Apps
    LaunchedEffect(Unit) {
        if (AppCache.cachedList != null) return@LaunchedEffect
        isLoading = true
        withContext(Dispatchers.IO) {
            val list = mutableListOf<AppItem>()
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            
            installedApps.forEach { app ->
                // Фильтр: только приложения с лаунчером (пользовательские)
                // Или явно разрешенные системные (хотя у Play Market и YouTube есть лаунчер)
                val hasLauncher = pm.getLaunchIntentForPackage(app.packageName) != null
                
                if (hasLauncher && 
                    app.packageName != context.packageName && 
                    !app.packageName.contains("vkontakte") && 
                    !app.packageName.contains("vk.calls")) {
                    list.add(AppItem(
                        name = app.loadLabel(pm).toString(),
                        packageName = app.packageName,
                        icon = app.loadIcon(pm)?.toBitmap()?.asImageBitmap()
                    ))
                }
            }
            appsList = list.sortedBy { it.name.lowercase() }
            AppCache.cachedList = appsList
        }
        isLoading = false
    }

    val filteredApps = appsList.filter { 
        it.name.contains(searchQuery, ignoreCase = true) || 
        it.packageName.contains(searchQuery, ignoreCase = true) 
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // --- Search Bar ---
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Поиск приложений...", fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp),
            shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        Spacer(modifier = Modifier.height(14.dp))

        // --- Mode Toggle (ЧС/БС) ---
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        "Режим исключений", 
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                    Text(
                        if (isWhitelist) "БС: Неотмеченные приложения добавляются в туннель (снимите галочку)" 
                        else "ЧС: Выбранные приложения исключаются из туннеля (поставьте галочку)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // ЧС Chip (Blacklist)
                    FilterChip(
                        selected = !isWhitelist,
                        onClick = {
                            if (isWhitelist) {
                                scope.launch {
                                    val all = appsList.map { it.packageName }.toSet()
                                    val inverted = all - selectedPackages
                                    settingsStore.saveExceptionsMode(inverted.joinToString(","), false)
                                    delay(300)
                                    com.wdtt.client.TunnelManager.reloadWireGuard()
                                }
                            }
                        },
                        label = { 
                            Text(
                                "ЧС", 
                                fontWeight = if (!isWhitelist) FontWeight.Bold else FontWeight.Medium,
                                color = if (!isWhitelist) Color.White else DarkCoffee
                            ) 
                        },
                        modifier = Modifier.width(64.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CoffeeBrown,
                            selectedLabelColor = Color.White,
                            containerColor = SoftLatte,
                            labelColor = DarkCoffee
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = !isWhitelist,
                            borderColor = WarmMocha.copy(alpha = 0.4f),
                            selectedBorderColor = CoffeeBrown
                        )
                    )
                    // БС Chip (Whitelist)
                    FilterChip(
                        selected = isWhitelist,
                        onClick = {
                            if (!isWhitelist) {
                                scope.launch {
                                    val all = appsList.map { it.packageName }.toSet()
                                    val inverted = all - selectedPackages
                                    settingsStore.saveExceptionsMode(inverted.joinToString(","), true)
                                    delay(300)
                                    com.wdtt.client.TunnelManager.reloadWireGuard()
                                }
                            }
                        },
                        label = { 
                            Text(
                                "БС", 
                                fontWeight = if (isWhitelist) FontWeight.Bold else FontWeight.Medium,
                                color = if (isWhitelist) Color.White else DarkCoffee
                            ) 
                        },
                        modifier = Modifier.width(64.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CoffeeBrown,
                            selectedLabelColor = Color.White,
                            containerColor = SoftLatte,
                            labelColor = DarkCoffee
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isWhitelist,
                            borderColor = WarmMocha.copy(alpha = 0.4f),
                            selectedBorderColor = CoffeeBrown
                        )
                    )
                }
            }
        }

        // --- List ---
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isSelected = selectedPackages.contains(app.packageName)
                    
                    AppRow(
                        app = app,
                        isSelected = isSelected,
                        onClick = {
                            val newList = if (isSelected) {
                                selectedPackages - app.packageName
                            } else {
                                selectedPackages + app.packageName
                            }
                            scope.launch { 
                                settingsStore.saveExcludedApps(newList.joinToString(",")) 
                                com.wdtt.client.TunnelManager.reloadWireGuard()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AppRow(app: AppItem, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        tonalElevation = if (isSelected) 4.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            if (app.icon != null) {
                Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(modifier = Modifier.size(40.dp).background(Color.Gray, RoundedCornerShape(8.dp)))
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
            )
        }
    }
}
