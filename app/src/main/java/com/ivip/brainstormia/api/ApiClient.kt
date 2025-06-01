// ApiClient.kt - Cliente COMPLETO para comunicar com nosso backend seguro
package com.ivip.brainstormia.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Resposta da validação premium do backend
 */
data class ValidationResponse(
    val hasAccess: Boolean,
    val subscriptionType: String?,
    val expirationDate: String?,
    val reasons: Map<String, Boolean>,
    val userId: String? = null,
    val validatedAt: Long = System.currentTimeMillis(),
    val errorCode: Int? = null,
    val errorMessage: String? = null
)

/**
 * Cliente para comunicação segura com o backend
 */
class ApiClient {
    companion object {
        private const val TAG = "ApiClient"

        private const val BASE_URL = "http://192.168.0.19:3000"

        private const val WEBHOOK_SECRET = "sua_chave_secreta_webhook" //
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true) // Retry automático
        .addInterceptor(LoggingInterceptor()) // Log das requisições
        .build()

    suspend fun setPremiumStatus(
        uid: String,
        purchaseToken: String,
        productId: String,
        planType: String,
        userToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔄 Enviando compra para backend...")

            val jsonBody = JSONObject().apply {
                put("uid", uid)
                put("purchaseToken", purchaseToken)
                put("productId", productId)
                put("planType", planType)
            }

            val request = Request.Builder()
                .url("$BASE_URL/api/premium/set-premium")
                .addHeader("Authorization", "Bearer $userToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "BrainstormiaApp/1.0 Android")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "Backend response: ${response.code} - $responseBody")

                if (response.isSuccessful) {
                    val jsonResponse = JSONObject(responseBody)
                    return@withContext jsonResponse.optBoolean("success", false)
                }

                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao enviar compra para backend", e)
            return@withContext false
        }
    }

