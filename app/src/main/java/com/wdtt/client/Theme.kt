package com.wdtt.client

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// "Raf on Coconut Milk" Palette
private val CoconutCream = Color(0xFFFFFBF7) // Background
private val WarmBeige = Color(0xFFF5F0EB)    // Surface
private val CoffeeBrown = Color(0xFF6D4C41)  // Primary
private val SoftLatte = Color(0xFFD7CCC8)    // Secondary/Container
private val DarkCoffee = Color(0xFF3E2723)   // OnBackground/Text

@Composable
fun WDTTTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = CoffeeBrown,
            onPrimary = Color.White,
            primaryContainer = SoftLatte,
            onPrimaryContainer = DarkCoffee,
            background = CoconutCream,
            onBackground = DarkCoffee,
            surface = WarmBeige,
            onSurface = DarkCoffee,
            surfaceVariant = Color(0xFFEFEBE9),
            onSurfaceVariant = Color(0xFF5D4037),
            error = Color(0xFFBA1A1A),
            onError = Color.White
        ),
        content = content
    )
}
