package com.devicepilot.ai.agent

import com.devicepilot.ai.tools.Param
import com.devicepilot.ai.tools.Tool
import com.devicepilot.ai.tools.ToolRegistry

/**
 * Builds a GBNF grammar that constrains the model to emit exactly:
 *
 *   <tool_call>
 *   {"name": "<one of the tools>", "arguments": {<typed args for that tool>}}
 *   </tool_call>
 *
 * With grammar sampling a 0.5B model cannot invent tool names, misspell keys,
 * or drift into prose — it can only choose *which* tool and *what values*.
 */
object ToolGrammar {

    fun build(registry: ToolRegistry): String {
        val sb = StringBuilder()
        val tools = registry.all.sortedBy { it.name }

        sb.append("root ::= \"<tool_call>\\n{\\\"name\\\": \" call \"}\\n</tool_call>\"\n")
        sb.append("call ::= ").append(tools.joinToString(" | ") { ruleName(it) }).append('\n')

        for (t in tools) {
            sb.append(ruleName(t)).append(" ::= \"\\\"").append(t.name).append("\\\", \\\"arguments\\\": {\" ")
            sb.append(argsRule(t.params)).append(" \"}\"\n")
        }

        sb.append(
            """
            string ::= "\"" chars "\""
            chars ::= ( [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]) )*
            integer ::= "-"? ( "0" | [1-9] [0-9]{0,8} )
            number ::= "-"? ( "0" | [1-9] [0-9]{0,8} ) ( "." [0-9]{1,4} )?
            boolean ::= "true" | "false"
            ws ::= " "?
            """.trimIndent()
        ).append('\n')
        return sb.toString()
    }

    private fun ruleName(t: Tool) = "t_" + t.name.replace(Regex("[^a-zA-Z0-9]"), "_")

    /**
     * Required params in fixed order, optional params each optionally appended.
     * (Fixed ordering is fine: the model only fills values.)
     */
    private fun argsRule(params: List<Param>): String {
        if (params.isEmpty()) return "\"\""
        val required = params.filter { it.required }
        val optional = params.filter { !it.required }

        fun kv(p: Param, first: Boolean) = "\"${if (first) "" else ", "}\\\"${p.name}\\\": \" ${valueRule(p)}"

        if (required.isNotEmpty()) {
            val parts = mutableListOf<String>()
            required.forEachIndexed { i, p -> parts += kv(p, i == 0) }
            optional.forEach { p -> parts += "( ${kv(p, false)} )?" }
            return parts.joinToString(" ")
        }

        // All optional: enumerate subsets so commas are always well-formed (max ~4 params → 16 alternatives).
        val alts = mutableListOf("\"\"")
        val n = optional.size
        for (mask in 1 until (1 shl n)) {
            val chosen = optional.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            alts += chosen.mapIndexed { i, p -> kv(p, i == 0) }.joinToString(" ")
        }
        return "( " + alts.joinToString(" | ") + " )"
    }

    private fun valueRule(p: Param): String {
        p.choices?.let { ch ->
            return "( " + ch.joinToString(" | ") { "\"\\\"$it\\\"\"" } + " )"
        }
        return when (p.type) {
            "integer" -> "integer"
            "number" -> "number"
            "boolean" -> "boolean"
            else -> "string"
        }
    }
}
