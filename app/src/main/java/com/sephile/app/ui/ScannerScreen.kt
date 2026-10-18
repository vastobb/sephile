package com.sephile.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.sephile.app.model.SearchType
import com.sephile.app.model.ValueType
import com.sephile.app.repository.MemoryRepository
import kotlinx.coroutines.launch

@Composable
fun ScannerScreen(
    repo: MemoryRepository,
    targetPid: Int,
    targetName: String,
    onBack: () -> Unit
) {
    val ui by remember { mutableStateOf(com.sephile.app.viewmodel.ScannerUiState()) }

    // Note: In a production build this screen would observe a ViewModel StateFlow.
    // Here we wire directly to MemoryRepository for the MVP.
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        TopAppBar(
            title = { Text(text = "Scanner") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            colors = TopAppBarDefaults.mediumTopAppBarColors(
                containerColor = Color.Transparent
            )
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Target chip
        val attached = ui.attached
        val targetText = if (attached) "Attached: $targetName (PID $targetPid)" else "Not attached"
        Chip(
            modifier = Modifier.fillMaxWidth(),
            onClick = null,
            colors = ChipDefaults.colors(
                containerColor = if (attached) Color(0xFF2E5E00) else Color(0xFF3A1E1E)
            ),
            label = {
                Text(
                    text = targetText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Controls card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row {
                    Text(
                        text = "Value type",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = {},
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ValueType.values().forEach { vt ->
                            DropdownMenuItem(
                                text = { Text(text = vt.label) },
                                onClick = { /* selection handled via state in full VM */ }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                Row {
                    Text(
                        text = "Target value",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    BasicTextField(
                        value = TextFieldValue(""),
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.textFieldColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
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

        // Action buttons
        Row {
            Button(
                onClick = {
                    scope.launch {
                        val count = repo.firstScan(
                            pid = targetPid,
                            type = ValueType.DWORD,
                            searchType = SearchType.EXACT,
                            targetValue = 0,
                            targetValueF = 0.0,
                            fuzzyDelta = 0,
                            onlyWritable = true
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(text = "First scan")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        val count = repo.nextScan(
                            type = ValueType.DWORD,
                            searchType = SearchType.EXACT,
                            targetValue = 0,
                            targetValueF = 0.0,
                            fuzzyDelta = 0,
                            onlyWritable = true
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text(text = "Next scan")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Results
        Text(
            text = "${ui.candidateCount} candidate(s)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (ui.candidateCount > 0) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(0 until ui.candidateCount, key = { it }) { index ->
                    CandidateItem(
                        index = index,
                        address = repo.candidateAddress(index),
                        hex = repo.candidateHex(index),
                        onEdit = {},
                        onFreeze = {}
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
        elevation = 2.dp
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
            Row {
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onFreeze) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Lock,
                        contentDescription = "Freeze",
                        tint = MaterialTheme.colorScheme.success
                    )
                }
            }
        }
    }
}
