package com.ivip.brainstormia

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivip.brainstormia.components.ThemeSwitch
import com.ivip.brainstormia.theme.BackgroundColor
import com.ivip.brainstormia.theme.BackgroundColorDark
import com.ivip.brainstormia.theme.PrimaryColor
import com.ivip.brainstormia.theme.TextColorDark
import com.ivip.brainstormia.theme.TextColorLight

@Composable
fun PasswordResetScreen(
    onBackToLogin: () -> Unit,
    authViewModel: AuthViewModel = viewModel(),
    isDarkTheme: Boolean = true,
    onThemeChanged: (Boolean) -> Unit = {}
) {
    var email by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    val passwordResetSuccessText = stringResource(id = R.string.password_reset_success_pt)
    val emailRequiredErrorText = stringResource(id = R.string.email_required_error)

    // Track auth state
    val authState by authViewModel.authState.collectAsState()

    // Theme-specific colors
    val backgroundColor = if (isDarkTheme) BackgroundColorDark else BackgroundColor
    val cardColor = if (isDarkTheme) Color(0xFF121212) else Color.White // Mudando para mais escuro
    val textColor = if (isDarkTheme) TextColorLight else TextColorDark
    val textSecondaryColor = if (isDarkTheme) TextColorLight.copy(alpha = 0.7f) else TextColorDark.copy(alpha = 0.9f)
    val inputBgColor = if (isDarkTheme) Color(0xFF212121) else Color.White // Usando #212121 para inputs
    val borderColor = if (isDarkTheme) Color.Gray.copy(alpha = 0.3f) else Color.Gray.copy(alpha = 0.5f)
    val cardElevation = if (isDarkTheme) 8.dp else 4.dp

    // Handle auth state changes
    LaunchedEffect(authState) {
        when (authState) {
            is AuthState.PasswordResetSent -> {
                successMessage = passwordResetSuccessText
                errorMessage = null
            }
            is AuthState.Error -> {
                errorMessage = (authState as AuthState.Error).message
                successMessage = null
            }
            else -> {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isDarkTheme) Color(0xFF121212) else backgroundColor,
                shape = RectangleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        // Theme switch in top-right corner
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(if (isDarkTheme) Color(0xFF121212) else backgroundColor)
        ) {
            ThemeSwitch(
                isDarkTheme = isDarkTheme,
                onThemeChanged = onThemeChanged
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
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
                        .size(90.dp)
                        .clip(CircleShape)
                        .background(PrimaryColor.copy(alpha = if (isDarkTheme) 0.2f else 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_bolt_foreground),
                        contentDescription = stringResource(id = R.string.logo_description),
                        modifier = Modifier.size(70.dp),
                        colorFilter = ColorFilter.tint(if (isDarkTheme) TextColorLight else PrimaryColor)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(animationSpec = tween(durationMillis = 500))
                ) {
                    Text(
                        text = stringResource(id = R.string.password_reset_screen_title),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = textColor,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Text(
                    text = stringResource(id = R.string.password_reset_instructions_pt),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = textSecondaryColor,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = {
                        Text(
                            stringResource(id = R.string.email),
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
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                // Error message
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

                // Success message
                AnimatedVisibility(
                    visible = successMessage != null,
                    enter = fadeIn(animationSpec = tween(durationMillis = 300)),
                    exit = fadeOut(animationSpec = tween(durationMillis = 300))
                ) {
                    successMessage?.let {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .background(
                                    color = Color(0xFF43A047),
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
                        successMessage = null

                        if (email.isBlank()) {
                            errorMessage = emailRequiredErrorText
                        }

                        // Send password reset email
                        authViewModel.resetPassword(email)
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
                        stringResource(id = R.string.send_email_button),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                TextButton(
                    onClick = onBackToLogin,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(id = R.string.back),
                            tint = if (isDarkTheme) TextColorLight else PrimaryColor
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            stringResource(id = R.string.back_to_login_button),
                            color = if (isDarkTheme) TextColorLight else PrimaryColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }
                }

                if (authState is AuthState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(top = 16.dp),
                        color = PrimaryColor,
                        strokeWidth = 3.dp
                    )
                }
            }
        }
    }
}