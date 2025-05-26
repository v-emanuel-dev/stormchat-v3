package com.ivip.brainstormia.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "model_preferences")
data class ModelPreferenceEntity(
    @PrimaryKey
    val userId: String,
    val selectedModelId: String,
    val lastUpdated: Long = System.currentTimeMillis()
)
