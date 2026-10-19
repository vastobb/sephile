package com.sephile.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sephile.app.ai.AiSettings
import com.sephile.app.ai.Llm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** API key + base URL + auto model fetch + target package. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var key by remember { mutableStateOf(AiSettings.key(ctx)) }
    var base by remember { mutableStateOf(AiSettings.base(ctx)) }
    var model by remember { mutableStateOf(AiSettings.model(ctx)) }
    var pkg by remember { mutableStateOf(AiSettings.pkg(ctx)) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    fun save() {
        AiSettings.setKey(ctx, key); AiSettings.setBase(ctx, base)
        AiSettings.setModel(ctx, model); AiSettings.setPkg(ctx, pkg)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AI model", style = MaterialTheme.typography.headlineSmall)
        Text("Any OpenAI-compatible endpoint (OpenAI, Ollama, LM Studio…).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(base, { base = it; save() }, label = { Text("Base URL") },
            placeholder = { Text("https://api.openai.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it; save() }, label = { Text("API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())

        Button({
            save(); loading = true
            scope.launch {
                val res = try { withContext(Dispatchers.IO) { Llm.fetchModels(base, key) } }
                catch (e: Throwable) {
                    Toast.makeText(ctx, "Fetch failed: ${e.message}", Toast.LENGTH_LONG).show()
                    emptyList()
                }
                models = res; loading = false
                if (res.isNotEmpty() && model !in res) { model = res.first(); AiSettings.setModel(ctx, model) }
            }
        }, enabled = !loading && base.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            if (loading) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
            else Text("Fetch models")
        }

        if (models.isNotEmpty()) {
            ExposedDropdownMenuBox(expanded, { expanded = it }) {
                OutlinedTextField(model, {}, readOnly = true, label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor())
                ExposedDropdownMenu(expanded, { expanded = false }) {
                    models.forEach { m -> DropdownMenuItem({ Text(m) }, {
                        model = m; AiSettings.setModel(ctx, m); expanded = false
                    }) }
                }
            }
        } else {
            OutlinedTextField(model, { model = it; save() }, label = { Text("Model id") },
                placeholder = { Text("gpt-4o-mini") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }

        OutlinedTextField(pkg, { pkg = it; save() }, label = { Text("Target package") },
            singleLine = true, modifier = Modifier.fillMaxWidth())

        OutlinedButton({ save(); Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show() },
            modifier = Modifier.fillMaxWidth()) { Text("Save") }

        Text(
            "The AI finds values the way a human does: scan → change in-game → refine → write. " +
                "Grant root when prompted so the engine can read Minecraft's memory.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
