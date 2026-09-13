package com.devicepilot.ai.agent

import org.json.JSONObject

data class ParsedCall(val name: String, val arguments: JSONObject, val thought: String?, val normalizedJson: String)

/**
 * Extracts a tool call from raw model output. Small models are sloppy, so this is
 * deliberately forgiving: it handles <tool_call> tags, bare JSON, markdown fences,
 * `tool`/`function`/`action` keys, `args`/`parameters`/`input` keys, trailing commas,
 * and a missing closing tag/brace.
 */
object ToolCallParser {

    private val tagRegex = Regex("""<tool_call>\s*(.*?)\s*(</tool_call>|$)""", RegexOption.DOT_MATCHES_ALL)
    private val fenceRegex = Regex("""```(?:json)?\s*(\{.*?})\s*```""", RegexOption.DOT_MATCHES_ALL)

    fun parse(raw: String): ParsedCall? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        var thought: String? = null
        var candidate: String? = null

        tagRegex.find(text)?.let { m ->
            candidate = m.groupValues[1]
            thought = text.substring(0, m.range.first).trim().ifBlank { null }
        }
        if (candidate == null) fenceRegex.find(text)?.let { m ->
            candidate = m.groupValues[1]
            thought = text.substring(0, m.range.first).trim().ifBlank { null }
        }
        if (candidate == null) {
            val start = text.indexOf('{')
            if (start >= 0) {
                candidate = text.substring(start)
                thought = text.substring(0, start).trim().ifBlank { null }
            }
        }
        val json = candidate?.let { parseJsonLenient(it) } ?: return null

        val name = firstString(json, "name", "tool", "function", "action", "tool_name")?.trim()
            ?: (json.optJSONObject("function")?.optString("name"))?.takeIf { it.isNotBlank() }
            ?: return null

        val args: JSONObject = json.optJSONObject("arguments")
            ?: json.optJSONObject("args")
            ?: json.optJSONObject("parameters")
            ?: json.optJSONObject("input")
            ?: json.optJSONObject("function")?.optJSONObject("arguments")
            ?: json.optString("arguments").takeIf { it.startsWith("{") }?.let { parseJsonLenient(it) }
            ?: JSONObject().also { o ->
                // Model may have inlined args at top level: {"name":"tap","x":1,"y":2}
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k !in setOf("name", "tool", "function", "action", "tool_name", "thought", "reasoning")) o.put(k, json.get(k))
                }
            }

        val normalized = JSONObject().put("name", name).put("arguments", args).toString()
        return ParsedCall(name, args, thought?.let { cleanThought(it) }, normalized)
    }

    private fun cleanThought(t: String): String? =
        t.replace(Regex("""^(Thought|Reasoning|Plan):\s*""", RegexOption.IGNORE_CASE), "")
            .lines().take(4).joinToString("\n").trim().take(400).ifBlank { null }

    private fun firstString(o: JSONObject, vararg keys: String): String? {
        for (k in keys) if (o.has(k) && o.opt(k) is String) return o.getString(k).takeIf { it.isNotBlank() }
        return null
    }

    fun parseJsonLenient(s: String): JSONObject? {
        var txt = s.trim()
        // Cut at the matching closing brace for the first '{'
        val start = txt.indexOf('{')
        if (start < 0) return null
        txt = txt.substring(start)
        val end = matchingBrace(txt)
        var body = if (end > 0) txt.substring(0, end + 1) else txt
        try { return JSONObject(body) } catch (_: Throwable) { }

        // Repairs: trailing commas, single quotes, unclosed braces, Python literals
        body = body.replace(Regex(""",\s*([}\]])"""), "$1")
        body = body.replace(Regex("""(?<=[{,]\s{0,8})'([^']+)'\s*:"""), "\"$1\":")
        body = body.replace(Regex(""":\s*'([^']*)'"""), ": \"$1\"")
        body = body.replace(Regex("""\bTrue\b"""), "true").replace(Regex("""\bFalse\b"""), "false").replace(Regex("""\bNone\b"""), "null")
        if (end <= 0) {
            val open = body.count { it == '{' } - body.count { it == '}' }
            if (body.count { it == '"' } % 2 == 1) body += "\""
            repeat(open.coerceAtLeast(0)) { body += "}" }
        }
        return try { JSONObject(body) } catch (_: Throwable) { null }
    }

    private fun matchingBrace(s: String): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in s.indices) {
            val c = s[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }
}
