package com.ivip.brainstormia.file

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Gerenciador para coordenar o processamento de diferentes tipos de arquivo
 */
class FileProcessingManager(private val context: Context) {
    private val processors = mutableListOf<FileProcessor>()

    init {
        // Registrar os processadores disponíveis
        registerProcessor(TextFileProcessor())
        registerProcessor(PdfFileProcessor())
        registerProcessor(ImageFileProcessor())

        // Opcional: Registrar processadores para Office (Word, Excel)
        // Usando registradores individuais para lidar com erros separadamente
        try {
            registerProcessor(WordFileProcessor())
            Log.d(TAG, "Processador Word registrado com sucesso")
        } catch (e: Exception) {
            Log.w(TAG, "Processador Word não registrado: ${e.message}")
        }

        try {
            registerProcessor(ExcelFileProcessor())
            Log.d(TAG, "Processador Excel registrado com sucesso")
        } catch (e: Exception) {
            Log.w(TAG, "Processador Excel não registrado: ${e.message}")
        }
    }

    /**
     * Registra um novo processador de arquivos
     */
    fun registerProcessor(processor: FileProcessor) {
        processors.add(processor)
    }

    /**
     * Processa um arquivo e extrai seu conteúdo/informações
     * @return String contendo o conteúdo extraído ou descrição do arquivo
     */
    suspend fun processFile(file: File, mimeType: String): String {
        Log.d(TAG, "Processando arquivo: ${file.name}, tipo: $mimeType")

        // Encontrar um processador adequado para este tipo de arquivo
        val processor = findProcessor(mimeType)

        return if (processor != null) {
            try {
                Log.d(TAG, "Processador encontrado: ${processor.javaClass.simpleName}")
                processor.processFile(file, mimeType, context)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar arquivo", e)
                "Erro ao processar o arquivo ${file.name}: ${e.message}"
            }
        } else {
            Log.w(TAG, "Nenhum processador disponível para o tipo: $mimeType")
            createGenericFileDescription(file, mimeType)
        }
    }

    /**
     * Processa um arquivo a partir de URI e extrai seu conteúdo/informações
     * @return String contendo o conteúdo extraído ou descrição do arquivo
     */
    suspend fun processUri(uri: Uri, mimeType: String): String {
        Log.d(TAG, "Processando URI: $uri, tipo: $mimeType")

        // Encontrar um processador adequado para este tipo de arquivo
        val processor = findProcessor(mimeType)

        return if (processor != null) {
            try {
                Log.d(TAG, "Processador encontrado: ${processor.javaClass.simpleName}")
                processor.processUri(uri, mimeType, context)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar URI", e)
                "Erro ao processar o arquivo da URI: ${e.message}"
            }
        } else {
            Log.w(TAG, "Nenhum processador disponível para o tipo: $mimeType")
            "Arquivo enviado com tipo $mimeType, mas não há processador disponível " +
                    "para extrair seu conteúdo. Apenas os metadados estão disponíveis."
        }
    }

    /**
     * Encontra um processador adequado para o tipo de arquivo
     */
    private fun findProcessor(mimeType: String): FileProcessor? {
        return processors.find { it.canProcess(mimeType) }
    }

    /**
     * Cria uma descrição genérica para arquivos sem processador específico
     */
    private fun createGenericFileDescription(file: File, mimeType: String): String {
        val size = formatFileSize(file.length())
        val extension = file.extension.takeIf { it.isNotBlank() } ?: "desconhecida"

        return "Arquivo: ${file.name}\n" +
                "Tipo: $mimeType\n" +
                "Extensão: $extension\n" +
                "Tamanho: $size\n\n" +
                "Este tipo de arquivo não possui um processador específico implementado. " +
                "Portanto, não é possível extrair seu conteúdo para análise."
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return "%.2f %s".format(size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    companion object {
        private const val TAG = "FileProcessingManager"
    }
}