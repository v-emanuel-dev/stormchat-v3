package com.ivip.brainstormia.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ivip.brainstormia.theme.PrimaryColor
import com.ivip.brainstormia.utils.SpeechRecognizerHelper

@Composable
fun ImprovedVoiceInputButton(
    onTextResult: (String) -> Unit,
    isListening: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    isSendEnabled: Boolean,
    isDarkTheme: Boolean = true
) {
    val context = LocalContext.current

    // Animação pulsante quando está gravando
    val infiniteTransition = rememberInfiniteTransition(label = "voiceButton")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isListening) Color.Red else PrimaryColor,
        animationSpec = tween(300),
        label = "background"
    )

    // Launcher para solicitar permissão
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (SpeechRecognizerHelper.isAvailable(context)) {
                onStartListening()
            } else {
                Toast.makeText(
                    context,
                    "Reconhecimento de voz não disponível neste dispositivo",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            Toast.makeText(
                context,
                "Permissão de microfone necessária para usar reconhecimento de voz",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (!isSendEnabled)
                    PrimaryColor.copy(alpha = if (isDarkTheme) 0.5f else 0.4f)
                else
                    backgroundColor
            )
            .clickable(enabled = isSendEnabled) {
                if (isListening) {
                    onStopListening()
                } else {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        onStartListening()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
            .scale(if (isListening) scale else 1f),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = if (isListening) "Parar gravação" else "Gravar mensagem",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}