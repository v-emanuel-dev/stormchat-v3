package com.ivip.brainstormia

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

class AnthropicClient(private val apiKey: String) {
    private val tag = "AnthropicClient"
    private val baseUrl = "https://api.anthropic.com/v1/messages"
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
        // 1) monta o array de history + user
        val messagesArray = JSONArray().apply {
            historyMessages.forEach { msg ->
                put(JSONObject().apply {
                    put("role", if (msg.sender == Sender.USER) "user" else "assistant")
                    put("content", msg.text)
                })
            }
            put(JSONObject().apply {             // última mensagem
                put("role", "user")
                put("content", userMessage)
            })
        }

        // 2) monta o body com o prompt de sistema no top-level
        val jsonBody = JSONObject().apply {
            put("model", modelId)
            put("system", systemPrompt)        // <<< aqui
            put("messages", messagesArray)     // só user/assistant
            put("max_tokens", 4096)
            put("temperature", 0.7)
            put("stream", false)
        }

        val requestBody = jsonBody
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: "empty body"
                throw IOException("API Anthropic retornou ${response.code}: $err")
            }
            // 3) parseia a resposta: "content" é um array de blocos de texto
            val resp = JSONObject(response.body!!.string())
            val blocks = resp.getJSONArray("content")
            val text = buildString {
                for (i in 0 until blocks.length()) {
                    val block = blocks.getJSONObject(i)
                    if (block.getString("type") == "text") {
                        append(block.getString("text"))
                    }
                }
            }
            emit(text)
        }
    }

    private fun createMessagesArray(
        historyMessages: List<ChatMessage>,
        userMessage: String
    ): JSONArray {
        val messages = JSONArray()

        // Adicionar histórico
        for (message in historyMessages) {
            val role = if (message.sender == Sender.USER) "user" else "assistant"
            val messageObj = JSONObject().apply {
                put("role", role)
                put("content", message.text)
            }
            messages.put(messageObj)
        }

        // Adicionar mensagem atual do usuário
        val userMessageObj = JSONObject().apply {
            put("role", "user")
            put("content", userMessage)
        }
        messages.put(userMessageObj)

        return messages
    }
}