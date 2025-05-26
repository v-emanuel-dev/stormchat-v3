package com.ivip.brainstormia.file

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Implementação para arquivos Word (DOCX)
 * Usa uma abordagem simples para extrair texto de arquivos DOCX
 */
class WordFileProcessor : FileProcessor {
    override fun canProcess(mimeType: String): Boolean {
        return mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || // docx
                mimeType == "application/msword" // doc
    }

    override suspend fun processFile(file: File, mimeType: String, context: Context): String =
        withContext(Dispatchers.IO) {
            return@withContext try {
                // Preparar a descrição básica
                val fileSize = file.length()
                val description = StringBuilder()
                description.append("Documento Word: ${file.name}\n")
                description.append("Tipo: $mimeType\n")
                description.append("Tamanho: ${formatFileSize(fileSize)}\n\n")

                // Extrair o texto baseado no tipo de arquivo
                if (mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document") {
                    // DOCX é um arquivo ZIP com XMLs dentro
                    val extractedText = extractTextFromDocx(file)

                    if (extractedText.isNotBlank()) {
                        // Truncar texto se for muito longo
                        val maxChars = 100000
                        if (extractedText.length > maxChars) {
                            description.append("Conteúdo extraído (primeiros $maxChars caracteres):\n\n")
                            description.append(extractedText.substring(0, maxChars))
                            description.append("\n\n... (texto truncado devido ao tamanho)")
                        } else {
                            description.append("Conteúdo extraído:\n\n")
                            description.append(extractedText)
                        }
                    } else {
                        description.append("Não foi possível extrair texto deste documento DOCX.")
                    }
                } else {
                    // Para arquivos DOC (formato antigo)
                    description.append("Nota: O formato DOC (Word antigo) não é suportado nesta versão do aplicativo.\n")
                    description.append("Para melhor compatibilidade, recomendamos salvar o documento no formato DOCX ou convertê-lo para TXT.")
                }

                description.toString()

            } catch (e: Exception) {
                Log.e("WordProcessor", "Erro ao processar arquivo Word", e)
                "Erro ao extrair texto do documento Word: ${e.message}"
            }
        }

    override suspend fun processUri(uri: Uri, mimeType: String, context: Context): String =
        withContext(Dispatchers.IO) {
            return@withContext try {
                // Obter tamanho do arquivo da URI
                var fileSize = 0L
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex("_size")
                    if (sizeIndex != -1 && cursor.moveToFirst()) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }

                // Preparar a descrição básica
                val description = StringBuilder()
                description.append("Documento Word da URI: ${uri.lastPathSegment ?: "desconhecido"}\n")
                description.append("Tipo: $mimeType\n")
                description.append("Tamanho: ${formatFileSize(fileSize)}\n\n")

                // Extrair o texto baseado no tipo de arquivo
                if (mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document") {
                    // Para DOCX, precisamos criar um arquivo temporário a partir da URI
                    val tempFile = File(context.cacheDir, "temp_docx_${System.currentTimeMillis()}.docx")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    val extractedText = extractTextFromDocx(tempFile)

                    // Limpar o arquivo temporário
                    tempFile.delete()

                    if (extractedText.isNotBlank()) {
                        // Truncar texto se for muito longo
                        val maxChars = 100000
                        if (extractedText.length > maxChars) {
                            description.append("Conteúdo extraído (primeiros $maxChars caracteres):\n\n")
                            description.append(extractedText.substring(0, maxChars))
                            description.append("\n\n... (texto truncado devido ao tamanho)")
                        } else {
                            description.append("Conteúdo extraído:\n\n")
                            description.append(extractedText)
                        }
                    } else {
                        description.append("Não foi possível extrair texto deste documento DOCX.")
                    }
                } else {
                    // Para arquivos DOC (formato antigo)
                    description.append("Nota: O formato DOC (Word antigo) não é suportado nesta versão do aplicativo.\n")
                    description.append("Para melhor compatibilidade, recomendamos salvar o documento no formato DOCX ou convertê-lo para TXT.")
                }

                description.toString()

            } catch (e: Exception) {
                Log.e("WordProcessor", "Erro ao processar arquivo Word da URI", e)
                "Erro ao extrair texto do documento Word: ${e.message}"
            }
        }

    private fun extractTextFromDocx(file: File): String {
        val result = StringBuilder()

        try {
            ZipInputStream(FileInputStream(file)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        // Encontramos o arquivo XML principal do documento
                        val content = readZipEntry(zipIn)

                        // Extrair texto do XML usando expressões regulares
                        val regex = "<w:t[^>]*>(.*?)</w:t>".toRegex(RegexOption.DOT_MATCHES_ALL)
                        val matches = regex.findAll(content)

                        for (match in matches) {
                            val text = match.groupValues[1]
                            result.append(text)
                        }

                        break // Podemos sair do loop depois de processar document.xml
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e("WordProcessor", "Erro ao extrair texto do DOCX", e)
        }

        return result.toString()
    }

    /**
     * Lê o conteúdo de uma entrada ZIP e converte para string
     */
    private fun readZipEntry(zipIn: ZipInputStream): String {
        val buffer = ByteArray(8192)
        val outputStream = ByteArrayOutputStream()

        var len: Int
        while (zipIn.read(buffer).also { len = it } > 0) {
            outputStream.write(buffer, 0, len)
        }

        return String(outputStream.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return "%.2f %s".format(size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}