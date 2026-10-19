package com.sephile.app.ai

import com.sephile.app.model.SearchType
import com.sephile.app.model.ValueType
import com.sephile.app.repository.MemoryRepository
import com.sephile.app.repository.ProcessRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The AI agent: exposes the memory engine to a chat model as *real tools*.
 *
 * The model can list processes, attach, run value scans, refine them, do AOB
 * signature scans, read/write/freeze memory. So when the user says "I have 64
 * grass blocks, help me find where that's stored," the model actually drives
 * the scanner: scan 64 → user drops to 63 → next_scan → narrow → write.
 *
 * This is the honest version of "AI finds the address and edits memory": it does
 * it through the same scan/refine workflow a human would, not magic.
 */
class Agent(
    private val memory: MemoryRepository,
    private val processes: ProcessRepository,
    private val targetPackage: String = "com.mojang.minecraftpe"
) {

    /** Built once — the tool catalog handed to the model. */
    val tools: JSONArray = buildTools()

    /** Run one full assistant turn, executing tool calls until it produces text. */
    suspend fun run(history: JSONArray, apiKey: String, baseUrl: String, model: String): String =
        withContext(Dispatchers.IO) {
            var guard = 0
            while (guard++ < 8) {
                when (val res = LlmClient.chat(baseUrl, apiKey, model, history, tools)) {
                    is ChatResult.Text -> return@withContext res.text
                    is ChatResult.NeedsTools -> {
                        if (res.assistantContent != null) {
                            history.put(assistantMsg(res.assistantContent, res.calls))
                        } else {
                            history.put(assistantMsg(null, res.calls))
                        }
                        for (call in res.calls) {
                            val out = try { execute(call.name, call.argumentsJson) }
                                      catch (t: Throwable) { "ERROR: ${t.message}" }
                            history.put(toolMsg(call.id, out))
                        }
                    }
                }
            }
            "Reached the step limit for this turn."
        }

    // ── Tool execution ──────────────────────────────────────────────────────
    private fun execute(name: String, argsJson: String): String {
        val a = try { JSONObject(if (argsJson.isBlank()) "{}" else argsJson) } catch (t: Throwable) { JSONObject() }
        return when (name) {
            "list_processes" -> listProcesses()
            "attach" -> {
                val pid = a.optInt("pid", -1)
                if (pid <= 0) "Need a pid. Use list_processes first."
                else if (memory.attach(pid)) "Attached to pid $pid." else "Attach to $pid failed."
            }
            "current_target" -> if (memory.isAttached()) "Attached to pid ${memory.targetPid}." else "Not attached."
            "scan" -> doScan(first = true, a)
            "next_scan" -> doScan(first = false, a)
            "candidate_count" -> "${memory.candidateCount} candidate(s)"
            "get_candidates" -> getCandidates(a.optInt("limit", 20))
            "read_memory" -> {
                val addr = a.optLong("address", 0L)
                val len = a.optInt("length", 16).coerceIn(1, 4096)
                val hex = memory.peekHex(addr, len)
                if (hex.isEmpty()) "Could not read $len bytes at 0x${addr.toString(16)}"
                else hexToSpaced(hex)
            }
            "write_memory" -> {
                val addr = a.optLong("address", 0L)
                val type = typeOf(a.optString("type", "dword"))
                val text = a.optString("value", "")
                val (ok, msg) = memory.editValueFromText(addr, type, text)
                if (ok) "Wrote $text (${type.label}) to 0x${addr.toString(16)}." else "Write failed: $msg"
            }
            "freeze" -> {
                val addr = a.optLong("address", 0L)
                val type = typeOf(a.optString("type", "dword"))
                val enable = a.optBoolean("enable", true)
                val value = a.optString("value", "0")
                memory.freeze(addr, enable, value, type)
                if (enable) "Froze 0x${addr.toString(16)} at $value." else "Unfroze 0x${addr.toString(16)}."
            }
            "pattern_scan" -> {
                val pat = a.optString("pattern", "")
                val hits = memory.patternScan(pat)
                if (hits.isEmpty()) "No matches for pattern."
                else "Found ${hits.size} match(es):\n" +
                    hits.take(50).joinToString("\n") { "0x" + it.toString(16) }
            }
            "read_maps" -> {
                val maps = memory.readableMaps()
                if (maps.isEmpty()) "No maps (not attached?)"
                else "${maps.size} readable region(s):\n" + maps.take(60).joinToString("\n")
            }
            else -> "Unknown tool '$name'."
        }
    }

    private fun listProcesses(): String {
        val procs = runCatching {
            kotlinx.coroutines.runBlocking { processes.listRunningProcesses() }
        }.getOrDefault(emptyList())
        if (procs.isEmpty()) return "No processes. Is the engine running?"
        val mc = procs.firstOrNull { it.name.contains(targetPackage, true) }
        val others = procs.filter { it !== mc }.take(40)
        val sb = StringBuilder()
        if (mc != null) sb.appendLine("MINECRAFT: pid=${mc.pid} ${mc.name}")
        sb.appendLine("Other processes:")
        others.forEach { sb.appendLine("  pid=${it.pid} ${it.name}") }
        return sb.toString()
    }

    private fun doScan(first: Boolean, a: JSONObject): String {
        if (!memory.isAttached()) return "Not attached. Call attach first."
        val type = typeOf(a.optString("type", "dword"))
        val search = searchOf(a.optString("search", "exact"))
        val raw = a.optString("value", "")
        val asFloat = type == ValueType.FLOAT || type == ValueType.DOUBLE
        val tval = if (asFloat) 0L else (raw.toLongOrNull() ?: 0L)
        val tfval = if (asFloat) (raw.toDoubleOrNull() ?: 0.0) else 0.0
        if (search == SearchType.EXACT && raw.isNotBlank() && !asFloat && raw.toLongOrNull() == null)
            return "'$raw' is not a valid ${type.label} number."
        val ok = if (first) {
            memory.firstScan(memory.targetPid, type, search, tval, tfval, 0L, onlyWritable = true)
        } else {
            memory.nextScan(type, search, tval, tfval, 0L, onlyWritable = true)
        }
        val count = memory.candidateCount
        return if (ok) "${if (first) "First" else "Next"} scan (${type.label}, $search) → $count candidate(s)."
        else "Scan failed."
    }

    private fun getCandidates(limit: Int): String {
        val count = memory.candidateCount
        if (count == 0) return "No candidates. Run a scan."
        val n = minOf(limit, count, 50)
        val sb = StringBuilder("$count candidate(s). First $n:\n")
        for (i in 0 until n) {
            val addr = memory.candidateAddress(i)
            val hex = memory.candidateHex(i)
            sb.appendLine("  0x${addr.toString(16)}  $hex")
        }
        if (count > n) sb.append("  … ${count - n} more")
        return sb.toString()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private fun typeOf(s: String): ValueType = when (s.lowercase()) {
        "byte" -> ValueType.BYTE
        "short" -> ValueType.SHORT
        "qword" -> ValueType.QWORD
        "float" -> ValueType.FLOAT
        "double" -> ValueType.DOUBLE
        else -> ValueType.DWORD
    }

    private fun searchOf(s: String): SearchType = when (s.lowercase()) {
        "unknown", "unknown_initial" -> SearchType.UNKNOWN
        "changed", "fuzzy" -> SearchType.FUZZY
        "greater", "greater_than", ">" -> SearchType.GREATER_THAN
        "less", "less_than", "<" -> SearchType.LESS_THAN
        else -> SearchType.EXACT
    }

    private fun hexToSpaced(hex: String): String =
        hex.chunked(2).joinToString(" ")

    // ── Message builders ────────────────────────────────────────────────────
    private fun assistantMsg(content: String?, calls: List<ToolCall>): JSONObject {
        val m = JSONObject().apply { put("role", "assistant") }
        if (content != null) m.put("content", content) else m.put("content", JSONArray())
        if (calls.isNotEmpty()) {
            val arr = JSONArray()
            for (c in calls) {
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", c.name)
                        put("arguments", c.argumentsJson)
                    })
                })
            }
            m.put("tool_calls", arr)
        }
        return m
    }

    private fun toolMsg(id: String, content: String): JSONObject =
        JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", id)
            put("content", content)
        }

    // ── Tool catalog ────────────────────────────────────────────────────────
    private fun buildTools(): JSONArray {
        fun fn(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject =
            JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", desc)
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", props)
                        put("required", JSONArray(required))
                    })
                })
            }
        fun str(name: String, desc: String) = JSONObject().apply {
            put("type", "string"); put("description", desc)
        }
        fun int(name: String, desc: String) = JSONObject().apply {
            put("type", "integer"); put("description", desc)
        }
        fun bool(name: String, desc: String) = JSONObject().apply {
            put("type", "boolean"); put("description", desc)
        }

        val typeEnum = JSONArray(listOf("byte", "short", "dword", "qword", "float", "double"))
        val searchEnum = JSONArray(listOf("exact", "unknown", "changed", "greater", "less"))
        fun typed(name: String) = JSONObject().apply {
            put("type", "string"); put("description", "Value type"); put("enum", typeEnum)
        }

        val t = JSONArray()
        t.put(fn("list_processes", "List running processes, flagging Minecraft (com.mojang.minecraftpe).",
            JSONObject(), emptyList()))
        t.put(fn("attach", "Attach the memory engine to a process by pid. Do this before scanning.",
            JSONObject().apply { put("pid", int("pid", "Process id to attach")) }, listOf("pid")))
        t.put(fn("current_target", "Return the currently attached pid, if any.",
            JSONObject(), emptyList()))
        t.put(fn("scan", "FIRST scan: search all writable memory for a value. type=dword by default, " +
            "search=exact. Use the current in-game value. Returns candidate count.",
            JSONObject().apply {
                put("value", str("value", "The value currently shown in game (e.g. 64)"))
                put("type", typed("type"))
                put("search", JSONObject().apply {
                    put("type", "string"); put("description", "How to compare"); put("enum", searchEnum)
                })
            }, listOf("value")))
        t.put(fn("next_scan", "REFINE the previous scan. Only candidates that still match survive. " +
            "Use after the in-game value changes.",
            JSONObject().apply {
                put("value", str("value", "The new in-game value"))
                put("type", typed("type"))
                put("search", JSONObject().apply {
                    put("type", "string"); put("description", "How to compare"); put("enum", searchEnum)
                })
            }, listOf("value")))
        t.put(fn("candidate_count", "How many candidates the current scan has.",
            JSONObject(), emptyList()))
        t.put(fn("get_candidates", "Show up to `limit` candidate addresses + their current bytes.",
            JSONObject().apply { put("limit", int("limit", "Max to show (default 20)")) }, emptyList()))
        t.put(fn("read_memory", "Read `length` bytes at a hex/decimal address and return hex.",
            JSONObject().apply {
                put("address", int("address", "Address (integer; pass as number)"))
                put("length", int("length", "Bytes to read (1-4096)"))
            }, listOf("address")))
        t.put(fn("write_memory", "Write a value to an address. type=dword default.",
            JSONObject().apply {
                put("address", int("address", "Target address (number)"))
                put("value", str("value", "Value to write"))
                put("type", typed("type"))
            }, listOf("address", "value")))
        t.put(fn("freeze", "Continuously re-write a value to an address (hold it). enable=false to stop.",
            JSONObject().apply {
                put("address", int("address", "Address (number)"))
                put("value", str("value", "Value to hold"))
                put("type", typed("type"))
                put("enable", bool("enable", "true to freeze, false to unfreeze"))
            }, listOf("address", "value")))
        t.put(fn("pattern_scan", "AOB / signature scan: find a byte pattern across all readable memory. " +
            "Space-separated hex bytes, ?? = wildcard (e.g. '48 89 5C 24 ?? 57').",
            JSONObject().apply { put("pattern", str("pattern", "Byte pattern with ?? wildcards")) },
            listOf("pattern")))
        t.put(fn("read_maps", "Dump the target's readable memory regions (start-end perms).",
            JSONObject(), emptyList()))
        return t
    }
}
