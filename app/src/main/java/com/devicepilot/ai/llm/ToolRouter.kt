package com.devicepilot.ai.llm

import android.util.Log
import com.devicepilot.ai.shizuku.DeviceController
import com.devicepilot.ai.shizuku.ToolExecutionResult
import org.json.JSONObject

sealed class ParsedResponse {
    data class Text(val content: String) : ParsedResponse()
    data class ToolCall(
        val toolName: String,
        val args: Map<String, Any?>,
        val rawJson: String,
        val executionResult: ToolExecutionResult? = null
    ) : ParsedResponse()
}

object ToolRouter {

    private const val TAG = "ToolRouter"

    fun isDeviceControlIntent(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("bright") || lower.contains("yorug") || lower.contains("ekran") ||
               lower.contains("volume") || lower.contains("ovoz") || lower.contains("tovush") ||
               lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("tarmoq") ||
               lower.contains("app") || lower.contains("ilova") || lower.contains("dastur") ||
               lower.contains("uninstall") || lower.contains("o'chir") || lower.contains("ochir") ||
               lower.contains("sozla") || lower.contains("o'rnat") || lower.contains("ornat")
    }

    /**
     * Fast deterministic pattern matcher for instant tool execution (<0.01ms)
     */
    fun tryDirectToolMatch(message: String): ParsedResponse.ToolCall? {
        val trimmed = message.trim().lowercase()

        // 1. Brightness: e.g. "set brightness to 180", "brightness 200", "set brightness to max", "ekran yorug'ligi 150"
        if (trimmed.contains("brightness") || trimmed.contains("yorug") || (trimmed.contains("ekran") && (trimmed.contains("1") || trimmed.contains("2") || trimmed.contains("max")))) {
            val level = when {
                trimmed.contains("max") || trimmed.contains("eng baland") || trimmed.contains("to'liq") -> 255
                trimmed.contains("min") || trimmed.contains("eng past") -> 10
                else -> {
                    val match = Regex("""\d+""").find(trimmed)
                    match?.value?.toIntOrNull()?.coerceIn(0, 255) ?: 180
                }
            }
            return ParsedResponse.ToolCall(
                toolName = "set_screen_brightness",
                args = mapOf("level" to level),
                rawJson = """{"tool":"set_screen_brightness","args":{"level":$level}}"""
            )
        }

        // 2. Volume: e.g. "set media volume to 8", "volume 10", "ovoz 5", "ovozni 12 qil", "max volume"
        if (trimmed.contains("volume") || trimmed.contains("ovoz") || trimmed.contains("tovush")) {
            val level = when {
                trimmed.contains("max") || trimmed.contains("eng baland") -> 15
                trimmed.contains("min") || trimmed.contains("jim") || trimmed.contains("mute") -> 0
                else -> {
                    val match = Regex("""\d+""").find(trimmed)
                    match?.value?.toIntOrNull()?.coerceIn(0, 15) ?: 8
                }
            }
            return ParsedResponse.ToolCall(
                toolName = "set_media_volume",
                args = mapOf("level" to level),
                rawJson = """{"tool":"set_media_volume","args":{"level":$level}}"""
            )
        }

        // 3. Wi-Fi: e.g. "turn off wi-fi", "turn on wi-fi", "wifi off", "wifi on", "wifini o'chir", "wifini yoq"
        if (trimmed.contains("wifi") || trimmed.contains("wi-fi")) {
            val state = when {
                trimmed.contains("off") || trimmed.contains("disable") || trimmed.contains("o'chir") || trimmed.contains("ochir") -> false
                trimmed.contains("on") || trimmed.contains("enable") || trimmed.contains("yoq") -> true
                else -> null
            }
            if (state != null) {
                return ParsedResponse.ToolCall(
                    toolName = "toggle_wifi",
                    args = mapOf("state" to state),
                    rawJson = """{"tool":"toggle_wifi","args":{"state":$state}}"""
                )
            }
        }

        // 4. List Apps: e.g. "list installed apps", "show apps", "dasturlar ro'yxati", "ilovalarni ko'rsat"
        if ((trimmed.contains("list") || trimmed.contains("show") || trimmed.contains("ko'rsat") || trimmed.contains("korsat") || trimmed.contains("ro'yxat")) &&
            (trimmed.contains("app") || trimmed.contains("ilova") || trimmed.contains("dastur") || trimmed.contains("package"))) {
            return ParsedResponse.ToolCall(
                toolName = "list_installed_apps",
                args = mapOf("filter" to "third_party"),
                rawJson = """{"tool":"list_installed_apps","args":{"filter":"third_party"}}"""
            )
        }

        // 5. Silent Uninstall: e.g. "uninstall com.example.app", "com.example.app ni o'chir"
        val uninstallRegex = Regex("""(?:silent\s+)?uninstall\s+([a-zA-Z0-9._]+)|([a-zA-Z0-9._]+)\s+(?:ilovasini\s+)?(?:o'chir|ochir)""", RegexOption.IGNORE_CASE)
        val uMatch = uninstallRegex.find(trimmed)
        if (uMatch != null) {
            val pkg = uMatch.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
            if (pkg != null && pkg.contains(".")) {
                return ParsedResponse.ToolCall(
                    toolName = "silent_uninstall_app",
                    args = mapOf("packageName" to pkg),
                    rawJson = """{"tool":"silent_uninstall_app","args":{"packageName":"$pkg"}}"""
                )
            }
        }

        return null
    }

