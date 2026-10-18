package com.sephile.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.FontFamily
import androidx.compose.ui.unit.dp
import com.sephile.app.repository.LuaRepository
import com.sephile.app.viewmodel.LuaViewModel
import kotlinx.coroutines.launch

@Composable
fun ScriptEditorScreen(luaRepo: com.sephile.app.repository.LuaRepository) {
    // This screen is instantiated via MainActivity with a real LuaViewModel.
    ScriptEditorContent(viewModel = remember { com.sephile.app.viewmodel.LuaViewModel(luaRepo) })
}

@Composable
private fun ScriptEditorContent(viewModel: LuaViewModel) {
    val ui by collectAsStateWithLifecycle(viewModel.ui)
    val scope = rememberCoroutineScope()

    val script by remember { mutableStateOf("""
-- Sephile Lua example (engine not yet bound --)
-- print("hello from sephile")
-- attach(1234)
-- scan(100, "dword")
""".trimIndent()) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        TopAppBar(
            title = { Text(text = "Lua Scripts") },
            navigationIcon = {
                IconButton(onClick = { /* back handled by nav */ }) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            colors = TopAppBarDefaults.mediumTopAppBarColors(containerColor = Color.Transparent)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (ui.loaded) "Lua engine loaded" else "Lua engine: stub (not bound)",
            style = MaterialTheme.typography.bodyMedium,
            color = if (ui.loaded) Color(0xFF50FA7B) else Color(0xFFFFB86C)
        )
        Spacer(modifier = Modifier.height(12.dp))

        BasicTextField(
            value = androidx.compose.ui.text.input.TextFieldValue(script),
            onValueChange = { script = it.text },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF121826)),
            colors = TextFieldDefaults.textFieldColors(
                containerColor = Color(0xFF121826),
                focusedContainerColor = Color(0xFF121826)
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.foundation.text.ImeAction.Done
            )
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row {
            Button(
                onClick = {
                    scope.launch { viewModel.runScript(script) }
                },
                enabled = ui.loaded,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (ui.loaded) MaterialTheme.colorScheme.primary
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
                    text = if (ui.output.isNotEmpty()) ui.output else "(no output yet)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace
                )
                ui.error?.let { err ->
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
