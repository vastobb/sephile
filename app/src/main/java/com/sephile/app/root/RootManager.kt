package com.sephile.app.root

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root & su availability checks. Sephile requires root to ptrace-attach into
 * other processes (Game Guardian style).
 */
object RootManager {

    suspend fun isRootAvailableAsync(): Boolean = withContext(Dispatchers.IO) { isRootAvailable() }

    fun isRootAvailable(): Boolean {
        // su probe with a hard 5s timeout so an ungranted Magisk prompt can
        // never hang the app's root gate.
        val probe = SuShell.run("id", 5000)
        if (probe.success && probe.stdout.contains("uid=0")) return true
        // su binary exists but wasn't granted yet — still count as available
        // so the gate lets the user in; attach errors surface later.
        return suBinaryExists()
    }

    private fun suBinaryExists(): Boolean {
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su",
            "/system/bin/.ext/.su", "/system/xbin/daemonsu",
            "/sbin/su", "/bin/su", "/data/local/su",
            "/data/local/bin/su", "/data/local/xbin/su",
            "/cache/su", "/cache/xbin/su"
        )
        return suPaths.any { java.io.File(it).exists() }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            // ACTION not available on this build — ignore.
        }
    }
}
