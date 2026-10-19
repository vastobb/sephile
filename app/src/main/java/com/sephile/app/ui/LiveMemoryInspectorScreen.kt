package com.sephile.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicTextField
import com.sephile.app.model.ValueType
import com.sephile.app.repository.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class InspectorUiState(
    val watching: Boolean = false,
    val address: Long = 0L,
    val size: Int = 0,
    val bytes: List<Int> = emptyList(),
    val changed: List<Boolean> = emptyList(),
    val error: String = ""
)

private class InspectorViewModel(private val repo: MemoryRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uiFlow = MutableStateFlow(InspectorUiState())
    val ui: MutableStateFlow<InspectorUiState> = uiFlow
    private var job: kotlinx.coroutines.Job? = null

    fun start(addressNum: Long, sizeNum: Int) {
        if (!repo.isAttached()) {
            uiFlow.update { it.copy(error = "Not attached — attach a process in the Processes tab") }
            return
        }
        if (!repo.engineAvailable) {
            uiFlow.update { it.copy(error = repo.engineStatus) }
            return
        }
        val size = sizeNum.coerceIn(8, 4096)
        stopWatch()
        uiFlow.update {
            it.copy(
                watching = true, address = addressNum, size = size,
                error = "Watching…", bytes = List(size) { -1 }, changed = List(size) { false }
            )
        }
        var previous: List<Int> = List(size) { -1 }
        job = scope.launch {
            while (isActive) {
                val buf = repo.peekMemory(addressNum, size)
                if (buf == null) {
                    uiFlow.update {
                        it.copy(watching = false, error = "Lost connection to the target process")
                    }
                    break
                }
                val now = buf.map { it.toInt() and 0xFF }
                val changed = List(size) { i -> previous[i] != -1 && previous[i] != now[i] }
                previous = now
                uiFlow.update { it.copy(bytes = now, changed = changed, error = "") }
                delay(400)
            }
        }
    }

    fun stopWatch() {
        job?.cancel()
        job = null
        uiFlow.update { it.copy(watching = false) }
    }

    fun poke(index: Int, hexByte: String): String {
        val text = hexByte.trim().removePrefix("0x").removePrefix("0X")
        val v = text.toLongOrNull(16)
        if (v == null || v !in 0..255) return "Enter a hex byte (00..FF)"
        val addr = uiFlow.value.address + index
        val ok = repo.editValue(addr, ValueType.BYTE, v, 0.0)
        return if (ok) "" else "Poke failed — target may have died"
    }
}

@Composable
fun LiveMemoryInspectorScreen(repo: MemoryRepository) {
    val vm = remember { InspectorViewModel(repo) }
    val scope = rememberCoroutineScope()
    val ui by vm.ui.collectAsState()

    var addressText by remember { mutableStateOf("") }
    var sizeText by remember { mutableStateOf("64") }
    var pokeIndex by remember { mutableStateOf<Int?>(null) }
    var pokeValue by remember { mutableStateOf("") }
    var pokeError by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { vm.stopWatch() } }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Live memory inspector",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Green cells changed since the last read · tap a cell to poke a byte",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                value = addressText,
                onValueChange = { addressText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                decorationBox = { inner ->
                    Column {
                        Text("Address (hex)", style = MaterialTheme.typography.labelSmall)
                        inner()
                    }
                }
            )
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = sizeText,
                onValueChange = { sizeText = it },
                modifier = Modifier.width(72.dp),
                singleLine = true,
                decorationBox = { inner ->
                    Column {
                        Text("Bytes", style = MaterialTheme.typography.labelSmall)
                        inner()
                    }
                }
            )
        }
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val addrText = addressText.trim().removePrefix("0x").removePrefix("0X")
                    val addr = addrText.toLongOrNull(16)
                    if (addr == null || addr <= 0L) {
                        vm.ui.update { it.copy(error = "Enter a hex address like 7FA5000") }
                    } else {
                        val size = sizeText.toIntOrNull() ?: 64
                        vm.start(addr, size)
                    }
                },
                enabled = !ui.watching
            ) { Text("Start watch") }
            Button(
                onClick = { vm.stopWatch() },
                enabled = ui.watching
            ) { Text("Stop") }
        }
        Spacer(Modifier.height(8.dp))

        if (ui.error.isNotEmpty()) {
            Text(
                text = when {
                    ui.watching -> ui.error.ifEmpty { "Watching…" }
                    ui.error.startsWith("Lost") || ui.error.startsWith("Not attached") ||
                        ui.error.startsWith("Native") -> ui.error
                    else -> ui.error
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (ui.watching && ui.error.isEmpty()) MaterialTheme.colorScheme.tertiary
                else if (!ui.watching && (ui.error.startsWith("Lost") || ui.error.startsWith("Not attached") ||
                    ui.error.startsWith("Native"))) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.tertiary
            )
            Spacer(Modifier.height(8.dp))
        }

        if (ui.bytes.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            HexDump(
                bytes = ui.bytes,
                changed = ui.changed,
                base = ui.address,
                onTap = { idx ->
                    pokeIndex = idx
                    pokeValue = "0x%02X".format(ui.bytes[idx].coerceIn(0, 255))
                    pokeError = ""
                }
            )
        } else {
            Spacer(Modifier.weight(1f))
            Text(
                text = "Enter a target address and press Start watch.\n" +
                    "Tip: find a known address first in the Scanner — tap a candidate to copy it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
        }
    }

    pokeIndex?.let { idx ->
        AlertDialog(
            onDismissRequest = { pokeIndex = null },
            title = { Text("Poke byte") },
            text = {
                Column {
                    Text(
                        text = "Address 0x${(ui.address + idx).toString(16).uppercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pokeValue,
                        onValueChange = { pokeValue = it },
                        label = { Text("Byte (hex 00..FF)") },
                        singleLine = true
                    )
                    if (pokeError.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(text = pokeError, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val err = vm.poke(idx, pokeValue)
                        scope.launch {
                            pokeError = err
                            if (err.isEmpty()) pokeIndex = null
                        }
                    }
                }) { Text("Write") }
            },
            dismissButton = {
                TextButton(onClick = { pokeIndex = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun HexDump(
    bytes: List<Int>,
    changed: List<Boolean>,
    base: Long,
    onTap: (Int) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        val cols = 16
        val rows = (bytes.size + cols - 1) / cols
        items(rows, key = { it }) { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "0x${(base + row * cols).toString(16).uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(64.dp)
                )
                repeat(cols) { col ->
                    val idx = row * cols + col
                    if (idx < bytes.size) {
                        val b = bytes[idx]
                        val isChanged = changed.getOrNull(idx) == true
                        Text(
                            text = "%02X".format(if (b in 0..255) b else 0),
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                isChanged -> Color(0xFF7CFC00)
                                b < 0 -> Color(0xFF394760)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .width(26.dp)
                                .clickable(enabled = !isChanged || true) { if (b >= 0) onTap(idx) }
                        )
                    } else {
                        Spacer(Modifier.width(26.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                repeat(cols) { col ->
                    val idx = row * cols + col
                    if (idx < bytes.size) {
                        val b = bytes[idx]
                        val c = if (b in 32..126) b.toChar() else '.'
                        Text(
                            text = c.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (b < 0) Color(0xFF394760)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(12.dp)
                        )
                    }
                }
            }
        }
    }
}
