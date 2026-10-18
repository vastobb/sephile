package com.sephile.app.root

import com.sephile.app.root.NativeBridge

class LuaRepository {

    private val bridge = NativeBridge()
    private var handle: Long = 0L

    init {
        handle = bridge.nativeInit()
        bridge.nativeLuaLoad(handle)
        bridge.nativeLuaBindApi(handle)
    }

    fun release() {
        if (handle != 0L) {
            bridge.nativeDestroy(handle)
            handle = 0L
        }
    }

    fun isLoaded(): Boolean = handle != 0L

    fun exec(script: String): String {
        if (handle == 0L) return "Lua not initialized"
        return bridge.nativeLuaExec(handle, script)
    }
}
