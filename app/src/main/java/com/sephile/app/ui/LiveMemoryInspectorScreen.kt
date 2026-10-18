package com.sephile.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.sephile.app.repository.MemoryRepository
import com.sephile.app.model.ValueType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Live Memory Inspector ("offset finder").
 *
 * Enter a target address, tap Start to continuously read N bytes from the
 * attached process and display them as hex + ASCII. Changed bytes highlight
 * green so you can spot the value that changes in-game. Tap any byte to
 * poke/edit it live into the target process.
 */
@Composable
fun LiveMemoryInspectorScreen(repo: MemoryRepository) {
    val viewModel = remember { LiveMemoryInspectorViewModel(repo) }
    val ui: StateFlow<LiveMemoryInspectorUiState> = viewModel.ui
    val uiState by ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val addressState = remember { mutableStateOf<String>("") }
    val lengthState = remember { mutableStateOf<String>("64") }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        TopAppBar(
            title = { Text(text = "Live Memory Inspector") },
            colors = TopAppBarDefaults.mediumTopAppBarColors(containerColor = Color.Transparent)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = addressState.value,
                onValueChange = { addressState.value = it },
                modifier = Modifier.weight(1f),
                label = { Text(text = "Address (e.g. 0x7f123000)") },
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = lengthState.value,
                onValueChange = { lengthState.value = it },
                modifier = Modifier.width(80.dp),
                label = { Text(text = "Bytes") },
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row {
            Button(
                onClick = {
                    scope.launch {
                        val addrText = addressState.value.trim().removePrefix("0x").removePrefix("0X")
                        val longAddr = addrText.toLongOrNull() ?: -1L
                        val len = lengthState.value.toIntOrNull() ?: 64
                        if (longAddr == -1L) {
                            viewModel.setError("Invalid address")
                        } else if (!viewModel.attached) {
                            viewModel.setError("Not attached to a process")
                        } else {
                            viewModel.setError("")
                            viewModel.startWatch(longAddr, len)
                        }
                    }
                },
                enabled = viewModel.attached && !uiState.watching,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.attached) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(text = if (uiState.watching) "Stop" else "Start watch")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { viewModel.stopWatch() },
                enabled = uiState.watching,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
            ) {
                Text(text = "Stop")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { viewModel.refreshOnce() },
                enabled = uiState.watching,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text(text = "Read once")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (viewModel.attached) "Attached to PID ${viewModel.targetPid}"
            else "Not attached — open Scanner and attach to a process first",
            style = MaterialTheme.typography.bodyMedium,
            color = if (viewModel.attached) Color(0xFF50FA7B) else Color(0xFFFFB86C)
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.error.isNotEmpty()) {
            Text(text = uiState.error, style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFF5555))
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (uiState.watching) {
            Text(
                text = "Watching ${uiState.byteCount} bytes @ 0x${uiState.address.toString(16).uppercase()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        HexDumpEditor(
            bytes = uiState.bytes,
            changed = uiState.changed,
            baseAddress = uiState.address,
            onByteEdit = { index, newValue ->
                scope.launch {
                    viewModel.pokeByte(index, newValue)
                }
            }
        )
    }
}

class LiveMemoryInspectorViewModel(private val repo: MemoryRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: kotlinx.coroutines.Job? = null

    private val _ui = MutableStateFlow(LiveMemoryInspectorUiState())
    val ui: StateFlow<LiveMemoryInspectorUiState> = _ui

    val attached: Boolean
        get() = repo.isAttached()
    val targetPid: Int
        get() = 0
        private set

    fun setError(err: String) {
        _ui.update { it.copy(error = err) }
    }

    fun startWatch(address: Long, length: Int) {
        stopWatch()
        _ui.update { it.copy(address = address, byteCount = length,
            bytes = ByteArray(length), changed = BooleanArray(length), watching = true, error = "") }
        var previous: ByteArray? = null
        watchJob = scope.launch {
            while (isActive) {
                val buf = repo.peekMemory(address, length) ?: break
                val changed = BooleanArray(length)
                for (i in buf.indices) {
                    val prev = previous?.getOrElse(i) { 0 }
                    if (buf[i] != prev) {
                        changed[i] = true
                    }
                }
                previous = buf.copyOf()
                _ui.update { it.copy(bytes = buf, changed = changed) }
                delay(400)
            }
            _ui.update { it.copy(watching = false) }
        }
    }

    fun stopWatch() {
        watchJob?.cancel()
        watchJob = null
        _ui.update { it.copy(watching = false) }
    }

    fun refreshOnce() {}

    fun pokeByte(index: Int, newValue: Int) {
        val addr = _ui.value.address + index
        val ok = repo.editValue(addr, ValueType.BYTE, newValue.toLong(), 0.0)
        _ui.update {
            it.copy(error = if (ok) "" else "Poke failed")
        }
    }
}

data class LiveMemoryInspectorUiState(
    val attached: Boolean = false,
    val targetPid: Int = -1,
    val watching: Boolean = false,
    val address: Long = 0L,
    val byteCount: Int = 0,
    val bytes: ByteArray = byteArrayOf(),
    val changed: BooleanArray = booleanArrayOf(),
    val error: String = ""
)

@Composable
private fun HexDumpEditor(
    bytes: ByteArray,
    changed: BooleanArray,
    baseAddress: Long,
    onByteEdit: (index: Int, newValue: Int) -> Unit
) {
    val columns = 16
    val rows = if (bytes.isEmpty()) 0 else ((bytes.size + columns - 1) / columns)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(count = rows, key = { it }) { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.width(56.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text = "0x${(baseAddress + (row * columns)).toString(16).uppercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                repeat(columns) { col ->
                    val index = row * columns + col
                    val inRange = index < bytes.size
                    val b = if (inRange) bytes[index] else 0
                    val isChanged = inRange && changed[index]
                    val hex = "%02X".format(b.toInt() and 0xFF)
                    Box(
                        modifier = Modifier
                            .width(26.dp)
                            .height(26.dp)
                            .clickable(enabled = inRange, onClick = { if (inRange) onByteEdit(index, b.toInt() and 0xFF) }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = hex,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isChanged) Color(0xFF7CFC00)
                            else if (inRange) Color(0xFFE6E6E6)
                            else Color(0xFF4A5568)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                repeat(columns) { col ->
                    val index = row * columns + col
                    val inRange = index < bytes.size
                    val c = if (inRange) bytes[index].toInt() else 0
                    val printable = if (inRange && c >= 32 && c <= 126) c.toChar() else '.'
                    Box(
                        modifier = Modifier.width(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = printable.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (inRange) Color(0xFFE6E6E6) else Color(0xFF4A5568)
                        )
                    }
                }
            }
        }
    }
}
