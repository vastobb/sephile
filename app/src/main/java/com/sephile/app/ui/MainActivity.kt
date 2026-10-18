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
                ProcessListScreen(
                    repo = processRepo,
                    onAttach = { pid, name ->
                        // Placeholder for future scanner navigation
                    }
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
