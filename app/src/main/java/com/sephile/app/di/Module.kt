package com.sephile.app.di

import android.content.Context
import com.sephile.app.repository.LuaRepository
import com.sephile.app.repository.MemoryRepository
import com.sephile.app.repository.ProcessRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope

object Module {

    fun processRepository(context: Context): ProcessRepository =
        ProcessRepository(context.applicationContext)

    fun memoryRepository(): MemoryRepository = MemoryRepository()

    fun luaRepository(): LuaRepository = LuaRepository()

    val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
