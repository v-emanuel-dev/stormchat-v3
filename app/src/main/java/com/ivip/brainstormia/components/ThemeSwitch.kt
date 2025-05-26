package com.ivip.brainstormia.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Um botão de alternância de tema que é garantidamente visível.
 */
@Composable
fun ThemeSwitch(
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // Cores fortes para garantir visibilidade
    val bgColor = if (isDarkTheme) Color.White else Color.Black
    val iconColor = if (isDarkTheme) Color.Black else Color.White

    // Envolvendo em uma Surface para garantir elevação
    Surface(
        modifier = modifier
            .size(48.dp)
            .zIndex(999f), // Garante que aparece acima de outros elementos
        shape = CircleShape,
        shadowElevation = 8.dp,
        color = bgColor
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable { onThemeChanged(!isDarkTheme) }
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                contentDescription = if (isDarkTheme) "Mudar para tema claro" else "Mudar para tema escuro",
                tint = iconColor
            )
        }
    }
}