package com.sephile.app.repository

import com.sephile.app.root.RootSession

/**
 * A tiny command-script engine that runs against the root daemon.
 *
 * The earlier plan vendored Lua 5.4 natively; that is not bound yet, so this
 * exposes a working, engine-backed script runner instead. Each non-empty,
 * non-`#` line is a command:
 *
 *     # comment
 *     attach <pid>
 *     detach
 *     list
 *     scan <value> [type] [writable|all]
 *     next <value> [type]
 *     peek <addrHex> <len>
 *     poke <addrHex> <value> [type]
 *     count
 *
 * Returns combined console output; never throws.
 */
class LuaRepository {

    private var attachedPid = -1

    val engineAvailable: Boolean get() = RootSession.isReady()

    fun attach(pid: Int): Boolean {
        if (!engineAvailable) return false
        return try { RootSession.request("ATTACH $pid").let { attachedPid = pid; true } }
        catch (t: Throwable) { false }
    }

    fun exec(script: String): String {
        if (!engineAvailable) {
            return "Engine not running. Grant root and wait for it to start.\n" + RootSession.detail.value
        }
        val sb = StringBuilder()
        script.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val p = line.split(Regex("\\s+"))
            try {
                when (p[0].lowercase()) {
                    "attach" -> {
                        val pid = p.getOrNull(1)?.toIntOrNull() ?: return@forEach sb.appendLine("attach: need a pid")
                        val ok = attach(pid)
                        sb.appendLine(if (ok) "attached to pid $pid" else "attach failed")
                    }
                    "detach" -> { RootSession.request("DETACH"); attachedPid = -1; sb.appendLine("detached") }
                    "list" -> {
                        val procs = RootSession.requestProcesses()
                        sb.appendLine("${procs.size} processes")
                        procs.take(30).forEach { sb.appendLine("  ${it.pid}\t${it.name}") }
                        if (procs.size > 30) sb.appendLine("  …")
                    }
                    "scan" -> {
                        val v = p.getOrNull(1) ?: return@forEach sb.appendLine("scan: need a value")
                        val writable = (p.getOrNull(3) ?: "writable") != "all"
                        val r = RootSession.request("SCAN 0 ${typeId(p.getOrNull(2))} 0 $v 0.0 0 ${if (writable) "1" else "0"} ${attachedPid}")
                        sb.appendLine("scan -> $r candidate(s)")
                    }
                    "next" -> {
                        val v = p.getOrNull(1) ?: return@forEach sb.appendLine("next: need a value")
                        val r = RootSession.request("SCAN 1 ${typeId(p.getOrNull(2))} 0 $v 0.0 0 1 -1")
                        sb.appendLine("next -> $r candidate(s)")
                    }
                    "peek" -> {
                        val a = hexToLong(p.getOrNull(1)) ?: return@forEach sb.appendLine("peek: bad addr")
                        val len = p.getOrNull(2)?.toIntOrNull() ?: 16
                        val hex = RootSession.request("PEEK $a $len")
                        sb.appendLine(hex.chunked(2).joinToString(" "))
                    }
                    "poke" -> {
                        val a = hexToLong(p.getOrNull(1)) ?: return@forEach sb.appendLine("poke: bad addr")
                        val v = p.getOrNull(2) ?: return@forEach sb.appendLine("poke: need value")
                        val r = RootSession.request("POKE $a ${typeId(p.getOrNull(3))} $v 0.0")
                        sb.appendLine(if (r == "1") "poked" else "poke failed")
                    }
                    "count" -> sb.appendLine("count -> ${RootSession.request("COUNT")}")
                    else -> sb.appendLine("? unknown command '${p[0]}'")
                }
            } catch (t: Throwable) {
                sb.appendLine("error: ${t.message}")
            }
        }
        return sb.toString().ifEmpty { "(no output)" }
    }

    private fun typeId(t: String?): Int = when (t?.lowercase()) {
        null, "dword", "int", "4" -> 2
        "byte", "1" -> 0
        "short", "2" -> 1
        "qword", "8" -> 3
        "float" -> 4
        "double" -> 5
        else -> 2
    }

    private fun hexToLong(s: String?): Long? =
        s?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16)

    fun release() {}
}
