package com.devicepilot.ai.agent

import com.devicepilot.ai.tools.Param
import com.devicepilot.ai.tools.Tool
import com.devicepilot.ai.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Prompt + grammar for MadeAgents Hammer 2.1 models, reproducing the exact
 * chat_template they were fine-tuned with:
 *
 *   <|im_start|>system\n{system}<|im_end|>
 *   <|im_start|>user\n[BEGIN OF TASK INSTRUCTION]...[END OF TASK INSTRUCTION]
 *   [BEGIN OF AVAILABLE_TOOLS]{json tools}[END OF AVAILABLE_TOOLS]
 *   [BEGIN OF TASK INSTRUCTION]{format}[END OF TASK INSTRUCTION]<|im_end|>
 *   <|im_start|>user\n{query}<|im_end|>
 *   <|im_start|>assistant\n```\n[{"name": ..., "arguments": {...}}]\n```
 *
 * Tool results are fed back as `<|im_start|>tool\n...<|im_end|>`.
 */
object HammerPrompt {

    private const val TASK_INSTRUCTION = """You are a tool calling assistant. In order to complete the user's request, you need to select one or more appropriate tools from the following tools and fill in the correct values for the tool parameters. Your specific tasks are:
1. Make one or more function/tool calls to meet the request based on the question.
2. If none of the function can be used, point it out and refuse to answer.
3. If the given question lacks the parameters required by the function, also point it out.

The following are characters that may interact with you
1. user: Provides query or additional information.
2. tool: Returns the results of the tool calling."""

    private const val FORMAT_INSTRUCTION = """The output MUST strictly adhere to the following JSON format, and NO other text MUST be included.
The example format is as follows. Please make sure the parameter type is correct. If no function call is needed, please directly output an empty list '[]'
```
[
    {"name": "func_name1", "arguments": {"argument1": "value1", "argument2": "value2"}},
    ... (more tool calls as required)
]
```"""

    fun system(deviceHint: String): String =
        "You are DevicePilot, an agent controlling this Android phone ($deviceHint) through tools. " +
        "Call ONE tool at a time. For greetings or questions that need no device action, call `reply`. " +
        "When the user's request is fully done, call `done` with a summary that matches the tool results (if the tool said OK, say it was done). Prefer direct set_* tools over navigating Settings. " +
        "For app UI tasks: open_app, then get_screen, then tap_element/type_text by index, then get_screen to verify."

    /** The constant prefix (system + tool catalog) — prefilled once. */
    fun prefix(registry: ToolRegistry, deviceHint: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(system(deviceHint)).append("<|im_end|>\n")
        sb.append("<|im_start|>user\n[BEGIN OF TASK INSTRUCTION]\n").append(TASK_INSTRUCTION).append("\n[END OF TASK INSTRUCTION]\n\n")
        sb.append("[BEGIN OF AVAILABLE_TOOLS]\n").append(toolsJson(registry).toString()).append("\n[END OF AVAILABLE_TOOLS]\n\n")
        sb.append("\n[BEGIN OF TASK INSTRUCTION]\n").append(FORMAT_INSTRUCTION).append("\n[END OF TASK INSTRUCTION]\n\n<|im_end|>\n")
        return sb.toString()
    }

    fun build(prefix: String, turns: List<Turn>): String {
        val sb = StringBuilder(prefix)
        val lastToolIdx = turns.indexOfLast { it is Turn.ToolOutput }
        turns.forEachIndexed { i, t ->
            when (t) {
                is Turn.User -> sb.append("<|im_start|>user\n").append(t.text).append("<|im_end|>\n")
                is Turn.Text -> sb.append("<|im_start|>assistant\n").append(t.text).append("<|im_end|>\n")
                is Turn.Assistant -> sb.append("<|im_start|>assistant\n```\n[").append(t.toolJson).append("]\n```<|im_end|>\n")
                is Turn.ToolOutput -> {
                    val limit = if (i == lastToolIdx) 2400 else 300
                    val body = if (t.output.length > limit) t.output.take(limit) + "\n…[truncated]" else t.output
                    sb.append("<|im_start|>tool\n").append(body).append("<|im_end|>\n")
                }
            }
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /** Hammer's flattened tool format: parameters = {name: {type, description, required?}} */
    private fun toolsJson(registry: ToolRegistry): JSONArray {
        val arr = JSONArray()
        for (t in registry.all.sortedBy { it.name }) {
            val params = JSONObject()
            for (p in t.params) {
                val o = JSONObject().put("type", p.type).put("description", p.description.take(90))
                p.choices?.let { o.put("enum", JSONArray(it)) }
                if (p.required) o.put("required", true)
                params.put(p.name, o)
            }
            arr.put(JSONObject().put("name", t.name).put("description", t.description.take(110)).put("parameters", params))
        }
        return arr
    }

    /**
     * Grammar for Hammer output: a fenced JSON array containing exactly one typed call.
     *   ```\n[{"name": "x", "arguments": {...}}]\n```
     */
    fun grammar(registry: ToolRegistry): String {
        val base = ToolGrammar.build(registry)
        // Replace ToolGrammar's <tool_call> root with the Hammer wrapper; reuse per-tool rules.
        val body = base.lines().filterNot { it.startsWith("root ::=") }.joinToString("\n")
        return "root ::= \"```\\n[{\\\"name\\\": \" call \"}]\\n```\"\n" + body
    }
}
