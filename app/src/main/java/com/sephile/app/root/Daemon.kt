package com.sephile.app.root

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter

/**
 * Root memory-engine daemon.
 *
 * Started by the app via:
 *
 *   su -c 'CLASSPATH=<base.apk> app_process /system/bin com.sephile.app.root.Daemon <lib-path>'
 *
 * so it runs as uid 0 in the privileged su/magisk SELinux domain — the only
 * Android context allowed to ptrace another app (RESEARCH.md §1–3).
 *
 * Protocol (newline-delimited over stdin/stdout):
 *   request  -> "CMD arg arg …"
 *   response -> "OK …"  |  "ERR <reason>"
 * Raw bytes are hex-encoded. Exits when stdin hits EOF (app closed the pipe).
 *
 * All native calls are serialised behind [nativeLock] so the freeze loop cannot
 * race a scan.
 */
object Daemon {

    private lateinit var bridge: NativeBridge
    private var handle = 0L
    private val nativeLock = Any()

    // addr -> (typeId, intVal, floatVal) to keep re-writing (freeze).
    private val frozen = HashMap<Long, Triple<Int, Long, Double>>()
    @Volatile private var freezeRunning = false

    @JvmStatic
    fun main(args: Array<String>) {
        val libPath = args.firstOrNull()
        try {
            if (libPath != null) System.load(libPath) else System.loadLibrary("sephile_jni")
        } catch (t: Throwable) {
            System.out.println("ERR cannot load native lib: ${t.message}")
            return
        }

        bridge = NativeBridge()
        handle = synchronized(nativeLock) { bridge.nativeInit() }

        System.out.println("OK SEPHILE_DAEMON_1 uid=${android.os.Process.myUid()}")

        // Freeze loop: re-apply frozen values ~10x/sec.
        freezeRunning = true
        Thread {
            while (freezeRunning) {
                val snapshot: List<Map.Entry<Long, Triple<Int, Long, Double>>>
                synchronized(frozen) { snapshot = frozen.entries.toList() }
                for ((addr, spec) in snapshot) {
                    synchronized(nativeLock) {
                        try { bridge.nativeEditValue(handle, addr, spec.first, spec.second, spec.third) }
                        catch (_: Throwable) {}
                    }
                }
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }

        val reader = BufferedReader(InputStreamReader(System.`in`))
        val writer = PrintWriter(OutputStreamWriter(System.out), true)

        while (true) {
            val line = try { reader.readLine() } catch (t: Throwable) { null } ?: break
            if (line.isBlank()) continue
            val parts = line.trim().split(' ')
            val cmd = parts[0].uppercase()
            try {
                when (cmd) {
                    "PING" -> writer.println("OK PONG")
                    "INIT" -> writer.println("OK $handle")
                    "ATTACH" -> {
                        val pid = parts.getOrNull(1)?.toIntOrNull()
                        if (pid == null) writer.println("ERR bad pid")
                        else {
                            val ok = synchronized(nativeLock) { bridge.nativeAttach(handle, pid) }
                            writer.println(if (ok) "OK 1" else "ERR attach refused (EPERM/SELinux or bad pid)")
                        }
                    }
                    "DETACH" -> {
                        synchronized(nativeLock) { bridge.nativeDetach(handle) }
                        writer.println("OK")
                    }
                    "STATUS" -> {
                        val a = synchronized(nativeLock) { bridge.nativeIsAttached(handle) }
                        writer.println(if (a) "OK attached" else "OK detached")
                    }
                    "SCAN" -> writer.println(doScan(parts))
                    "COUNT" -> writer.println("OK ${synchronized(nativeLock) { bridge.nativeCandidateCount(handle) }}")
                    "CANDIDATE" -> {
                        val i = parts.getOrNull(1)?.toIntOrNull()
                        if (i == null) writer.println("ERR bad index")
                        else {
                            val (addr, hex) = synchronized(nativeLock) {
                                bridge.nativeCandidateAddress(handle, i) to bridge.nativeCandidateHex(handle, i)
                            }
                            writer.println("OK $addr $hex")
                        }
                    }
                    "PEEK" -> {
                        val addr = parts.getOrNull(1)?.toLongOrNull()
                        val len = parts.getOrNull(2)?.toIntOrNull()
                        if (addr == null || len == null) writer.println("ERR bad peek args")
                        else {
                            val bytes = synchronized(nativeLock) { bridge.nativePeekMemory(handle, addr, len) }
                            writer.println("OK " + (bytes?.joinToString("") { "%02X".format(it) } ?: ""))
                        }
                    }
                    "POKE" -> {
                        val addr = parts.getOrNull(1)?.toLongOrNull()
                        val type = parts.getOrNull(2)?.toIntOrNull() ?: 2
                        val iv = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                        val fv = parts.getOrNull(4)?.toDoubleOrNull() ?: 0.0
                        if (addr == null) writer.println("ERR bad poke addr")
                        else {
                            val ok = synchronized(nativeLock) { bridge.nativeEditValue(handle, addr, type, iv, fv) }
                            writer.println(if (ok) "OK 1" else "OK 0")
                        }
                    }
                    "FREEZE" -> {
                        val addr = parts.getOrNull(1)?.toLongOrNull()
                        val enable = parts.getOrNull(2) == "1"
                        val type = parts.getOrNull(3)?.toIntOrNull() ?: 2
                        val iv = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                        val fv = parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0
                        if (addr == null) writer.println("ERR bad freeze addr")
                        else {
                            if (enable) synchronized(frozen) { frozen[addr] = Triple(type, iv, fv) }
                            else synchronized(frozen) { frozen.remove(addr) }
                            writer.println("OK")
                        }
                    }
                    "LISTPROCS" -> writeProcesses(writer)
                    "QUIT" -> { writer.println("OK BYE"); break }
                    else -> writer.println("ERR unknown command $cmd")
                }
            } catch (t: Throwable) {
                writer.println("ERR ${t.message ?: t.javaClass.simpleName}")
            }
        }

        freezeRunning = false
        synchronized(nativeLock) { try { bridge.nativeDestroy(handle) } catch (_: Throwable) {} }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun doScan(parts: List<String>): String {
        val phase = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val vtype = parts.getOrNull(2)?.toIntOrNull() ?: 2
        val stype = parts.getOrNull(3)?.toIntOrNull() ?: 0
        val tval = parts.getOrNull(4)?.toLongOrNull() ?: 0L
        val tfval = parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0
        val fdelta = parts.getOrNull(6)?.toLongOrNull() ?: 0L
        val writable = parts.getOrNull(7) == "1"
        val pid = parts.getOrNull(8)?.toIntOrNull() ?: -1

        return synchronized(nativeLock) {
            if (phase == 0) {
                val n = bridge.nativeFirstScan(handle, pid, vtype, stype, tval, tfval, fdelta, writable)
                if (n >= 0) "OK $n" else "ERR first scan failed"
            } else {
                val n = bridge.nativeNextScan(handle, vtype, stype, tval, tfval, fdelta, writable)
                if (n >= 0) "OK $n" else "ERR next scan failed"
            }
        }
    }

    /** Root can read /proc for every process; return "pid uid name" lines. */
    private fun writeProcesses(writer: PrintWriter) {
        val procs = ArrayList<String>()
        val entries = File("/proc").list() ?: emptyArray()
        for (e in entries) {
            val pid = e.toIntOrNull() ?: continue
            val name = readFirstLine("/proc/$e/cmdline")?.split('\u0000')?.firstOrNull()
            if (name.isNullOrBlank()) continue
            if (name.contains("sephile")) continue
            val uid = readUid("/proc/$e/status")
            procs.add("$pid $uid $name")
        }
        procs.sortBy { it.substringBefore(' ').toIntOrNull() ?: 0 }
        writer.println("OK ${procs.size}")
        for (p in procs) writer.println(p)
        writer.println("END")
    }

    private fun readFirstLine(path: String): String? = try {
        File(path).inputStream().bufferedReader().use { it.readLine() }
    } catch (t: Throwable) { null }

    private fun readUid(statusPath: String): Int = try {
        File(statusPath).inputStream().bufferedReader().useLines { lines ->
            lines.firstOrNull { it.startsWith("Uid:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull() ?: -1
        }
    } catch (t: Throwable) { -1 }
}
