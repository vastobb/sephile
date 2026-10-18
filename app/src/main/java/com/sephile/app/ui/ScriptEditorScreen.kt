package com.sephile.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import com.sephile.app.root.LuaRepository

@Composable
fun ScriptEditorScreen(luaRepo: LuaRepository) {
    val viewModel = remember { LuaViewModel(luaRepo) }
    val uiState = viewModel.ui.value
    val scope = rememberCoroutineScope()

    val scriptState = remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        androidx.compose.material3.TopAppBar(
            title = { Text(text = "Lua Scripts") },
            colors = androidx.compose.material3.TopAppBarDefaults.mediumTopAppBarColors(
                containerColor = Color.Transparent
            )
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (uiState.loaded) "Lua engine loaded" else "Lua engine: stub",
            style = MaterialTheme.typography.bodyMedium,
            color = if (uiState.loaded) Color(0xFF50FA7B) else Color(0xFFFFB86C)
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = scriptState.value,
            onValueChange = { scriptState.value = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = { Text(text = "Lua script") }
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row {
            Button(
                onClick = {
                    scope.launch { viewModel.runScript(scriptState.value) }
                },
                enabled = uiState.loaded,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.loaded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(text = "Run script")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { viewModel.clearOutput() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Text(text = "Clear")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0E14)),
            elevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Output",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (uiState.output.isNotEmpty()) uiState.output else "(no output yet)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                uiState.error?.let { err ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF5555)
                    )
                }
            }
        }
    }
}

class LuaViewModel(private val repo: com.sephile.app.repository.LuaRepository) {

    private val _ui = MutableStateFlow(LuaUiState(loaded = repo.isLoaded()))
    val ui: StateFlow<LuaUiState> = _ui.asStateFlow()

    fun runScript(script: String) {
        _ui.update { it.copy(isRunning = true, error = null, output = "") }
        val result = repo.exec(script)
        _ui.update {
            it.copy(
                isRunning = false,
                output = result,
                error = if (result.contains("not bound") || result.contains("not initialized"))
                    "Lua engine not enabled" else null
            )
        }
    }

    fun clearOutput() {
        _ui.update { it.copy(output = "", error = null) }
    }
}

data class LuaUiState(
    val loaded: Boolean = false,
    val isRunning: Boolean = false,
    val output: String = "",
    val error: String? = null
)
