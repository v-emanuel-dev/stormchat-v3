package com.ivip.brainstormia.file

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Implementação para arquivos de imagem
 */
class ImageFileProcessor : FileProcessor {
    override fun canProcess(mimeType: String): Boolean {
        return mimeType.startsWith("image/")
    }

    override suspend fun processFile(file: File, mimeType: String, context: Context): String =
        withContext(Dispatchers.IO) {
            return@withContext try {
                // Carregar a imagem como bitmap
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)

                if (bitmap == null) {
                    return@withContext "Não foi possível decodificar a imagem: ${file.name}"
                }

                // Extrair informações básicas da imagem
                val width = bitmap.width
                val height = bitmap.height
                val aspectRatio = width.toFloat() / height.toFloat()
                val hasAlpha = bitmap.hasAlpha()

                // Converter para Base64 se necessário (para modelos que aceitam imagens)
                val base64Image = if (file.length() < 3 * 1024 * 1024) { // Aumentar para 3MB
                    convertBitmapToBase64(bitmap, mimeType)
                } else {
                    null
                }

                // Construir descrição da imagem
                val description = StringBuilder()
                description.append("Arquivo de Imagem: ${file.name}\n")
                description.append("Tipo: $mimeType\n")
                description.append("Dimensões: ${width}x${height} pixels\n")
                description.append("Proporção: ${"%.2f".format(aspectRatio)}\n")
                description.append("Canal Alpha: ${if (hasAlpha) "Sim" else "Não"}\n")
                description.append("Tamanho: ${formatFileSize(file.length())}\n\n")

                // Executar análise de conteúdo da imagem
                val imageContent = analyzeImageContent(bitmap)
                description.append("\nAnálise de Conteúdo:\n")
                description.append(imageContent)

                // Adicionar código para descritivo com base64 se disponível
                if (base64Image != null) {
                    description.append("\n[BASE64_IMAGE]$base64Image[/BASE64_IMAGE]\n\n")
                    description.append("A imagem também foi codificada em base64 no marcador acima, caso o modelo possa processá-la diretamente.\n")
                } else {
                    description.append("\nA imagem é muito grande para ser codificada em base64. Apenas os metadados estão disponíveis.\n")
                }

                description.toString()
            } catch (e: Exception) {
                Log.e("ImageProcessor", "Erro ao processar imagem", e)
                "Erro ao processar imagem: ${e.message}"
            }
        }

    override suspend fun processUri(uri: Uri, mimeType: String, context: Context): String =
        withContext(Dispatchers.IO) {
            return@withContext try {
                // Carregar a imagem como bitmap da URI
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap == null) {
                    return@withContext "Não foi possível decodificar a imagem da URI"
                }

                // Extrair informações básicas da imagem
                val width = bitmap.width
                val height = bitmap.height
                val aspectRatio = width.toFloat() / height.toFloat()
                val hasAlpha = bitmap.hasAlpha()

                // Calcular tamanho aproximado
                val byteCount = bitmap.byteCount

                // Converter para Base64 se necessário (para modelos que aceitam imagens)
                val base64Image = if (byteCount < 3 * 1024 * 1024) { // Aumentar para 3MB
                    convertBitmapToBase64(bitmap, mimeType)
                } else {
                    null
                }

                // Construir descrição da imagem
                val description = StringBuilder()
                description.append("Arquivo de Imagem da URI: $uri\n")
                description.append("Tipo: $mimeType\n")
                description.append("Dimensões: ${width}x${height} pixels\n")
                description.append("Proporção: ${"%.2f".format(aspectRatio)}\n")
                description.append("Canal Alpha: ${if (hasAlpha) "Sim" else "Não"}\n")
                description.append("Tamanho em memória: ${formatFileSize(byteCount.toLong())}\n\n")

                // Executar análise de conteúdo da imagem
                val imageContent = analyzeImageContent(bitmap)
                description.append("\nAnálise de Conteúdo:\n")
                description.append(imageContent)

                // Adicionar código para descritivo com base64 se disponível
                if (base64Image != null) {
                    description.append("\n[BASE64_IMAGE]$base64Image[/BASE64_IMAGE]\n\n")
                    description.append("A imagem também foi codificada em base64 no marcador acima, caso o modelo possa processá-la diretamente.\n")
                } else {
                    description.append("\nA imagem é muito grande para ser codificada em base64. Apenas os metadados estão disponíveis.\n")
                }

                description.toString()
            } catch (e: Exception) {
                Log.e("ImageProcessor", "Erro ao processar imagem da URI", e)
                "Erro ao processar imagem: ${e.message}"
            }
        }

    private fun convertBitmapToBase64(bitmap: Bitmap, mimeType: String): String {
        try {
            // Redimensionar e preparar a imagem
            val maxDimension = 768  // Reduzido para 768px
            val resizedBitmap = resizeBitmapIfNeeded(bitmap, maxDimension)

            val outputStream = ByteArrayOutputStream()
            val format = when {
                mimeType.contains("png") -> Bitmap.CompressFormat.PNG
                mimeType.contains("webp") && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R ->
                    Bitmap.CompressFormat.WEBP_LOSSY
                else -> Bitmap.CompressFormat.JPEG
            }

            // Reduzir qualidade para garantir tamanho menor
            val quality = 70  // Qualidade reduzida para 70%
            resizedBitmap.compress(format, quality, outputStream)

            val byteArray = outputStream.toByteArray()
            // Usar NO_WRAP para evitar quebras de linha no base64
            val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)

            // Garantir que o tipo MIME esteja correto
            val actualMimeType = when(format) {
                Bitmap.CompressFormat.JPEG -> "image/jpeg"
                Bitmap.CompressFormat.PNG -> "image/png"
                else -> mimeType
            }

            val result = "data:$actualMimeType;base64,$base64"

            // Log para debugging do tamanho
            Log.d("ImageProcessor", "Base64 gerado com sucesso: ${result.length} bytes")

            return result
        } catch (e: Exception) {
            Log.e("ImageProcessor", "Erro ao converter bitmap para base64", e)
            return ""
        }
    }

    // Adicione esta nova função para redimensionar bitmaps
    private fun resizeBitmapIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDimension && height <= maxDimension) {
            return bitmap  // Já está pequeno o suficiente
        }

        val ratio = maxDimension.toFloat() / Math.max(width, height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return "%.2f %s".format(size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    // Função de análise de conteúdo de imagem usando ML Kit
    // Função de análise de conteúdo de imagem usando ML Kit
    private suspend fun analyzeImageContent(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val results = StringBuilder()

        try {
            // Inicializar o detector de rótulos
            val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

            // Converter bitmap para InputImage
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            // Processar imagem e obter resultados
            val task = labeler.process(inputImage)
            val labels = Tasks.await(task)

            if (labels.isNotEmpty()) {
                results.append("Conteúdo detectado na imagem:\n")

                // Limitar a 5 rótulos com maior confiança
                val topLabels = labels.sortedByDescending { it.confidence }.take(5)

                for (label in topLabels) {
                    val text = label.text
                    val confidence = label.confidence * 100
                    results.append("- $text (confiança: ${String.format("%.1f", confidence)}%)\n")
                }
            } else {
                results.append("Nenhum conteúdo específico identificado na imagem.")
            }
        } catch (e: Exception) {
            Log.e("ImageProcessor", "Erro na análise de imagem", e)
            results.append("Erro ao analisar o conteúdo da imagem: ${e.message}")
        }

        return@withContext results.toString()
    }
}