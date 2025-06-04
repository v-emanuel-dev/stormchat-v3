package com.ivip.brainstormia

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.util.Log
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.ivip.brainstormia.components.ExportDialog
import com.ivip.brainstormia.components.ImageGenerationDialog
import com.ivip.brainstormia.components.ModelSelectionDropdown
import com.ivip.brainstormia.theme.BackgroundColor
import com.ivip.brainstormia.theme.BotBubbleColor
import com.ivip.brainstormia.theme.PrimaryColor
import com.ivip.brainstormia.theme.SurfaceColor
import com.ivip.brainstormia.theme.SurfaceColorDark
import com.ivip.brainstormia.theme.TextColorDark
import com.ivip.brainstormia.theme.TextColorLight
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.commonmark.node.ThematicBreak
import java.text.DecimalFormat

@Composable
fun MessageBubble(
    message: ChatMessage,
    isDarkTheme: Boolean = true,
    onSaveImageClicked: (String?) -> Unit = {},
    onFileClicked: (String?) -> Unit = {}
) {
    val isUserMessage = message.sender == Sender.USER

    // Verificar se a mensagem contém uma imagem
    val containsImage = message.text.contains("![Imagem Gerada]")

    // Verificar se a mensagem contém um anexo de arquivo
    val containsFileAttachment = message.text.contains("📎 Arquivo:")

    // Se for uma mensagem com imagem, extrair o caminho
    val imagePath = if (containsImage) {
        val regex = "!\\[Imagem Gerada\\]\\((.+?)\\)".toRegex()
        val matchResult = regex.find(message.text)
        matchResult?.groupValues?.get(1)
    } else null

    // Se for uma mensagem com anexo, extrair informações do arquivo
    val fileInfo = if (containsFileAttachment && !containsImage) {
        val regex = "📎 Arquivo: (.+?) \\((.+?)\\)".toRegex()
        val matchResult = regex.find(message.text)
        if (matchResult != null) {
            val fileName = matchResult.groupValues[1]
            val fileSize = matchResult.groupValues[2]
            Pair(fileName, fileSize)
        } else null
    } else null

    val userShape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 6.dp
    )

    val botShape = RoundedCornerShape(
        topStart = 6.dp,
        topEnd = 20.dp,
        bottomStart = 20.dp,
        bottomEnd = 20.dp
    )

    // Colors
    val userBubbleColor = BotBubbleColor
    val userTextColor = Color.White
    val botTextColor =
        if (isDarkTheme) TextColorLight else Color.Black

    val visibleState = remember { MutableTransitionState(initialState = isUserMessage) }

    LaunchedEffect(message) {
        if (!isUserMessage) visibleState.targetState = true
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = if (isUserMessage) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        if (isUserMessage) {
            // User bubble
            Card(
                modifier = Modifier
                    .widthIn(max = LocalConfiguration.current.screenWidthDp.dp * 0.88f),
                shape = userShape,
                colors = CardDefaults.cardColors(containerColor = userBubbleColor),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = if (isDarkTheme) 4.dp else 2.dp
                )
            ) {
                Box(
                    modifier = Modifier
                        .background(userBubbleColor)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (fileInfo != null) {
                        // Mostrar visualização de arquivo na mensagem do usuário
                        Column {
                            // Exibir mensagem do usuário (se houver) sem as informações do anexo
                            val messageText = message.text.replace("\\n\\n📎 Arquivo: .+".toRegex(), "")
                            if (messageText.isNotBlank()) {
                                SelectionContainer {
                                    Text(
                                        text = messageText,
                                        color = userTextColor,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Exibir anexo
                            FileAttachmentCard(
                                fileName = fileInfo.first,
                                fileSize = fileInfo.second,
                                isDarkTheme = isDarkTheme,
                                isUserMessage = true,
                                onFileClick = { onFileClicked(fileInfo.first) }
                            )
                        }
                    } else {
                        // Mensagem normal sem anexo
                        SelectionContainer {
                            Text(
                                text = message.text,
                                color = userTextColor,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                }
            }
        } else {
            // Bot message
            AnimatedVisibility(
                visibleState = visibleState,
                enter = fadeIn(animationSpec = tween(300)) +
                        slideInHorizontally(
                            initialOffsetX = { -40 },
                            animationSpec = tween(400, easing = FastOutSlowInEasing)
                        )
            ) {
                // Para mensagens com imagem, trate de forma diferente
                if (imagePath != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .padding(start = 16.dp, end = 16.dp)
                    ) {
                        // Exibição da imagem simplificada - sem cards ou boxes aninhados
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            var isImageLoading by remember { mutableStateOf(true) }

                            // Imagem sem containers adicionais
                            Image(
                                painter = rememberAsyncImagePainter(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(imagePath)
                                        .listener(
                                            onStart = {
                                                isImageLoading = true
                                            },
                                            onSuccess = { _, _ ->
                                                isImageLoading = false
                                            },
                                            onError = { _, _ ->
                                                isImageLoading = false
                                            }
                                        )
                                        .build()
                                ),
                                contentDescription = stringResource(R.string.generated_image_description),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp),
                                contentScale = ContentScale.Fit // Mantém a proporção original
                            )

                            // Mostrar indicador de carregamento
                            if (isImageLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(30.dp)
                                )
                            } else {
                                // Botão de salvar
                                IconButton(
                                    onClick = {
                                        onSaveImageClicked(imagePath)
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                        .size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SaveAlt,
                                        contentDescription = stringResource(R.string.save_image),
                                        tint = Color.White
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Container para o texto do prompt
                        Card(
                            shape = botShape,
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDarkTheme) Color(0xFF292929) else Color(
                                    0xFFE8E8E8
                                )
                            ),
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (isDarkTheme) 2.dp else 1.dp
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                // Texto do prompt
                                val promptRegex = "\"(.+?)\"".toRegex()
                                val promptMatch = promptRegex.find(message.text)
                                val prompt = promptMatch?.groupValues?.get(1) ?: ""

                                Text(
                                    text = "Imagem gerada com base no prompt:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontStyle = FontStyle.Italic,
                                    color = botTextColor
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "\"$prompt\"",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = botTextColor
                                )
                            }
                        }
                    }
                } else {
                    // Mensagens normais de texto (sem imagem)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        // Use AndroidView com TextView + Markwon
                        AndroidView(
                            factory = { context ->
                                TextView(context).apply {
                                    // Configuração básica do TextView
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                    setTextColor(botTextColor.toArgb())
                                    textSize = 16f
                                    setLineSpacing(4f, 1f)

                                    // Aplicar técnica de correção de seleção de texto
                                    setTextIsSelectable(false)
                                    post {
                                        setTextIsSelectable(true)
                                    }

                                    // Plugin personalizado para lidar com regras horizontais
                                    val customHrPlugin = object : AbstractMarkwonPlugin() {
                                        override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                                            builder.setFactory(ThematicBreak::class.java) { _, _ ->
                                                arrayOf(
                                                    // Criar um span personalizado em vez do HR padrão
                                                    object : LeadingMarginSpan.Standard(0),
                                                        LineBackgroundSpan {
                                                        override fun drawBackground(
                                                            canvas: Canvas,
                                                            paint: Paint,
                                                            left: Int,
                                                            right: Int,
                                                            top: Int,
                                                            baseline: Int,
                                                            bottom: Int,
                                                            text: CharSequence,
                                                            start: Int,
                                                            end: Int,
                                                            lineNumber: Int
                                                        ) {
                                                            val originalColor = paint.color
                                                            val originalWidth = paint.strokeWidth

                                                            // Usar valores diretos em vez de métodos do theme
                                                            paint.color = botTextColor.toArgb()
                                                            paint.strokeWidth = 6f // ~2dp

                                                            // Padding fixo em vez de recursos de dimensão
                                                            val padding = 48 // ~16dp
                                                            val lineLeft = left + padding
                                                            val lineRight = right - padding
                                                            val lineY = (top + bottom) / 2f

                                                            canvas.drawLine(
                                                                lineLeft.toFloat(),
                                                                lineY,
                                                                lineRight.toFloat(),
                                                                lineY,
                                                                paint
                                                            )

                                                            paint.color = originalColor
                                                            paint.strokeWidth = originalWidth
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    // Configurar Markwon para renderizar Markdown com nosso plugin personalizado
                                    val markwon = Markwon.builder(context)
                                        .usePlugin(HtmlPlugin.create())
                                        .usePlugin(LinkifyPlugin.create())
                                        .usePlugin(customHrPlugin) // Adicionar nosso plugin personalizado
                                        .build()

                                    // Renderizar o Markdown
                                    markwon.setMarkdown(this, message.text)
                                }
                            },
                            update = { textView ->
                                // Atualizar quando a mensagem mudar
                                val context = textView.context

                                // Plugin personalizado para lidar com regras horizontais (durante atualizações)
                                val customHrPlugin = object : AbstractMarkwonPlugin() {
                                    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                                        builder.setFactory(ThematicBreak::class.java) { _, _ ->
                                            arrayOf(
                                                object : LeadingMarginSpan.Standard(0),
                                                    LineBackgroundSpan {
                                                    override fun drawBackground(
                                                        canvas: Canvas, paint: Paint,
                                                        left: Int, right: Int,
                                                        top: Int, baseline: Int, bottom: Int,
                                                        text: CharSequence, start: Int, end: Int,
                                                        lineNumber: Int
                                                    ) {
                                                        val originalColor = paint.color
                                                        val originalWidth = paint.strokeWidth

                                                        paint.color = botTextColor.toArgb()
                                                        paint.strokeWidth = 6f

                                                        val padding = 48
                                                        val lineLeft = left + padding
                                                        val lineRight = right - padding
                                                        val lineY = (top + bottom) / 2f

                                                        canvas.drawLine(
                                                            lineLeft.toFloat(),
                                                            lineY,
                                                            lineRight.toFloat(),
                                                            lineY,
                                                            paint
                                                        )

                                                        paint.color = originalColor
                                                        paint.strokeWidth = originalWidth
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                // Criar instância do Markwon com nosso plugin personalizado
                                val markwon = Markwon.builder(context)
                                    .usePlugin(HtmlPlugin.create())
                                    .usePlugin(LinkifyPlugin.create())
                                    .usePlugin(customHrPlugin)
                                    .build()

                                // Resetar o estado de seleção para corrigir o bug
                                textView.setTextIsSelectable(false)

                                // Sempre atualizar a cor do texto quando isDarkTheme muda
                                textView.setTextColor(botTextColor.toArgb())

                                // Renderizar Markdown e reativar seleção
                                markwon.setMarkdown(textView, message.text)
                                textView.post {
                                    textView.setTextIsSelectable(true)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Componente para exibir anexo de arquivo em mensagens
 */
@Composable
fun FileAttachmentCard(
    fileName: String,
    fileSize: String,
    isDarkTheme: Boolean,
    isUserMessage: Boolean,
    onFileClick: () -> Unit
) {
    val backgroundColor = if (isUserMessage) {
        if (isDarkTheme) Color(0xFF0D47A1).copy(alpha = 0.7f) else Color(0xFF1976D2).copy(alpha = 0.7f)
    } else {
        if (isDarkTheme) Color(0xFF333333) else Color(0xFFF5F5F5)
    }

    // MODIFICAÇÃO: Correção das cores do texto para garantir que sejam brancas em tema escuro
    val textColor = if (isUserMessage || isDarkTheme) {
        // Forçar texto branco para: 1) mensagens do usuário OU 2) qualquer mensagem em tema escuro
        Color.White
    } else {
        // Para mensagens não-usuário em tema claro
        Color.Black
    }

    // MODIFICAÇÃO: Ajuste na cor secundária para maior contraste
    val secondaryTextColor = if (isUserMessage || isDarkTheme) {
        // Texto secundário mais visível para mensagens do usuário ou tema escuro
        Color.White.copy(alpha = 0.7f)
    } else {
        Color.DarkGray
    }

    // Determinar o ícone baseado na extensão do arquivo
    val fileIcon = when {
        fileName.endsWith(".pdf", ignoreCase = true) -> Icons.Default.PictureAsPdf
        fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) ||
                fileName.endsWith(".png", ignoreCase = true) || fileName.endsWith(".gif", ignoreCase = true) -> Icons.Default.Image
        fileName.endsWith(".mp4", ignoreCase = true) || fileName.endsWith(".avi", ignoreCase = true) ||
                fileName.endsWith(".mov", ignoreCase = true) -> Icons.Default.VideoFile
        fileName.endsWith(".mp3", ignoreCase = true) || fileName.endsWith(".wav", ignoreCase = true) ||
                fileName.endsWith(".ogg", ignoreCase = true) -> Icons.Default.AudioFile
        fileName.endsWith(".txt", ignoreCase = true) || fileName.endsWith(".doc", ignoreCase = true) ||
                fileName.endsWith(".docx", ignoreCase = true) -> Icons.Default.TextSnippet
        else -> Icons.Default.InsertDriveFile
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable { onFileClick() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Ícone do arquivo
        Icon(
            imageVector = fileIcon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Informações do arquivo
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = fileSize,
                color = secondaryTextColor,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun FileAttachmentPreview(
    attachment: ChatViewModel.FileAttachment,
    onRemoveClick: () -> Unit,
    isDarkTheme: Boolean = true
) {
    val backgroundColor = if (isDarkTheme) Color(0xFF292929) else Color(0xFFE8E8E8)
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val secondaryTextColor = if (isDarkTheme) Color.LightGray else Color.DarkGray
    val accentColor = if (isDarkTheme) PrimaryColor.copy(alpha = 0.7f) else PrimaryColor.copy(alpha = 0.6f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accentColor),  // Adiciona borda para destacar
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDarkTheme) 2.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Título "Anexo" para tornar claro que esse arquivo será enviado
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {

                // Botão para remover o anexo
                IconButton(
                    onClick = onRemoveClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.remove_attachment),
                        tint = secondaryTextColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Informações do arquivo
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Ícone baseado no tipo de arquivo
                val fileIcon = when {
                    attachment.type.startsWith("image/") -> Icons.Default.Image
                    attachment.type.startsWith("video/") -> Icons.Default.VideoFile
                    attachment.type.startsWith("audio/") -> Icons.Default.AudioFile
                    attachment.type.startsWith("application/pdf") -> Icons.Default.PictureAsPdf
                    attachment.type.startsWith("text/") -> Icons.Default.TextSnippet
                    else -> Icons.Default.InsertDriveFile
                }

                Icon(
                    imageVector = fileIcon,
                    contentDescription = "Tipo de arquivo",
                    modifier = Modifier
                        .size(40.dp)
                        .padding(end = 12.dp),
                    tint = textColor
                )

                // Informações do arquivo
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = attachment.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = formatFileSize(attachment.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                }
            }
        }
    }
}

/**
 * Formata o tamanho do arquivo para exibição em unidades legíveis
 */
private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

/**
 * A SelectionContainer that tries to maintain selection when tapping outside
 */
@Composable
fun PersistentSelectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val selectionIsImportant = remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null // No ripple effect
            ) {
                // Do nothing on click, but capture the click event
                // This prevents clicks from bubbling up and canceling selection
            }
    ) {
        SelectionContainer {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToUsageLimits: () -> Unit = {},
    chatViewModel: ChatViewModel,
    authViewModel: AuthViewModel = viewModel(),
    exportViewModel: ExportViewModel,
    isDarkTheme: Boolean = true,
    onThemeChanged: (Boolean) -> Unit = {}
) {
    // Definir cores do tema dentro do Composable
    val backgroundColor =
        if (isDarkTheme) Color(0xFF121212) else BackgroundColor // Usando #121212 como cor de fundo principal
    val textColor = if (isDarkTheme) TextColorLight else TextColorDark

    // Definir a cor amarela para o ícone de raio
    val raioBrandColor = Color(0xFFFFD700) // Cor amarela padrão

    val messages by chatViewModel.messages.collectAsState()
    val conversationDisplayList by chatViewModel.conversationListForDrawer.collectAsState()
    val currentConversationId by chatViewModel.currentConversationId.collectAsState()
    val isLoading by chatViewModel.isLoading.collectAsState()
    val errorMessage by chatViewModel.errorMessage.collectAsState()
    val currentUser by authViewModel.currentUser.collectAsState()
    val logoutEvent by authViewModel.logoutEvent.collectAsState()
    val selectedModel by chatViewModel.selectedModel.collectAsState()

    // Estado de exportação
    val exportState by exportViewModel.exportState.collectAsState()

    // Estado de geração de imagem
    val isImageGenerating by chatViewModel.isImageGenerating.collectAsState()
    val currentImagePrompt by chatViewModel.currentImagePrompt.collectAsState()

    // Estado para arquivos anexados
    val currentAttachment by chatViewModel.currentAttachment.collectAsState()

    var userMessage by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val modelSelectorVisible = remember {
        derivedStateOf {
            // Mostra o seletor quando estamos no topo ou quando é uma nova conversa vazia
            messages.isEmpty() || (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 50)
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var conversationIdToRename by remember { mutableStateOf<Long?>(null) }
    var currentTitleForDialog by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmationDialog by remember { mutableStateOf<Long?>(null) }
    var conversationIdToExport by remember { mutableStateOf<Long?>(null) }
    var exportDialogTitle by remember { mutableStateOf("") }
    val isPremiumUser by chatViewModel.isPremiumUser.collectAsState()

    var showImageGenerationDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Variável para armazenar o caminho da imagem a ser salva após permissão
    var imagePathToSaveAfterPermission by remember { mutableStateOf<String?>(null) }

    // Launcher para seleção de arquivos
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                chatViewModel.handleFileUpload(uri)
            }
        }
    }

    // Definir a permissão a ser solicitada com base na versão Android
    val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Para Android Q (API 29) e superior
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    } else {
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    }

    // Variável para controlar a exibição do diálogo de explicação da permissão
    var showPermissionRationaleDialog by remember { mutableStateOf(false) }

    // Launcher para solicitar permissão
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            imagePathToSaveAfterPermission?.let { path ->
                chatViewModel.saveImageToGallery(path)
                imagePathToSaveAfterPermission = null // Limpa após usar
            }
        } else {
            // Permissão negada, mostrar snackbar
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.image_save_permission_denied),
                    duration = SnackbarDuration.Short
                )
            }
            // Opcionalmente mostrar diálogo explicando a necessidade da permissão
            // showPermissionRationaleDialog = true
        }
    }

    // Função para verificar permissão e salvar imagem
    fun checkAndSaveImage(imagePath: String) {
        // Para Android Q (API 29) e superior, salvar em MediaStore na pasta Pictures/AppName
        // geralmente não requer permissão explícita de WRITE_EXTERNAL_STORAGE.
        // Para Android < Q, a permissão é necessária.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            when (PackageManager.PERMISSION_GRANTED) {
                ContextCompat.checkSelfPermission(context, permissionToRequest) -> {
                    chatViewModel.saveImageToGallery(imagePath)
                }

                else -> {
                    imagePathToSaveAfterPermission = imagePath
                    requestPermissionLauncher.launch(permissionToRequest)
                }
            }
        } else {
            // Em Android Q+ não precisamos de permissão para salvar na pasta específica do App no MediaStore.
            chatViewModel.saveImageToGallery(imagePath)
        }
    }

    // Diálogo de explicação de permissão (opcional)
    if (showPermissionRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionRationaleDialog = false },
            title = { Text(stringResource(R.string.permission_required)) },
            text = { Text(stringResource(R.string.request_save_permission)) },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionRationaleDialog = false
                }) {
                    Text("OK")
                }
            }
        )
    }

    // Efeitos para verificação de usuário premium e outros eventos
    LaunchedEffect(currentUser) {
        if (currentUser != null) {
            // MODIFICAÇÃO: Verificação mais robusta em múltiplos estágios

            // Primeira verificação imediata
            chatViewModel.forceCheckPremiumStatus(highPriority = true)

            // Segunda verificação após breve atraso
            delay(700)
            chatViewModel.forceCheckPremiumStatus(highPriority = true)

            // Terceira verificação após sistema estabilizar
            delay(2000)
            chatViewModel.forceCheckPremiumStatus()
        }
    }

    LaunchedEffect(Unit) {
        // Verificação imediata ao iniciar a tela
        if (currentUser != null) {
            chatViewModel.checkIfUserIsPremium()
        }

        // Verificação periódica
        while (true) {
            delay(60000) // 1 minuto
            if (currentUser != null) {
                chatViewModel.checkIfUserIsPremium()
            }
        }
    }

    // Escutar eventos de mensagem adicionada
    LaunchedEffect(Unit) {
        chatViewModel.messageAddedEvent.collect {
            // Rolar para a última mensagem quando uma nova for adicionada
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    // Escutar eventos de imagem salva
    LaunchedEffect(Unit) {
        chatViewModel.imageSavedEvent.collect { message ->
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Long
            )
        }
    }

    // Escutar eventos de arquivo carregado
    LaunchedEffect(Unit) {
        chatViewModel.fileUploadEvent.collect { attachment ->
            snackbarHostState.showSnackbar(
                message = "Arquivo \"${attachment.name}\" carregado com sucesso",
                duration = SnackbarDuration.Short
            )
        }
    }

    LaunchedEffect(logoutEvent) {
        if (logoutEvent) {
            userMessage = ""
            chatViewModel.handleLogout()
            Log.d("ChatScreen", "Tela e menu lateral limpos após logout")
        }
    }

    LaunchedEffect(exportState) {
        if (exportState is ExportState.Success) {
            // Forçar atualização da lista de conversas após exportação bem-sucedida
            delay(500) // Pequeno atraso para garantir que o banco de dados atualizou
            chatViewModel.refreshConversationList()
        }
    }

    LaunchedEffect(conversationIdToRename) {
        val id = conversationIdToRename
        currentTitleForDialog = if (id != null && id != NEW_CONVERSATION_ID) "" else null
        if (id != null && id != NEW_CONVERSATION_ID) {
            Log.d("ChatScreen", "Fetching title for rename dialog (ID: $id)")
            try {
                currentTitleForDialog = chatViewModel.getDisplayTitle(id)
            } catch (e: Exception) {
                Log.e("ChatScreen", "Error fetching title for rename dialog", e)
            }
        }
    }

    // Novo efeito para buscar o título da conversa a ser exportada
    LaunchedEffect(conversationIdToExport) {
        val id = conversationIdToExport
        if (id != null && id != NEW_CONVERSATION_ID) {
            try {
                exportDialogTitle = chatViewModel.getDisplayTitle(id)
                exportViewModel.setupDriveService()
                Log.d(
                    "ChatScreen",
                    "Preparando para exportar conversa: $exportDialogTitle (ID: $id)"
                )
            } catch (e: Exception) {
                Log.e("ChatScreen", "Erro ao buscar título para exportação", e)
                conversationIdToExport = null
            }
        }
    }

    // Altura desejada para o input e padding
    val inputHeight = 90.dp
    val bottomPadding = 36.dp

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawerContent(
                conversationDisplayItems = conversationDisplayList,
                currentConversationId = currentConversationId,
                onConversationClick = { conversationId ->
                    coroutineScope.launch {
                        drawerState.close()
                        try {
                            chatViewModel.selectConversation(conversationId)
                        } catch (e: Exception) {
                            Log.e("ChatScreen", "Erro ao selecionar conversa $conversationId", e)
                        }
                    }
                },
                onNewChatClick = {
                    coroutineScope.launch {
                        drawerState.close()
                        if (currentConversationId != null && currentConversationId != NEW_CONVERSATION_ID) {
                            chatViewModel.startNewConversation()
                        }
                    }
                },
                onDeleteConversationRequest = { conversationId ->
                    showDeleteConfirmationDialog = conversationId
                },
                onRenameConversationRequest = { conversationId ->
                    Log.d("ChatScreen", "Rename requested for $conversationId. Setting state.")
                    conversationIdToRename = conversationId
                },
                onExportConversationRequest = { conversationId ->
                    exportViewModel.resetExportState()
                    conversationIdToExport = conversationId
                },
                onNavigateToProfile = onNavigateToProfile,
                onNavigateToUsageLimits = {
                    coroutineScope.launch {
                        drawerState.close()
                        // Navegar para a tela de limites via MainActivity
                        onNavigateToUsageLimits()
                    }
                },
                isDarkTheme = isDarkTheme,
                onThemeChanged = onThemeChanged,
                chatViewModel = chatViewModel
            )
        }
    ) {
        // IMPORTANTE: Usamos Box como container principal
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
        ) {
            // TopAppBar
            CenterAlignedTopAppBar(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(),
                windowInsets = WindowInsets(0),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_bolt_foreground),
                            contentDescription = stringResource(R.string.app_icon_description),
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                },
                navigationIcon = {
                    Row {
                        IconButton(onClick = {
                            coroutineScope.launch { drawerState.open() }
                        }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = stringResource(R.string.menu_description),
                                tint = Color.White
                            )
                        }

                        val isPremiumUser by chatViewModel.isPremiumUser.collectAsState()

                        if (isPremiumUser == true && currentConversationId != null && currentConversationId != NEW_CONVERSATION_ID) {
                            IconButton(
                                onClick = {
                                    exportViewModel.resetExportState()
                                    conversationIdToExport = currentConversationId
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = stringResource(R.string.export_conversation_description),
                                    tint = Color.White
                                )
                            }
                        }
                    }
                },
                actions = {
                    val rotation = rememberInfiniteTransition()
                    val angle by rotation.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 6000),
                            repeatMode = RepeatMode.Restart
                        )
                    )
                    // Para usuários premium, mostrar a estrela dourada animada
                    if (isPremiumUser) {
                        IconButton(
                            onClick = { onNavigateToProfile() },
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .graphicsLayer { rotationZ = angle }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = stringResource(R.string.premium_user_description),
                                tint = Color(0xFFFFD700) // Usar cor dourada consistente
                            )
                        }
                    } else {
                        // Para usuários normais, mostrar a estrela branca com a mesma animação rotativa
                        val rotation = rememberInfiniteTransition(label = "basicStarRotation")
                        val angle by rotation.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 6000),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "basicStarAngle"
                        )

                        IconButton(
                            onClick = { onNavigateToProfile() },
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .graphicsLayer { rotationZ = angle }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = stringResource(R.string.upgrade_to_premium),
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            if (currentUser != null) {
                                onLogout()
                            } else {
                                onLogin()
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (currentUser != null) Icons.AutoMirrored.Filled.Logout else Icons.AutoMirrored.Filled.Login,
                            contentDescription = if (currentUser != null) stringResource(
                                R.string.logout
                            ) else stringResource(R.string.login),
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = if (isDarkTheme) Color(0xFF1E1E1E) else PrimaryColor,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )

            // Container para a lista de mensagens, posicionado debaixo da AppBar e acima do input
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp) // Altura aproximada da TopAppBar
                    .padding(bottom = inputHeight + bottomPadding) // Espaço para o input
            ) {
                // Seletor de modelos (aparece no topo da coluna)
                if (currentUser != null) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = modelSelectorVisible.value,
                        enter = fadeIn(animationSpec = tween(300)) +
                                expandVertically(animationSpec = tween(300)),
                        exit = fadeOut(animationSpec = tween(300)) +
                                shrinkVertically(animationSpec = tween(300))
                    ) {
                        key(isPremiumUser) {
                            ModelSelectionDropdown(
                                models = chatViewModel.modelOptions,
                                selectedModel = selectedModel,
                                onModelSelected = { chatViewModel.selectModel(it) },
                                isPremiumUser = isPremiumUser,
                                isDarkTheme = isDarkTheme
                            )
                        }
                    }
                }

                // Lista de mensagens - agora em uma Column para garantir ordem vertical correta
                Box(modifier = Modifier.weight(1f)) {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                                .clickable(
                                    enabled = false,
                                    onClick = {}
                                ),
                            contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(
                                items = messages,
                                key = { index, _ -> index }
                            ) { _, message ->
                                MessageBubble(
                                    message = message,
                                    isDarkTheme = isDarkTheme,
                                    onSaveImageClicked = { imagePath ->
                                        if (imagePath != null) {
                                            checkAndSaveImage(imagePath)
                                        } else {
                                            Log.w(
                                                "ChatScreen",
                                                "Save image clicked, but path was null for message: ${message.text}"
                                            )
                                        }
                                    },
                                    onFileClicked = { fileName ->
                                        if (fileName != null) {
                                            chatViewModel.openFile(fileName)
                                        }
                                    }
                                )
                            }

                            // Mostrar indicador de carregamento durante geração de imagem
                            if (isImageGenerating) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Card(
                                            modifier = Modifier
                                                .widthIn(max = LocalConfiguration.current.screenWidthDp.dp * 0.88f),
                                            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isDarkTheme) Color(0xFF292929) else Color(0xFFE4E4E4)
                                            )
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                LightningLoadingAnimation(
                                                    isDarkTheme = isDarkTheme
                                                )

                                                Spacer(modifier = Modifier.height(8.dp))

                                                Text(
                                                    text = "Gerando imagem...",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (isDarkTheme) TextColorLight else TextColorDark
                                                )

                                                if (!currentImagePrompt.isNullOrBlank()) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "\"${currentImagePrompt}\"",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontStyle = FontStyle.Italic,
                                                        color = if (isDarkTheme) TextColorLight else TextColorDark
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Loading para resposta de texto do modelo
                            if (isLoading) {
                                item {
                                    LightningLoadingAnimation(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        isDarkTheme = isDarkTheme
                                    )
                                }
                            }
                        }
                    }

                    // Mensagens de erro/sucesso
                    errorMessage?.let { errorMsg ->
                        // Determinar se é uma mensagem de sucesso ou erro
                        val isSuccess = errorMsg.contains("sucesso", ignoreCase = true) ||
                                errorMsg.contains("carregado com sucesso", ignoreCase = true)

                        val backgroundColor = if (isSuccess) {
                            Color(0xFF388E3C) // Verde para mensagens de sucesso
                        } else {
                            Color(0xFFE53935) // Vermelho para mensagens de erro
                        }

                        Text(
                            text = if (isSuccess) errorMsg else stringResource(R.string.error_prefix, errorMsg),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                                .background(
                                    color = backgroundColor,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(vertical = 8.dp, horizontal = 12.dp)
                        )
                    }

                    // Botão de rolagem para o topo
                    val showScrollToTopButton = remember {
                        derivedStateOf {
                            listState.firstVisibleItemIndex > 2 ||
                                    (listState.firstVisibleItemIndex > 0 && listState.firstVisibleItemScrollOffset > 100)
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = showScrollToTopButton.value && messages.size > 3,
                        enter = fadeIn(animationSpec = tween(300)),
                        exit = fadeOut(animationSpec = tween(300)),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 16.dp)
                    ) {
                        FloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(index = 0)
                                }
                            },
                            modifier = Modifier.size(46.dp),
                            containerColor = if (isDarkTheme)
                                Color(0xFF3D3D3D) else
                                PrimaryColor,
                            contentColor = Color.White
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.scroll_to_top),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Image generation dialog - movido para fora da Column
                ImageGenerationDialog(
                    isVisible = showImageGenerationDialog,
                    onDismiss = { showImageGenerationDialog = false },
                    onGenerateImage = { prompt, quality, size, transparent ->
                        chatViewModel.generateImage(prompt, quality, size, transparent)
                        showImageGenerationDialog = false
                    },
                    generationState = chatViewModel.imageGenerationState.collectAsState().value,
                    isDarkTheme = isDarkTheme
                )
            }

            // Input fixo na parte inferior com Surface para sombra
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                tonalElevation = 8.dp,
                color = backgroundColor
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = bottomPadding)
                ) {
                    // Exibir preview do arquivo se houver um anexo
                    if (currentAttachment != null) {
                        FileAttachmentPreview(
                            attachment = currentAttachment!!,
                            onRemoveClick = { chatViewModel.clearCurrentAttachment() },
                            isDarkTheme = isDarkTheme
                        )
                    }

                    // Input de mensagens
                    MessageInput(
                        message = userMessage,
                        onMessageChange = { newText -> userMessage = newText },
                        onSendClick = {
                            if (userMessage.isNotBlank() || currentAttachment != null) {
                                if (currentAttachment != null) {
                                    // Se há anexo, usar sendMessageWithAttachment (com ou sem texto)
                                    chatViewModel.sendMessageWithAttachment(userMessage, currentAttachment!!)
                                } else {
                                    // Se não há anexo, usar o sendMessage normal
                                    chatViewModel.sendMessage(userMessage)
                                }
                                userMessage = ""
                            }
                        },
                        onFileUploadClick = { filePickerLauncher.launch("*/*") },
                        isSendEnabled = !isLoading,
                        isDarkTheme = isDarkTheme,
                        viewModel = chatViewModel,
                        onImageGenerationClick = { showImageGenerationDialog = true },
                        hasAttachment = currentAttachment != null
                    )
                }
            }

            // Snackbar host
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = inputHeight + bottomPadding)
            )
        }

        // Diálogos
        showDeleteConfirmationDialog?.let { conversationIdToDelete ->
            AlertDialog(
                onDismissRequest = { showDeleteConfirmationDialog = null },
                title = {
                    Text(
                        text = stringResource(R.string.delete_confirmation_title),
                        fontWeight = FontWeight.Bold,
                        color = if (isDarkTheme) TextColorLight else TextColorDark
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.delete_confirmation_message),
                        fontWeight = FontWeight.Medium,
                        color = if (isDarkTheme) TextColorLight else TextColorDark
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            chatViewModel.deleteConversation(conversationIdToDelete)
                            showDeleteConfirmationDialog = null
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmationDialog = null }) {
                        Text(
                            text = stringResource(R.string.cancel),
                            fontWeight = FontWeight.Medium,
                            color = if (isDarkTheme) TextColorLight.copy(alpha = 0.8f) else Color.DarkGray
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = if (isDarkTheme) SurfaceColorDark else SurfaceColor,
                tonalElevation = if (isDarkTheme) 8.dp else 4.dp
            )
        }

        // Diálogo de renomear conversa
        conversationIdToRename?.let { id ->
            if (currentTitleForDialog != null) {
                RenameConversationDialog(
                    conversationId = id,
                    currentTitle = currentTitleForDialog,
                    onConfirm = { confirmedId, newTitle ->
                        chatViewModel.renameConversation(confirmedId, newTitle)
                        conversationIdToRename = null
                    },
                    onDismiss = {
                        conversationIdToRename = null
                    },
                    isDarkTheme = isDarkTheme
                )
            }
        }

        // Diálogo de exportação
        conversationIdToExport?.let { convId ->
            ExportDialog(
                conversationTitle = exportDialogTitle,
                exportState = exportState,
                onExportConfirm = {
                    exportViewModel.exportConversation(
                        conversationId = convId,
                        title = exportDialogTitle,
                        messages = messages
                    )
                },
                onDismiss = {
                    if (exportState !is ExportState.Loading) {
                        conversationIdToExport = null
                        if (exportState is ExportState.Success) {
                            exportViewModel.resetExportState()
                        }
                    }
                },
                isDarkTheme = isDarkTheme
            )
        }
    }

    // Efeito para rolar para a última mensagem
    LaunchedEffect(messages.size, isLoading) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
}

