package com.ivip.brainstormia

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ivip.brainstormia.components.ThemeSwitch
import com.ivip.brainstormia.theme.BackgroundColor
import com.ivip.brainstormia.theme.PrimaryColor
import com.ivip.brainstormia.theme.TextColorDark
import com.ivip.brainstormia.theme.TextColorLight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppDrawerContent(
    conversationDisplayItems: List<ConversationDisplayItem>,
    currentConversationId: Long?,
    onConversationClick: (Long) -> Unit,
    onNewChatClick: () -> Unit,
    onDeleteConversationRequest: (Long) -> Unit,
    onRenameConversationRequest: (Long) -> Unit,
    onExportConversationRequest: (Long) -> Unit,
    onNavigateToProfile: () -> Unit,
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit = {},
    chatViewModel: ChatViewModel = viewModel(),
    authViewModel: AuthViewModel = viewModel() // Adicionado para acessar o usuário atual
) {
    val backgroundColor = if (isDarkTheme) Color(0xFF121212) else BackgroundColor
    val textColor = if (isDarkTheme) TextColorLight else TextColorDark
    val selectedItemColor = if (isDarkTheme) PrimaryColor.copy(alpha = 0.2f) else PrimaryColor.copy(alpha = 0.1f)
    val currentUser by authViewModel.currentUser.collectAsState() // Obtém o usuário atual
    val selectedModel by chatViewModel.selectedModel.collectAsState()
    val isPremiumUser by chatViewModel.isPremiumUser.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(backgroundColor)
            .padding(top = 16.dp, bottom = 16.dp)
    ) {
        // Header with theme toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.conversations),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = textColor
            )

            // Add ThemeSwitch here
            ThemeSwitch(
                isDarkTheme = isDarkTheme,
                onThemeChanged = onThemeChanged,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // Nova conversa button
        Button(
            onClick = onNewChatClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDarkTheme) Color(0xFF333333) else PrimaryColor
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.new_conversation),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.new_conversation_button),
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Divider(
            color = if (isDarkTheme) Color.DarkGray.copy(alpha = 0.5f) else Color.LightGray.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Lista de conversas - Adicionamos uma key para forçar recomposição
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = rememberLazyListState() // Adicionar um estado explícito
        ) {
            // Em vez de usar key(), usamos items com o parâmetro key
            items(
                items = conversationDisplayItems.sortedByDescending { it.lastTimestamp },
                key = { it.id } // Garantir que cada item tenha uma chave única
            ) { item ->
                val isSelected = item.id == currentConversationId
                val itemBackgroundColor = if (isSelected) selectedItemColor else Color.Transparent

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(itemBackgroundColor)
                            .clickable { onConversationClick(item.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Removido o ícone baseado no tipo de conversa

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = item.displayTitle,
                                color = textColor,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 15.sp
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                                    .format(Date(item.lastTimestamp)),
                                color = textColor.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                        }

                        // Cor padronizada para todos os ícones de ação
                        val actionIconTint = if (isDarkTheme) Color.LightGray else PrimaryColor.copy(alpha = 0.7f)

                        // Ações em um espaço mais compacto
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp) // Diminuir espaçamento
                        ) {
                            // Ícones menores e mais compactos
                            IconButton(
                                onClick = { onRenameConversationRequest(item.id) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.rename_conversation),
                                    tint = actionIconTint,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            val isPremiumUser by chatViewModel.isPremiumUser.collectAsState()

                            if (isPremiumUser == true) {
                                IconButton(
                                    onClick = { onExportConversationRequest(item.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Upload,
                                        contentDescription = stringResource(R.string.export_conversation),
                                        tint = actionIconTint,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { onDeleteConversationRequest(item.id) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete_conversation),
                                    tint = actionIconTint,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Separador antes do botão de perfil
        Divider(
            color = if (isDarkTheme) Color.DarkGray.copy(alpha = 0.5f) else Color.LightGray.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Botão de perfil do usuário (Tipo outlined, estilo ChatGPT)
        if (currentUser != null) {
            val context = LocalContext.current
            OutlinedButton(
                onClick = { onNavigateToProfile() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isDarkTheme) TextColorLight else TextColorDark,
                    containerColor = if (isDarkTheme) Color(0xFF1A1A1A) else Color.White
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isDarkTheme) Color.DarkGray.copy(alpha = 0.7f) else Color.LightGray
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    // Foto do usuário (ou ícone padrão se não houver foto)
                    if (currentUser?.photoUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(currentUser?.photoUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = stringResource(R.string.profile_photo),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .border(
                                    width = 1.dp,
                                    color = if (isDarkTheme) Color.DarkGray else Color.LightGray,
                                    shape = CircleShape
                                )
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = stringResource(R.string.profile),
                            tint = if (isDarkTheme) Color.LightGray else PrimaryColor.copy(alpha = 0.8f),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Email do usuário com ellipsis se for muito longo
                    Text(
                        text = currentUser?.email ?: stringResource(R.string.email_not_available),
                        color = if (isDarkTheme) TextColorLight else TextColorDark,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Ícone para indicar que é clicável
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = if (isDarkTheme) Color.LightGray else PrimaryColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        } else {
            // Versão simplificada para usuários não logados
            OutlinedButton(
                onClick = { onNavigateToProfile() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isDarkTheme) TextColorLight else TextColorDark,
                    containerColor = if (isDarkTheme) Color(0xFF1A1A1A) else Color.White
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isDarkTheme) Color.DarkGray.copy(alpha = 0.7f) else Color.LightGray
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = "Perfil do usuário",
                        tint = if (isDarkTheme) Color.LightGray else PrimaryColor.copy(alpha = 0.8f),
                        modifier = Modifier.size(28.dp)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = "Fazer login",
                        color = if (isDarkTheme) TextColorLight else TextColorDark,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = if (isDarkTheme) Color.LightGray else PrimaryColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// Extensão para aplicar alpha à cor
private fun Modifier.alpha(alpha: Float): Modifier {
    return this.graphicsLayer(alpha = alpha)
}
