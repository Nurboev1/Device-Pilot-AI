package com.devicepilot.ai.tools

import org.json.JSONObject

enum class Risk {
    /** Read-only or trivially reversible. Executes immediately. */
    SAFE,
    /** Changes device state in a way the user may not expect. Executes immediately but is logged. */
    LOW,
    /** Destructive or hard to undo. Requires explicit user confirmation. */
    CONFIRM
}

data class Param(
    val name: String,
    val type: String,          // "string" | "integer" | "boolean" | "number"
    val description: String,
    val required: Boolean = true,
    val choices: List<String>? = null
)

data class ToolResult(
    val success: Boolean,
    /** Text fed back to the model. Keep it compact. */
    val output: String,
    /** Optional path to an artifact (e.g. screenshot) for the UI. */
    val artifactPath: String? = null,
    /** The underlying shell command, for display. */
    val command: String? = null
) {
    companion object {
        fun ok(output: String, command: String? = null, artifact: String? = null) = ToolResult(true, output, artifact, command)
        fun fail(output: String, command: String? = null) = ToolResult(false, output, null, command)
    }
}

class ToolArgs(private val json: JSONObject) {
    fun string(name: String): String? = if (json.has(name) && !json.isNull(name)) json.get(name).toString() else null
    fun requireString(name: String): String = string(name)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing required argument '$name'")
    fun int(name: String): Int? = when (val v = if (json.has(name)) json.opt(name) else null) {
        null -> null
        is Number -> v.toInt()
        is String -> v.trim().removeSuffix("%").trim().toDoubleOrNull()?.toInt()
        else -> null
    }
    fun requireInt(name: String): Int = int(name) ?: throw IllegalArgumentException("Missing required integer argument '$name'")
    fun bool(name: String): Boolean? = when (val v = if (json.has(name)) json.opt(name) else null) {
        null -> null
        is Boolean -> v
        is String -> when (v.trim().lowercase()) {
            "true", "on", "yes", "enable", "enabled", "1" -> true
            "false", "off", "no", "disable", "disabled", "0" -> false
            else -> null
        }
        is Number -> v.toInt() != 0
        else -> null
    }
    fun requireBool(name: String): Boolean = bool(name) ?: throw IllegalArgumentException("Missing required boolean argument '$name'")
    fun has(name: String) = json.has(name) && !json.isNull(name)
    val raw: JSONObject get() = json
    override fun toString(): String = json.toString()
}

abstract class Tool(
    val name: String,
    val description: String,
    val params: List<Param> = emptyList(),
    val risk: Risk = Risk.SAFE,
    val category: String
) {
    abstract suspend fun execute(args: ToolArgs): ToolResult

    /** Short human-facing summary for the confirmation dialog / step card. */
    open fun summarize(args: ToolArgs): String = "$name ${args.raw}"

    /** OpenAI-style function schema, used in the system prompt. */
    fun schema(): JSONObject {
        val props = JSONObject()
        val required = mutableListOf<String>()
        for (p in params) {
            val o = JSONObject().put("type", p.type).put("description", p.description)
            p.choices?.let { o.put("enum", org.json.JSONArray(it)) }
            props.put(p.name, o)
            if (p.required) required += p.name
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("required", org.json.JSONArray(required)))
    }
}

/** Convenience for tools implemented as a lambda. */
class SimpleTool(
    name: String,
    description: String,
    params: List<Param> = emptyList(),
    risk: Risk = Risk.SAFE,
    category: String,
    private val summary: ((ToolArgs) -> String)? = null,
    private val body: suspend (ToolArgs) -> ToolResult
) : Tool(name, description, params, risk, category) {
    override suspend fun execute(args: ToolArgs): ToolResult = body(args)
    override fun summarize(args: ToolArgs): String = summary?.invoke(args) ?: super.summarize(args)
}