@Composable
fun MessageInput(
    message: String,
    onMessageChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onFileUploadClick: () -> Unit,
    isSendEnabled: Boolean,
    isDarkTheme: Boolean = true,
    viewModel: ChatViewModel,
    onImageGenerationClick: () -> Unit,
    hasAttachment: Boolean = false // Novo parâmetro para indicar se há um anexo
) {
    var isFocused by remember { mutableStateOf(false) }
    val isListening by viewModel.isListening.collectAsState()

    // Estado para verificar se tem texto sendo digitado
    val isTyping = message.isNotBlank()

    // Adicionar indicador visual de anexo ao campo de texto
    val hintText = if (hasAttachment) {
        stringResource(R.string.message_with_attachment_hint)
    } else {
        stringResource(R.string.message_hint)
    }

    // Modificar a condição para habilitar o botão de enviar
    // Agora, deve ser habilitado se há um anexo (mesmo sem texto) ou se há texto
    val isSendButtonEnabled = isSendEnabled && (message.isNotBlank() || hasAttachment)

    // Animação de piscar para a borda
    val infiniteTransition = rememberInfiniteTransition()
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    // Cores do tema
    val backgroundColor = if (isDarkTheme) Color(0xFF121212) else BackgroundColor
    val surfaceColor = if (isDarkTheme) Color(0xFF333333) else Color(0xFFC8C8C9)
    val disabledContainerColor =
        if (isDarkTheme) Color(0xFF282828) else PrimaryColor.copy(alpha = 0.15f)
    val disabledTextColor =
        if (isDarkTheme) Color.LightGray.copy(alpha = 0.5f) else Color.DarkGray.copy(alpha = 0.7f)
    val disabledCursorColor =
        if (isDarkTheme) PrimaryColor.copy(alpha = 0.7f) else PrimaryColor.copy(alpha = 0.6f)

    // Cor da bolha do usuário (azul usado no botão de enviar)
    val userBubbleColor =
        if (isDarkTheme) Color(0xFF0D47A1) else Color(0xFF1976D2) // Azul da bolha do usuário

    // FIXED: Cor do botão de enviar quando tem texto ou anexo
    val sendButtonColor = when {
        !isSendEnabled -> if (isDarkTheme) Color(0xFF333333) else PrimaryColor.copy(alpha = 0.4f)
        message.isNotBlank() || hasAttachment -> if (isDarkTheme) Color(0xFF333333) else PrimaryColor
        else -> if (isDarkTheme) Color(0xFF333333).copy(alpha = 0.6f) else PrimaryColor.copy(alpha = 0.5f)
    }

    // Cor do placeholder
    val placeholderColor = if (isDarkTheme)
        Color.LightGray.copy(alpha = 0.6f)
    else
        Color.Black.copy(alpha = 0.6f)

    // Cor branca para borda e cursor
    val focusColor = if (isDarkTheme) Color.White else PrimaryColor
    val borderColor = if (isFocused) focusColor.copy(alpha = blinkAlpha) else Color.Transparent
    val cursorColor = if (isFocused) focusColor else disabledCursorColor

    val borderWidth = if (isDarkTheme) 2.dp else 2.5.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding() // Adicionar esta linha
            .background(backgroundColor)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = if (isSendEnabled) surfaceColor else disabledContainerColor,
                    shape = RoundedCornerShape(28.dp)
                )
                .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(28.dp))
                .padding(
                    start = 8.dp,
                    end = 4.dp,
                    top = 2.dp,
                    bottom = 6.dp
                ), // Reduzido padding geral
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = message,
                onValueChange = onMessageChange,
                placeholder = {
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            color = if (isSendEnabled) placeholderColor else disabledTextColor
                        )
                    )
                },
                textStyle = TextStyle(
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = if (isDarkTheme) TextColorLight else TextColorDark
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 50.dp) // Reduzido a altura mínima
                    .onFocusChanged { focusState ->
                        isFocused = focusState.isFocused
                    },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                    focusedContainerColor = if (isSendEnabled) surfaceColor else disabledContainerColor,
                    unfocusedContainerColor = if (isSendEnabled) surfaceColor else disabledContainerColor,
                    disabledContainerColor = disabledContainerColor,
                    cursorColor = cursorColor,
                    focusedTextColor = if (isDarkTheme) TextColorLight else TextColorDark,
                    unfocusedTextColor = if (isDarkTheme) TextColorLight else TextColorDark
                ),
                enabled = isSendEnabled,
                maxLines = 7 // Aumentado para 7 linhas
            )

            // Espaço reduzido entre os componentes
            Spacer(modifier = Modifier.width(4.dp))

            // Áreas de botões agrupados
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 1.dp)
            ) {
                // Botões para quando não está digitando
                if (!isTyping) {
                    // Botão de upload de arquivo (novo)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isDarkTheme) Color.Gray.copy(alpha = 0.3f) else PrimaryColor.copy(alpha = 0.25f)
                            )
                            .clickable(enabled = isSendEnabled) { onFileUploadClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = stringResource(R.string.attach_file),
                            modifier = Modifier.size(18.dp),
                            tint = if (isDarkTheme) Color.White else Color.Black
                        )
                    }

                    // Espaço mínimo entre botões
                    Spacer(modifier = Modifier.width(2.dp))

                    // Botão de microfone
                    SimpleVoiceInputButton(
                        onTextResult = { text ->
                            onMessageChange(text); viewModel.handleVoiceInput(
                            text
                        )
                        },
                        isListening = isListening,
                        onStartListening = { viewModel.startListening() },
                        onStopListening = { viewModel.stopListening() },
                        isSendEnabled = isSendEnabled,
                        isDarkTheme = isDarkTheme,
                        size = 36.dp,
                        iconSize = 18.dp
                    )

                    // Espaço mínimo entre microfone e botão de enviar
                    Spacer(modifier = Modifier.width(2.dp))
                }
            }

            // Botão de enviar (agora funciona tanto para mensagens normais quanto para anexos)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(sendButtonColor)
                    .clickable(enabled = isSendButtonEnabled) { onSendClick() },
                contentAlignment = Alignment.Center
            ) {
                val iconColor = if (!isDarkTheme && (message.isNotBlank() || hasAttachment)) Color.White else Color.White

                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.send_description),
                    modifier = Modifier.size(26.dp),
                    tint = iconColor
                )
            }
        }
    }
}

