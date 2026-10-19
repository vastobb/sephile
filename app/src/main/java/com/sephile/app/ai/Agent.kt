package com.sephile.app.ai

import com.sephile.app.engine.Engine
import com.sephile.app.engine.candFilter
import com.sephile.app.engine.candRegions
import com.sephile.app.engine.elf
import com.sephile.app.engine.libsFull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gives the chat model real tools over the memory engine: find Minecraft,
 * attach, value-scan / refine, AOB signature scan, read/write/freeze memory,
 * list loaded libraries, and resolve ELF symbols inside them.
 *
 * [trace] is a live log of every tool call the model makes — the UI renders it
 * as "thinking" so you can watch it work.
 */
class Agent(private val defaultPkg: String) {

    val tools: JSONArray = buildTools()

    private val _trace = MutableStateFlow<List<String>>(emptyList())
    val trace: StateFlow<List<String>> = _trace

    private fun log(line: String) {
        _trace.value = (_trace.value + line).takeLast(120)
    }

    fun clearTrace() { _trace.value = emptyList() }

    suspend fun run(history: JSONArray, key: String, base: String, model: String): String =
        withContext(Dispatchers.IO) {
            var guard = 0
            while (guard++ < 10) {
                when (val res = Llm.chat(base, key, model, history, tools)) {
                    is Llm.Round.Text -> return@withContext res.text
                    is Llm.Round.NeedsTools -> {
                        history.put(assistant(res.content, res.calls))
                        for (c in res.calls) {
                            log("▸ ${c.name} ${compact(c.args)}")
                            val out = try { exec(c.name, c.args) } catch (t: Throwable) { "ERROR: ${t.message}" }
                            log("  ↵ ${out.lineSequence().first().take(140)}")
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
            // ── library audit (attach to MC libs) ──────────────────────────
            "list_libraries" -> {
                val libs = Engine.libsFull()
                if (libs.isEmpty()) "No native libraries loaded."
                else "${libs.size} libs:\n" + libs.joinToString("\n")
            }
            "find_symbols" -> {
                val lib = a.optString("lib", "minecraftpe")
                val filter = a.optString("filter", "")
                val syms = Engine.elf(lib, filter)
                if (syms.isEmpty()) "No symbols matched in '$lib' (filter='$filter')."
                else "Symbols in $lib (${syms.size}):\n" + syms.take(80).joinToString("\n")
            }
            // ── region-aware candidate triage (the "which of 800k is real" fix) ──
            "cand_regions" -> {
                val regions = Engine.candRegions()
                // regions[0] = "total N", rest = "kind count"
                val total = regions.firstOrNull()?.substringAfter(' ') ?: "0"
                if (regions.size <= 1) "No candidates."
                else "Total $total candidates. By region:\n" +
                    regions.drop(1).joinToString("\n") +
                    "\n(player state lives on heap/anon — use cand_filter to drop lib/file/stack noise)"
            }
            "cand_filter" -> {
                val kind = a.optString("region", "heap")
                val before = Engine.request("COUNT")
                val res = Engine.candFilter(kind)
                "$res (was $before before)"
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
    private fun compact(args: String) =
        args.replace(Regex("\\s+"), " ").trim().take(100)

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
        // ── library audit ──
        t.put(fn("list_libraries", "List every native library (.so) loaded in Minecraft with its base address. " +
            "Use this to see libminecraftpe.so and friends.", JSONObject(), emptyList()))
        t.put(fn("find_symbols", "Resolve ELF symbols inside a loaded library by name substring. " +
            "e.g. lib='minecraftpe', filter='Player' to find player-related functions. " +
            "Returns runtime addresses (base + offset).", 
            JSONObject().apply {
                put("lib", s("lib", "Library name substring, e.g. 'minecraftpe'"))
                put("filter", s("filter", "Symbol name substring, e.g. 'Health' (blank=all)"))
            }, listOf("lib")))
        // ── region triage ──
        t.put(fn("cand_regions", "Histogram of current candidates by memory-region kind (heap/anon/lib/file/stack). " +
            "Call this when a scan returns too many candidates to list — it shows WHERE they cluster so you can " +
            "filter. Player state (health, XP, coords) lives on the HEAP, not inside read-only libraries.",
            JSONObject(), emptyList()))
        t.put(fn("cand_filter", "Keep only candidates in a given region kind. Use AFTER cand_regions to cut " +
            "hundreds of thousands of candidates down to the ones that matter. " +
            "region: 'heap', 'anon', 'lib', 'file', 'stack', or 'all'.",
            JSONObject().apply { put("region", e("region", "Region kind to keep", 
                listOf("heap", "anon", "lib", "file", "stack", "all"))) }, listOf("region")))
        return t
    }
}
