package com.sephile.app.di

import android.content.Context
import com.sephile.app.repository.MemoryRepository
import com.sephile.app.repository.ProcessRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope

object Module {

    fun processRepository(context: Context): ProcessRepository =
        ProcessRepository(context.applicationContext)

    fun memoryRepository(): MemoryRepository = MemoryRepository()

    val dispatcher = Dispatchers.Default
    val supervisorJob = SupervisorJob()
    val coroutineScope = CoroutineScope(supervisorJob + Dispatchers.Default)
}
