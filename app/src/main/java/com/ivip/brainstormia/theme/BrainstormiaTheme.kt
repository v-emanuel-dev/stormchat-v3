package com.ivip.brainstormia.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat

// Using system font family
val appFontFamily = FontFamily.SansSerif

// Light color scheme
private val LightColorScheme = lightColorScheme(
    primary = PrimaryColor,
    secondary = SecondaryColor,
    background = BackgroundColor,
    surface = SurfaceColor,
    onPrimary = TextColorLight,
    onSecondary = TextColorDark,
    onBackground = TextColorDark,
    onSurface = TextColorDark
)

// Dark color scheme
private val DarkColorScheme = darkColorScheme(
    primary = TopBarColorDark, // Preto para topbar
    secondary = SecondaryColorDark, // #212121 como cor secundária
    background = BackgroundColorBlack, // #121212 como cor de fundo
    surface = SurfaceColorBlack, // #212121 como cor de superfície
    onPrimary = TextColorLight,
    onSecondary = TextColorLight,
    onBackground = TextColorLight,
    onSurface = TextColorLight
)

// Cores personalizadas para seleção de texto
private val lightSelectionColors = TextSelectionColors(
    handleColor = PrimaryColor,
    backgroundColor = PrimaryColor.copy(alpha = 0.3f)
)

private val darkSelectionColors = TextSelectionColors(
    handleColor = Color(0xFF90CAF9), // Azul claro para as alças
    backgroundColor = Color(0xFF4A6572).copy(alpha = 0.7f) // Azul acinzentado claro para o fundo
)

// Typography with improved readability
val BrainstormiaTypography = Typography(
    // ... resto do seu código de tipografia permanece o mesmo
)

@Composable
fun BrainstormiaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
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
            // Usar a cor original da sua barra de status
            window.statusBarColor = if (darkTheme) TopBarColorDark.toArgb() else PrimaryColor.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    // Escolher as cores de seleção com base no tema
    val textSelectionColors = if (darkTheme) darkSelectionColors else lightSelectionColors

    // Fornecer as cores de seleção customizadas para todo o conteúdo
    CompositionLocalProvider(
        LocalTextSelectionColors provides textSelectionColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BrainstormiaTypography,
            content = content
        )
    }
}