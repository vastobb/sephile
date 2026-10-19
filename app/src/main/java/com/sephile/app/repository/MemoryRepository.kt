package com.sephile.app.repository

import com.sephile.app.model.ScanCandidate
import com.sephile.app.model.SearchType
import com.sephile.app.model.ValueType
import com.sephile.app.root.RootSession

/**
 * Memory engine façade, now backed by the root [RootSession] daemon instead of
 * in-process ptrace (an app process can never ptrace — see RESEARCH.md).
 *
 * Public API is unchanged from the UI's perspective. `isAttached()`/`targetPid`
 * are served from a local cache so the Compose layer never blocks on IPC.
 * The blocking calls (`firstScan`, `peekMemory`, …) must be invoked from a
 * background dispatcher.
 */
class MemoryRepository {

    @Volatile private var attachedPid: Int = -1

    val engineAvailable: Boolean get() = RootSession.isReady()
    val engineStatus: String get() = RootSession.detail.value
    val targetPid: Int get() = attachedPid

    private fun ready(): Boolean {
        if (!RootSession.isReady()) return false
        return true
    }

    fun attach(pid: Int): Boolean {
        if (!ready()) return false
        return try {
            RootSession.request("ATTACH $pid").let { true }
                .also { if (it) attachedPid = pid }
        } catch (t: Throwable) { false }
    }

    fun detach() {
        if (!ready()) return
        try { RootSession.request("DETACH") } catch (_: Throwable) {}
        attachedPid = -1
    }

    /** Cache-only; never blocks the caller on an IPC round-trip. */
    fun isAttached(): Boolean = RootSession.isReady() && attachedPid > 0

    fun firstScan(
        pid: Int, type: ValueType, searchType: SearchType,
        targetValue: Long, targetValueF: Double, fuzzyDelta: Long, onlyWritable: Boolean
    ): Boolean {
        if (!ready()) return false
        return try {
            RootSession.request(
                "SCAN 0 ${type.id} ${searchType.id} $targetValue $targetValueF $fuzzyDelta " +
                    (if (onlyWritable) "1" else "0") + " $pid"
            ).let { true }
        } catch (t: Throwable) { false }
    }

    fun nextScan(
        type: ValueType, searchType: SearchType,
        targetValue: Long, targetValueF: Double, fuzzyDelta: Long, onlyWritable: Boolean
    ): Boolean {
        if (!ready()) return false
        return try {
            RootSession.request(
                "SCAN 1 ${type.id} ${searchType.id} $targetValue $targetValueF $fuzzyDelta " +
                    (if (onlyWritable) "1" else "0") + " -1"
            ).let { true }
        } catch (t: Throwable) { false }
    }

    fun clearCandidates() {
        // A fresh first scan resets the candidate list server-side.
        if (!ready()) return
        try { RootSession.request("DETACH") } catch (_: Throwable) {}
        attachedPid = -1
    }

    val candidateCount: Int
        get() {
            if (!ready()) return 0
            return try { RootSession.request("COUNT").toIntOrNull() ?: 0 } catch (t: Throwable) { 0 }
        }

    fun candidateAddress(index: Int): Long {
        if (!ready()) return 0L
        return try {
            RootSession.request("CANDIDATE $index").substringBefore(' ').toLongOrNull() ?: 0L
        } catch (t: Throwable) { 0L }
    }

    fun candidateHex(index: Int): String {
        if (!ready()) return ""
        return try {
            RootSession.request("CANDIDATE $index").substringAfter(' ', "")
        } catch (t: Throwable) { "" }
    }

    fun snapshotCandidates(): List<ScanCandidate> {
        val out = mutableListOf<ScanCandidate>()
        val count = candidateCount
        for (i in 0 until count) {
            out += ScanCandidate(address = candidateAddress(i), hexDump = candidateHex(i))
        }
        return out
    }

    fun editValue(address: Long, type: ValueType, intVal: Long, floatVal: Double): Boolean {
        if (!ready()) return false
        return try {
            RootSession.request("POKE $address ${type.id} $intVal $floatVal") == "1"
        } catch (t: Throwable) { false }
    }

    fun editValueFromText(address: Long, type: ValueType, text: String): Pair<Boolean, String> {
        val t = text.trim()
        val parsed: Pair<Long, Double>? = when (type) {
            ValueType.FLOAT -> t.toFloatOrNull()?.let { 0L to it.toDouble() }
            ValueType.DOUBLE -> t.toDoubleOrNull()?.let { it.toRawBits() to it }
            ValueType.BYTE -> t.toLongOrNull()?.let { v -> if (v in -128..255) (v and 0xFFL) to 0.0 else null }
            ValueType.SHORT -> t.toLongOrNull()?.let { v -> if (v in -32768..65535) v to 0.0 else null }
            ValueType.DWORD -> t.toLongOrNull()?.let { v -> if (v in Int.MIN_VALUE..0xFFFF_FFFFL) v to 0.0 else null }
            ValueType.QWORD -> t.toLongOrNull()?.let { it to 0.0 }
        }
        if (parsed == null) return false to "Value out of range for ${type.label}"
        val ok = editValue(address, type, parsed.first, parsed.second)
        return ok to if (ok) "Poked ✅" else "Write failed (engine or target issue)"
    }

    fun freeze(address: Long, enabled: Boolean, value: String, type: ValueType = ValueType.DWORD) {
        if (!ready()) return
        val iv = value.toLongOrNull() ?: 0L
        val fv = value.toDoubleOrNull() ?: 0.0
        try { RootSession.request("FREEZE $address ${if (enabled) "1" else "0"} ${type.id} $iv $fv") } catch (_: Throwable) {}
    }

    fun peekMemory(address: Long, length: Int): ByteArray? {
        if (!ready() || length <= 0) return null
        return try {
            val hex = RootSession.request("PEEK $address $length")
            if (hex.isEmpty()) return ByteArray(0)
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (t: Throwable) { null }
    }

    fun release() {
        attachedPid = -1
        RootSession.stop()
    }
}
