package com.sephile.app.ai

import com.sephile.app.engine.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gives the chat model real tools over the memory engine: find Minecraft,
 * attach, value-scan / refine, AOB signature scan, read/write/freeze memory.
 *
 * The model drives the same scan→narrow→write workflow a human would — that's
 * how it "finds the offset/address and edits it." No magic; real scanner.
 */
class Agent(private val defaultPkg: String) {

    val tools: JSONArray = buildTools()

    suspend fun run(history: JSONArray, key: String, base: String, model: String): String =
        withContext(Dispatchers.IO) {
            var guard = 0
            while (guard++ < 8) {
                when (val res = Llm.chat(base, key, model, history, tools)) {
                    is Llm.Round.Text -> return@withContext res.text
                    is Llm.Round.NeedsTools -> {
                        history.put(assistant(res.content, res.calls))
                        for (c in res.calls) {
                            val out = try { exec(c.name, c.args) } catch (t: Throwable) { "ERROR: ${t.message}" }
                            history.put(tool(c.id, out))
                        }
                    }
                }
            }
            "Step limit reached for this turn."
        }

    private fun exec(name: String, argJson: String): String {
        val a = try { JSONObject(argJson.ifBlank { "{}" }) } catch (t: Throwable) { JSONObject() }
        return when (name) {
            "find_minecraft" -> {
                val pid = a.optInt("pid", -1)
                val target = if (pid > 0) pid else runCatching {
                    kotlinx.coroutines.runBlocking { Engine.autoAttachMinecraft() }
                }.getOrDefault(-1)
                if (target > 0) "Found Minecraft, attached to pid $target." else "Minecraft isn't running (or attach failed)."
            }
            "engine_status" -> {
                val pid = Engine.targetPid.value
                if (pid > 0) "Attached to Minecraft pid $pid." else "Not attached."
            }
            "scan" -> scan(first = true, a)
            "next_scan" -> scan(first = false, a)
            "candidate_count" -> "${Engine.request("COUNT")} candidate(s)"
            "get_candidates" -> candidates(a.optInt("limit", 20))
            "read_memory" -> {
                val addr = a.optLong("address", 0L)
                val len = a.optInt("length", 16).coerceIn(1, 4096)
                val hex = Engine.request("PEEK $addr $len")
                if (hex.isBlank()) "Cannot read at 0x${addr.toString(16)}" else spaced(hex)
            }
            "write_memory" -> {
                val addr = a.optLong("address", 0L)
                val type = a.optString("type", "dword")
                val value = a.optString("value", "")
                val iv: Long; var fv = 0.0
                if (type == "float" || type == "double") { fv = value.toDoubleOrNull() ?: 0.0; iv = 0L }
                else iv = value.toLongOrNull() ?: 0L
                val ok = Engine.request("POKE $addr ${typeId(type)} $iv $fv") == "1"
                if (ok) "Wrote $value to 0x${addr.toString(16)}." else "Write failed."
            }
            "freeze" -> {
                val addr = a.optLong("address", 0L)
                val type = a.optString("type", "dword")
                val value = a.optString("value", "0")
                val en = a.optBoolean("enable", true)
                val iv: Long; var fv = 0.0
                if (type == "float" || type == "double") { fv = value.toDoubleOrNull() ?: 0.0; iv = 0L }
                else iv = value.toLongOrNull() ?: 0L
                Engine.request("FREEZE $addr ${if (en) 1 else 0} ${typeId(type)} $iv $fv")
                if (en) "Freezing 0x${addr.toString(16)} at $value." else "Unfroze."
            }
            "pattern_scan" -> {
                val hits = Engine.requestMulti("PATTERNSCAN ${a.optString("pattern", "")}")
                if (hits.isEmpty()) "No matches."
                else "Found ${hits.size}:\n" + hits.take(50).joinToString("\n")
            }
            "read_maps" -> {
                val maps = Engine.requestMulti("MAPS")
                if (maps.isEmpty()) "No maps." else "${maps.size} regions:\n" + maps.take(60).joinToString("\n")
            }
            else -> "Unknown tool '$name'."
        }
    }

    private fun scan(first: Boolean, a: JSONObject): String {
        if (Engine.targetPid.value <= 0) return "Not attached. Use find_minecraft first."
        val type = a.optString("type", "dword")
        val search = a.optString("search", "exact")
        val raw = a.optString("value", "")
        val isF = type == "float" || type == "double"
        val tv = if (isF) 0L else (raw.toLongOrNull() ?: 0L)
        val tf = if (isF) (raw.toDoubleOrNull() ?: 0.0) else 0.0
        val phase = if (first) 0 else 1
        Engine.request("SCAN $phase ${typeId(type)} ${searchId(search)} $tv $tf 1")
        val count = Engine.request("COUNT")
        return "${if (first) "First" else "Next"} scan ($type/$search) → $count candidate(s)."
    }

