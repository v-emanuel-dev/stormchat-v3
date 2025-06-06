package com.ivip.brainstormia

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.brainstormia.ConversationType
import com.google.firebase.auth.FirebaseAuth
import com.ivip.brainstormia.api.ApiClient
import com.ivip.brainstormia.api.ApiService
import com.ivip.brainstormia.api.ValidationResponse
import com.ivip.brainstormia.auth.TokenManager
import com.ivip.brainstormia.data.db.AppDatabase
import com.ivip.brainstormia.data.db.ChatDao
import com.ivip.brainstormia.data.db.ChatMessageEntity
import com.ivip.brainstormia.data.db.ConversationInfo
import com.ivip.brainstormia.data.db.ConversationMetadataDao
import com.ivip.brainstormia.data.db.ConversationMetadataEntity
import com.ivip.brainstormia.data.db.ModelPreferenceDao
import com.ivip.brainstormia.data.db.ModelPreferenceEntity
import com.ivip.brainstormia.data.models.AIModel
import com.ivip.brainstormia.data.models.AIProvider
import com.ivip.brainstormia.file.FileProcessingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class LoadingState { IDLE, LOADING, ERROR }

const val NEW_CONVERSATION_ID = -1L
private const val MAX_HISTORY_MESSAGES = 20

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "ChatViewModel"

    // API Clients
    private val openAIClient = OpenAIClient(BuildConfig.OPENAI_API_KEY)
    private val googleAIClient = GoogleAIClient(BuildConfig.GOOGLE_API_KEY)
    private val anthropicClient = AnthropicClient(BuildConfig.ANTHROPIC_API_KEY)

    // ADICIONAR AQUI:
    private val tokenManager = TokenManager(application.applicationContext)
    private val apiService = ApiService(tokenManager)

    private val auth = FirebaseAuth.getInstance()
    private val appDb = AppDatabase.getDatabase(application)
    private val chatDao: ChatDao = appDb.chatDao()
    private val metadataDao: ConversationMetadataDao = appDb.conversationMetadataDao()
    private val modelPreferenceDao: ModelPreferenceDao = appDb.modelPreferenceDao()
    private val context = application.applicationContext
    private val apiClient = ApiClient()

    // Image generation manager
    private val imageGenerationManager = ImageGenerationManager(application)

    // Image generation state flows
    private val _imageGenerationState = MutableStateFlow<ImageGenerationResult?>(null)
    val imageGenerationState: StateFlow<ImageGenerationResult?> =
        _imageGenerationState.asStateFlow()

    private val _generatedImageUri = MutableStateFlow<Uri?>(null)
    val generatedImageUri: StateFlow<Uri?> = _generatedImageUri.asStateFlow()

    private val _isGeneratingImage = MutableStateFlow(false)
    val isGeneratingImage: StateFlow<Boolean> = _isGeneratingImage.asStateFlow()

    private val _imageSavedEvent = MutableSharedFlow<String>() // Emitirá a mensagem de sucesso/erro
    val imageSavedEvent: SharedFlow<String> = _imageSavedEvent.asSharedFlow()

    data class FileAttachment(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val type: String,
        val size: Long,
        val localUri: Uri,
        val filePath: String? = null
    )

    // Estado para mostrar na UI
    private val _currentAttachment = MutableStateFlow<FileAttachment?>(null)
    val currentAttachment: StateFlow<FileAttachment?> = _currentAttachment.asStateFlow()

    // Evento para notificar quando o arquivo foi processado
    private val _fileUploadEvent = MutableSharedFlow<FileAttachment>()
    val fileUploadEvent: SharedFlow<FileAttachment> = _fileUploadEvent.asSharedFlow()

    // List of available models
    val availableModels = listOf(

        // Anthropic
        AIModel(
            id = "claude-opus-4-20250514",
            displayName = "Claude Opus 4",
            apiEndpoint = "claude-opus-4-20250514",
            provider = AIProvider.ANTHROPIC,
            isPremium = true
        ),
        AIModel(
            id = "claude-sonnet-4-20250514",
            displayName = "Claude Sonnet 4",
            apiEndpoint = "claude-sonnet-4-20250514",
            provider = AIProvider.ANTHROPIC,
            isPremium = true
        ),
        AIModel(
            id = "claude-3-7-sonnet-latest",
            displayName = "Claude 3.7 Sonnet",
            apiEndpoint = "claude-3-7-sonnet-latest",
            provider = AIProvider.ANTHROPIC,
            isPremium = true
        ),
        AIModel(
            id = "claude-3-5-sonnet-20241022",
            displayName = "Claude 3.5 Sonnet",
            apiEndpoint = "claude-3-5-sonnet-20241022",
            provider = AIProvider.ANTHROPIC,
            isPremium = true
        ),

        // Google Gemini
        AIModel(
            id = "gemini-2.5-pro-preview-05-06",
            displayName = "Gemini 2.5 Pro",
            apiEndpoint = "gemini-2.5-pro-preview-05-06",
            provider = AIProvider.GOOGLE,
            isPremium = true
        ),
        AIModel(
            id = "gemini-2.5-flash-preview-05-20",
            displayName = "Gemini 2.5 Flash",
            apiEndpoint = "gemini-2.5-flash-preview-05-20",
            provider = AIProvider.GOOGLE,
            isPremium = false
        ),
        AIModel(
            id = "gemini-2.0-flash",
            displayName = "Gemini 2.0 Flash",
            apiEndpoint = "gemini-2.0-flash",
            provider = AIProvider.GOOGLE,
            isPremium = false
        ),

        // OpenAI
        AIModel(
            id = "gpt-4.1",
            displayName = "GPT-4.1",
            apiEndpoint = "gpt-4.1",
            provider = AIProvider.OPENAI,
            isPremium = true
        ),
        AIModel(
            id = "gpt-4.1-mini",
            displayName = "GPT-4.1 Mini",
            apiEndpoint = "gpt-4.1-mini",
            provider = AIProvider.OPENAI,
            isPremium = false
        ),
        AIModel(
            id = "gpt-4o",
            displayName = "GPT-4o",
            apiEndpoint = "gpt-4o",
            provider = AIProvider.OPENAI,
            isPremium = true
        ),
        AIModel(
            id = "gpt-4o-mini",
            displayName = "GPT-4o Mini",
            apiEndpoint = "gpt-4o-mini",
            provider = AIProvider.OPENAI,
            isPremium = false
        ),
        AIModel(
            id = "gpt-4.5-preview",
            displayName = "GPT-4.5 Preview",
            apiEndpoint = "gpt-4.5-preview",
            provider = AIProvider.OPENAI,
            isPremium = true
        ),
        AIModel(
            id = "o1",
            displayName = "GPT o1",
            apiEndpoint = "o1",
            provider = AIProvider.OPENAI,
            isPremium = true
        ),
        AIModel(
            id = "o3",
            displayName = "GPT o3",
            apiEndpoint = "o3",
            provider = AIProvider.OPENAI,
            isPremium = true
        ),
        AIModel(
            id = "o3-mini",
            displayName = "GPT o3 Mini",
            apiEndpoint = "o3-mini",
            provider = AIProvider.OPENAI,
            isPremium = true
        ),
        AIModel(
            id = "o4-mini",
            displayName = "GPT o4 Mini",
            apiEndpoint = "o4-mini",
            provider = AIProvider.OPENAI,
            isPremium = true
        )
    )

    private val defaultModel = AIModel(
        id = "gemini-2.5-flash-preview-05-20",
        displayName = "Gemini 2.5 Flash",
        apiEndpoint = "gemini-2.5-flash-preview-05-20",
        provider = AIProvider.GOOGLE,
        isPremium = false
    )

    private val _selectedModel = MutableStateFlow(defaultModel)
    val selectedModel: StateFlow<AIModel> = _selectedModel

    // Adicione esta propriedade ao ChatViewModel
    private val _isImageGenerating = MutableStateFlow(false)
    val isImageGenerating: StateFlow<Boolean> = _isImageGenerating.asStateFlow()

    // Adicione esta propriedade para armazenar o prompt durante a geração
    private val _currentImagePrompt = MutableStateFlow<String?>(null)
    val currentImagePrompt: StateFlow<String?> = _currentImagePrompt.asStateFlow()

    private val fileProcessingManager =
        FileProcessingManager(getApplication<Application>().applicationContext)

    private val _userPlanType = MutableStateFlow<String?>(null)
    val userPlanType: StateFlow<String?> = _userPlanType.asStateFlow()

    val billingVM = (application as BrainstormiaApplication).billingViewModel

    private val _isUpdatingModel = MutableStateFlow(false)

    private var modelPreferenceJob: Job? = null

    // Backend integration - ADICIONAR ESTAS PROPRIEDADES
    fun checkPremiumStatusWithBackend() {
        val startTime = System.currentTimeMillis()
        val currentUser = FirebaseAuth.getInstance().currentUser

        // ✅ LOGS BACKEND - INÍCIO DA VERIFICAÇÃO
        Log.d("backend", "=== VERIFICAÇÃO PREMIUM BACKEND ===")
        Log.d("backend", "Timestamp: $startTime")
        Log.d("backend", "Thread: ${Thread.currentThread().name}")
        Log.d("backend", "User ID: \"${currentUser?.uid}\"")
        Log.d("backend", "User Email: \"${currentUser?.email}\"")
        Log.d("backend", "Display Name: \"${currentUser?.displayName}\"")
        Log.d("backend", "Is Anonymous: ${currentUser?.isAnonymous}")
        Log.d("backend", "Provider Data: ${currentUser?.providerData?.map { it.providerId }}")
        Log.d("backend", "===================================")

        viewModelScope.launch {
            try {
                Log.d("backend", "🔍 OBTENDO TOKEN JWT...")

                // Obter token JWT do usuário atual
                val userToken = tokenManager.getValidToken()

                if (userToken == null) {
                    Log.e("backend", "❌ TOKEN JWT NULO")
                    Log.d("backend", "Possíveis causas:")
                    Log.d("backend", "- Usuário não autenticado")
                    Log.d("backend", "- Falha na renovação do token")
                    Log.d("backend", "- Erro no Firebase Auth")
                    Log.d("backend", "- Problema de conectividade")

                    _isPremiumUser.value = false

                    val endTime = System.currentTimeMillis()
                    Log.d("backend", "Verificação backend falhou em ${endTime - startTime}ms")
                    return@launch
                }

                // ✅ LOGS BACKEND - TOKEN OBTIDO
                Log.d("backend", "=== TOKEN JWT OBTIDO ===")
                Log.d("backend", "Token length: ${userToken.length}")
                Log.d("backend", "Token prefix: \"${userToken.take(50)}...\"")
                Log.d("backend", "Token válido: ${userToken.isNotEmpty()}")
                Log.d("backend", "Preparando chamada para backend...")
                Log.d("backend", "========================")

                // ✅ LOGS BACKEND - ANTES DA CHAMADA API
                Log.d("backend", "=== CHAMADA PARA BACKEND ===")
                Log.d("backend", "Endpoint: validatePremiumStatus")
                Log.d("backend", "Method: POST")
                Log.d("backend", "Authorization: Bearer ${userToken.take(20)}...")
                Log.d("backend", "Content-Type: application/json")
                Log.d("backend", "User-Agent: BrainstormiaApp/1.0 Android")
                Log.d("backend", "Timeout: 10s")
                Log.d("backend", "============================")

                // Chamar backend para validar
                val validationResponse = apiClient.validatePremiumStatus(userToken)

                val endTime = System.currentTimeMillis()
                val duration = endTime - startTime

                // ✅ LOGS BACKEND - RESPOSTA COMPLETA
                Log.d("backend", "=== RESPOSTA DO BACKEND ===")
                Log.d("backend", "Duração total: ${duration}ms")
                Log.d("backend", "Has Access: ${validationResponse.hasAccess}")
                Log.d("backend", "Subscription Type: \"${validationResponse.subscriptionType}\"")
                Log.d("backend", "Expiration Date: \"${validationResponse.expirationDate}\"")
                Log.d("backend", "User ID: \"${validationResponse.userId}\"")
                Log.d("backend", "Validated At: ${validationResponse.validatedAt}")
                Log.d("backend", "Error Code: ${validationResponse.errorCode}")
                Log.d("backend", "Error Message: \"${validationResponse.errorMessage}\"")
                Log.d("backend", "Reasons: ${validationResponse.reasons}")
                Log.d("backend", "===========================")

                // ✅ LOGS BACKEND - ANÁLISE DA RESPOSTA
                if (validationResponse.hasAccess) {
                    Log.d("backend", "✅ ACESSO PREMIUM CONFIRMADO")
                    Log.d(
                        "backend",
                        "Tipo de assinatura: \"${validationResponse.subscriptionType}\""
                    )
                    Log.d("backend", "Data de expiração: \"${validationResponse.expirationDate}\"")
                } else {
                    Log.d("backend", "❌ ACESSO PREMIUM NEGADO")
                    Log.d("backend", "Código de erro: ${validationResponse.errorCode}")
                    Log.d("backend", "Mensagem de erro: \"${validationResponse.errorMessage}\"")

                    if (validationResponse.reasons.isNotEmpty()) {
                        Log.d("backend", "=== RAZÕES DE NEGAÇÃO ===")
                        validationResponse.reasons.forEach { (reason, value) ->
                            if (value) {
                                Log.d("backend", "- $reason: $value")
                            }
                        }
                        Log.d("backend", "========================")
                    }
                }

                // Atualizar estado baseado na resposta do backend
                withContext(Dispatchers.Main) {
                    // ✅ LOGS BACKEND - ATUALIZANDO ESTADO LOCAL
                    Log.d("backend", "=== ATUALIZANDO ESTADO LOCAL ===")
                    Log.d("backend", "Estado anterior - Premium: ${_isPremiumUser.value}")
                    Log.d("backend", "Estado anterior - Plano: \"${_userPlanType.value}\"")

                    _isPremiumUser.value = validationResponse.hasAccess

                    if (validationResponse.hasAccess) {
                        // Mapear tipo de assinatura do backend para formato local
                        val localPlanType =
                            when (validationResponse.subscriptionType?.lowercase()) {
                                "monthly" -> "Monthly plan"
                                "annual" -> "Annual Plan"
                                "lifetime" -> "Lifetime"
                                else -> validationResponse.subscriptionType
                            }
                        _userPlanType.value = localPlanType

                        Log.d("backend", "✅ ESTADO ATUALIZADO PARA PREMIUM")
                        Log.d("backend", "Novo plano: \"$localPlanType\"")
                        Log.d(
                            "backend",
                            "Mapeamento: \"${validationResponse.subscriptionType}\" → \"$localPlanType\""
                        )

                        Log.i(
                            "ChatViewModel",
                            "✅ Backend confirmou: Premium=true, Plano=$localPlanType"
                        )
                    } else {
                        _userPlanType.value = null

                        Log.d("backend", "❌ ESTADO MANTIDO COMO NÃO PREMIUM")
                        Log.d("backend", "Plano removido: null")

                        Log.i("ChatViewModel", "❌ Backend negou acesso premium")
                    }

                    Log.d("backend", "Estado final - Premium: ${_isPremiumUser.value}")
                    Log.d("backend", "Estado final - Plano: \"${_userPlanType.value}\"")
                    Log.d("backend", "================================")

                    // ✅ LOGS BACKEND - VALIDANDO MODELO ATUAL
                    Log.d("backend", "=== VALIDANDO MODELO ATUAL ===")
                    Log.d("backend", "Modelo atual: \"${_selectedModel.value.displayName}\"")
                    Log.d("backend", "Modelo é premium: ${_selectedModel.value.isPremium}")
                    Log.d("backend", "Usuário tem acesso: ${validationResponse.hasAccess}")

                    // Validar modelo atual baseado no resultado
                    validateCurrentModel(validationResponse.hasAccess)

                    Log.d(
                        "backend",
                        "Modelo pós-validação: \"${_selectedModel.value.displayName}\""
                    )
                    Log.d("backend", "==============================")
                }

                // ✅ LOGS BACKEND - SUCESSO COMPLETO
                Log.d("backend", "=== VERIFICAÇÃO BACKEND COMPLETA ===")
                Log.d("backend", "Status: SUCESSO")
                Log.d("backend", "Duração total: ${duration}ms")
                Log.d("backend", "Premium access: ${validationResponse.hasAccess}")
                Log.d("backend", "Timestamp final: ${System.currentTimeMillis()}")
                Log.d("backend", "===================================")

            } catch (e: Exception) {
                val endTime = System.currentTimeMillis()
                val duration = endTime - startTime

                Log.e("backend", "❌ ERRO NA VERIFICAÇÃO BACKEND: ${e.message}", e)

                // ✅ LOGS BACKEND - DETALHES DO ERRO
                Log.d("backend", "=== ERRO DETALHADO ===")
                Log.d("backend", "Tipo: ${e.javaClass.simpleName}")
                Log.d("backend", "Mensagem: \"${e.message}\"")
                Log.d("backend", "Causa: \"${e.cause?.message}\"")
                Log.d("backend", "Stack trace: ${e.stackTrace.take(3).joinToString()}")
                Log.d("backend", "Duração até erro: ${duration}ms")
                Log.d("backend", "======================")

                withContext(Dispatchers.Main) {
                    // ✅ LOGS BACKEND - FALLBACK
                    Log.d("backend", "=== INICIANDO FALLBACK ===")
                    Log.d("backend", "Motivo: Erro na verificação backend")
                    Log.d("backend", "Fallback: Método local (BillingViewModel)")
                    Log.d("backend", "Timestamp: ${System.currentTimeMillis()}")
                    Log.d("backend", "==========================")

                    // Em caso de erro, usar fallback para método local
                    Log.w("ChatViewModel", "Falha na verificação backend, usando método local")
                    checkIfUserIsPremium() // Fallback para método original
                }
            }
        }
    }

    /**
     * Processa o upload de um arquivo
     */
    fun handleFileUpload(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _loadingState.value = LoadingState.LOADING

                // Obter informações do arquivo
                val context = getApplication<Application>().applicationContext
                val contentResolver = context.contentResolver

                // Obter nome e tipo do arquivo
                var fileName = ""
                var fileType = ""
                var fileSize = 0L

                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)

                    if (cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex)
                        fileSize = cursor.getLong(sizeIndex)

                        // Obter o tipo MIME do arquivo
                        fileType = contentResolver.getType(uri) ?: ""
                        if (fileType.isEmpty()) {
                            // Tentar obter o tipo a partir da extensão
                            val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                            if (extension != null) {
                                fileType =
                                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                                        ?: ""
                            }
                        }
                    }
                }

                // Salvar o arquivo em armazenamento interno para acesso posterior
                val filesDir = File(context.filesDir, "uploads").apply {
                    if (!exists()) mkdir()
                }

                val destinationFile = File(filesDir, fileName)
                val filePath = destinationFile.absolutePath

                contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                // Criar e armazenar o objeto de arquivo
                val fileAttachment = FileAttachment(
                    name = fileName,
                    type = fileType,
                    size = fileSize,
                    localUri = uri,
                    filePath = filePath
                )

                // Atualizar estado e notificar
                _currentAttachment.value = fileAttachment
                _fileUploadEvent.emit(fileAttachment)

                // Mensagem de confirmação para o usuário
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Arquivo \"$fileName\" carregado com sucesso!"

                    // Depois de um tempo, limpar a mensagem
                    delay(3000)
                    _errorMessage.value = null
                }

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error handling file upload", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Erro ao processar arquivo: ${e.localizedMessage}"
                }
            } finally {
                _loadingState.value = LoadingState.IDLE
            }
        }
    }

    fun sendMessageWithAttachment(userMessageText: String, attachment: FileAttachment) {
        if (_loadingState.value == LoadingState.LOADING) {
            Log.w("ChatViewModel", "sendMessageWithAttachment cancelled: Already loading.")
            _errorMessage.value = context.getString(R.string.error_wait_previous)
            return
        }

        // VERIFICAÇÃO DE LIMITES ANTES DE PROCESSAR MENSAGEM COM ANEXO
        viewModelScope.launch {
            // Verificar limites antes de processar
            val currentModel = _selectedModel.value
            val limitCheck = checkModelUsageLimits(currentModel.apiEndpoint)

            limitCheck.fold(
                onSuccess = { canProceed ->
                    if (canProceed) {
                        // Continuar com a lógica original
                        processSendMessageWithAttachment(userMessageText, attachment)
                    } else {
                        // Limite excedido, erro já foi mostrado
                        Log.w(
                            "ChatViewModel",
                            "sendMessageWithAttachment cancelled: Usage limit exceeded"
                        )
                    }
                },
                onFailure = {
                    // Limite excedido, não enviar mensagem
                    Log.w(
                        "ChatViewModel",
                        "sendMessageWithAttachment cancelled: Usage limit exceeded"
                    )
                }
            )
        }
    }

    /**
     * Processa o envio de mensagem com anexo após verificação de limites
     */
    private fun processSendMessageWithAttachment(
        userMessageText: String,
        attachment: FileAttachment
    ) {
        _loadingState.value = LoadingState.LOADING
        _errorMessage.value = null

        val timestamp = System.currentTimeMillis()
        var targetConversationId = _currentConversationId.value
        val userId = _userIdFlow.value
        val isStartingNewConversation =
            (targetConversationId == null || targetConversationId == NEW_CONVERSATION_ID)

        if (isStartingNewConversation) {
            targetConversationId = timestamp
            Log.i(
                "ChatViewModel",
                "Action: Creating new conversation for message with attachment, ID: $targetConversationId"
            )
            _currentConversationId.value = targetConversationId
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    metadataDao.insertOrUpdateMetadata(
                        ConversationMetadataEntity(
                            conversationId = targetConversationId!!,
                            customTitle = null,
                            userId = userId
                        )
                    )
                    Log.d(
                        "ChatViewModel",
                        "Initial metadata saved for new conversation $targetConversationId"
                    )
                } catch (e: Exception) {
                    Log.e(
                        "ChatViewModel",
                        "Error saving initial metadata for new conv $targetConversationId",
                        e
                    )
                }
            }
        }

        if (targetConversationId == null || targetConversationId == NEW_CONVERSATION_ID) {
            Log.e(
                "ChatViewModel",
                "processSendMessageWithAttachment Error: Invalid targetConversationId"
            )
            _errorMessage.value = context.getString(R.string.error_internal_conversation)
            _loadingState.value = LoadingState.IDLE
            return
        }

        // Preparar a mensagem de texto do usuário com informações sobre o anexo
        val attachmentInfo = "📎 Arquivo: ${attachment.name} (${formatFileSize(attachment.size)})"
        val finalUserMessage = if (userMessageText.isBlank()) {
            attachmentInfo
        } else {
            "$userMessageText\n\n$attachmentInfo"
        }

        val userUiMessage = com.ivip.brainstormia.ChatMessage(finalUserMessage, Sender.USER)
        saveMessageToDb(userUiMessage, targetConversationId!!, timestamp)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentMessagesFromDb =
                    chatDao.getMessagesForConversation(targetConversationId!!, userId).first()
                val historyMessages = mapEntitiesToUiMessages(currentMessagesFromDb)
                    .takeLast(MAX_HISTORY_MESSAGES)

                // NOVA IMPLEMENTAÇÃO: Processar o conteúdo do arquivo
                var fileContent = ""

                try {
                    // Verificar se temos um arquivo local ou precisamos processar da URI
                    if (attachment.filePath != null && File(attachment.filePath).exists()) {
                        // Processar do arquivo local
                        Log.d("ChatViewModel", "Processando arquivo local: ${attachment.filePath}")
                        fileContent = fileProcessingManager.processFile(
                            File(attachment.filePath),
                            attachment.type
                        )
                    } else if (attachment.localUri != null) {
                        // Processar da URI
                        Log.d("ChatViewModel", "Processando arquivo de URI: ${attachment.localUri}")
                        fileContent = fileProcessingManager.processUri(
                            attachment.localUri,
                            attachment.type
                        )
                    } else {
                        Log.w(
                            "ChatViewModel",
                            "Arquivo não encontrado nem no caminho local nem na URI"
                        )
                        fileContent = "Arquivo não encontrado ou não acessível"
                    }
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Erro ao processar conteúdo do arquivo", e)
                    fileContent = "Erro ao extrair conteúdo do arquivo: ${e.message}"
                }

                // Verificar se o modelo suporta visão e se o conteúdo tem base64
                val currentModel = _selectedModel.value
                val isVisionCapable = when (currentModel.provider) {
                    AIProvider.OPENAI -> currentModel.id.contains("o") || currentModel.id.contains("vision")
                    AIProvider.GOOGLE -> currentModel.id.contains("gemini")
                    AIProvider.ANTHROPIC -> currentModel.id.contains("claude-3")
                    else -> false
                }

                // Verificar se o arquivo é uma imagem
                val isImageFile = attachment.type.startsWith("image/")

                // Preparar a mensagem para o modelo de IA incluindo o conteúdo do arquivo
                val augmentedUserMessage = if (isImageFile && isVisionCapable) {
                    // Para modelos com visão e arquivos de imagem, vamos manter o base64
                    if (fileContent.contains("[BASE64_IMAGE]")) {
                        // Adicionar contexto ao base64 para o modelo
                        val userContext = if (userMessageText.isBlank()) {
                            "Por favor analise esta imagem."
                        } else {
                            userMessageText
                        }
                        "$userContext\n\n$fileContent"
                    } else {
                        // Se não tem base64 (possivelmente imagem grande demais)
                        "$userMessageText\n\n" +
                                "Foi enviado um arquivo de imagem com as seguintes informações:\n" +
                                "Nome: ${attachment.name}\n" +
                                "Tipo: ${attachment.type}\n" +
                                "Tamanho: ${formatFileSize(attachment.size)}\n\n" +
                                "Conteúdo extraído da imagem:\n" +
                                fileContent
                    }
                } else {
                    // Para modelos sem visão ou arquivos não-imagem, usar formato normal de texto
                    val fileInfo = """
                Foi enviado um arquivo com as seguintes informações:
                Nome: ${attachment.name}
                Tipo: ${attachment.type}
                Tamanho: ${formatFileSize(attachment.size)}
                
                CONTEÚDO DO ARQUIVO:
                $fileContent
                
                Por favor, analise este conteúdo e forneça insights ou orientações relevantes.
                """.trimIndent()

                    if (userMessageText.isBlank()) {
                        fileInfo
                    } else {
                        "$userMessageText\n\n$fileInfo"
                    }
                }

                Log.d(
                    "ChatViewModel",
                    "API Call (with attachment): Sending message to API for conv $targetConversationId"
                )
                callOpenAIApi(augmentedUserMessage, historyMessages, targetConversationId!!)

                // Limpar o anexo atual após o envio
                withContext(Dispatchers.Main) {
                    _currentAttachment.value = null
                }

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error preparing history or calling API with attachment", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Erro ao processar mensagem com anexo: ${e.message}"
                    _loadingState.value = LoadingState.ERROR
                }
            }
        }
    }

    /**
     * Atualizar informações de limites de uso (para usar na UI se necessário)
     */
    fun refreshUsageLimits() {
        viewModelScope.launch {
            try {
                val result = apiService.getAllModelsUsage()
                result.fold(
                    onSuccess = { allUsage ->
                        Log.d(TAG, "Limites de uso atualizados: ${allUsage.usage.size} modelos")
                        // Você pode criar um StateFlow para expor estes dados à UI se necessário
                        // Por exemplo: _usageLimits.value = allUsage
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Erro ao atualizar limites: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exceção ao atualizar limites: ${e.message}")
            }
        }
    }

    /**
     * Formata o tamanho do arquivo para exibição
     */
    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(
            size / Math.pow(
                1024.0,
                digitGroups.toDouble()
            )
        ) + " " + units[digitGroups]
    }

    /**
     * Limpa o anexo atual
     */
    fun clearCurrentAttachment() {
        _currentAttachment.value = null
    }

    /**
     * Abre um arquivo com base no nome do arquivo
     */
    fun openFile(fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>().applicationContext
                val filesDir = File(context.filesDir, "uploads")
                val file = File(filesDir, fileName)

                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        _errorMessage.value = "Arquivo não encontrado: $fileName"
                    }
                    return@launch
                }

                // Criar um URI para o arquivo usando FileProvider
                val fileUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

                // Determinar o tipo MIME do arquivo
                val mimeType = getMimeType(file.name) ?: "*/*"

                // Criar intent para abrir o arquivo
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                // Verificar se há aplicativos para abrir este tipo de arquivo
                val packageManager = context.packageManager
                val activities =
                    packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

                if (activities.size > 0) {
                    // Há aplicativos que podem abrir este arquivo
                    context.startActivity(intent)
                } else {
                    // Nenhum aplicativo encontrado
                    withContext(Dispatchers.Main) {
                        _errorMessage.value =
                            "Nenhum aplicativo encontrado para abrir este tipo de arquivo."
                    }
                }

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error opening file: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Erro ao abrir arquivo: ${e.localizedMessage}"
                }
            }
        }
    }

    private fun getMimeType(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    fun selectModel(model: AIModel) {
        Log.d("ModelDebug", "🎯 selectModel() called")
        Log.d("ModelDebug", "Requested model: ${model.displayName} (${model.id})")
        Log.d("ModelDebug", "Is premium: ${model.isPremium}")
        logModelState("SELECT MODEL START")

        // Clear previous error messages
        _errorMessage.value = null

        // Check if user is logged in
        val currentUserId = _userIdFlow.value
        Log.d("ModelDebug", "Current user ID: '$currentUserId'")

        if (currentUserId.isBlank() || currentUserId == "local_user") {
            Log.w("ModelDebug", "❌ Attempted to select model without user login")
            _errorMessage.value = context.getString(R.string.error_login_required)
            return
        }

        // Check if user has permission to use premium model
        if (model.isPremium && !_isPremiumUser.value) {
            Log.w("ModelDebug", "❌ User attempted to select premium model without premium access")
            Log.w("ModelDebug", "   Model is premium: ${model.isPremium}")
            Log.w("ModelDebug", "   User is premium: ${_isPremiumUser.value}")
            _errorMessage.value = context.getString(R.string.error_premium_required)
            return
        }

        // 🔧 NOVA VERIFICAÇÃO: Evitar updates desnecessários
        if (model.id == _selectedModel.value.id) {
            Log.d("ModelDebug", "⚡ Model ${model.displayName} already selected, no change needed")
            logModelState("SELECT MODEL NO CHANGE")
            return
        }

        Log.d("ModelDebug", "🔄 Model change detected, proceeding with update")
        Log.d("ModelDebug", "   FROM: ${_selectedModel.value.displayName} (${_selectedModel.value.id})")
        Log.d("ModelDebug", "   TO: ${model.displayName} (${model.id})")

        viewModelScope.launch {
            try {
                Log.d("ModelDebug", "💾 Starting model update process")

                // 🔧 NOVA ESTRATÉGIA: Bloquear observação durante update
                Log.d("ModelDebug", "🔒 Setting update flag to prevent race condition")
                _isUpdatingModel.value = true

                // 1. Atualizar modelo localmente PRIMEIRO
                withContext(Dispatchers.Main) {
                    Log.d("ModelDebug", "🔄 Updating selectedModel on Main thread")
                    val previousModel = _selectedModel.value
                    _selectedModel.value = model
                    Log.d("ModelDebug", "✅ Model updated in StateFlow")
                    Log.d("ModelDebug", "   Previous: ${previousModel.displayName}")
                    Log.d("ModelDebug", "   Current: ${_selectedModel.value.displayName}")
                }

                // 2. Aguardar um pouco para garantir que a UI foi atualizada
                delay(100)

                // 3. Salvar no banco de dados
                withContext(Dispatchers.IO) {
                    Log.d("ModelDebug", "💾 Saving to database on IO thread")
                    Log.d("ModelDebug", "   User ID: $currentUserId")
                    Log.d("ModelDebug", "   Model ID: ${model.id}")

                    modelPreferenceDao.insertOrUpdatePreference(
                        ModelPreferenceEntity(
                            userId = currentUserId,
                            selectedModelId = model.id
                        )
                    )
                    Log.d("ModelDebug", "✅ Successfully saved to database")
                }

                // 4. Aguardar mais um pouco para garantir que a transação foi commitada
                delay(200)

                // 5. Liberar a observação
                Log.d("ModelDebug", "🔓 Releasing update flag")
                _isUpdatingModel.value = false

                Log.i("ModelDebug", "🎉 Model preference saved successfully: ${model.displayName}")
                logModelState("SELECT MODEL SUCCESS")

            } catch (e: Exception) {
                Log.e("ModelDebug", "❌ Error saving model preference", e)
                Log.e("ModelDebug", "   Error message: ${e.message}")
                Log.e("ModelDebug", "   Error class: ${e.javaClass.simpleName}")

                // 🔧 IMPORTANTE: Liberar flag mesmo em caso de erro
                _isUpdatingModel.value = false

                withContext(Dispatchers.Main) {
                    _errorMessage.value = context.getString(R.string.error_save_model, e.localizedMessage)
                }
                logModelState("SELECT MODEL ERROR")
            }
        }
    }

    fun generateImage(
        prompt: String,
        quality: String = "standard",
        size: String = "1024x1024",
        transparent: Boolean = false
    ) {
        // Check if user is authenticated
        val currentUserId = _userIdFlow.value
        if (currentUserId.isBlank() || currentUserId == "local_user") {
            _errorMessage.value = context.getString(R.string.error_login_required)
            return
        }

        // Get premium status
        val isPremium = _isPremiumUser.value
        Log.d("ChatViewModel", "Starting image generation, user premium status: $isPremium")

        // Encontrar o modelo específico que queremos usar
        val imageModel = availableModels.find { it.id == "dall-e-3" }
            ?: AIModel(
                id = "dall-e-3",
                displayName = "DALL-E 3",
                apiEndpoint = "dall-e-3",
                provider = AIProvider.OPENAI,
                isPremium = true
            )

        Log.d(
            "ChatViewModel",
            "Using image model: ${imageModel.displayName} (${imageModel.apiEndpoint})"
        )

        // Atualizar estados para mostrar o carregamento na UI
        _isImageGenerating.value = true
        _currentImagePrompt.value = prompt
        _isGeneratingImage.value = true
        _imageGenerationState.value =
            ImageGenerationResult.Loading("Iniciando geração de imagem...")

        viewModelScope.launch {
            try {
                // Collect result from image generation
                // Passando o modelo específico para o imageGenerationManager
                imageGenerationManager.generateAndSaveImage(
                    openAIClient = openAIClient,
                    prompt = prompt,
                    quality = quality,
                    size = size,
                    transparent = transparent,
                    isPremiumUser = isPremium,
                    modelId = imageModel.apiEndpoint // Novo parâmetro especificando o modelo
                ).collect { result ->
                    Log.d("ChatViewModel", "Image generation update: $result")
                    _imageGenerationState.value = result

                    // Also display errors in the main error UI
                    if (result is ImageGenerationResult.Error) {
                        Log.e("ChatViewModel", "Image generation error: ${result.message}")
                        _errorMessage.value = result.message
                    }

                    if (result is ImageGenerationResult.Success) {
                        try {
                            var effectiveConversationId = _currentConversationId.value
                            val userId = _userIdFlow.value // Get current user ID

                            if (effectiveConversationId == null || effectiveConversationId == NEW_CONVERSATION_ID) {
                                val newConvTimestampId = System.currentTimeMillis()
                                _currentConversationId.value =
                                    newConvTimestampId // Critical: Update the ViewModel's current conversation ID
                                effectiveConversationId = newConvTimestampId

                                // Save metadata for this new conversation
                                // This ensures it's recognized as a proper conversation
                                withContext(Dispatchers.IO) {
                                    metadataDao.insertOrUpdateMetadata(
                                        ConversationMetadataEntity(
                                            conversationId = newConvTimestampId,
                                            customTitle = null, // Title can be generated later based on prompt or first interaction
                                            userId = userId
                                        )
                                    )
                                }
                                Log.d(
                                    "ChatViewModel",
                                    "Image generation initiated a new conversation. ID: $newConvTimestampId"
                                )
                            }

                            // Save the generated image URI
                            _generatedImageUri.value = result.imageUri
                            Log.d(
                                "ChatViewModel",
                                "Image generated successfully at: ${result.imagePath}"
                            )

                            // Verify the file exists
                            val file = File(result.imagePath)
                            Log.d(
                                "ChatViewModel",
                                "Image file exists: ${file.exists()}, size: ${file.length()}"
                            )

                            // Send the image as a bot message
                            val imageMessage = """
                ![Imagem Gerada](${result.imagePath})
                
                *Imagem gerada com base no prompt:* 
                "${prompt}"
                """.trimIndent()

                            Log.d(
                                "ChatViewModel",
                                "Creating bot message with image path: ${result.imagePath}"
                            )

                            val botMessageEntity = ChatMessageEntity(
                                id = 0,
                                conversationId = effectiveConversationId!!,
                                text = imageMessage,
                                sender = Sender.BOT.name,
                                timestamp = System.currentTimeMillis(),
                                userId = _userIdFlow.value
                            )

                            // Insert message and verify - usando withContext para garantir execução completa
                            val messageId = withContext(Dispatchers.IO) {
                                chatDao.insertMessage(botMessageEntity)
                            }
                            Log.d(
                                "ChatViewModel",
                                "Bot message with image inserted with ID: $messageId"
                            )

                            // Emitir evento de mensagem adicionada para atualizar a UI
                            _messageAddedEvent.emit(Unit)

                            // Atualizar explicitamente a UI
                            withContext(Dispatchers.Main) {
                                // Forçar atualização da UI
                                val currentId = _currentConversationId.value
                                if (currentId != null) {
                                    // Apenas notificar que uma mensagem foi adicionada
                                    // sem mudar o ID da conversa
                                    _messageAddedEvent.emit(Unit)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ChatViewModel", "Error processing successful image", e)
                            _errorMessage.value = "Erro ao processar imagem: ${e.localizedMessage}"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error in image generation", e)
                _imageGenerationState.value =
                    ImageGenerationResult.Error("Erro: ${e.localizedMessage ?: "Erro desconhecido"}")
                _errorMessage.value =
                    "Erro na geração de imagem: ${e.localizedMessage ?: "Erro desconhecido"}"
            } finally {
                // Limpar os estados de carregamento quando terminar
                _isImageGenerating.value = false
                _currentImagePrompt.value = null
                _isGeneratingImage.value = false
            }
        }
    }

    // Function to check if user is premium via BillingViewModel singleton
    fun checkIfUserIsPremium() {
        val currentUser = FirebaseAuth.getInstance().currentUser

        if (currentUser == null) {
            Log.e("ChatViewModel", "Usuário não autenticado")
            _isPremiumUser.value = false
            validateCurrentModel(false)
            return
        }

        Log.d("ChatViewModel", "🔍 Verificando status premium via backend...")

        viewModelScope.launch {
            try {
                // Sempre tentar backend primeiro
                val tokenResult = currentUser.getIdToken(false).await()
                val userToken = tokenResult.token

                if (userToken != null) {
                    val response = apiClient.validatePremiumStatus(userToken)

                    withContext(Dispatchers.Main) {
                        _isPremiumUser.value = response.hasAccess
                        _userPlanType.value = response.subscriptionType
                        validateCurrentModel(response.hasAccess)

                        Log.i(
                            "ChatViewModel",
                            "✅ Backend: Premium=${response.hasAccess}, Plano=${response.subscriptionType}"
                        )
                    }
                } else {
                    // Fallback para método local se não conseguir token
                    checkIfUserIsPremiumLocal()
                }
            } catch (e: Exception) {
                Log.e(
                    "ChatViewModel",
                    "❌ Erro na verificação backend, usando fallback local: ${e.message}"
                )
                // Fallback para método local
                checkIfUserIsPremiumLocal()
            }
        }
    }

    fun saveImageToGallery(imagePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(imagePath)
                if (!file.exists()) {
                    Log.e("ChatViewModel", "File to save does not exist: $imagePath")
                    _imageSavedEvent.emit(context.getString(R.string.error_file_not_found_for_saving))
                    return@launch
                }

                val resolver = context.contentResolver
                val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val imageName = file.name
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, imageName)
                    put(
                        MediaStore.Images.Media.MIME_TYPE,
                        "image/png"
                    ) // Ou o tipo correto se souber
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            "Pictures/StormChat"
                        ) // Salva na pasta Pictures/StormChat
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val imageUri = resolver.insert(imageCollection, contentValues)

                if (imageUri == null) {
                    Log.e("ChatViewModel", "Failed to create new MediaStore record.")
                    _imageSavedEvent.emit(context.getString(R.string.error_saving_image_generic))
                    return@launch
                }

                var outputStream: OutputStream? = null
                try {
                    outputStream = resolver.openOutputStream(imageUri)
                    if (outputStream == null) {
                        throw IOException("Failed to get output stream.")
                    }
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(imageUri, contentValues, null, null)
                    }
                    Log.d("ChatViewModel", "Image saved to gallery: $imageUri")
                    _imageSavedEvent.emit(
                        context.getString(
                            R.string.image_saved_to_gallery,
                            "Pictures/StormChat"
                        )
                    )
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Error copying file to MediaStore: ${e.message}", e)
                    // Se falhar, tenta remover a entrada pendente
                    resolver.delete(imageUri, null, null)
                    _imageSavedEvent.emit(context.getString(R.string.error_saving_image_generic))
                } finally {
                    outputStream?.close()
                }

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error saving image to gallery: ${e.message}", e)
                _imageSavedEvent.emit(context.getString(R.string.error_saving_image_generic))
            }
        }
    }

    private fun checkIfUserIsPremiumWithBackend() {
        viewModelScope.launch {
            try {
                val userToken = tokenManager.getValidToken()
                if (userToken == null) {
                    _isPremiumUser.value = false
                    return@launch
                }

                val response = apiClient.validatePremiumStatus(userToken)

                withContext(Dispatchers.Main) {
                    _isPremiumUser.value = response.hasAccess
                    _userPlanType.value = response.subscriptionType

                    Log.i("ChatViewModel", "Backend confirmou: Premium=${response.hasAccess}")
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Erro verificação backend: ${e.message}")
                // Fallback para método local em caso de erro
                checkIfUserIsPremiumLocal()
            }
        }
    }

    // Usar o novo endpoint de validação
    private suspend fun validateWithBackend(): ValidationResponse {
        val currentUser = FirebaseAuth.getInstance().currentUser

        return try {
            // ✅ CORREÇÃO: Acessar .token da GetTokenResult
            val tokenResult = currentUser?.getIdToken(false)?.await()
            val token = tokenResult?.token

            if (token != null) {
                apiClient.validatePremiumStatus(token)
            } else {
                ValidationResponse(
                    hasAccess = false,
                    subscriptionType = null,
                    expirationDate = null,
                    reasons = mapOf("noToken" to true),
                    errorMessage = "No authentication token available"
                )
            }
        } catch (e: Exception) {
            ValidationResponse(
                hasAccess = false,
                subscriptionType = null,
                expirationDate = null,
                reasons = mapOf("error" to true),
                errorMessage = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * ✅ 4. NOVA FUNÇÃO: Verificação local (método original) - ADICIONAR ESTA FUNÇÃO
     */
    private fun checkIfUserIsPremiumLocal() {
        val startTime = System.currentTimeMillis()
        val currentUser = FirebaseAuth.getInstance().currentUser

        // ✅ LOGS BACKEND - INÍCIO VERIFICAÇÃO LOCAL
        Log.d("backend", "=== VERIFICAÇÃO PREMIUM LOCAL ===")
        Log.d("backend", "Timestamp: $startTime")
        Log.d("backend", "Motivo: Fallback ou configuração")
        Log.d("backend", "User ID: \"${currentUser?.uid}\"")
        Log.d("backend", "User Email: \"${currentUser?.email}\"")
        Log.d("backend", "=================================")

        // Verificar a última vez que verificamos e se o usuário mudou
        val lastCheck = System.currentTimeMillis() - lastPremiumCheck
        val shouldSkipCheck = lastCheck < 5000 &&
                lastCheckedUserId == FirebaseAuth.getInstance().currentUser?.uid &&
                !forceNextCheck &&
                _isPremiumUser.value

        // ✅ LOGS BACKEND - CACHE LOCAL
        Log.d("backend", "=== CACHE VERIFICAÇÃO LOCAL ===")
        Log.d("backend", "Última verificação: $lastPremiumCheck")
        Log.d("backend", "Tempo desde última: ${lastCheck}ms")
        Log.d("backend", "Último user ID: \"$lastCheckedUserId\"")
        Log.d("backend", "Current user ID: \"${currentUser?.uid}\"")
        Log.d("backend", "Force next check: $forceNextCheck")
        Log.d("backend", "Current premium: ${_isPremiumUser.value}")
        Log.d("backend", "Should skip: $shouldSkipCheck")
        Log.d("backend", "==============================")

        if (shouldSkipCheck) {
            Log.d("ChatViewModel", "Verificação premium local recente. Pulando.")
            Log.d("backend", "✅ USANDO CACHE LOCAL (verificação recente)")
            return
        }

        // ✅ LOGS BACKEND - NOVA VERIFICAÇÃO LOCAL
        Log.d("backend", "=== NOVA VERIFICAÇÃO LOCAL ===")
        Log.d("backend", "Cache invalidado ou expirado")
        Log.d("backend", "Atualizando timestamps...")

        // Atualizar o timestamp e usuário da última verificação
        lastPremiumCheck = System.currentTimeMillis()
        lastCheckedUserId = FirebaseAuth.getInstance().currentUser?.uid

        Log.d("backend", "Novo timestamp: $lastPremiumCheck")
        Log.d("backend", "Novo user ID: \"$lastCheckedUserId\"")
        Log.d("backend", "=============================")

        Log.d("ChatViewModel", "Verificando status premium LOCAL para usuário: $lastCheckedUserId")

        // Get singleton BillingViewModel through application
        viewModelScope.launch {
            try {
                val app = getApplication<Application>() as BrainstormiaApplication
                val billingViewModel = app.billingViewModel

                // ✅ LOGS BACKEND - BILLING VIEW MODEL
                Log.d("backend", "=== BILLING VIEW MODEL ===")
                Log.d("backend", "BillingViewModel obtido: ${billingViewModel != null}")
                Log.d("backend", "Estado atual premium: ${billingViewModel.isPremiumUser.value}")
                Log.d("backend", "Plano atual: \"${billingViewModel.userPlanType.value}\"")
                Log.d("backend", "Loading state: ${billingViewModel.isPremiumLoading.value}")
                Log.d("backend", "==========================")

                // Observe premium status changes from BillingViewModel
                launch {
                    billingViewModel.isPremiumUser.collect { isPremiumFromBilling ->
                        Log.d(
                            "ChatViewModel",
                            "BillingViewModel reported premium status: $isPremiumFromBilling"
                        )

                        // ✅ LOGS BACKEND - STATUS CHANGE
                        Log.d("backend", "=== STATUS CHANGE FROM BILLING ===")
                        Log.d("backend", "Previous: ${_isPremiumUser.value}")
                        Log.d("backend", "New: $isPremiumFromBilling")
                        Log.d("backend", "Timestamp: ${System.currentTimeMillis()}")
                        Log.d("backend", "=================================")

                        _isPremiumUser.value = isPremiumFromBilling
                        validateCurrentModel(isPremiumFromBilling)
                    }
                }

                // ✅ LOGS BACKEND - FORCE CHECK DECISION
                if (forceNextCheck) {
                    Log.d("backend", "=== FORCE CHECK SOLICITADO ===")
                    Log.d("backend", "Priority: HIGH")
                    Log.d("backend", "Reason: Force flag ativo")
                    Log.d("backend", "=============================")

                    billingVM.checkPremiumStatus(forceRefresh = true)
                    forceNextCheck = false

                    launch {
                        delay(2000)
                        Log.d("backend", "🔄 Segunda verificação após force check...")
                    }
                } else {
                    Log.d("backend", "=== CHECK NORMAL ===")
                    Log.d("backend", "Type: NORMAL")
                    Log.d("backend", "Method: checkUserSubscription")
                    Log.d("backend", "===================")
                }

                val endTime = System.currentTimeMillis()
                Log.d("backend", "Verificação local completada em ${endTime - startTime}ms")

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error checking premium with BillingViewModel", e)

                val endTime = System.currentTimeMillis()
                Log.d("backend", "❌ ERRO NA VERIFICAÇÃO LOCAL")
                Log.d("backend", "Erro: ${e.message}")
                Log.d("backend", "Duração: ${endTime - startTime}ms")
            }
        }
    }

    // ADICIONAR ESTE MÉTODO COMPLETO:
    /**
     * Verificar limites de uso para um modelo específico antes de enviar mensagem
     */
    private suspend fun checkModelUsageLimits(modelName: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Verificando limites para modelo: $modelName")

                val result = apiService.checkAndIncrementModelUsage(modelName)
                result.fold(
                    onSuccess = { usageInfo ->
                        Log.d(TAG, "Limites verificados: ${usageInfo.current}/${usageInfo.limit}")
                        if (usageInfo.remaining > 0) {
                            Result.success(true)
                        } else {
                            withContext(Dispatchers.Main) {
                                val resetDate =
                                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                        .format(Date(usageInfo.resetAt))

                                _errorMessage.value = if (_isPremiumUser.value) {
                                    "Limite diário atingido para ${_selectedModel.value.displayName}. " +
                                            "Próximo reset: $resetDate"
                                } else {
                                    "Limite diário atingido para ${_selectedModel.value.displayName}. " +
                                            "Faça upgrade para Premium para limites maiores. " +
                                            "Próximo reset: $resetDate"
                                }
                            }
                            Result.failure(Exception("USAGE_LIMIT_EXCEEDED"))
                        }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Erro ao verificar limites: ${error.message}")
                        // Em caso de erro na verificação, permitir o uso (fail-safe)
                        Result.success(true)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exceção ao verificar limites: ${e.message}")
                // Em caso de exceção, permitir o uso (fail-safe)
                Result.success(true)
            }
        }
    }

    // New method to validate current model based on premium status
    private fun validateCurrentModel(isPremium: Boolean) {
        Log.d(
            "ChatViewModel",
            "[LOG] validateCurrentModel() chamado. isPremium: " + isPremium + ", Modelo atual: " + _selectedModel.value.displayName + ", Modelo é premium: " + _selectedModel.value.isPremium
        );
        if (!isPremium && _selectedModel.value.isPremium) {
            // Non-premium user using premium model
            // Return to default model
            val defaultModel =
                availableModels.find { it.id == "gemini-2.5-flash-preview-05-20" } ?: defaultModel

            viewModelScope.launch {
                try {
                    // Update selected model
                    Log.d(
                        "ChatViewModel",
                        "[LOG] _selectedModel.value alterado para: " + (defaultModel).displayName
                    );
                    _selectedModel.value = defaultModel
                    Log.i(
                        "ChatViewModel",
                        "Non-premium user. Reverting to default model: ${defaultModel.displayName}"
                    )

                    // Update preference in database
                    modelPreferenceDao.insertOrUpdatePreference(
                        ModelPreferenceEntity(
                            userId = _userIdFlow.value,
                            selectedModelId = defaultModel.id
                        )
                    )
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Error saving default model preference", e)
                }
            }
        }
    }

    private val _isPremiumUser = MutableStateFlow(false)
    val isPremiumUser: StateFlow<Boolean> = _isPremiumUser
    private var lastPremiumCheck = 0L
    private var lastCheckedUserId: String? = null
    private var forceNextCheck = true

    // Expose model list
    val modelOptions: List<AIModel> = availableModels

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Voice recognition states
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    fun startListening() {
        _isListening.value = true
        // Timeout to stop listening after 30 seconds
        viewModelScope.launch {
            delay(30000)
            stopListening()
        }
    }

    fun stopListening() {
        _isListening.value = false
    }

    // Method to handle voice recognition result
    fun handleVoiceInput(text: String) {
        stopListening()
        // Recognized text will be sent as a regular message
        // You can process it here before sending to the service
    }

    private val _currentConversationId = MutableStateFlow<Long?>(null)
    val currentConversationId: StateFlow<Long?> = _currentConversationId.asStateFlow()

    private val _loadingState = MutableStateFlow(LoadingState.IDLE)
    val isLoading: StateFlow<Boolean> = _loadingState.map { it == LoadingState.LOADING }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = false
        )

    private val _clearConversationListEvent = MutableStateFlow(false)
    val clearConversationListEvent: StateFlow<Boolean> = _clearConversationListEvent.asStateFlow()

    private val _userIdFlow = MutableStateFlow(getCurrentUserId())

    private val _showConversations = MutableStateFlow(true)
    val showConversations: StateFlow<Boolean> = _showConversations.asStateFlow()

    /* ─── Readiness flag ─────────────────────────────────────────────── */

    // 1) internal mutable flag
    private val _isReady = MutableStateFlow(false)

    // 2) public flag for external observers
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private fun logModelState(context: String) {
        Log.d("ModelDebug", "=== $context ===")
        Log.d("ModelDebug", "Current Model: ${_selectedModel.value.displayName} (${_selectedModel.value.id})")
        Log.d("ModelDebug", "Is Premium Model: ${_selectedModel.value.isPremium}")
        Log.d("ModelDebug", "User Premium Status: ${_isPremiumUser.value}")
        Log.d("ModelDebug", "User ID: ${_userIdFlow.value}")
        Log.d("ModelDebug", "Auth User: ${FirebaseAuth.getInstance().currentUser?.uid}")
        Log.d("ModelDebug", "======================")
    }

    init {
        // Initial premium status check
        checkIfUserIsPremium()

        viewModelScope.launch {
            // Aguarda 5 segundos antes da primeira verificação periódica
            delay(5000)

            // Verificações periódicas a cada 60 segundos
            while (true) {
                delay(60000) // 60 segundos

                // Somente verifica se o usuário estiver logado
                if (FirebaseAuth.getInstance().currentUser != null) {
                    Log.d("ChatViewModel", "Verificação periódica de status premium")
                    checkIfUserIsPremium()
                }
            }
        }

        // NOVO: Timer para verificar o status premium periodicamente
        viewModelScope.launch {
            // Verificar imediatamente na inicialização
            checkIfUserIsPremium()

            // Depois verificar novamente após 3 segundos (para correção de status inicial)
            delay(3000)
            checkIfUserIsPremium()

            // E depois a cada 30 segundos (verificação leve)
            while (true) {
                delay(30000)  // 30 segundos
                // Somente fazer verificação se o usuário estiver logado
                if (FirebaseAuth.getInstance().currentUser != null) {
                    checkIfUserIsPremium()
                }
            }
        }

        // Observe premium status changes to validate selected model
        viewModelScope.launch {
            _isPremiumUser.collect { isPremium ->
                Log.d("ChatViewModel", "Premium status changed: $isPremium")
                validateCurrentModel(isPremium)
            }
        }

        // 🔧 CORREÇÃO PRINCIPAL: Premium status check and selected model validation
        viewModelScope.launch {
            Log.d("ModelDebug", "🔄 Starting userIdFlow collection")

            // Aguardar que o usuário seja carregado primeiro
            _userIdFlow.collect { userId ->
                Log.d("ModelDebug", "👤 UserID changed to: '$userId'")
                logModelState("USER ID CHANGED")

                // Cancelar job anterior de observação de preferências
                modelPreferenceJob?.cancel()

                if (userId.isNotBlank() && userId != "local_user") {
                    Log.d("ModelDebug", "✅ Valid user detected, loading model preference for: $userId")

                    // Criar novo job para observar preferências
                    modelPreferenceJob = launch {
                        // Carrega preferência do usuário do banco
                        modelPreferenceDao.getModelPreference(userId)
                            .collect { preference ->
                                // 🔧 NOVA VERIFICAÇÃO: Só processar se não estamos atualizando
                                if (_isUpdatingModel.value) {
                                    Log.d("ModelDebug", "⏸️ Skipping preference load - model update in progress")
                                    return@collect
                                }

                                Log.d("ModelDebug", "💾 Database preference loaded:")
                                Log.d("ModelDebug", "  - Preference exists: ${preference != null}")
                                Log.d("ModelDebug", "  - Saved model ID: ${preference?.selectedModelId ?: "none"}")
                                Log.d("ModelDebug", "  - Current updating flag: ${_isUpdatingModel.value}")
                                logModelState("BEFORE PREFERENCE PROCESSING")

                                if (preference != null) {
                                    val savedModel = availableModels.find { it.id == preference.selectedModelId }
                                    Log.d("ModelDebug", "🔍 Searching for saved model:")
                                    Log.d("ModelDebug", "  - Saved model found: ${savedModel != null}")
                                    Log.d("ModelDebug", "  - Saved model name: ${savedModel?.displayName ?: "not found"}")

                                    if (savedModel != null) {
                                        Log.d("ModelDebug", "🎯 Found saved model: ${savedModel.displayName}")

                                        // Verificar se o usuário tem permissão para usar este modelo
                                        if (savedModel.isPremium && !_isPremiumUser.value) {
                                            Log.w("ModelDebug", "⚠️ User lost premium access, reverting to default model")
                                            Log.w("ModelDebug", "   - Saved model is premium: ${savedModel.isPremium}")
                                            Log.w("ModelDebug", "   - User premium status: ${_isPremiumUser.value}")

                                            // Se perdeu acesso premium, voltar ao modelo padrão
                                            _selectedModel.value = defaultModel
                                            logModelState("REVERTED TO DEFAULT (NO PREMIUM)")

                                            // Atualizar preferência no banco
                                            try {
                                                modelPreferenceDao.insertOrUpdatePreference(
                                                    ModelPreferenceEntity(
                                                        userId = userId,
                                                        selectedModelId = defaultModel.id
                                                    )
                                                )
                                                Log.d("ModelDebug", "💾 Updated DB preference to default model")
                                            } catch (e: Exception) {
                                                Log.e("ModelDebug", "❌ Error updating model preference to default", e)
                                            }
                                        } else {
                                            // 🔧 NOVA VERIFICAÇÃO: Só atualizar se realmente mudou
                                            if (_selectedModel.value.id != savedModel.id) {
                                                Log.d("ModelDebug", "✅ User has permission for saved model")
                                                Log.d("ModelDebug", "   - Model is premium: ${savedModel.isPremium}")
                                                Log.d("ModelDebug", "   - User is premium: ${_isPremiumUser.value}")

                                                val previousModel = _selectedModel.value
                                                _selectedModel.value = savedModel

                                                Log.i("ModelDebug", "🔄 Model changed:")
                                                Log.i("ModelDebug", "   FROM: ${previousModel.displayName}")
                                                Log.i("ModelDebug", "   TO: ${savedModel.displayName}")
                                                logModelState("LOADED SAVED MODEL")
                                            } else {
                                                Log.d("ModelDebug", "✅ Saved model matches current, no change needed")
                                            }
                                        }
                                    } else {
                                        Log.w("ModelDebug", "❌ Saved model ID '${preference.selectedModelId}' not found in availableModels")
                                        Log.w("ModelDebug", "Available model IDs: ${availableModels.map { it.id }}")

                                        // Modelo não encontrado, usar padrão e atualizar banco
                                        _selectedModel.value = defaultModel
                                        logModelState("FALLBACK TO DEFAULT (MODEL NOT FOUND)")

                                        try {
                                            modelPreferenceDao.insertOrUpdatePreference(
                                                ModelPreferenceEntity(
                                                    userId = userId,
                                                    selectedModelId = defaultModel.id
                                                )
                                            )
                                            Log.i("ModelDebug", "💾 Updated preference to default model due to missing saved model")
                                        } catch (e: Exception) {
                                            Log.e("ModelDebug", "❌ Error updating model preference to default", e)
                                        }
                                    }
                                } else {
                                    Log.i("ModelDebug", "🆕 No model preference found for user $userId, using default")
                                    logModelState("NO PREFERENCE FOUND")

                                    // Primeira vez do usuário, salvar o modelo padrão
                                    try {
                                        modelPreferenceDao.insertOrUpdatePreference(
                                            ModelPreferenceEntity(
                                                userId = userId,
                                                selectedModelId = defaultModel.id
                                            )
                                        )
                                        Log.i("ModelDebug", "💾 Saved default model preference for new user")
                                        logModelState("SAVED DEFAULT FOR NEW USER")
                                    } catch (e: Exception) {
                                        Log.e("ModelDebug", "❌ Error saving default model preference for new user", e)
                                    }
                                }
                            }
                    }
                } else {
                    Log.d("ModelDebug", "👤 User not logged in, using default model")
                    _selectedModel.value = defaultModel
                    logModelState("USER NOT LOGGED IN")
                }
            }
        }

        // wait for "new conversation" creation or any task
        loadInitialConversationOrStartNew()
        _isReady.value = true          // <- READY ✔

        auth.addAuthStateListener { firebaseAuth ->
            val newUser = firebaseAuth.currentUser
            val newUserId = newUser?.uid ?: "local_user"
            val previousUserId = _userIdFlow.value

            Log.d("ChatViewModel", "Auth state changed: $previousUserId -> $newUserId")

            if (newUserId != previousUserId) {
                viewModelScope.launch {
                    if (newUser != null) {
                        // User logged in
                        Log.d("ChatViewModel", "User logged in: $newUserId")
                        _userIdFlow.value = newUserId
                        _showConversations.value = true

                        // Try to load conversations with delay
                        delay(300)
                        forceLoadConversationsAfterLogin()
                    } else {
                        // User logged out
                        Log.d("ChatViewModel", "User logged out")
                        _userIdFlow.value = "local_user"
                        _showConversations.value = false
                        _currentConversationId.value = NEW_CONVERSATION_ID
                    }
                }
            }
        }

        loadInitialConversationOrStartNew()
    }

    private fun getCurrentUserId(): String =
        FirebaseAuth.getInstance().currentUser?.uid ?: "local_user"

    private val rawConversationsFlow: Flow<List<ConversationInfo>> =
        _userIdFlow.flatMapLatest { uid ->
            Log.d("ChatViewModel", "Initializing rawConversationsFlow for user: $uid")
            if (uid.isBlank()) {
                Log.w("ChatViewModel", "Empty user ID in rawConversationsFlow, emitting empty list")
                flowOf(emptyList())
            } else {
                chatDao.getConversationsForUser(uid)
                    .onStart {
                        Log.d("ChatViewModel", "Starting to collect conversations for user: $uid")
                    }
                    .onEmpty {
                        Log.d("ChatViewModel", "No conversations found for user: $uid")
                    }
                    .catch { e ->
                        Log.e(
                            "ChatViewModel",
                            "Error loading raw conversations flow for user: $uid",
                            e
                        )
                        _errorMessage.value = "Erro ao carregar lista de conversas (raw)."
                        emit(emptyList())
                    }
            }
        }

    private val metadataFlow: Flow<List<ConversationMetadataEntity>> =
        _userIdFlow.flatMapLatest { uid ->
            metadataDao.getMetadataForUser(uid)
                .catch { e ->
                    Log.e("ChatViewModel", "Error loading metadata flow", e)
                    emit(emptyList())
                }
        }

    val conversationListForDrawer: StateFlow<List<ConversationDisplayItem>> =
        combine(
            rawConversationsFlow,
            metadataFlow,
            _showConversations,
            _userIdFlow
        ) { conversations, metadataList, showConversations, currentUserId ->
            if (!showConversations || auth.currentUser == null) {
                return@combine emptyList<ConversationDisplayItem>()
            }

            Log.d(
                "ChatViewModel",
                "Combining ${conversations.size} convs and ${metadataList.size} metadata entries for user $currentUserId."
            )

            val userMetadata = metadataList.filter { it.userId == currentUserId }
            val metadataMap = userMetadata.associateBy({ it.conversationId }, { it.customTitle })

            conversations.map { convInfo ->
                val customTitle = metadataMap[convInfo.id]?.takeIf { it.isNotBlank() }
                val finalTitle = customTitle ?: generateFallbackTitleSync(convInfo.id)
                val conversationType = determineConversationType(finalTitle, convInfo.id)
                ConversationDisplayItem(
                    id = convInfo.id,
                    displayTitle = finalTitle,
                    lastTimestamp = convInfo.lastTimestamp,
                    conversationType = conversationType
                )
            }
        }
            .flowOn(Dispatchers.Default)
            .catch { e ->
                Log.e("ChatViewModel", "Error combining conversations and metadata", e)
                withContext(Dispatchers.Main.immediate) {
                    _errorMessage.value = "Erro ao processar lista de conversas para exibição."
                }
                emit(emptyList())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList()
            )

    val messages: StateFlow<List<com.ivip.brainstormia.ChatMessage>> =
        _currentConversationId.flatMapLatest { convId ->
            Log.d("ChatViewModel", "[State] CurrentConversationId changed: $convId")
            when (convId) {
                null, NEW_CONVERSATION_ID -> {
                    flowOf(
                        listOf(
                            com.ivip.brainstormia.ChatMessage(
                                welcomeMessageText,
                                Sender.BOT
                            )
                        )
                    )
                }

                else -> chatDao.getMessagesForConversation(convId, _userIdFlow.value)
                    .map { entities ->
                        Log.d(
                            "ChatViewModel",
                            "[State] Mapping ${entities.size} entities for conv $convId"
                        )
                        mapEntitiesToUiMessages(entities)
                    }
                    .catch { e ->
                        Log.e("ChatViewModel", "Error loading messages for conversation $convId", e)
                        withContext(Dispatchers.Main.immediate) {
                            _errorMessage.value = "Erro ao carregar mensagens da conversa."
                        }
                        emit(emptyList())
                    }
            }
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyList()
            )

    private val welcomeMessageText =
        getApplication<Application>().getString(R.string.welcome_message)

    // Evento disparado sempre que uma nova mensagem é salva no BD
    private val _messageAddedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val messageAddedEvent: SharedFlow<Unit> = _messageAddedEvent.asSharedFlow()

    private val brainstormiaSystemPrompt = """
    ############################################
    #           STORMCHAT – SYSTEM PROMPT       #
    ############################################
    
    ## 1. Essence
    You are **StormChat**, a free‑thinking creativity agent wielding the candor of Nietzsche's "hammer." Your mission is to solve any problem the user presents with clear, impactful, and actionable answers.
    
    ## 2. Practical Mission
    Provide direct solutions, original insights, and incisive questions that break fragile assumptions and guide the user to a concrete outcome.
    
    ## 3. Core Values
    1. **Clarity with Depth** – explain without shallow simplification.  
    2. **Creativity with Impact** – bold yet relevant analogies and examples.  
    3. **Intellectual Courage** – question certainties and present alternative views.  
    4. **Immediate Utility** – deliver executable steps or practical recommendations.  
    5. **User Respect** – adapt tone and detail to the user's context and goals.
    
    ## 4. Interaction Style
    - **Free Flow** – maintain context, weaving ideas across the conversation.  
    - **Critical Hammer Blows** – spotlight fragile premises and propose questions to test them.  
    - **Tailored References** – cite science, art, technology, business, or pop culture only when they add real value.  
    - **No Empty Jargon** – favor direct language; acknowledge uncertainties when relevant.
    
    ## 5. Language
    Respond primarily in the user's language. If the user switches languages, adapt immediately while preserving the same level of clarity and impact. Treat all languages equally; default to English only when unsure.
    
    ## 6. Safety & Ethics
    - Do not provide medical, legal, or financial advice without clear disclaimers.  
    - Never invent data or use non‑existent quotations.  
    - When information is missing, direct the user to trustworthy sources or research paths.
    
    ## 7. Default Identity
    If asked "Who are you?" reply:  
    > **I'm StormChat ⚡ — here to generate ideas and solutions with precision.**
    
    ## 8. Expected Outcome
    The user leaves the conversation **impressed**, equipped with fresh perspectives and a **concrete action plan** — whether it's an algorithm, a study roadmap, a business pitch, or a practical life insight.
    
    ## 9. Formatting Instructions
    When separating sections in your text, use one of these formats:
    1. Markdown headings: ## ⚡ 4. Section Name
    2. Bold text: **⚡ 4. Section Name**
    
    Never use sequences of characters like "──────────" to create visual separators, as this breaks markdown formatting.
    """

    private fun autoGenerateConversationTitle(
        conversationId: Long,
        userMessage: String,
        botResponse: String
    ) {
        // Don't generate title for new or invalid conversation
        if (conversationId == NEW_CONVERSATION_ID) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existingTitle = metadataDao.getCustomTitle(conversationId)

                if (!existingTitle.isNullOrBlank()) {
                    // Already has a custom title, no need to generate
                    Log.d(
                        "ChatViewModel",
                        "Conversation $conversationId already has a custom title: '$existingTitle'"
                    )
                    return@launch
                }

                Log.d(
                    "ChatViewModel",
                    "Generating automatic title after first interaction for conversation $conversationId"
                )

                // Build specific prompt to create title based only on the first interaction
                val promptText =
                    "Based on this first interaction, create a short descriptive title (3-4 words) for this conversation in English.\n\n" +
                            "User: $userMessage\n\n" +
                            "Assistant: ${botResponse.take(200)}\n\n" +
                            "Title (only the title, no quotes or other text):"

                // Use a lightweight model by default to save tokens
                val titleModelId = when (_selectedModel.value.provider) {
                    AIProvider.OPENAI -> "gpt-4.1-mini"
                    AIProvider.GOOGLE -> "gemini-2.0-flash"
                    AIProvider.ANTHROPIC -> "claude-3-5-sonnet-20241022"
                }

                var titleResponse = ""

                // Use the most appropriate client based on the selected model's provider
                when (_selectedModel.value.provider) {
                    AIProvider.OPENAI -> {
                        openAIClient.generateChatCompletion(
                            modelId = titleModelId,
                            systemPrompt = "You generate short descriptive titles for conversations. Respond ONLY with the title in English, without explanations or prefixes like 'Title:' or quotes.",
                            userMessage = promptText,
                            historyMessages = emptyList() // We don't need history since we have the context in the prompt
                        ).collect { chunk -> titleResponse += chunk }
                    }

                    AIProvider.GOOGLE -> {
                        googleAIClient.generateChatCompletion(
                            modelId = titleModelId,
                            systemPrompt = "You generate short descriptive titles for conversations. Respond ONLY with the title in English, without explanations or prefixes like 'Title:' or quotes.",
                            userMessage = promptText,
                            historyMessages = emptyList()
                        ).collect { chunk -> titleResponse += chunk }
                    }

                    AIProvider.ANTHROPIC -> {
                        anthropicClient.generateChatCompletion(
                            modelId = titleModelId,
                            systemPrompt = "You generate short descriptive titles for conversations. Respond ONLY with the title in English, without explanations or prefixes like 'Title:' or quotes.",
                            userMessage = promptText,
                            historyMessages = emptyList()
                        ).collect { chunk -> titleResponse += chunk }
                    }
                }

                // Clean and validate the generated title
                val cleanedTitle = titleResponse.trim()
                    .replace(Regex("^['\"](.*)['\"]$"), "$1") // Remove quotes
                    .replace(Regex("^Title: ?"), "") // Remove "Title: " prefix if it exists
                    .replace(Regex("^Theme: ?"), "") // Remove "Theme: " prefix if it exists
                    .replace("\n", " ") // Remove line breaks
                    .take(50) // Maximum character limit

                if (cleanedTitle.isNotBlank()) {
                    Log.i(
                        "ChatViewModel",
                        "Automatic title generated after first interaction: '$cleanedTitle' for conversation $conversationId"
                    )

                    // Save the custom title to the database
                    // We use insertOrUpdateMetadata which is already implemented and working
                    metadataDao.insertOrUpdateMetadata(
                        ConversationMetadataEntity(
                            conversationId = conversationId,
                            customTitle = cleanedTitle,
                            userId = _userIdFlow.value
                        )
                    )
                }

            } catch (e: Exception) {
                // In case of error, just log and continue
                Log.e("ChatViewModel", "Error generating automatic title: ${e.message}", e)
            }
        }
    }

    fun forceCheckPremiumStatus() {
        forceNextCheck = true
        checkIfUserIsPremium()
    }

    // Public method to force premium status check with options
    fun forceCheckPremiumStatus(highPriority: Boolean = false) {
        Log.d("ChatViewModel", "Forcing premium status check with high priority: $highPriority")

        viewModelScope.launch {
            try {
                val app = getApplication<Application>() as BrainstormiaApplication
                val billingVM = app.billingViewModel

                // Chama verificação forçada (o BillingViewModel já faz debounce/caching)
                billingVM.checkPremiumStatus(forceRefresh = true)

                // Espera e atualiza status local
                delay(1000)
                _isPremiumUser.value = billingVM.isPremiumUser.value

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error in forceCheckPremiumStatus: ${e.message}", e)
            }
        }
    }

    fun handleLogin() {
        Log.d("ChatViewModel", "handleLogin() called - user=${_userIdFlow.value}")

        // 🔧 REMOVIDO: _selectedModel.value = defaultModel
        // (Deixe o carregamento de preferências cuidar disso)

        _showConversations.value = true

        // Notifica BillingViewModel uma única vez
        val app = getApplication<Application>() as BrainstormiaApplication
        app.billingViewModel.handleUserChanged()

        // Force reload of conversations
        viewModelScope.launch {
            val currentUserId = getCurrentUserId()
            Log.d("ChatViewModel", "handleLogin: reloading conversations for user $currentUserId")

            // Reset the flow to force recomposition
            _userIdFlow.value = ""
            delay(50)
            _userIdFlow.value = currentUserId

            // Aguarda um pouco antes de verificar as conversas
            delay(500)

            if (conversationListForDrawer.value.isEmpty() && auth.currentUser != null) {
                Log.w("ChatViewModel", "Conversas vazias, tentando recarregar")
                refreshConversationList()
            }
        }
    }

    private suspend fun checkPremiumStatusViaBackend(): Pair<Boolean, String?> {
        return try {
            Log.d(TAG, "🔍 Verificando status premium via backend...")

            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser == null) {
                Log.w(TAG, "❌ Usuário não autenticado")
                return Pair(false, null)
            }

            val tokenResult = withTimeoutOrNull(3000) {
                currentUser.getIdToken(false).await()
            }

            if (tokenResult?.token == null) {
                Log.w(TAG, "❌ Não foi possível obter token JWT")
                return Pair(false, null)
            }

            val apiClient = ApiClient()
            val validationResponse = withTimeoutOrNull(5000) {
                apiClient.validatePremiumStatus(tokenResult.token!!)
            }

            if (validationResponse == null) {
                Log.w(TAG, "⚠️ Timeout na validação do backend")
                return Pair(false, null)
            }

            // ✅ CORREÇÃO: Usar hasAccess
            val isPremium = validationResponse.hasAccess
            val planType = validationResponse.subscriptionType

            Log.i(TAG, "✅ Backend: Premium=$isPremium, Plano=$planType")

            Pair(isPremium, planType)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erro ao verificar premium via backend: ${e.message}", e)
            Pair(false, null)
        }
    }

    // Add this method to ChatViewModel
    fun forceLoadConversationsAfterLogin() {
        viewModelScope.launch {
            Log.d("ChatViewModel", "Force loading conversations after login")

            // Ensure we're showing conversations
            _showConversations.value = true

            // Make sure we have the correct user ID
            val currentUser = FirebaseAuth.getInstance().currentUser
            val userId = currentUser?.uid

            if (userId == null) {
                Log.e("ChatViewModel", "Cannot load conversations - no user ID available")
                return@launch
            }

            // Log current and new user ID for debugging
            val previousUserId = _userIdFlow.value
            Log.d("ChatViewModel", "User ID transition: $previousUserId -> $userId")

            // Force update user ID with delay to ensure database operations complete
            _userIdFlow.value = ""
            delay(100) // This is now inside a coroutine so it works
            _userIdFlow.value = userId

            // Rest of the function...
        }
    }


    fun handleLogout() {
        startNewConversation()
        Log.d(
            "ChatViewModel",
            "[LOG] _selectedModel.value alterado para: " + (defaultModel).displayName
        );
        _selectedModel.value = defaultModel
        _clearConversationListEvent.value = true
        _showConversations.value = false

        Log.d("ChatViewModel", "Logout detectado em ChatViewModel. Definindo forceNextCheck = true")
        forceNextCheck = true
        _isPremiumUser.value = false

        viewModelScope.launch {
            delay(300)
            _clearConversationListEvent.value = false
        }
    }

    fun refreshConversationList() {
        viewModelScope.launch {
            Log.d("ChatViewModel", "Explicitly refreshing conversation list")

            // Clear and reset events
            _clearConversationListEvent.value = true
            delay(100)
            _clearConversationListEvent.value = false

            // Force reload by updating user ID flow
            val currentUserId = getCurrentUserId()
            _userIdFlow.value = ""
            delay(50)
            _userIdFlow.value = currentUserId

            // Log the current state
            Log.d("ChatViewModel", "Refreshed conversation list for user ${_userIdFlow.value}")
        }
    }

    private fun determineConversationType(title: String, id: Long): ConversationType {
        val lowercaseTitle = title.lowercase()
        return when {
            lowercaseTitle.contains("ansiedade") ||
                    lowercaseTitle.contains("medo") ||
                    lowercaseTitle.contains("preocup") -> ConversationType.EMOTIONAL

            lowercaseTitle.contains("depress") ||
                    lowercaseTitle.contains("triste") ||
                    lowercaseTitle.contains("terapia") ||
                    lowercaseTitle.contains("tratamento") -> ConversationType.THERAPEUTIC

            lowercaseTitle.contains("eu") ||
                    lowercaseTitle.contains("minha") ||
                    lowercaseTitle.contains("meu") ||
                    lowercaseTitle.contains("como me") -> ConversationType.PERSONAL

            lowercaseTitle.contains("importante") ||
                    lowercaseTitle.contains("urgente") ||
                    lowercaseTitle.contains("lembrar") -> ConversationType.HIGHLIGHTED

            else -> {
                when ((id % 5)) {
                    0L -> ConversationType.GENERAL
                    1L -> ConversationType.PERSONAL
                    2L -> ConversationType.EMOTIONAL
                    3L -> ConversationType.THERAPEUTIC
                    else -> ConversationType.HIGHLIGHTED
                }
            }
        }
    }

    private fun loadInitialConversationOrStartNew() {
        viewModelScope.launch {
            delay(150)
            _currentConversationId.value = NEW_CONVERSATION_ID
            Log.i(
                "ChatViewModel",
                "[Init] App started with new conversation (without restoring previous state)."
            )
        }
    }

    fun startNewConversation() {
        if (_currentConversationId.value != NEW_CONVERSATION_ID) {
            Log.i("ChatViewModel", "Action: Starting new conversation flow")
            _currentConversationId.value = NEW_CONVERSATION_ID
            _errorMessage.value = null
            _loadingState.value = LoadingState.IDLE
        } else {
            Log.d(
                "ChatViewModel",
                "Action: Already in new conversation flow, ignoring startNewConversation."
            )
        }
    }

    fun selectConversation(conversationId: Long) {
        if (conversationId != _currentConversationId.value && conversationId != NEW_CONVERSATION_ID) {
            Log.i("ChatViewModel", "Action: Selecting conversation $conversationId")
            _currentConversationId.value = conversationId
            _errorMessage.value = null
            _loadingState.value = LoadingState.IDLE
        } else if (conversationId == _currentConversationId.value) {
            Log.d(
                "ChatViewModel",
                "Action: Conversation $conversationId already selected, ignoring selectConversation."
            )
        } else {
            Log.w(
                "ChatViewModel",
                "Action: Attempted to select invalid NEW_CONVERSATION_ID ($conversationId), ignoring."
            )
        }
    }

    // Dentro da sua classe ChatViewModel

    // Dentro da sua classe ChatViewModel

    fun sendMessage(userMessageText: String) {
        if (userMessageText.isBlank()) {
            Log.w("ChatViewModel", "sendMessage cancelled: Empty message.")
            return
        }

        // --- LÓGICA DE COMANDO DE IMAGEM ---
        val imageCommandPrefix = "/imagine " // Defina seu prefixo. O espaço no final é útil.

        if (userMessageText.startsWith(imageCommandPrefix, ignoreCase = true)) {
            val imagePrompt = userMessageText.substring(imageCommandPrefix.length).trim()

            if (imagePrompt.isNotBlank()) {
                Log.d("ChatViewModel", "Image generation command detected. Prompt: '$imagePrompt'")

                // Chama a função de gerar imagem.
                // A função generateImage já lida com a criação de nova conversa se necessário.
                generateImage(prompt = imagePrompt)

                // Salva a mensagem de comando do usuário no banco de dados.
                viewModelScope.launch {
                    // Um pequeno delay para dar tempo a generateImage de potencialmente atualizar
                    // _currentConversationId.value se uma nova conversa foi criada.
                    delay(300)
                    val conversationIdForUserCommand = _currentConversationId.value
                    val userIdForUserCommand = _userIdFlow.value

                    if (conversationIdForUserCommand != null && conversationIdForUserCommand != NEW_CONVERSATION_ID) {
                        saveMessageToDb( // ESTA É A SUA ÚNICA FUNÇÃO saveMessageToDb
                            uiMessage = com.ivip.brainstormia.ChatMessage(
                                userMessageText,
                                Sender.USER
                            ),
                            conversationId = conversationIdForUserCommand,
                            timestamp = System.currentTimeMillis() - 500 // Um pouco antes da imagem do bot
                        )
                        Log.d(
                            "ChatViewModel",
                            "User's image command '$userMessageText' saved to conversation $conversationIdForUserCommand"
                        )
                    } else {
                        Log.w(
                            "ChatViewModel",
                            "Could not save user's image command: Invalid conversationId ($conversationIdForUserCommand) after image generation call."
                        )
                    }
                }
                // A UI (ChatScreen) é responsável por limpar o userMessage após o envio.
            } else {
                // USA A STRING RESOURCE CORRIGIDA
                _errorMessage.value = context.getString(
                    R.string.error_prompt_required_after_command,
                    imageCommandPrefix.trim()
                )
            }
            return // Importante: Não processar como mensagem de texto normal
        }
        // --- FIM DA LÓGICA DE COMANDO DE IMAGEM ---

        // VERIFICAÇÃO DE LIMITES ANTES DE PROCESSAR MENSAGEM DE TEXTO
        viewModelScope.launch {
            // Verificar limites antes de processar
            val currentModel = _selectedModel.value
            val limitCheck = checkModelUsageLimits(currentModel.apiEndpoint)

            limitCheck.fold(
                onSuccess = { canProceed ->
                    if (canProceed) {
                        // Continuar com a lógica original
                        processSendMessage(userMessageText)
                    } else {
                        // Limite excedido, erro já foi mostrado em checkModelUsageLimits
                        Log.w("ChatViewModel", "sendMessage cancelled: Usage limit exceeded")
                    }
                },
                onFailure = {
                    // Limite excedido, não enviar mensagem
                    Log.w("ChatViewModel", "sendMessage cancelled: Usage limit exceeded")
                }
            )
        }
    }

    /**
     * Processa o envio de mensagem de texto após verificação de limites
     */
    private fun processSendMessage(userMessageText: String) {
        if (_loadingState.value == LoadingState.LOADING) {
            Log.w("ChatViewModel", "processSendMessage cancelled: Already loading (text message).")
            _errorMessage.value = context.getString(R.string.error_wait_previous)
            return
        }
        _loadingState.value = LoadingState.LOADING
        _errorMessage.value = null

        val timestamp = System.currentTimeMillis()
        var targetConversationId = _currentConversationId.value
        val userId = _userIdFlow.value
        val isStartingNewConversation =
            (targetConversationId == null || targetConversationId == NEW_CONVERSATION_ID)

        if (isStartingNewConversation) {
            targetConversationId = timestamp
            Log.i(
                "ChatViewModel",
                "Action: Creating new conversation for text message with ID: $targetConversationId for user $userId"
            )
            _currentConversationId.value = targetConversationId
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    metadataDao.insertOrUpdateMetadata(
                        ConversationMetadataEntity(
                            conversationId = targetConversationId!!,
                            customTitle = null,
                            userId = userId
                        )
                    )
                    Log.d(
                        "ChatViewModel",
                        "Initial metadata saved for new conversation $targetConversationId"
                    )
                } catch (e: Exception) {
                    Log.e(
                        "ChatViewModel",
                        "Error saving initial metadata for new conv $targetConversationId",
                        e
                    )
                }
            }
        }

        if (targetConversationId == null || targetConversationId == NEW_CONVERSATION_ID) {
            Log.e(
                "ChatViewModel",
                "processSendMessage Error: Invalid targetConversationId ($targetConversationId) after new conversation logic for text message."
            )
            _errorMessage.value = context.getString(R.string.error_internal_conversation)
            _loadingState.value = LoadingState.IDLE
            return
        }

        val userUiMessage = com.ivip.brainstormia.ChatMessage(userMessageText, Sender.USER)
        saveMessageToDb(userUiMessage, targetConversationId!!, timestamp)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentMessagesFromDb =
                    chatDao.getMessagesForConversation(targetConversationId!!, userId).first()
                val historyMessages = mapEntitiesToUiMessages(currentMessagesFromDb)
                    .takeLast(MAX_HISTORY_MESSAGES)

                Log.d(
                    "ChatViewModel",
                    "API Call (Text): Sending ${historyMessages.size} messages to API for conv $targetConversationId using model ${_selectedModel.value.displayName}"
                )
                callOpenAIApi(userMessageText, historyMessages, targetConversationId!!)
            } catch (e: Exception) {
                Log.e(
                    "ChatViewModel",
                    "Error preparing history or calling text API for conv $targetConversationId",
                    e
                )
                withContext(Dispatchers.Main) {
                    _errorMessage.value =
                        context.getString(R.string.error_process_history, e.message)
                    _loadingState.value = LoadingState.ERROR
                }
            }
        }
    }

    // ... (suas outras funções: deleteConversation, renameConversation, callOpenAIApi, mappers, etc.)
    // A função saveMessageToDb que você já tem:
    private fun saveMessageToDb(
        uiMessage: com.ivip.brainstormia.ChatMessage,
        conversationId: Long,
        timestamp: Long
    ) {
        val entity = mapUiMessageToEntity(uiMessage, conversationId, timestamp)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatDao.insertMessage(entity)
                // Emitir evento aqui se a lista de mensagens na UI não estiver sendo atualizada automaticamente
                // ao salvar a mensagem do usuário ANTES da resposta do bot.
                // No entanto, o _messageAddedEvent é geralmente para após a resposta do BOT.
                // A reatividade do Flow do Room deve cuidar da mensagem do usuário.
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error saving message to DB", e)
            }
        }
    }

    fun deleteConversation(conversationId: Long) {
        if (conversationId == NEW_CONVERSATION_ID) {
            Log.w("ChatViewModel", "Attempted to delete invalid NEW_CONVERSATION_ID conversation.")
            return
        }
        Log.i("ChatViewModel", "Action: Deleting conversation $conversationId and its metadata")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatDao.clearConversation(conversationId, _userIdFlow.value)
                metadataDao.deleteMetadata(conversationId)
                Log.i(
                    "ChatViewModel",
                    "Conversation $conversationId and metadata deleted successfully from DB."
                )
                if (_currentConversationId.value == conversationId) {
                    val remainingConversations =
                        chatDao.getConversationsForUser(_userIdFlow.value).first()
                    withContext(Dispatchers.Main) {
                        val nextConversationId = remainingConversations.firstOrNull()?.id
                        if (nextConversationId != null) {
                            Log.i(
                                "ChatViewModel",
                                "Deleted current conversation, selecting next available from DB: $nextConversationId"
                            )
                            _currentConversationId.value = nextConversationId
                        } else {
                            Log.i(
                                "ChatViewModel",
                                "Deleted current conversation, no others left in DB. Starting new conversation flow."
                            )
                            _currentConversationId.value = NEW_CONVERSATION_ID
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(
                    "ChatViewModel",
                    "Error deleting conversation $conversationId or its metadata",
                    e
                )
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Erro ao excluir conversa: ${e.localizedMessage}"
                }
            }
        }
    }

    fun renameConversation(conversationId: Long, newTitle: String) {
        if (conversationId == NEW_CONVERSATION_ID) {
            Log.w("ChatViewModel", "Cannot rename NEW_CONVERSATION_ID.")
            _errorMessage.value = "Não é possível renomear uma conversa não salva."
            return
        }
        val trimmedTitle = newTitle.trim()
        if (trimmedTitle.isBlank()) {
            Log.w("ChatViewModel", "Cannot rename conversation $conversationId to blank title.")
            _errorMessage.value = "O título não pode ficar em branco."
            return
        }
        Log.i("ChatViewModel", "Action: Renaming conversation $conversationId to '$trimmedTitle'")
        val metadata = ConversationMetadataEntity(
            conversationId = conversationId,
            customTitle = trimmedTitle,
            userId = _userIdFlow.value
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                metadataDao.insertOrUpdateMetadata(metadata)
                Log.i("ChatViewModel", "Conversation $conversationId renamed successfully in DB.")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error renaming conversation $conversationId", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Erro ao renomear conversa: ${e.localizedMessage}"
                }
            }
        }
    }

    // Apenas a função modificada dentro do ChatViewModel.kt
