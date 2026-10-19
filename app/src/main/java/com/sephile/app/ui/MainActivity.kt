package com.sephile.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sephile.app.SephileApp
import com.sephile.app.ai.AiSettings
import com.sephile.app.ai.ChatViewModel
import com.sephile.app.engine.Engine
import com.sephile.app.engine.EngineState
import com.sephile.app.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: String) {
    Chat("AI", "🤖"),
    Scan("Scan", "🔎"),
    Settings("Settings", "⚙️")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root()
                }
            }
        }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatVm = remember { ChatViewModel(ctx.applicationContext) }
    var tab by remember { mutableStateOf(Tab.Chat) }
    var crash by remember { mutableStateOf<String?>(null) }

    // Boot engine + auto-attach Minecraft once.
    LaunchedEffect(Unit) {
        crash = SephileApp.lastCrash(ctx.filesDir)
        scope.launch(Dispatchers.IO) {
            if (Engine.start(ctx)) Engine.autoAttachMinecraft()
        }
    }

    val state by Engine.state.collectAsState()
    val detail by Engine.detail.collectAsState()
    val target by Engine.targetPid.collectAsState()

    crash?.let { text ->
        AlertDialog(
            onDismissRequest = { SephileApp.clearCrash(ctx.filesDir); crash = null },
            title = { Text("The app crashed last run") },
            text = { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) },
            confirmButton = { TextButton({ SephileApp.clearCrash(ctx.filesDir); crash = null }) { Text("Dismiss") } }
        )
    }

    when (state) {
        EngineState.STARTING -> Gate("Starting root engine…\n$detail")
        EngineState.ERROR, EngineState.DOWN -> Gate("Engine not running\n$detail\n\nGrant root when prompted, then tap Retry.") {
            scope.launch(Dispatchers.IO) { if (Engine.start(ctx)) Engine.autoAttachMinecraft() }
        }
        EngineState.UP -> Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(tab == t, { tab = t },
                            icon = { Text(t.icon) }, label = { Text(t.label) })
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    Tab.Chat -> ChatScreen(chatVm)
                    Tab.Scan -> ScanScreen()
                    Tab.Settings -> SettingsScreen()
                }
            }
            // Small floating status line about attach state.
            if (target <= 0 && state == EngineState.UP) {
                LaunchedEffect(Unit) { Engine.autoAttachMinecraft() }
            }
        }
    }
}

@Composable
private fun Gate(msg: String, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                Text(msg, color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.bodyMedium)
                if (onRetry != null) {
                    androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                    TextButton(onRetry) { Text("Retry") }
                }
            }
        }
    }
}
