package com.sephile.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sephile.app.model.ProcessInfo
import com.sephile.app.repository.MemoryRepository
import com.sephile.app.repository.ProcessRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private class ProcessListViewModel(private val repo: ProcessRepository) {

    val all = MutableStateFlow<List<ProcessInfo>>(emptyList())
    val loading = MutableStateFlow(false)

    suspend fun refresh() {
        loading.value = true
        try {
            all.value = repo.listRunningProcesses()
        } finally {
            loading.value = false
        }
    }
}

@Composable
fun ProcessListScreen(
    repo: ProcessRepository,
    memoryRepo: MemoryRepository,
    onAttach: (pid: Int, name: String) -> Unit
) {
    val vm = remember { ProcessListViewModel(repo) }
    val scope = rememberCoroutineScope()
    val processes by vm.all.collectAsState()
    val loading by vm.loading.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refresh() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Running processes",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = { scope.launch(Dispatchers.IO) { vm.refresh() } }) {
                Text("⟳ Refresh")
            }
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search by name or PID…") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        val activePid = if (memoryRepo.isAttached()) memoryRepo.targetPid else -1

        val filtered = if (query.isBlank()) processes else processes.filter {
            it.name.lowercase().contains(query.trim().lowercase()) ||
                it.pid.toString().contains(query.trim())
        }

        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = if (processes.isEmpty()) "No processes found — tap ⟳" else "No match for \"$query\"",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(filtered, key = { it.pid }) { proc ->
                    ProcessListItem(
                        process = proc,
                        attached = proc.pid == activePid,
                        onClick = { onAttach(proc.pid, proc.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessListItem(process: ProcessInfo, attached: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (attached) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = process.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "PID ${process.pid}${if (attached) "  ·  connected ⚡" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (attached) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onClick) {
                Text(text = if (attached) "Re-attach" else "Attach")
            }
        }
    }
}
