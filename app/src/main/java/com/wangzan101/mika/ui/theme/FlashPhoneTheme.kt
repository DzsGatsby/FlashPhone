package com.wangzan101.mika.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Colors from the accepted phone preview, scoped to the phone workspace. */
object FlashPhoneColors {
    val White = Color.White
    val Ink = Color(0xFF1A201D)
    val Secondary = Color(0xFF63686A)
    val Muted = Color(0xFF777C80)
    val Line = Color(0xFFEDEEF0)
    val Fill = Color(0xFFF4F4F7)
    val Green = Color(0xFF167447)
    val GreenSoft = Color(0xFFE8EEE9)
    val CallGreen = Color(0xFF2DCC59)
    val ConversationGreen = Color(0xFF32CF69)
    val ConversationInk = Color(0xFF123421)
    val ConversationBackdrop = Color(0xFFF3F2F8)
    val Blue = Color(0xFF007AFF)
    val BlueSoft = Color(0xFFEFF5FF)
    val Dock = Color(0xFFFAFBFA)
    val DialDock = Color(0xFFFCFCFD)
    val DialDockLine = Color(0xFFE0E0E3)
    val DialSelected = Color(0xFFEDEDF0)
}

// MikaColors intentionally has no copy(): keep the phone palette local instead of
// changing the upstream app's generic surfaces, accents, or dark appearance.
private val PhoneColors = MikaColors(
    bg = FlashPhoneColors.White,
    surface = FlashPhoneColors.White,
    fill = FlashPhoneColors.Fill,
    ink = FlashPhoneColors.Ink,
    ink2 = FlashPhoneColors.Secondary,
    ink3 = FlashPhoneColors.Muted,
    hair = FlashPhoneColors.Line,
    edge = FlashPhoneColors.Line,
    accent = FlashPhoneColors.Green,
    accentSoft = FlashPhoneColors.GreenSoft,
    onAccent = FlashPhoneColors.White,
    ok = FlashPhoneColors.Green,
    okSoft = Color(0xFFEAF6EE),
    warn = Color(0xFF946116),
    warnSolid = Color(0xFFB5761B),
    warnSoft = Color(0xFFFAF3E8),
    bad = Color(0xFFBF4A43),
    badSoft = Color(0xFFFFF0EF),
    neutralSoft = FlashPhoneColors.Fill,
    answer = FlashPhoneColors.ConversationBackdrop,
    greySoft = Color(0xFFE8E9EB),
    glass = FlashPhoneColors.White,
    glassEdge = FlashPhoneColors.Line,
    cardShadow = Color.Transparent,
    floatShadow = Color(0xFF232C24),
    thumb = FlashPhoneColors.White,
    series = LightColors.series,
    seriesOther = LightColors.seriesOther,
    grid = LightColors.grid,
    isDark = false,
)

private val PhoneScheme = lightColorScheme(
    primary = PhoneColors.accent,
    onPrimary = PhoneColors.onAccent,
    primaryContainer = PhoneColors.accentSoft,
    onPrimaryContainer = PhoneColors.accent,
    secondary = PhoneColors.accent,
    onSecondary = PhoneColors.onAccent,
    secondaryContainer = PhoneColors.fill,
    onSecondaryContainer = PhoneColors.ink,
    tertiary = PhoneColors.ink2,
    onTertiary = PhoneColors.surface,
    tertiaryContainer = PhoneColors.fill,
    onTertiaryContainer = PhoneColors.ink,
    background = PhoneColors.bg,
    onBackground = PhoneColors.ink,
    surface = PhoneColors.surface,
    onSurface = PhoneColors.ink,
    surfaceVariant = PhoneColors.fill,
    onSurfaceVariant = PhoneColors.ink2,
    surfaceContainerLowest = PhoneColors.surface,
    surfaceContainerLow = PhoneColors.surface,
    surfaceContainer = PhoneColors.surface,
    surfaceContainerHigh = PhoneColors.surface,
    surfaceContainerHighest = PhoneColors.surface,
    surfaceTint = PhoneColors.surface,
    outline = PhoneColors.ink3,
    outlineVariant = PhoneColors.hair,
    error = PhoneColors.bad,
    onError = PhoneColors.onAccent,
    errorContainer = PhoneColors.badSoft,
    onErrorContainer = PhoneColors.bad,
)

private val PhoneShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The accepted phone design is white in every system appearance. Nest this inside
 * MikaTheme around the entire phone screen so dialogs and phone subpages agree.
 * The caller's layout direction, typography, and reduced-motion setting survive.
 */
@Composable
@Suppress("DEPRECATION")
fun FlashPhoneTheme(
    reduceMotion: Boolean = MikaTheme.reduceMotion,
    content: @Composable () -> Unit,
) {
    // The dedicated entry point also themes About, licensing and device setup.
    // Embedded call screens retain this wrapper, without stacking system-bar effects.
    if (LocalMikaColors.current === PhoneColors) {
        content()
        return
    }
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousLightStatus = bars?.isAppearanceLightStatusBars
        val previousLightNavigation = bars?.isAppearanceLightNavigationBars
        val previousStatusColor = window?.statusBarColor
        val previousNavigationColor = window?.navigationBarColor
        val previousContrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window?.isNavigationBarContrastEnforced
        } else null
        bars?.isAppearanceLightStatusBars = true
        bars?.isAppearanceLightNavigationBars = true
        window?.statusBarColor = android.graphics.Color.TRANSPARENT
        window?.navigationBarColor = android.graphics.Color.WHITE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window?.isNavigationBarContrastEnforced = false
        onDispose {
            previousLightStatus?.let { bars?.isAppearanceLightStatusBars = it }
            previousLightNavigation?.let { bars?.isAppearanceLightNavigationBars = it }
            previousStatusColor?.let { window?.statusBarColor = it }
            previousNavigationColor?.let { window?.navigationBarColor = it }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                previousContrast?.let { window?.isNavigationBarContrastEnforced = it }
            }
        }
    }
    CompositionLocalProvider(
        LocalMikaColors provides PhoneColors,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = PhoneScheme, shapes = PhoneShapes) {
            CompositionLocalProvider(LocalContentColor provides PhoneColors.ink, content = content)
        }
    }
}

/** Apply the phone palette to the sheet's own window, including in system dark mode. */
@Composable
fun FlashPhoneSheetSystemBars() {
    val view = LocalView.current
    val dark = MikaTheme.colors.isDark
    DisposableEffect(view, dark) {
        val window = generateSequence(view as android.view.View?) { it.parent as? android.view.View }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = !dark
        controller?.isAppearanceLightNavigationBars = !dark
        onDispose {
            previousStatus?.let { controller?.isAppearanceLightStatusBars = it }
            previousNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }
}
