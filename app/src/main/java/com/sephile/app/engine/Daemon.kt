package com.sephile.app.engine

import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.io.RandomAccessFile

/**
 * Root memory daemon, spawned via:
 *   su -c 'CLASSPATH=<base.apk> app_process /system/bin com.sephile.app.engine.Daemon'
 *
 * Runs as uid 0 (privileged domain) — the only Android context that can open
 * another app's /proc/<pid>/mem (open() does a PTRACE_MODE_ATTACH check that
 * root passes). Pure JVM: parses /proc/<pid>/maps and reads/writes
 * /proc/<pid>/mem. No native lib, no dlopen, no linker-namespace issues.
 *
 * Speaks a newline text protocol over fd0/fd1 (raw FileDescriptor — app_process
 * can redirect System.out to logcat, which would swallow the protocol).
 *
 * Key command: FINDMC — scans /proc for the running Minecraft process so the
 * client can auto-attach without the user picking a pid.
 */
object Daemon {

    private val lock = Any()
    private var pid = -1
    private var mem: RandomAccessFile? = null

    private data class Cand(val addr: Long, var hex: String)
    private val cands = ArrayList<Cand>()
    private val frozen = HashMap<Long, Triple<Int, Long, Double>>()
    @Volatile private var freezeOn = false

    // value type ids
    private const val BYTE = 0; private const val SHORT = 1; private const val DWORD = 2
    private const val QWORD = 3; private const val FLOAT = 4; private const val DOUBLE = 5
    // search type ids
    private const val EXACT = 0; private const val GREATER = 3; private const val LESS = 4

