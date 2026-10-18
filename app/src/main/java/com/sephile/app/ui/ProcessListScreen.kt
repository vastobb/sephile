package com.sephile.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sephile.app.model.ProcessInfo
import com.sephile.app.repository.ProcessRepository
import kotlinx.coroutines.launch

@Composable
fun ProcessListScreen(
    repo: ProcessRepository,
    onAttach: (pid: Int, name: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val processes by repo.observeProcesses().collectAsStateWithLifecycle()
    var refresh by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Running processes",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = { refresh = true },
            colors = ButtonDefaults.textButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(text = "Refresh", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (processes.isEmpty()) {
            Text(text = "No processes found", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(processes, key = { it.pid }) { proc ->
                    ProcessListItem(
                        process = proc,
                        onClick = {
                            scope.launch {
                                onAttach(proc.pid, proc.name)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessListItem(process: ProcessInfo, onClick: () -> Unit) {
    val isSystem = process.uid < 10000
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.PlayCircleOutline,
                    contentDescription = "Attach to ${process.name}",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = process.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "PID ${process.pid}  ·  UID ${process.uid}  ·  ${if (isSystem) "system" else "user"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
