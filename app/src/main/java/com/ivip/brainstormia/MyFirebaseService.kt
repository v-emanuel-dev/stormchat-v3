package com.ivip.brainstormia

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseService : FirebaseMessagingService() {
    private val TAG = "MyFirebaseService"

    override fun onNewToken(token: String) {
        // Log extendido para ter certeza de que veremos no logcat
        Log.e("FCM_TOKEN_DEBUG", "=====================================")
        Log.e("FCM_TOKEN_DEBUG", "NOVO TOKEN FCM GERADO:")
        Log.e("FCM_TOKEN_DEBUG", token)
        Log.e("FCM_TOKEN_DEBUG", "=====================================")

        // Salvar o token localmente
        val prefs = getSharedPreferences("brainstormia_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "Mensagem recebida de: ${remoteMessage.from}")

        // Verificar se a mensagem contém notificação
        remoteMessage.notification?.let {
            val title = it.title ?: "Brainstormia"
            val body = it.body ?: "Você tem uma nova mensagem"
            Log.d(TAG, "Notificação: $title - $body")

            showNotification(title, body, remoteMessage.data)
        }
    }

    private fun showNotification(title: String, body: String, data: Map<String, String> = emptyMap()) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

            // Adicionar ID da conversa, se presente
            data["conversationId"]?.toLongOrNull()?.let { convId ->
                putExtra("conversation_id", convId)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notificationBuilder = NotificationCompat.Builder(this, BrainstormiaApplication.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        val notificationManager = NotificationManagerCompat.from(this)

        try {
            val notificationId = System.currentTimeMillis().toInt()
            notificationManager.notify(notificationId, notificationBuilder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "Permissão de notificação negada", e)
        }
    }
}