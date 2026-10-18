package com.sephile.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sephile.app.model.SearchType
import com.sephile.app.model.ValueType
import com.sephile.app.repository.MemoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

data class ScannerUiState(
    val attached: Boolean = false,
    val targetPid: Int = -1,
    val targetName: String = "",
    val candidateCount: Int = 0,
    val isScanning: Boolean = false,
    val lastResult: String = "",
    val error: String? = null
)

class ScannerViewModel(private val repo: MemoryRepository) : ViewModel() {

    private val _ui = MutableStateFlow(ScannerUiState())
    val ui: StateFlow<ScannerUiState> = _ui.stateIn(viewModelScope, replay = 1)

    fun attachToProcess(pid: Int, name: String) {
        viewModelScope.launch {
            _ui.update { it.copy(error = null, isScanning = true) }
            val ok = withContext(Dispatchers.Default) { repo.attach(pid) }
            _ui.update {
                it.copy(
                    attached = ok,
                    targetPid = if (ok) pid else -1,
                    targetName = name,
                    isScanning = false,
                    error = if (ok) null else "Attach failed. Root required?"
                )
            }
        }
    }

    fun detach() {
        viewModelScope.launch {
            repo.detach()
            _ui.update { it.copy(attached = false, targetPid = -1, candidateCount = 0) }
        }
    }

    fun firstScan(
        type: ValueType, searchType: SearchType,
        targetValue: Long, targetValueF: Double, fuzzyDelta: Long, onlyWritable: Boolean
    ) {
        val pid = _ui.value.targetPid
        if (pid == -1) {
            _ui.update { it.copy(error = "Select a process first") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(isScanning = true, error = null) }
            val count = withContext(Dispatchers.Default) {
                repo.firstScan(pid, type, searchType, targetValue, targetValueF, fuzzyDelta, onlyWritable)
            }
            _ui.update {
                it.copy(
                    isScanning = false,
                    candidateCount = repo.candidateCount(),
                    lastResult = "First scan: $count candidates"
                )
            }
        }
    }

    fun nextScan(
        type: ValueType, searchType: SearchType,
        targetValue: Long, targetValueF: Double, fuzzyDelta: Long, onlyWritable: Boolean
    ) {
        viewModelScope.launch {
            _ui.update { it.copy(isScanning = true, error = null) }
            val count = withContext(Dispatchers.Default) {
                repo.nextScan(type, searchType, targetValue, targetValueF, fuzzyDelta, onlyWritable)
            }
            _ui.update {
                it.copy(
                    isScanning = false,
                    candidateCount = repo.candidateCount(),
                    lastResult = "Next scan: $count candidates"
                )
            }
        }
    }

    fun editCandidate(index: Int, type: ValueType, intVal: Long, floatVal: Double) {
        val address = repo.candidateAddress(index)
        if (address == 0L) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) {
                repo.editValue(address, type, intVal, floatVal)
            }
            _ui.update {
                it.copy(lastResult = if (ok) "Edited 0x${address.toString(16)}" else "Edit failed")
            }
        }
    }

    fun toggleFreeze(index: Int, enabled: Boolean, value: String) {
        val address = repo.candidateAddress(index)
        if (address == 0L) return
        repo.freeze(address, enabled, value)
        _ui.update {
            it.copy(lastResult = if (enabled) "Frozen 0x${address.toString(16)}" else "Unfrozen")
        }
    }

    fun clearCandidates() {
        _ui.update { it.copy(candidateCount = 0, lastResult = "Cleared") }
    }
}
