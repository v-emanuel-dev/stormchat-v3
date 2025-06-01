// TokenManager.kt - Gerenciador COMPLETO de tokens seguro
package com.ivip.brainstormia.auth

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException

/**
 * Gerenciador seguro de tokens JWT com criptografia
 */
class TokenManager(private val context: Context) {
    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_NAME = "secure_tokens"
        private const val KEY_JWT_TOKEN = "jwt_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_LAST_REFRESH = "last_refresh"

        // Token expiry margin (renew if expires in 5 minutes)
        private const val TOKEN_EXPIRY_MARGIN = 5 * 60 * 1000L // 5 minutos

        // Maximum token age (force refresh after 50 minutes)
        private const val MAX_TOKEN_AGE = 50 * 60 * 1000L // 50 minutos
    }

    private val masterKey by lazy {
        try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Erro ao criar MasterKey", e)
            throw SecurityException("Não foi possível inicializar criptografia segura", e)
        }
    }

    private val encryptedPrefs by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar EncryptedSharedPreferences", e)
            throw SecurityException("Não foi possível inicializar armazenamento seguro", e)
        }
    }

    /**
     * ✅ FUNÇÃO PRINCIPAL: Obtém token JWT válido (renovando se necessário)
     */
    suspend fun getValidToken(): String? = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // ✅ LOGS BACKEND - INÍCIO DA OPERAÇÃO
        Log.d("backend", "=== TOKEN MANAGER - GET VALID TOKEN ===")
        Log.d("backend", "Timestamp: $startTime")
        Log.d("backend", "Thread: ${Thread.currentThread().name}")
        Log.d("backend", "=======================================")

        try {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                Log.e(TAG, "❌ Usuário não autenticado")
                Log.d("backend", "❌ ERRO: Usuário não autenticado")
                clearTokens()
                return@withContext null
            }

            val currentUserId = currentUser.uid
            val cachedUserId = encryptedPrefs.getString(KEY_USER_ID, null)

            // ✅ LOGS BACKEND - INFORMAÇÕES DO USUÁRIO
            Log.d("backend", "=== INFORMAÇÕES DO USUÁRIO ===")
            Log.d("backend", "Current User ID: \"$currentUserId\"")
            Log.d("backend", "Cached User ID: \"$cachedUserId\"")
            Log.d("backend", "User Email: \"${currentUser.email}\"")
            Log.d("backend", "==============================")

            // Verificar se é o mesmo usuário
            if (cachedUserId != null && cachedUserId != currentUserId) {
                Log.d(TAG, "🔄 Usuário mudou ($cachedUserId → $currentUserId), limpando tokens")
                Log.d("backend", "🔄 USUÁRIO MUDOU: \"$cachedUserId\" → \"$currentUserId\"")
                clearTokens()
            }

            // Verificar se token em cache ainda é válido
            val cachedToken = encryptedPrefs.getString(KEY_JWT_TOKEN, null)
            val tokenExpiry = encryptedPrefs.getLong(KEY_TOKEN_EXPIRY, 0L)
            val lastRefresh = encryptedPrefs.getLong(KEY_LAST_REFRESH, 0L)
            val now = System.currentTimeMillis()

            // ✅ LOGS BACKEND - STATUS DO TOKEN EM CACHE
            Log.d("backend", "=== STATUS TOKEN EM CACHE ===")
            Log.d("backend", "Token existe: ${cachedToken != null}")
            Log.d("backend", "Token length: ${cachedToken?.length ?: 0}")
            Log.d("backend", "Token prefix: \"${cachedToken?.take(20)}...\"")
            Log.d("backend", "Token expiry: $tokenExpiry")
            Log.d("backend", "Last refresh: $lastRefresh")
            Log.d("backend", "Current time: $now")
            Log.d("backend", "Expira em: ${(tokenExpiry - now) / 1000}s")
            Log.d("backend", "Idade token: ${(now - lastRefresh) / 1000}s")
            Log.d("backend", "Margem segurança: ${TOKEN_EXPIRY_MARGIN / 1000}s")
            Log.d("backend", "Max idade: ${MAX_TOKEN_AGE / 1000}s")
            Log.d("backend", "=============================")

            // Condições para usar token em cache
            val tokenStillValid = cachedToken != null &&
                    now < (tokenExpiry - TOKEN_EXPIRY_MARGIN) &&
                    (now - lastRefresh) < MAX_TOKEN_AGE

            // ✅ LOGS BACKEND - DECISÃO DE USAR CACHE OU RENOVAR
            Log.d("backend", "=== DECISÃO TOKEN ===")
            Log.d("backend", "Token ainda válido: $tokenStillValid")
            Log.d("backend", "Expiry check: ${now < (tokenExpiry - TOKEN_EXPIRY_MARGIN)}")
            Log.d("backend", "Age check: ${(now - lastRefresh) < MAX_TOKEN_AGE}")
            Log.d("backend", "Ação: ${if (tokenStillValid) "USAR CACHE" else "RENOVAR"}")
            Log.d("backend", "====================")

            if (tokenStillValid) {
                val remainingTime = (tokenExpiry - now) / 1000
                Log.d(TAG, "🟡 Usando token em cache (expira em ${remainingTime}s)")

                // ✅ LOGS BACKEND - USANDO TOKEN EM CACHE
                Log.d("backend", "🟡 USANDO TOKEN EM CACHE")
                Log.d("backend", "Tempo restante: ${remainingTime}s")
                Log.d("backend", "Token válido para backend")

                val endTime = System.currentTimeMillis()
                Log.d("backend", "Operação getValidToken completada em ${endTime - startTime}ms")

                return@withContext cachedToken
            }

            // Precisa renovar token
            Log.d(TAG, "🔄 Renovando token Firebase...")
            Log.d("backend", "🔄 RENOVANDO TOKEN FIREBASE...")
            Log.d("backend", "Motivo: Token inválido ou expirado")

            val tokenResult = currentUser.getIdToken(true).await() // force refresh
            val newToken = tokenResult.token

            if (newToken != null) {
                // Calcular expiração do token (1 hora padrão do Firebase)
                val expiryTime = now + (60 * 60 * 1000) // 1 hora

                // ✅ LOGS BACKEND - NOVO TOKEN OBTIDO
                Log.d("backend", "=== NOVO TOKEN OBTIDO ===")
                Log.d("backend", "Token length: ${newToken.length}")
                Log.d("backend", "Token prefix: \"${newToken.take(20)}...\"")
                Log.d("backend", "Token suffix: \"...${newToken.takeLast(10)}\"")
                Log.d("backend", "Expiry time: $expiryTime")
                Log.d("backend", "Expira em: ${(expiryTime - now) / 1000}s")
                Log.d("backend", "User ID: \"$currentUserId\"")
                Log.d("backend", "Timestamp: $now")
                Log.d("backend", "=========================")

                // Salvar token criptografado
                val saveSuccess = saveTokenSecurely(newToken, expiryTime, currentUserId, now)

                if (saveSuccess) {
                    Log.d(TAG, "✅ Novo token salvo em cache seguro")
                    Log.d("backend", "✅ TOKEN SALVO COM SUCESSO")
                    Log.d("backend", "Cache atualizado para próximas requisições")

                    val endTime = System.currentTimeMillis()
                    Log.d("backend", "Operação getValidToken completada em ${endTime - startTime}ms")

                    return@withContext newToken
                } else {
                    Log.e(TAG, "❌ Falha ao salvar token, mas retornando mesmo assim")
                    Log.d("backend", "⚠️ FALHA AO SALVAR TOKEN")
                    Log.d("backend", "Token será usado mas não persistido")

                    val endTime = System.currentTimeMillis()
                    Log.d("backend", "Operação getValidToken completada em ${endTime - startTime}ms")

                    return@withContext newToken
                }
            } else {
                Log.e(TAG, "❌ Firebase retornou token nulo")
                Log.d("backend", "❌ FIREBASE RETORNOU TOKEN NULO")
                Log.d("backend", "Erro crítico: Firebase não conseguiu gerar token")
                clearTokens()

                val endTime = System.currentTimeMillis()
                Log.d("backend", "Operação getValidToken falhou em ${endTime - startTime}ms")

                return@withContext null
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao obter token", e)
            Log.d("backend", "❌ ERRO NA OBTENÇÃO DO TOKEN")
            Log.d("backend", "Erro: ${e.message}")
            Log.d("backend", "Tipo: ${e.javaClass.simpleName}")

            val endTime = System.currentTimeMillis()
            Log.d("backend", "Operação getValidToken falhou em ${endTime - startTime}ms")

            return@withContext null
        }
    }

    /**
     * ✅ Salva token de forma segura com validações
     */
    private fun saveTokenSecurely(token: String, expiryTime: Long, userId: String, refreshTime: Long): Boolean {
        return try {
            // ✅ LOGS BACKEND - SALVANDO TOKEN
            Log.d("backend", "=== SALVANDO TOKEN SEGURO ===")
            Log.d("backend", "User ID: \"$userId\"")
            Log.d("backend", "Token length: ${token.length}")
            Log.d("backend", "Expiry time: $expiryTime")
            Log.d("backend", "Refresh time: $refreshTime")
            Log.d("backend", "Validação dados...")

            // Validar dados antes de salvar
            if (token.isBlank() || userId.isBlank() || expiryTime <= System.currentTimeMillis()) {
                Log.e(TAG, "❌ Dados inválidos para salvar token")
                Log.d("backend", "❌ DADOS INVÁLIDOS")
                Log.d("backend", "Token blank: ${token.isBlank()}")
                Log.d("backend", "User blank: ${userId.isBlank()}")
                Log.d("backend", "Expiry invalid: ${expiryTime <= System.currentTimeMillis()}")
                return false
            }

            Log.d("backend", "✅ Dados válidos, salvando...")

            encryptedPrefs.edit()
                .putString(KEY_JWT_TOKEN, token)
                .putLong(KEY_TOKEN_EXPIRY, expiryTime)
                .putString(KEY_USER_ID, userId)
                .putLong(KEY_LAST_REFRESH, refreshTime)
                .apply()

            Log.d(TAG, "✅ Token salvo: expira em ${(expiryTime - System.currentTimeMillis())/1000}s")
            Log.d("backend", "✅ TOKEN SALVO COM SUCESSO")
            Log.d("backend", "Método: EncryptedSharedPreferences")
            Log.d("backend", "Keys salvos: JWT_TOKEN, TOKEN_EXPIRY, USER_ID, LAST_REFRESH")
            Log.d("backend", "=============================")

            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao salvar token", e)
            Log.d("backend", "❌ ERRO AO SALVAR TOKEN")
            Log.d("backend", "Erro: ${e.message}")
            Log.d("backend", "Tipo: ${e.javaClass.simpleName}")
            false
        }
    }


    /**
     * ✅ Verifica se o token atual ainda é válido (sem renovar)
     */
    fun isTokenValid(): Boolean {
        return try {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) return false

            val cachedToken = encryptedPrefs.getString(KEY_JWT_TOKEN, null)
            val tokenExpiry = encryptedPrefs.getLong(KEY_TOKEN_EXPIRY, 0L)
            val cachedUserId = encryptedPrefs.getString(KEY_USER_ID, null)

            val now = System.currentTimeMillis()

            cachedToken != null &&
                    cachedUserId == currentUser.uid &&
                    now < (tokenExpiry - TOKEN_EXPIRY_MARGIN)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar validade do token", e)
            false
        }
    }

    /**
     * ✅ Força renovação do token
     */
    suspend fun forceRefreshToken(): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔄 Forçando renovação do token...")
            Log.d("backend", "=== FORÇANDO RENOVAÇÃO TOKEN ===")
            Log.d("backend", "Timestamp: ${System.currentTimeMillis()}")
            Log.d("backend", "Operação: Force refresh solicitada")
            Log.d("backend", "================================")

            // Limpar cache atual
            clearTokens()

            // Obter novo token
            val newToken = getValidToken()

            Log.d("backend", "=== RESULTADO FORCE REFRESH ===")
            Log.d("backend", "Novo token obtido: ${newToken != null}")
            Log.d("backend", "Token length: ${newToken?.length ?: 0}")
            Log.d("backend", "===============================")

            return@withContext newToken
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao forçar renovação do token", e)
            Log.d("backend", "❌ ERRO no force refresh: ${e.message}")
            return@withContext null
        }
    }

    /**
     * ✅ Limpa tokens em cache (usar no logout)
     */
    fun clearTokens() {
        try {
            Log.d("backend", "=== LIMPANDO TOKENS ===")
            Log.d("backend", "Motivo: Limpeza solicitada")
            Log.d("backend", "Timestamp: ${System.currentTimeMillis()}")

            encryptedPrefs.edit().clear().apply()
            Log.d(TAG, "🧹 Tokens limpos do cache")

            Log.d("backend", "✅ TOKENS LIMPOS")
            Log.d("backend", "Cache completamente limpo")
            Log.d("backend", "======================")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao limpar tokens", e)
            Log.d("backend", "❌ ERRO ao limpar: ${e.message}")
        }
    }

    /**
     * ✅ Obtém informações sobre o token atual (para debug)
     */
    fun getTokenInfo(): TokenInfo? {
        return try {
            val currentUser = FirebaseAuth.getInstance().currentUser
            val cachedToken = encryptedPrefs.getString(KEY_JWT_TOKEN, null)
            val tokenExpiry = encryptedPrefs.getLong(KEY_TOKEN_EXPIRY, 0L)
            val cachedUserId = encryptedPrefs.getString(KEY_USER_ID, null)
            val lastRefresh = encryptedPrefs.getLong(KEY_LAST_REFRESH, 0L)

            if (cachedToken != null) {
                TokenInfo(
                    hasToken = true,
                    isValid = isTokenValid(),
                    expiresAt = tokenExpiry,
                    lastRefreshAt = lastRefresh,
                    userId = cachedUserId,
                    currentUserId = currentUser?.uid,
                    userMatches = cachedUserId == currentUser?.uid
                )
            } else {
                TokenInfo(
                    hasToken = false,
                    isValid = false,
                    expiresAt = 0L,
                    lastRefreshAt = 0L,
                    userId = null,
                    currentUserId = currentUser?.uid,
                    userMatches = false
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter informações do token", e)
            null
        }
    }

    /**
     * ✅ Verifica se precisa renovar o token em breve
     */
    fun needsRefreshSoon(): Boolean {
        return try {
            val tokenExpiry = encryptedPrefs.getLong(KEY_TOKEN_EXPIRY, 0L)
            val now = System.currentTimeMillis()

            // Precisa renovar se expira em menos de 10 minutos
            val needsRefresh = (tokenExpiry - now) < (10 * 60 * 1000)

            if (needsRefresh) {
                Log.d(TAG, "⏰ Token precisa ser renovado em breve (expira em ${(tokenExpiry - now)/1000}s)")
            }

            needsRefresh
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar necessidade de renovação", e)
            true // Em caso de erro, assumir que precisa renovar
        }
    }
}

/**
 * ✅ Data class com informações sobre o token atual
 */
data class TokenInfo(
    val hasToken: Boolean,
    val isValid: Boolean,
    val expiresAt: Long,
    val lastRefreshAt: Long,
    val userId: String?,
    val currentUserId: String?,
    val userMatches: Boolean
) {
    val timeToExpiry: Long
        get() = maxOf(0L, expiresAt - System.currentTimeMillis())

    val timeSinceRefresh: Long
        get() = System.currentTimeMillis() - lastRefreshAt
}