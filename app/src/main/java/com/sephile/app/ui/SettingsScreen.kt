package com.sephile.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import com.sephile.app.ai.LlmClient
import com.sephile.app.ai.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI settings: API key + base URL + model. Entering the key/URL and tapping
 * "Fetch models" pulls the available model list from any OpenAI-compatible
 * endpoint (OpenAI, Ollama, LM Studio, …) and lets the user pick one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var key by remember { mutableStateOf(Settings.getKey(ctx)) }
    var base by remember { mutableStateOf(Settings.getBase(ctx)) }
    var model by remember { mutableStateOf(Settings.getModel(ctx)) }
    var target by remember { mutableStateOf(Settings.getTarget(ctx)) }

    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    fun saveAll() {
        Settings.setKey(ctx, key)
        Settings.setBase(ctx, base)
        Settings.setModel(ctx, model)
        Settings.setTarget(ctx, target)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AI model", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Works with any OpenAI-compatible endpoint (OpenAI, Ollama, LM Studio, …).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = base,
            onValueChange = { base = it; saveAll() },
            label = { Text("Base URL") },
            placeholder = { Text("https://api.openai.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = key,
            onValueChange = { key = it; saveAll() },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                saveAll()
                loading = true
                scope.launch {
                    val res = try {
                        withContext(Dispatchers.IO) { LlmClient.fetchModels(base, key) }
                    } catch (e: Throwable) {
                        emptyList<String>().also {
                            Toast.makeText(ctx, "Fetch failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    models = res
                    loading = false
                    if (res.isNotEmpty() && model !in res) {
                        model = res.first()
                        Settings.setModel(ctx, model)
                    }
                }
            },
            enabled = !loading && base.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
            else Text("Fetch models")
        }

        // Model picker (falls back to a free-text field if none fetched).
        if (models.isNotEmpty()) {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = model,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    models.forEach { m ->
                        DropdownMenuItem(text = { Text(m) }, onClick = {
                            model = m
                            Settings.setModel(ctx, m)
                            expanded = false
                        })
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; saveAll() },
                label = { Text("Model id") },
                placeholder = { Text("gpt-4o-mini") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        OutlinedTextField(
            value = target,
            onValueChange = { target = it; saveAll() },
            label = { Text("Target package") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(onClick = { saveAll(); Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show() },
            modifier = Modifier.fillMaxWidth()) { Text("Save") }

        Spacer(Modifier.height(4.dp))
        Text(
            "The AI finds values the same way a human does: scan → change in-game → refine → " +
                "write. Grant root when prompted so the engine can read the target's memory.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
