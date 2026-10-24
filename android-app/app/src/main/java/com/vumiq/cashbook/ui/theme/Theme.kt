package com.vumiq.cashbook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vumiq.cashbook.domain.ThemePreference

// VUMIQ Cashbook Brand Palette (60-30-10 Financial Architecture)
val VumiqForest = Color(0xFF126B45)
val VumiqForestHover = Color(0xFF0E5738)
val VumiqEmerald = Color(0xFF168653)
val VumiqMint = Color(0xFF8CE8BE)
val VumiqSplashObsidian = Color(0xFF08120E)

// Light Mode Surfaces (Warm Ivory & Crisp White)
val LightCanvas = Color(0xFFFAF8F4)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceSubtle = Color(0xFFF3F0EA)
val LightTextPrimary = Color(0xFF171A1C)
val LightTextSecondary = Color(0xFF5C646A)
val LightBorder = Color(0xFFE4E1DA)
val LightIncomePositive = Color(0xFF13794B)
val LightIncomeTint = Color(0xFFEAF6F0)
val LightExpenseDanger = Color(0xFFC93B3B)
val LightExpenseTint = Color(0xFFFDECEC)
val LightWarningAmber = Color(0xFFB4690E)
val LightWarningTint = Color(0xFFFEF5E7)

// Dark Mode Surfaces (Deep Slate Obsidian)
val DarkCanvas = Color(0xFF0C1110)
val DarkSurface = Color(0xFF141B19)
val DarkSurfaceSubtle = Color(0xFF1C2623)
val DarkTextPrimary = Color(0xFFF2F5F4)
val DarkTextSecondary = Color(0xFF9AA8A3)
val DarkBorder = Color(0xFF25322E)
val DarkIncomePositive = Color(0xFF4ADE80)
val DarkIncomeTint = Color(0xFF122B20)
val DarkExpenseDanger = Color(0xFFF87171)
val DarkExpenseTint = Color(0xFF2D1618)
val DarkWarningAmber = Color(0xFFFBBF24)
val DarkWarningTint = Color(0xFF2C2210)

@Immutable
data class VumiqSemanticColors(
    val canvas: Color,
    val cardSurface: Color,
    val subtleSurface: Color,
    val hairlineBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val incomeText: Color,
    val incomeContainer: Color,
    val expenseText: Color,
    val expenseContainer: Color,
    val warningText: Color,
    val warningContainer: Color,
    val isDark: Boolean
)

val LocalVumiqColors = staticCompositionLocalOf {
    VumiqSemanticColors(
        canvas = LightCanvas,
        cardSurface = LightSurface,
        subtleSurface = LightSurfaceSubtle,
        hairlineBorder = LightBorder,
        textPrimary = LightTextPrimary,
        textSecondary = LightTextSecondary,
        incomeText = LightIncomePositive,
        incomeContainer = LightIncomeTint,
        expenseText = LightExpenseDanger,
        expenseContainer = LightExpenseTint,
        warningText = LightWarningAmber,
        warningContainer = LightWarningTint,
        isDark = false
    )
}

private val LightColorScheme = lightColorScheme(
    primary = VumiqForest,
    onPrimary = Color.White,
    primaryContainer = LightIncomeTint,
    onPrimaryContainer = VumiqForestHover,
    secondary = VumiqEmerald,
    onSecondary = Color.White,
    background = LightCanvas,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceSubtle,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    error = LightExpenseDanger,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF062819),
    primaryContainer = DarkIncomeTint,
    onPrimaryContainer = VumiqMint,
    secondary = VumiqMint,
    onSecondary = Color(0xFF062819),
    background = DarkCanvas,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceSubtle,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    error = DarkExpenseDanger,
    onError = Color(0xFF3B0909)
)

val TabularMoneyStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontFeatureSettings = "tnum"
)

private val VumiqTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = "tnum"
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
        fontFeatureSettings = "tnum"
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun VumiqCashbookTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDark = when (themePreference) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }

    val semanticColors = if (useDark) {
        VumiqSemanticColors(
            canvas = DarkCanvas,
            cardSurface = DarkSurface,
            subtleSurface = DarkSurfaceSubtle,
            hairlineBorder = DarkBorder,
            textPrimary = DarkTextPrimary,
            textSecondary = DarkTextSecondary,
            incomeText = DarkIncomePositive,
            incomeContainer = DarkIncomeTint,
            expenseText = DarkExpenseDanger,
            expenseContainer = DarkExpenseTint,
            warningText = DarkWarningAmber,
            warningContainer = DarkWarningTint,
            isDark = true
        )
    } else {
        VumiqSemanticColors(
            canvas = LightCanvas,
            cardSurface = LightSurface,
            subtleSurface = LightSurfaceSubtle,
            hairlineBorder = LightBorder,
            textPrimary = LightTextPrimary,
            textSecondary = LightTextSecondary,
            incomeText = LightIncomePositive,
            incomeContainer = LightIncomeTint,
            expenseText = LightExpenseDanger,
            expenseContainer = LightExpenseTint,
            warningText = LightWarningAmber,
            warningContainer = LightWarningTint,
            isDark = false
        )
    }

    CompositionLocalProvider(LocalVumiqColors provides semanticColors) {
        MaterialTheme(
            colorScheme = if (useDark) DarkColorScheme else LightColorScheme,
            typography = VumiqTypography,
            content = content
        )
    }
}
