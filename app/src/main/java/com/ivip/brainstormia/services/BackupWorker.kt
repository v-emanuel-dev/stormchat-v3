package com.ivip.brainstormia.services

// Imports corretos para BackupWorker
// Remova qualquer declaração de 'class DriveService' que possa estar aqui por engano.
// O DriveService é importado e usado, não redeclarado.
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.ivip.brainstormia.BrainstormiaApplication
import com.ivip.brainstormia.ChatMessage
import com.ivip.brainstormia.MainActivity
import com.ivip.brainstormia.R
import com.ivip.brainstormia.Sender
import com.ivip.brainstormia.data.db.AppDatabase
import com.ivip.brainstormia.data.db.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A linha 24 (aproximadamente) deve ser o início da declaração da classe BackupWorker,
// e NÃO uma nova declaração de 'class DriveService'.
class BackupWorker(private val appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val TAG = "BackupWorker"
    private val NOTIFICATION_ID = 12345

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "BackupWorker iniciado.")
        createNotificationChannel()

        try {
            val auth = FirebaseAuth.getInstance()
            val currentUser = auth.currentUser
            val userId = currentUser?.uid

            if (userId == null) {
                Log.w(TAG, "Nenhum usuário logado. Backup cancelado.")
                sendNotification(
                    appContext.getString(R.string.backup_failed_title),
                    appContext.getString(R.string.backup_no_user_logged_in)
                )
                return@withContext Result.failure()
            }

            val userEmail = currentUser.email
            if (userEmail == null) {
                Log.w(TAG, "Email do usuário não encontrado para $userId. Backup cancelado.")
                sendNotification(
                    appContext.getString(R.string.backup_failed_title),
                    appContext.getString(R.string.backup_user_email_not_found)
                )
                return@withContext Result.failure()
            }

            Log.d(TAG, "Iniciando backup para usuário: $userId ($userEmail)")

            val database = AppDatabase.getDatabase(appContext)
            val chatDao = database.chatDao()
            val metadataDao = database.conversationMetadataDao()

            // Instanciando o DriveService que está definido em DriveService.kt
            val driveService = DriveService(appContext)

            if (!driveService.setupDriveService(userEmail)) { // Usando '!' para negação
                Log.e(TAG, "DriveService não pôde ser inicializado para $userEmail. Backup falhou.")
                sendNotification(
                    appContext.getString(R.string.backup_failed_title),
                    appContext.getString(R.string.backup_drive_connection_failed)
                )
                return@withContext Result.failure()
            }

            val conversationsInfo = chatDao.getConversationsForUser(userId).firstOrNull()
            if (conversationsInfo.isNullOrEmpty()) {
                Log.i(TAG, "Nenhuma conversa para fazer backup para o usuário $userId.")
                sendNotification(
                    appContext.getString(R.string.backup_complete_title),
                    appContext.getString(R.string.backup_no_new_conversations)
                )
                return@withContext Result.success()
            }

            Log.i(TAG, "Encontradas ${conversationsInfo.size} conversas para backup do usuário $userId.")
            var successfulBackups = 0
            var totalToBackup = 0

            for (convInfo in conversationsInfo) {
                val conversationId = convInfo.id
                val messagesEntity: List<ChatMessageEntity> = chatDao.getMessagesForConversation(conversationId, userId).firstOrNull() ?: emptyList()

                if (messagesEntity.isEmpty()) {
                    Log.i(TAG, "Conversa $conversationId (usuário $userId) está vazia, pulando.")
                    continue
                }
                totalToBackup++

                val messagesUi: List<ChatMessage> = messagesEntity.mapNotNull { entity ->
                    try {
                        ChatMessage(entity.text, Sender.valueOf(entity.sender.uppercase()))
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Sender inválido '${entity.sender}' na mensagem ID ${entity.id} da conversa $conversationId. Pulando mensagem.")
                        null
                    }
                }

                if (messagesUi.isEmpty() && messagesEntity.isNotEmpty()){
                    Log.w(TAG, "Todas as mensagens da conversa $conversationId foram puladas devido a senders inválidos.")
                    continue
                }

                val customTitle = metadataDao.getCustomTitle(conversationId)
                val title = customTitle ?: run {
                    val firstUserMessageText = messagesUi.firstOrNull { it.sender == Sender.USER }?.text
                    if (!firstUserMessageText.isNullOrBlank()) {
                        firstUserMessageText.take(30) + if (firstUserMessageText.length > 30) "..." else ""
                    } else {
                        appContext.getString(R.string.default_conversation_title_prefix) + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(conversationId))
                    }
                }

                val conversationContent = driveService.formatConversationForExport(messagesUi)
                if (conversationContent.isBlank()) {
                    Log.w(TAG, "Conteúdo formatado para a conversa '$title' (ID: $conversationId) está vazio. Pulando.")
                    continue
                }

                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
                val dateTime = dateFormat.format(Date())
                val sanitizedTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(50)
                val fileName = appContext.getString(R.string.export_file_prefix_auto, sanitizedTitle, dateTime)

                var successThisConversation = false
                var attempt = 0
                val maxAttempts = 2

                while (!successThisConversation && attempt < maxAttempts) {
                    attempt++
                    Log.d(TAG, "Tentativa $attempt de backup da conversa '$title' (ID: $conversationId) para o arquivo: $fileName")
                    try {
                        var callbackSuccess = false
                        var callbackError: Exception? = null

                        driveService.exportConversation(
                            title = fileName,
                            content = conversationContent,
                            onSuccess = { fileId, _ ->
                                Log.i(TAG, "Backup da conversa '$title' (ID: $conversationId) realizado com sucesso. File ID: $fileId")
                                callbackSuccess = true
                            },
                            onFailure = { exception ->
                                Log.e(TAG, "Falha na tentativa $attempt de backup da conversa '$title' (ID: $conversationId): ${exception.message}", exception)
                                callbackError = exception
                            }
                        )

                        var waitTime = 0L
                        val maxWaitTime = 10000L
                        val checkInterval = 500L

                        while(!callbackSuccess && callbackError == null && waitTime < maxWaitTime) {
                            kotlinx.coroutines.delay(checkInterval)
                            waitTime += checkInterval
                        }

                        if (callbackSuccess) {
                            successThisConversation = true
                        } else {
                            Log.w(TAG, "Callback para exportConversation da conversa '$title' não indicou sucesso após ${waitTime}ms. Erro: ${callbackError?.message}")
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Exceção durante a chamada de exportConversation na tentativa $attempt para '$title' (ID: $conversationId): ${e.message}", e)
                    }

                    if (!successThisConversation && attempt < maxAttempts) {
                        kotlinx.coroutines.delay(3000L * attempt)
                    }
                }
                if (successThisConversation) successfulBackups++
            }

            val summaryMessage = if (totalToBackup == 0) {
                appContext.getString(R.string.backup_no_conversations_found)
            } else {
                appContext.getString(R.string.backup_summary, successfulBackups, totalToBackup)
            }
            Log.i(TAG, "Backup automático concluído para $userId. $summaryMessage")
            sendNotification(appContext.getString(R.string.backup_complete_title), summaryMessage)
            return@withContext Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Erro fatal durante o BackupWorker para usuário ${FirebaseAuth.getInstance().currentUser?.uid}: ${e.message}", e)
            sendNotification(
                appContext.getString(R.string.backup_failed_title),
                appContext.getString(R.string.backup_unexpected_error)
            )
            return@withContext Result.failure()
        }
    }

    private fun sendNotification(title: String, message: String) {
        val intent = Intent(appContext, MainActivity::class.java)
        intent.apply { // Aplicando flags ao intent
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(appContext, 0, intent, pendingIntentFlags)

        val builder = NotificationCompat.Builder(appContext, BrainstormiaApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(appContext)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notify(NOTIFICATION_ID, builder.build())
                    } else {
                        Log.w(TAG, "Permissão para postar notificações não concedida (API 33+).")
                    }
                } else {
                    notify(NOTIFICATION_ID, builder.build())
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException ao tentar enviar notificação: ${e.message}", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = appContext.getString(R.string.notification_channel_backup_name)
            val descriptionText = appContext.getString(R.string.notification_channel_backup_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(BrainstormiaApplication.CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Canal de notificação para backup verificado/criado.")
        }
    }
}
