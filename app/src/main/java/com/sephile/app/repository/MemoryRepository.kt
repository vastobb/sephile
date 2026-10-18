package com.sephile.app.repository

import com.sephile.app.model.SearchType
import com.sephile.app.model.ValueType
import com.sephile.app.root.NativeBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class MemoryRepository {

    private val bridge = NativeBridge()
    private var handle: Long = 0L

    init {
        handle = bridge.nativeInit()
    }

    fun release() {
        if (handle != 0L) {
            bridge.nativeDetach(handle)
            bridge.nativeDestroy(handle)
            handle = 0L
        }
    }

    fun hasRoot(): Boolean = bridge.nativeHasRoot()

    fun attach(pid: Int): Boolean {
        if (handle == 0L) handle = bridge.nativeInit()
        return bridge.nativeAttach(handle, pid)
    }

    fun detach() {
        if (handle != 0L) bridge.nativeDetach(handle)
    }

    fun isAttached(): Boolean = handle != 0L && bridge.nativeIsAttached(handle)

    fun firstScan(
        pid: Int, type: ValueType, searchType: SearchType,
        targetValue: Long, targetValueF: Double, fuzzyDelta: Long, onlyWritable: Boolean
    ): Long {
        return bridge.nativeFirstScan(
            handle, pid, type.id, searchType.id,
            targetValue, targetValueF, fuzzyDelta, onlyWritable
        )
    }

    fun nextScan(
        type: ValueType, searchType: SearchType,
        targetValue: Long, targetValueF: Double, fuzzyDelta: Long, onlyWritable: Boolean
    ): Long {
        return bridge.nativeNextScan(
            handle, type.id, searchType.id,
            targetValue, targetValueF, fuzzyDelta, onlyWritable
        )
    }

    fun candidateCount(): Int = bridge.nativeCandidateCount(handle)

    fun candidateAddress(index: Int): Long = bridge.nativeCandidateAddress(handle, index)

    fun candidateHex(index: Int): String = bridge.nativeCandidateHex(handle, index)

    fun editValue(address: Long, type: ValueType, intVal: Long, floatVal: Double): Boolean {
        return bridge.nativeEditValue(handle, address, type.id, intVal, floatVal)
    }

    fun freeze(address: Long, enabled: Boolean, value: String) {
        bridge.nativeFreeze(handle, address, enabled, value)
    }
}
