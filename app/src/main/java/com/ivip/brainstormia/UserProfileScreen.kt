package com.ivip.brainstormia

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.ivip.brainstormia.theme.BrainGold
import com.ivip.brainstormia.theme.PrimaryColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPayment: () -> Unit,
    authViewModel: AuthViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
    isDarkTheme: Boolean = true
) {
    val context = LocalContext.current
    val billingViewModel = (context.applicationContext as? BrainstormiaApplication)?.billingViewModel
        ?: throw IllegalStateException("BillingViewModel não inicializado na BrainstormiaApplication.")

    val goldColor = BrainGold
    val primaryColorForTheme = if (isDarkTheme) goldColor else PrimaryColor

    val currentTextColor = if (isDarkTheme) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface
    val currentSecondaryTextColor = if (isDarkTheme) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant

    val currentUser by authViewModel.currentUser.collectAsState()
    val isPremiumUser by billingViewModel.isPremiumUser.collectAsState(initial = false)
    val userPlanType by billingViewModel.userPlanType.collectAsState()
    val isPremiumLoading by billingViewModel.isPremiumLoading.collectAsState(initial = false)
    var isRefreshing by remember { mutableStateOf(false) }

    var selectedImagePreviewUri by remember { mutableStateOf<Uri?>(null) }
    val userMessage by authViewModel.userMessage.collectAsState()
    val isUpdatingProfilePic by authViewModel.isUpdatingProfilePicture.collectAsState()

    val emailFromCurrentUser = currentUser?.email ?: ""
    val displayName = currentUser?.displayName ?: stringResource(R.string.brainstormer)
    val persistedPhotoUrlString = currentUser?.photoUrl?.toString()

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val autoBackupEnabled by settingsViewModel.isAutoBackupEnabled.collectAsState()

    LaunchedEffect(userMessage) {
        userMessage?.let { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            authViewModel.clearUserMessage()
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImagePreviewUri = it
            authViewModel.updateProfilePicture(it)
        }
    }

    LaunchedEffect(Unit) {
        isRefreshing = true
        billingViewModel.forceRefreshPremiumStatus()
        delay(1000)
        isRefreshing = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = if (isDarkTheme) {
                            listOf(Color(0xFF101010), Color(0xFF181818))
                        } else {
                            listOf(Color(0xFFE8F0F6), Color(0xFFF8F9FA))
                        }
                    )
                )
        )

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.my_profile), fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    isRefreshing = true
                                    billingViewModel.forceRefreshPremiumStatus(); delay(800); isRefreshing = false
                                }
                            },
                            enabled = !isPremiumLoading && !isRefreshing && !isUpdatingProfilePic
                        ) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_status)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (isDarkTheme) MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp) else PrimaryColor,
                        titleContentColor = if (isDarkTheme) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = if (isDarkTheme) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = if (isDarkTheme) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
                    )
                )
            },
            containerColor = Color.Transparent
        ) { paddingValues ->
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp).verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                ProfileHeader(
                    displayName = displayName,
                    email = emailFromCurrentUser,
                    displayImageUri = selectedImagePreviewUri ?: persistedPhotoUrlString?.let { Uri.parse(it) },
                    onPhotoChangeRequested = { imagePickerLauncher.launch("image/*") },
                    isPremium = isPremiumUser,
                    planType = userPlanType,
                    isDarkTheme = isDarkTheme,
                    isLoading = isPremiumLoading || isUpdatingProfilePic, // Removido isRefreshing daqui pois o ProfileHeader já tem seu próprio isLoading
                    textColor = currentTextColor,
                    secondaryTextColor = currentSecondaryTextColor
                )

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDarkTheme) MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp) else MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (isDarkTheme) 2.dp else 3.dp),
                    border = if(!isDarkTheme) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)) else null
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = currentTextColor,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        if (isPremiumUser) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        settingsViewModel.setAutoBackupEnabled(!autoBackupEnabled)
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.auto_backup_title),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = currentTextColor
                                    )
                                    Text(
                                        text = if (autoBackupEnabled) stringResource(R.string.enabled) else stringResource(R.string.disabled),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = currentSecondaryTextColor
                                    )
                                }
                                Switch(
                                    checked = autoBackupEnabled,
                                    onCheckedChange = { enabled ->
                                        settingsViewModel.setAutoBackupEnabled(enabled)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = if (isDarkTheme) goldColor else PrimaryColor,
                                        checkedTrackColor = (if (isDarkTheme) goldColor else PrimaryColor).copy(alpha = 0.5f),
                                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                )
                            }
                            Text(
                                text = stringResource(R.string.auto_backup_description_premium),
                                style = MaterialTheme.typography.bodySmall,
                                color = currentSecondaryTextColor,
                                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                            )

                            OutlinedButton(
                                onClick = { settingsViewModel.scheduleOneTimeBackupForTest() },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (isDarkTheme) goldColor else PrimaryColor
                                ),
                                border = BorderStroke(1.dp, if (isDarkTheme) goldColor.copy(alpha = 0.7f) else PrimaryColor.copy(alpha = 0.7f))
                            ) {
                                Icon(
                                    Icons.Default.Backup,
                                    contentDescription = stringResource(R.string.test_one_time_backup_button),
                                    modifier = Modifier.size(ButtonDefaults.IconSize)
                                )
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.test_one_time_backup_button))
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                message = context.getString(R.string.auto_backup_requires_premium),
                                                duration = SnackbarDuration.Short
                                            )
                                        }
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.auto_backup_title),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = currentTextColor.copy(alpha = 0.6f)
                                    )
                                    Text(
                                        text = stringResource(R.string.disabled),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = currentSecondaryTextColor.copy(alpha = 0.6f)
                                    )
                                }
                                Switch(
                                    checked = false,
                                    onCheckedChange = null,
                                    enabled = false,
                                    colors = SwitchDefaults.colors(
                                        disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                )
                            }
                            Text(
                                text = stringResource(R.string.auto_backup_description_non_premium),
                                style = MaterialTheme.typography.bodySmall,
                                color = currentSecondaryTextColor,
                                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                when {
                    isPremiumLoading || isRefreshing || isUpdatingProfilePic -> LoadingContent(isDarkTheme = isDarkTheme)
                    // Passar onNavigateToPayment para PremiumContent
                    isPremiumUser -> PremiumContent(isDarkTheme, currentTextColor, currentSecondaryTextColor, primaryColorForTheme)
                    else -> BasicContent(onNavigateToPayment, isDarkTheme, currentTextColor, currentSecondaryTextColor, primaryColorForTheme)
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            authViewModel.logout()
                            onNavigateBack()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.9f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = if (isDarkTheme) ButtonDefaults.buttonColors(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    else ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = if (isDarkTheme) null else BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = stringResource(R.string.logout)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.logout_text),
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun ProfileHeader(
    displayName: String,
    email: String,
    displayImageUri: Uri?,
    onPhotoChangeRequested: () -> Unit,
    isPremium: Boolean,
    planType: String?,
    isDarkTheme: Boolean,
    isLoading: Boolean,
    textColor: Color,
    secondaryTextColor: Color
) {
    val context = LocalContext.current
    val goldColor = BrainGold
    val cardBackgroundColor = if (isDarkTheme) MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp) else MaterialTheme.colorScheme.surface
    val premiumBorderBrush = Brush.linearGradient(colors = listOf(goldColor, goldColor.copy(alpha = 0.6f)))
    val defaultBorderBrush = Brush.linearGradient(colors = listOf(PrimaryColor, MaterialTheme.colorScheme.tertiary))

    val infiniteTransition = rememberInfiniteTransition(label = "ProfileHeaderGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = if (isPremium) 0.4f else 0.0f,
        targetValue = if (isPremium) 0.8f else 0.0f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "GlowAnimation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isDarkTheme) 8.dp else 5.dp,
                shape = RoundedCornerShape(20.dp),
                spotColor = if (isDarkTheme) goldColor.copy(alpha = 0.25f) else PrimaryColor.copy(alpha = 0.2f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackgroundColor),
        border = if (isPremium && !isDarkTheme) BorderStroke(1.dp, goldColor.copy(alpha = 0.5f)) else null
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                val imageModifierBase = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(3.dp, if (isPremium) premiumBorderBrush else defaultBorderBrush, CircleShape)
                    .clickable(enabled = !isLoading) { onPhotoChangeRequested() }

                val imageToLoad = displayImageUri

                if (imageToLoad != null) {
                    val painter = rememberAsyncImagePainter(
                        model = ImageRequest.Builder(context)
                            .data(imageToLoad)
                            .crossfade(true)
                            // Removido .error(R.drawable.ic_profile_placeholder)
                            // Removido .placeholder(R.drawable.ic_profile_placeholder)
                            .build()
                    )
                    Image(
                        painter = painter,
                        contentDescription = stringResource(R.string.profile_photo),
                        modifier = imageModifierBase,
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = imageModifierBase,
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AccountCircle,
                            contentDescription = stringResource(R.string.profile_photo_placeholder),
                            modifier = Modifier.size(70.dp),
                            tint = if (isDarkTheme) Color.LightGray else PrimaryColor.copy(alpha = 0.6f)
                        )
                    }
                }

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = if (isDarkTheme && isPremium) goldColor else PrimaryColor,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = (8).dp, y = (8).dp)
                    ) {
                        IconButton(
                            onClick = onPhotoChangeRequested,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(10.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit_photo), tint = PrimaryColor, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(displayName, style = MaterialTheme.typography.headlineSmall, color = textColor, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(if (email.isBlank()) stringResource(R.string.email_not_available) else email, style = MaterialTheme.typography.bodyMedium, color = secondaryTextColor, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(12.dp))

            if (!isLoading || LocalInspectionMode.current) {
                val statusBackgroundColor = when {
                    isPremium && isDarkTheme -> goldColor.copy(alpha = 0.25f)
                    isPremium && !isDarkTheme -> MaterialTheme.colorScheme.primaryContainer
                    !isPremium && isDarkTheme -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    else -> MaterialTheme.colorScheme.secondaryContainer
                }
                val statusTextColor = when {
                    isPremium && isDarkTheme -> goldColor
                    isPremium && !isDarkTheme -> MaterialTheme.colorScheme.onPrimaryContainer
                    !isPremium && isDarkTheme -> Color.LightGray
                    else -> MaterialTheme.colorScheme.onSecondaryContainer
                }
                val statusBorderColor = when {
                    isPremium && isDarkTheme -> goldColor.copy(alpha = 0.6f)
                    isPremium && !isDarkTheme -> PrimaryColor
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = statusBackgroundColor,
                    border = BorderStroke(1.dp, statusBorderColor),
                    modifier = Modifier.drawBehind { if (isPremium) drawCircle(brush = Brush.radialGradient(listOf(goldColor.copy(alpha = 0.05f * glowAlpha), Color.Transparent), radius = size.width * 0.7f), blendMode = androidx.compose.ui.graphics.BlendMode.Plus) }
                ) {
                    Text(
                        text = if (isPremium) stringResource(R.string.premium_member) else stringResource(R.string.basic_member),
                        color = statusTextColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                if (isPremium && !planType.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.plan_prefix, planType.replaceFirstChar { it.titlecase() }),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isPremium && isDarkTheme) goldColor.copy(alpha = 0.85f) else statusTextColor.copy(alpha = 0.9f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun LoadingContent(isDarkTheme: Boolean) {
    val cardBackgroundColor = if (isDarkTheme) MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp) else MaterialTheme.colorScheme.surface
    Card(
        modifier = Modifier.fillMaxWidth(0.9f).padding(vertical = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDarkTheme) 3.dp else 4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                color = if (isDarkTheme) BrainGold else PrimaryColor,
                modifier = Modifier.size(48.dp),
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                stringResource(R.string.processing),
                color = if (isDarkTheme) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun AddCorrectUserButton(
    isDarkTheme: Boolean,
    textColor: Color,
    secondaryTextColor: Color
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Título explicativo
        Text(
            text = "Problemas com a assinatura?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isDarkTheme) Color(0xFFFF5252) else Color(0xFFD32F2F),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Texto explicativo
        Text(
            text = "Foi detectada uma alteração no email do usuário nas verificações de assinatura. " +
                    "Use o botão abaixo para corrigir o problema.",
            style = MaterialTheme.typography.bodyMedium,
            color = secondaryTextColor,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(bottom = 16.dp)
        )
    }
}

@Composable
fun PremiumContent(
    isDarkTheme: Boolean,
    textColor: Color,
    secondaryTextColor: Color,
    highlightColor: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = highlightColor.copy(alpha = 0.08f)),
            border = BorderStroke(1.dp, highlightColor.copy(alpha = 0.25f))
        ) {
            Text(
                stringResource(R.string.premium_thanks),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(16.dp)
            )
        }

        // Opcional: Adicionar mais informações sobre os benefícios premium aqui
        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDarkTheme)
                    MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                else
                    MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isDarkTheme) 2.dp else 3.dp),
            border = if(!isDarkTheme) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)) else null
        ) {
        }
    }
}

