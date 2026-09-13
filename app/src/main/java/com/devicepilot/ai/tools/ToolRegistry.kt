package com.devicepilot.ai.tools

import android.content.Context
import org.json.JSONArray

/**
 * Central catalog of every tool the agent may call.
 */
class ToolRegistry(context: Context) {

    private val tools: Map<String, Tool> =
        (UiTools.all(context) + AppTools.all(context) + SystemTools.all(context) + controlTools())
            .associateBy { it.name }

    val all: Collection<Tool> get() = tools.values

    operator fun get(name: String): Tool? = tools[name] ?: aliases[name]?.let { tools[it] }

    /** Full JSON schema list for prompts that support it. */
    fun schemaJson(): JSONArray = JSONArray().also { arr -> tools.values.forEach { arr.put(it.schema()) } }

    /**
     * Compact catalog, ~4x fewer tokens than JSON schemas. Small models cope better with this.
     * Format:  name(param: type, optional?: type) — description
     */
    fun compactCatalog(): String {
        val sb = StringBuilder()
        val byCat = tools.values.groupBy { it.category }
        val order = listOf("control", "screen", "apps", "system", "info", "comm", "files", "advanced")
        for (cat in order + (byCat.keys - order.toSet())) {
            val list = byCat[cat] ?: continue
            sb.append("## ").append(cat).append('\n')
            for (t in list) {
                sb.append(t.name).append('(')
                sb.append(t.params.joinToString(", ") { p ->
                    val ty = p.choices?.joinToString("|") ?: p.type
                    (if (p.required) p.name else p.name + "?") + ": " + ty
                })
                sb.append(") — ").append(t.description.substringBefore(". ").take(120))
                if (t.risk == Risk.CONFIRM) sb.append(" [needs confirmation]")
                sb.append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    private fun controlTools(): List<Tool> = listOf(
        SimpleTool(
            name = "done",
            description = "Call when the task is fully complete. Give a short summary of what was done.",
            params = listOf(Param("summary", "string", "What you accomplished, 1-2 sentences")),
            category = "control", summary = { "Done" }
        ) { ToolResult.ok(it.string("summary") ?: "Done.") },
        SimpleTool(
            name = "ask_user",
            description = "Ask the user a question when you need information or a decision you cannot infer. The task pauses until they answer.",
            params = listOf(Param("question", "string", "")),
            category = "control", summary = { "Ask user" }
        ) { ToolResult.ok(it.requireString("question")) },
        SimpleTool(
            name = "reply",
            description = "Reply conversationally when the user is chatting or asking a question that needs no device action. Ends the turn.",
            params = listOf(Param("message", "string", "")),
            category = "control", summary = { "Reply" }
        ) { ToolResult.ok(it.requireString("message")) }
    )

    private val aliases = mapOf(
        "finish" to "done", "complete" to "done", "task_complete" to "done", "final_answer" to "done",
        "answer" to "reply", "respond" to "reply", "say" to "reply", "message" to "reply",
        "click" to "tap_element", "click_element" to "tap_element", "tap_index" to "tap_element",
        "input_text" to "type_text", "type" to "type_text", "enter_text" to "type_text",
        "scroll" to "swipe", "key" to "press_key", "keyevent" to "press_key", "press" to "press_key",
        "launch_app" to "open_app", "start_app" to "open_app", "open" to "open_app",
        "screenshot_screen" to "screenshot", "read_screen" to "get_screen", "screen" to "get_screen", "observe" to "get_screen",
        "set_screen_brightness" to "set_brightness", "set_media_volume" to "set_volume", "toggle_wifi" to "set_wifi",
        "silent_uninstall_app" to "uninstall_app", "list_installed_apps" to "list_apps", "run_shell" to "shell", "adb" to "shell",
        "sleep" to "wait", "device_state" to "get_device_state", "get_state" to "get_device_state"
    )
}
