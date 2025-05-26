package com.ivip.brainstormia

import com.example.brainstormia.ConversationType

data class ChatMessage(val text: String, val sender: Sender)

enum class Sender {
    USER, BOT
}

data class ConversationDisplayItem(
    val id: Long,
    val displayTitle: String,
    val lastTimestamp: Long,
    val conversationType: ConversationType = ConversationType.GENERAL
)