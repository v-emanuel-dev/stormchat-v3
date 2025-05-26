package com.ivip.brainstormia.file

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.File
import java.io.FileInputStream

/**
 * Implementação para arquivos Excel (XLS/XLSX)
 * Usa Apache POI para extrair o conteúdo
 */
class ExcelFileProcessor : FileProcessor {
    override fun canProcess(mimeType: String): Boolean {
        return mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" || // xlsx
                mimeType == "application/vnd.ms-excel" // xls
    }

    override suspend fun processFile(file: File, mimeType: String, context: Context): String =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val inputStream = FileInputStream(file)
                val workbook = WorkbookFactory.create(inputStream)

                val sheetCount = workbook.numberOfSheets
                val result = StringBuilder()

                result.append("Arquivo Excel: ${file.name}\n")
                result.append("Número de planilhas: $sheetCount\n")
                result.append("Tamanho: ${formatFileSize(file.length())}\n\n")

                // Processar até 3 planilhas para não sobrecarregar
                val maxSheets = minOf(sheetCount, 3)

                for (i in 0 until maxSheets) {
                    val sheet = workbook.getSheetAt(i)
                    result.append("Planilha ${i+1}: ${sheet.sheetName}\n")

                    // Processar linhas (limitado a 50 para evitar sobrecarga)
                    val maxRows = minOf(sheet.physicalNumberOfRows, 50)

                    for (rowIndex in 0 until maxRows) {
                        val row = sheet.getRow(rowIndex) ?: continue

                        // Processar células (limitado a 10 colunas)
                        val maxCells = minOf(row.physicalNumberOfCells, 10)
                        val rowContent = StringBuilder()

                        for (cellIndex in 0 until maxCells) {
                            val cell = row.getCell(cellIndex)
                            val cellValue = when (cell?.cellType) {
                                null -> ""
                                else -> try { cell.toString() } catch (e: Exception) { "" }
                            }

                            if (cellValue.isNotBlank()) {
                                rowContent.append("$cellValue | ")
                            }
                        }

                        if (rowContent.isNotEmpty()) {
                            result.append("  ${rowIndex+1}: ${rowContent.toString().trim(' ', '|')}\n")
                        }
                    }

                    if (maxRows < sheet.physicalNumberOfRows) {
                        result.append("  ... mais ${sheet.physicalNumberOfRows - maxRows} linhas não exibidas\n")
                    }

                    result.append("\n")
                }

                if (maxSheets < sheetCount) {
                    result.append("Nota: ${sheetCount - maxSheets} planilhas adicionais não exibidas.\n")
                }

                workbook.close()
                inputStream.close()

                result.toString()
            } catch (e: Exception) {
                Log.e("ExcelProcessor", "Erro ao processar Excel", e)
                "Erro ao processar arquivo Excel: ${e.message}"
            }
        }

    override suspend fun processUri(uri: Uri, mimeType: String, context: Context): String =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val workbook = WorkbookFactory.create(inputStream)

                val sheetCount = workbook.numberOfSheets
                val result = StringBuilder()

                // Obter nome do arquivo da URI
                val fileName = uri.lastPathSegment ?: "desconhecido"

                result.append("Arquivo Excel: $fileName\n")
                result.append("Número de planilhas: $sheetCount\n\n")

                // Processar até 3 planilhas para não sobrecarregar
                val maxSheets = minOf(sheetCount, 3)

                for (i in 0 until maxSheets) {
                    val sheet = workbook.getSheetAt(i)
                    result.append("Planilha ${i+1}: ${sheet.sheetName}\n")

                    // Processar linhas (limitado a 50 para evitar sobrecarga)
                    val maxRows = minOf(sheet.physicalNumberOfRows, 50)

                    for (rowIndex in 0 until maxRows) {
                        val row = sheet.getRow(rowIndex) ?: continue

                        // Processar células (limitado a 10 colunas)
                        val maxCells = minOf(row.physicalNumberOfCells, 10)
                        val rowContent = StringBuilder()

                        for (cellIndex in 0 until maxCells) {
                            val cell = row.getCell(cellIndex)
                            val cellValue = when (cell?.cellType) {
                                null -> ""
                                else -> try { cell.toString() } catch (e: Exception) { "" }
                            }

                            if (cellValue.isNotBlank()) {
                                rowContent.append("$cellValue | ")
                            }
                        }

                        if (rowContent.isNotEmpty()) {
                            result.append("  ${rowIndex+1}: ${rowContent.toString().trim(' ', '|')}\n")
                        }
                    }

                    if (maxRows < sheet.physicalNumberOfRows) {
                        result.append("  ... mais ${sheet.physicalNumberOfRows - maxRows} linhas não exibidas\n")
                    }

                    result.append("\n")
                }

                if (maxSheets < sheetCount) {
                    result.append("Nota: ${sheetCount - maxSheets} planilhas adicionais não exibidas.\n")
                }

                workbook.close()
                inputStream?.close()

                result.toString()
            } catch (e: Exception) {
                Log.e("ExcelProcessor", "Erro ao processar Excel de URI", e)
                "Erro ao processar arquivo Excel: ${e.message}"
            }
        }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return "%.2f %s".format(size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}