// Componente de botão de voz otimizado com tamanho personalizável
@Composable
fun SimpleVoiceInputButton(
    onTextResult: (String) -> Unit,
    isListening: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    isSendEnabled: Boolean,
    isDarkTheme: Boolean,
    size: Dp = 36.dp,        // Tamanho do botão (personalizável)
    iconSize: Dp = 18.dp     // Tamanho do ícone (personalizável)
) {
    // Implementação existente, mas com tamanhos personalizáveis
    val backgroundColor = if (isDarkTheme) {
        if (isListening) Color.Red.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.3f)
    } else {
        if (isListening) Color.Red.copy(alpha = 0.6f) else PrimaryColor.copy(alpha = 0.25f)
    }

    Box(
        modifier = Modifier
            .size(size) // Tamanho personalizável
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(enabled = isSendEnabled) {
                if (isListening) {
                    onStopListening()
                } else {
                    onStartListening()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Use Icons.Default.KeyboardVoice em vez de Icons.Default.Mic
        Icon(
            imageVector = Icons.Default.KeyboardVoice, // Alternativa para Icons.Default.Mic
            contentDescription = stringResource(R.string.voice_input_description), // Substitui a descrição nula ou vazia
            modifier = Modifier.size(iconSize), // Tamanho do ícone personalizável
            tint = if (isDarkTheme) Color.White else Color.Black
        )
    }
}

@Composable
fun TypingIndicatorAnimation(
    isDarkTheme: Boolean = true,
    dotSize: Dp = 8.dp,
    spaceBetweenDots: Dp = 4.dp,
    bounceHeight: Dp = 6.dp
) {
    // Definir a cor dos pontos dentro da função
    val dotColor = if (isDarkTheme) TextColorLight else TextColorDark

    val dots = listOf(
        remember { Animatable(0f) },
        remember { Animatable(0f) },
        remember { Animatable(0f) }
    )

    val bounceHeightPx = with(LocalDensity.current) { bounceHeight.toPx() }

    dots.forEachIndexed { index, animatable ->
        LaunchedEffect(animatable) {
            delay(index * 140L)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1000
                        0f at 0 using LinearOutSlowInEasing
                        1f at 250 using LinearOutSlowInEasing
                        0f at 500 using LinearOutSlowInEasing
                        0f at 1000
                    },
                    repeatMode = RepeatMode.Restart
                )
            )
        }
    }

    Row(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dots.forEachIndexed { index, animatable ->
            if (index != 0) {
                Spacer(modifier = Modifier.width(spaceBetweenDots))
            }

            val translateY = -animatable.value * bounceHeightPx

            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        translationY = translateY
                    }
                    .background(color = dotColor, shape = CircleShape)
            )
        }
    }
}

