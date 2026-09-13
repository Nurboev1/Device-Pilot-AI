package com.devicepilot.ai.agent

import com.devicepilot.ai.tools.ToolRegistry

sealed class Turn {
    data class User(val text: String) : Turn()
    /** Model output: optional thought + a single tool call (kept as the raw JSON we accepted). */
    data class Assistant(val thought: String?, val toolJson: String) : Turn()
    data class ToolOutput(val name: String, val output: String) : Turn()
    data class Text(val text: String) : Turn()   // plain assistant text (reply/done summaries)
}

/**
 * Builds the Qwen2.5 ChatML prompt for one agent step. Uses the Hermes-style
 * <tool_call> format Qwen2.5-Instruct was fine-tuned on, but simplified: exactly one
 * call per step, and a compact tool catalog to save tokens.
 */
object AgentPrompt {

    private const val MAX_TOOL_OUTPUT_CHARS = 2400
    private const val MAX_OLD_TOOL_OUTPUT_CHARS = 300

    fun system(registry: ToolRegistry, deviceHint: String): String = """
You are DevicePilot, an agent that controls this Android phone by calling tools (ADB privileges via Shizuku).

Every turn you output exactly one tool call:
<tool_call>
{"name": "TOOL", "arguments": {...}}
</tool_call>

Rules:
- Small talk or a question that needs no device action → reply(message).
- Task finished → done(summary).
- Need info from the user → ask_user(question).
- Simple settings (wifi, brightness, volume, dark mode…) → use the direct set_* tool. Do NOT open Settings for these.
- App UI tasks → open_app, then get_screen, then tap_element / type_text using the element indexes you saw, then get_screen again to verify.
- Never repeat a call that already failed; try something else or ask_user.

Examples:
user: hi → reply {"message": "Hi! What should I do on your phone?"}
user: turn off wifi → set_wifi {"enabled": false} → then done {"summary": "Wi-Fi is off."}
user: set brightness to 40% → set_brightness {"percent": 40} → done
user: open bluetooth settings → open_settings_page {"page": "bluetooth"} → done
user: which apps have camera permission → list_apps, then get_app_permissions for likely apps, then done with the answer

Tools:
${registry.compactCatalog()}

Device: $deviceHint
""".trim()

    fun build(systemPrompt: String, turns: List<Turn>): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")

        // Truncate older tool outputs aggressively; only the most recent one needs full detail.
        val lastToolIdx = turns.indexOfLast { it is Turn.ToolOutput }

        turns.forEachIndexed { i, t ->
            when (t) {
                is Turn.User -> sb.append("<|im_start|>user\n").append(t.text).append("<|im_end|>\n")
                is Turn.Text -> sb.append("<|im_start|>assistant\n").append(t.text).append("<|im_end|>\n")
                is Turn.Assistant -> {
                    sb.append("<|im_start|>assistant\n")
                    if (!t.thought.isNullOrBlank()) sb.append(t.thought.trim()).append('\n')
                    sb.append("<tool_call>\n").append(t.toolJson).append("\n</tool_call><|im_end|>\n")
                }
                is Turn.ToolOutput -> {
                    val limit = if (i == lastToolIdx) MAX_TOOL_OUTPUT_CHARS else MAX_OLD_TOOL_OUTPUT_CHARS
                    val body = if (t.output.length > limit) t.output.take(limit) + "\n…[truncated]" else t.output
                    sb.append("<|im_start|>user\n<tool_response>\n").append(body).append("\n</tool_response><|im_end|>\n")
                }
            }
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}
