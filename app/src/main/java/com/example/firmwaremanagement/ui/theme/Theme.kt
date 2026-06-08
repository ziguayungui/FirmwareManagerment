package com.example.firmwaremanagement.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// 科技蓝配色
val TechBlue = Color(0xFF2196F3)
val TechBlueDark = Color(0xFF1976D2)
val TechBlueLight = Color(0xFF64B5F6)

// 深色背景色
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val DarkCard = Color(0xFF2D2D2D)

// 浅色背景色
val LightBackground = Color(0xFFF5F5F5)
val LightSurface = Color(0xFFFFFFFF)

// 文字颜色
val WhiteText = Color(0xFFFFFFFF)
val GrayText = Color(0xFFB0B0B0)
val DarkGrayText = Color(0xFF757575)

// 错误/警告颜色
val ErrorRed = Color(0xFFFF5252)
val SuccessGreen = Color(0xFF4CAF50)
val WarningOrange = Color(0xFFFF9800)

private val DarkColorScheme = darkColorScheme(
    primary = TechBlue,
    onPrimary = WhiteText,
    primaryContainer = TechBlueDark,
    onPrimaryContainer = WhiteText,
    secondary = TechBlueDark,
    onSecondary = WhiteText,
    secondaryContainer = TechBlueLight,
    onSecondaryContainer = TechBlue,
    tertiary = TechBlueDark,
    onTertiary = WhiteText,
    background = LightBackground,
    onBackground = DarkBackground,
    surface = LightSurface,
    onSurface = DarkBackground,
    surfaceVariant = LightSurface,
    onSurfaceVariant = DarkGrayText,
    error = ErrorRed,
    onError = WhiteText
)

private val LightColorScheme = lightColorScheme(
    primary = TechBlue,
    onPrimary = WhiteText,
    primaryContainer = TechBlueLight,
    onPrimaryContainer = DarkBackground,
    secondary = DarkGrayText,
    onSecondary = LightBackground,
    secondaryContainer = LightSurface,
    onSecondaryContainer = DarkBackground,
    tertiary = TechBlueDark,
    onTertiary = WhiteText,
    background = LightBackground,
    onBackground = DarkBackground,
    surface = LightSurface,
    onSurface = DarkBackground,
    surfaceVariant = LightSurface,
    onSurfaceVariant = DarkGrayText,
    error = ErrorRed,
    onError = WhiteText
)

@Composable
fun FirmwareManagementTheme(
    darkTheme: Boolean = true, // 默认使用深色主题
    dynamicColor: Boolean = false, // 禁用动态颜色
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = 0xFF2196F3.toInt() // TechBlue
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
