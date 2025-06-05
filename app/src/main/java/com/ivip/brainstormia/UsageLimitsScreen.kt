package com.ivip.brainstormia.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivip.brainstormia.api.ModelUsageInfo
import com.ivip.brainstormia.viewmodels.UsageLimitsViewModel
import com.ivip.brainstormia.viewmodels.UsageState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageLimitsScreen(
    onBack: () -> Unit,
    isDarkTheme: Boolean = true,
    viewModel: UsageLimitsViewModel = viewModel()
) {
    val usageState by viewModel.usageState.collectAsState()

    // Cores do tema
    val backgroundColor = if (isDarkTheme) Color(0xFF121212) else Color(0xFFF0F4F7)
    val cardColor = if (isDarkTheme) Color(0xFF1E1E1E) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val primaryColor = if (isDarkTheme) Color(0xFFFFD700) else Color(0xFF1976D2)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp)
    ) {
        // Header com botão de voltar e título
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Voltar",
                    tint = textColor
                )
            }

            Text(
                text = "Limites de Uso",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = textColor,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = { viewModel.refreshUsageData() }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Atualizar",
                    tint = textColor
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (val state = usageState) {
            is UsageState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = primaryColor)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Carregando dados de uso...",
                            color = textColor
                        )
                    }
                }
            }

            is UsageState.Error -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Erro ao carregar dados",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.Red,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = state.message,
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { viewModel.refreshUsageData() },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                        ) {
                            Text("Tentar Novamente", color = Color.White)
                        }
                    }
                }
            }

            is UsageState.Success -> {
                val allUsage = state.data

                // Informações do usuário
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Tipo de usuário:",
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor
                            )

                            Text(
                                text = if (allUsage.isPremium) "Premium ⭐" else "Básico",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (allUsage.isPremium) Color(0xFFFFD700) else textColor
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Próximo reset: ${
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                    .format(Date(allUsage.nextReset))
                            }",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Lista de modelos agrupados por provedor
                val modelsByProvider = allUsage.usage.entries.groupBy { entry ->
                    when {
                        entry.key.startsWith("claude") -> "Anthropic (Claude)"
                        entry.key.startsWith("gemini") -> "Google (Gemini)"
                        entry.key.startsWith("gpt") ||
                                entry.key.startsWith("dall") ||
                                entry.key.startsWith("o") -> "OpenAI (GPT)"
                        else -> "Outros"
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    modelsByProvider.forEach { (provider, models) ->
                        item {
                            Text(
                                text = provider,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = primaryColor,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }

                        items(models) { (modelName, usage) ->
                            ModelUsageCard(
                                modelName = modelName,
                                usage = usage,
                                isPremium = allUsage.isPremium,
                                isDarkTheme = isDarkTheme
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModelUsageCard(
    modelName: String,
    usage: ModelUsageInfo,
    isPremium: Boolean,
    isDarkTheme: Boolean
) {
    val cardColor = if (isDarkTheme) Color(0xFF1E1E1E) else Color.White
    val textColor = if (isDarkTheme) Color.White else Color.Black

    val percentage = if (usage.limit > 0) {
        (usage.current.toFloat() / usage.limit) * 100f
    } else 0f

    val progressColor = when {
        percentage > 90f -> Color.Red
        percentage > 70f -> Color.Yellow
        else -> if (isDarkTheme) Color(0xFFFFD700) else Color(0xFF1976D2)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = getDisplayName(modelName),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { (percentage / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = progressColor,
                    trackColor = if (isDarkTheme) Color(0xFF333333) else Color(0xFFE0E0E0)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "${usage.current}/${usage.limit}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Restante: ${usage.remaining}",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.7f)
                )

                if (percentage > 80f) {
                    Text(
                        text = "${String.format("%.1f", percentage)}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = progressColor
                    )
                }
            }
        }
    }
}

// Função auxiliar para obter nome de exibição amigável
private fun getDisplayName(modelId: String): String {
    return when (modelId) {
        "claude-opus-4-20250514" -> "Claude Opus 4"
        "claude-sonnet-4-20250514" -> "Claude Sonnet 4"
        "claude-3-7-sonnet-latest" -> "Claude 3.7 Sonnet"
        "claude-3-5-sonnet-20241022" -> "Claude 3.5 Sonnet"
        "gemini-2.5-pro-preview-05-06" -> "Gemini 2.5 Pro"
        "gemini-2.5-flash-preview-05-20" -> "Gemini 2.5 Flash"
        "gemini-2.0-flash" -> "Gemini 2.0 Flash"
        "gpt-4.1" -> "GPT-4.1"
        "gpt-4.1-mini" -> "GPT-4.1 Mini"
        "gpt-4o" -> "GPT-4o"
        "gpt-4o-mini" -> "GPT-4o Mini"
        "gpt-4.5-preview" -> "GPT-4.5 Preview"
        "o1" -> "GPT o1"
        "o3" -> "GPT o3"
        "o3-mini" -> "GPT o3 Mini"
        "o4-mini" -> "GPT o4 Mini"
        "dall-e-3" -> "DALL-E 3"
        "dall-e-2" -> "DALL-E 2"
        "whisper" -> "Whisper"
        "text-embedding-ada" -> "Text Embedding Ada"
        else -> modelId.replace("-", " ").split(" ").joinToString(" ") {
            it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString() }
        }
    }
}