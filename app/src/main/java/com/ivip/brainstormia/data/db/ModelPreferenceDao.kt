package com.ivip.brainstormia.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelPreferenceDao {
    @Query("SELECT * FROM model_preferences WHERE userId = :userId")
    fun getModelPreference(userId: String): Flow<ModelPreferenceEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePreference(preference: ModelPreferenceEntity)
}