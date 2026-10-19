package com.sephile.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.sephile.app.model.ScanCandidate
import com.sephile.app.model.SearchType
import com.sephile.app.model.ValueType
import com.sephile.app.repository.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ScannerUiState(
    val scanning: Boolean = false,
    val error: String = "",
    val candidates: List<ScanCandidate> = emptyList(),
    val values: Map<Long, String> = emptyMap(),
    val frozen: Set<Long> = emptySet()
)

private class ScannerViewModel(private val repo: MemoryRepository) {

    val type = MutableStateFlow(ValueType.DWORD)
    val searchType = MutableStateFlow(SearchType.EXACT)
    val valueText = MutableStateFlow("")
    val onlyWritable = MutableStateFlow(true)
    val ui = MutableStateFlow(ScannerUiState())

    private fun targetPid(): Int =
        if (repo.isAttached()) repo.targetPid else -1

    fun firstScan() {
        val pid = targetPid()
        if (pid <= 0) {
            ui.update { it.copy(error = "Not attached — pick a process in the Processes tab") }
            return
        }
        if (!repo.engineAvailable) {
            ui.update { it.copy(error = repo.engineStatus) }
            return
        }
        val t = type.value
        val st = searchType.value
        val raw = valueText.value.trim()
        val parsed = parseTarget(raw, t, st) ?: run {
            ui.update { it.copy(error = "Enter a numeric target value for ${t.label}") }
            return
        }
        ui.update { it.copy(scanning = true, error = "Scanning…", candidates = emptyList()) }
        LaunchScope.launch(Dispatchers.IO) {
            val ok = repo.firstScan(
                pid = pid, type = t, searchType = st,
                targetValue = parsed.first, targetValueF = parsed.second,
                fuzzyDelta = 0L, onlyWritable = onlyWritable.value
            )
            val list = if (ok) repo.snapshotCandidates() else emptyList()
            val values = list.associate { it.address to interpret(it.hexDump, t) }
            ui.update {
                it.copy(
                    scanning = false,
                    error = if (!ok) "Scan failed — is the process still alive?" else "",
                    candidates = list,
                    values = values
                )
            }
        }
    }

    fun nextScan() {
        if (!repo.isAttached()) {
            ui.update { it.copy(error = "Not attached") }
            return
        }
        val t = type.value
        val st = searchType.value
        val raw = valueText.value.trim()
        val parsed = parseTarget(raw, t, st) ?: run {
            ui.update { it.copy(error = "Enter a numeric target value for ${t.label}") }
            return
        }
        ui.update { it.copy(scanning = true, error = "Refining…") }
        LaunchScope.launch(Dispatchers.IO) {
            val ok = repo.nextScan(
                type = t, searchType = st,
                targetValue = parsed.first, targetValueF = parsed.second,
                fuzzyDelta = 0L, onlyWritable = onlyWritable.value
            )
            val list = if (ok) repo.snapshotCandidates() else emptyList()
            val values = list.associate { it.address to interpret(it.hexDump, t) }
            ui.update {
                it.copy(
                    scanning = false,
                    error = if (!ok) "Refine failed" else "",
                    candidates = list,
                    values = values
                )
            }
        }
    }

    fun clear() {
        LaunchScope.launch(Dispatchers.IO) {
            repo.clearCandidates()
            ui.update { it.copy(candidates = emptyList(), values = emptyMap(), frozen = emptySet(), error = "") }
        }
    }

    fun edit(address: Long, text: String, onDone: (Boolean, String) -> Unit) {
        val t = type.value
        LaunchScope.launch(Dispatchers.IO) {
            val (ok, msg) = repo.editValueFromText(address, t, text)
            if (ok) {
                val hex = byteHexOf(address, t.size)
                ui.update {
                    val newValues = if (hex == null) it.values else it.values + (address to interpret(hex, t))
                    it.copy(values = newValues)
                }
            }
            onDone(ok, msg)
        }
    }

    fun toggleFreeze(address: Long) {
        val enable = address !in ui.value.frozen
        val valueText = ui.value.values[address] ?: "0"
        LaunchScope.launch(Dispatchers.IO) { repo.freeze(address, enable, valueText) }
        ui.update {
            it.copy(frozen = if (enable) it.frozen + address else it.frozen - address)
        }
    }

    private fun byteHexOf(address: Long, size: Int): String? {
        val bytes = repo.peekMemory(address, size) ?: return null
        return bytes.joinToString(" ") { "%02X".format(it) }
    }
}

private val LaunchScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
)

/** Parse a user typed scan target; null when invalid for the requested type. */
private fun parseTarget(text: String, type: ValueType, search: SearchType): Pair<Long, Double>? {
    if (search != SearchType.EXACT) return 0L to 0.0
    if (text.isEmpty()) return null
    val hex = text.startsWith("0x") || text.startsWith("0X")
    return when (type) {
        ValueType.FLOAT -> text.toFloatOrNull()?.let { it.toLong().toLong() to it.toDouble() }
        ValueType.DOUBLE -> text.toDoubleOrNull()?.let { 0L to it }
        else -> {
            val v: Long? = if (hex) {
                text.removePrefix("0x").removePrefix("0X").toLongOrNull(16)
            } else text.toLongOrNull()
            v?.let { it to 0.0 }
        }
    }
}

