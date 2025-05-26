package com.ivip.brainstormia.file

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Implementação para arquivos de texto
 */
class TextFileProcessor : FileProcessor {
    override fun canProcess(mimeType: String): Boolean {
        return mimeType.startsWith("text/") ||
                mimeType == "application/json" ||
                mimeType == "application/xml" ||
                mimeType == "application/javascript"
    }

    override suspend fun processFile(file: File, mimeType: String, context: Context): String {
        return try {
            val content = file.readText()
            if (content.length > 100000) {
                // Se o conteúdo for muito grande, truncar e informar
                "Este arquivo de texto é muito grande (${content.length} caracteres). Aqui estão os primeiros 100.000 caracteres:\n\n" +
                        content.take(100000) + "\n\n[Conteúdo truncado devido ao tamanho...]"
            } else {
                content
            }
        } catch (e: Exception) {
            "Erro ao ler arquivo de texto: ${e.message}"
        }
    }

    override suspend fun processUri(uri: Uri, mimeType: String, context: Context): String {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val content = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            inputStream?.close()

            if (content.length > 100000) {
                // Se o conteúdo for muito grande, truncar e informar
                "Este arquivo de texto é muito grande (${content.length} caracteres). Aqui estão os primeiros 100.000 caracteres:\n\n" +
                        content.take(100000) + "\n\n[Conteúdo truncado devido ao tamanho...]"
            } else {
                content
            }
        } catch (e: Exception) {
            "Erro ao ler arquivo de texto: ${e.message}"
        }
    }
}