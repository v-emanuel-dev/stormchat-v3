package com.ivip.brainstormia.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ivip.brainstormia.R
import com.ivip.brainstormia.data.models.AIModel
import com.ivip.brainstormia.theme.BrainGold

@Composable
fun ModelSelectionDropdown(
    models: List<AIModel>,
    selectedModel: AIModel,
    onModelSelected: (AIModel) -> Unit,
    isPremiumUser: Boolean?,
    isDarkTheme: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    val backgroundColor = if (isDarkTheme) Color(0xFF212121) else Color(0xFFF5F5F5)
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val goldColor = BrainGold

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = backgroundColor
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 2.dp
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.ai_model_selector_title),
                        fontWeight = FontWeight.Medium,
                        color = textColor,
                        modifier = Modifier.padding(end = 8.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(goldColor)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = selectedModel.displayName,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black, // Always BLACK text on gold background regardless of theme
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 4.dp)
                        )

                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
                            tint = Color.Black // Always BLACK icon on gold background regardless of theme
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .padding(top = 8.dp)
                .align(Alignment.TopEnd)
                .background(if (isDarkTheme) Color(0xFF282828) else Color.White)
        ) {
            models.forEach { model ->
                val isDisabled = model.isPremium && isPremiumUser != true
                val rowBackgroundColor = when {
                    model.id == selectedModel.id -> goldColor
                    isDisabled -> if (isDarkTheme) Color(0xFF333333) else Color(0xFFEEEEEE)
                    else -> Color.Transparent
                }

                // Determine text color based on background and state
                val itemTextColor = when {
                    model.id == selectedModel.id -> Color.Black // Selected items always have BLACK text on gold background
                    isDisabled -> if (isDarkTheme) Color.Gray else Color.Gray.copy(alpha = 0.6f)
                    else -> if (isDarkTheme) Color.White else Color.Black
                }

                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = model.displayName,
                                fontWeight = if (model.id == selectedModel.id) FontWeight.Bold else FontWeight.Normal,
                                color = itemTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            if (model.isPremium) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = if (isPremiumUser == true) Icons.Default.Star else Icons.Default.Lock,
                                    contentDescription = stringResource(R.string.premium_feature),
                                    tint = if (model.id == selectedModel.id) Color.Black else goldColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        if (!isDisabled) {
                            onModelSelected(model)
                            expanded = false
                        }
                    },
                    enabled = !isDisabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBackgroundColor)
                )
            }
        }
    }
}
