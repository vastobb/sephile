package com.sephile.app.repository

import com.sephile.app.model.ScanCandidate
import com.sephile.app.model.SearchType
import com.sephile.app.model.ValueType
import com.sephile.app.root.NativeBridge

/**
 * High level repository over the native memory engine.
 *
 * Every call is guarded so a missing native engine, a dead handle or a bad
 * address degrades to `false` / `0` / `null` instead of crashing the app.
 * All JNI work must be invoked from background dispatchers.
 */
class MemoryRepository {

    private val bridge = NativeBridge()

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var attachedPid: Int = -1

    /** True when libsephile_jni.so loaded successfully. */
    val engineAvailable: Boolean get() = bridge.loaded

    val engineStatus: String get() = if (bridge.loaded) "Native engine ready" else NativeBridge.loadError

    /** Lazily create the native scanner the first time it is actually needed. */
    private fun ensureHandle(): Long {
        if (handle == 0L && bridge.loaded) {
            handle = try { bridge.nativeInit() } catch (t: Throwable) { 0L }
        }
        return handle
    }

    fun attach(pid: Int): Boolean {
        if (!bridge.loaded) return false
        val h = ensureHandle()
        if (h == 0L) return false
        return try {
            val ok = bridge.nativeAttach(h, pid)
            if (ok) attachedPid = pid
            ok
        } catch (t: Throwable) { false }
    }

    fun detach() {
        val h = handle
        if (h != 0L && bridge.loaded) {
            try { bridge.nativeDetach(h) } catch (_: Throwable) {}
            attachedPid = -1
        }
    }

    fun isAttached(): Boolean {
        val h = handle
        if (h == 0L || !bridge.loaded) return false
        return try { bridge.nativeIsAttached(h) } catch (t: Throwable) { false }
    }

    val targetPid: Int get() = attachedPid

    fun hasRoot(): Boolean {
        if (!bridge.loaded) return false
        return try { bridge.nativeHasRoot() } catch (t: Throwable) { false }
    }

    fun firstScan(
        pid: Int, type: ValueType, searchType: SearchType,
        targetValue: Long, targetValueF: Double, fuzzyDelta: Long, onlyWritable: Boolean
    ): Boolean {
        if (!ensureAttachedAndInit(pid)) return false
        return try {
            bridge.nativeFirstScan(
                handle, pid, type.id, searchType.id,
                targetValue, targetValueF, fuzzyDelta, onlyWritable
            ) >= 0
        } catch (t: Throwable) { false }
    }

    fun nextScan(
        type: ValueType, searchType: SearchType,
        targetValue: Long, targetValueF: Double, fuzzyDelta: Long, onlyWritable: Boolean
    ): Boolean {
        if (!isAttached()) return false
        return try {
            bridge.nativeNextScan(
                handle, type.id, searchType.id,
                targetValue, targetValueF, fuzzyDelta, onlyWritable
            ) >= 0
        } catch (t: Throwable) { false }
    }

    /** Wipe the candidate list so a fresh scan can start over. */
    fun clearCandidates() {
        // Native side keeps candidates until the next firstScan; detach+reinit is a clean reset.
        if (handle != 0L && bridge.loaded) {
            try {
                bridge.nativeDestroy(handle)
            } catch (_: Throwable) {}
            handle = 0L
            attachedPid = -1
        }
    }

    val candidateCount: Int
        get() {
            val h = handle
            if (h == 0L || !bridge.loaded) return 0
            return try { bridge.nativeCandidateCount(h) } catch (t: Throwable) { 0 }
        }

    fun candidateAddress(index: Int): Long {
        val h = handle
        if (h == 0L || !bridge.loaded) return 0L
        return try { bridge.nativeCandidateAddress(h, index) } catch (t: Throwable) { 0L }
    }

    fun candidateHex(index: Int): String {
        val h = handle
        if (h == 0L || !bridge.loaded) return ""
        return try { bridge.nativeCandidateHex(h, index) } catch (t: Throwable) { "" }
    }

    /** Materialise the whole candidate list for UI display. */
    fun snapshotCandidates(): List<ScanCandidate> {
        val out = mutableListOf<ScanCandidate>()
        val count = candidateCount
        if (count <= 0) return out
        for (i in 0 until count) {
            out += ScanCandidate(
                address = candidateAddress(i),
                hexDump = candidateHex(i)
            )
        }
        return out
    }

    fun editValue(address: Long, type: ValueType, intVal: Long, floatVal: Double): Boolean {
        if (!isAttached()) return false
        return try {
            bridge.nativeEditValue(handle, address, type.id, intVal, floatVal)
        } catch (t: Throwable) { false }
    }

    /** Parse a user entered value for [type] and write it at [address]. */
    fun editValueFromText(address: Long, type: ValueType, text: String): Pair<Boolean, String> {
        val t = text.trim()
        val parsed: Pair<Long, Double>? = when (type) {
            ValueType.FLOAT -> t.toFloatOrNull()?.let { it.toLong() to it.toDouble() }
            ValueType.DOUBLE -> t.toDoubleOrNull()?.let { it.toRawBits().toLong() to it }
            ValueType.BYTE -> t.toLongOrNull()?.let { v ->
                if (v in -128..255) v and 0xFFL to 0.0 else null
            }
            ValueType.SHORT -> t.toLongOrNull()?.let { v ->
                if (v in -32768..65535) v else null
            }?.let { it to 0.0 }
            ValueType.DWORD -> t.toLongOrNull()?.let { v ->
                if (v in Int.MIN_VALUE..0xFFFF_FFFFL) v else null
            }?.let { it to 0.0 }
            ValueType.QWORD -> t.toLongOrNull()?.let { it to 0.0 }
        }
        if (parsed == null) {
            return false to "Value out of range for ${type.label}"
        }
        val ok = editValue(address, type, parsed.first, parsed.second)
        return ok to if (ok) "Poked ✅" else "Write failed (is target still alive?)"
    }

    fun freeze(address: Long, enabled: Boolean, value: String) {
        if (!isAttached()) return
        try { bridge.nativeFreeze(handle, address, enabled, value) } catch (_: Throwable) {}
    }

    /** Read raw bytes from the attached process; null when unavailable. */
    fun peekMemory(address: Long, length: Int): ByteArray? {
        if (!isAttached() || length <= 0) return null
        return try { bridge.nativePeekMemory(handle, address, length) } catch (t: Throwable) { null }
    }

    private fun ensureAttachedAndInit(pid: Int): Boolean {
        if (!bridge.loaded) return false
        val h = ensureHandle()
        if (h == 0L) return false
        if (isAttached() && attachedPid == pid) return true
        return attach(pid)
    }

    fun release() {
        val h = handle
        if (h != 0L && bridge.loaded) {
            try {
                bridge.nativeDetach(h)
                bridge.nativeDestroy(h)
            } catch (_: Throwable) {}
            handle = 0L
            attachedPid = -1
        }
    }
}
