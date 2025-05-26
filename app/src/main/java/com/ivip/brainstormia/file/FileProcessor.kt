package com.ivip.brainstormia.file

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Interface para processadores de arquivo
 * Cada tipo de arquivo terá sua própria implementação
 */
interface FileProcessor {
    /**
     * Verifica se este processador pode lidar com o tipo de arquivo fornecido
     */
    fun canProcess(mimeType: String): Boolean

    /**
     * Processa o arquivo e extrai seu conteúdo/informações
     * @return String contendo o conteúdo extraído ou descrição do arquivo
     */
    suspend fun processFile(file: File, mimeType: String, context: Context): String

    /**
     * Processa o arquivo através de URI
     * @return String contendo o conteúdo extraído ou descrição do arquivo
     */
    suspend fun processUri(uri: Uri, mimeType: String, context: Context): String
}