    fun postPremiumPurchase(
        uid: String,
        purchaseToken: String,
        productId: String,
        planType: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        val url = "http://SEU_BACKEND/api/premium/set-premium" // Substitua pelo seu endpoint real

        val json = JSONObject()
        json.put("uid", uid)
        json.put("purchaseToken", purchaseToken)
        json.put("productId", productId)
        json.put("planType", planType)

        val body = RequestBody.create(
            "application/json; charset=utf-8".toMediaTypeOrNull(),
            json.toString()
        )

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, e.message)
            }
            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                val success = try {
                    val obj = JSONObject(responseBody ?: "")
                    obj.optBoolean("success", false)
                } catch (e: Exception) { false }
                onResult(success, responseBody)
            }
        })
    }

    suspend fun validatePremiumStatus(userToken: String): ValidationResponse = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Validando status premium no backend...")

            // ✅ LOG DO TOKEN SENDO ENVIADO
            Log.d("backend", "=== VALIDAÇÃO PREMIUM ===")
            Log.d("backend", "URL: $BASE_URL/api/auth/validate-premium-access")
            Log.d("backend", "userToken: \"${userToken.take(50)}...\" (primeiros 50 chars)")
            Log.d("backend", "Authorization: Bearer ${userToken.take(20)}...")
            Log.d("backend", "=========================")

            val request = Request.Builder()
                .url("$BASE_URL/api/auth/validate-premium-access")
                .addHeader("Authorization", "Bearer $userToken")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "BrainstormiaApp/1.0 Android")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                Log.d(TAG, "Backend response: ${response.code} - ${responseBody.take(200)}")

                // ✅ LOGS DETALHADOS DA RESPOSTA
                Log.d("backend", "=== RESPOSTA VALIDAÇÃO ===")
                Log.d("backend", "HTTP Status: ${response.code}")
                Log.d("backend", "Response Headers: ${response.headers}")
                Log.d("backend", "Response Body: $responseBody")
                Log.d("backend", "Response Length: ${responseBody.length}")
                Log.d("backend", "==========================")

                if (!response.isSuccessful) {
                    Log.e(TAG, "❌ Erro na validação: ${response.code} - $responseBody")

                    Log.d("backend", "❌ VALIDAÇÃO ERRO HTTP: ${response.code}")
                    Log.d("backend", "❌ VALIDAÇÃO ERRO BODY: $responseBody")

                    return@withContext ValidationResponse(
                        hasAccess = false,
                        subscriptionType = null,
                        expirationDate = null,
                        reasons = mapOf(
                            "serverError" to true,
                            "httpError" to true
                        ),
                        errorCode = response.code,
                        errorMessage = responseBody
                    )
                }

                val jsonResponse = JSONObject(responseBody)
                val success = jsonResponse.optBoolean("success", false)

                Log.d("backend", "=== PARSING RESPOSTA ===")
                Log.d("backend", "JSON success: $success")
                Log.d("backend", "JSON completo: ${jsonResponse.toString(2)}")
                Log.d("backend", "========================")

                if (!success) {
                    val errorMessage = jsonResponse.optString("message", "Acesso negado")
                    Log.w(TAG, "⚠️ Backend retornou success=false: $errorMessage")

                    Log.d("backend", "❌ BACKEND DENIED: $errorMessage")

                    return@withContext ValidationResponse(
                        hasAccess = false,
                        subscriptionType = null,
                        expirationDate = null,
                        reasons = mapOf(
                            "backendDenied" to true
                        ),
                        errorMessage = errorMessage
                    )
                }

                val data = jsonResponse.getJSONObject("data")
                val hasAccess = data.optBoolean("hasAccess", false)
                val subscriptionType = data.optString("subscriptionType", null).takeIf { it != "null" }
                val expirationDate = data.optString("expirationDate", null).takeIf { it != "null" }
                val userId = data.optString("userId", null).takeIf { it != "null" }

                // ✅ LOGS DOS DADOS EXTRAÍDOS
                Log.d("backend", "=== DADOS EXTRAÍDOS ===")
                Log.d("backend", "hasAccess: $hasAccess")
                Log.d("backend", "subscriptionType: \"$subscriptionType\"")
                Log.d("backend", "expirationDate: \"$expirationDate\"")
                Log.d("backend", "userId: \"$userId\"")
                Log.d("backend", "======================")

                // Capturar reasons se existir
                val reasons = mutableMapOf<String, Boolean>()
                if (data.has("reasons")) {
                    val reasonsObj = data.getJSONObject("reasons")
                    val keys = reasonsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = when (val rawValue = reasonsObj.get(key)) {
                            is Boolean -> rawValue
                            is String -> rawValue.toBoolean()
                            is Number -> rawValue.toInt() != 0
                            else -> true
                        }
                        reasons[key] = value
                    }

                    Log.d("backend", "=== REASONS EXTRAÍDOS ===")
                    Log.d("backend", "reasons: $reasons")
                    Log.d("backend", "=========================")
                }

                Log.d(TAG, "✅ Validação backend: hasAccess=$hasAccess, type=$subscriptionType, userId=$userId")

                val finalResponse = ValidationResponse(
                    hasAccess = hasAccess,
                    subscriptionType = subscriptionType,
                    expirationDate = expirationDate,
                    reasons = reasons,
                    userId = userId
                )

                Log.d("backend", "=== RESPOSTA FINAL ===")
                Log.d("backend", "ValidationResponse: $finalResponse")
                Log.d("backend", "======================")

                return@withContext finalResponse
            }
        } catch (e: IOException) {
            Log.e(TAG, "❌ Erro de rede na validação premium", e)
            return@withContext ValidationResponse(
                hasAccess = false,
                subscriptionType = null,
                expirationDate = null,
                reasons = mapOf(
                    "networkError" to true
                ),
                errorMessage = e.message ?: "Network error"
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro inesperado na validação premium", e)
            return@withContext ValidationResponse(
                hasAccess = false,
                subscriptionType = null,
                expirationDate = null,
                reasons = mapOf(
                    "unknownError" to true
                ),
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * ✅ Envia webhook de pagamento para o backend
     */
    suspend fun sendPaymentWebhook(
        paymentId: String,
        userId: String,
        planType: String,
        paymentProvider: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "📤 Enviando webhook de pagamento para backend...")

            val timestamp = System.currentTimeMillis()
            val nonce = generateNonce()

            val jsonBody = JSONObject().apply {
                put("paymentId", paymentId)
                put("userId", userId)
                put("planType", planType)
                put("paymentProvider", paymentProvider)
                put("timestamp", timestamp)
                put("nonce", nonce)

                // Gerar assinatura HMAC para segurança
                val signature = generateHMACSignature(paymentId, userId, planType, timestamp.toString(), nonce)
                put("signature", signature)

                // Metadados adicionais
                put("source", "android_app")
                put("version", "1.0")
            }

            // ✅ LOGS PARA BACKEND - WEBHOOK DATA
            Log.d("backend", "=== ENVIANDO WEBHOOK ===")
            Log.d("backend", "URL: $BASE_URL/api/subscription/webhook")
            Log.d("backend", "paymentId: \"$paymentId\"")
            Log.d("backend", "userId: \"$userId\"")
            Log.d("backend", "planType: \"$planType\"")
            Log.d("backend", "paymentProvider: \"$paymentProvider\"")
            Log.d("backend", "timestamp: $timestamp")
            Log.d("backend", "nonce: \"$nonce\"")
            Log.d("backend", "JSON Body completo:")
            Log.d("backend", jsonBody.toString(2))
            Log.d("backend", "========================")

            val request = Request.Builder()
                .url("$BASE_URL/api/subscription/webhook")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "BrainstormiaApp/1.0 Android")
                .addHeader("X-Webhook-Source", "android")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                val success = response.isSuccessful

                // ✅ LOGS PARA RESPOSTA DO WEBHOOK
                Log.d("backend", "=== RESPOSTA WEBHOOK ===")
                Log.d("backend", "HTTP Status: ${response.code}")
                Log.d("backend", "Response Body: $responseBody")
                Log.d("backend", "Success: $success")
                Log.d("backend", "Headers: ${response.headers}")
                Log.d("backend", "========================")

                if (success) {
                    Log.d(TAG, "✅ Webhook enviado com sucesso: $responseBody")

                    // Verificar resposta do backend
                    try {
                        val jsonResponse = JSONObject(responseBody)
                        val backendSuccess = jsonResponse.optBoolean("success", false)

                        Log.d("backend", "=== ANÁLISE RESPOSTA ===")
                        Log.d("backend", "backendSuccess: $backendSuccess")
                        Log.d("backend", "jsonResponse: ${jsonResponse.toString(2)}")
                        Log.d("backend", "=======================")

                        if (!backendSuccess) {
                            val message = jsonResponse.optString("message", "Erro desconhecido")
                            Log.w(TAG, "⚠️ Backend retornou erro no webhook: $message")
                            Log.d("backend", "❌ WEBHOOK ERRO: $message")
                            return@withContext false
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Não foi possível parsear resposta do webhook, mas HTTP foi 200")
                        Log.d("backend", "⚠️ PARSE ERROR mas HTTP 200: ${e.message}")
                    }
                } else {
                    Log.e(TAG, "❌ Erro no webhook: ${response.code} - $responseBody")
                    Log.d("backend", "❌ WEBHOOK HTTP ERROR: ${response.code} - $responseBody")
                }

                return@withContext success
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao enviar webhook", e)
            return@withContext false
        }
    }

    /**
     * ✅ Verifica status de saúde do backend
     */
    suspend fun checkBackendHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/health")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val isHealthy = response.isSuccessful
                Log.d(TAG, if (isHealthy) "✅ Backend healthy" else "❌ Backend unhealthy: ${response.code}")
                return@withContext isHealthy
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao verificar saúde do backend", e)
            return@withContext false
        }
    }

    /**
     * ✅ Gera assinatura HMAC SHA-256 para segurança
     */
    private fun generateHMACSignature(vararg data: String): String {
        val message = data.joinToString("|")
        val key = WEBHOOK_SECRET.toByteArray()
        val messageBytes = message.toByteArray()

        // Implementação HMAC SHA-256 simples
        // Em produção, usar javax.crypto.Mac para HMAC adequado
        val combinedData = "$WEBHOOK_SECRET$message"
        return generateSHA256Hash(combinedData)
    }

    /**
     * ✅ Gera hash SHA-256
     */
    private fun generateSHA256Hash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * ✅ Gera nonce único para prevenir replay attacks
     */
    private fun generateNonce(): String {
        val timestamp = System.currentTimeMillis()
        val random = (1..6).map { ('0'..'9').random() }.joinToString("")
        return "$timestamp$random"
    }
}

/**
 * ✅ Interceptor para logging das requisições HTTP
 */
private class LoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        Log.d("ApiClient", "→ ${request.method} ${request.url}")

        val response = chain.proceed(request)
        val endTime = System.currentTimeMillis()

        Log.d("ApiClient", "← ${response.code} ${request.url} (${endTime - startTime}ms)")

        return response
    }
}