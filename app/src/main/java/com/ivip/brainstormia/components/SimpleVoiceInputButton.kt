package com.ivip.brainstormia.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SimpleVoiceInputButton(
    onTextResult: (String) -> Unit,
    isListening: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    isSendEnabled: Boolean,
    isDarkTheme: Boolean
) {
    // Cores atualizadas para o tema escuro
    val iconColor = if (isDarkTheme) Color.White else Color.White
    val backgroundColor = if (isDarkTheme) {
        if (isListening) Color(0xFF333333) else Color(0xFF333333) // Cinza escuro para tema escuro
    } else {
        if (isListening) Color(0xFFE53935) else Color(0xFF1A5F7A) // Vermelho ou azul para tema claro
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                color = if (!isSendEnabled) {
                    if (isDarkTheme) Color(0xFF333333).copy(alpha = 0.5f) else Color(0xFF1A5F7A).copy(alpha = 0.5f)
                } else {
                    backgroundColor
                }
            )
            .clickable(
                enabled = isSendEnabled,
                onClick = {
                    if (isListening) {
                        onStopListening()
                    } else {
                        onStartListening()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = if (isListening) "Parar de gravar" else "Iniciar gravação de voz",
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )
    }
}