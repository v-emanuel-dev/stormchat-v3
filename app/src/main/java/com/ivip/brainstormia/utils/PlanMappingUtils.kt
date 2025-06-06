// ================================================================
// 📱 PlanMappingUtils.kt - Utilitários para Mapear Produtos para Planos
// Crie este arquivo em: app/src/main/java/com/ivip/brainstormia/utils/
// ================================================================

package com.ivip.brainstormia.utils

import android.util.Log

/**
 * Dados de um plano específico
 */
data class PlanInfo(
    val type: String,           // "monthly", "annual", "lifetime"
    val displayName: String,    // Nome para exibição
    val description: String,    // Descrição do plano
    val durationMonths: Int?    // null para lifetime
)

/**
 * Objeto utilitário para mapear produtos do Google Play para planos específicos
 */
object PlanMappingUtils {

    private const val TAG = "PlanMappingUtils"

    // ✅ Mapeamento de Product IDs para Planos
    // APENAS os IDs REAIS configurados no Google Play Console
    private val PLAN_MAPPING = mapOf(
        // ✅ IDs REAIS do Google Play Console
        "mensal" to PlanInfo(
            type = "monthly",
            displayName = "Premium Mensal",
            description = "Plano premium com renovação mensal",
            durationMonths = 1
        ),
        "anual" to PlanInfo(
            type = "annual",
            displayName = "Premium Anual",
            description = "Plano premium anual com 25% de desconto",
            durationMonths = 12
        ),
        "vital" to PlanInfo(
            type = "lifetime",
            displayName = "Premium Vitalício",
            description = "Acesso premium para toda a vida",
            durationMonths = null
        ),

        // Compatibilidade com versões antigas (se houver)
        "vitalicio" to PlanInfo(
            type = "lifetime",
            displayName = "Premium Vitalício",
            description = "Plano vitalício",
            durationMonths = null
        )
    )

    /**
     * Mapeia um productId para informações de plano
     */
    fun mapProductIdToPlan(productId: String): PlanInfo {
        Log.d(TAG, "Mapeando productId: $productId")

        // Busca exata no mapeamento
        PLAN_MAPPING[productId]?.let { planInfo ->
            Log.d(TAG, "✅ Mapeamento encontrado: $productId -> ${planInfo.type}")
            return planInfo
        }

        // Busca por palavras-chave no productId
        val productIdLower = productId.lowercase()

        val detectedPlan = when {
            productIdLower.contains("lifetime") || productIdLower.contains("vitalicio") -> {
                PlanInfo(
                    type = "lifetime",
                    displayName = "Premium Vitalício",
                    description = "Detectado automaticamente como vitalício",
                    durationMonths = null
                )
            }
            productIdLower.contains("annual") || productIdLower.contains("anual") || productIdLower.contains("year") -> {
                PlanInfo(
                    type = "annual",
                    displayName = "Premium Anual",
                    description = "Detectado automaticamente como anual",
                    durationMonths = 12
                )
            }
            productIdLower.contains("monthly") || productIdLower.contains("mensal") || productIdLower.contains("month") -> {
                PlanInfo(
                    type = "monthly",
                    displayName = "Premium Mensal",
                    description = "Detectado automaticamente como mensal",
                    durationMonths = 1
                )
            }
            else -> {
                Log.w(TAG, "⚠️ ProductId não reconhecido: $productId, assumindo mensal")
                PlanInfo(
                    type = "monthly",
                    displayName = "Premium Personalizado",
                    description = "Plano não mapeado, assumindo mensal",
                    durationMonths = 1
                )
            }
        }

        Log.d(TAG, "🔍 Detectado por palavra-chave: $productId -> ${detectedPlan.type}")
        return detectedPlan
    }

    /**
     * Obtém todos os planos disponíveis
     */
    fun getAllAvailablePlans(): List<Pair<String, PlanInfo>> {
        return PLAN_MAPPING.toList()
    }

    /**
     * Verifica se um productId é válido
     */
    fun isValidProductId(productId: String): Boolean {
        return PLAN_MAPPING.containsKey(productId) ||
                productId.lowercase().let { lower ->
                    lower.contains("monthly") || lower.contains("annual") ||
                            lower.contains("lifetime") || lower.contains("mensal") ||
                            lower.contains("anual") || lower.contains("vitalicio")
                }
    }

    /**
     * Obtém o display name para um tipo de plano
     */
    fun getDisplayNameForPlanType(planType: String): String {
        return when (planType) {
            "monthly" -> "Premium Mensal"
            "annual" -> "Premium Anual"
            "lifetime" -> "Premium Vitalício"
            "basic" -> "Plano Básico"
            else -> planType.replaceFirstChar { it.titlecase() }
        }
    }

    /**
     * Obtém descrição amigável para um tipo de plano
     */
    fun getDescriptionForPlanType(planType: String): String {
        return when (planType) {
            "monthly" -> "⭐ Acesso premium com renovação mensal"
            "annual" -> "⭐⭐ Acesso premium anual com desconto"
            "lifetime" -> "👑 Acesso premium vitalício"
            "basic" -> "📝 Acesso limitado aos recursos básicos"
            else -> "Plano personalizado"
        }
    }

    /**
     * Obtém a cor associada a um tipo de plano
     */
    fun getColorForPlanType(planType: String): Long {
        return when (planType) {
            "monthly" -> 0xFF4CAF50   // Verde
            "annual" -> 0xFFFF9800    // Laranja
            "lifetime" -> 0xFFFFD700  // Dourado
            "basic" -> 0xFF757575     // Cinza
            else -> 0xFF1976D2        // Azul padrão
        }
    }

    /**
     * Validação dos dados antes de enviar para o backend
     */
    fun validatePlanData(productId: String, planType: String?): ValidationResult {
        val errors = mutableListOf<String>()

        if (productId.isBlank()) {
            errors.add("ProductId não pode estar vazio")
        }

        if (!isValidProductId(productId)) {
            errors.add("ProductId não reconhecido: $productId")
        }

        planType?.let { type ->
            if (type !in listOf("monthly", "annual", "lifetime")) {
                errors.add("Tipo de plano inválido: $type")
            }
        }

        val detectedPlan = mapProductIdToPlan(productId)
        if (planType != null && planType != detectedPlan.type) {
            errors.add("Inconsistência: productId sugere '${detectedPlan.type}' mas planType é '$planType'")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            detectedPlan = detectedPlan
        )
    }
}

/**
 * Resultado da validação dos dados de plano
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val detectedPlan: PlanInfo
)

/**
 * Extensões úteis para trabalhar com planos
 */
fun String.toPlanType(): String {
    return PlanMappingUtils.mapProductIdToPlan(this).type
}

fun String.toPlanDisplayName(): String {
    return PlanMappingUtils.getDisplayNameForPlanType(this)
}

fun String.toPlanDescription(): String {
    return PlanMappingUtils.getDescriptionForPlanType(this)
}