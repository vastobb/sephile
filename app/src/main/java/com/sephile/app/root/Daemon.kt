package com.sephile.app.root

import java.io.BufferedReader
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.io.RandomAccessFile

/**
 * Root memory-engine daemon — pure JVM, no native library.
 *
 * Started by the app via:
 *
 *   su -c 'CLASSPATH=<base.apk> app_process /system/bin com.sephile.app.root.Daemon'
 *
 * so it runs as uid 0 in the privileged su/magisk SELinux domain — the only
 * Android context allowed to open another app's /proc/<pid>/mem (the open does
 * a PTRACE_MODE_ATTACH check that root passes). See RESEARCH.md.
 *
 * This deliberately does NOT load a native .so: doing so from app_process hit
 * linker-namespace / extraction problems, whereas reading /proc/<pid>/mem as
 * root needs nothing but the file itself.
 *
 * Protocol (newline-delimited over stdin/stdout):
 *   request  -> "CMD arg arg …"
 *   response -> "OK …"  |  "ERR <reason>"
 * Raw bytes are hex-encoded. Exits when stdin hits EOF (app closed the pipe).
 */
object Daemon {

    // ---- engine state (all native access serialised behind [stateLock]) ----
    private val stateLock = Any()
    private var pid = -1
    private var mem: RandomAccessFile? = null
    private data class Cand(val addr: Long, var hex: String)
    private val candidates = ArrayList<Cand>()
    private val frozen = HashMap<Long, Triple<Int, Long, Double>>()
    @Volatile private var freezeRunning = false

    // value type ids match com.sephile.app.model.ValueType
    private const val BYTE = 0; private const val SHORT = 1; private const val DWORD = 2
    private const val QWORD = 3; private const val FLOAT = 4; private const val DOUBLE = 5
    // search type ids match com.sephile.app.model.SearchType
    private const val EXACT = 0; private const val GREATER = 3; private const val LESS = 4

