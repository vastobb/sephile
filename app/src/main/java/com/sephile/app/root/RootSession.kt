package com.sephile.app.root

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter

enum class SessionState { DISCONNECTED, STARTING, READY, ERROR }

/**
 * Client for [Daemon]. Spawns the engine as a root process via `su` +
 * `app_process` (see RESEARCH.md) and talks to it over stdin/stdout.
 *
 * The app process itself never ptraces; it only sends text commands here.
 */
object RootSession {

    private val _state = MutableStateFlow(SessionState.DISCONNECTED)
    val state: StateFlow<SessionState> = _state

    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail

    @Volatile private var process: Process? = null
    @Volatile private var writer: PrintWriter? = null
    @Volatile private var reader: BufferedReader? = null
    @Volatile private var starting = false
    private val lock = Any()

    /** True once the daemon handshake completed. */
    fun isReady(): Boolean = _state.value == SessionState.READY

    /** Spawn the daemon. Safe to call repeatedly; only one start runs at a time. */
    suspend fun start(context: Context): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (_state.value == SessionState.READY && process?.isAlive == true) return@withContext true
            if (starting) return@withContext false
            starting = true
            _state.value = SessionState.STARTING
            _detail.value = "Starting root engine…"
        }
        try {
            val app = context.applicationInfo
            val apk = app.sourceDir
            val lib = app.nativeLibraryDir + "/libsephile_jni.so"
            if (!java.io.File(lib).exists()) {
                fail("Native library missing at $lib")
                return@withContext false
            }

            val p = Runtime.getRuntime().exec("su")
            val w = PrintWriter(OutputStreamWriter(p.outputStream), true)
            val r = BufferedReader(InputStreamReader(p.inputStream))

            // Drain stderr so app_process / su failures aren't lost.
            val errBuf = StringBuilder()
            Thread {
                try {
                    p.errorStream.bufferedReader().forEachLine { line ->
                        synchronized(errBuf) { errBuf.append(line).append('\n') }
                    }
                } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }

            // Hand off stdin/stdout to app_process. `exec` keeps the same fds so
            // our pipes connect straight to the daemon.
            w.println("export CLASSPATH=$apk")
            w.println("exec /system/bin/app_process /system/bin com.sephile.app.root.Daemon $lib")

            // Handshake: read until the daemon banner (skip any su noise).
            var ready = false
            val deadline = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) break
                val line = try { r.readLine() } catch (t: Throwable) { null } ?: break
                if (line.startsWith("OK SEPHILE")) { ready = true; break }
            }

            if (!ready) {
                p.destroy()
                val err = synchronized(errBuf) { errBuf.toString().trim() }.take(400)
                fail(if (err.isNotEmpty()) "Engine failed: $err" else "Engine did not start (root denied, or app_process unavailable)")
                return@withContext false
            }

            process = p
            writer = w
            reader = r
            synchronized(lock) {
                _state.value = SessionState.READY
                _detail.value = "Engine ready"
            }
            true
        } catch (t: Throwable) {
            fail("Spawn failed: ${t.message}")
            false
        } finally {
            starting = false
        }
    }

    /** Send one command; returns the daemon's `OK …` payload, throws on `ERR`. */
    fun request(cmd: String): String = synchronized(lock) {
        val w = writer ?: error("Engine not running")
        val r = reader ?: error("Engine not running")
        w.println(cmd)
        val line = r.readLine() ?: run {
            _state.value = SessionState.ERROR
            _detail.value = "Engine closed the connection"
            error("engine closed")
        }
        if (line.startsWith("OK")) {
            line.removePrefix("OK").trim()
        } else {
            error(line.removePrefix("ERR").trim().ifEmpty { "engine error" })
        }
    }

    /** LISTPROCS: `OK <count>` then <count> lines then `END`. */
    fun requestProcesses(): List<ProcEntry> = synchronized(lock) {
        val w = writer ?: error("Engine not running")
        val r = reader ?: error("Engine not running")
        w.println("LISTPROCS")
        val head = r.readLine() ?: error("engine closed")
        if (!head.startsWith("OK")) error(head.removePrefix("ERR").trim())
        val count = head.removePrefix("OK").trim().toIntOrNull() ?: 0
        val out = ArrayList<ProcEntry>(count)
        repeat(count) {
            val l = r.readLine() ?: return@repeat
            val pid = l.substringBefore(' ').toIntOrNull() ?: return@repeat
            val rest = l.substringAfter(' ', "")
            val uid = rest.substringBefore(' ').toIntOrNull() ?: -1
            val name = rest.substringAfter(' ', "")
            out.add(ProcEntry(pid, uid, name))
        }
        val end = r.readLine() // "END"
        out
    }

    fun stop() {
        synchronized(lock) {
            try { writer?.println("QUIT") } catch (_: Throwable) {}
            try { process?.destroy() } catch (_: Throwable) {}
            process = null; writer = null; reader = null
            _state.value = SessionState.DISCONNECTED
            _detail.value = ""
        }
    }

    private fun fail(msg: String) {
        synchronized(lock) {
            _state.value = SessionState.ERROR
            _detail.value = msg
        }
    }
}

data class ProcEntry(val pid: Int, val uid: Int, val name: String)
