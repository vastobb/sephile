package com.sephile.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class SephileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, tr ->
            try {
                val sw = StringWriter()
                tr.printStackTrace(PrintWriter(sw))
                File(filesDir, CRASH).writeText("${java.util.Date()}\n${thread.name}\n$sw")
            } catch (_: Throwable) {} finally {
                prev?.uncaughtException(thread, tr)
            }
        }
    }

    companion object {
        private const val CRASH = "last_crash.txt"
        fun lastCrash(dir: File): String? = try {
            val f = File(dir, CRASH); if (f.exists()) f.readText().take(4000) else null
        } catch (_: Throwable) { null }
        fun clearCrash(dir: File) { try { File(dir, CRASH).delete() } catch (_: Throwable) {} }
    }
}
