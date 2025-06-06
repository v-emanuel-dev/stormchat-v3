// ================================================================
// 📱 ApiService.kt - Atualizado para Sistema de Planos
// ================================================================

package com.ivip.brainstormia.api

import android.util.Log
import com.ivip.brainstormia.auth.TokenManager
import com.ivip.brainstormia.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.ivip.brainstormia.models.PlanStats

class ApiService(private val tokenManager: TokenManager) {
    private val baseUrl = "https://stormchat-678f9f5a3073.herokuapp.com"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val TAG = "ApiService"

    /**
     * ✅ ATUALIZADO: Verificar e incrementar uso do modelo com suporte a planos
     */
    suspend fun checkAndIncrementModelUsage(modelName: String): Result<ModelAccessResponse> = withContext(Dispatchers.IO) {
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
                // Tentar parsear erro detalhado
                try {
                    val errorJson = JSONObject(responseBody)
                    val upgradeOptions = parseUpgradeOptions(errorJson.optJSONArray("upgradeOptions"))
                    val recommendation = parseUpgradeRecommendation(errorJson.optJSONObject("recommendation"))

                    return@withContext Result.success(
                        ModelAccessResponse(
                            success = false,
                            hasAccess = false,
                            message = errorJson.optString("message", "Limite excedido"),
                            usage = parseUsageInfo(errorJson.optJSONObject("usage"), modelName),
                            upgradeOptions = upgradeOptions,
                            recommendation = recommendation
                        )
                    )
                } catch (e: Exception) {
                    return@withContext Result.failure(IOException("API retornou código: ${response.code}"))
                }
            }

