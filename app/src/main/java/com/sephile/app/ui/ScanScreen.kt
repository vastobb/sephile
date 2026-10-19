package com.sephile.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sephile.app.engine.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TYPES = listOf("byte", "short", "dword", "qword", "float", "double")
private val SEARCHES = listOf("exact", "greater", "less")

data class Cand(val addr: Long, val hex: String)

/** Manual memory scanner (same engine the AI drives). */
@Composable
fun ScanScreen() {
    val scope = rememberCoroutineScope()
    val target by Engine.targetPid.collectAsState()

    var type by remember { mutableStateOf("dword") }
    var search by remember { mutableStateOf("exact") }
    var value by remember { mutableStateOf("") }
    var count by remember { mutableIntStateOf(0) }
    var cands by remember { mutableStateOf<List<Cand>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun refresh() = scope.launch(Dispatchers.IO) {
        try {
            val c = Engine.request("COUNT").toIntOrNull() ?: 0
            val list = ArrayList<Cand>(minOf(c, 300))
            for (i in 0 until minOf(c, 300)) {
                val parts = Engine.request("CANDIDATE $i").split(' ')
                val a = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                list.add(Cand(a, parts.getOrNull(1) ?: ""))
            }
            withContext(kotlinx.coroutines.Main.immediate) { count = c; cands = list }
        } catch (t: Throwable) { }
    }

    fun scan(phase: Int) {
        if (target <= 0) { status = "No target — use the AI tab (find_minecraft) first"; return }
        busy = true; status = "Scanning…"
        scope.launch(Dispatchers.IO) {
            val isF = type == "float" || type == "double"
            val tv = if (isF) 0L else (value.toLongOrNull() ?: 0L)
            val tf = if (isF) (value.toDoubleOrNull() ?: 0.0) else 0.0
            val stId = when (search) { "greater" -> 3; "less" -> 4; else -> 0 }
            val typeId = when (type) { "byte"->0; "short"->1; "qword"->3; "float"->4; "double"->5; else->2 }
            try {
                Engine.request("SCAN $phase $typeId $stId $tv $tf 1")
                refresh()
                withContext(kotlinx.coroutines.Main.immediate) {
                    status = "Done"; busy = false
                }
            } catch (t: Throwable) {
                withContext(kotlinx.coroutines.Main.immediate) {
                    status = "Error: ${t.message}"; busy = false
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (target > 0) "Target: Minecraft (pid $target)" else "No target attached",
            style = MaterialTheme.typography.titleSmall,
            color = if (target > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )

        // type chips
        Text("Type", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TYPES.take(4).forEach { t ->
                AssistChip(onClick = { type = t }, label = { Text(t) },
                    border = androidx.compose.foundation.BorderStroke(
                        if (type == t) 2.dp else 1.dp,
                        if (type == t) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    ))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TYPES.drop(4).forEach { t ->
                AssistChip(onClick = { type = t }, label = { Text(t) },
                    border = androidx.compose.foundation.BorderStroke(
                        if (type == t) 2.dp else 1.dp,
                        if (type == t) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    ))
            }
            SEARCHES.forEach { s ->
                AssistChip(onClick = { search = s }, label = { Text(s) },
                    border = androidx.compose.foundation.BorderStroke(
                        if (search == s) 2.dp else 1.dp,
                        if (search == s) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                    ))
            }
        }

        OutlinedTextField(value = value, onValueChange = { value = it },
            label = { Text("Value") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scan(0) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("First scan") }
            OutlinedButton(onClick = { scan(1) }, enabled = !busy && count > 0, modifier = Modifier.weight(1f)) { Text("Next scan") }
            OutlinedButton(onClick = {
                scope.launch(Dispatchers.IO) { try { Engine.request("CLEAR") } catch (_: Throwable) {} ; refresh() }
                count = 0; cands = emptyList(); status = ""
            }) { Text("Clear") }
        }

        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$count candidate(s)", style = MaterialTheme.typography.labelMedium)

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(cands) { _, c ->
                Text(
                    "0x${c.addr.toString(16).uppercase()}   ${c.hex.chunked(2).joinToString(" ")}",
                    Modifier.fillMaxWidth().clickable {
                        // tap to edit
                        status = "0x${c.addr.toString(16)}"
                    }.padding(vertical = 6.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
