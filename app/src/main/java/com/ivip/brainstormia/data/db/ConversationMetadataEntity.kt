package com.ivip.brainstormia.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_metadata",
    indices = [Index(value = ["conversation_id", "user_id"], unique = true)]
)
data class ConversationMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: Long,

    @ColumnInfo(name = "custom_title")
    val customTitle: String?,

    @ColumnInfo(name = "user_id")
    val userId: String
)