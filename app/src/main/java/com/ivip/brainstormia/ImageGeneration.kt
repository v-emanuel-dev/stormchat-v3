package com.ivip.brainstormia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.aallam.openai.api.image.ImageCreation
import com.aallam.openai.api.image.ImageSize
import com.aallam.openai.api.model.ModelId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Classe para representar os diferentes estados durante geração de imagens
sealed class ImageGenerationResult {
    data class Loading(val message: String) : ImageGenerationResult()
    data class Success(val imagePath: String, val imageUri: Uri) : ImageGenerationResult()
    data class Error(val message: String) : ImageGenerationResult()
}

// Gerenciador responsável pela geração e salvamento de imagens
class ImageGenerationManager(private val context: Context) {
    companion object {
        private const val TAG = "ImageGeneration"
    }

    /**
     * Generate an image using the OpenAI API
     */
    suspend fun generateImage(
        openAIClient: OpenAIClient,  // Adicionado parâmetro do cliente
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
                val result = openAIClient.openAI.imageJSON(imageRequest)

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

    suspend fun generateAndSaveImage(
        openAIClient: OpenAIClient,
        prompt: String,
        quality: String = "standard",
        size: String = "1024x1024",
        transparent: Boolean = false,
        isPremiumUser: Boolean = false,
        modelId: String = ""
    ): Flow<ImageGenerationResult> = flow {
        emit(ImageGenerationResult.Loading("Iniciando geração de imagem..."))

        try {
            // Use the OpenAIClient to generate the image
            var imageData: String? = null

            // Atualizado para passar o cliente como primeiro parâmetro
            generateImage(
                openAIClient, prompt, quality, size, null, transparent, isPremiumUser, modelId
            ).collect { chunk ->
                if (chunk.startsWith("URL:")) {
                    imageData = chunk.substring(4)
                    emit(ImageGenerationResult.Loading("Baixando imagem da URL..."))
                } else if (chunk.startsWith("data:image")) {
                    imageData = chunk
                    emit(ImageGenerationResult.Loading("Processando dados da imagem..."))
                } else if (chunk.startsWith("RESPONSE:")) {
                    // Código existente para RESPONSE...
                } else if (chunk.startsWith("iVBORw0")) {
                    // NOVO: Tratar resposta de base64 puro
                    imageData = "data:image/png;base64," + chunk
                    emit(ImageGenerationResult.Loading("Processando dados da imagem base64..."))
                } else {
                    // Update loading status
                    emit(ImageGenerationResult.Loading(chunk))
                }
            }

            if (imageData == null) {
                throw IOException("Nenhum dado de imagem recebido da API")
            }

            // Process image data (URL or base64)
            val bitmap = if (imageData!!.startsWith("http")) {
                // Download from URL
                emit(ImageGenerationResult.Loading("Baixando imagem..."))
                downloadImageFromUrl(imageData!!)
            } else if (imageData!!.startsWith("data:image")) {
                // Process base64
                emit(ImageGenerationResult.Loading("Decodificando imagem..."))
                decodeBase64Image(imageData!!)
            } else {
                throw IOException("Formato de dados de imagem não reconhecido")
            }

            // Save to internal storage
            emit(ImageGenerationResult.Loading("Salvando imagem..."))
            val imageFile = saveImageToInternalStorage(bitmap)

            val imageUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile
            )

            Log.d(TAG, "Image saved successfully at: ${imageFile.absolutePath}")
            emit(ImageGenerationResult.Success(imageFile.absolutePath, imageUri))

        } catch (e: Exception) {
            Log.e(TAG, "Error in image generation and saving", e)
            emit(ImageGenerationResult.Error("Erro: ${e.message ?: "Erro desconhecido"}"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun saveImageToInternalStorage(bitmap: Bitmap): File = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "IMG_${timestamp}.png"

        val imagesDir = File(context.filesDir, "generated_images").apply {
            if (!exists()) mkdir()
        }

        val imageFile = File(imagesDir, fileName)

        FileOutputStream(imageFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }

        return@withContext imageFile
    }

    private suspend fun downloadImageFromUrl(imageUrl: String): Bitmap = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading image from URL: $imageUrl")
            val connection = URL(imageUrl).openConnection()
            connection.connect()
            val inputStream = connection.getInputStream()
            val bitmap = BitmapFactory.decodeStream(inputStream)
            if (bitmap == null) {
                throw IOException("Failed to decode bitmap from URL")
            }
            return@withContext bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading image from URL", e)
            throw IOException("Failed to download image: ${e.message}")
        }
    }

    private fun decodeBase64Image(base64String: String): Bitmap {
        try {
            Log.d(TAG, "Decoding base64 image data")
            // Remove the data:image/jpeg;base64, part if present
            val base64Data = if (base64String.contains(",")) {
                base64String.split(",")[1]
            } else {
                base64String
            }

            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            if (bitmap == null) {
                throw IOException("Failed to decode bitmap from base64 data")
            }
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding base64 image", e)
            throw IOException("Failed to decode base64 image: ${e.message}")
        }
    }
}