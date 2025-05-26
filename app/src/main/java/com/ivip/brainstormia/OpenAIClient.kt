package com.ivip.brainstormia

import android.util.Log
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.image.ImageCreation
import com.aallam.openai.api.image.ImageSize
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.aallam.openai.api.chat.ChatMessage as OpenAIChatMessage

/**
 * Client for communicating with the OpenAI API
 */
class OpenAIClient(private val apiKey: String) {

    val openAI: OpenAI
    private val client: OkHttpClient

    init {
        val config = OpenAIConfig(
            token = apiKey
        )
        openAI = OpenAI(config)
        client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        Log.d(TAG, "OpenAIClient initialized")
    }

    /**
     * Generates a chat response using the OpenAI API with streaming
     */
    suspend fun generateChatCompletion(
        modelId: String,
        systemPrompt: String,
        userMessage: String,
        historyMessages: List<com.ivip.brainstormia.ChatMessage>
    ): Flow<String> = flow {
        try {
            // Verificar se estamos usando um modelo com capacidade de visão
            val isVisionModel = modelId.contains("o") || modelId.contains("vision")
            val hasImageContent = userMessage.contains("[BASE64_IMAGE]")

            if (isVisionModel && hasImageContent) {
                Log.d(TAG, "Usando modo de visão para o modelo $modelId")
                // Usar o método para mensagens multimodais, passando 'emit' como argumento
                handleMultimodalRequest(modelId, systemPrompt, userMessage, historyMessages) { value ->
                    emit(value)
                }
                return@flow
            }

            // Código original para modelos sem capacidade de visão
            // Convert messages from application format to OpenAI format
            val openAIMessages = mutableListOf<OpenAIChatMessage>()

            // Add system prompt
            openAIMessages.add(OpenAIChatMessage(
                role = ChatRole.System,
                content = systemPrompt
            ))

            // Add history messages (limited to MAX_HISTORY_MESSAGES)
            val recentMessages = historyMessages.takeLast(MAX_HISTORY_MESSAGES)

            for (message in recentMessages) {
                val role = if (message.sender == Sender.USER) ChatRole.User else ChatRole.Assistant
                openAIMessages.add(OpenAIChatMessage(
                    role = role,
                    content = message.text
                ))
            }

            // Add current user message
            openAIMessages.add(OpenAIChatMessage(
                role = ChatRole.User,
                content = userMessage
            ))

            Log.d(TAG, "Sending ${openAIMessages.size} messages to OpenAI using model $modelId")

            // Create request
            val chatCompletionRequest = ChatCompletionRequest(
                model = ModelId(modelId),
                messages = openAIMessages
            )

            var responseText = StringBuilder()

            // API call with streaming
            openAI.chatCompletions(chatCompletionRequest).collect { completion ->
                completion.choices.firstOrNull()?.delta?.content?.let { chunk ->
                    if (chunk.isNotEmpty()) {
                        responseText.append(chunk)
                        emit(chunk)
                    }
                }
            }

            Log.d(TAG, "Complete response from OpenAI: ${responseText.length} characters")

        } catch (e: Exception) {
            Log.e(TAG, "Error in generateChatCompletion", e)
            throw e
        }
    }

