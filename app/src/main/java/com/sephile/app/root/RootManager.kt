package com.sephile.app.root

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Root & su availability checks. Sephile requires root to ptrace-attach into
 * other processes (Game Guardian style).
 */
object RootManager {

    fun isRootAvailable(): Boolean {
        return suBinaryExists() || canRunSu()
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

    private fun canRunSu(): Boolean {
        return try {
            val proc = java.lang.Runtime.getRuntime().exec("su -c id")
            val out = java.io.BufferedReader(
                java.io.InputStreamReader(proc.inputStream)).readText()
            proc.waitFor()
            out.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    fun isRootMgmtInstalled(): Boolean {
        val pkgs = listOf(
            "com.topjohnwu.magisk", "com.topjohnwu.magisk.debug",
            "com.kuroiku.zygisk", "org.chainfire.supersu",
            "me.phh.superuser", "com.koushikdutta.superuser"
        )
        // check via package manager would need context; keep simple here
        return pkgs.any { p ->
            try {
                java.lang.Class.forName("android.app.Application")
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun runInBackground(scope: CoroutineScope, block: () -> Unit) {
        scope.launch(Dispatchers.Default) {
            thread(isDaemon = true, start = true) {
                block()
            }
        }
    }
}
