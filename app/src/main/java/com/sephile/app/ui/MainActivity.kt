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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sephile.app.di.Module
import com.sephile.app.repository.LuaRepository
import com.sephile.app.repository.MemoryRepository
import com.sephile.app.repository.ProcessRepository
import com.sephile.app.root.RootManager
import com.sephile.app.ui.theme.SephileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val processRepo = Module.processRepository(this)
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
    Processes("Processes"),
    Scanner("Scanner"),
    Inspector("Inspector"),
    Lua("Lua")
}

@Composable
private fun SephileApp(
    processRepo: ProcessRepository,
    memoryRepo: MemoryRepository,
    luaRepo: LuaRepository
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var gateDone by remember { mutableStateOf(false) }
    var rooted by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(Tab.Processes) }
    var targetPid by remember { mutableStateOf(-1) }
    var targetName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        rooted = RootManager.isRootAvailableAsync()
        gateDone = true
    }

    when {
        !gateDone -> GateLoading()
        !rooted -> RootGate(onRecheck = {
            scope.launch {
                gateDone = false
                rooted = RootManager.isRootAvailableAsync()
                gateDone = true
            }
        })
        else -> Scaffold(
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
                    Tab.Processes -> ProcessListScreen(
                        repo = processRepo,
                        memoryRepo = memoryRepo,
                        onAttach = { pid, name ->
                            scope.launch(Dispatchers.IO) {
                                val ok = memoryRepo.attach(pid)
                                scope.launch {
                                    snackbar.showSnackbar(
                                        if (ok) "Attached to $name (PID $pid)"
                                        else "Attach failed — grant root (Magisk/KernelSU) and retry"
                                    )
                                    if (ok) {
                                        targetPid = pid
                                        targetName = name
                                        tab = Tab.Scanner
                                    }
                                }
                            }
                        }
                    )
                    Tab.Scanner -> ScannerScreen(
                        repo = memoryRepo,
                        fallbackPid = targetPid
                    )
                    Tab.Inspector -> LiveMemoryInspectorScreen(repo = memoryRepo)
                    Tab.Lua -> ScriptEditorScreen(repo = luaRepo)
                }
            }
        }
    }
}

private fun tabIcon(t: Tab): String = when (t) {
    Tab.Processes -> "🧩"
    Tab.Scanner -> "🔎"
    Tab.Inspector -> "🧬"
    Tab.Lua -> "📜"
}

@Composable
private fun GateLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "Checking root…", color = Color(0xFF9AA0A6))
    }
}

@Composable
private fun RootGate(onRecheck: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "🔒", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Root required",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Sephile attaches to other apps' memory with ptrace — that needs root.\n\n" +
                "1. Install Magisk / KernelSU / APatch on your device.\n" +
                "2. Open the root manager, grant Sephile superuser.\n" +
                "3. Tap re-check below.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF9AA0A6),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRecheck) { Text(text = "Re-check root") }
    }
}
