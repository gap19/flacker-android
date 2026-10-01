package io.github.gap19.flacker.ui

import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.gap19.flacker.R

/** Narrow poster voice for titles only; never for durations, lyrics or metadata. */
val Gothic = FontFamily(Font(R.font.league_gothic))
val Sans = FontFamily(
    Font(R.font.source_sans_regular, FontWeight.Normal),
    Font(R.font.source_sans_semibold, FontWeight.SemiBold),
    Font(R.font.source_sans_bold, FontWeight.Bold),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE5AF87), onPrimary = Color(0xFF201710),
    primaryContainer = Color(0xFF3A2C22), onPrimaryContainer = Color(0xFFF3D4BE),
    secondary = Color(0xFFACB9BD), onSecondary = Color(0xFF111617),
    secondaryContainer = Color(0xFF2C3638), onSecondaryContainer = Color(0xFFF0F4F4),
    tertiary = Color(0xFFE5AF87), onTertiary = Color(0xFF201710),
    background = Color(0xFF111617), onBackground = Color(0xFFF0F4F4),
    surface = Color(0xFF111617), onSurface = Color(0xFFF0F4F4),
    surfaceVariant = Color(0xFF20282A), onSurfaceVariant = Color(0xFFACB9BD),
    surfaceContainer = Color(0xFF1A2123), surfaceContainerHigh = Color(0xFF20282A),
    surfaceContainerHighest = Color(0xFF283437), surfaceContainerLow = Color(0xFF151B1C),
    outline = Color(0xFF465457), outlineVariant = Color(0xFF2C3638),
)
private val Light = lightColorScheme(
    primary = Color(0xFF724527), onPrimary = Color(0xFFFFF8F2),
    primaryContainer = Color(0xFFF3D4BE), onPrimaryContainer = Color(0xFF3A2010),
    secondary = Color(0xFF52676E), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E1E4), onSecondaryContainer = Color(0xFF142024),
    tertiary = Color(0xFF724527), onTertiary = Color(0xFFFFF8F2),
    background = Color(0xFFE7ECEE), onBackground = Color(0xFF142024),
    surface = Color(0xFFE7ECEE), onSurface = Color(0xFF142024),
    surfaceVariant = Color(0xFFF8FAFB), onSurfaceVariant = Color(0xFF52676E),
    surfaceContainer = Color(0xFFF1F4F5), surfaceContainerHigh = Color(0xFFF8FAFB),
    surfaceContainerHighest = Color(0xFFD7E1E4), surfaceContainerLow = Color(0xFFEDF1F2),
    outline = Color(0xFFB9C9CD), outlineVariant = Color(0xFFD3DDE0),
)

private val base = Typography()
private val Type = Typography(
    displayMedium = TextStyle(fontFamily = Gothic, fontSize = 44.sp, lineHeight = 48.sp),
    displaySmall = TextStyle(fontFamily = Gothic, fontSize = 36.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = Gothic, fontSize = 32.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Gothic, fontSize = 28.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Gothic, fontSize = 24.sp, lineHeight = 28.sp),
    titleLarge = base.titleLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = Sans),
    bodyMedium = base.bodyMedium.copy(fontFamily = Sans),
    bodySmall = base.bodySmall.copy(fontFamily = Sans),
    labelLarge = base.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = Sans),
    labelSmall = base.labelSmall.copy(fontFamily = Sans),
)

val LocalDark = staticCompositionLocalOf { true }

@Composable
fun FlackerTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    val activity = androidx.activity.compose.LocalActivity.current as? androidx.activity.ComponentActivity
    androidx.compose.runtime.LaunchedEffect(dark) {
        // Light status and navigation icons on the dark theme, dark icons on the light one.
        val style = if (dark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
    }
}