@Composable
fun LightningLoadingAnimation(
    isDarkTheme: Boolean = true,
    modifier: Modifier = Modifier
) {
    // FIXED: Enhanced colors for light theme
    // Cores para a animação do raio ajustadas para melhor contraste em tema claro
    val baseColor =
        if (isDarkTheme) Color(0xFFFFD700) else Color(0xFFB8860B) // Amarelo dourado (escuro) para tema claro
    val accentColor =
        if (isDarkTheme) Color(0xFFFF9500) else Color(0xFFFFA500) // Laranja mais intenso para tema claro

    // Animação de rotação
    val rotation = rememberInfiniteTransition(label = "rotationTransition")
    val rotateAngle by rotation.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rotateAnimation"
    )

    // Animação de escala (pulsar)
    val scale = rememberInfiniteTransition(label = "scaleTransition")
    val scaleSize by scale.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scaleAnimation"
    )

    // Animação de cor
    val colorTransition = rememberInfiniteTransition(label = "colorTransition")
    val colorProgress by colorTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "colorAnimation"
    )

    // Interpolação de cor manual
    val currentColor = androidx.compose.ui.graphics.lerp(
        baseColor,
        accentColor,
        colorProgress
    )

    // Animação de brilho (glow) - ENHANCED
    val glow = rememberInfiniteTransition(label = "glowTransition")
    val glowIntensity by glow.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAnimation"
    )

    Box(
        modifier = modifier
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        // FIXED: Glow effect enhanced for light theme
        // Adicionar um efeito de círculo de "glow" atrás do raio com opacidade aumentada para tema claro
        Box(
            modifier = Modifier
                .size(70.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            currentColor.copy(alpha = if (isDarkTheme) 0.3f * glowIntensity else 0.5f * glowIntensity),
                            currentColor.copy(alpha = if (isDarkTheme) 0.1f * glowIntensity else 0.25f * glowIntensity),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // FIXED: Shadow effect for better visibility in light theme
        if (!isDarkTheme) {
            // Add an extra drop shadow effect for light theme
            Icon(
                painter = painterResource(id = R.drawable.ic_bolt_foreground),
                contentDescription = stringResource(R.string.loading), // Substitui "Carregando..."
                modifier = Modifier
                    .size(50.dp)
                    .graphicsLayer {
                        rotationZ = rotateAngle
                        scaleX = scaleSize
                        scaleY = scaleSize
                        alpha = 0.5f
                    },
                tint = Color.DarkGray.copy(alpha = 0.5f)
            )
        }

        // Ícone do raio
        Icon(
            painter = painterResource(id = R.drawable.ic_bolt_foreground),
            contentDescription = "Carregando...",
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    rotationZ = rotateAngle
                    scaleX = scaleSize
                    scaleY = scaleSize
                    alpha = glowIntensity
                },
            tint = currentColor
        )
    }
}

// Substitua a função TypingBubbleAnimation existente por esta:
@Composable
fun TypingBubbleAnimation(
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = true
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // Substitui a animação de pontos pelo raio animado
        Box(
            modifier = modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            LightningLoadingAnimation(
                isDarkTheme = isDarkTheme
            )
        }
    }
}