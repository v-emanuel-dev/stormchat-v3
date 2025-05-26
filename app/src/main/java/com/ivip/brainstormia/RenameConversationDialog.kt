package com.ivip.brainstormia

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivip.brainstormia.theme.PrimaryColor
import com.ivip.brainstormia.theme.SurfaceColorDark
import com.ivip.brainstormia.theme.TextColorDark
import com.ivip.brainstormia.theme.TextColorLight

@Composable
fun RenameConversationDialog(
    conversationId: Long,
    currentTitle: String?,
    onConfirm: (Long, String) -> Unit,
    onDismiss: () -> Unit,
    isDarkTheme: Boolean = true
) {
    var newTitle by remember { mutableStateOf(currentTitle ?: "") }

    val dialogBgColor = if (isDarkTheme) Color(0xFF121212) else Color.White // Usando #121212 para dialogs
    val textColor = if (isDarkTheme) TextColorLight else TextColorDark
    val secondaryTextColor = if (isDarkTheme) TextColorLight.copy(alpha = 0.7f) else Color.DarkGray
    val unfocusedBorderColor = if (isDarkTheme) Color.Gray.copy(alpha = 0.3f) else Color.Gray.copy(alpha = 0.5f)
    val buttonTextColor = TextColorLight // Sempre branco para melhor visibilidade

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(id = R.string.rename_conversation_dialog_title_pt),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = {
                        Text(
                            stringResource(id = R.string.new_name_label),
                            fontWeight = FontWeight.Medium,
                            color = if (isDarkTheme) Color.LightGray else Color.DarkGray
                        )
                    },
                    textStyle = TextStyle(
                        fontWeight = FontWeight.Medium,
                        color = textColor
                    ),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = if (isDarkTheme) SurfaceColorDark.copy(alpha = 0.8f) else Color.White,
                        unfocusedContainerColor = if (isDarkTheme) SurfaceColorDark.copy(alpha = 0.8f) else Color.White,
                        focusedBorderColor = PrimaryColor,
                        unfocusedBorderColor = unfocusedBorderColor,
                        focusedLabelColor = PrimaryColor,
                        cursorColor = PrimaryColor,
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newTitle.isNotBlank()) {
                        onConfirm(conversationId, newTitle)
                    }
                },
                enabled = newTitle.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDarkTheme) Color(0xFF333333) else PrimaryColor,
                    contentColor = buttonTextColor // Garantir texto branco no botão
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    stringResource(id = R.string.save_button_pt),
                    fontWeight = FontWeight.SemiBold,
                    color = buttonTextColor // Garantir texto branco
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = buttonTextColor // Garantir texto branco no botão de cancelar
                )
            ) {
                Text(
                    stringResource(id = R.string.cancel), // Changed from cancel_button_pt to cancel
                    color = buttonTextColor, // Usar sempre branco em vez de secondaryTextColor
                    fontWeight = FontWeight.Medium
                )
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = dialogBgColor,
        tonalElevation = if (isDarkTheme) 16.dp else 8.dp
    )
}