@Composable
fun BasicContent(
    onUpgradeToPremium: () -> Unit,
    isDarkTheme: Boolean,
    textColor: Color,
    secondaryTextColor: Color,
    highlightColor: Color
) {
    val cardBackgroundColor = if (isDarkTheme) MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp) else MaterialTheme.colorScheme.surface
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PremiumButton(
            onClick = onUpgradeToPremium,
            text = stringResource(R.string.become_premium),
            isDarkTheme = isDarkTheme
        )
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            stringResource(R.string.unlock_potential),
            style = MaterialTheme.typography.headlineSmall,
            color = highlightColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 20.dp)
        )
        FeatureCard(
            title = stringResource(R.string.advanced_ai_models_title),
            description = stringResource(R.string.advanced_ai_models_desc_basic),
            icon = Icons.Outlined.AutoAwesome,
            isDarkTheme = isDarkTheme,
            cardBackgroundColor = cardBackgroundColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor,
            highlightColor = highlightColor,
            isLocked = true
        )
        Spacer(modifier = Modifier.height(12.dp))
        FeatureCard(
            title = stringResource(R.string.conversation_export_title),
            description = stringResource(R.string.conversation_export_desc_basic_auto_backup),
            icon = Icons.Outlined.CloudDownload,
            isDarkTheme = isDarkTheme,
            cardBackgroundColor = cardBackgroundColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor,
            highlightColor = highlightColor,
            isLocked = true
        )
        Spacer(modifier = Modifier.height(12.dp))
        FeatureCard(
            title = stringResource(R.string.priority_support_title),
            description = stringResource(R.string.priority_support_desc_basic),
            icon = Icons.Outlined.SupportAgent,
            isDarkTheme = isDarkTheme,
            cardBackgroundColor = cardBackgroundColor,
            textColor = textColor,
            secondaryTextColor = secondaryTextColor,
            highlightColor = highlightColor,
            isLocked = true
        )
    }
}

