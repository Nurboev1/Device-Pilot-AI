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
You are DevicePilot, an autonomous agent that controls this Android phone by calling tools. You have ADB-level privileges through Shizuku.

# How to act
Each turn, output ONE tool call and nothing else, in this exact format:
<tool_call>
{"name": "<tool>", "arguments": {<args>}}
</tool_call>

Rules:
- One tool per turn. You will receive the result, then choose the next tool.
- To interact with an app's UI: open_app → get_screen → tap_element/type_text/swipe → get_screen to verify. Never guess coordinates; read the screen first.
- Use the dedicated system tools (set_wifi, set_brightness, …) instead of navigating Settings by hand.
- After the goal is achieved, call done with a brief summary. If the user is just chatting or asking a question, call reply.
- If something is ambiguous or you need private info (a password, which contact), call ask_user.
- If an action fails twice, try a different approach or ask_user. Do not repeat the identical failing call.
- Never do anything destructive the user did not ask for.

# Tools
${registry.compactCatalog()}

# Device
$deviceHint
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
