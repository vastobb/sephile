package com.sephile.app.repository

import com.sephile.app.root.NativeBridge

/**
 * Bridges the scripting engine.
 *
 * Real Lua 5.4 support requires vendoring the Lua sources
 * (app/src/main/jni/lua/) — until then the native stubs respond with an
 * explanatory message. This class is safe to construct anywhere: it never
 * throws even if the native engine is missing.
 */
class LuaRepository {

    private val bridge = NativeBridge()

    @Volatile
    private var handle: Long = 0L

    val engineAvailable: Boolean get() = bridge.loaded

    init {
        if (bridge.loaded) {
            try {
                handle = bridge.nativeInit()
                bridge.nativeLuaLoad(handle)
                bridge.nativeLuaBindApi(handle)
            } catch (_: Throwable) {
                handle = 0L
            }
        }
    }

    fun release() {
        val h = handle
        if (h != 0L) {
            try { bridge.nativeDestroy(h) } catch (_: Throwable) {}
            handle = 0L
        }
    }

    fun isLoaded(): Boolean = handle != 0L

    /** Execute a Lua snippet; returns printed/console output. Never throws. */
    fun exec(script: String): String {
        val h = handle
        if (h == 0L || !bridge.loaded) {
            return "Lua engine unavailable.\n${NativeBridge.loadError}\n" +
                "To enable scripting: vendor lua-5.4 sources into app/src/main/jni/lua/ " +
                "and re-run the build."
        }
        return try {
            bridge.nativeLuaExec(h, script)
        } catch (t: Throwable) {
            "Script error: ${t.message}"
        }
    }
}