/** Interpret a hex byte dump as the value of [type] (little endian). */
internal fun interpret(hexDump: String, type: ValueType): String {
    if (hexDump.isEmpty()) return "?"
    val bytes = hexDump.trim().split(Regex("\\s+")).mapNotNull { s ->
        s.toLongOrNull(16)?.toInt()?.and(0xFF) ?: s.removePrefix("0x").toLongOrNull(16)?.toInt()?.and(0xFF)
    }
    val size = type.size
    if (bytes.size < size) return "?"
    var le = 0L
    for (i in 0 until size) le = le or ((bytes[i].toLong() and 0xFF) shl (8 * i))
    return when (type) {
        ValueType.BYTE -> ((le and 0xFF).let { if (it > 0x7F) it - 0x100 else it }).toString()
        ValueType.SHORT -> le.toShort().toString()
        ValueType.DWORD -> le.toInt().toString()
        ValueType.QWORD -> le.toString()
        ValueType.FLOAT -> java.lang.Float.fromBits(le.toInt()).toString()
        ValueType.DOUBLE -> java.lang.Double.fromBits(le).toString()
    }
}

@Composable
fun ScannerScreen(repo: MemoryRepository, fallbackPid: Int) {
    val vm = remember { ScannerViewModel(repo) }
    val ui by vm.ui.collectAsState()
    val curType by vm.type.collectAsState()
    val curSearch by vm.searchType.collectAsState()
    val curValue by vm.valueText.collectAsState()
    val curWritable by vm.onlyWritable.collectAsState()
    var editing by remember { mutableStateOf<ScanCandidate?>(null) }
    var editValueText by remember { mutableStateOf("") }

    val attached = repo.isAttached()
    val attachedPid = if (attached) repo.targetPid else fallbackPid

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (attached) "🎯 ${pidName(attachedPid)}"
                else "Not attached — pick a process first",
                style = MaterialTheme.typography.titleMedium,
                color = if (attached) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.weight(1f))
            if (attached) {
                TextButton(onClick = { vm.clear(); repo.detach() }) { Text("Detach") }
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ValueType.entries.forEach { t ->
                FilterChip(
                    selected = curType == t,
                    onClick = { vm.type.value = t },
                    label = { Text(chipLabel(t)) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SearchType.entries.forEach { st ->
                FilterChip(
                    selected = curSearch == st,
                    onClick = { vm.searchType.value = st },
                    label = { Text(st.label) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = curValue,
            onValueChange = { vm.valueText.value = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Target value") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = curWritable,
                onCheckedChange = { vm.onlyWritable.value = it }
            )
            Text(
                text = "Writable regions only",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.firstScan() },
                enabled = !ui.scanning && attachedOrFallback(attachedPid, attached)
            ) { Text("First scan") }
            Button(
                onClick = { vm.nextScan() },
                enabled = !ui.scanning && attached && ui.candidates.isNotEmpty()
            ) { Text("Next scan") }
            Button(
                onClick = { vm.clear() },
                enabled = !ui.scanning && ui.candidates.isNotEmpty()
            ) { Text("Clear") }
        }
        Spacer(Modifier.height(8.dp))

        if (ui.error.isNotEmpty()) {
            Text(
                text = ui.error,
                style = MaterialTheme.typography.bodyMedium,
                color = if (ui.scanning) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = "${ui.candidates.size} candidate(s)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        if (ui.candidates.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(ui.candidates, key = { it.address }) { cand ->
                    val current = ui.values[cand.address] ?: "?"
                    val frozenNow = cand.address in ui.frozen
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = current,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (frozenNow) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "0x${cand.address.toString(16).uppercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = cand.hexDump,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                editing = cand
                                editValueText = current
                            }) { Text("Edit") }
                            TextButton(onClick = { vm.toggleFreeze(cand.address) }) {
                                Text(if (frozenNow) "Unfreeze" else "Freeze")
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
            Text(
                text = "Run a first scan with an exact value, then change it in-game and hit Next scan.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    editing?.let { cand ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit 0x${cand.address.toString(16).uppercase()}") },
            text = {
                OutlinedTextField(
                    value = editValueText,
                    onValueChange = { editValueText = it },
                    label = { Text(vm.type.value.label) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val child = editing
                    if (child != null) {
                        vm.edit(child.address, editValueText) { ok, msg ->
                            editing = null
                        }
                    }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            }
        )
    }
}

private fun pidName(pid: Int): String = "PID $pid"

private fun chipLabel(t: ValueType): String = when (t) {
    ValueType.BYTE -> "Byte"
    ValueType.SHORT -> "2 bytes"
    ValueType.DWORD -> "4 bytes"
    ValueType.QWORD -> "8 bytes"
    ValueType.FLOAT -> "Float"
    ValueType.DOUBLE -> "Double"
}

private fun attachedOrFallback(pid: Int, attached: Boolean): Boolean = attached || pid > 0