    @JvmStatic
    fun main(args: Array<String>) {
        // Speak over raw fd 0/1: app_process may redirect System.out to logcat,
        // which would swallow this protocol. fd1 is the client's pipe, fd0 its commands.
        val reader = BufferedReader(InputStreamReader(FileInputStream(FileDescriptor.`in`)))
        val writer = PrintStream(FileOutputStream(FileDescriptor.out), true)

        writer.println("OK SEPHILE_DAEMON_1 uid=${android.os.Process.myUid()}")

        // Freeze loop: re-apply frozen values ~10x/sec.
        freezeRunning = true
        Thread {
            while (freezeRunning) {
                val snap: List<Map.Entry<Long, Triple<Int, Long, Double>>>
                synchronized(frozen) { snap = frozen.entries.toList() }
                for ((addr, spec) in snap) {
                    synchronized(stateLock) {
                        try { writeValue(addr, spec.first, spec.second, spec.third) } catch (_: Throwable) {}
                    }
                }
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }

        while (true) {
            val line = try { reader.readLine() } catch (t: Throwable) { null } ?: break
            if (line.isBlank()) continue
            val parts = line.trim().split(' ')
            val cmd = parts[0].uppercase()
            try {
                when (cmd) {
                    "PING" -> writer.println("OK PONG")
                    "INIT" -> writer.println("OK 0")
                    "ATTACH" -> {
                        val p = parts.getOrNull(1)?.toIntOrNull()
                        if (p == null) writer.println("ERR bad pid") else writer.println(attach(p))
                    }
                    "DETACH" -> { detach(); writer.println("OK") }
                    "STATUS" -> writer.println(if (pid > 0) "OK attached" else "OK detached")
                    "SCAN" -> writer.println(doScan(parts))
                    "COUNT" -> writer.println("OK ${synchronized(stateLock) { candidates.size }}")
                    "CANDIDATE" -> {
                        val i = parts.getOrNull(1)?.toIntOrNull()
                        val c = synchronized(stateLock) { i?.let { candidates.getOrNull(it) } }
                        if (c == null) writer.println("ERR bad index") else writer.println("OK ${c.addr} ${c.hex}")
                    }
                    "PEEK" -> {
                        val a = parts.getOrNull(1)?.toLongOrNull()
                        val n = parts.getOrNull(2)?.toIntOrNull()
                        if (a == null || n == null) writer.println("ERR bad peek args")
                        else {
                            // Serialise with the freeze loop — RandomAccessFile shares a file pointer.
                            val hex = synchronized(stateLock) { peekHex(a, n) }
                            writer.println("OK $hex")
                        }
                    }
                    "POKE" -> {
                        val a = parts.getOrNull(1)?.toLongOrNull()
                        val t = parts.getOrNull(2)?.toIntOrNull() ?: DWORD
                        val iv = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                        val fv = parts.getOrNull(4)?.toDoubleOrNull() ?: 0.0
                        if (a == null) writer.println("ERR bad poke addr")
                        else {
                            val ok = synchronized(stateLock) { writeValue(a, t, iv, fv) }
                            writer.println(if (ok) "OK 1" else "OK 0")
                        }
                    }
                    "FREEZE" -> {
                        val a = parts.getOrNull(1)?.toLongOrNull()
                        val enable = parts.getOrNull(2) == "1"
                        val t = parts.getOrNull(3)?.toIntOrNull() ?: DWORD
                        val iv = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                        val fv = parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0
                        if (a == null) writer.println("ERR bad freeze addr")
                        else {
                            if (enable) synchronized(frozen) { frozen[a] = Triple(t, iv, fv) }
                            else synchronized(frozen) { frozen.remove(a) }
                            writer.println("OK")
                        }
                    }
                    // Drop scan results + freezes but KEEP the attach (mem stays open),
                    // so the user can run a fresh first scan on the same process.
                    "CLEAR" -> {
                        synchronized(stateLock) { candidates.clear() }
                        synchronized(frozen) { frozen.clear() }
                        writer.println("OK")
                    }
                    // AOB / signature scan: "PATTERNSCAN 48 89 5C 24 ?? 57 ..." (?? = wildcard).
                    // Returns "OK <n>" then n hex addresses then "END".
                    "PATTERNSCAN" -> {
                        val pat = parts.drop(1).joinToString(" ")
                        writePatternScan(writer, pat)
                    }
                    // Readable memory regions: "OK <n>" then "start-end perms" lines then "END".
                    "MAPS" -> writeMaps(writer)
                    "LISTPROCS" -> writeProcesses(writer)
                    "QUIT" -> { writer.println("OK BYE"); break }
                    else -> writer.println("ERR unknown command $cmd")
                }
            } catch (t: Throwable) {
                writer.println("ERR ${t.message ?: t.javaClass.simpleName}")
            }
        }

        freezeRunning = false
        detach()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ---------------- attach / detach ----------------

    private fun attach(targetPid: Int): String {
        synchronized(stateLock) {
            detachLocked()
            try {
                mem = RandomAccessFile("/proc/$targetPid/mem", "rw")
                pid = targetPid
            } catch (t: Throwable) {
                return "ERR cannot open /proc/$targetPid/mem: ${t.message}"
            }
        }
        return "OK 1"
    }

    private fun detach() { synchronized(stateLock) { detachLocked() } }
    private fun detachLocked() {
        try { mem?.close() } catch (_: Throwable) {}
        mem = null
        pid = -1
        synchronized(frozen) { frozen.clear() }
        candidates.clear()
    }

    // ---------------- memory ops ----------------

    /** Read [len] bytes at [addr]; null on failure / short read. */
    private fun readAt(addr: Long, len: Int): ByteArray? {
        val raf = mem ?: return null
        val buf = ByteArray(len)
        return try {
            raf.seek(addr)
            var off = 0
            while (off < len) {
                val n = raf.read(buf, off, len - off)
                if (n < 0) break
                off += n
            }
            if (off == len) buf else if (off > 0) buf.copyOf(off) else null
        } catch (t: Throwable) { null }
    }

    private fun peekHex(addr: Long, len: Int): String {
        val b = readAt(addr, len.coerceIn(1, 8192)) ?: return ""
        return b.joinToString("") { "%02X".format(it) }
    }

    private fun writeValue(addr: Long, type: Int, intVal: Long, floatVal: Double): Boolean {
        val raf = mem ?: return false
        val bytes = valueBytes(type, intVal, floatVal)
        return try {
            raf.seek(addr)
            raf.write(bytes)
            true
        } catch (t: Throwable) { false }
    }

    // ---------------- scanning ----------------

    private fun doScan(parts: List<String>): String {
        val phase = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val type = parts.getOrNull(2)?.toIntOrNull() ?: DWORD
        val stype = parts.getOrNull(3)?.toIntOrNull() ?: EXACT
        val tval = parts.getOrNull(4)?.toLongOrNull() ?: 0L
        val tfval = parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0
        val fdelta = parts.getOrNull(6)?.toLongOrNull() ?: 0L
        val writable = parts.getOrNull(7) == "1"
        synchronized(stateLock) {
            if (pid > 0 && mem != null) {
                return if (phase == 0) firstScan(type, stype, tval, tfval, fdelta, writable)
                else nextScan(type, stype, tval, tfval, fdelta)
            }
        }
        return "ERR not attached"
    }

    private fun firstScan(type: Int, stype: Int, tval: Long, tfval: Double, fdelta: Long, writable: Boolean): String {
        candidates.clear()
        val regions = parseMaps(pid)
        val typeSize = sizeOf(type)
        val chunk = 64 * 1024
        for (r in regions) {
            if (!r.readable) continue
            if (writable && !r.writable) continue
            var addr = r.start
            while (addr + typeSize <= r.end) {
                val len = minOf(chunk.toLong(), r.end - addr).toInt()
                val buf = readAt(addr, len) ?: break
                var i = 0
                while (i + typeSize <= buf.size) {
                    if (matches(buf, i, type, stype, tval, tfval, fdelta)) {
                        val hex = buf.copyOfRange(i, i + typeSize).joinToString("") { "%02X".format(it) }
                        candidates.add(Cand(addr + i, hex))
                    }
                    i += typeSize
                }
                addr += len
            }
        }
        return "OK ${candidates.size}"
    }

    private fun nextScan(type: Int, stype: Int, tval: Long, tfval: Double, fdelta: Long): String {
        val typeSize = sizeOf(type)
        val next = ArrayList<Cand>()
        for (c in candidates) {
            val buf = readAt(c.addr, typeSize) ?: continue
            if (matches(buf, 0, type, stype, tval, tfval, fdelta)) {
                c.hex = buf.joinToString("") { "%02X".format(it) }
                next.add(c)
            }
        }
        candidates.clear()
        candidates.addAll(next)
        return "OK ${candidates.size}"
    }

    private fun matches(buf: ByteArray, i: Int, type: Int, stype: Int, tval: Long, tfval: Double, fdelta: Long): Boolean {
        when (type) {
            FLOAT -> {
                val cur = Float.fromBits(readLE(buf, i, 4).toInt())
                val t = tfval.toFloat()
                return when (stype) { EXACT -> cur == t; GREATER -> cur > t; LESS -> cur < t; else -> true }
            }
            DOUBLE -> {
                val cur = Double.fromBits(readLE(buf, i, 8))
                return when (stype) { EXACT -> cur == tfval; GREATER -> cur > tfval; LESS -> cur < tfval; else -> true }
            }
            else -> {
                val n = sizeOf(type)
                if (i + n > buf.size) return false
                val cur = readLE(buf, i, n)
                val t = fit(tval, n)
                return when (stype) { EXACT -> cur == t; GREATER -> cur > t; LESS -> cur < t; else -> true }
            }
        }
    }

    // ---------------- helpers ----------------

    private fun sizeOf(type: Int): Int = when (type) {
        BYTE -> 1; SHORT -> 2; DWORD, FLOAT -> 4; QWORD, DOUBLE -> 8; else -> 4
    }

    /** Little-endian signed read of [n] bytes at [i]. */
    private fun readLE(b: ByteArray, i: Int, n: Int): Long {
        var v = 0L
        for (k in n - 1 downTo 0) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
        val shift = 64 - n * 8
        return (v shl shift) shr shift
    }

    /** Truncate to n bytes then sign-extend (so target matches readLE width). */
    private fun fit(v: Long, n: Int): Long { val shift = 64 - n * 8; return (v shl shift) shr shift }

    private fun toLE(v: Long, n: Int): ByteArray = ByteArray(n) { k -> (v shr (8 * k)).toByte() }

    private fun valueBytes(type: Int, intVal: Long, floatVal: Double): ByteArray = when (type) {
        BYTE -> toLE(intVal, 1)
        SHORT -> toLE(intVal, 2)
        DWORD -> toLE(intVal, 4)
        QWORD -> toLE(intVal, 8)
        FLOAT -> toLE(floatVal.toFloat().toRawBits().toLong(), 4)
        DOUBLE -> toLE(floatVal.toRawBits(), 8)
        else -> toLE(intVal, 4)
    }

    private class Region(val start: Long, val end: Long, val readable: Boolean, val writable: Boolean)

    private fun parseMaps(pid: Int): List<Region> {
        val out = ArrayList<Region>()
        try {
            File("/proc/$pid/maps").forEachLine { line ->
                // start-end perms offset dev inode pathname
                val dash = line.indexOf('-')
                if (dash <= 0) return@forEachLine
                val start = line.substring(0, dash).toLongOrNull(16) ?: return@forEachLine
                val space = line.indexOf(' ', dash)
                if (space <= 0) return@forEachLine
                val end = line.substring(dash + 1, space).toLongOrNull(16) ?: return@forEachLine
                val perms = line.substring(space + 1, space + 5)
                if (perms.length < 3) return@forEachLine
                out.add(Region(start, end, perms[0] == 'r', perms[1] == 'w'))
            }
        } catch (t: Throwable) { }
        return out
    }

    /**
     * AOB / signature scan. Pattern is space-separated hex bytes with "??" as
     * wildcard, e.g. "48 89 5C 24 ?? 57". Scans all readable regions for the
     * sequence. Returns "OK <n>" then n hex addresses then "END" (capped).
     */
    private fun writePatternScan(writer: PrintStream, pattern: String) {
        val toks = pattern.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (toks.isEmpty() || toks.size > 128) { writer.println("ERR bad pattern"); return }
        val pat = IntArray(toks.size) { -1 }
        for (i in toks.indices) {
            val t = toks[i]
            if (t == "??" || t == "?" || t == "*") { pat[i] = -1; continue }
            val v = t.toIntOrNull(16) ?: run { writer.println("ERR bad byte '$t'"); return }
            if (v !in 0..255) { writer.println("ERR bad byte '$t'"); return }
            pat[i] = v
        }
        val minPat = pat.count { it >= 0 }
        if (minPat == 0) { writer.println("ERR pattern is all wildcards"); return }

        val hits = ArrayList<Long>()
        val cap = 512
        val chunk = 1024 * 1024
        synchronized(stateLock) {
            if (pid <= 0 || mem == null) { writer.println("ERR not attached"); return }
            for (r in parseMaps(pid)) {
                if (!r.readable) continue
                var addr = r.start
                while (addr + pat.size <= r.end && hits.size < cap) {
                    val len = minOf(chunk.toLong(), r.end - addr).toInt()
                    val buf = readAt(addr, len) ?: break
                    var i = 0
                    while (i + pat.size <= buf.size && hits.size < cap) {
                        var ok = true
                        for (j in pat.indices) {
                            val p = pat[j]
                            if (p >= 0 && (buf[i + j].toInt() and 0xFF) != p) { ok = false; break }
                        }
                        if (ok) hits.add(addr + i)
                        i += 1
                    }
                    addr += len
                }
                if (hits.size >= cap) break
            }
        }
        writer.println("OK ${hits.size}")
        for (a in hits) writer.println("0x" + a.toString(16))
        writer.println("END")
    }

    /** Readable memory regions: "OK <n>" then "start-end perms" lines then "END". */
    private fun writeMaps(writer: PrintStream) {
        val regions = synchronized(stateLock) { if (pid > 0) parseMaps(pid) else emptyList() }
        val readable = regions.filter { it.readable }
        writer.println("OK ${readable.size}")
        for (r in readable) {
            val perms = (if (r.readable) "r" else "-") + (if (r.writable) "w" else "-")
            writer.println("${r.start.toString(16)}-${r.end.toString(16)} $perms")
        }
        writer.println("END")
    }

    private fun writeProcesses(writer: PrintStream) {
        val procs = ArrayList<String>()
        val entries = File("/proc").list() ?: emptyArray()
        for (e in entries) {
            val p = e.toIntOrNull() ?: continue
            val name = try {
                File("/proc/$e/cmdline").inputStream().bufferedReader().use { it.readLine() }
                    ?.split('\u0000')?.firstOrNull()
            } catch (t: Throwable) { null }
            if (name.isNullOrBlank()) continue
            if (name.contains("sephile")) continue
            val uid = try {
                File("/proc/$e/status").inputStream().bufferedReader().useLines { ls ->
                    ls.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull() ?: -1
                }
            } catch (t: Throwable) { -1 }
            procs.add("$p $uid $name")
        }
        procs.sortBy { it.substringBefore(' ').toIntOrNull() ?: 0 }
        writer.println("OK ${procs.size}")
        for (x in procs) writer.println(x)
        writer.println("END")
    }
}
