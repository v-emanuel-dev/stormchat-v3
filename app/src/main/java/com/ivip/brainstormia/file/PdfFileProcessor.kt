package com.ivip.brainstormia.file

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Implementação para arquivos PDF
 * Usa PDFBox para Android para extrair o texto
 */
class PdfFileProcessor : FileProcessor {
    override fun canProcess(mimeType: String): Boolean {
        return mimeType == "application/pdf"
    }

    override suspend fun processFile(file: File, mimeType: String, context: Context): String =
        withContext(Dispatchers.IO) {
            return@withContext try {
                // Inicializar PDFBox no contexto (necessário apenas uma vez)
                PDFBoxResourceLoader.init(context)

                val inputStream = FileInputStream(file)
                val document = PDDocument.load(inputStream)

                val pageCount = document.numberOfPages
                val fileSize = file.length()

                // Extrair informações do PDF
                val result = StringBuilder()
                result.append("Arquivo PDF: ${file.name}\n")
                result.append("Páginas: $pageCount\n")
                result.append("Tamanho: ${formatFileSize(fileSize)}\n\n")

                // Extrair texto do PDF (até 50 páginas para não sobrecarregar)
                val maxPages = minOf(pageCount, 50)
                val textStripper = PDFTextStripper()

                for (i in 1..maxPages) {
                    textStripper.startPage = i
                    textStripper.endPage = i
                    val pageText = textStripper.getText(document)

                    // Limitar o texto de cada página (máximo 1000 caracteres)
                    val truncatedText = if (pageText.length > 1000) {
                        pageText.substring(0, 1000) + "... (texto truncado)"
                    } else {
                        pageText
                    }

                    result.append("Página $i:\n")
                    result.append(truncatedText)
                    result.append("\n---\n")
                }

                if (maxPages < pageCount) {
                    result.append("\nNota: ${pageCount - maxPages} páginas adicionais não exibidas.\n")
                }

                document.close()
                inputStream.close()

                result.toString()
            } catch (e: Exception) {
                Log.e("PdfProcessor", "Erro ao processar PDF", e)
                "Erro ao processar arquivo PDF: ${e.message}"
            }
        }

    override suspend fun processUri(uri: Uri, mimeType: String, context: Context): String =
        withContext(Dispatchers.IO) {
            return@withContext try {
                // Inicializar PDFBox no contexto (necessário apenas uma vez)
                PDFBoxResourceLoader.init(context)

                val inputStream = context.contentResolver.openInputStream(uri)
                val document = PDDocument.load(inputStream)

                val pageCount = document.numberOfPages

                // Obter tamanho do arquivo da URI
                var fileSize = 0L
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex("_size")
                    if (sizeIndex != -1 && cursor.moveToFirst()) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }

                // Extrair informações do PDF
                val result = StringBuilder()
                result.append("Arquivo PDF: ${uri.lastPathSegment ?: "desconhecido"}\n")
                result.append("Páginas: $pageCount\n")
                result.append("Tamanho: ${formatFileSize(fileSize)}\n\n")

                // Extrair texto do PDF (até 50 páginas para não sobrecarregar)
                val maxPages = minOf(pageCount, 50)
                val textStripper = PDFTextStripper()

                for (i in 1..maxPages) {
                    textStripper.startPage = i
                    textStripper.endPage = i
                    val pageText = textStripper.getText(document)

                    // Limitar o texto de cada página (máximo 1000 caracteres)
                    val truncatedText = if (pageText.length > 1000) {
                        pageText.substring(0, 1000) + "... (texto truncado)"
                    } else {
                        pageText
                    }

                    result.append("Página $i:\n")
                    result.append(truncatedText)
                    result.append("\n---\n")
                }

                if (maxPages < pageCount) {
                    result.append("\nNota: ${pageCount - maxPages} páginas adicionais não exibidas.\n")
                }

                document.close()
                inputStream?.close()

                result.toString()
            } catch (e: Exception) {
                Log.e("PdfProcessor", "Erro ao processar PDF de URI", e)
                "Erro ao processar arquivo PDF: ${e.message}"
            }
        }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return "%.2f %s".format(size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}