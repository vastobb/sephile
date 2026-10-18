package com.sephile.app.root

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter

/**
 * Tiny helper to run shell commands through `su` and capture stdout.
 */
object SuShell {

    fun run(cmd: String, timeoutMillis: Long = 5000): SuResult {
        return try {
            val process = java.lang.Runtime.getRuntime().exec("su")
            val writer = PrintWriter(OutputStreamWriter(process.outputStream), true)
            writer.println(cmd)
            writer.println("exit")
            writer.flush()

            val outBuf = StringBuilder()
            val errBuf = StringBuilder()
            val outReader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))

            var outLine: String? = outReader.readLine()
            while (outLine != null) {
                outBuf.append(outLine).append("\n")
                outLine = outReader.readLine()
            }
            var errLine: String? = errReader.readLine()
            while (errLine != null) {
                errBuf.append(errLine).append("\n")
                errLine = errReader.readLine()
            }

            val finished = process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            SuResult(
                exitCode = if (finished) process.exitValue() else -1,
                stdout = outBuf.toString(),
                stderr = errBuf.toString(),
                timedOut = !finished
            )
            process.destroyForcibly()
        } catch (e: Exception) {
            SuResult(exitCode = -1, stdout = "", stderr = e.message ?: "unknown", timedOut = false)
        }
    }

    fun pidof(processName: String): Int? {
        val res = run("pidof $processName")
        val trimmed = res.stdout.trim()
        return if (trimmed.isNotEmpty()) trimmed.split(" ").first().toIntOrNull() else null
    }
}

data class SuResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
) {
    val success: Boolean get() = exitCode == 0
}
