package com.ivip.brainstormia

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ivip.brainstormia.auth.GoogleSignInManager
import com.ivip.brainstormia.theme.BackgroundColor
import com.ivip.brainstormia.theme.BackgroundColorDark
import com.ivip.brainstormia.theme.PrimaryColor
import com.ivip.brainstormia.theme.TextColorDark
import com.ivip.brainstormia.theme.TextColorLight
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onNavigateToChat: () -> Unit,
    onBackToChat: () -> Unit,
    onNavigateToPasswordReset: () -> Unit,
    authViewModel: AuthViewModel = viewModel(),
    isDarkTheme: Boolean = true,
    onThemeChanged: (Boolean) -> Unit = {}
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLogin by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }

    val authState by authViewModel.authState.collectAsState()
    val context = LocalContext.current

    // Estado para controlar a visibilidade do spinner de carregamento
    var isLoading by remember { mutableStateOf(false) }

    // Strings que precisam ser obtidas com context.getString para uso em lambdas
    val fillAllFieldsError = context.getString(R.string.fill_all_fields)
    val googleAuthFailedPrefix = "Falha na autenticação com Google: "

    // Theme-specific colors
    val backgroundColor = if (isDarkTheme) BackgroundColorDark else BackgroundColor
    val cardColor = if (isDarkTheme) Color(0xFF121212) else Color.White
    val textColor = if (isDarkTheme) TextColorLight else TextColorDark
    val textSecondaryColor = if (isDarkTheme) TextColorLight.copy(alpha = 0.7f) else TextColorDark.copy(alpha = 0.9f)
    val inputBgColor = if (isDarkTheme) Color(0xFF212121) else Color.White
    val borderColor = if (isDarkTheme) Color.Gray.copy(alpha = 0.3f) else Color.Gray.copy(alpha = 0.5f)
    val dividerColor = if (isDarkTheme) Color.Gray.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.3f)
    val cardElevation = if (isDarkTheme) 8.dp else 4.dp
    val raioYellowColor = Color(0xFFFFD700) // Amarelo dourado

    // Inicializar o gerenciador de login Google
    val googleSignInManager = remember { GoogleSignInManager(context) }
    val coroutineScope = rememberCoroutineScope()

    // Configurar o launcher para o Google Sign-In
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isLoading = true
        Log.d("googlelogin", "AuthScreen: Resultado de login Google recebido, processando...")

        coroutineScope.launch {
            try {
                val signInResult = googleSignInManager.handleSignInResult(result.data)

                when (signInResult) {
                    is GoogleSignInManager.SignInResult.Success -> {
                        Log.d("googlelogin", "AuthScreen: Login Google bem-sucedido: ${signInResult.user.email}")
                        FirebaseCrashlytics.getInstance().log("Login Google bem-sucedido via AuthScreen")
                        authViewModel.handleFirebaseUser(signInResult.user)
                    }
                    is GoogleSignInManager.SignInResult.Error -> {
                        Log.e("googlelogin", "AuthScreen: Login Google falhou: ${signInResult.message}")
                        FirebaseCrashlytics.getInstance().log("Login Google falhou via AuthScreen: ${signInResult.message}")
                        // Reportar como erro fatal
                        FirebaseCrashlytics.getInstance().recordException(
                            RuntimeException("Falha de autenticação Google via AuthScreen: ${signInResult.message}")
                        )
                        errorMessage = signInResult.message
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                Log.e("googlelogin", "AuthScreen: Exceção durante processamento de login Google", e)
                FirebaseCrashlytics.getInstance().log("Exceção durante processamento de login Google via AuthScreen")
                // Reportar como erro fatal
                FirebaseCrashlytics.getInstance().recordException(e)
                errorMessage = "Erro inesperado: ${e.message}"
                isLoading = false
            }
        }
    }

    LaunchedEffect(authState) {
        when (authState) {
            is AuthState.Loading -> isLoading = true
            is AuthState.Success -> {
                isLoading = false
                onNavigateToChat()
            }
            is AuthState.Error -> {
                isLoading = false
                errorMessage = (authState as AuthState.Error).message
            }
            else -> { isLoading = false }
        }
    }

    // Box externo para garantir que não haja cantos arredondados
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDarkTheme) Color(0xFF121212) else backgroundColor)
    ) {
        // Box principal com todo o conteúdo
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (isDarkTheme) Color(0xFF121212) else backgroundColor,
                    shape = RectangleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // Botão de troca de tema no canto superior direito
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .zIndex(999f), // Garante que aparece acima de outros elementos
                shape = CircleShape,
                shadowElevation = 8.dp,
                color = if (isDarkTheme) Color.White else Color.Black
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable { onThemeChanged(!isDarkTheme) }
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = if (isDarkTheme)
                            stringResource(R.string.switch_to_light)
                        else
                            stringResource(R.string.switch_to_dark),
                        tint = if (isDarkTheme) Color.Black else Color.White
                    )
                }
            }

            // Card principal
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.99f)
                    .padding(16.dp)
                    .shadow(
                        elevation = cardElevation,
                        spotColor = Color.Black.copy(alpha = if (isDarkTheme) 0.3f else 0.1f),
                        shape = RoundedCornerShape(32.dp)
                    ),
                shape = RoundedCornerShape(32.dp),
                colors = CardDefaults.cardColors(
                    containerColor = cardColor
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(if (isDarkTheme) Color(0xFF333333) else PrimaryColor.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_bolt_foreground),
                            contentDescription = stringResource(R.string.logo_description),
                            modifier = Modifier.size(80.dp),
                            colorFilter = ColorFilter.tint(if (isDarkTheme) Color(0xFFFFD700) else Color.Black)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isLogin)
                            stringResource(R.string.login)
                        else
                            stringResource(R.string.create_account),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = textColor,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = {
                            Text(
                                stringResource(R.string.email),
                                fontWeight = FontWeight.Medium,
                                color = if (isDarkTheme) Color.LightGray else Color.DarkGray
                            )
                        },
                        textStyle = TextStyle(
                            fontWeight = FontWeight.Medium,
                            color = textColor
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = inputBgColor,
                            unfocusedContainerColor = inputBgColor,
                            focusedBorderColor = PrimaryColor,
                            unfocusedBorderColor = borderColor,
                            focusedLabelColor = PrimaryColor,
                            unfocusedLabelColor = if (isDarkTheme) Color.LightGray else Color.DarkGray,
                            cursorColor = PrimaryColor,
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor
                        ),
                        keyboardOptions = KeyboardOptions.Default.copy(
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = {
                            Text(
                                stringResource(R.string.password),
                                fontWeight = FontWeight.Medium,
                                color = if (isDarkTheme) Color.LightGray else Color.DarkGray
                            )
                        },
                        textStyle = TextStyle(
                            fontWeight = FontWeight.Medium,
                            color = textColor
                        ),
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = inputBgColor,
                            unfocusedContainerColor = inputBgColor,
                            focusedBorderColor = PrimaryColor,
                            unfocusedBorderColor = borderColor,
                            focusedLabelColor = PrimaryColor,
                            unfocusedLabelColor = if (isDarkTheme) Color.LightGray else Color.DarkGray,
                            cursorColor = PrimaryColor,
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        trailingIcon = {
                            val image = if (passwordVisible)
                                Icons.Filled.Visibility
                            else Icons.Filled.VisibilityOff
                            val description = if (passwordVisible)
                                stringResource(R.string.hide_password)
                            else
                                stringResource(R.string.show_password)
                            IconButton(onClick = {passwordVisible = !passwordVisible}){
                                Icon(
                                    imageVector = image,
                                    contentDescription = description,
                                    tint = if (isDarkTheme) Color.LightGray else Color.DarkGray
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )

                    // Links agrupados "Não tem uma conta?" e "Esqueci minha senha" (Somente no modo de login)
                    if (isLogin) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TextButton(
                                onClick = { isLogin = !isLogin },
                                contentPadding = PaddingValues(4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.no_account),
                                    color = if (isDarkTheme) TextColorLight else PrimaryColor,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            }

                            TextButton(
                                onClick = onNavigateToPasswordReset,
                                contentPadding = PaddingValues(4.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.forgot_password),
                                    color = if (isDarkTheme) TextColorLight else PrimaryColor,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = errorMessage != null,
                        enter = fadeIn(animationSpec = tween(durationMillis = 300)),
                        exit = fadeOut(animationSpec = tween(durationMillis = 300))
                    ) {
                        errorMessage?.let {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .background(
                                        color = Color(0xFFE53935),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = it,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            errorMessage = null
                            if (email.isBlank() || password.isBlank()) {
                                errorMessage = fillAllFieldsError
                                return@Button
                            }

                            isLoading = true // Ativando o spinner antes do login
                            if (isLogin) {
                                authViewModel.loginWithEmail(email, password)
                            } else {
                                authViewModel.registerWithEmail(email, password)
                            }

                            FirebaseCrashlytics.getInstance().apply {
                                log("Tentativa de autenticação via ${if (isLogin) "login" else "registro"} com email")
                                setCustomKey("auth_email_domain", email.substringAfter('@'))
                            }

                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDarkTheme) Color(0xFF333333) else PrimaryColor,
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 2.dp,
                            pressedElevation = 0.dp
                        )
                    ) {
                        Text(
                            if (isLogin) stringResource(R.string.enter) else stringResource(R.string.register),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Divider(
                            modifier = Modifier
                                .weight(1f),
                            color = dividerColor
                        )
                        Text(
                            text = "  ou  ",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = textSecondaryColor
                        )
                        Divider(
                            modifier = Modifier
                                .weight(1f),
                            color = dividerColor
                        )
                    }

                    // NOVA IMPLEMENTAÇÃO DO BOTÃO DE GOOGLE
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                width = 1.dp,
                                color = borderColor,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .background(inputBgColor)
                            .clickable(enabled = !isLoading) {
                                isLoading = true
                                googleSignInManager.signOut() // Limpar estado anterior
                                googleSignInLauncher.launch(googleSignInManager.getSignInIntent())
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Icon(
                                imageVector = googleIcon(),
                                contentDescription = stringResource(R.string.google_logo_description),
                                modifier = Modifier.size(24.dp),
                                tint = Color.Unspecified
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = stringResource(R.string.login_with_google),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Visible
                            )
                        }
                    }

                    // Removido o botão "Não tem uma conta?" que estava aqui e mantendo apenas
                    // o botão "Já tem uma conta? Faça login" quando está no modo de cadastro
                    if (!isLogin) {
                        TextButton(
                            onClick = { isLogin = !isLogin },
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Text(
                                stringResource(R.string.already_have_account),
                                color = if (isDarkTheme) TextColorLight else PrimaryColor,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }

            // Overlay de carregamento com spinner
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = cardColor
                        ),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = 8.dp
                        ),
                        modifier = Modifier.size(200.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = PrimaryColor,
                                strokeWidth = 5.dp,
                                modifier = Modifier.size(70.dp)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = stringResource(R.string.logging_in),
                                color = textColor,
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun googleIcon() = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(id = R.drawable.ic_google_logo)