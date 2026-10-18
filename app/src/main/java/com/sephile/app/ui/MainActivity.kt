package com.sephile.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sephile.app.di.Module
import com.sephile.app.repository.MemoryRepository
import com.sephile.app.repository.ProcessRepository
import com.sephile.app.root.LuaRepository
import com.sephile.app.ui.theme.SephileTheme

class MainActivity : ComponentActivity() {

    private val processRepo = Module.processRepository(this)
    private val memoryRepo = Module.memoryRepository()
    private val luaRepo = LuaRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SephileTheme {
                SephileNavigator(
                    processRepo = processRepo,
                    memoryRepo = memoryRepo,
                    luaRepo = luaRepo
                )
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
private fun SephileNavigator(
    processRepo: ProcessRepository,
    memoryRepo: MemoryRepository,
    luaRepo: LuaRepository
) {
    var screen by remember { mutableStateOf(Screen.Processes) }

    androidx.compose.material3.Scaffold(
        topBar = androidx.compose.material3.TopAppBar(
            title = { Text(text = when (screen) {
                Screen.Processes -> "Processes"
                Screen.Scanner -> "Scanner"
                Screen.LiveInspector -> "Live Inspector"
                Screen.Lua -> "Lua Scripts"
                Screen.Settings -> "Settings"
            }) },
            colors = androidx.compose.material3.TopAppBarDefaults.mediumTopAppBarColors(
                containerColor = Color(0xFF0B0E14)
            )
        )
    ) { _ ->
        when (screen) {
            Screen.Processes -> ProcessListScreen(
                repo = processRepo,
                onAttach = { _, _ -> }
            )
            Screen.Scanner -> ScannerScreen(
                repo = memoryRepo,
                targetPid = -1,
                targetName = "",
                onBack = { }
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
