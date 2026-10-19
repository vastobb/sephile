package com.sephile.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter

enum class EngineState { DOWN, STARTING, UP, ERROR }

/**
 * Client for [Daemon]. Spawns it as root (su + app_process) and talks the text
 * protocol over stdin/stdout. Auto-finds the running Minecraft process so the
 * user never has to pick a pid.
 */
object Engine {

    private val _state = MutableStateFlow(EngineState.DOWN)
    val state: StateFlow<EngineState> = _state

    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail

    private val _targetPid = MutableStateFlow(-1)
    /** Attached Minecraft pid (-1 if none). */
    val targetPid: StateFlow<Int> = _targetPid

    @Volatile private var proc: Process? = null
    @Volatile private var w: PrintWriter? = null
    @Volatile private var r: BufferedReader? = null
    private val lock = Any()

    fun isUp(): Boolean = _state.value == EngineState.UP

    /** Spawn the root daemon and auto-attach Minecraft. Idempotent. */
    suspend fun start(context: Context): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (_state.value == EngineState.UP && proc?.isAlive == true) return@withContext true
            _state.value = EngineState.STARTING
            _detail.value = "Starting root engine…"
        }
        try {
            val apk = context.applicationInfo.sourceDir
            val p = Runtime.getRuntime().exec("su")
            val pw = PrintWriter(OutputStreamWriter(p.outputStream), true)
            val pr = BufferedReader(InputStreamReader(p.inputStream))

            val err = StringBuilder()
            Thread {
                try { p.errorStream.bufferedReader().forEachLine { l -> synchronized(err) { err.append(l).append('\n') } } }
                catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }

            pw.println("export CLASSPATH=$apk")
            pw.println("exec /system/bin/app_process /system/bin com.sephile.app.engine.Daemon")

            var ok = false
            val deadline = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive) break
                val line = try { pr.readLine() } catch (t: Throwable) { null } ?: break
                if (line.startsWith("OK SEPHILE")) { ok = true; break }
            }
            if (!ok) {
                p.destroy()
                val e = synchronized(err) { err.toString().trim() }.take(300)
                fail(if (e.isNotEmpty()) "Engine failed: $e" else "Root denied or app_process unavailable")
                return@withContext false
            }
            proc = p; w = pw; r = pr
            _state.value = EngineState.UP
            _detail.value = "Engine up"
            true
        } catch (t: Throwable) {
            fail("Spawn failed: ${t.message}"); false
        }
    }

    /**
     * Find the running Minecraft process and attach to it.
     * @return the pid, or -1 if Minecraft isn't running / attach failed.
     */
    suspend fun autoAttachMinecraft(): Int = withContext(Dispatchers.IO) {
        if (!isUp()) return@withContext -1
        val found = try { request("FINDMC").toIntOrNull() ?: -1 } catch (t: Throwable) { -1 }
        if (found <= 0) { _targetPid.value = -1; return@withContext -1 }
        val attached = try { request("ATTACH $found"); true } catch (t: Throwable) { false }
        if (attached) { _targetPid.value = found; _detail.value = "Attached to Minecraft (pid $found)"; found }
        else { _targetPid.value = -1; -1 }
    }

    fun detach() {
        try { request("DETACH") } catch (_: Throwable) {}
        _targetPid.value = -1
    }

    /** Send a command; returns the `OK …` payload, throws on `ERR`. */
    fun request(cmd: String): String = synchronized(lock) {
        val pw = w ?: error("engine down")
        val pr = r ?: error("engine down")
        pw.println(cmd)
        val line = pr.readLine() ?: run { _state.value = EngineState.ERROR; error("engine closed") }
        if (line.startsWith("OK")) line.removePrefix("OK").trim()
        else error(line.removePrefix("ERR").trim().ifEmpty { "engine error" })
    }

    /** Multi-line reply: "OK n", n lines, "END". */
    fun requestMulti(cmd: String): List<String> = synchronized(lock) {
        val pw = w ?: error("engine down")
        val pr = r ?: error("engine down")
        pw.println(cmd)
        val head = pr.readLine() ?: error("engine closed")
        if (!head.startsWith("OK")) error(head.removePrefix("ERR").trim())
        val n = head.removePrefix("OK").trim().toIntOrNull() ?: 0
        val out = ArrayList<String>(n)
        repeat(n) { pr.readLine()?.let { out.add(it) } ?: return@repeat }
        pr.readLine()
        out
    }

    fun stop() {
        synchronized(lock) {
            try { w?.println("QUIT") } catch (_: Throwable) {}
            try { proc?.destroy() } catch (_: Throwable) {}
            proc = null; w = null; r = null
            _state.value = EngineState.DOWN
            _targetPid.value = -1
        }
    }

    private fun fail(msg: String) {
        _state.value = EngineState.ERROR
        _detail.value = msg
    }
}

// ── lib/audit + candidate helpers ──────────────────────────────────────────
fun Engine.libs(): List<String> =
    runBlocking { requestMulti("LIBS").map { it.substringBefore(" ") } }
fun Engine.libsFull(): List<String> =
    runBlocking { requestMulti("LIBS") }
fun Engine.elf(lib: String, filter: String = ""): List<String> =
    runBlocking {
        val cmd = "ELF $lib${if (filter.isNotEmpty()) " $filter" else ""}"
        requestMulti(cmd)
    }
fun Engine.candFilter(kind: String): String = runBlocking { request("CANDFILTER $kind") }
fun Engine.candRegions(): List<String> = runBlocking { requestMulti("CANDREGIONS") }
fun Engine.symbols(lib: String, filter: String = ""): List<String> =
    elf(lib, filter)
