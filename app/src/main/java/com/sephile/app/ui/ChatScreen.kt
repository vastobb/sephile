package com.sephile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sephile.app.ai.ChatMsg
import com.sephile.app.ai.ChatViewModel
import com.sephile.app.engine.Engine

private val Green = Color(0xFF3DDC84)
private val TraceBg = Color(0xFF0C1A10)

/** AI chat — drives the memory scanner; shows a live "thinking" trace of tool calls. */
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val msgs by vm.msgs.collectAsState()
    val busy by vm.busy.collectAsState()
    val trace by vm.trace.collectAsState()
    val engineState by Engine.state.collectAsState()
    val target by Engine.targetPid.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }

    Column(Modifier.fillMaxSize()) {
        // Status strip
        Text(
            when {
                target > 0 -> "● Minecraft attached · pid $target"
                engineState.name == "UP" -> "● Engine ready"
                else -> "○ Engine starting…"
            },
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            color = if (target > 0) Green else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (msgs.isEmpty() && !busy) {
                item { EmptyState(onPick = { input = it }) }
            }
            items(msgs) { m -> Bubble(m) }
            if (busy) {
                item { WorkingTrace(trace) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask the AI…  or /help") }, maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { val t = input; input = ""; vm.send(t) }, enabled = !busy && input.isNotBlank()) {
                Text("Send")
            }
        }
    }
}

/** Shown while the agent works: a live monospace log of every tool call. */
@Composable
private fun WorkingTrace(trace: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(TraceBg, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.height(14.dp), strokeWidth = 2.dp, color = Green)
            Spacer(Modifier.width(8.dp))
            Text("thinking…", color = Green, style = MaterialTheme.typography.labelMedium)
        }
        if (trace.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(Modifier.heightIn(max = 220.dp)) {
                val shown = trace.takeLast(12)
                shown.forEach { line ->
                    Text(
                        line,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onPick: (String) -> Unit) {
    Column {
        Text(
            "Ask the AI to find & edit memory.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        val chips = listOf(
            "Find my XP and set it to 999",
            "I have 64 grass blocks — find where that's stored",
            "Set my health to 20.0",
            "Show me Minecraft's loaded libraries",
            "Scan signature 48 89 5C 24 ?? 57"
        )
        chips.forEach { c ->
            Text(
                "› $c",
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(c) }
                    .padding(vertical = 4.dp),
                color = Green,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Commands: /btw <note> · /libs · /status · /clear · /help",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Bubble(m: ChatMsg) {
    val bg = if (m.mine) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF14261A)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            m.text,
            Modifier.widthIn(max = 320.dp).background(bg, RoundedCornerShape(12.dp)).padding(10.dp),
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
