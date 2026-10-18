package com.sephile.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sephile.app.di.Module
import com.sephile.app.repository.MemoryRepository
import com.sephile.app.repository.ProcessRepository
import com.sephile.app.root.LuaRepository
import com.sephile.app.root.RootManager
import com.sephile.app.ui.theme.SephileTheme
import com.sephile.app.ui.theme.forceSephileColors

class MainActivity : ComponentActivity() {

    private val processRepo = Module.processRepository(this)
    private val memoryRepo = Module.memoryRepository()
    private val luaRepo = LuaRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SephileTheme(darkTheme = true) {
                forceSephileColors()
                val rootState = rememberSaveable { mutableStateOf<Boolean>(!RootManager.isRootAvailable()) }
                val showRootCheck = rootState.value

                if (showRootCheck) {
                    RootGate(onRootConfirmed = { rootState.value = false })
                } else {
                    SephileNavigator(
                        processRepo = processRepo,
                        memoryRepo = memoryRepo,
                        luaRepo = luaRepo
                    )
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

@Composable
private fun RootGate(onRootConfirmed: () -> Unit) {
    val rootAvailable = RootManager.isRootAvailable()

    SephileTheme(darkTheme = true) {
        androidx.compose.material3.Scaffold(
            topBar = TopAppBar(
                title = { Text(text = "Sephile — Root Check") },
                colors = TopAppBarDefaults.mediumTopAppBarColors(
                    containerColor = Color(0xFF121826)
                )
            )
        ) { padding ->
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    text = if (rootAvailable) "Root detected" else "Root not detected",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                    color = if (rootAvailable) Color(0xFF50FA7B) else Color(0xFFFF5555)
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Sephile requires root to read/write other processes' memory.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9AA0A6)
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        RootManager.requestIgnoreBatteryOptimizations(
                            androidx.compose.ui.platform.LocalContext.current
                        )
                        if (rootAvailable) onRootConfirmed()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7CFC00)
                    )
                ) {
                    Text(if (rootAvailable) "Continue" else "Retry root check")
                }
            }
        }
    }
}

@Composable
private fun SephileNavigator(
    processRepo: ProcessRepository,
    memoryRepo: MemoryRepository,
    luaRepo: LuaRepository
) {
    var screen by remember { mutableStateOf(Screen.Processes) }
    var target by remember { mutableStateOf(Pair(-1, "")) }

    androidx.compose.material3.Scaffold(
        topBar = TopAppBar(
            title = { Text(text = when (screen) {
                Screen.Processes -> "Processes"
                Screen.Scanner -> "Scanner"
                Screen.LiveInspector -> "Live Inspector"
                Screen.Lua -> "Lua Scripts"
                Screen.Settings -> "Settings"
            }) },
            colors = TopAppBarDefaults.mediumTopAppBarColors(
                containerColor = Color(0xFF0B0E14)
            )
        )
    ) { padding ->
        when (screen) {
            Screen.Processes -> ProcessListScreen(
                repo = processRepo,
                onAttach = { pid, name ->
                    target = Pair(pid, name)
                    screen = Screen.Scanner
                }
            )
            Screen.Scanner -> ScannerScreen(
                repo = memoryRepo,
                targetPid = target.first,
                targetName = target.second,
                onBack = { screen = Screen.Processes }
            )
            Screen.LiveInspector -> LiveMemoryInspectorScreen(repo = memoryRepo)
            Screen.Lua -> ScriptEditorScreen(luaRepo = luaRepo)
            Screen.Settings -> SettingsScreen()
        }
    }
}

private enum class Screen {
    Processes, Scanner, LiveInspector, Lua, Settings
}

@Composable
private fun SettingsScreen() {
    Text(
        text = "Settings (coming soon)",
        modifier = Modifier.fillMaxSize(),
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
    )
}
