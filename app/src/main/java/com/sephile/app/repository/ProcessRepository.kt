package com.sephile.app.repository

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import com.sephile.app.model.ProcessInfo
import com.sephile.app.root.SuShell
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class ProcessRepository(private val context: Context) {

    fun listRunningProcesses(): List<ProcessInfo> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val apps = am.runningAppProcesses ?: return emptyList()
        return apps.mapNotNull { proc ->
            val name = proc.processName ?: ""
            val pid = proc.pid
            // Skip the tool itself and kernel threads
            if (pid == Process.myPid()) return@mapNotNull null
            if (name.isEmpty()) return@mapNotNull null
            ProcessInfo(pid = pid, name = name, uid = proc.uid)
        }.sortedBy { it.name }
    }

    fun findPidByName(processName: String): Int? {
        return SuShell.pidof(processName)
    }

    fun observeProcesses(): Flow<List<ProcessInfo>> = flow {
        while (true) {
            emit(listRunningProcesses())
            withContext(Dispatchers.Default) {
                kotlinx.coroutines.delay(3000)
            }
        }
    }.flowOn(Dispatchers.Default)
}
