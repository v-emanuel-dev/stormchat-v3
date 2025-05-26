package com.ivip.brainstormia

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.ivip.brainstormia.data.db.AppDatabase
import com.ivip.brainstormia.data.db.ChatMessageEntity
import com.ivip.brainstormia.data.db.ConversationMetadataEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupManager(private val context: Context) {
    private val backupDir = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)}/BrainstormiaBackups"
    private val database = AppDatabase.getDatabase(context)

    suspend fun backupConversations() {
        withContext(Dispatchers.IO) {
            try {
                // Criar diretório se não existir
                val directory = File(backupDir)
                if (!directory.exists()) {
                    directory.mkdirs()
                }

                // Nome do arquivo com data formatada
                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                val formattedDate = dateFormat.format(Date())
                val fileName = "brainstormia_backup_$formattedDate.json"
                val backupFile = File(directory, fileName)

                // Obter todas as conversas e metadados
                val userId = getCurrentUserId()
                val conversations = database.chatDao().getConversationsForUser(userId).first()
                val allMetadata = database.conversationMetadataDao().getMetadataForUser(userId).first()

                Log.d("BackupManager", "Iniciando backup para usuário $userId com ${conversations.size} conversas")

                // Para cada conversa, obter mensagens
                val fullBackup = conversations.map { conversationInfo ->
                    val messages = database.chatDao().getMessagesForConversation(
                        conversationInfo.id, userId
                    ).first()

                    val metadata = allMetadata.find { it.conversationId == conversationInfo.id }

                    ConversationBackup(
                        id = conversationInfo.id,
                        title = metadata?.customTitle ?: "Conversa ${conversationInfo.id}",
                        messages = messages,
                        lastTimestamp = conversationInfo.lastTimestamp,
                        userId = userId
                    )
                }

                // Converter para JSON e salvar
                val gson = Gson()
                val json = gson.toJson(BackupData(conversations = fullBackup))
                backupFile.writeText(json)

                // Manter apenas os 5 backups mais recentes
                cleanupOldBackups()

                Log.i("BackupManager", "Backup concluído com sucesso: ${backupFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("BackupManager", "Erro ao fazer backup: ${e.message}", e)
            }
        }
    }

    private fun cleanupOldBackups() {
        val directory = File(backupDir)
        val files = directory.listFiles()

        if (files != null) {
            val backupFiles = files.filter { it.name.startsWith("brainstormia_backup_") }
            if (backupFiles.isNotEmpty()) {
                backupFiles.sortedByDescending { it.lastModified() }
                    .drop(5)
                    .forEach { it.delete() }
            }
        }
    }

    suspend fun restoreLatestBackup(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val directory = File(backupDir)
                if (!directory.exists()) return@withContext false

                val backupFiles = directory.listFiles()?.filter {
                    it.name.startsWith("brainstormia_backup_") && it.extension == "json"
                }

                val latestBackup = backupFiles?.maxByOrNull { it.lastModified() } ?: return@withContext false

                Log.d("BackupManager", "Tentando restaurar do arquivo ${latestBackup.name}")

                // Ler e processar o arquivo de backup
                val json = latestBackup.readText()
                val gson = Gson()
                val backupData = gson.fromJson(json, BackupData::class.java)

                // Restaurar dados
                val currentUserId = getCurrentUserId()

                Log.d("BackupManager", "Restaurando ${backupData.conversations.size} conversas para usuário $currentUserId")

                backupData.conversations.forEach { conversationBackup ->
                    // Restaurar metadados
                    database.conversationMetadataDao().insertOrUpdateMetadata(
                        ConversationMetadataEntity(
                            conversationId = conversationBackup.id,
                            customTitle = conversationBackup.title,
                            userId = currentUserId
                        )
                    )

                    // Restaurar mensagens
                    conversationBackup.messages.forEach { message ->
                        val messageEntity = ChatMessageEntity(
                            id = 0, // Novo ID será gerado
                            conversationId = conversationBackup.id,
                            text = message.text,
                            sender = message.sender,
                            timestamp = message.timestamp,
                            userId = currentUserId
                        )
                        database.chatDao().insertMessage(messageEntity)
                    }
                }

                Log.i("BackupManager", "Restauração concluída com sucesso!")
                true
            } catch (e: Exception) {
                Log.e("BackupManager", "Erro ao restaurar: ${e.message}", e)
                false
            }
        }
    }

    private fun getCurrentUserId(): String {
        return FirebaseAuth.getInstance().currentUser?.uid ?: "local_user"
    }

    // Classes para serialização
    data class BackupData(val conversations: List<ConversationBackup>)

    data class ConversationBackup(
        val id: Long,
        val title: String,
        val messages: List<ChatMessageEntity>,
        val lastTimestamp: Long,
        val userId: String
    )
}