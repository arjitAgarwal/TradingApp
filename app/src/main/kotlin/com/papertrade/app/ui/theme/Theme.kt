package com.papertrade.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.papertrade.app.R

// Groww-like palette: clean white surfaces, a single green accent, muted grey text.
val Gain = Color(0xFF00B386)
val Loss = Color(0xFFEB5B3C)
val Warn = Color(0xFFB7791F)
val Demo = Color(0xFF7C3AED)
private val Ink = Color(0xFF44475B)        // primary text
private val InkMuted = Color(0xFF7C7E8C)   // secondary text
private val Hairline = Color(0xFFE9E9EB)   // dividers, outlines
private val GreenTint = Color(0xFFE6F7F3)  // selected chips / nav pill

private val Light = lightColorScheme(
    primary = Gain, onPrimary = Color.White, primaryContainer = GreenTint, onPrimaryContainer = Color(0xFF00694F),
    secondary = Ink, onSecondary = Color.White, secondaryContainer = GreenTint, onSecondaryContainer = Color(0xFF00694F),
    background = Color.White, onBackground = Ink, surface = Color.White, onSurface = Ink,
    surfaceVariant = Color(0xFFF5F5F7), onSurfaceVariant = InkMuted, outline = Color(0xFFD1D1D6), outlineVariant = Hairline,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF7F7F8), surfaceContainerHighest = Color(0xFFF2F2F4), surfaceTint = Color.White,
    error = Loss, onError = Color.White, errorContainer = Color(0xFFFDEDE9), onErrorContainer = Color(0xFF8A2A14),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF2DCB9E), onPrimary = Color(0xFF00382A), primaryContainer = Color(0xFF0D3A2F), onPrimaryContainer = Color(0xFFB4F0DE),
    secondary = Color(0xFFD7D8DF), secondaryContainer = Color(0xFF0D3A2F), onSecondaryContainer = Color(0xFFB4F0DE),
    background = Color(0xFF121212), onBackground = Color(0xFFE4E5EA), surface = Color(0xFF121212), onSurface = Color(0xFFE4E5EA),
    surfaceVariant = Color(0xFF1E1F22), onSurfaceVariant = Color(0xFF9A9CA8), outline = Color(0xFF3A3B40), outlineVariant = Color(0xFF2A2B2F),
    surfaceContainerLowest = Color(0xFF121212), surfaceContainerLow = Color(0xFF121212), surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1B1E), surfaceContainerHighest = Color(0xFF222326), surfaceTint = Color(0xFF121212),
    error = Color(0xFFFF7A5C), errorContainer = Color(0xFF3D1A12), onErrorContainer = Color(0xFFFFD6CC),
)

/**
 * Inter (variable font, bundled; SIL Open Font License 1.1, see docs/licenses/Inter-OFL.txt, which allows bundling in an app).
 * Chosen as a clean, neutral sans-serif with a similar feel to modern Indian broker apps; it is not claimed to be any app's font.
 */
@OptIn(ExperimentalTextApi::class)
private fun inter(w: FontWeight) = Font(R.font.inter, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
val Inter = FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map(::inter))

private fun style(size: Int, line: Int, weight: FontWeight) =
    // Tabular figures everywhere so prices, quantities and P&L line up; Material's default tracking looks loose with Inter.
    TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp, fontFeatureSettings = "tnum")

/**
 * The app's only type scale. Screens pick a role, never a raw size:
 * - Bold (700), sparingly: headlineLarge = hero price on detail screens, headlineMedium = portfolio totals.
 * - SemiBold (600): headlineSmall = app title; titleLarge = page/section headings; titleMedium = card headings and
 *   key values; titleSmall = instrument names in rows.
 * - Regular (400): body* = body text, labels and supporting information.
 * - Medium (500): labelLarge = buttons and row values; labelMedium = navigation, tabs' small text and table headers;
 *   labelSmall = tags and compact option-chain figures.
 */
private val AppType = Typography(
    displayLarge = style(57, 64, FontWeight.Normal), displayMedium = style(45, 52, FontWeight.Normal), displaySmall = style(36, 44, FontWeight.Normal),
    headlineLarge = style(30, 36, FontWeight.Bold), headlineMedium = style(24, 32, FontWeight.Bold), headlineSmall = style(20, 28, FontWeight.SemiBold),
    titleLarge = style(18, 24, FontWeight.SemiBold), titleMedium = style(16, 22, FontWeight.SemiBold), titleSmall = style(15, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 22, FontWeight.Normal), bodyMedium = style(14, 20, FontWeight.Normal), bodySmall = style(12, 16, FontWeight.Normal),
    labelLarge = style(15, 20, FontWeight.Medium), labelMedium = style(13, 18, FontWeight.Medium), labelSmall = style(11, 14, FontWeight.Medium),
)

@Composable
fun PaperTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = AppType, content = content)
