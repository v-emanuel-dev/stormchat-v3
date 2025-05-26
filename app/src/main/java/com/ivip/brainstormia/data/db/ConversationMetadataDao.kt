package com.ivip.brainstormia.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMetadata(metadata: ConversationMetadataEntity)

    @Query("SELECT * FROM conversation_metadata WHERE conversation_id = :conversationId")
    suspend fun getMetadata(conversationId: Long): ConversationMetadataEntity?

    @Query("SELECT custom_title FROM conversation_metadata WHERE conversation_id = :conversationId")
    suspend fun getCustomTitle(conversationId: Long): String?

    @Query("SELECT * FROM conversation_metadata WHERE user_id = :userId")
    fun getMetadataForUser(userId: String): Flow<List<ConversationMetadataEntity>>

    @Query("DELETE FROM conversation_metadata WHERE conversation_id = :conversationId")
    suspend fun deleteMetadata(conversationId: Long)

    @Query("DELETE FROM conversation_metadata WHERE user_id = :userId")
    suspend fun clearAllMetadataForUser(userId: String)

    @Query("SELECT * FROM conversation_metadata WHERE conversation_id = :conversationId LIMIT 1")
    suspend fun getMetadataForConversationId(conversationId: Long): ConversationMetadataEntity?
}