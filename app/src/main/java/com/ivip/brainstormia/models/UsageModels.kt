// ================================================================
// 📁 app/src/main/java/com/ivip/brainstormia/models/UsageModels.kt
// CRIAR ESTE ARQUIVO com as classes de dados necessárias
// ================================================================

package com.ivip.brainstormia.models

/**
 * Informações de uso de um modelo específico
 */
data class ModelUsageInfo(
    val modelName: String,
    val current: Int,
    val limit: Int,
    val remaining: Int,
    val resetAt: Long,
    val planType: String? = null,
    val planCategory: String? = null,
    val category: String? = null,
    val displayName: String? = null
)

/**
 * Uso de todos os modelos do usuário
 */
data class AllModelsUsage(
    val usage: Map<String, ModelUsageInfo>,
    val userType: String = "basic",
    val isPremium: Boolean = false,
    val planType: String? = null,
    val planCategory: String? = null,
    val nextReset: Long = 0,
    val summary: UsageSummary? = null
)

/**
 * Resumo de uso
 */
data class UsageSummary(
    val totalModels: Int,
    val availableModels: Int,
    val totalUsage: Int,
    val totalLimit: Int,
    val utilizationPercentage: Int = 0
)

/**
 * Informações de plano
 */
data class PlanInfo(
    val isPremium: Boolean,
    val planType: String,
    val planCategory: String,
    val planDisplayName: String? = null,
    val planDescription: String? = null,
    val expiresAt: String? = null,
    val activatedAt: String? = null,
    val isLifetime: Boolean = false,
    val autoRenewing: Boolean = false
)

/**
 * Resposta da API de informações de plano
 */
data class PlanInfoResponse(
    val success: Boolean,
    val currentPlan: PlanInfo,
    val upgradeOptions: Map<String, PlanUpgradeInfo> = emptyMap(),
    val planHierarchy: List<String> = emptyList()
)

/**
 * Informações de upgrade de plano
 */
data class PlanUpgradeInfo(
    val planName: String,
    val totalIncrease: Int,
    val modelsCount: Int,
    val benefitsByCategory: Map<String, CategoryBenefit> = emptyMap(),
    val recommended: Boolean = false,
    val price: String? = null
)

/**
 * Benefícios por categoria
 */
data class CategoryBenefit(
    val increase: Int,
    val models: List<ModelBenefit>
)

/**
 * Benefício de modelo
 */
data class ModelBenefit(
    val name: String,
    val currentLimit: Int,
    val upgradeLimit: Int,
    val increase: Int
)

/**
 * Resposta de acesso a modelo
 */
data class ModelAccessResponse(
    val success: Boolean,
    val hasAccess: Boolean,
    val message: String? = null,
    val usage: ModelUsageInfo? = null,
    val planInfo: PlanInfo? = null,
    val upgradeOptions: List<UpgradeOption> = emptyList(),
    val recommendation: UpgradeRecommendation? = null
)

/**
 * Opção de upgrade
 */
data class UpgradeOption(
    val plan: String,
    val displayName: String,
    val limit: Int,
    val increase: Int,
    val recommended: Boolean = false,
    val price: String? = null,
    val description: String? = null
)

/**
 * Recomendação de upgrade
 */
data class UpgradeRecommendation(
    val message: String,
    val plan: String,
    val newLimit: Int,
    val benefits: List<String> = emptyList()
)

/**
 * Informações de modelo
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val category: String,
    val limits: ModelLimits,
    val costWeight: Int,
    val isAvailableToUser: Boolean
)

/**
 * Limites de modelo por plano
 */
data class ModelLimits(
    val basic: Int,
    val monthly: Int,
    val annual: Int,
    val lifetime: Int,
    val currentForUser: Int
)

/**
 * Resposta de informações de modelos
 */
data class ModelsInfoResponse(
    val success: Boolean,
    val userPlan: PlanInfo,
    val models: List<ModelInfo>,
    val categories: Map<String, List<ModelInfo>>,
    val planComparison: Map<String, PlanStats>
)

/**
 * Ativação premium
 */
data class PremiumActivationRequest(
    val purchaseToken: String,
    val productId: String,
    val orderId: String,
    val planType: String? = null
)

/**
 * Resposta de ativação premium
 */
data class PremiumActivationResponse(
    val success: Boolean,
    val message: String? = null,
    val error: String? = null,
    val isPremium: Boolean = false,
    val planType: String? = null,
    val planDisplayName: String? = null,
    val planDescription: String? = null,
    val activatedAt: String? = null,
    val expiresAt: String? = null,
    val isLifetime: Boolean = false,
    val orderId: String? = null,
    val productId: String? = null,
    val planBenefits: PlanBenefits? = null
)

/**
 * Benefícios do plano
 */
data class PlanBenefits(
    val totalLimitPerMonth: Int,
    val availableModels: Int,
    val totalModelsInSystem: Int,
    val comparisons: Map<String, PlanComparison>
)

/**
 * Comparação de plano
 */
data class PlanComparison(
    val additionalUses: Int,
    val percentageIncrease: String
)