package com.sephile.app.di

import android.content.Context
import com.sephile.app.repository.MemoryRepository
import com.sephile.app.repository.ProcessRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.newCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
object Module {

    fun processRepository(context: Context): ProcessRepository =
        ProcessRepository(context.applicationContext)

    fun memoryRepository(): MemoryRepository = MemoryRepository()

    val dispatcher = Dispatchers.Default
    val supervisorJob = SupervisorJob()
    val coroutineContext = newCoroutineContext(SupervisorJob() + Dispatchers.Default)
}
