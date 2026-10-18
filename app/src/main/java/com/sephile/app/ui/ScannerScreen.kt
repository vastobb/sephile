package com.sephile.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sephile.app.model.SearchType
import com.sephile.app.model.ValueType
import com.sephile.app.repository.MemoryRepository

@Composable
fun ScannerScreen(
    repo: MemoryRepository,
    targetPid: Int,
    targetName: String,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        androidx.compose.material3.TopAppBar(
            title = { Text(text = "Scanner") },
            colors = androidx.compose.material3.TopAppBarDefaults.mediumTopAppBarColors(
                containerColor = Color.Transparent
            )
        )
        Spacer(modifier = Modifier.height(8.dp))

        val attached = repo.isAttached()
        val targetText = if (attached) "Attached: $targetName (PID $targetPid)" else "Not attached"
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = targetText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row {
                    Text(
                        text = "Value type",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(12.dp))

                Row {
                    Text(
                        text = "Target value",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(12.dp))

                Row {
                    Checkbox(
                        checked = true,
                        onCheckedChange = {}
                    )
                    Text(
                        text = "Writable regions only",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        Row {
            Button(
                onClick = {
                    repo.firstScan(
                        pid = targetPid,
                        type = ValueType.DWORD,
                        searchType = SearchType.EXACT,
                        targetValue = 0,
                        targetValueF = 0.0,
                        fuzzyDelta = 0,
                        onlyWritable = true
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(text = "First scan")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    repo.nextScan(
                        type = ValueType.DWORD,
                        searchType = SearchType.EXACT,
                        targetValue = 0,
                        targetValueF = 0.0,
                        fuzzyDelta = 0,
                        onlyWritable = true
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text(text = "Next scan")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        val count = repo.candidateCount()
        Text(
            text = "$count candidate(s)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (count > 0) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(count, key = { it }) { index ->
                    CandidateItem(
                        index = index,
                        address = repo.candidateAddress(index),
                        hex = repo.candidateHex(index),
                        onEdit = { },
                        onFreeze = { }
                    )
                }
            }
        } else {
            Text(text = "No candidates yet. Run a first scan.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CandidateItem(
    index: Int,
    address: Long,
    hex: String,
    onEdit: () -> Unit,
    onFreeze: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "0x${address.toString(16).uppercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = hex,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onEdit) {
                Text(text = "Edit")
            }
            TextButton(onClick = onFreeze) {
                Text(text = "Freeze")
            }
        }
    }
}