    /**
     * Handles multimodal requests with both text and images
     */
    private suspend fun handleMultimodalRequest(
        modelId: String,
        systemPrompt: String,
        userMessage: String,
        historyMessages: List<com.ivip.brainstormia.ChatMessage>,
        emit: suspend (String) -> Unit
    ) {
        try {
            // Extrair a base64 da imagem - MODIFICADO PARA DEPURAÇÃO
            val regex = "\\[BASE64_IMAGE\\](.*?)\\[/BASE64_IMAGE\\]".toRegex()
            val match = regex.find(userMessage)

            // Adicionar mais logs para debugar
            Log.d(TAG, "Tentando extrair base64 da mensagem, tamanho: ${userMessage.length}")

            if (match == null) {
                Log.e(TAG, "Padrão [BASE64_IMAGE] não encontrado na mensagem")
                throw Exception("Formato de imagem inválido")
            }

            // Verificar a estrutura do base64 encontrado
            val imageBase64 = match.groupValues[1]
            Log.d(TAG, "Encontrado base64 de tamanho: ${imageBase64.length}")

            // Verificar se o base64 tem o formato correto e consertar se necessário
            val validBase64 = if (!imageBase64.startsWith("data:image")) {
                // Se não começar com "data:image", assumir que é uma imagem JPEG
                Log.d(TAG, "Base64 sem prefixo de tipo MIME, adicionando prefixo")
                "data:image/jpeg;base64,$imageBase64"
            } else {
                // Já tem o prefixo correto
                imageBase64
            }

            Log.d(TAG, "Base64 validado: ${validBase64.substring(0, Math.min(50, validBase64.length))}...")

            val textPart = userMessage.replace(regex, "").trim()

            // Construir o JSON para a requisição multimodal
            val messagesArray = JSONArray()

            // Adicionar prompt de sistema
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })

            // Adicionar histórico de mensagens
            val recentMessages = historyMessages.takeLast(MAX_HISTORY_MESSAGES)
            for (message in recentMessages) {
                val role = if (message.sender == Sender.USER) "user" else "assistant"
                messagesArray.put(JSONObject().apply {
                    put("role", role)
                    put("content", message.text)
                })
            }

            // Adicionar mensagem do usuário com conteúdo multimodal
            val contentArray = JSONArray()

