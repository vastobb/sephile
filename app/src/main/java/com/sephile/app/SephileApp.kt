package com.sephile.app

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class SephileApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Capture any fatal crash to a file so the next launch can display it.
        // Without this, a startup crash on a user's device is invisible.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(filesDir, CRASH_FILE).writeText(
                    "${java.util.Date()}\n${thread.name}\n${sw.toString()}"
                )
            } catch (_: Throwable) {} finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    companion object {
        private const val CRASH_FILE = "last_crash.txt"

        /** Last recorded fatal crash text, or null. */
        fun lastCrash(dir: File): String? = try {
            val f = File(dir, CRASH_FILE)
            if (f.exists()) f.readText().take(4000) else null
        } catch (_: Throwable) { null }

        fun clearCrash(dir: File) {
            try { File(dir, CRASH_FILE).delete() } catch (_: Throwable) {}
        }
    }
}
