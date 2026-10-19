package com.sephile.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal OpenAI-compatible client (works with OpenAI, Ollama, LM Studio, etc.).
 * No third-party deps — plain HttpURLConnection + org.json, so the build stays simple.
 */
object LlmClient {

    /** Normalize a user-entered base URL to ".../v1". */
    private fun normalizeBase(base: String): String {
        var b = base.trim().trimEnd('/')
        if (b.isEmpty()) return b
        if (!b.endsWith("/v1")) b += "/v1"
        return b
    }

    /**
     * GET {base}/models → list of model ids.
     * Reads `data[].id` (OpenAI shape). Throws with a readable message on failure.
     */
    fun fetchModels(baseUrl: String, apiKey: String): List<String> {
        val base = normalizeBase(baseUrl)
        require(base.isNotEmpty()) { "Enter an API base URL" }
        val url = URL("$base/models")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(200)}")
        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString("id")
            if (!id.isNullOrBlank()) out.add(id)
        }
        return out
    }

    /**
     * POST {base}/chat/completions with `tools` declared. Returns either the
     * model's text or the tool calls it wants to run. [history] is the running
     * message list (assistant + tool messages are appended by the caller).
     */
    fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        history: JSONArray,
        tools: JSONArray
    ): ChatResult {
        val base = normalizeBase(baseUrl)
        require(base.isNotEmpty()) { "Enter an API base URL" }
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", history)
            if (tools.length() > 0) put("tools", tools)
            put("temperature", 0.2)
        }
        val url = URL("$base/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 60_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(300)}")

        val msg = JSONObject(body)
            .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw IllegalStateException("No choices in response")

        val calls = ArrayList<ToolCall>()
        val tcArr = msg.optJSONArray("tool_calls")
        if (tcArr != null) {
            for (i in 0 until tcArr.length()) {
                val o = tcArr.optJSONObject(i) ?: continue
                val fn = o.optJSONObject("function") ?: continue
                calls.add(
                    ToolCall(
                        id = o.optString("id", "call_$i"),
                        name = fn.optString("name"),
                        argumentsJson = fn.optString("arguments", "{}")
                    )
                )
            }
        }
        val content = msg.optString("content")
        return if (calls.isNotEmpty()) {
            ChatResult.NeedsTools(if (content.isNullOrBlank()) null else content, calls)
        } else {
            ChatResult.Text(content ?: "(empty)")
        }
    }
}

/** One chat message (role + text or tool payload). */
data class ChatTurn(val role: String, val content: String?, val toolCallId: String? = null)

/** A function the model asked to run. */
data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/** Result of one chat round-trip. */
sealed class ChatResult {
    data class Text(val text: String) : ChatResult()
    data class NeedsTools(val assistantContent: String?, val calls: List<ToolCall>) : ChatResult()
}
