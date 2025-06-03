package com.ivip.brainstormia

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed class AuthState {
    object Initial : AuthState()
    object Loading : AuthState()
    class Success(val user: FirebaseUser) : AuthState()
    class Error(val message: String) : AuthState()
    object PasswordResetSent : AuthState()
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = "googlelogin"
    private val auth = FirebaseAuth.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val crashlytics = FirebaseCrashlytics.getInstance()
    private val billingViewModel = (application as BrainstormiaApplication).billingViewModel

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initial)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _logoutEvent = MutableStateFlow<Boolean>(false)
    val logoutEvent: StateFlow<Boolean> = _logoutEvent.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val _isUpdatingProfilePicture = MutableStateFlow<Boolean>(false)
    val isUpdatingProfilePicture: StateFlow<Boolean> = _isUpdatingProfilePicture.asStateFlow()

    init {
        _currentUser.value = auth.currentUser
        Log.d(tag, "AuthViewModel: Inicializado. Usuário atual: ${auth.currentUser?.email}")

        // Log initial authentication state
        if (auth.currentUser != null) {
            crashlytics.setUserId(auth.currentUser!!.uid)
            crashlytics.log("App iniciado com usuário autenticado: ${auth.currentUser!!.uid}")
            Log.d(tag, "AuthViewModel: App iniciado com usuário autenticado: ${auth.currentUser!!.email} (${auth.currentUser!!.uid})")
        } else {
            crashlytics.log("App iniciado sem autenticação")
            Log.d(tag, "AuthViewModel: App iniciado sem autenticação")
        }

        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = firebaseAuth.currentUser

            if (firebaseAuth.currentUser == null) {
                crashlytics.log("Autenticação perdida/removida")
                crashlytics.setUserId("")
                Log.d(tag, "AuthViewModel: Autenticação perdida/removida")

                if (_authState.value is AuthState.Success) {
                    _authState.value = AuthState.Initial
                }
            } else {
                crashlytics.setUserId(firebaseAuth.currentUser!!.uid)
                crashlytics.log("Estado de autenticação atualizado: usuário ${firebaseAuth.currentUser!!.uid}")
                Log.d(tag, "AuthViewModel: Estado de autenticação atualizado: usuário ${firebaseAuth.currentUser!!.email} (${firebaseAuth.currentUser!!.uid})")

                if (_authState.value is AuthState.Initial || _authState.value is AuthState.Error) {
                    _authState.value = AuthState.Success(firebaseAuth.currentUser!!)
                }
            }
        }
    }

    fun handleFirebaseUser(user: FirebaseUser) {
        Log.d(tag, "AuthViewModel: Firebase user recebido: ${user.email}")
        Log.i("BillingViewModel", "Usuário logado: UID=${user.uid}, Email=${user.email}")
        Log.i("BillingViewModel", "Nome: ${user.displayName}")
        Log.i("BillingViewModel", "Provedores: ${user.providerData.map { it.providerId }}")
        viewModelScope.launch {
            try {
                val tokenResult = user.getIdToken(false).await()
                val jwt = tokenResult?.token
                if (jwt != null) {
                    Log.i("BillingViewModel", "JWT Token: $jwt")
                } else {
                    Log.e("BillingViewModel", "Não foi possível obter o token JWT.")
                }
            } catch (e: Exception) {
                Log.e("BillingViewModel", "Erro ao obter token JWT", e)
            }
        }

        // Adicionar registro para Crashlytics
        crashlytics.setUserId(user.uid)
        crashlytics.setCustomKey("user_email_domain", user.email?.substringAfter('@') ?: "unknown")
        crashlytics.setCustomKey("auth_provider", user.providerData.firstOrNull()?.providerId ?: "unknown")
        crashlytics.setCustomKey("user_has_profile_pic", user.photoUrl != null)
        crashlytics.log("User authenticated successfully: ${user.uid}")

        _currentUser.value = user
        _authState.value = AuthState.Success(user)

        // Notifica BillingViewModel apenas uma vez
        viewModelScope.launch {
            try {
                Log.d(tag, "AuthViewModel: Notificando BillingViewModel sobre novo login")
                billingViewModel.handleUserChanged()

                // Aguarda um momento para garantir que a primeira verificação complete
                delay(2000)

                // Uma segunda verificação opcional após estabilização
                Log.d(tag, "AuthViewModel: Verificação adicional do status premium")
                billingViewModel.checkPremiumStatus()

            } catch (e: Exception) {
                Log.e(tag, "Erro ao notificar BillingViewModel", e)
            }
        }
    }

    fun loginWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            Log.d(tag, "AuthViewModel: Tentando login com email para $email")
            crashlytics.log("Attempting email login")
            crashlytics.setCustomKey("auth_method", "email")
            crashlytics.setCustomKey("email_domain", email.substringAfter('@'))

            try {
                auth.signInWithEmailAndPassword(email, password)
                    .addOnSuccessListener { result ->
                        _currentUser.value = result.user
                        Log.d(tag, "AuthViewModel: Login com email bem-sucedido: ${result.user?.email}")
                        crashlytics.log("Email login successful")
                        result.user?.let {
                            crashlytics.setUserId(it.uid)
                            _authState.value = AuthState.Success(it)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(tag, "AuthViewModel: Login com email falhou para $email", e)
                        crashlytics.log("Email login failed: ${e.message}")
                        crashlytics.setCustomKey("auth_error", e.message ?: "unknown")
                        crashlytics.setCustomKey("auth_error_code", getFirebaseErrorCode(e))

                        _authState.value = AuthState.Error(e.message ?: "Login failed")
                    }
            } catch (e: Exception) {
                Log.e(tag, "AuthViewModel: Exceção de login com email para $email", e)
                crashlytics.log("Email login exception: ${e.message}")
                crashlytics.setCustomKey("auth_error", e.message ?: "unknown")

                _authState.value = AuthState.Error(e.message ?: "Login failed")
            }
        }
    }

    fun registerWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            Log.d(tag, "AuthViewModel: Tentando registro para $email")
            crashlytics.log("Attempting email registration")
            crashlytics.setCustomKey("auth_method", "email_registration")
            crashlytics.setCustomKey("email_domain", email.substringAfter('@'))

            try {
                auth.createUserWithEmailAndPassword(email, password)
                    .addOnSuccessListener { result ->
                        _currentUser.value = result.user
                        Log.d(tag, "AuthViewModel: Registro bem-sucedido: ${result.user?.email}")
                        crashlytics.log("Email registration successful")
                        result.user?.let {
                            crashlytics.setUserId(it.uid)
                            _authState.value = AuthState.Success(it)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(tag, "AuthViewModel: Registro falhou para $email", e)
                        crashlytics.log("Email registration failed: ${e.message}")
                        crashlytics.setCustomKey("auth_error", e.message ?: "unknown")
                        crashlytics.setCustomKey("auth_error_code", getFirebaseErrorCode(e))

                        _authState.value = AuthState.Error(e.message ?: "Registration failed")
                    }
            } catch (e: Exception) {
                Log.e(tag, "AuthViewModel: Exceção de registro para $email", e)
                crashlytics.log("Email registration exception: ${e.message}")
                crashlytics.setCustomKey("auth_error", e.message ?: "unknown")

                _authState.value = AuthState.Error(e.message ?: "Registration failed")
            }
        }
    }

    fun resetPassword(email: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            Log.d(tag, "AuthViewModel: Tentando resetar senha para $email")
            crashlytics.log("Attempting password reset")
            crashlytics.setCustomKey("auth_method", "password_reset")
            crashlytics.setCustomKey("email_domain", email.substringAfter('@'))

            try {
                auth.sendPasswordResetEmail(email)
                    .addOnSuccessListener {
                        Log.d(tag, "AuthViewModel: Email de reset de senha enviado para $email")
                        crashlytics.log("Password reset email sent successfully")
                        _authState.value = AuthState.PasswordResetSent
                    }
                    .addOnFailureListener { e ->
                        Log.e(tag, "AuthViewModel: Reset de senha falhou para $email", e)
                        crashlytics.log("Password reset failed: ${e.message}")
                        crashlytics.setCustomKey("auth_error", e.message ?: "unknown")
                        crashlytics.setCustomKey("auth_error_code", getFirebaseErrorCode(e))

                        _authState.value = AuthState.Error(e.message ?: "Password reset failed")
                    }
            } catch (e: Exception) {
                Log.e(tag, "AuthViewModel: Exceção de reset de senha para $email", e)
                crashlytics.log("Password reset exception: ${e.message}")
                crashlytics.setCustomKey("auth_error", e.message ?: "unknown")

                _authState.value = AuthState.Error(e.message ?: "Password reset failed")
            }
        }
    }

    fun logout() {
        Log.d(tag, "AuthViewModel: Deslogando usuário: ${_currentUser.value?.email}")
        crashlytics.log("User logging out")

        // NOVO: Notificar BillingViewModel antes do logout
        Log.d(tag, "AuthViewModel: Notificando BillingViewModel sobre logout")
        billingViewModel.handleUserChanged()

        auth.signOut()
        crashlytics.setUserId("") // Limpar o ID do usuário no Crashlytics
        _logoutEvent.value = true
        viewModelScope.launch {
            kotlinx.coroutines.delay(100)
            _logoutEvent.value = false
        }
        Log.d(tag, "AuthViewModel: Logout completo")
        crashlytics.log("Logout completed")
    }

    // Métodos para atualização de foto de perfil atualizados
    fun updateProfilePicture(localImageUri: Uri) {
        val user = _currentUser.value
        Log.d(tag, "AuthViewModel: updateProfilePicture: Iniciado com URI: $localImageUri")
        crashlytics.log("Iniciando atualização de foto de perfil")

        if (user == null) {
            Log.w(tag, "AuthViewModel: updateProfilePicture: Utilizador é nulo. Abortando.")
            _userMessage.value = "Utilizador não autenticado para atualizar a foto."
            crashlytics.log("Falha na atualização de foto: usuário nulo")

            return
        }

        Log.d(tag, "AuthViewModel: updateProfilePicture: Utilizador autenticado: ${user.uid} / ${user.email}")
        crashlytics.setCustomKey("profile_update_user", user.uid)
        _isUpdatingProfilePicture.value = true
        _userMessage.value = null

        viewModelScope.launch {
            try {
                val fileName = "profile_pic_${java.util.UUID.randomUUID()}.jpg"
                val storagePath = "profile_images/${user.uid}/$fileName"
                val storageRef = storage.reference.child(storagePath)

                Log.d(tag, "AuthViewModel: updateProfilePicture: Tentando upload para Storage path: $storagePath")
                crashlytics.log("Iniciando upload de foto para $storagePath")

                // Upload
                val uploadTaskSnapshot = storageRef.putFile(localImageUri).await()
                Log.d(tag, "AuthViewModel: updateProfilePicture: Upload concluído. Bytes: ${uploadTaskSnapshot.bytesTransferred}")
                crashlytics.log("Upload concluído: ${uploadTaskSnapshot.bytesTransferred} bytes")
                crashlytics.setCustomKey("profile_pic_size", uploadTaskSnapshot.bytesTransferred)

                // Obter URL de Download
                Log.d(tag, "AuthViewModel: updateProfilePicture: Obtendo URL de download...")
                val downloadUrl = storageRef.downloadUrl.await()
                Log.d(tag, "AuthViewModel: updateProfilePicture: URL de Download obtida: $downloadUrl")
                crashlytics.log("URL de download obtida")

                // Atualizar Perfil Firebase Auth
                val profileUpdates = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                    .setPhotoUri(downloadUrl)
                    .build()
                Log.d(tag, "AuthViewModel: updateProfilePicture: Atualizando perfil do FirebaseUser...")
                crashlytics.log("Atualizando perfil do usuário")
                user.updateProfile(profileUpdates).await()
                Log.d(tag, "AuthViewModel: updateProfilePicture: Perfil do FirebaseUser atualizado com sucesso.")
                crashlytics.log("Perfil atualizado com sucesso")

                // Atualizar StateFlow e Mensagem
                _currentUser.value = auth.currentUser
                _userMessage.value = "Foto de perfil atualizada com sucesso!"
                Log.i(tag, "AuthViewModel: updateProfilePicture: Foto atualizada com sucesso para ${user.email}. Nova URL: ${auth.currentUser?.photoUrl}")

            } catch (e: com.google.firebase.storage.StorageException) {
                // Tratar erros específicos do Firebase Storage
                Log.e(tag, "AuthViewModel: updateProfilePicture: Erro de Storage - Código: ${e.errorCode}, Mensagem: ${e.message}", e)
                crashlytics.setCustomKey("storage_error_code", e.errorCode)
                crashlytics.setCustomKey("storage_http_code", e.httpResultCode)

                var errorMessage = "Erro de Storage: (${e.errorCode}) ${e.httpResultCode} - ${e.message}"
                // Adicionar mais detalhes baseados no errorCode
                when (e.errorCode) {
                    com.google.firebase.storage.StorageException.ERROR_BUCKET_NOT_FOUND ->
                        errorMessage = "Erro: Bucket não encontrado. Verifique a configuração do Firebase."
                    com.google.firebase.storage.StorageException.ERROR_PROJECT_NOT_FOUND ->
                        errorMessage = "Erro: Projeto Firebase não encontrado."
                    com.google.firebase.storage.StorageException.ERROR_QUOTA_EXCEEDED ->
                        errorMessage = "Erro: Quota de armazenamento excedida."
                    com.google.firebase.storage.StorageException.ERROR_NOT_AUTHENTICATED ->
                        errorMessage = "Erro: Não autenticado para esta operação."
                    com.google.firebase.storage.StorageException.ERROR_NOT_AUTHORIZED ->
                        errorMessage = "Erro: Não autorizado. Verifique as Regras de Segurança do Firebase Storage."
                    com.google.firebase.storage.StorageException.ERROR_RETRY_LIMIT_EXCEEDED ->
                        errorMessage = "Erro: Limite de tentativas excedido. Verifique a sua ligação."
                }
                _userMessage.value = errorMessage
                crashlytics.log("Erro no storage: $errorMessage")
            } catch (e: Exception) {
                Log.e(tag, "AuthViewModel: updateProfilePicture: Falha genérica", e)
                crashlytics.recordException(e)

                // Tornar o erro fatal
                throw RuntimeException("Falha genérica na atualização de foto", e)

                crashlytics.log("Erro genérico na atualização de perfil: ${e.message}")
                _userMessage.value = "Erro ao atualizar foto: ${e.message}"
            } finally {
                Log.d(tag, "AuthViewModel: updateProfilePicture: Finalizado.")
                _isUpdatingProfilePicture.value = false
            }
        }
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    // Função auxiliar para obter códigos de erro do Firebase Auth para logging
    private fun getFirebaseErrorCode(e: Exception): String {
        // Se for uma FirebaseAuthException, extrair o código de erro
        return if (e is com.google.firebase.auth.FirebaseAuthException) {
            e.errorCode
        } else {
            "unknown_error_code"
        }
    }
}