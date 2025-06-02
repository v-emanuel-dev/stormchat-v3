package com.ivip.brainstormia.api

import android.util.Log
import com.ivip.brainstormia.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Resposta da validação premium do backend
 */
data class ValidationResponse(
    val hasAccess: Boolean,
    val subscriptionType: String?,
    val expirationDate: String?,
    val reasons: Map<String, Boolean> = emptyMap(),
    val userId: String? = null,
    val validatedAt: Long = System.currentTimeMillis(),
    val errorCode: Int? = null,
    val errorMessage: String? = null
)

/**
 * Cliente otimizado para comunicação segura com o backend
 */
class ApiClient {
    companion object {
        private const val TAG = "ApiClient"

        // Configurações vindas do BuildConfig (seguro)
        private val BASE_URL = BuildConfig.API_BASE_URL
        private val WEBHOOK_SECRET = BuildConfig.WEBHOOK_SECRET
        private val API_VERSION = BuildConfig.API_VERSION

        // Timeouts
        private const val CONNECT_TIMEOUT = 10L
        private const val READ_TIMEOUT = 15L
        private const val WRITE_TIMEOUT = 10L

        // Headers padrão
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val HEADER_AUTHORIZATION = "Authorization"
        private const val HEADER_USER_AGENT = "User-Agent"
        private const val HEADER_API_VERSION = "X-API-Version"
        private const val HEADER_WEBHOOK_SOURCE = "X-Webhook-Source"

        // Valores dos headers
        private const val CONTENT_TYPE_JSON = "application/json"
        private val USER_AGENT = "BrainstormiaApp/${BuildConfig.VERSION_NAME} Android"
    }

    // Cliente HTTP configurado e otimizado
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().apply {
            // Timeouts
            connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)

            // Retry automático para falhas de rede
            retryOnConnectionFailure(true)

