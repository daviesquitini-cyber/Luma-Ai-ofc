package app.luma.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class ChatTurn(val role: String, val content: String)

class OpenAiClient {

    private val baseSystem =
        "Você é a Luma, uma assistente pessoal simpática, inteligente e direta. " +
        "O usuário se chama Esquitini. Responda sempre em português do Brasil."
    private val voiceSystem =
        " Esta é uma conversa por voz: responda de forma curta e natural (no máximo 3 frases), " +
        "sem markdown, listas, emojis ou símbolos."

    suspend fun chat(apiKey: String, model: String, history: List<ChatTurn>, voiceMode: Boolean): String {
        require(apiKey.isNotBlank()) { "Configure a chave da OpenAI em Configurações." }

        val messages = JSONArray().put(
            JSONObject().put("role", "system")
                .put("content", baseSystem + if (voiceMode) voiceSystem else "")
        )
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.7)
            .toString()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return Http.client.newCall(request).await().use { resp ->
            val text = withContext(Dispatchers.IO) { resp.body?.string().orEmpty() }
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                    .getOrDefault(text.take(200))
                throw IOException("OpenAI ${resp.code}: $msg")
            }
            JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        }
    }
}
