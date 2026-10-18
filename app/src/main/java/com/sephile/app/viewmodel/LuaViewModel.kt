package com.sephile.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sephile.app.repository.LuaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

data class LuaUiState(
    val loaded: Boolean = false,
    val isRunning: Boolean = false,
    val output: String = "",
    val error: String? = null
)

class LuaViewModel(private val repo: LuaRepository) : ViewModel() {

    private val _ui = MutableStateFlow(LuaUiState(loaded = repo.isLoaded()))
    val ui: StateFlow<LuaUiState> = _ui.stateIn(viewModelScope, replay = 1)

    fun runScript(script: String) {
        viewModelScope.launch {
            _ui.update { it.copy(isRunning = true, error = null, output = "") }
            val result = withContext(Dispatchers.Default) { repo.exec(script) }
            _ui.update {
                it.copy(
                    isRunning = false,
                    output = result,
                    error = if (result.contains("not bound") || result.contains("not initialized"))
                        "Lua engine not enabled — see LuaRepository setup" else null
                )
            }
        }
    }

    fun clearOutput() {
        _ui.update { it.copy(output = "", error = null) }
    }
}
