package com.ivip.brainstormia.auth

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.ivip.brainstormia.BuildConfig
import com.ivip.brainstormia.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class GoogleSignInManager(private val context: Context) {

    private val tag = "googlelogin"
    private val auth = FirebaseAuth.getInstance()
    private val crashlytics = FirebaseCrashlytics.getInstance()

    init {
        // Registrar informações do dispositivo e ambiente
        Log.d(tag, "GoogleSignInManager: inicializando...")
        crashlytics.setCustomKey("device_manufacturer", Build.MANUFACTURER)
        crashlytics.setCustomKey("device_model", Build.MODEL)
        crashlytics.setCustomKey("android_version", Build.VERSION.SDK_INT)
        crashlytics.setCustomKey("app_version", getAppVersionName())
        crashlytics.setCustomKey("has_network", isNetworkAvailable())
        crashlytics.setCustomKey("build_type", if (BuildConfig.DEBUG) "debug" else "release")
        crashlytics.setCustomKey("google_play_services_version", getGooglePlayServicesVersion())
        crashlytics.log("GoogleSignInManager inicializado")

        // Verificar Play Services no início
        val playServicesAvailable = isGooglePlayServicesAvailable()
        crashlytics.setCustomKey("play_services_available", playServicesAvailable)
        Log.d(tag, "GoogleSignInManager: Play Services disponível: $playServicesAvailable")

        // Registrar certificados para diagnóstico
        logAppSignatures()
    }

    private fun logAppSignatures() {
        try {
            Log.d(tag, "GoogleSignInManager: verificando assinaturas do aplicativo...")
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
                // Use safe call for signingInfo
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            // Check if signatures is not null and not empty
            if (signatures?.isNotEmpty() == true) {
                val firstSignature = signatures[0] // Safe to access now
                val sha1 = getSignatureHash(firstSignature, "SHA-1")
                val sha256 = getSignatureHash(firstSignature, "SHA-256")

                Log.d(tag, "GoogleSignInManager: App SHA-1: $sha1")
                Log.d(tag, "GoogleSignInManager: App SHA-256: $sha256")

                crashlytics.log("App SHA-1: $sha1")
                crashlytics.log("App SHA-256: $sha256")

                // Armazenar apenas para diagnóstico
                crashlytics.setCustomKey("app_sha1", sha1)
            } else {
                Log.d(tag, "GoogleSignInManager: Nenhuma assinatura encontrada ou signingInfo é nulo.")
                crashlytics.log("Nenhuma assinatura encontrada ou signingInfo é nulo.")
            }
        } catch (e: Exception) {
            Log.e(tag, "GoogleSignInManager: Falha ao obter assinaturas", e)
            crashlytics.recordException(e)
            // Marcar como FATAL para aparecer no dashboard
            throw RuntimeException("Falha ao verificar assinaturas do app", e)
        }
    }

    private fun getSignatureHash(signature: android.content.pm.Signature, algorithm: String): String {
        val messageDigest = java.security.MessageDigest.getInstance(algorithm)
        messageDigest.update(signature.toByteArray())
        val digest = messageDigest.digest()

        return digest.joinToString(":") {
            String.format("%02x", it)
        }
    }

    fun getSignInIntent(): Intent {
        Log.d(tag, "GoogleSignInManager: Obtendo intent de login")
        crashlytics.log("Obtendo intent de login Google")

        try {
            // Obter o WebClientID diretamente
            val webClientId = context.getString(R.string.default_web_client_id)

            // Log explícito do WebClientID para verificação
            Log.d(tag, "GoogleSignInManager: Usando webClientId: $webClientId")
            crashlytics.log("WebClientID usado para login: $webClientId")

            // Criar um GSO novo a cada chamada para evitar problemas de cache
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken(webClientId)
                // Adicionar configuração explícita para APIs de audiência
                .requestServerAuthCode(webClientId, false)
                .build()

            // Criar um cliente novo a cada vez
            val client = GoogleSignIn.getClient(context, gso)
            Log.d(tag, "GoogleSignInManager: Cliente Google Sign-In criado")

            // Forçar uma atualização das configurações
            client.signOut().addOnCompleteListener {
                Log.d(tag, "GoogleSignInManager: Sign out completado antes de obter intent")
            }

            // Obter a intent com configurações atualizadas
            val intent = client.signInIntent
            Log.d(tag, "GoogleSignInManager: Intent de login obtida com sucesso")

            // Adicionar parâmetros extras para diagnóstico
            intent.putExtra("login_environment", if (BuildConfig.DEBUG) "debug" else "release")

            crashlytics.log("Intent de login Google obtida com sucesso")
            return intent
        } catch (e: Exception) {
            Log.e(tag, "GoogleSignInManager: Falha ao obter intent de login Google", e)
            crashlytics.log("Falha ao obter intent de login Google")
            crashlytics.recordException(e)

            // Marcar como FATAL para aparecer no dashboard
            throw RuntimeException("Falha ao obter intent de login Google", e)
        }
    }

    suspend fun handleSignInResult(data: Intent?): SignInResult {
        return try {
            if (data == null) {
                val error = "Intent de resultado nula"
                Log.e(tag, "GoogleSignInManager: $error")
                crashlytics.log(error)
                // Forçar um registro como FATAL para o dashboard
                throw RuntimeException("GoogleSignIn Falhou: Intent nula")
            }

            Log.d(tag, "GoogleSignInManager: Processando resultado do login")
            crashlytics.log("Processando resultado do login Google")

            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)

                Log.d(tag, "GoogleSignInManager: Conta Google recuperada: ${account.email}")
                crashlytics.log("Conta Google recuperada com sucesso")
                crashlytics.setCustomKey("google_auth_email_domain", account.email?.substringAfter('@') ?: "unknown")
                crashlytics.setCustomKey("google_auth_display_name", account.displayName != null)
                crashlytics.setCustomKey("google_auth_photo_url", account.photoUrl != null)
                crashlytics.setCustomKey("google_auth_id_token_present", account.idToken != null)

                // Firebase authentication
                firebaseAuthWithGoogle(account)
            } catch (e: ApiException) {
                val statusCode = e.statusCode
                val statusMessage = e.status?.statusMessage ?: "Sem mensagem de status"

                Log.e(tag, "GoogleSignInManager: Login Google falhou - Código: $statusCode", e)
                Log.e(tag, "GoogleSignInManager: Status message: $statusMessage")

                // Record detailed error data to Crashlytics
                crashlytics.log("Falha no login Google - ApiException")
                crashlytics.setCustomKey("api_error_status_code", statusCode)
                crashlytics.setCustomKey("api_error_status_message", statusMessage)
                crashlytics.setCustomKey("api_error_has_resolution", e.status?.hasResolution() ?: false)
                crashlytics.setCustomKey("google_play_services_available", isGooglePlayServicesAvailable())
                crashlytics.setCustomKey("network_available", isNetworkAvailable())

                // Registrar como exceção FATAL para aparecer como crash no dashboard
                throw RuntimeException("GoogleSignIn falhou: Código=$statusCode", e)

                // Exibir Toast com código de status
                withContext(Dispatchers.Main) {
                    val errorMsg = getErrorMessageByStatusCode(statusCode)
                    Toast.makeText(
                        context,
                        "Login Google falhou: $statusCode - $errorMsg",
                        Toast.LENGTH_LONG
                    ).show()

                    // Para erros de configuração, mostrar um toast adicional
                    if (statusCode == 10) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            Toast.makeText(
                                context,
                                "Erro de configuração: Verifique SHA-1 e SHA-256 no Firebase",
                                Toast.LENGTH_LONG
                            ).show()
                        }, 3000) // Mostrar um segundo toast após 3 segundos
                    }
                }

                val errorMsg = getErrorMessageByStatusCode(statusCode)
                SignInResult.Error(errorMsg)
            } catch (e: Exception) {
                Log.e(tag, "GoogleSignInManager: Erro inesperado durante login Google", e)
                crashlytics.log("Erro inesperado no login Google")
                crashlytics.setCustomKey("exception_type", e.javaClass.simpleName)
                crashlytics.setCustomKey("network_available", isNetworkAvailable())

                // Registrar como exceção FATAL
                throw RuntimeException("Erro inesperado no GoogleSignIn", e)

                // Mostrar Toast com detalhes
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Erro inesperado: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }

                SignInResult.Error("Erro inesperado: ${e.localizedMessage}")
            }
        } catch (e: Exception) {
            // Captura falhas no próprio método handleSignInResult
            Log.e(tag, "GoogleSignInManager: Falha crítica no handleSignInResult", e)
            // Gravar como exceção FATAL
            crashlytics.recordException(e)
            throw RuntimeException("Falha crítica no processamento do login Google", e)

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Falha crítica: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }

            SignInResult.Error("Falha crítica: ${e.localizedMessage}")
        }
    }

    private suspend fun firebaseAuthWithGoogle(account: GoogleSignInAccount): SignInResult {
        return try {
            Log.d(tag, "GoogleSignInManager: Autenticando com Firebase")
            crashlytics.log("Autenticando com Firebase usando credencial Google")
            val idToken = account.idToken

            if (idToken == null) {
                Log.e(tag, "GoogleSignInManager: ID Token é nulo!")
                crashlytics.log("Token ID Google é nulo")

                // Registrar como FATAL para o dashboard
                throw RuntimeException("Token ID Google nulo para ${account.email}")

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Erro: Token de autenticação ausente",
                        Toast.LENGTH_LONG
                    ).show()
                }

                return SignInResult.Error("Token de autenticação ausente")
            }

            Log.d(tag, "GoogleSignInManager: Token ID recuperado, tamanho: ${idToken.length}")
            crashlytics.log("Token ID Google obtido com sucesso")
            crashlytics.setCustomKey("token_length", idToken.length)

            val credential = GoogleAuthProvider.getCredential(idToken, null)

            crashlytics.log("Iniciando signInWithCredential")
            Log.d(tag, "GoogleSignInManager: Iniciando signInWithCredential")
            val authResult = auth.signInWithCredential(credential).await()

            val user = authResult.user
            if (user != null) {
                Log.d(tag, "GoogleSignInManager: Autenticação Firebase bem-sucedida: ${user.uid}")
                crashlytics.log("Autenticação Firebase bem-sucedida")
                crashlytics.setUserId(user.uid)
                crashlytics.setCustomKey("is_new_user", authResult.additionalUserInfo?.isNewUser ?: false)
                SignInResult.Success(user)
            } else {
                Log.e(tag, "GoogleSignInManager: Autenticação Firebase falhou: Usuário é nulo")
                crashlytics.log("Autenticação Firebase falhou: usuário é nulo")

                // Registrar como FATAL para o dashboard
                throw RuntimeException("Autenticação Firebase retornou usuário nulo")

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Falha na autenticação: Usuário nulo",
                        Toast.LENGTH_LONG
                    ).show()
                }

                SignInResult.Error("Falha na autenticação")
            }
        } catch (e: Exception) {
            Log.e(tag, "GoogleSignInManager: Falha na autenticação Firebase", e)
            crashlytics.log("Falha na autenticação Firebase")
            crashlytics.setCustomKey("auth_exception_type", e.javaClass.simpleName)

            // Registrar código de erro específico se for FirebaseAuthException
            if (e is com.google.firebase.auth.FirebaseAuthException) {
                crashlytics.setCustomKey("firebase_auth_error_code", e.errorCode)
            }

            // Registrar como FATAL para o dashboard
            throw RuntimeException("Falha na autenticação Firebase", e)

            // Exibir Toast com mensagem de erro
            withContext(Dispatchers.Main) {
                val errorMessage = when {
                    e is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException ->
                        "Credencial Google inválida. Tente novamente."
                    e is com.google.firebase.FirebaseNetworkException ->
                        "Erro de rede ao conectar com Firebase. Verifique sua conexão."
                    e is com.google.firebase.auth.FirebaseAuthUserCollisionException ->
                        "Esta conta Google já está vinculada a outro usuário."
                    else -> "Falha na autenticação: ${e.localizedMessage}"
                }

                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
            }

            val errorMessage = when {
                e is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException ->
                    "Credencial Google inválida. Tente novamente."
                e is com.google.firebase.FirebaseNetworkException ->
                    "Erro de rede ao conectar com Firebase. Verifique sua conexão."
                e is com.google.firebase.auth.FirebaseAuthUserCollisionException ->
                    "Esta conta Google já está vinculada a outro usuário."
                else -> "Falha na autenticação: ${e.localizedMessage}"
            }

            SignInResult.Error(errorMessage)
        }
    }

    fun signOut() {
        Log.d(tag, "GoogleSignInManager: Realizando logout")
        crashlytics.log("Realizando logout do Google e Firebase")
        try {
            googleSignInClient.signOut()
            auth.signOut()
            crashlytics.log("Logout realizado com sucesso")
            crashlytics.setUserId("")  // Limpar ID do usuário no Crashlytics
            Log.d(tag, "GoogleSignInManager: Logout completo")
        } catch (e: Exception) {
            crashlytics.log("Erro durante o logout")
            crashlytics.recordException(e)
            Log.e(tag, "GoogleSignInManager: Erro durante logout", e)
            throw RuntimeException("Falha durante logout do Google", e)
        }
    }

    // Cliente lazy para operações como signOut
    private val googleSignInClient: GoogleSignInClient by lazy {
        val webClientId = context.getString(R.string.default_web_client_id)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(webClientId)
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    // Método para traduzir códigos de erro em mensagens amigáveis
    private fun getErrorMessageByStatusCode(statusCode: Int): String {
        return when(statusCode) {
            7 -> "Rede indisponível. Verifique sua conexão."
            10 -> "O app não está configurado corretamente."
            12500 -> "Erro na configuração do Play Services. Tente atualizar o app."
            12501 -> "Erro ao conectar com o Google. Tente novamente."
            12502 -> "Login cancelado pelo usuário."
            16 -> "Erro no servidor do Google."
            8 -> "Erro interno do Google Play Services."
            15 -> "Timeout de conexão com Google."
            5 -> "Operação cancelada pelo cliente."
            13 -> "Erro na conexão com Google Play Services."
            14 -> "Serviço do Google desativado."
            4 -> "Operação interrompida."
            else -> "Erro desconhecido no login com Google: $statusCode"
        }
    }

    // Função para diagnóstico e testes
    fun testCrashlyticsErrorReporting() {
        Log.d(tag, "GoogleSignInManager: Testando relatório de erro")
        crashlytics.log("Teste de relatório de erro forçado")
        crashlytics.setCustomKey("test_forced_error", true)
        throw RuntimeException("Teste de captura de erro de autenticação Google")

        // Exibir toast para confirmar
        Toast.makeText(
            context,
            "Teste de erro enviado para Crashlytics",
            Toast.LENGTH_SHORT
        ).show()
    }

    // Métodos auxiliares para Crashlytics
    private fun getAppVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = packageInfo.versionName ?: "unknown"
            Log.d(tag, "GoogleSignInManager: App version: $versionName")
            versionName
        } catch (e: Exception) {
            crashlytics.recordException(e)
            Log.e(tag, "GoogleSignInManager: Falha ao obter versão do app", e)
            "unknown"
        }
    }

    private fun isNetworkAvailable(): Boolean {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                Log.d(tag, "GoogleSignInManager: Rede disponível: $hasInternet")
                return hasInternet
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo ?: return false
                @Suppress("DEPRECATION")
                val isConnected = networkInfo.isConnected
                Log.d(tag, "GoogleSignInManager: Rede disponível (legacy): $isConnected")
                return isConnected
            }
        } catch (e: Exception) {
            crashlytics.recordException(e)
            Log.e(tag, "GoogleSignInManager: Erro ao verificar rede", e)
            throw RuntimeException("Falha ao verificar disponibilidade de rede", e)
        }
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        try {
            val availability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
            val resultCode = availability.isGooglePlayServicesAvailable(context)
            val isAvailable = resultCode == com.google.android.gms.common.ConnectionResult.SUCCESS

            if (!isAvailable) {
                val errorMessage = availability.getErrorString(resultCode)
                Log.e(tag, "GoogleSignInManager: Google Play Services não disponível: $errorMessage (código $resultCode)")
                crashlytics.log("Google Play Services não disponível: $errorMessage")
                crashlytics.setCustomKey("play_services_error_code", resultCode)
                crashlytics.setCustomKey("play_services_error_message", errorMessage)
            } else {
                Log.d(tag, "GoogleSignInManager: Google Play Services disponível")
            }

            return isAvailable
        } catch (e: Exception) {
            crashlytics.recordException(e)
            Log.e(tag, "GoogleSignInManager: Erro ao verificar Google Play Services", e)
            throw RuntimeException("Falha ao verificar disponibilidade do Google Play Services", e)
        }
    }

    private fun getGooglePlayServicesVersion(): Int {
        try {
            val version = com.google.android.gms.common.GoogleApiAvailability.GOOGLE_PLAY_SERVICES_VERSION_CODE
            Log.d(tag, "GoogleSignInManager: Google Play Services version: $version")
            return version
        } catch (e: Exception) {
            crashlytics.recordException(e)
            Log.e(tag, "GoogleSignInManager: Erro ao obter versão do Google Play Services", e)
            throw RuntimeException("Falha ao obter versão do Google Play Services", e)
        }
    }

    sealed class SignInResult {
        data class Success(val user: com.google.firebase.auth.FirebaseUser) : SignInResult()
        data class Error(val message: String) : SignInResult()
    }
}