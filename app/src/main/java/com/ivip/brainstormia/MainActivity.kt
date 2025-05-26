package com.ivip.brainstormia

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import com.ivip.brainstormia.auth.GoogleSignInManager
import com.ivip.brainstormia.billing.PaymentScreen
import com.ivip.brainstormia.navigation.Routes
import com.ivip.brainstormia.theme.BrainstormiaTheme
import com.ivip.brainstormia.theme.PrimaryColor
import com.ivip.brainstormia.theme.TopBarColorDark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// DataStore for theme preferences
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class ThemePreferences(private val context: Context) {
    companion object { val DARK_THEME_ENABLED = booleanPreferencesKey("dark_mode_enabled") }

    val isDarkThemeEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DARK_THEME_ENABLED] ?: true // Default to dark theme
    }

    suspend fun setDarkThemeEnabled(enabled: Boolean) {
        context.dataStore.edit { settings ->
            settings[DARK_THEME_ENABLED] = enabled
        }
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var googleSignInManager: GoogleSignInManager
    private lateinit var themePreferences: ThemePreferences
    private lateinit var firebaseAnalytics: FirebaseAnalytics
    private lateinit var crashlytics: FirebaseCrashlytics

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        firebaseAnalytics.logEvent("google_signin_result", Bundle().apply {
            putInt("result_code", result.resultCode)
            putBoolean("has_data", result.data != null)
        })

        // Adicionar registro para Crashlytics
        Log.d("googlelogin", "MainActivity: Resultado de login Google recebido, código: ${result.resultCode}, tem dados: ${result.data != null}")
        crashlytics.apply {
            log("Google Sign In result received in MainActivity")
            setCustomKey("signin_result_code", result.resultCode)
            setCustomKey("signin_has_data", result.data != null)
        }

        lifecycleScope.launch {
            try {
                val signInResult = googleSignInManager.handleSignInResult(result.data)

                when (signInResult) {
                    is GoogleSignInManager.SignInResult.Success -> {
                        Log.d("googlelogin", "MainActivity: Login Google bem-sucedido: ${signInResult.user.email}")
                        crashlytics.log("Google login successful in MainActivity")
                        handleLoginSuccess(signInResult.user.email, null)
                    }
                    is GoogleSignInManager.SignInResult.Error -> {
                        Log.e("googlelogin", "MainActivity: Login Google falhou: ${signInResult.message}")
                        crashlytics.apply {
                            log("Google login failed in MainActivity: ${signInResult.message}")
                            setCustomKey("main_auth_error_message", signInResult.message)
                            // Tornar o erro fatal
                            recordException(RuntimeException("Falha de login Google via MainActivity: ${signInResult.message}"))
                        }
                        Toast.makeText(this@MainActivity, signInResult.message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("googlelogin", "MainActivity: Erro inesperado ao processar resultado de login", e)
                crashlytics.apply {
                    log("Exception in MainActivity sign in result handling")
                    // Tornar o erro fatal
                    recordException(e)
                }
                Toast.makeText(
                    this@MainActivity,
                    "Erro inesperado durante o login: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("MainActivity", "Notification permission granted")
            crashlytics.log("Permissão de notificação concedida")
        } else {
            Log.w("MainActivity", "Notification permission denied")
            crashlytics.log("Permissão de notificação negada")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installSplashScreen()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        firebaseAnalytics = Firebase.analytics
        crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(true)

        // Registrar informações de sessão
        try {
            val appInfo = packageManager.getPackageInfo(packageName, 0)
            crashlytics.setCustomKey("app_version_name", appInfo.versionName ?: "unknown")
            crashlytics.setCustomKey("app_version_code", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                appInfo.longVersionCode else appInfo.versionCode.toLong())
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting package info", e)
        }

        crashlytics.log("Iniciando MainActivity")
        themePreferences = ThemePreferences(this)

        // Inicializar o gerenciador de login Google
        try {
            googleSignInManager = GoogleSignInManager(this)
            crashlytics.log("GoogleSignInManager inicializado com sucesso")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing GoogleSignInManager", e)
            crashlytics.log("Erro ao inicializar GoogleSignInManager")
            crashlytics.recordException(e)
            // Exibir mensagem amigável
            Toast.makeText(
                this,
                "Erro ao inicializar componentes de login. Por favor, tente novamente.",
                Toast.LENGTH_LONG
            ).show()
        }

        requestNotificationPermission()
        handleNotificationIntent(intent)

        // Configuração do Firebase Messaging
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                Log.d("FCM_TOKEN", "FCM Token obtained: $token")
                val localPrefs = getSharedPreferences("brainstormia_prefs", Context.MODE_PRIVATE)
                localPrefs.edit().putString("fcm_token", token).apply()
                crashlytics.log("FCM token obtido com sucesso")
            } else {
                Log.e("FCM_TOKEN", "Failed to get token: ${task.exception}")
                crashlytics.log("Falha ao obter token FCM")
                task.exception?.let { crashlytics.recordException(it) }
            }
        }

        setContent {
            val navController = rememberNavController()
            val isDarkThemeEnabled by themePreferences.isDarkThemeEnabled.collectAsState(initial = true)

            val authViewModel: AuthViewModel = viewModel()
            val currentUser by authViewModel.currentUser.collectAsState()

            val app = applicationContext as BrainstormiaApplication
            val chatViewModelInstance = app.chatViewModel
            val exportViewModelInstance = app.exportViewModel

            var showLoadingOverlay by remember { mutableStateOf(false) }
            val exportState by exportViewModelInstance.exportState.collectAsState()

            DisposableEffect(isDarkThemeEnabled) {
                // Obtenha as cores da TopBar usando a constante definida
                val topBarColor = if (isDarkThemeEnabled) {
                    TopBarColorDark.toArgb()  // Usando a constante atualizada Color(0xFF1E1E1E)
                } else {
                    PrimaryColor.toArgb()
                }

                val BackgroundColorBlack = Color(0xFF121212)  // #121212  (modo escuro)
                val BackgroundColor      = Color(0xFFF0F4F7)  // #F0F4F7  (modo claro)

                // Aplique as cores às barras do sistema
                window.statusBarColor = topBarColor
                window.navigationBarColor = topBarColor

                // Evita áreas brancas em tela cheia, preenchendo to.do o fundo da janela
                // Usando exatamente as cores solicitadas: #121212 para escuro e #F0F4F7 para claro
                window.decorView.setBackgroundColor(if (isDarkThemeEnabled) BackgroundColorBlack.toArgb() else BackgroundColor.toArgb())

                // Configure a visibilidade dos ícones (sempre brancos, já que ambos os fundos são escuros)
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = false  // Ícones sempre brancos, já que PrimaryColor é escuro
                    isAppearanceLightNavigationBars = false  // Ícones sempre brancos
                }

                onDispose {}
            }

            LaunchedEffect(exportState) {
                showLoadingOverlay = exportState is ExportState.Loading
            }

            val authState by authViewModel.authState.collectAsState()
            LaunchedEffect(authState, navController.currentDestination?.route) {
                // Só ative o overlay global quando NÃO estiver na tela de autenticação
                showLoadingOverlay = authState is AuthState.Loading &&
                        (navController.currentDestination?.route != Routes.AUTH &&
                                navController.currentDestination?.route != Routes.RESET_PASSWORD)
            }

            BrainstormiaTheme(darkTheme = isDarkThemeEnabled) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    Surface(Modifier.fillMaxSize()) {
                        NavHost(
                            navController = navController,
                            startDestination = if (currentUser != null) Routes.MAIN else Routes.AUTH
                        ) {
                            composable(Routes.AUTH) {
                                AuthScreen(
                                    onNavigateToChat = {
                                        navController.navigate(Routes.MAIN) {
                                            popUpTo(Routes.AUTH) { inclusive = true }
                                        }
                                    },
                                    onBackToChat = { navController.popBackStack() },
                                    onNavigateToPasswordReset = {
                                        navController.navigate(Routes.RESET_PASSWORD)
                                    },
                                    authViewModel  = authViewModel,
                                    isDarkTheme    = isDarkThemeEnabled,
                                    onThemeChanged = { enabled -> lifecycleScope.launch { themePreferences.setDarkThemeEnabled(enabled) } }
                                )
                            }
                            composable(Routes.RESET_PASSWORD) {
                                PasswordResetScreen(
                                    onBackToLogin = { navController.popBackStack() },
                                    authViewModel  = authViewModel,
                                    isDarkTheme    = isDarkThemeEnabled,
                                    onThemeChanged = { enabled -> lifecycleScope.launch { themePreferences.setDarkThemeEnabled(enabled) } }
                                )
                            }
                            composable(Routes.MAIN) {
                                ChatScreen(
                                    onLogin         = { launchLogin() },
                                    onLogout        = { authViewModel.logout() },
                                    onNavigateToProfile = { navController.navigate(Routes.USER_PROFILE) },
                                    chatViewModel   = chatViewModelInstance,
                                    authViewModel   = authViewModel,
                                    exportViewModel = exportViewModelInstance,
                                    isDarkTheme     = isDarkThemeEnabled,
                                    onThemeChanged  = { enabled -> lifecycleScope.launch { themePreferences.setDarkThemeEnabled(enabled) } }
                                )
                            }
                            composable(Routes.USER_PROFILE) {
                                UserProfileScreen(
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToPayment = {
                                        navController.navigate(Routes.PAYMENT)
                                    },
                                    authViewModel = authViewModel,
                                    isDarkTheme = isDarkThemeEnabled
                                )
                            }
                            composable(Routes.PAYMENT) {
                                PaymentScreen(
                                    onNavigateBack = { navController.popBackStack() },
                                    onPurchaseComplete = {
                                        navController.popBackStack(Routes.USER_PROFILE, inclusive = false)
                                    },
                                    isDarkTheme = isDarkThemeEnabled
                                )
                            }
                        }
                    }

                    if (showLoadingOverlay) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = PrimaryColor)
                        }
                    }
                }
            }
        }
    }

    private fun launchLogin() {
        Log.d("googlelogin", "MainActivity: Iniciando processo de login Google")
        crashlytics.log("Iniciando login Google via MainActivity")

        // Execute diagnóstico antes de iniciar login
        try {
            val authDiagnostics = AuthDiagnostics(this)
            authDiagnostics.runDiagnostics()
        } catch (e: Exception) {
            Log.e("googlelogin", "MainActivity: Erro ao executar diagnóstico antes do login", e)
            crashlytics.recordException(e)
        }

        try {
            // Obter WebClientID para log
            val webClientId = getString(R.string.default_web_client_id)
            Log.d("googlelogin", "MainActivity: Usando WebClientID: $webClientId")
            crashlytics.log("WebClientID em uso: $webClientId")

            // Usar o gerenciador para iniciar o login
            googleSignInManager.signOut() // Limpar estado anterior
            Log.d("googlelogin", "MainActivity: Lançando intent de login Google")
            signInLauncher.launch(googleSignInManager.getSignInIntent())
        } catch (e: Exception) {
            Log.e("googlelogin", "MainActivity: Erro ao lançar login Google", e)
            crashlytics.apply {
                log("Exception launching Google sign in")
                // Tornar o erro fatal
                recordException(e)
            }
            Toast.makeText(
                this,
                "Erro ao iniciar login com Google: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handleLoginSuccess(email: String?, idToken: String?) {
        crashlytics.apply {
            log("Login success handling in MainActivity")
            setCustomKey("email_domain", email?.substringAfter('@') ?: "unknown")
        }

        firebaseAnalytics.logEvent("google_login_success", Bundle().apply {
            putString("email_domain", email?.substringAfter('@') ?: "unknown")
        })

        try {
            val app = applicationContext as BrainstormiaApplication
            app.exportViewModel.setupDriveService()
            app.chatViewModel.handleLogin()

            Toast.makeText(
                this,
                "Welcome ${email ?: "back"}!",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in handleLoginSuccess", e)
            crashlytics.apply {
                log("Exception in handleLoginSuccess")
                recordException(e)
            }

            // Mostrar uma mensagem amigável mas ainda permitir a navegação
            Toast.makeText(
                this,
                "Bem-vindo! Algumas funcionalidades podem estar limitadas.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun requestNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    crashlytics.log("Solicitando permissão de notificação")
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error requesting notification permission", e)
            crashlytics.log("Erro ao solicitar permissão de notificação")
            crashlytics.recordException(e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        try {
            if (intent == null) return

            crashlytics.log("Processando intent de notificação")
            val app = applicationContext as BrainstormiaApplication

            if (intent.getBooleanExtra("check_subscription", false)) {
                Log.d("MainActivity", "Received subscription check notification, verifying status...")
                crashlytics.log("Verificando status de assinatura via notificação")
                app.handleSubscriptionCancellationNotification()
            }

            val conversationId = intent.getLongExtra("conversation_id", -1L)
            if (conversationId != -1L) {
                Log.d("MainActivity", "Opening conversation from notification: $conversationId")
                crashlytics.log("Abrindo conversa a partir da notificação: $conversationId")
                crashlytics.setCustomKey("conversation_opened_from_notification", true)
                app.chatViewModel.selectConversation(conversationId)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error processing notification intent", e)
            crashlytics.log("Erro ao processar intent de notificação")
            crashlytics.recordException(e)
        }
    }

    override fun onResume() {
        super.onResume()
        crashlytics.log("MainActivity.onResume")
    }

    override fun onPause() {
        super.onPause()
        crashlytics.log("MainActivity.onPause")
    }

    override fun onDestroy() {
        super.onDestroy()
        crashlytics.log("MainActivity.onDestroy")
    }
}