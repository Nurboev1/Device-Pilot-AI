package com.devicepilot.ai.shizuku

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Method

data class ShellResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val ok: Boolean get() = exitCode == 0
    /** Best human-readable output: stdout, else stderr, else a code summary. */
    val text: String
        get() = stdout.trim().ifEmpty { stderr.trim().ifEmpty { if (ok) "OK" else "exit code $exitCode" } }
}

/**
 * Runs shell commands with ADB-shell privileges through Shizuku.
 * Everything the agent does to the device funnels through here.
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"

    private val newProcess: Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).apply { isAccessible = true }
        } catch (t: Throwable) {
            Log.e(TAG, "Shizuku.newProcess unavailable", t); null
        }
    }

    /** Runs `sh -c <script>`. */
    suspend fun run(script: String, timeoutMs: Long = 20_000): ShellResult =
        exec(arrayOf("sh", "-c", script), script, timeoutMs)

    /** Runs an argv array directly (no shell parsing). */
    suspend fun runArgs(vararg args: String, timeoutMs: Long = 20_000): ShellResult =
        exec(arrayOf(*args), args.joinToString(" "), timeoutMs)

    /** Runs a command and writes its raw stdout to [dest]; used for screenshots. */
    suspend fun runToFile(script: String, dest: File, timeoutMs: Long = 20_000): ShellResult = withContext(Dispatchers.IO) {
        if (!ShizukuManager.isReady()) return@withContext notReady(script)
        val method = newProcess ?: return@withContext ShellResult(script, -1, "", "Shizuku.newProcess unavailable")
        try {
            withTimeout(timeoutMs) {
                val p = method.invoke(null, arrayOf("sh", "-c", script), null, null) as Process
                dest.outputStream().use { out -> p.inputStream.copyTo(out) }
                val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
                val code = p.waitFor()
                ShellResult(script, code, "", err)
            }
        } catch (e: TimeoutCancellationException) {
            ShellResult(script, -1, "", "Timed out after ${timeoutMs}ms")
        } catch (t: Throwable) {
            ShellResult(script, -1, "", "Error: ${t.message}")
        }
    }

    private suspend fun exec(argv: Array<String>, display: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        if (!ShizukuManager.isReady()) return@withContext notReady(display)
        val method = newProcess ?: return@withContext ShellResult(display, -1, "", "Shizuku.newProcess unavailable")
        Log.d(TAG, "$ $display")
        var proc: Process? = null
        try {
            withTimeout(timeoutMs) {
                val p = method.invoke(null, argv, null, null) as Process
                proc = p
                val outBuf = ByteArrayOutputStream()
                val errBuf = ByteArrayOutputStream()
                // Drain stderr on a helper thread so a chatty command can't deadlock the pipe.
                val errThread = Thread { runCatching { p.errorStream.copyTo(errBuf) } }.apply { start() }
                p.inputStream.copyTo(outBuf)
                val code = p.waitFor()
                errThread.join(1000)
                ShellResult(display, code, outBuf.toString("UTF-8"), errBuf.toString("UTF-8"))
            }
        } catch (e: TimeoutCancellationException) {
            runCatching { proc?.destroy() }
            ShellResult(display, -1, "", "Timed out after ${timeoutMs}ms")
        } catch (t: Throwable) {
            runCatching { proc?.destroy() }
            Log.e(TAG, "exec failed", t)
            ShellResult(display, -1, "", "Error: ${t.message}")
        }
    }

    private fun notReady(cmd: String) = ShellResult(
        cmd, -1, "",
        "Shizuku is not connected or permission not granted. Open Shizuku, start it, then grant DevicePilot access."
    )

    /** Quote a string for safe inclusion in `sh -c`. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