            val jsonResponse = JSONObject(responseBody)
            if (jsonResponse.optBoolean("success", false)) {
                val usage = jsonResponse.optJSONObject("usage")
                val planInfo = jsonResponse.optJSONObject("planInfo")

                return@withContext Result.success(
                    ModelAccessResponse(
                        success = true,
                        hasAccess = true,
                        message = jsonResponse.optString("message"),
                        usage = parseUsageInfo(usage, modelName),
                        planInfo = parsePlanInfo(planInfo)
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

    /**
     * ✅ ATUALIZADO: Obter todos os limites e uso atual com informações de plano
     */
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
                val summary = jsonResponse.optJSONObject("summary")

                val modelUsage = mutableMapOf<String, ModelUsageInfo>()

                usageData.keys().forEach { modelName ->
                    val modelInfo = usageData.getJSONObject(modelName)
                    modelUsage[modelName] = ModelUsageInfo(
                        modelName = modelName,
                        current = modelInfo.optInt("count", 0),
                        limit = modelInfo.optInt("limit", 0),
                        remaining = modelInfo.optInt("remaining", 0),
                        resetAt = modelInfo.optLong("resetAt", 0),
                        planType = data.optString("planType"),
                        planCategory = data.optString("planCategory"),
                        category = modelInfo.optString("category"),
                        displayName = modelInfo.optString("displayName")
                    )
                }

                return@withContext Result.success(
                    AllModelsUsage(
                        usage = modelUsage,
                        userType = data.optString("userType", "basic"),
                        isPremium = data.optBoolean("isPremium", false),
                        planType = data.optString("planType"),
                        planCategory = data.optString("planCategory"),
                        nextReset = data.optLong("nextReset", 0),
                        summary = parseSummary(summary)
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

    /**
     * ✅ NOVO: Obter informações detalhadas do plano atual e opções de upgrade
     */
    suspend fun getPlanInfo(): Result<PlanInfoResponse> = withContext(Dispatchers.IO) {
        try {
            val token = tokenManager.getValidToken() ?: return@withContext Result.failure(
                IOException("Token de autenticação não disponível")
            )

            val request = Request.Builder()
                .url("$baseUrl/api/usage/plan-info")
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
                val currentPlan = parsePlanInfo(jsonResponse.optJSONObject("currentPlan"))
                val upgradeOptions = parseUpgradeOptionsMap(jsonResponse.optJSONObject("upgradeOptions"))
                val planHierarchy = parsePlanHierarchy(jsonResponse.optJSONArray("planHierarchy"))

                return@withContext Result.success(
                    PlanInfoResponse(
                        success = true,
                        currentPlan = currentPlan ?: PlanInfo(false, "basic", "basic"),
                        upgradeOptions = upgradeOptions,
                        planHierarchy = planHierarchy
                    )
                )
            } else {
                return@withContext Result.failure(
                    IOException("Erro na API: ${jsonResponse.optString("error", "Erro desconhecido")}")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter informações de plano", e)
            return@withContext Result.failure(e)
        }
    }

    /**
     * ✅ NOVO: Obter modelos disponíveis com limites por plano
     */
    suspend fun getModelsInfo(): Result<ModelsInfoResponse> = withContext(Dispatchers.IO) {
        try {
            val token = tokenManager.getValidToken() ?: return@withContext Result.failure(
                IOException("Token de autenticação não disponível")
            )

            val request = Request.Builder()
                .url("$baseUrl/api/usage/models")
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
                val userPlan = parsePlanInfo(jsonResponse.optJSONObject("userPlan"))
                val models = parseModelsList(jsonResponse.optJSONArray("models"))
                val categories = parseCategoriesMap(jsonResponse.optJSONObject("categories"))
                val planComparison = parsePlanComparisonMap(jsonResponse.optJSONObject("planComparison"))

                return@withContext Result.success(
                    ModelsInfoResponse(
                        success = true,
                        userPlan = userPlan ?: PlanInfo(false, "basic", "basic"),
                        models = models,
                        categories = categories,
                        planComparison = planComparison
                    )
                )
            } else {
                return@withContext Result.failure(
                    IOException("Erro na API: ${jsonResponse.optString("error", "Erro desconhecido")}")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter informações de modelos", e)
            return@withContext Result.failure(e)
        }
    }

    /**
     * ✅ NOVO: Ativar premium com plano específico
     */
    suspend fun activatePremium(request: PremiumActivationRequest): Result<PremiumActivationResponse> = withContext(Dispatchers.IO) {
        try {
            val token = tokenManager.getValidToken() ?: return@withContext Result.failure(
                IOException("Token de autenticação não disponível")
            )

            val payload = JSONObject().apply {
                put("purchaseToken", request.purchaseToken)
                put("productId", request.productId)
                put("orderId", request.orderId)
                request.planType?.let { put("planType", it) }
            }

            val httpRequest = Request.Builder()
                .url("$baseUrl/api/premium/set-premium")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: "{}"

            val jsonResponse = JSONObject(responseBody)

            if (response.isSuccessful && jsonResponse.optBoolean("success", false)) {
                return@withContext Result.success(
                    PremiumActivationResponse(
                        success = true,
                        message = jsonResponse.optString("message"),
                        isPremium = jsonResponse.optBoolean("isPremium", false),
                        planType = jsonResponse.optString("planType"),
                        planDisplayName = jsonResponse.optString("planDisplayName"),
                        planDescription = jsonResponse.optString("planDescription"),
                        activatedAt = jsonResponse.optString("activatedAt"),
                        expiresAt = jsonResponse.optString("expiresAt"),
                        isLifetime = jsonResponse.optBoolean("isLifetime", false),
                        orderId = jsonResponse.optString("orderId"),
                        productId = jsonResponse.optString("productId"),
                        planBenefits = parsePlanBenefits(jsonResponse.optJSONObject("planBenefits"))
                    )
                )
            } else {
                return@withContext Result.success(
                    PremiumActivationResponse(
                        success = false,
                        error = jsonResponse.optString("error", "Erro na ativação")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ativar premium", e)
            return@withContext Result.failure(e)
        }
    }

    // ================================================================
    // 🛠️ FUNÇÕES AUXILIARES DE PARSING
    // ================================================================

    private fun parseUsageInfo(json: JSONObject?, modelName: String): ModelUsageInfo? {
        return json?.let {
            ModelUsageInfo(
                modelName = it.optString("modelName", modelName),
                current = it.optInt("current", 0),
                limit = it.optInt("limit", 0),
                remaining = it.optInt("remaining", 0),
                resetAt = it.optLong("resetAt", 0),
                planType = it.optString("planType"),
                planCategory = it.optString("planCategory"),
                category = it.optString("category"),
                displayName = it.optString("displayName")
            )
        }
    }

    private fun parsePlanInfo(json: JSONObject?): PlanInfo? {
        return json?.let {
            PlanInfo(
                isPremium = it.optBoolean("isPremium", false),
                planType = it.optString("planType", "basic"),
                planCategory = it.optString("planCategory", "basic"),
                planDisplayName = it.optString("planDisplayName"),
                planDescription = it.optString("planDescription"),
                expiresAt = it.optString("expiresAt"),
                activatedAt = it.optString("activatedAt"),
                isLifetime = it.optBoolean("isLifetime", false),
                autoRenewing = it.optBoolean("autoRenewing", false)
            )
        }
    }

    private fun parseUpgradeOptions(jsonArray: org.json.JSONArray?): List<UpgradeOption> {
        val options = mutableListOf<UpgradeOption>()
        jsonArray?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                options.add(
                    UpgradeOption(
                        plan = item.optString("plan"),
                        displayName = item.optString("displayName"),
                        limit = item.optInt("limit", 0),
                        increase = item.optInt("increase", 0),
                        recommended = item.optBoolean("recommended", false),
                        price = item.optString("price"),
                        description = item.optString("description")
                    )
                )
            }
        }
        return options
    }

    private fun parseUpgradeRecommendation(json: JSONObject?): UpgradeRecommendation? {
        return json?.let {
            UpgradeRecommendation(
                message = it.optString("message", ""),
                plan = it.optString("plan", ""),
                newLimit = it.optInt("newLimit", 0),
                benefits = parseBenefitsList(it.optJSONArray("benefits"))
            )
        }
    }

    private fun parseBenefitsList(jsonArray: org.json.JSONArray?): List<String> {
        val benefits = mutableListOf<String>()
        jsonArray?.let { array ->
            for (i in 0 until array.length()) {
                benefits.add(array.getString(i))
            }
        }
        return benefits
    }

    private fun parseSummary(json: JSONObject?): UsageSummary? {
        return json?.let {
            UsageSummary(
                totalModels = it.optInt("totalModels", 0),
                availableModels = it.optInt("availableModels", 0),
                totalUsage = it.optInt("totalUsage", 0),
                totalLimit = it.optInt("totalLimit", 0),
                utilizationPercentage = it.optInt("utilizationPercentage", 0)
            )
        }
    }

    private fun parseUpgradeOptionsMap(json: JSONObject?): Map<String, PlanUpgradeInfo> {
        val map = mutableMapOf<String, PlanUpgradeInfo>()
        json?.let { obj ->
            obj.keys().forEach { key ->
                val planInfo = obj.getJSONObject(key)
                map[key] = PlanUpgradeInfo(
                    planName = planInfo.optString("planName"),
                    totalIncrease = planInfo.optInt("totalIncrease", 0),
                    modelsCount = planInfo.optInt("modelsCount", 0),
                    benefitsByCategory = parseBenefitsByCategoryMap(planInfo.optJSONObject("benefitsByCategory")),
                    recommended = planInfo.optBoolean("recommended", false),
                    price = planInfo.optString("price")
                )
            }
        }
        return map
    }

    private fun parseBenefitsByCategoryMap(json: JSONObject?): Map<String, CategoryBenefit> {
        val map = mutableMapOf<String, CategoryBenefit>()
        json?.let { obj ->
            obj.keys().forEach { key ->
                val categoryInfo = obj.getJSONObject(key)
                map[key] = CategoryBenefit(
                    increase = categoryInfo.optInt("increase", 0),
                    models = parseModelBenefitsList(categoryInfo.optJSONArray("models"))
                )
            }
        }
        return map
    }

    private fun parseModelBenefitsList(jsonArray: org.json.JSONArray?): List<ModelBenefit> {
        val benefits = mutableListOf<ModelBenefit>()
        jsonArray?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                benefits.add(
                    ModelBenefit(
                        name = item.optString("name"),
                        currentLimit = item.optInt("currentLimit", 0),
                        upgradeLimit = item.optInt("upgradeLimit", 0),
                        increase = item.optInt("increase", 0)
                    )
                )
            }
        }
        return benefits
    }

    private fun parsePlanHierarchy(jsonArray: org.json.JSONArray?): List<String> {
        val hierarchy = mutableListOf<String>()
        jsonArray?.let { array ->
            for (i in 0 until array.length()) {
                hierarchy.add(array.getString(i))
            }
        }
        return hierarchy
    }

    private fun parseModelsList(jsonArray: org.json.JSONArray?): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        jsonArray?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val limits = item.getJSONObject("limits")
                models.add(
                    ModelInfo(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        category = item.optString("category"),
                        limits = ModelLimits(
                            basic = limits.optInt("basic", 0),
                            monthly = limits.optInt("monthly", 0),
                            annual = limits.optInt("annual", 0),
                            lifetime = limits.optInt("lifetime", 0),
                            currentForUser = limits.optInt("currentForUser", 0)
                        ),
                        costWeight = item.optInt("costWeight", 1),
                        isAvailableToUser = item.optBoolean("isAvailableToUser", false)
                    )
                )
            }
        }
        return models
    }

    private fun parseCategoriesMap(json: JSONObject?): Map<String, List<ModelInfo>> {
        val map = mutableMapOf<String, List<ModelInfo>>()
        json?.let { obj ->
            obj.keys().forEach { key ->
                map[key] = parseModelsList(obj.getJSONArray(key))
            }
        }
        return map
    }

    private fun parsePlanComparisonMap(json: JSONObject?): Map<String, com.ivip.brainstormia.models.PlanStats> {
        val map = mutableMapOf<String, com.ivip.brainstormia.models.PlanStats>()
        json?.let { obj ->
            obj.keys().forEach { key ->
                val statsJson = obj.getJSONObject(key)
                val total = statsJson.optInt("total", 0)
                val available = statsJson.optInt("available", 0)
                map[key] = com.ivip.brainstormia.models.PlanStats(
                    limit = total,
                    remaining = available,
                    current = total - available,
                    resetAt = null // This info isn't in the plan comparison JSON
                )
            }
        }
        return map
    }

    private fun parsePlanBenefits(json: JSONObject?): PlanBenefits? {
        return json?.let {
            PlanBenefits(
                totalLimitPerMonth = it.optInt("totalLimitPerMonth", 0),
                availableModels = it.optInt("availableModels", 0),
                totalModelsInSystem = it.optInt("totalModelsInSystem", 0),
                comparisons = parsePlanComparisonMapV2(it.optJSONObject("comparisons"))
            )
        }
    }

    private fun parsePlanComparisonMapV2(json: JSONObject?): Map<String, PlanComparison> {
        val map = mutableMapOf<String, PlanComparison>()
        json?.let { obj ->
            obj.keys().forEach { key ->
                val comparison = obj.getJSONObject(key)
                map[key] = PlanComparison(
                    additionalUses = comparison.optInt("additionalUses", 0),
                    percentageIncrease = comparison.optString("percentageIncrease", "0%")
                )
            }
        }
        return map
    }
}