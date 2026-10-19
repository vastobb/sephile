package com.sephile.app.repository

import com.sephile.app.model.ProcessInfo
import com.sephile.app.root.RootSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process enumeration, backed by the root daemon which reads /proc directly.
 * The app-side ActivityManager API only returns *visible* processes, which is
 * useless for a memory tool — the daemon (uid 0) sees everything.
 */
class ProcessRepository {

    suspend fun listRunningProcesses(): List<ProcessInfo> = withContext(Dispatchers.IO) {
        if (!RootSession.isReady()) return@withContext emptyList()
        try {
            RootSession.requestProcesses()
                .filter { it.name.isNotBlank() }
                .map { ProcessInfo(pid = it.pid, name = it.name, uid = it.uid, isSystem = it.uid in 1..9999) }
                .sortedBy { it.name }
        } catch (t: Throwable) {
            emptyList()
        }
    }
}