@Composable
fun FeatureCard(
    title: String,
    description: String,
    icon: ImageVector,
    isDarkTheme: Boolean,
    cardBackgroundColor: Color,
    textColor: Color,
    secondaryTextColor: Color,
    highlightColor: Color,
    isLocked: Boolean = false
) {
    val iconBg = if (isDarkTheme) highlightColor.copy(alpha = 0.1f) else MaterialTheme.colorScheme.primaryContainer
    val iconTint = if (isDarkTheme) highlightColor else MaterialTheme.colorScheme.primary
    val lockTint = if (isDarkTheme) highlightColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Card(
        modifier = Modifier.fillMaxWidth(0.95f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDarkTheme) 2.dp else 3.dp),
        border = if(!isDarkTheme) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)) else null
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, title, tint = iconTint, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = textColor, fontWeight = FontWeight.Bold)
                    if (isLocked) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.locked_feature),
                            tint = lockTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium, color = secondaryTextColor, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
fun PremiumButton(onClick: () -> Unit, text: String, isDarkTheme: Boolean) {
    val goldColor = BrainGold
    val lightThemeGradient = Brush.linearGradient(colors = listOf(Color(0xFFFBC02D), Color(0xFFF9A825)))
    val darkThemeGradient = Brush.linearGradient(colors = listOf(goldColor, goldColor.copy(alpha = 0.8f)))

    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDarkTheme) darkThemeGradient else lightThemeGradient, shape = RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.StarOutline, null, tint = if (isDarkTheme) Color.Black else Color.White.copy(alpha = 0.95f))
                Spacer(Modifier.width(10.dp))
                Text(text, color = if (isDarkTheme) Color.Black else Color.White.copy(alpha = 0.95f), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
    }
}