package com.sephile.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** OpenAI-compatible client (OpenAI / Ollama / LM Studio / …). No third-party deps. */
object Llm {

    private fun base(b: String): String {
        var x = b.trim().trimEnd('/')
        if (x.isNotEmpty() && !x.endsWith("/v1")) x += "/v1"
        return x
    }

    /** Bearer tokens never contain whitespace. A pasted key often carries a stray
     *  newline/space (0x0a) that makes HttpURLConnection reject the header, so
     *  strip ALL whitespace before building the Authorization value. */
    private fun sanKey(k: String) = k.filterNot { it.isWhitespace() }

    /** GET /models → model ids. Throws with a readable message on failure. */
    fun fetchModels(baseUrl: String, apiKey: String): List<String> {
        val b = base(baseUrl); require(b.isNotEmpty()) { "Enter a base URL" }
        val conn = (URL("$b/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 20_000; readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer ${sanKey(apiKey)}")
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(200)}")
        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }

    /** POST /chat/completions with tools. Returns text or the tool calls requested. */
    fun chat(baseUrl: String, apiKey: String, model: String, history: JSONArray, tools: JSONArray): Round {
        val b = base(baseUrl); require(b.isNotEmpty()) { "Enter a base URL" }
        val payload = JSONObject().apply {
            put("model", model); put("messages", history)
            if (tools.length() > 0) put("tools", tools)
            put("temperature", 0.2)
        }
        val conn = (URL("$b/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 60_000; readTimeout = 120_000; doOutput = true
            setRequestProperty("Authorization", "Bearer ${sanKey(apiKey)}")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${body.take(300)}")

        val msg = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw IllegalStateException("No choices in response")
        val calls = ArrayList<TCall>()
        msg.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val f = o.optJSONObject("function") ?: continue
                calls.add(TCall(o.optString("id", "c$i"), f.optString("name"), f.optString("arguments", "{}")))
            }
        }
        val content = msg.optString("content")
        return if (calls.isNotEmpty()) Round.NeedsTools(content?.takeIf { it.isNotBlank() }, calls)
        else Round.Text(content ?: "(empty)")
    }

    data class TCall(val id: String, val name: String, val args: String)
    sealed class Round {
        data class Text(val text: String) : Round()
        data class NeedsTools(val content: String?, val calls: List<TCall>) : Round()
    }
}
