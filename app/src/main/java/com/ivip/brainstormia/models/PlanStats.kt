package com.ivip.brainstormia.models

data class PlanStats(
    val current: Int,
    val limit: Int,
    val remaining: Int,
    val resetAt: String? = null
)
