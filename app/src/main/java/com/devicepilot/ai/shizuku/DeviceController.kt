package com.devicepilot.ai.shizuku

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

data class ToolExecutionResult(
    val tool: String,
    val command: String,
    val isSuccess: Boolean,
    val output: String,
    val exitCode: Int = 0
)

object DeviceController {

    private const val TAG = "DeviceController"

    private val newProcessMethod: Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to resolve Shizuku.newProcess via reflection", e)
            null
        }
    }

    private fun createProcess(cmd: Array<String>): Process {
        val method = newProcessMethod
            ?: throw IllegalStateException("Shizuku.newProcess method is unavailable")
        return method.invoke(null, cmd, null, null) as Process
    }

    /**
     * Executes a privileged shell command through Shizuku service.
     */
    suspend fun runPrivilegedCommand(cmd: Array<String>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val commandString = cmd.joinToString(" ")
        Log.i(TAG, "Executing privileged command: $commandString")

        if (!ShizukuManager.isReady()) {
            return@withContext ToolExecutionResult(
                tool = "generic",
                command = commandString,
                isSuccess = false,
                output = "Shizuku is not connected or permission was denied. Please grant Shizuku permission first.",
                exitCode = -1
            )
        }

        try {
            val process = createProcess(cmd)
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            while (stdoutReader.readLine().also { line = it } != null) {
                stdout.append(line).append("\n")
            }
            while (stderrReader.readLine().also { line = it } != null) {
                stderr.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            stdoutReader.close()
            stderrReader.close()

            val combinedOutput = if (exitCode == 0) {
                stdout.toString().trim().ifEmpty { "Success (Code $exitCode)" }
            } else {
                val err = stderr.toString().trim()
                if (err.isNotEmpty()) err else stdout.toString().trim().ifEmpty { "Failed with exit code $exitCode" }
            }

            Log.i(TAG, "Command finished with exit code $exitCode: $combinedOutput")

            ToolExecutionResult(
                tool = cmd.firstOrNull() ?: "shell",
                command = commandString,
                isSuccess = exitCode == 0,
                output = combinedOutput,
                exitCode = exitCode
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Execution exception: ${e.message}", e)
            ToolExecutionResult(
                tool = cmd.firstOrNull() ?: "shell",
                command = commandString,
                isSuccess = false,
                output = "Error executing via Shizuku: ${e.message}",
                exitCode = -1
            )
        }
    }

    /**
     * 1. Silent uninstall of third-party package: `pm uninstall <packageName>`
     */
    suspend fun silentUninstallApp(packageName: String): ToolExecutionResult {
        val cleanPkg = packageName.trim()
        val cmd = arrayOf("pm", "uninstall", cleanPkg)
        val result = runPrivilegedCommand(cmd)
        return result.copy(tool = "silent_uninstall_app")
    }

    /**
     * 2. List installed third-party apps: `pm list packages -3`
     */
    suspend fun listInstalledApps(filter: String = "third_party"): ToolExecutionResult {
        val cmd = if (filter.equals("all", ignoreCase = true)) {
            arrayOf("pm", "list", "packages")
        } else {
            arrayOf("pm", "list", "packages", "-3")
        }

        val result = runPrivilegedCommand(cmd)
        val formatted = if (result.isSuccess) {
            val lines = result.output.lines()
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                "No packages found matching filter '$filter'."
            } else {
                "Found ${lines.size} packages:\n" + lines.take(20).joinToString("\n") { "• $it" } +
                        if (lines.size > 20) "\n...and ${lines.size - 20} more" else ""
            }
        } else {
            result.output
        }

        return result.copy(tool = "list_installed_apps", output = formatted)
    }

    /**
     * 3. Set screen brightness (level 0-255): `settings put system screen_brightness <value>`
     */
    suspend fun setScreenBrightness(level: Int): ToolExecutionResult {
        val clampedLevel = level.coerceIn(0, 255)
        val cmd = arrayOf("settings", "put", "system", "screen_brightness", clampedLevel.toString())
        val result = runPrivilegedCommand(cmd)
        return result.copy(
            tool = "set_screen_brightness",
            output = if (result.isSuccess) "Screen brightness set to $clampedLevel (0-255)." else result.output
        )
    }

    /**
     * 4. Set media volume (level 0-15): `cmd media_session volume --stream 3 --set <value>`
     */
    suspend fun setMediaVolume(level: Int): ToolExecutionResult {
        val clampedLevel = level.coerceIn(0, 15)
        val cmd = arrayOf("cmd", "media_session", "volume", "--stream", "3", "--set", clampedLevel.toString())
        val result = runPrivilegedCommand(cmd)
        return result.copy(
            tool = "set_media_volume",
            output = if (result.isSuccess) "Media volume set to $clampedLevel/15." else result.output
        )
    }

    /**
     * 5. Toggle Wi-Fi state: `svc wifi [enable|disable]`
     */
    suspend fun toggleWifi(state: Boolean): ToolExecutionResult {
        val cmd = arrayOf("svc", "wifi", if (state) "enable" else "disable")
        val result = runPrivilegedCommand(cmd)
        return result.copy(
            tool = "toggle_wifi",
            output = if (result.isSuccess) "Wi-Fi turned ${if (state) "ON" else "OFF"}." else result.output
        )
    }

    /**
     * Dispatcher to dynamically route tool calls by name
     */
    suspend fun dispatchTool(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            "set_screen_brightness" -> {
                val level = (args["level"] as? Number)?.toInt() ?: 180
                setScreenBrightness(level)
            }
            "set_media_volume" -> {
                val level = (args["level"] as? Number)?.toInt() ?: 10
                setMediaVolume(level)
            }
            "toggle_wifi" -> {
                val state = when (val s = args["state"]) {
                    is Boolean -> s
                    is String -> s.equals("true", ignoreCase = true) || s.equals("enable", ignoreCase = true) || s.equals("on", ignoreCase = true)
                    else -> true
                }
                toggleWifi(state)
            }
            "silent_uninstall_app" -> {
                val pkg = args["packageName"]?.toString() ?: "com.dummy.app"
                silentUninstallApp(pkg)
            }
            "list_installed_apps" -> {
                val filter = args["filter"]?.toString() ?: "third_party"
                listInstalledApps(filter)
            }
            else -> {
                ToolExecutionResult(
                    tool = toolName,
                    command = "unknown",
                    isSuccess = false,
                    output = "Unrecognized tool: $toolName",
                    exitCode = -1
                )
            }
        }
    }
}
