package com.sephile.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sephile.app.di.Module
import com.sephile.app.repository.ProcessRepository
import com.sephile.app.repository.MemoryRepository
import com.sephile.app.root.RootManager
import com.sephile.app.ui.theme.SephileTheme
import com.sephile.app.ui.theme.forceSephileColors

class MainActivity : ComponentActivity() {

    private val processRepo = Module.processRepository(this)
    private val memoryRepo = Module.memoryRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SephileTheme(darkTheme = true) {
                forceSephileColors()
                var showRootCheck by rememberSaveable { mutableStateOf(!RootManager.isRootAvailable()) }

                if (showRootCheck) {
                    RootGate(onRootConfirmed = { showRootCheck = false })
                } else {
                    SephileNavigator(
                        processRepo = processRepo,
                        memoryRepo = memoryRepo
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        memoryRepo.release()
    }
}

@Composable
private fun RootGate(onRootConfirmed: () -> Unit) {
    val rootAvailable = RootManager.isRootAvailable()

    SephileTheme(darkTheme = true) {
        androidx.compose.material3.Scaffold(
            topBar = TopAppBar(
                title = { Text(text = "Sephile — Root Check") },
                colors = androidx.compose.material3.TopAppBarDefaults.smallTopAppBarColors(
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
    memoryRepo: MemoryRepository
) {
    var screen by rememberSaveable { mutableStateOf(Screen.Processes) }
    var target by rememberSaveable { mutableStateOf(Pair(-1, "")) }

    androidx.compose.material3.Scaffold(
        topBar = TopAppBar(
            title = { Text(text = when (screen) {
                Screen.Processes -> "Processes"
                Screen.Scanner -> "Scanner"
                Screen.Settings -> "Settings"
            }) },
            colors = androidx.compose.material3.TopAppBarDefaults.mediumTopAppBarColors(
                containerColor = Color(0xFF0B0E14)
            )
        )
    ) { padding ->
        when (screen) {
            Screen.Processes -> ProcessListScreen(
                repo = processRepo,
                onAttach = { pid, name ->
                    screen = Screen.Scanner
                    target = Pair(pid, name)
                }
            )
            Screen.Scanner -> ScannerScreen(
                repo = memoryRepo,
                targetPid = target.first,
                targetName = target.second,
                onBack = { screen = Screen.Processes }
            )
            Screen.Settings -> SettingsScreen()
        }
    }
}

private enum class Screen {
    Processes, Scanner, Settings
}

@Composable
private fun SettingsScreen() {
    Text(
        text = "Settings (coming soon)",
        modifier = Modifier.fillMaxSize(),
        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
    )
}