            // Interceptors
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
            }

            // Interceptor customizado para headers padrão
            addInterceptor(DefaultHeadersInterceptor())

            // Cache (opcional)
            // cache(Cache(cacheDir, cacheSize))
        }.build()
    }

    /**
     * Envia compra para o backend processar
     */
    suspend fun setPremiumStatus(
        uid: String,
        purchaseToken: String,
        productId: String,
        planType: String,
        userToken: String,
        orderId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Validações
            require(orderId.isNotBlank()) { "OrderId é obrigatório" }
            require(uid.isNotBlank()) { "UID é obrigatório" }
            require(purchaseToken.isNotBlank()) { "PurchaseToken é obrigatório" }
            require(userToken.isNotBlank()) { "UserToken é obrigatório" }

            Log.i(TAG, "📤 Enviando compra: productId=$productId, orderId=$orderId")

            val payload = JSONObject().apply {
                put("uid", uid)
                put("purchaseToken", purchaseToken)
                put("productId", productId)
                put("planType", planType)
                put("orderId", orderId)
                put("timestamp", System.currentTimeMillis())
            }

            val request = Request.Builder()
                .url("$BASE_URL/api/premium/set-premium")
                .addHeader(HEADER_AUTHORIZATION, "Bearer $userToken")
                .post(payload.toString().toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: "{}"

                if (!response.isSuccessful) {
                    Log.e(TAG, "❌ Erro HTTP ${response.code}: $responseBody")
                    return@withContext false
                }

                val jsonResponse = JSONObject(responseBody)
                val success = jsonResponse.optBoolean("success", false)

                if (success) {
                    Log.i(TAG, "✅ Compra processada com sucesso")
                } else {
                    val error = jsonResponse.optString("error", "Erro desconhecido")
                    Log.e(TAG, "❌ Backend rejeitou: $error")
                }

                return@withContext success
            }
        } catch (e: IOException) {
            Log.e(TAG, "❌ Erro de rede: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "❌ Parâmetros inválidos: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro inesperado: ${e.message}", e)
            false
        }
    }

    /**
     * Valida status premium do usuário
     */
    suspend fun validatePremiumStatus(userToken: String): ValidationResponse = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Validando status premium...")

            val request = Request.Builder()
                .url("$BASE_URL/api/auth/validate-premium-access")
                .addHeader(HEADER_AUTHORIZATION, "Bearer $userToken")
                .post("{}".toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: "{}"

                if (!response.isSuccessful) {
                    Log.e(TAG, "❌ Erro na validação: ${response.code}")
                    return@withContext ValidationResponse(
                        hasAccess = false,
                        subscriptionType = null,
                        expirationDate = null,
                        reasons = mapOf("httpError" to true),
                        errorCode = response.code,
                        errorMessage = "HTTP ${response.code}"
                    )
                }

                val json = JSONObject(responseBody)
                val success = json.optBoolean("success", false)

                if (!success) {
                    val message = json.optString("message", "Acesso negado")
                    return@withContext ValidationResponse(
                        hasAccess = false,
                        subscriptionType = null,
                        expirationDate = null,
                        reasons = mapOf("denied" to true),
                        errorMessage = message
                    )
                }

                // Extrai dados da resposta
                val hasAccess = json.optBoolean("hasAccess", false)
                val subscriptionType = json.optString("subscriptionType").takeIf { it.isNotEmpty() }
                val expirationDate = json.optString("expirationDate").takeIf { it.isNotEmpty() }
                val userId = json.optString("userId").takeIf { it.isNotEmpty() }

                // Extrai reasons se existir
                val reasons = json.optJSONObject("reasons")?.let { reasonsObj ->
                    val map = mutableMapOf<String, Boolean>()
                    reasonsObj.keys().forEach { key ->
                        map[key] = reasonsObj.optBoolean(key, false)
                    }
                    map
                } ?: emptyMap()

                Log.i(TAG, "✅ Validação concluída: hasAccess=$hasAccess, type=$subscriptionType")

                return@withContext ValidationResponse(
                    hasAccess = hasAccess,
                    subscriptionType = subscriptionType,
                    expirationDate = expirationDate,
                    reasons = reasons,
                    userId = userId
                )
            }
        } catch (e: IOException) {
            Log.e(TAG, "❌ Erro de rede: ${e.message}")
            ValidationResponse(
                hasAccess = false,
                subscriptionType = null,
                expirationDate = null,
                reasons = mapOf("networkError" to true),
                errorMessage = e.message
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro inesperado: ${e.message}", e)
            ValidationResponse(
                hasAccess = false,
                subscriptionType = null,
                expirationDate = null,
                reasons = mapOf("unknownError" to true),
                errorMessage = e.message
            )
        }
    }

    /**
     * Envia webhook de pagamento (com HMAC seguro)
     */
    suspend fun sendPaymentWebhook(
        paymentId: String,
        userId: String,
        planType: String,
        paymentProvider: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val nonce = generateNonce()

            // Cria payload
            val payload = JSONObject().apply {
                put("paymentId", paymentId)
                put("userId", userId)
                put("planType", planType)
                put("paymentProvider", paymentProvider)
                put("timestamp", timestamp)
                put("nonce", nonce)
                put("source", "android_app")
                put("version", API_VERSION)
            }

            // Gera assinatura HMAC
            val signature = generateHMACSignature(
                paymentId, userId, planType,
                paymentProvider, timestamp.toString(), nonce
            )
            payload.put("signature", signature)

            Log.d(TAG, "📤 Enviando webhook para pagamento $paymentId")

            val request = Request.Builder()
                .url("$BASE_URL/api/subscription/webhook")
                .addHeader(HEADER_WEBHOOK_SOURCE, "android")
                .addHeader("X-Webhook-Signature", signature)
                .post(payload.toString().toRequestBody(CONTENT_TYPE_JSON.toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val success = response.isSuccessful

                if (success) {
                    Log.i(TAG, "✅ Webhook enviado com sucesso")
                } else {
                    val body = response.body?.string() ?: ""
                    Log.e(TAG, "❌ Erro no webhook: ${response.code} - $body")
                }

                return@withContext success
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao enviar webhook: ${e.message}", e)
            false
        }
    }

    /**
     * Verifica saúde do backend
     */
    suspend fun checkBackendHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/health")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Backend offline: ${e.message}")
            false
        }
    }

    /**
     * Gera assinatura HMAC-SHA256 segura
     */
    private fun generateHMACSignature(vararg data: String): String {
        val message = data.joinToString("|")
        val signingKey = SecretKeySpec(WEBHOOK_SECRET.toByteArray(), "HmacSHA256")

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(signingKey)

        val hash = mac.doFinal(message.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Gera nonce único
     */
    private fun generateNonce(): String {
        val timestamp = System.currentTimeMillis()
        val random = (0..999999).random()
        return "$timestamp-$random"
    }

    /**
     * Interceptor para adicionar headers padrão
     */
    private class DefaultHeadersInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()

            val request = originalRequest.newBuilder()
                .addHeader(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .addHeader(HEADER_USER_AGENT, USER_AGENT)
                .addHeader(HEADER_API_VERSION, API_VERSION)
                .addHeader("X-Platform", "Android")
                .addHeader("X-App-Version", BuildConfig.VERSION_NAME)
                .build()

            val startTime = System.currentTimeMillis()
            val response = chain.proceed(request)
            val duration = System.currentTimeMillis() - startTime

            Log.d("ApiClient", "[${response.code}] ${request.method} ${request.url} (${duration}ms)")

            return response
        }
    }
}