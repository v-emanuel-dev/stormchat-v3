// ApiService.kt atualizado para limite e incremento por modelo
package com.ivip.brainstormia.api

import android.util.Log
import com.ivip.brainstormia.auth.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiService(private val tokenManager: TokenManager) {
    private val baseUrl = "https://stormchat-678f9f5a3073.herokuapp.com"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val TAG = "ApiService"

    // NOVO: Verificar e INCREMENTAR uso do modelo (ENDPOINT CORRETO)
    suspend fun checkAndIncrementModelUsage(modelName: String): Result<ModelUsageInfo> = withContext(Dispatchers.IO) {
        try {
            val token = tokenManager.getValidToken() ?: return@withContext Result.failure(
                IOException("Token de autenticação não disponível")
            )

            val request = Request.Builder()
                .url("$baseUrl/api/usage/check/$modelName")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("API retornou código: ${response.code}"))
            }

            val jsonResponse = JSONObject(responseBody)
            if (jsonResponse.optBoolean("success", false)) {
                val usage = jsonResponse.getJSONObject("usage")
                return@withContext Result.success(
                    ModelUsageInfo(
                        modelName = modelName,
                        current = usage.optInt("current", usage.optInt("count", 0)),
                        limit = usage.optInt("limit", 0),
                        remaining = usage.optInt("remaining", 0),
                        resetAt = usage.optLong("resetAt", 0)
                    )
                )
            } else {
                val error = jsonResponse.optString("error", "Erro desconhecido")
                return@withContext Result.failure(IOException(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar/incrementar uso para $modelName", e)
            return@withContext Result.failure(e)
        }
    }

    // Obter todos os limites e uso atual
    suspend fun getAllModelsUsage(): Result<AllModelsUsage> = withContext(Dispatchers.IO) {
        try {
            val token = tokenManager.getValidToken() ?: return@withContext Result.failure(
                IOException("Token de autenticação não disponível")
            )

            val request = Request.Builder()
                .url("$baseUrl/api/usage/all")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IOException("API retornou código: ${response.code}")
                )
            }

            val responseBody = response.body?.string() ?: "{}"
            val jsonResponse = JSONObject(responseBody)

            if (jsonResponse.optBoolean("success", false)) {
                val data = jsonResponse.getJSONObject("data")
                val usageData = data.getJSONObject("usage")

                val modelUsage = mutableMapOf<String, ModelUsageInfo>()

                usageData.keys().forEach { modelName ->
                    val modelInfo = usageData.getJSONObject(modelName)
                    modelUsage[modelName] = ModelUsageInfo(
                        modelName = modelName,
                        current = modelInfo.optInt("count", 0),
                        limit = modelInfo.optInt("limit", 0),
                        remaining = modelInfo.optInt("remaining", 0),
                        resetAt = modelInfo.optLong("resetAt", 0)
                    )
                }

                return@withContext Result.success(
                    AllModelsUsage(
                        usage = modelUsage,
                        userType = data.optString("userType", "basic"),
                        isPremium = data.optBoolean("isPremium", false),
                        nextReset = data.optLong("nextReset", 0)
                    )
                )
            } else {
                return@withContext Result.failure(
                    IOException("Erro na API: ${jsonResponse.optString("error", "Erro desconhecido")}")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter todos os limites de uso", e)
            return@withContext Result.failure(e)
        }
    }
}

// Classes de dados

data class ModelUsageInfo(
    val modelName: String,
    val current: Int,
    val limit: Int,
    val remaining: Int,
    val resetAt: Long
)

data class AllModelsUsage(
    val usage: Map<String, ModelUsageInfo>,
    val userType: String,
    val isPremium: Boolean,
    val nextReset: Long
)
