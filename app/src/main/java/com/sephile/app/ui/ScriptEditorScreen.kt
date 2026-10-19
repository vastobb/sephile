package com.sephile.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sephile.app.repository.LuaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ScriptUiState(
    val running: Boolean = false,
    val output: String = ""
)

private class ScriptViewModel(private val repo: LuaRepository) {

    val ui = MutableStateFlow(
        ScriptUiState(
            output = if (repo.engineAvailable)
                "Script engine ready — type commands like: attach 1234 / scan 100 / list"
            else
                "Root engine not running. Grant superuser, then retry."
        )
    )

    fun run(script: String) {
        val src = script.trim()
        if (src.isEmpty()) {
            ui.update { it.copy(output = "Nothing to run — write a script first.") }
            return
        }
        ui.update { it.copy(running = true, output = "Running…") }
        RunScope.launch(Dispatchers.IO) {
            val out = try {
                repo.exec(src)
            } catch (t: Throwable) {
                "Script error: ${t.message}"
            }
            ui.update { it.copy(running = false, output = out.ifEmpty { "(no output)" }) }
        }
    }
}

/** Background scope for running scripts off the UI thread. */
private val RunScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
)

private val DEFAULT_SCRIPT = """# Sephile command script
# Runs against the root engine, line by line.
# Commands: attach <pid> / detach / list / scan <val> [type] / next <val> / peek <addrHex> <len> / poke <addrHex> <val> [type] / count
list
# attach 1234
# scan 100 dword
# next 120
# poke 0x7FA50000 999 dword
"""

@Composable
fun ScriptEditorScreen(repo: LuaRepository) {
    val vm = remember { ScriptViewModel(repo) }
    val ui by vm.ui.collectAsState()
    var script by remember { mutableStateOf(DEFAULT_SCRIPT) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Command scripts",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (repo.engineAvailable) "engine loaded" else "engine missing",
                style = MaterialTheme.typography.labelSmall,
                color = if (repo.engineAvailable) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(10.dp))

        Text(
            text = "Script",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium
        ) {
            BasicTextField(
                value = script,
                onValueChange = { script = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState()),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.run(script) }, enabled = !ui.running) {
                Text(if (ui.running) "Running…" else "▶ Run")
            }
            TextButton(onClick = { script = DEFAULT_SCRIPT }) { Text("Reset") }
            TextButton(onClick = { script = "" }) { Text("Clear") }
        }
        Spacer(Modifier.height(10.dp))

        Text(
            text = "Output",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium
        ) {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(10.dp)
            ) {
                Text(
                    text = ui.output,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    }
}
