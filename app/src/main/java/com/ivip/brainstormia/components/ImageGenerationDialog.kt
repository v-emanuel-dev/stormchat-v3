package com.ivip.brainstormia.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ivip.brainstormia.ImageGenerationResult
import com.ivip.brainstormia.R
import com.ivip.brainstormia.theme.TextColorDark
import com.ivip.brainstormia.theme.TextColorLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenerationDialog(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onGenerateImage: (prompt: String, quality: String, size: String, transparent: Boolean) -> Unit,
    generationState: ImageGenerationResult?,
    isDarkTheme: Boolean
) {
    if (!isVisible) return

    var prompt by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf("standard") }
    var size by remember { mutableStateOf("1024x1024") }
    var transparent by remember { mutableStateOf(false) }

    val textColor = if (isDarkTheme) TextColorLight else TextColorDark
    val backgroundColor = if (isDarkTheme) Color(0xFF2D2D2D) else Color(0xFFF5F5F5)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = backgroundColor
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.generate_image),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.image_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = if (isDarkTheme) Color.Gray else Color.DarkGray
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Quality selection
                Text(
                    text = "${stringResource(R.string.quality)}: $quality",
                    color = textColor,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RadioButton(
                        selected = quality == "standard",
                        onClick = { quality = "standard" }
                    )
                    RadioButton(
                        selected = quality == "hd",
                        onClick = { quality = "hd" }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Size selection
                Text(
                    text = "Tamanho: $size",
                    color = textColor,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RadioButton(
                        selected = size == "1024x1024",
                        onClick = { size = "1024x1024" }
                    )
                    RadioButton(
                        selected = size == "1024x1536",
                        onClick = { size = "1024x1536" }
                    )
                    RadioButton(
                        selected = size == "1536x1024",
                        onClick = { size = "1536x1024" }
                    )
                }

                // Transparent background toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = transparent,
                        onCheckedChange = { transparent = it }
                    )

                    Text(
                        text = stringResource(R.string.transparent_background),
                        color = textColor
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (generationState) {
                    is ImageGenerationResult.Loading -> {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = generationState.message,
                            color = textColor
                        )
                    }
                    is ImageGenerationResult.Error -> {
                        Text(
                            text = generationState.message,
                            color = Color.Red
                        )
                    }
                    is ImageGenerationResult.Success -> {
                        Text(
                            text = stringResource(R.string.image_generated_successfully),
                            color = Color.Green
                        )
                    }
                    null -> {
                        // No status yet
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDarkTheme) Color.DarkGray else Color.LightGray
                        )
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    Button(
                        onClick = {
                            if (prompt.isNotBlank()) {
                                onGenerateImage(prompt, quality, size, transparent)
                            }
                        },
                        enabled = prompt.isNotBlank() && generationState !is ImageGenerationResult.Loading
                    ) {
                        Text(stringResource(R.string.generate))
                    }
                }
            }
        }
    }
}