    private fun candidates(limit: Int): String {
        val count = Engine.request("COUNT").toIntOrNull() ?: 0
        if (count == 0) return "No candidates yet."
        val n = minOf(limit, count, 50)
        val sb = StringBuilder("$count candidates. First $n:\n")
        for (i in 0 until n) {
            val parts = Engine.request("CANDIDATE $i").split(' ')
            sb.appendLine("  0x${parts.getOrNull(0) ?: "?"}  ${parts.getOrNull(1) ?: ""}")
        }
        if (count > n) sb.append("  … ${count - n} more")
        return sb.toString()
    }

    private fun typeId(t: String) = when (t.lowercase()) {
        "byte" -> 0; "short" -> 1; "qword" -> 3; "float" -> 4; "double" -> 5; else -> 2
    }
    private fun searchId(s: String) = when (s.lowercase()) {
        "unknown" -> 1; "changed" -> 2; "greater" -> 3; "less" -> 4; else -> 0
    }
    private fun spaced(hex: String) = hex.chunked(2).joinToString(" ")

    private fun assistant(content: String?, calls: List<Llm.TCall>): JSONObject =
        JSONObject().apply {
            put("role", "assistant")
            put("content", content ?: JSONArray())
            if (calls.isNotEmpty()) {
                put("tool_calls", JSONArray().apply {
                    for (c in calls) put(JSONObject().apply {
                        put("id", c.id); put("type", "function")
                        put("function", JSONObject().apply { put("name", c.name); put("arguments", c.args) })
                    })
                })
            }
        }

    private fun tool(id: String, content: String) = JSONObject().apply {
        put("role", "tool"); put("tool_call_id", id); put("content", content)
    }

    private fun buildTools(): JSONArray {
        fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().apply {
            put("type", "function"); put("function", JSONObject().apply {
                put("name", name); put("description", desc)
                put("parameters", JSONObject().apply {
                    put("type", "object"); put("properties", props); put("required", JSONArray(req))
                })
            })
        }
        fun s(n: String, d: String) = JSONObject().apply { put("type", "string"); put("description", d) }
        fun i(n: String, d: String) = JSONObject().apply { put("type", "integer"); put("description", d) }
        fun b(n: String, d: String) = JSONObject().apply { put("type", "boolean"); put("description", d) }
        fun e(n: String, d: String, vals: List<String>) = JSONObject().apply {
            put("type", "string"); put("description", d); put("enum", JSONArray(vals))
        }
        val types = listOf("byte", "short", "dword", "qword", "float", "double")
        val searches = listOf("exact", "unknown", "changed", "greater", "less")
        fun scanProps() = JSONObject().apply {
            put("value", s("value", "Current in-game value (e.g. 64)"))
            put("type", e("type", "Value type", types))
            put("search", e("search", "Comparison", searches))
        }

        val t = JSONArray()
        t.put(fn("find_minecraft", "Locate and attach the running Minecraft process. Call this first.", JSONObject(), emptyList()))
        t.put(fn("engine_status", "Report whether Minecraft is currently attached.", JSONObject(), emptyList()))
        t.put(fn("scan", "FIRST value scan across writable memory. Returns candidate count.", scanProps(), listOf("value")))
        t.put(fn("next_scan", "REFINE previous scan after the in-game value changes.", scanProps(), listOf("value")))
        t.put(fn("candidate_count", "How many candidates remain.", JSONObject(), emptyList()))
        t.put(fn("get_candidates", "List candidate addresses + bytes.",
            JSONObject().apply { put("limit", i("limit", "Max to show")) }, emptyList()))
        t.put(fn("read_memory", "Read bytes at an address (returns hex).",
            JSONObject().apply { put("address", i("address", "Address")); put("length", i("length", "Bytes")) },
            listOf("address")))
        t.put(fn("write_memory", "Write a value to an address.",
            JSONObject().apply {
                put("address", i("address", "Address")); put("value", s("value", "Value"))
                put("type", e("type", "Type", types))
            }, listOf("address", "value")))
        t.put(fn("freeze", "Hold a value at an address (enable=false to stop).",
            JSONObject().apply {
                put("address", i("address", "Address")); put("value", s("value", "Value"))
                put("type", e("type", "Type", types)); put("enable", b("enable", "true/false"))
            }, listOf("address", "value")))
        t.put(fn("pattern_scan", "AOB/signature scan: find a byte pattern ('48 89 5C 24 ?? 57', ??=wildcard).",
            JSONObject().apply { put("pattern", s("pattern", "Byte pattern")) }, listOf("pattern")))
        t.put(fn("read_maps", "Dump readable memory regions.", JSONObject(), emptyList()))
        return t
    }
}
