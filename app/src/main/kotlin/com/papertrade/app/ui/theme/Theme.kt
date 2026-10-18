package com.papertrade.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Gain = Color(0xFF00A67E)
val Loss = Color(0xFFE5484D)
val Warn = Color(0xFFB7791F)
val Demo = Color(0xFF7C3AED)

private val Light = lightColorScheme(primary = Color(0xFF00A67E), onPrimary = Color.White, secondary = Color(0xFF44475B), background = Color(0xFFFAFAFB), surface = Color.White)
private val Dark = darkColorScheme(primary = Color(0xFF2DD4A7), onPrimary = Color(0xFF003828), background = Color(0xFF111318), surface = Color(0xFF1A1D24))

@Composable
fun PaperTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
