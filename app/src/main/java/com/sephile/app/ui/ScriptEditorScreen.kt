package com.sephile.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import com.sephile.app.root.LuaRepository
import kotlinx.coroutines.launch

@Composable
fun ScriptEditorScreen(luaRepo: LuaRepository) {
    val viewModel = remember { LuaViewModel(luaRepo) }
    val scope = rememberCoroutineScope()

    var script by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        androidx.compose.material3.TopAppBar(
            title = { Text(text = "Lua Scripts") },
            colors = androidx.compose.material3.TopAppBarDefaults.mediumTopAppBarColors(
                containerColor = Color.Transparent
            )
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (viewModel.isLoaded) "Lua engine loaded" else "Lua engine: stub",
            style = MaterialTheme.typography.bodyMedium,
            color = if (viewModel.isLoaded) Color(0xFF50FA7B) else Color(0xFFFFB86C)
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = script,
            onValueChange = { script = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = { Text(text = "Lua script") }
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row {
            Button(
                onClick = {
                    scope.launch { viewModel.runScript(script) }
                },
                enabled = viewModel.isLoaded,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.isLoaded) MaterialTheme.colorScheme.primary
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
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Output",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (viewModel.output.isNotEmpty()) viewModel.output else "(no output yet)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (viewModel.error.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = viewModel.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF5555)
                    )
                }
            }
        }
    }
}

class LuaViewModel(private val repo: LuaRepository) {

    var isLoaded: Boolean = repo.isLoaded()
        private set

    var output: String = ""
        private set

    var error: String? = null
        private set

    fun runScript(script: String) {
        output = repo.exec(script)
        error = if (output.contains("not bound") || output.contains("not initialized"))
            "Lua engine not enabled" else null
    }

    fun clearOutput() {
        output = ""
        error = null
    }
}
