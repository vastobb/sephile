package com.sephile.app.repository

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import com.sephile.app.model.ProcessInfo
import com.sephile.app.root.SuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class ProcessRepository(private val context: Context) {

    /**
     * Best-effort list of running app processes.
     * Never throws: failures degrade to the fallback list.
     */
    suspend fun listRunningProcesses(): List<ProcessInfo> = withContext(Dispatchers.IO) {
        try {
            val primary = fromActivityManager()
            if (primary.isNotEmpty()) primary else fromPsFallback()
        } catch (t: Throwable) {
            fromPsFallback()
        }
    }

    private fun fromActivityManager(): List<ProcessInfo> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val apps = am.runningAppProcesses ?: return emptyList()
        return apps.mapNotNull { proc ->
            val name = proc.processName ?: return@mapNotNull null
            if (name.isEmpty()) return@mapNotNull null
            if (proc.pid == Process.myPid()) return@mapNotNull null
            if (name.startsWith("com.android.") && name.endsWith(".sandboxed_process")) return@mapNotNull null
            ProcessInfo(pid = proc.pid, name = name, uid = proc.uid)
        }.sortedBy { it.pid }
    }

    /** ps -A fallback via su; used when the regular API can't see other apps. */
    private fun fromPsFallback(): List<ProcessInfo> {
        val res = SuShell.run("ps -A 2>/dev/null")
        if (!res.success && res.stdout.isEmpty()) return emptyList()
        return res.stdout.lines().mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            // toybox ps -A: USER PID PPID VSZ RSS WCHAN ADDR S NAME
            if (cols.size < 9) return@mapNotNull null
            val user = cols[0]
            val pid = cols[1].toIntOrNull() ?: return@mapNotNull null
            val name = cols[cols.size - 1]
            if (pid == Process.myPid() || name.isEmpty()) return@mapNotNull null
            if (name.contains("sephile")) return@mapNotNull null
            val maybeUid = user.toIntOrNull()
            ProcessInfo(
                pid = pid,
                name = name,
                uid = maybeUid ?: guessUidFromUser(user),
                isSystem = user == "root" || (maybeUid != null && maybeUid < 10000)
            )
        }.distinctBy { it.pid }.sortedBy { it.pid }
    }

    private fun guessUidFromUser(user: String): Int =
        if (user == "root") 0 else -1

    fun findPidByName(processName: String): Int? = SuShell.pidof(processName)

    fun observeProcesses(): Flow<List<ProcessInfo>> = flow {
        while (true) {
            emit (
                try { listRunningProcesses() } catch (t: Throwable) { emptyList() }
            )
            delay(3000)
        }
    }.flowOn(Dispatchers.IO)
}
