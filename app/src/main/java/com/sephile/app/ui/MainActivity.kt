package com.sephile.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sephile.app.di.Module
import com.sephile.app.repository.LuaRepository
import com.sephile.app.repository.MemoryRepository
import com.sephile.app.repository.ProcessRepository
import com.sephile.app.root.RootSession
import com.sephile.app.root.SessionState
import com.sephile.app.ui.theme.SephileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val processRepo = Module.processRepository()
    private val memoryRepo = Module.memoryRepository()
    private val luaRepo = Module.luaRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SephileTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SephileApp(processRepo, memoryRepo, luaRepo)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        memoryRepo.release()
        luaRepo.release()
    }
}

private enum class Tab(val label: String) {
    Chat("AI"),
    Scanner("Scan"),
    Overlay("Overlay"),
    Settings("Settings")
}

@Composable
private fun SephileApp(
    processRepo: ProcessRepository,
    memoryRepo: MemoryRepository,
    luaRepo: LuaRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var tab by remember { mutableStateOf(Tab.Chat) }
    var targetPid by remember { mutableStateOf(-1) }

    val chatVm = remember {
        com.sephile.app.ai.ChatViewModel(memoryRepo, processRepo, context.applicationContext)
    }
    var crashText by remember { mutableStateOf<String?>(null) }

    // Boot the root engine exactly once.
    LaunchedEffect(Unit) {
        crashText = com.sephile.app.SephileApp.lastCrash(context.filesDir)
        RootSession.start(context)
    }

    val sessionState by RootSession.state.collectAsState()
    val sessionDetail by RootSession.detail.collectAsState()

    crashText?.let { text ->
        AlertDialog(
            onDismissRequest = {
                com.sephile.app.SephileApp.clearCrash(context.filesDir)
                crashText = null
            },
            title = { Text("The app crashed last run") },
            text = {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    com.sephile.app.SephileApp.clearCrash(context.filesDir)
                    crashText = null
                }) { Text("Dismiss") }
            }
        )
    }

    when (sessionState) {
        SessionState.STARTING -> GateLoading(sessionDetail)
        SessionState.DISCONNECTED, SessionState.ERROR -> EngineGate(
            detail = sessionDetail,
            onRetry = { scope.launch(Dispatchers.IO) { RootSession.start(context) } }
        )
        SessionState.READY -> Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbar) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Text(text = tabIcon(t)) },
                            label = { Text(text = t.label) }
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    Tab.Chat -> ChatScreen(chatVm)
                    Tab.Scanner -> ScannerScreen(repo = memoryRepo, fallbackPid = targetPid)
                    Tab.Overlay -> OverlayScreen()
                    Tab.Settings -> SettingsScreen()
                }
            }
        }
    }
}

private fun tabIcon(t: Tab): String = when (t) {
    Tab.Chat -> "🤖"
    Tab.Scanner -> "🔎"
    Tab.Overlay -> "🧿"
    Tab.Settings -> "⚙️"
}

@Composable
private fun GateLoading(detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text = "Starting root engine…", color = MaterialTheme.colorScheme.onBackground)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA0A6))
        }
    }
}

@Composable
private fun EngineGate(detail: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "🔐", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Root engine needed",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Sephile reads game memory from a root daemon (uid 0), because an " +
                "app process is blocked by SELinux/Yama from touching other apps.\n\n" +
                "Grant Sephile superuser in Magisk / KernelSU / APatch, then retry.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF9AA0A6),
            textAlign = TextAlign.Center
        )
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text(text = "Retry / request root") }
    }
}
