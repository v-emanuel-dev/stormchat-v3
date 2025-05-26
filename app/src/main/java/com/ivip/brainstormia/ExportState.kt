package com.ivip.brainstormia

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import com.google.api.services.drive.model.File as DriveFile

// Sealed class for export states
sealed class ExportState {
    object Initial : ExportState()
    object Loading : ExportState()
    data class Success(val fileId: String? = null, val fileName: String = "") : ExportState()
    data class Error(val message: String) : ExportState()
}

// ViewModel for export logic
class ExportViewModel(application: Application) : AndroidViewModel(application) {

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Initial)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private var googleDriveApiService: Drive? = null
    private val tag = "ExportViewModel"
    private val context = application.applicationContext
    // Instance of our wrapper DriveService class from the 'services' package
    private val appDriveService = com.ivip.brainstormia.services.DriveService(application)

    fun setupDriveService() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val googleAccount = GoogleSignIn.getLastSignedInAccount(getApplication())
                if (googleAccount == null) {
                    Log.w(tag, context.getString(R.string.export_error_no_google_account))
                    googleDriveApiService = null
                    return@launch
                }
                val email = googleAccount.email
                if (email != null) {
                    // Call setupDriveService on our wrapper, which internally configures its own Google API Drive instance
                    if (appDriveService.setupDriveService(email)) {
                        Log.d(tag, context.getString(R.string.export_log_drive_setup_success))

                        // For direct use in this ViewModel, also initialize googleDriveApiService here
                        val credential = GoogleAccountCredential.usingOAuth2(
                            getApplication(),
                            Collections.singleton(DriveScopes.DRIVE_FILE)
                        )
                        credential.selectedAccount = googleAccount.account
                        googleDriveApiService = Drive.Builder(
                            NetHttpTransport(),
                            GsonFactory.getDefaultInstance(),
                            credential
                        )
                            .setApplicationName(context.getString(R.string.app_name))
                            .build()
                    } else {
                        Log.e(tag, context.getString(R.string.export_error_drive_setup) + " (appDriveService.setupDriveService returned false)")
                        googleDriveApiService = null
                    }
                } else {
                    Log.e(tag, context.getString(R.string.export_error_drive_setup) + " (email from Google account is null)")
                    googleDriveApiService = null
                }
            } catch (e: Exception) {
                Log.e(tag, context.getString(R.string.export_error_drive_setup), e)
                googleDriveApiService = null
            }
        }
    }

    fun resetExportState() {
        _exportState.value = ExportState.Initial
    }

    fun exportConversation(conversationId: Long, title: String, messages: List<ChatMessage>) {
        if (messages.isEmpty()) {
            _exportState.value = ExportState.Error(context.getString(R.string.export_error_no_messages))
            return
        }

        _exportState.value = ExportState.Loading
        // Ensure conversationId is converted to an appropriate type if your string resource expects an Int
        Log.d(tag, context.getString(R.string.export_log_start, conversationId.toInt(), title))

        viewModelScope.launch {
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
                val dateTime = dateFormat.format(Date())
                val sanitizedTitle = sanitizeFileName(title)
                // Use export_file_prefix for manual exports
                val fileName = context.getString(R.string.export_file_prefix, sanitizedTitle, dateTime)
                Log.d(tag, context.getString(R.string.export_log_filename, fileName))

                val fileContent = formatConversationAsText(messages)
                if (fileContent.isBlank()) {
                    _exportState.value = ExportState.Error(context.getString(R.string.export_error_no_content))
                    return@launch
                }

                if (googleDriveApiService != null) {
                    exportToDrive(googleDriveApiService!!, fileName, fileContent)
                } else {
                    Log.w(tag, "Google Drive API Service not ready or null. Attempting to set it up for export.")
                    setupDriveService()
                    kotlinx.coroutines.delay(3000) // Allow some time for async setup

                    if (googleDriveApiService != null) {
                        Log.d(tag, "Google Drive API Service is now ready after setup attempt. Proceeding with Drive export.")
                        exportToDrive(googleDriveApiService!!, fileName, fileContent)
                    } else {
                        Log.w(tag, "Google Drive API Service still not ready. Falling back to local storage.")
                        exportToLocalStorage(fileName, fileContent)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error exporting conversation: ${e.message}", e)
                val errorMessage = e.localizedMessage ?: context.getString(R.string.export_error_unknown)
                _exportState.value = ExportState.Error(
                    context.getString(R.string.export_error_prefix, errorMessage)
                )
            }
        }
    }

    private suspend fun exportToDrive(drive: Drive, fileName: String, content: String) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(tag, context.getString(R.string.export_log_drive_create, fileName))
                // Call the internal method from our appDriveService instance
                val folderId: String? = appDriveService.getFolderIdInternalOnly()

                if (folderId == null) {
                    // Ensure "StormChat" or the correct folder name is used if it's hardcoded here
                    throw IOException(context.getString(R.string.export_error_folder_access, "StormChat"))
                }

                val fileMetadata = DriveFile().apply {
                    name = fileName
                    mimeType = "text/plain"
                    parents = listOf(folderId) // folderId is smart-cast to String after the null check
                }
                val contentStream = ByteArrayContent.fromString("text/plain", content)
                val file = drive.files().create(fileMetadata, contentStream)
                    .setFields("id,webViewLink")
                    .execute()
                Log.d(tag, context.getString(R.string.export_log_drive_created, file.id ?: "N/A"))

                withContext(Dispatchers.Main) {
                    _exportState.value = ExportState.Success(
                        fileId = file.id,
                        fileName = fileName
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, context.getString(R.string.export_error_drive_create) + ": ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _exportState.value = ExportState.Error(context.getString(R.string.export_error_drive_create) + ": " + (e.localizedMessage ?: "Unknown Drive error"))
                }
            }
        }
    }

    private suspend fun exportToLocalStorage(fileName: String, content: String) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(tag, context.getString(R.string.export_log_local_export, fileName))
                val contextApp = getApplication<Application>()

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Save in Documents/StormChat for better organization and user visibility
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/StormChat")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val resolver = contextApp.contentResolver
                val uri: Uri? = resolver.insert(
                    // Use Downloads collection for wider compatibility and user access if Documents isn't ideal
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Downloads.getContentUri("external_primary")
                    } else {
                        MediaStore.Files.getContentUri("external") // Fallback for older versions
                    }
                    , values
                )

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { output: OutputStream ->
                        output.write(content.toByteArray())
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                    Log.d(tag, context.getString(R.string.export_log_local_saved, uri.toString()))
                    withContext(Dispatchers.Main) {
                        _exportState.value = ExportState.Success(fileName = fileName)
                    }
                } else {
                    throw IOException(context.getString(R.string.export_error_local_file))
                }
            } catch (e: Exception) {
                Log.e(tag, "Error exporting to local storage: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _exportState.value = ExportState.Error(context.getString(R.string.export_error_local_file) + ": " + (e.localizedMessage ?: "Unknown local storage error"))
                }
            }
        }
    }

    private fun formatConversationAsText(messages: List<ChatMessage>): String {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val currentDate = dateFormat.format(Date())

        return buildString {
            appendLine(context.getString(R.string.export_conversation_file_header, context.getString(R.string.app_name)))
            appendLine("${context.getString(R.string.export_header_date)} $currentDate")
            appendLine(context.getString(R.string.export_message_count, messages.size))
            appendLine(context.getString(R.string.export_separator))
            appendLine()

            messages.forEachIndexed { index, message ->
                val sender = when (message.sender) {
                    Sender.USER -> context.getString(R.string.export_sender_user)
                    Sender.BOT -> context.getString(R.string.app_name) // Bot is named after the app
                }

                appendLine("[$sender]:")
                appendLine(message.text)

                if (index < messages.size - 1) {
                    appendLine()
                    appendLine(context.getString(R.string.export_message_separator))
                    appendLine()
                }
            }
            val contentResult = toString()
            Log.d(tag, context.getString(R.string.export_log_content_size, contentResult.length))
            contentResult
        }
    }

    // Sanitize file name utility function
    private fun sanitizeFileName(name: String): String {
        // Replace invalid characters for file names with an underscore
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace("\\s+".toRegex(), "_") // Replace multiple spaces with a single underscore
            .take(30) // Limit the length of the sanitized name
            .trim('_') // Remove leading/trailing underscores
            .takeIf { it.isNotEmpty() } ?: context.getString(R.string.export_fallback_title) // Fallback title if empty
    }
}