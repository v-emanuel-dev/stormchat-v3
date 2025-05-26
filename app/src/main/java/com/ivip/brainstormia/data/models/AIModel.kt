package com.ivip.brainstormia.data.models

data class AIModel(
    val id: String,
    val displayName: String,
    val apiEndpoint: String,
    val provider: AIProvider = AIProvider.OPENAI, // Valor padrão
    val isPremium: Boolean = false // NOVO CAMPO
)

enum class AIProvider {
    OPENAI,
    GOOGLE,
    ANTHROPIC
}