// Substitua esta função pelo existente

    private suspend fun callOpenAIApi(
        userMessageText: String,
        historyMessages: List<ChatMessage>,
        conversationId: Long
    ) {
        try {
            val currentModel = _selectedModel.value
            Log.d(
                "ChatViewModel",
                "Starting API call with model ${currentModel.displayName} (${currentModel.provider}) for conv $conversationId"
            )

            var responseText = StringBuilder()
            var modelUsed = currentModel

            // Verifica se a mensagem tem imagem e se o modelo é compatível com visão
            val hasImageContent = userMessageText.contains("[BASE64_IMAGE]")
            val isVisionCapableModel = when (currentModel.provider) {
                AIProvider.OPENAI -> currentModel.id.contains("o") || currentModel.id.contains("vision")
                AIProvider.GOOGLE -> currentModel.id.contains("gemini")
                AIProvider.ANTHROPIC -> currentModel.id.contains("claude-3")
                else -> false
            }

            // Logar detalhes de diagnóstico para debugging
            if (hasImageContent) {
                Log.d(
                    "ChatViewModel",
                    "Mensagem contém imagem. Modelo suporta visão: $isVisionCapableModel"
                )
            }

            withContext(Dispatchers.IO) {
                try {
                    // Choose client based on provider
                    val result = when (currentModel.provider) {
                        AIProvider.OPENAI -> {
                            Log.d("ChatViewModel", "Using OpenAI client")
                            withTimeoutOrNull(200000) {
                                openAIClient.generateChatCompletion(
                                    modelId = currentModel.apiEndpoint,
                                    systemPrompt = brainstormiaSystemPrompt,
                                    userMessage = userMessageText,
                                    historyMessages = historyMessages
                                ).collect { chunk ->
                                    responseText.append(chunk)
                                }
                                true
                            }
                        }

                        AIProvider.GOOGLE -> {
                            Log.d("ChatViewModel", "Using Google client")
                            withTimeoutOrNull(200000) {
                                googleAIClient.generateChatCompletion(
                                    modelId = currentModel.apiEndpoint,
                                    systemPrompt = brainstormiaSystemPrompt,
                                    userMessage = userMessageText,
                                    historyMessages = historyMessages
                                ).collect { chunk ->
                                    responseText.append(chunk)
                                }
                                true
                            }
                        }

                        AIProvider.ANTHROPIC -> {
                            Log.d("ChatViewModel", "Using Anthropic client")
                            withTimeoutOrNull(300000) { // Give Claude more time
                                anthropicClient.generateChatCompletion(
                                    modelId = currentModel.apiEndpoint,
                                    systemPrompt = brainstormiaSystemPrompt,
                                    userMessage = userMessageText,
                                    historyMessages = historyMessages
                                ).collect { chunk ->
                                    responseText.append(chunk)
                                }
                                true
                            }
                        }
                    }

                    // Check for timeout
                    if (result == null) {
                        Log.w("ChatViewModel", "Timeout with model ${currentModel.id}")

                        // Caso de timeout com OpenAI, tentar modelo de backup
                        if (currentModel.provider == AIProvider.OPENAI && currentModel.id != "gemini-2.5-flash-preview-05-20") {
                            responseText.clear()
                            Log.w("ChatViewModel", "Using backup model (Gemini )")

                            val backupModel =
                                availableModels.first { it.id == "gemini-2.5-flash-preview-05-20" }
                            modelUsed = backupModel

                            val backupResult = withTimeoutOrNull(60000) {
                                openAIClient.generateChatCompletion(
                                    modelId = backupModel.apiEndpoint,
                                    systemPrompt = brainstormiaSystemPrompt,
                                    userMessage = userMessageText,
                                    historyMessages = historyMessages
                                ).collect { chunk ->
                                    responseText.append(chunk)
                                }
                                true
                            }

                            if (backupResult == null) {
                                throw Exception("Timeout in API call (second attempt)")
                            }
                        } else {
                            throw Exception("Timeout in API call")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Error in API call: ${e.message}")
                    throw e
                }
            }

            // Response processing
            val finalResponse = responseText.toString()
            if (finalResponse.isNotBlank()) {
                Log.d(
                    "ChatViewModel",
                    "API response received for conv $conversationId (${finalResponse.length} characters)"
                )

                val botMessageEntity = ChatMessageEntity(
                    id = 0,
                    conversationId = conversationId,
                    text = finalResponse,
                    sender = Sender.BOT.name,
                    timestamp = System.currentTimeMillis(),
                    userId = _userIdFlow.value
                )

                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        chatDao.insertMessage(botMessageEntity)
                        Log.d("ChatViewModel", "Bot message saved to database")
                        if (historyMessages.size <= 1 ||
                            (conversationId != NEW_CONVERSATION_ID && historyMessages.count { it.sender == Sender.USER } == 0)
                        ) {
                            // It's the first interaction, generate automatic title
                            autoGenerateConversationTitle(
                                conversationId,
                                userMessageText,
                                finalResponse
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Error saving bot message to database", e)
                    }
                }
            } else {
                Log.w("ChatViewModel", "Empty response from API for conv $conversationId")
                withContext(Dispatchers.Main) {
                    _errorMessage.value = context.getString(R.string.error_empty_response)
                }
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error in API call for conv $conversationId", e)
            withContext(Dispatchers.Main) {
                if (e.message?.contains("Timeout") == true) {
                    _errorMessage.value = context.getString(R.string.error_timeout)
                } else {
                    _errorMessage.value =
                        context.getString(R.string.error_ai_communication, e.localizedMessage)
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                _loadingState.value = LoadingState.IDLE
            }
        }
    }

    private fun mapEntitiesToUiMessages(entities: List<ChatMessageEntity>): List<com.ivip.brainstormia.ChatMessage> {
        return entities.mapNotNull { entity ->
            try {
                val sender = enumValueOf<Sender>(entity.sender.uppercase())
                com.ivip.brainstormia.ChatMessage(entity.text, sender)
            } catch (e: IllegalArgumentException) {
                Log.e(
                    "ChatViewModelMapper",
                    "Invalid sender string in DB: ${entity.sender}. Skipping message ID ${entity.id}."
                )
                null
            }
        }
    }

    private fun mapUiMessageToEntity(
        message: com.ivip.brainstormia.ChatMessage,
        conversationId: Long,
        timestamp: Long
    ): ChatMessageEntity {
        return ChatMessageEntity(
            conversationId = conversationId,
            text = message.text,
            sender = message.sender.name,
            timestamp = timestamp,
            userId = _userIdFlow.value
        )
    }

    private fun generateFallbackTitleSync(conversationId: Long): String {
        return try {
            runCatching {
                runBlocking {
                    generateFallbackTitle(conversationId)
                }
            }.getOrElse { ex ->
                Log.e(
                    "ChatViewModel",
                    "Error generating fallback title synchronously for conv $conversationId",
                    ex
                )
                "Conversa $conversationId"
            }
        } catch (e: Exception) {
            "Conversa $conversationId"
        }
    }

    private suspend fun generateFallbackTitle(conversationId: Long): String =
        withContext(Dispatchers.IO) {
            try {
                val firstUserMessageText =
                    chatDao.getFirstUserMessageText(conversationId, _userIdFlow.value)
                if (!firstUserMessageText.isNullOrBlank()) {
                    Log.d(
                        "ChatViewModel",
                        "Generating fallback title for $conversationId using first message."
                    )
                    return@withContext firstUserMessageText.take(30) + if (firstUserMessageText.length > 30) "..." else ""
                } else {
                    try {
                        Log.d(
                            "ChatViewModel",
                            "Generating fallback title for $conversationId using date."
                        )
                        return@withContext "Conversa ${titleDateFormatter.format(Date(conversationId))}"
                    } catch (formatException: Exception) {
                        Log.w(
                            "ChatViewModel",
                            "Could not format conversationId $conversationId as Date for fallback title.",
                            formatException
                        )
                        return@withContext "Conversa $conversationId"
                    }
                }
            } catch (dbException: Exception) {
                Log.e(
                    "ChatViewModel",
                    "Error generating fallback title for conv $conversationId",
                    dbException
                )
                return@withContext "Conversa $conversationId"
            }
        }

    suspend fun getDisplayTitle(conversationId: Long): String {
        return withContext(Dispatchers.IO) {
            if (conversationId == NEW_CONVERSATION_ID) {
                "Nova Conversa"
            } else {
                try {
                    val customTitle = metadataDao.getCustomTitle(conversationId)
                    if (!customTitle.isNullOrBlank()) {
                        Log.d(
                            "ChatViewModel",
                            "Using custom title for $conversationId: '$customTitle'"
                        )
                        customTitle
                    } else {
                        generateFallbackTitle(conversationId)
                    }
                } catch (dbException: Exception) {
                    Log.e(
                        "ChatViewModel",
                        "Error fetching title data for conv $conversationId",
                        dbException
                    )
                    "Conversa $conversationId"
                }
            }
        }
    }


    companion object {
        private val titleDateFormatter = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    }
}