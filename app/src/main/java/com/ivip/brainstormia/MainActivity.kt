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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.ivip.brainstormia.billing.BillingViewModel
import com.ivip.brainstormia.billing.PaymentScreen
import com.ivip.brainstormia.navigation.Routes
import com.ivip.brainstormia.screens.UsageLimitsScreen
import com.ivip.brainstormia.theme.BrainstormiaTheme
import com.ivip.brainstormia.theme.PrimaryColor
import com.ivip.brainstormia.theme.TopBarColorDark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// DataStore for theme preferences
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class ThemePreferences(private val context: Context) {
    companion object {
        val DARK_THEME_ENABLED = booleanPreferencesKey("dark_mode_enabled")
    }

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
                            recordException(RuntimeException("Falha de login Google via MainActivity: ${signInResult.message}"))
                        }
                        Toast.makeText(this@MainActivity, signInResult.message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("googlelogin", "MainActivity: Erro inesperado ao processar resultado de login", e)
                crashlytics.apply {
                    log("Exception in MainActivity sign in result handling")
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

    // ✅ NOVA FUNÇÃO: Verificação segura do sistema de billing
    private fun initializeBillingSafely() {
        try {
            Log.d("MainActivity", "=== VERIFICANDO SISTEMA DE BILLING ===")

            val app = applicationContext as? BrainstormiaApplication
            if (app == null) {
                Log.e("MainActivity", "❌ Não foi possível obter BrainstormiaApplication")
                crashlytics.recordException(RuntimeException("BrainstormiaApplication não encontrada"))
                return
            }

            val billingViewModel = app.billingViewModel

            if (billingViewModel == null) {
                Log.w("MainActivity", "⚠️ BillingViewModel não inicializado - tentando recriar")

                try {
                    app.recreateBillingViewModel()
                    Log.d("MainActivity", "✅ BillingViewModel recriado com sucesso")
                } catch (e: Exception) {
                    Log.e("MainActivity", "❌ Falha ao recriar BillingViewModel", e)
                    crashlytics.recordException(e)
                }
            } else {
                Log.d("MainActivity", "✅ BillingViewModel está disponível")
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Erro crítico na verificação de billing", e)
            crashlytics.recordException(e)
        }
    }

    // ✅ NOVA FUNÇÃO: Verificar se billing está funcional
    private fun isBillingAvailable(): Boolean {
        return try {
            val app = applicationContext as? BrainstormiaApplication
            val billing = app?.billingViewModel

            val isAvailable = billing != null
            Log.d("MainActivity", "Billing disponível: $isAvailable")

            isAvailable
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ Erro ao verificar disponibilidade do billing", e)
            crashlytics.recordException(e)
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installSplashScreen()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        firebaseAnalytics = Firebase.analytics
        crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(true)

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

        // ✅ PROTEÇÃO: Verificar billing logo na inicialização
        initializeBillingSafely()

        try {
            googleSignInManager = GoogleSignInManager(this)
            crashlytics.log("GoogleSignInManager inicializado com sucesso")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error initializing GoogleSignInManager", e)
            crashlytics.log("Erro ao inicializar GoogleSignInManager")
            crashlytics.recordException(e)
            Toast.makeText(
                this,
                "Erro ao inicializar componentes de login. Por favor, tente novamente.",
                Toast.LENGTH_LONG
            ).show()
        }

        requestNotificationPermission()
        handleNotificationIntent(intent)

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
                val topBarColor = if (isDarkThemeEnabled) {
                    TopBarColorDark.toArgb()
                } else {
                    PrimaryColor.toArgb()
                }

                val backgroundColor = if (isDarkThemeEnabled) Color(0xFF121212).toArgb() else Color(0xFFF0F4F7).toArgb()

                window.statusBarColor = topBarColor
                window.navigationBarColor = topBarColor

                window.decorView.setBackgroundColor(backgroundColor)

                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }

                onDispose {}
            }

            LaunchedEffect(exportState) {
                showLoadingOverlay = exportState is ExportState.Loading
            }

            val authState by authViewModel.authState.collectAsState()
            LaunchedEffect(authState, navController.currentDestination?.route) {
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
                                    onNavigateToUsageLimits = {
                                        Log.d("MainActivity", "Navegando para limites via ChatScreen")
                                        navController.navigate(Routes.USAGE_LIMITS)
                                    },
                                    chatViewModel   = chatViewModelInstance,
                                    authViewModel   = authViewModel,
                                    exportViewModel = exportViewModelInstance,
                                    isDarkTheme     = isDarkThemeEnabled,
                                    onThemeChanged  = { enabled -> lifecycleScope.launch { themePreferences.setDarkThemeEnabled(enabled) } }
                                )
                            }

                            @OptIn(ExperimentalMaterial3Api::class)
                            composable(Routes.USER_PROFILE) {
                                val user by authViewModel.currentUser.collectAsState()
                                val isPremium by chatViewModelInstance.isPremiumUser.collectAsState()
                                val planType by chatViewModelInstance.userPlanType.collectAsState()

                                // ✅ SOLUÇÃO: UserProfileScreen já é autossuficiente
                                // Ele tem seu próprio Scaffold, TopBar, e verticalScroll
                                // NÃO precisamos de nada extra aqui!

                                UserProfileScreen(
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToPayment = {
                                        if (isBillingAvailable()) {
                                            navController.navigate(Routes.PAYMENT)
                                        } else {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Sistema de pagamentos indisponível.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    },
                                    onNavigateToUsageLimits = {
                                        Log.d("MainActivity", "Navegando para limites via Profile")
                                        navController.navigate(Routes.USAGE_LIMITS)
                                    },
                                    authViewModel = authViewModel,
                                    settingsViewModel = viewModel(),
                                    isDarkTheme = isDarkThemeEnabled
                                )
                            }

                            composable(Routes.PAYMENT) {
                                if (isBillingAvailable()) {
                                    PaymentScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                        onPurchaseComplete = { navController.popBackStack(Routes.USER_PROFILE, inclusive = false) },
                                        isDarkTheme = isDarkThemeEnabled
                                    )
                                } else {
                                    FallbackPaymentScreen(
                                        onNavigateBack = { navController.popBackStack() },
                                        isDarkTheme = isDarkThemeEnabled
                                    )
                                }
                            }
                            composable(Routes.USAGE_LIMITS) {
                                UsageLimitsScreen(
                                    onBack = { navController.popBackStack() },
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

        try {
            val authDiagnostics = AuthDiagnostics(this)
            authDiagnostics.runDiagnostics()
        } catch (e: Exception) {
            Log.e("googlelogin", "MainActivity: Erro ao executar diagnóstico antes do login", e)
            crashlytics.recordException(e)
        }

        try {
            val webClientId = getString(R.string.default_web_client_id)
            Log.d("googlelogin", "MainActivity: Usando WebClientID: $webClientId")
            crashlytics.log("WebClientID em uso: $webClientId")

            googleSignInManager.signOut()
            Log.d("googlelogin", "MainActivity: Lançando intent de login Google")
            signInLauncher.launch(googleSignInManager.getSignInIntent())
        } catch (e: Exception) {
            Log.e("googlelogin", "MainActivity: Erro ao lançar login Google", e)
            crashlytics.apply {
                log("Exception launching Google sign in")
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

            // ✅ PROTEÇÃO: Verificar billing após login bem-sucedido
            initializeBillingSafely()

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

        try {
            if (isBillingAvailable()) {
                Log.d("MainActivity", "✅ Billing verificado no onResume - OK")
            } else {
                Log.w("MainActivity", "⚠️ Billing indisponível no onResume - tentando recriar")
                initializeBillingSafely()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Erro ao verificar billing no onResume", e)
        }
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

// ✅ EXTENSÃO para recriar BillingViewModel
fun BrainstormiaApplication.recreateBillingViewModel() {
    try {
        Log.d("BrainstormiaApp", "=== RECRIANDO BILLING VIEW MODEL ===")

        // Recriar usando o campo público
        val newBilling = BillingViewModel.getInstance(this)

        Log.d("BrainstormiaApp", "✅ BillingViewModel recriado com sucesso")

        if (newBilling != null) {
            Log.d("BrainstormiaApp", "✅ Verificação: BillingViewModel está disponível")
        } else {
            Log.e("BrainstormiaApp", "❌ Verificação: BillingViewModel ainda é NULL")
        }

    } catch (e: Exception) {
        Log.e("BrainstormiaApp", "❌ ERRO CRÍTICO ao recriar BillingViewModel", e)
        FirebaseCrashlytics.getInstance().recordException(e)
    }
}