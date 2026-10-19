package com.sephile.app.root

/**
 * Thin JNI proxy for `libsephile_jni.so`.
 *
 * The library is loaded once, lazily, and guarded: a missing or incompatible
 * native library (wrong ABI, corrupted APK, etc.) can never crash the app.
 * Callers MUST check [loaded] before invoking any `native*` entry point.
 */
class NativeBridge {

    val loaded: Boolean get() = Companion.loaded

    external fun nativeInit(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeAttach(handle: Long, pid: Int): Boolean
    external fun nativeDetach(handle: Long)
    external fun nativeIsAttached(handle: Long): Boolean
    external fun nativeFirstScan(
        handle: Long, pid: Int, type: Int, searchType: Int,
        targetValue: Long, targetValueF: Double, fuzzyDelta: Long, onlyWritable: Boolean
    ): Long
    external fun nativeNextScan(
        handle: Long, type: Int, searchType: Int,
        targetValue: Long, targetValueF: Double, fuzzyDelta: Long, onlyWritable: Boolean
    ): Long
    external fun nativeCandidateCount(handle: Long): Int
    external fun nativeCandidateAddress(handle: Long, index: Int): Long
    external fun nativeCandidateHex(handle: Long, index: Int): String
    external fun nativeEditValue(handle: Long, address: Long, type: Int, intVal: Long, floatVal: Double): Boolean
    external fun nativeFreeze(handle: Long, address: Long, enabled: Boolean, value: String)
    external fun nativeHasRoot(): Boolean
    external fun nativeLuaLoad(handle: Long): Boolean
    external fun nativeLuaExec(handle: Long, script: String): String
    external fun nativeLuaBindApi(handle: Long): Boolean
    external fun nativePeekMemory(handle: Long, address: Long, length: Int): ByteArray?

    companion object {
        /** True once `libsephile_jni.so` was successfully dlopen'ed. */
        val loaded: Boolean = try {
            System.loadLibrary("sephile_jni")
            true
        } catch (t: Throwable) {
            false
        }

        /** Human readable reason when [loaded] is false (for diagnostics UI). */
        val loadError: String = if (loaded) "" else
            "Native engine unavailable — the device ABI may not be supported."
    }
}
