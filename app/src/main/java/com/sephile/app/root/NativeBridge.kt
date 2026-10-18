package com.sephile.app.root

import com.sephile.app.model.SearchType
import com.sephile.app.model.ValueType

object NativeBridge {
    init {
        System.loadLibrary("sephile_jni")
    }

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

    // -------- Lua scripting --------
    external fun nativeLuaLoad(handle: Long): Boolean
    external fun nativeLuaExec(handle: Long, script: String): String
    external fun nativeLuaBindApi(handle: Long): Boolean

    // -------- Raw memory peek (hex editor / live inspector) --------
    external fun nativePeekMemory(handle: Long, address: Long, length: Int): ByteArray?
}
