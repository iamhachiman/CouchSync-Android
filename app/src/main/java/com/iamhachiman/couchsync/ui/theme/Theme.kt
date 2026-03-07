package com.iamhachiman.couchsync.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Night,
    secondary = Warm,
    tertiary = Coral,
    background = Night,
    surface = NightSurface,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    onSurface = Cloud,
    onSurfaceVariant = Slate,
    outline = Mist,
    error = Coral,
    onError = Cloud
)

private val LightColorScheme = lightColorScheme(
    primary = MintDark,
    onPrimary = Cloud,
    secondary = Warm,
    tertiary = Coral,
    background = Cloud,
    surface = Color(0xFFF1F7FD),
    onSurface = Night,
    onSurfaceVariant = Color(0xFF546579),
    outline = Slate,
    error = Coral,
    onError = Cloud
)

@Composable
fun CouchSyncTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme || dynamicColor) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}