    /**
     * Formats user input into Qwen2.5 ChatML template
     */
    fun formatPrompt(userMessage: String, history: List<Pair<String, String>> = emptyList()): String {
        val systemPrompt = if (isDeviceControlIntent(userMessage)) {
            """You are DevicePilot AI. Output ONLY JSON: {"tool":"<name>","args":{...}}
Examples:
User: Turn off wifi
Assistant: {"tool":"toggle_wifi","args":{"state":false}}
User: Set brightness to 180
Assistant: {"tool":"set_screen_brightness","args":{"level":180}}
User: Set volume to 8
Assistant: {"tool":"set_media_volume","args":{"level":8}}"""
        } else {
            "You are DevicePilot AI, an intelligent mobile assistant. Answer questions concisely and politely in the user's language."
        }

        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")

        for ((role, content) in history) {
            sb.append("<|im_start|>").append(role).append("\n").append(content).append("<|im_end|>\n")
        }

        sb.append("<|im_start|>user\n").append(userMessage).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /**
     * Parses the LLM's completed output to check for tool invocation JSON
     */
    fun parseOutput(rawOutput: String): ParsedResponse {
        val trimmed = rawOutput.trim()

        // Extract JSON candidate string if wrapped in markdown code blocks
        val jsonCandidate = when {
            trimmed.startsWith("```json") && trimmed.endsWith("```") -> {
                trimmed.removePrefix("```json").removeSuffix("```").trim()
            }
            trimmed.startsWith("```") && trimmed.endsWith("```") -> {
                trimmed.removePrefix("```").removeSuffix("```").trim()
            }
            trimmed.contains("{") && trimmed.contains("}") -> {
                val start = trimmed.indexOf('{')
                val end = trimmed.lastIndexOf('}')
                if (start in 0 until end) {
                    trimmed.substring(start, end + 1)
                } else {
                    trimmed
                }
            }
            else -> trimmed
        }

        return try {
            val json = JSONObject(jsonCandidate)
            if (json.has("tool")) {
                val toolName = json.getString("tool")
                val argsMap = mutableMapOf<String, Any?>()
                if (json.has("args")) {
                    val argsObj = json.getJSONObject("args")
                    val keys = argsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        argsMap[key] = argsObj.get(key)
                    }
                }
                ParsedResponse.ToolCall(
                    toolName = toolName,
                    args = argsMap,
                    rawJson = jsonCandidate
                )
            } else {
                ParsedResponse.Text(rawOutput)
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Not a valid tool JSON call: ${e.message}")
            ParsedResponse.Text(rawOutput)
        }
    }

    /**
     * Dispatches the parsed response to the privileged DeviceController if it's a tool
     */
    suspend fun executeIfTool(parsed: ParsedResponse): ParsedResponse {
        if (parsed !is ParsedResponse.ToolCall) return parsed

        val executionResult = DeviceController.dispatchTool(parsed.toolName, parsed.args)
        return parsed.copy(executionResult = executionResult)
    }
}
