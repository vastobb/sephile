package com.sephile.app.di

import com.sephile.app.repository.LuaRepository
import com.sephile.app.repository.MemoryRepository
import com.sephile.app.repository.ProcessRepository

object Module {

    fun processRepository(): ProcessRepository = ProcessRepository()

    fun memoryRepository(): MemoryRepository = MemoryRepository()

    fun luaRepository(): LuaRepository = LuaRepository()
}
