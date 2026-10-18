package com.sephile.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sephile.app.model.ProcessInfo
import com.sephile.app.repository.ProcessRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProcessListViewModel(private val repo: ProcessRepository) : ViewModel() {

    val processes: StateFlow<List<ProcessInfo>> =
        repo.observeProcesses().stateIn(viewModelScope, replay = 0)

    fun refresh() {
        viewModelScope.launch {
            // flow auto-emits; this is a placeholder for manual refresh triggers
        }
    }

    fun selectProcess(process: ProcessInfo) {
        viewModelScope.launch {
            _selectedProcess.value = process
        }
    }

    private val _selectedProcess = androidx.lifecycle.MutableStateFlow<ProcessInfo?>(null)
    val selectedProcess: StateFlow<ProcessInfo?> = _selectedProcess.asStateFlow()
}
