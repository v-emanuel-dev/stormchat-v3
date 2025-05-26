package com.ivip.brainstormia.services

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.ivip.brainstormia.ChatMessage
import com.ivip.brainstormia.R
import com.ivip.brainstormia.Sender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class DriveService(private val context: Context) {
    private val TAG = "DriveService"
    private val SCOPES = Collections.singleton(DriveScopes.DRIVE_FILE)
    private val FOLDER_NAME = "StormChat"
    private var folderIdCache: String? = null
    // This is the instance of the Google Drive API service
    private var googleApiDriveService: Drive? = null
    private var currentUserServiceAccount: String? = null

    fun setupDriveService(userAccountEmail: String?): Boolean {
        if (userAccountEmail.isNullOrBlank()) {
            Log.e(TAG, context.getString(R.string.log_drive_setup_attempt_null_email))
            this.googleApiDriveService = null // Use the correct member variable
            currentUserServiceAccount = null
            return false
        }

        // If already configured for the same user, do nothing
        if (this.googleApiDriveService != null && currentUserServiceAccount == userAccountEmail) {
            Log.d(TAG, context.getString(R.string.log_drive_setup_already_configured, userAccountEmail))
            return true
        }

        Log.d(TAG, context.getString(R.string.log_drive_setup_configuring_for_user, userAccountEmail))
        currentUserServiceAccount = userAccountEmail

        try {
            val googleAccount = GoogleSignIn.getLastSignedInAccount(context)
            if (googleAccount == null || googleAccount.email != userAccountEmail) {
                Log.w(TAG, context.getString(R.string.log_drive_setup_google_account_issue, userAccountEmail))
                this.googleApiDriveService = null
                return false
            }

            val credential = GoogleAccountCredential.usingOAuth2(
                context, SCOPES
            ).setSelectedAccount(googleAccount.account)

            this.googleApiDriveService = Drive.Builder( // Assign to the member variable
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName(context.getString(R.string.app_name))
                .build()

            Log.i(TAG, context.getString(R.string.log_drive_setup_success_for_user, userAccountEmail))
            return true
        } catch (e: Exception) {
            Log.e(TAG, context.getString(R.string.log_drive_setup_error_for_user, userAccountEmail, e.message ?: "Unknown error"))
            this.googleApiDriveService = null
            return false
        }
    }

    /**
     * This method is intended for internal use by ViewModels that need the folder ID.
     * It uses the 'googleApiDriveService' instance configured by this DriveService class.
     */
    internal suspend fun getFolderIdInternalOnly(): String? = withContext(Dispatchers.IO) {
        val currentDriveService = this@DriveService.googleApiDriveService // Use the class member
        if (currentDriveService == null) {
            Log.e(TAG, context.getString(R.string.log_drive_folder_id_not_init) + " (getFolderIdInternalOnly)")
            return@withContext null
        }
        if (folderIdCache != null) {
            Log.d(TAG, context.getString(R.string.log_drive_folder_id_cache_used, folderIdCache ?: "N/A"))
            return@withContext folderIdCache
        }

        try {
            val query = "name = '$FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            Log.d(TAG, context.getString(R.string.log_drive_querying_folder, query))
            // Use currentDriveService (which is this.googleApiDriveService)
            val result = currentDriveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()

            if (result.files.isNotEmpty()) {
                val folderId = result.files[0].id
                Log.i(TAG, context.getString(R.string.log_drive_folder_found, FOLDER_NAME, folderId))
                folderIdCache = folderId
                return@withContext folderId
            } else {
                Log.i(TAG, context.getString(R.string.log_drive_folder_not_found_creating, FOLDER_NAME))
                val folderMetadata = File().apply { // com.google.api.services.drive.model.File
                    name = FOLDER_NAME
                    mimeType = "application/vnd.google-apps.folder"
                }
                // Use currentDriveService
                val folder = currentDriveService.files().create(folderMetadata)
                    .setFields("id")
                    .execute()
                val newFolderId = folder.id
                Log.i(TAG, context.getString(R.string.log_drive_folder_created, FOLDER_NAME, newFolderId))
                folderIdCache = newFolderId
                return@withContext newFolderId
            }
        } catch (e: IOException) {
            Log.e(TAG, context.getString(R.string.log_drive_folder_io_error, FOLDER_NAME, e.message ?: "Unknown IO error"))
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, context.getString(R.string.log_drive_folder_general_error, FOLDER_NAME, e.message ?: "Unknown error"))
            return@withContext null
        }
    }

    suspend fun exportConversation(
        title: String,
        content: String,
        onSuccess: (fileId: String, webViewLink: String?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val currentDriveService = this.googleApiDriveService // Use the class member
        if (currentDriveService == null) {
            Log.e(TAG, context.getString(R.string.export_error_drive_not_init_log))
            withContext(Dispatchers.Main) {
                onFailure(IllegalStateException(context.getString(R.string.export_error_drive_not_init)))
            }
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val targetFolderId = getFolderIdInternalOnly() // This now correctly calls the method in this class
                if (targetFolderId == null) {
                    throw IOException(context.getString(R.string.export_error_folder_access, FOLDER_NAME))
                }

                val fileMetadata = File().apply { // com.google.api.services.drive.model.File
                    name = title
                    mimeType = "text/plain"
                    parents = listOf(targetFolderId)
                }
                val mediaContent = ByteArrayContent.fromString("text/plain", content)

                Log.d(TAG, context.getString(R.string.log_drive_exporting_file_to_folder, title, targetFolderId))
                // Use currentDriveService
                val file = currentDriveService.files().create(fileMetadata, mediaContent)
                    .setFields("id, webViewLink")
                    .execute()

                Log.i(TAG, context.getString(R.string.log_drive_file_exported_with_id, title, file.id, file.webViewLink ?: "N/A"))

                withContext(Dispatchers.Main) {
                    onSuccess(file.id, file.webViewLink)
                }
            } catch (e: Exception) {
                Log.e(TAG, context.getString(R.string.log_drive_export_error, title, e.message ?: "Unknown error"))
                withContext(Dispatchers.Main) {
                    onFailure(e)
                }
            }
        }
    }

    fun formatConversationForExport(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        sb.appendLine(context.getString(R.string.export_conversation_file_header, context.getString(R.string.app_name)))
        sb.appendLine("${context.getString(R.string.export_header_date)} ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())}")
        sb.appendLine("-".repeat(40))
        sb.appendLine()

        messages.forEach { message ->
            val senderName = when (message.sender) {
                Sender.USER -> context.getString(R.string.export_sender_user)
                Sender.BOT -> context.getString(R.string.app_name)
            }
            sb.appendLine("[$senderName]:")
            sb.appendLine(message.text)
            sb.appendLine()
        }
        return sb.toString()
    }
}