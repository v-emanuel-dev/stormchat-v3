package com.ivip.brainstormia.components

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivip.brainstormia.ExportState
import com.ivip.brainstormia.R
import com.ivip.brainstormia.theme.SurfaceColor
import com.ivip.brainstormia.theme.TextColorDark
import com.ivip.brainstormia.theme.TextColorLight

@Composable
fun ExportDialog(
    conversationTitle: String,
    exportState: ExportState,
    onExportConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDarkTheme: Boolean = true
) {
    val exportGreenColor = Color(0xFF4CAF50)
    val context = LocalContext.current

    // Obtenha a mensagem de erro fora do LaunchedEffect
    val errorDriveOpenMessage = stringResource(id = R.string.export_error_drive_open, "")

    // Abre o arquivo exportado diretamente no app Google Drive
    LaunchedEffect(exportState) {
        if (exportState is ExportState.Success) {
            try {
                // URI do arquivo no Drive
                val fileId = exportState.fileId.orEmpty()
                val driveUri = Uri.parse("https://drive.google.com/file/d/$fileId/view")
                val intent = Intent(Intent.ACTION_VIEW, driveUri)
                    .setPackage("com.google.android.apps.docs")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                context.startActivity(intent)
            } catch (e: Exception) {
                // Use uma string de log fixa em vez de stringResource aqui
                Log.e("ExportDialog", "Erro ao abrir o Drive: ${e.message ?: "Erro desconhecido"}")
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (exportState !is ExportState.Loading) {
                onDismiss()
            }
        },
        title = {
            Text(
                text = stringResource(id = R.string.export_dialog_title),
                fontWeight = FontWeight.Bold,
                color = if (isDarkTheme) TextColorLight else TextColorDark
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                when (exportState) {
                    is ExportState.Initial -> {
                        Text(
                            text = stringResource(id = R.string.export_dialog_confirmation),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (isDarkTheme) TextColorLight else TextColorDark
                        )
                        Text(
                            text = stringResource(id = R.string.export_dialog_title_prefix, conversationTitle),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Normal,
                            color = if (isDarkTheme) TextColorLight.copy(alpha = 0.8f) else TextColorDark.copy(alpha = 0.8f)
                        )
                    }
                    is ExportState.Loading -> {
                        CircularProgressIndicator(
                            color = exportGreenColor,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = stringResource(id = R.string.export_dialog_exporting),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (isDarkTheme) TextColorLight else TextColorDark
                        )
                    }
                    is ExportState.Success -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Sucesso",
                            tint = exportGreenColor,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = stringResource(id = R.string.export_dialog_success),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (isDarkTheme) TextColorLight else TextColorDark
                        )

                        Text(
                            text = stringResource(id = R.string.export_dialog_filename, exportState.fileName),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Normal,
                            color = if (isDarkTheme) TextColorLight.copy(alpha = 0.8f) else TextColorDark.copy(alpha = 0.8f)
                        )

                        OutlinedButton(
                            onClick = {
                                try {
                                    val fileId = exportState.fileId.orEmpty()
                                    val driveUri = Uri.parse("https://drive.google.com/file/d/$fileId/view")
                                    val intent = Intent(Intent.ACTION_VIEW, driveUri)
                                        .setPackage("com.google.android.apps.docs")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // Use uma string de log fixa em vez de stringResource aqui
                                    Log.e("ExportDialog", "Erro ao abrir o Drive: ${e.message ?: "Erro desconhecido"}")
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = exportGreenColor
                            ),
                            border = BorderStroke(1.dp, exportGreenColor)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = exportGreenColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(id = R.string.export_dialog_open_drive),
                                color = exportGreenColor
                            )
                        }
                    }
                    is ExportState.Error -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Erro",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = stringResource(id = R.string.export_dialog_error_prefix, exportState.message),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (isDarkTheme) TextColorLight else TextColorDark
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (exportState) {
                is ExportState.Initial -> {
                    Button(
                        onClick = onExportConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = exportGreenColor
                        )
                    ) {
                        Text(
                            text = stringResource(id = R.string.export_dialog_action_export),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                is ExportState.Success, is ExportState.Error -> {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = exportGreenColor
                        )
                    ) {
                        Text(
                            text = stringResource(id = R.string.export_dialog_action_ok),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (exportState is ExportState.Initial) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(id = R.string.cancel),
                        fontWeight = FontWeight.Medium,
                        color = if (isDarkTheme) TextColorLight.copy(alpha = 0.8f) else Color.DarkGray
                    )
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = if (isDarkTheme) Color(0xFF121212) else SurfaceColor,
        tonalElevation = if (isDarkTheme) 8.dp else 4.dp
    )
}