            // Adicionar parte de texto
            if (textPart.isNotBlank()) {
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", textPart)
                })
            }

            // Adicionar parte da imagem com o base64 validado
            contentArray.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", validBase64)
                })
            })

            messagesArray.put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            })

            val jsonBody = JSONObject().apply {
                put("model", modelId)
                put("messages", messagesArray)
                put("stream", true)
                put("max_tokens", 4096)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()

            Log.d(TAG, "Enviando requisição multimodal para OpenAI")

            // Fazer a requisição
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "Erro na API OpenAI: ${response.code} - $errorBody")
                    throw Exception("Erro ao processar imagem: ${response.code}")
                }

                val responseBody = response.body
                if (responseBody != null) {
                    val reader = responseBody.charStream().buffered()
                    var line: String?
                    var responseText = StringBuilder()

                    // Processar resposta em streaming
                    while (reader.readLine().also { line = it } != null) {
                        if (line!!.startsWith("data: ")) {
                            val data = line!!.substring(6)
                            if (data == "[DONE]") break

                            try {
                                val jsonObj = JSONObject(data)
                                val choices = jsonObj.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    if (delta != null) {
                                        val content = delta.optString("content")
                                        if (content.isNotEmpty()) {
                                            responseText.append(content)
                                            emit(content)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Erro ao processar chunk: $data", e)
                            }
                        }
                    }

                    Log.d(TAG, "Resposta multimodal completa: ${responseText.length} caracteres")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar requisição multimodal: ${e.message}", e)
            emit("Erro ao processar a imagem: ${e.message ?: "Formato de imagem inválido"}")
            throw e
        }
    }

    /**
     * Generate an image using the OpenAI API
     */
    suspend fun generateImage(
        prompt: String,
        quality: String = "standard",
        size: String = "1024x1024",
        outputPath: String? = null,
        transparent: Boolean = false,
        isPremiumUser: Boolean = false,
        modelId: String = "" // Novo parâmetro com valor padrão vazio
    ): Flow<String> = flow {
        try {
            emit("Gerando imagem...")
            Log.d(TAG, "Generating image with prompt: $prompt, premium user: $isPremiumUser, model: ${modelId.ifEmpty { "default" }}")

            // Determine o modelo a usar
            val effectiveModelId = if (modelId.isNotEmpty()) {
                // Usar o modelo especificado se foi fornecido
                modelId
            } else {
                // Lista de modelos de fallback se nenhum modelo específico foi fornecido
                val modelIdsToTry = listOf(
                    "dall-e-3",      // Try DALL-E 3 if available (premium)
                    "dall-e-2",      // Fallback to DALL-E 2
                )
                // Escolher o primeiro modelo da lista como padrão
                modelIdsToTry.first()
            }

            Log.d(TAG, "Using model: $effectiveModelId for image generation")
            emit("Usando modelo: ${effectiveModelId}...")

            try {
                // Criar a requisição para o modelo específico
                val imageRequest = ImageCreation(
                    prompt = prompt,
                    model = ModelId(effectiveModelId),
                    n = 1,
                    size = if (effectiveModelId == "dall-e-3") ImageSize("1024x1024") else null
                )

                // API call
                emit("Baixando imagem gerada...")
                val result = openAI.imageJSON(imageRequest)

                if (result.isNotEmpty()) {
                    // First result (we only asked for 1 image)
                    val image = result.first()

                    // Log the entire response for debugging
                    val imageString = image.toString()
                    Log.d(TAG, "Image response from $effectiveModelId: $imageString")

                    // Try different methods to extract the data
                    // Method 1: Look for base64 data in the toString() output
                    val base64Pattern = "data:image/[^\"']+".toRegex()
                    val base64Match = base64Pattern.find(imageString)
                    if (base64Match != null) {
                        val match = base64Match.value
                        Log.d(TAG, "Found base64 data in response: ${match.take(50)}...")
                        emit(match)
                        return@flow
                    }

                    // Method 2: Try to access fields via reflection
                    for (field in image::class.java.declaredFields) {
                        field.isAccessible = true
                        try {
                            val fieldValue = field.get(image)
                            if (fieldValue is String) {
                                if (fieldValue.startsWith("data:image")) {
                                    Log.d(TAG, "Found base64 data via reflection in field ${field.name}")
                                    emit(fieldValue)
                                    return@flow
                                } else if (fieldValue.length > 1000) {
                                    Log.d(TAG, "Found possible base64 data (long string) in field ${field.name}")
                                    emit(fieldValue)
                                    return@flow
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error accessing field ${field.name}", e)
                        }
                    }

                    // Method 3: Look for URL pattern in the response
                    val urlPattern = "https?://[^\"'\\s]+".toRegex()
                    val urlMatch = urlPattern.find(imageString)
                    if (urlMatch != null) {
                        val url = urlMatch.value
                        Log.d(TAG, "Found URL in response: $url")
                        emit("URL:$url")
                        return@flow
                    }

                    // Method 4: Check for specific known fields by name
                    for (fieldName in listOf("url", "b64_json", "b64Json", "base64", "data")) {
                        try {
                            val field = image::class.java.getDeclaredField(fieldName)
                            field.isAccessible = true
                            val fieldValue = field.get(image)
                            if (fieldValue != null && fieldValue is String && fieldValue.isNotEmpty()) {
                                Log.d(TAG, "Found data in field '$fieldName': ${fieldValue.take(50)}...")
                                if (fieldName == "url") {
                                    emit("URL:$fieldValue")
                                } else {
                                    emit(fieldValue)
                                }
                                return@flow
                            }
                        } catch (e: NoSuchFieldException) {
                            // Field doesn't exist, continue to next one
                        } catch (e: Exception) {
                            Log.e(TAG, "Error accessing field $fieldName", e)
                        }
                    }

                    // Method 5: As last resort, return the full stringified response
                    Log.d(TAG, "Could not extract image data properly, returning full response")
                    emit("RESPONSE:$imageString")
                    return@flow
                } else {
                    Log.w(TAG, "Empty result from model $effectiveModelId")
                    throw Exception("Resposta vazia do modelo $effectiveModelId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error with model $effectiveModelId", e)
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating image", e)
            throw Exception("Failed to generate image: ${e.message ?: "Unknown error"}")
        }
    }

    companion object {
        private const val TAG = "OpenAIClient"
        private const val MAX_HISTORY_MESSAGES = 20
    }
}