    /** Process names to treat as "Minecraft" when auto-finding. */
    private val MC_PKGS = listOf(
        "com.mojang.minecraftpe",
        "com.mojang.minecraftpe.beta",
        "net.kdt.pocketminecraft", // old MCPE
        "com.mojang.minecraft"
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val reader = BufferedReader(InputStreamReader(FileInputStream(FileDescriptor.`in`)))
        val out = PrintStream(FileOutputStream(FileDescriptor.out), true)
        out.println("OK SEPHILE_MC_1 uid=${android.os.Process.myUid()}")

        freezeOn = true
        Thread {
            while (freezeOn) {
                val snap: List<Map.Entry<Long, Triple<Int, Long, Double>>>
                synchronized(frozen) { snap = frozen.entries.toList() }
                for ((a, spec) in snap) {
                    synchronized(lock) { try { writeVal(a, spec.first, spec.second, spec.third) } catch (_: Throwable) {} }
                }
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }

        while (true) {
            val line = try { reader.readLine() } catch (t: Throwable) { null } ?: break
            if (line.isBlank()) continue
            val p = line.trim().split(' ')
            val cmd = p[0].uppercase()
            try {
                when (cmd) {
                    "PING" -> out.println("OK PONG")
                    "FINDMC" -> out.println("OK ${findMinecraft()}")
                    "ATTACH" -> {
                        val t = p.getOrNull(1)?.toIntOrNull()
                        out.println(if (t == null) "ERR bad pid" else attach(t))
                    }
                    "DETACH" -> { detach(); out.println("OK") }
                    "STATUS" -> out.println(if (pid > 0) "OK attached $pid" else "OK detached")
                    "SCAN" -> out.println(doScan(p))
                    "COUNT" -> out.println("OK ${synchronized(lock) { cands.size }}")
                    "CANDIDATE" -> {
                        val i = p.getOrNull(1)?.toIntOrNull()
                        val c = synchronized(lock) { i?.let { cands.getOrNull(it) } }
                        out.println(if (c == null) "ERR bad index" else "OK ${c.addr} ${c.hex}")
                    }
                    "PEEK" -> {
                        val a = p.getOrNull(1)?.toLongOrNull()
                        val n = p.getOrNull(2)?.toIntOrNull()
                        if (a == null || n == null) out.println("ERR bad peek")
                        else out.println("OK ${synchronized(lock) { peekHex(a, n) }}")
                    }
                    "POKE" -> {
                        val a = p.getOrNull(1)?.toLongOrNull()
                        val t = p.getOrNull(2)?.toIntOrNull() ?: DWORD
                        val iv = p.getOrNull(3)?.toLongOrNull() ?: 0L
                        val fv = p.getOrNull(4)?.toDoubleOrNull() ?: 0.0
                        if (a == null) out.println("ERR bad poke")
                        else out.println(if (synchronized(lock) { writeVal(a, t, iv, fv) }) "OK 1" else "OK 0")
                    }
                    "FREEZE" -> {
                        val a = p.getOrNull(1)?.toLongOrNull()
                        val en = p.getOrNull(2) == "1"
                        val t = p.getOrNull(3)?.toIntOrNull() ?: DWORD
                        val iv = p.getOrNull(4)?.toLongOrNull() ?: 0L
                        val fv = p.getOrNull(5)?.toDoubleOrNull() ?: 0.0
                        if (a == null) out.println("ERR bad freeze")
                        else {
                            if (en) synchronized(frozen) { frozen[a] = Triple(t, iv, fv) }
                            else synchronized(frozen) { frozen.remove(a) }
                            out.println("OK")
                        }
                    }
                    "CLEAR" -> {
                        synchronized(lock) { cands.clear() }
                        synchronized(frozen) { frozen.clear() }
                        out.println("OK")
                    }
                    "PATTERNSCAN" -> writePattern(out, p.drop(1).joinToString(" "))
                    "MAPS" -> writeMaps(out)
                    "QUIT" -> { out.println("OK BYE"); break }
                    else -> out.println("ERR unknown $cmd")
                }
            } catch (t: Throwable) {
                out.println("ERR ${t.message ?: t.javaClass.simpleName}")
            }
        }
        freezeOn = false
        detach()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ── find the running Minecraft process by scanning /proc ────────────────
    private fun findMinecraft(): Int {
        val procs = File("/proc").list() ?: return -1
        for (e in procs) {
            if (!e.all { it.isDigit() }) continue
            val cmdline = try {
                File("/proc/$e/cmdline").inputStream().bufferedReader().use { it.readText() }
            } catch (t: Throwable) { continue }
            val name = cmdline.split('\u0000').firstOrNull() ?: continue
            if (MC_PKGS.any { name == it || name.startsWith(it) }) return e.toIntOrNull() ?: -1
        }
        return -1
    }

    // ── attach / detach ─────────────────────────────────────────────────────
    private fun attach(target: Int): String {
        synchronized(lock) {
            detachLocked()
            try {
                mem = RandomAccessFile("/proc/$target/mem", "rw")
                pid = target
            } catch (t: Throwable) {
                return "ERR cannot open /proc/$target/mem: ${t.message}"
            }
        }
        return "OK 1"
    }

    private fun detach() { synchronized(lock) { detachLocked() } }
    private fun detachLocked() {
        try { mem?.close() } catch (_: Throwable) {}
        mem = null; pid = -1
        synchronized(frozen) { frozen.clear() }
        cands.clear()
    }

    // ── memory ops ──────────────────────────────────────────────────────────
    private fun readAt(addr: Long, len: Int): ByteArray? {
        val r = mem ?: return null
        val b = ByteArray(len)
        return try {
            r.seek(addr)
            var off = 0
            while (off < len) { val n = r.read(b, off, len - off); if (n < 0) break; off += n }
            if (off == len) b else if (off > 0) b.copyOf(off) else null
        } catch (t: Throwable) { null }
    }

    private fun peekHex(addr: Long, len: Int): String {
        val b = readAt(addr, len.coerceIn(1, 8192)) ?: return ""
        return b.joinToString("") { "%02X".format(it) }
    }

    private fun writeVal(addr: Long, type: Int, intVal: Long, floatVal: Double): Boolean {
        val r = mem ?: return false
        return try {
            r.seek(addr); r.write(valBytes(type, intVal, floatVal)); true
        } catch (t: Throwable) { false }
    }

    // ── value scan ──────────────────────────────────────────────────────────
    private fun doScan(p: List<String>): String = synchronized(lock) {
        if (pid <= 0 || mem == null) return "ERR not attached"
        val phase = p.getOrNull(1)?.toIntOrNull() ?: 0
        val type = p.getOrNull(2)?.toIntOrNull() ?: DWORD
        val st = p.getOrNull(3)?.toIntOrNull() ?: EXACT
        val tv = p.getOrNull(4)?.toLongOrNull() ?: 0L
        val tf = p.getOrNull(5)?.toDoubleOrNull() ?: 0.0
        val writable = p.getOrNull(6) == "1"
        return if (phase == 0) firstScan(type, st, tv, tf, writable) else nextScan(type, st, tv, tf)
    }

    private fun firstScan(type: Int, st: Int, tv: Long, tf: Double, writable: Boolean): String {
        cands.clear()
        val sz = sizeOf(type)
        val chunk = 64 * 1024
        for (r in parseMaps(pid)) {
            if (!r.readable) continue
            if (writable && !r.writable) continue
            var a = r.start
            while (a + sz <= r.end) {
                val len = minOf(chunk.toLong(), r.end - a).toInt()
                val buf = readAt(a, len) ?: break
                var i = 0
                while (i + sz <= buf.size) {
                    if (match(buf, i, type, st, tv, tf)) {
                        cands.add(Cand(a + i, buf.copyOfRange(i, i + sz).joinToString("") { "%02X".format(it) }))
                    }
                    i += sz
                }
                a += len
            }
        }
        return "OK ${cands.size}"
    }

    private fun nextScan(type: Int, st: Int, tv: Long, tf: Double): String {
        val sz = sizeOf(type)
        val next = ArrayList<Cand>()
        for (c in cands) {
            val buf = readAt(c.addr, sz) ?: continue
            if (match(buf, 0, type, st, tv, tf)) {
                c.hex = buf.joinToString("") { "%02X".format(it) }
                next.add(c)
            }
        }
        cands.clear(); cands.addAll(next)
        return "OK ${cands.size}"
    }

    private fun match(b: ByteArray, i: Int, type: Int, st: Int, tv: Long, tf: Double): Boolean {
        when (type) {
            FLOAT -> { val cur = Float.fromBits(rd(b, i, 4).toInt()); val t = tf.toFloat()
                return when (st) { EXACT -> cur == t; GREATER -> cur > t; LESS -> cur < t; else -> true } }
            DOUBLE -> { val cur = Double.fromBits(rd(b, i, 8))
                return when (st) { EXACT -> cur == tf; GREATER -> cur > tf; LESS -> cur < tf; else -> true } }
            else -> { val n = sizeOf(type); if (i + n > b.size) return false
                val cur = rd(b, i, n); val t = fit(tv, n)
                return when (st) { EXACT -> cur == t; GREATER -> cur > t; LESS -> cur < t; else -> true } }
        }
    }

    // ── AOB signature scan ──────────────────────────────────────────────────
    private fun writePattern(out: PrintStream, pattern: String) {
        val toks = pattern.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.isEmpty() || toks.size > 128) { out.println("ERR bad pattern"); return }
        val pat = IntArray(toks.size) { -1 }
        for (i in toks.indices) {
            if (toks[i] == "??" || toks[i] == "?" || toks[i] == "*") continue
            val v = toks[i].toIntOrNull(16)
            if (v == null || v !in 0..255) { out.println("ERR bad byte ${toks[i]}"); return }
            pat[i] = v
        }
        if (pat.none { it >= 0 }) { out.println("ERR all wildcards"); return }
        val hits = ArrayList<Long>(); val cap = 512; val chunk = 1024 * 1024
        synchronized(lock) {
            if (pid <= 0 || mem == null) { out.println("ERR not attached"); return }
            for (r in parseMaps(pid)) {
                if (!r.readable) continue
                var a = r.start
                while (a + pat.size <= r.end && hits.size < cap) {
                    val len = minOf(chunk.toLong(), r.end - a).toInt()
                    val buf = readAt(a, len) ?: break
                    var i = 0
                    while (i + pat.size <= buf.size && hits.size < cap) {
                        var ok = true
                        for (j in pat.indices) { val q = pat[j]; if (q >= 0 && (buf[i+j].toInt() and 0xFF) != q) { ok = false; break } }
                        if (ok) hits.add(a + i)
                        i++
                    }
                    a += len
                }
                if (hits.size >= cap) break
            }
        }
        out.println("OK ${hits.size}")
        for (h in hits) out.println("0x" + h.toString(16))
        out.println("END")
    }

    private fun writeMaps(out: PrintStream) {
        val rs = synchronized(lock) { if (pid > 0) parseMaps(pid) else emptyList() }.filter { it.readable }
        out.println("OK ${rs.size}")
        for (r in rs) out.println("${r.start.toString(16)}-${r.end.toString(16)} ${if (r.writable) "rw" else "r"}")
        out.println("END")
    }

    // ── helpers ─────────────────────────────────────────────────────────────
    private fun sizeOf(t: Int) = when (t) { BYTE->1; SHORT->2; DWORD, FLOAT->4; QWORD, DOUBLE->8; else->4 }
    private fun rd(b: ByteArray, i: Int, n: Int): Long {
        var v = 0L
        for (k in n-1 downTo 0) v = (v shl 8) or (b[i+k].toLong() and 0xFF)
        val sh = 64 - n*8; return (v shl sh) shr sh
    }
    private fun fit(v: Long, n: Int): Long { val sh = 64 - n*8; return (v shl sh) shr sh }
    private fun toLe(v: Long, n: Int) = ByteArray(n) { k -> (v shr (8*k)).toByte() }
    private fun valBytes(t: Int, iv: Long, fv: Double) = when (t) {
        BYTE->toLe(iv,1); SHORT->toLe(iv,2); DWORD->toLe(iv,4); QWORD->toLe(iv,8)
        FLOAT->toLe(fv.toFloat().toRawBits().toLong(),4); DOUBLE->toLe(fv.toRawBits(),8); else->toLe(iv,4)
    }

    private class Rgn(val start: Long, val end: Long, val readable: Boolean, val writable: Boolean)
    private fun parseMaps(pid: Int): List<Rgn> {
        val out = ArrayList<Rgn>()
        try {
            File("/proc/$pid/maps").forEachLine { l ->
                val d = l.indexOf('-'); if (d <= 0) return@forEachLine
                val s = l.substring(0, d).toLongOrNull(16) ?: return@forEachLine
                val sp = l.indexOf(' ', d); if (sp <= 0) return@forEachLine
                val e = l.substring(d+1, sp).toLongOrNull(16) ?: return@forEachLine
                val perms = l.substring(sp+1, sp+5)
                if (perms.length < 3) return@forEachLine
                out.add(Rgn(s, e, perms[0]=='r', perms[1]=='w'))
            }
        } catch (t: Throwable) {}
        return out
    }
}
