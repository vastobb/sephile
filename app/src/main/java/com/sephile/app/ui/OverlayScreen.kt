package com.sephile.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sephile.app.overlay.InspectorService
import com.sephile.app.overlay.OverlayService

/**
 * Overlay tab: one-time setup for the floating layout inspector.
 *
 * Two grants are required (both are user-controlled Android permissions):
 *  1. Display over other apps — lets the floating bubble draw over other apps.
 *  2. Accessibility service   — lets Sephile read the foreground app's view tree.
 *
 * Then Start Overlay launches [OverlayService].
 */
@Composable
fun OverlayScreen() {
    val context = LocalContext.current
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var a11yOn by remember { mutableStateOf(InspectorService.isRunning) }
    var overlayRunning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Layout inspector", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Draws boundaries on every UI element of the foreground app and lets you tap " +
                "one to see its text, id, bounds and supported actions (click / set text on " +
                "editable fields).",
            style = MaterialTheme.typography.bodyMedium
        )

        // Step 1: overlay permission
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (overlayGranted)
                    MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (overlayGranted) "✅  Overlay permission granted"
                    else "1️⃣  Grant \"Display over other apps\"",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(6.dp))
                if (!overlayGranted) {
                    Button(
                        onClick = { requestOverlayPermission(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Grant overlay permission") }
                }
            }
        }

        // Step 2: accessibility service
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (a11yOn)
                    MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (a11yOn) "✅  Accessibility service enabled"
                    else "2️⃣  Enable layout access",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Turn on \"Sephile Layout\" under Accessibility. This is what reads the " +
                        "on-screen elements.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { context.startActivity(Intent(ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open accessibility settings") }
            }
        }

        // Step 3: start overlay
        Button(
            onClick = {
                overlayGranted = Settings.canDrawOverlays(context)
                a11yOn = InspectorService.isRunning
                if (!overlayGranted) {
                    requestOverlayPermission(context)
                    return@Button
                }
                if (!overlayRunning) {
                    context.startForegroundService(Intent(context, OverlayService::class.java))
                    overlayRunning = true
                } else {
                    context.stopService(Intent(context, OverlayService::class.java))
                    overlayRunning = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (overlayRunning) "Stop overlay" else "Start overlay")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("What it can / cannot do", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "• Works on normal Android apps (Settings, browsers, most apps).\n" +
                        "• \"Address\" shown for an element is a resource id + screen bounds — " +
                        "there is no memory address to poke for a UI element.\n" +
                        "• Runtime edits are limited to accessibility actions: click, and set " +
                        "text on editable fields. Renaming/resizing arbitrary views of another " +
                        "app is not possible from outside its process.\n" +
                        "• Games that draw their own UI in OpenGL (e.g. Minecraft) expose no " +
                        "view tree, so only the full-screen surface is visible there.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

private fun requestOverlayPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        try {
            context.startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(context, "Open Settings ▸ Apps ▸ Sephile ▸ Display over other apps", Toast.LENGTH_LONG).show()
        }
    }
}
