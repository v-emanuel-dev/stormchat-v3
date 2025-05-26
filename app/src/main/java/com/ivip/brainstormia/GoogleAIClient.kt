package com.ivip.brainstormia

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GoogleAIClient(private val apiKey: String) {
    private val tag = "GoogleAIClient"
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun generateChatCompletion(
        modelId: String,
        systemPrompt: String,
        userMessage: String,
        historyMessages: List<ChatMessage>
    ): Flow<String> = flow {
        try {
            Log.d(tag, "Iniciando chamada à API Google com modelo $modelId")

            // Converter histórico para formato Google
            val contents = createContentsArray(systemPrompt, userMessage, historyMessages)

            // Preparar o JSON da requisição
            val jsonBody = JSONObject().apply {
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 2048)
                })
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)

            // Criar a requisição
            val request = Request.Builder()
                .url("$baseUrl/$modelId:generateContent?key=$apiKey")
                .post(requestBody)
                .build()

            Log.d(tag, "Enviando requisição para Google AI")

            // Executar a chamada
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Corpo vazio"
                    throw IOException("API Google retornou código ${response.code}: $errorBody")
                }

                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val jsonResponse = JSONObject(responseBody)
                    Log.d(tag, "Resposta recebida da Google AI")

                    val candidates = jsonResponse.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val content = candidates.getJSONObject(0).optJSONObject("content")
                        if (content != null) {
                            val parts = content.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val text = parts.getJSONObject(0).optString("text", "")
                                if (text.isNotBlank()) {
                                    emit(text)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Erro em generateChatCompletion: ${e.message}")
            throw e
        }
    }

    private fun createContentsArray(
        systemPrompt: String,
        userMessage: String,
        historyMessages: List<ChatMessage>
    ): JSONArray {
        val contents = JSONArray()

        // Para Gemini, incorporamos o prompt de sistema como uma mensagem de usuário especial
        if (systemPrompt.isNotBlank()) {
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "[SYSTEM INSTRUCTION]\n$systemPrompt\n[/SYSTEM INSTRUCTION]")
                    })
                })
            })

            // Adicionamos uma resposta do modelo vazia para separar o prompt do sistema do diálogo real
            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "Entendido.")
                    })
                })
            })
        }

        // Adicionar histórico de mensagens
        for (message in historyMessages) {
            val role = if (message.sender == Sender.USER) "user" else "model"
            contents.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", message.text)
                    })
                })
            })
        }

        // Adicionar mensagem atual do usuário
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply {
                    put("text", userMessage)
                })
            })
        })

        return contents
    }
}