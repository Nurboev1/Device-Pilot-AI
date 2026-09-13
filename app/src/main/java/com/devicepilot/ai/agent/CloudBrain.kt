package com.devicepilot.ai.agent

import android.util.Log
import com.devicepilot.ai.model.CloudConfig
import com.devicepilot.ai.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Uses any OpenAI-compatible /chat/completions endpoint with native function calling
 * (OpenAI, Groq, OpenRouter, Together, DeepSeek, Gemini's OpenAI endpoint, local
 * Ollama/LM Studio over LAN, …). Converts the response back into the same
 * <tool_call> text the local path produces so the runner is provider-agnostic.
 */
class CloudBrain(private val configProvider: () -> CloudConfig) : AgentBrain {

    override val isReady: Boolean get() = configProvider().let { it.apiKey.isNotBlank() || it.baseUrl.contains("localhost") || it.baseUrl.contains("192.168.") }
    override val label: String get() = configProvider().let { "${it.providerName} · ${it.model}" }

    private val active = AtomicReference<HttpURLConnection?>(null)

    override fun cancel() { runCatching { active.getAndSet(null)?.disconnect() } }

    override suspend fun nextStep(registry: ToolRegistry, deviceHint: String, turns: List<Turn>, onToken: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val cfg = configProvider()
            if (cfg.apiKey.isBlank() && !cfg.baseUrl.contains("localhost") && !cfg.baseUrl.contains("192.168.")) {
                return@withContext "Error: no API key set. Open settings → Cloud and paste your key."
            }
            val body = JSONObject()
                .put("model", cfg.model)
                .put("temperature", 0.1)
                .put("max_tokens", 600)
                .put("messages", messages(registry, deviceHint, turns))
                .put("tools", tools(registry))
                .put("tool_choice", "required")
                .put("parallel_tool_calls", false)

            val url = URL(cfg.baseUrl.trimEnd('/') + "/chat/completions")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 90_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    if (cfg.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
                    setRequestProperty("HTTP-Referer", "https://github.com/Nurboev1/Device-Pilot-AI")
                    setRequestProperty("X-Title", "DevicePilot AI")
                }
                active.set(conn)
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                if (code !in 200..299) {
                    val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                    return@withContext "Error: HTTP $code from ${cfg.providerName}: ${msg ?: text.take(300)}"
                }
                val choice = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                val msg = choice.getJSONObject("message")
                val calls = msg.optJSONArray("tool_calls")
                val out = if (calls != null && calls.length() > 0) {
                    val fn = calls.getJSONObject(0).getJSONObject("function")
                    val argsRaw = fn.optString("arguments", "{}")
                    val args = runCatching { JSONObject(argsRaw) }.getOrElse { JSONObject() }
                    "<tool_call>\n" + JSONObject().put("name", fn.getString("name")).put("arguments", args) + "\n</tool_call>"
                } else {
                    // Provider ignored tool_choice and answered in prose → treat as reply.
                    val content = msg.optString("content").trim()
                    "<tool_call>\n" + JSONObject().put("name", "reply").put("arguments", JSONObject().put("message", content.ifBlank { "OK." })) + "\n</tool_call>"
                }
                onToken(out)
                out
            } catch (t: Throwable) {
                Log.e(TAG, "cloud request failed", t)
                "Error: ${t.message ?: "network error"}"
            } finally {
                active.compareAndSet(conn, null)
            }
        }

    private fun messages(registry: ToolRegistry, deviceHint: String, turns: List<Turn>): JSONArray {
        val arr = JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", CloudPrompt.system(deviceHint)))
        var callCounter = 0
        var lastCallId: String? = null
        for (t in turns) {
            when (t) {
                is Turn.User -> arr.put(JSONObject().put("role", "user").put("content", t.text))
                is Turn.Text -> arr.put(JSONObject().put("role", "assistant").put("content", t.text))
                is Turn.Assistant -> {
                    val j = runCatching { JSONObject(t.toolJson) }.getOrElse { JSONObject().put("name", "unknown").put("arguments", JSONObject()) }
                    val id = "call_${callCounter++}"
                    lastCallId = id
                    val tc = JSONObject().put("id", id).put("type", "function").put("function",
                        JSONObject().put("name", j.optString("name")).put("arguments", j.optJSONObject("arguments")?.toString() ?: "{}"))
                    val m = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(tc))
                    if (!t.thought.isNullOrBlank()) m.put("content", t.thought)
                    arr.put(m)
                }
                is Turn.ToolOutput -> {
                    val id = lastCallId ?: "call_${callCounter++}"
                    arr.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", t.output.take(6000)))
                    lastCallId = null
                }
            }
        }
        // OpenAI rejects a tool_call with no following tool message; if the last assistant turn is an
        // unanswered call (e.g. ask_user), append a placeholder.
        if (lastCallId != null) arr.put(JSONObject().put("role", "tool").put("tool_call_id", lastCallId).put("content", "(no result)"))
        return arr
    }

    private fun tools(registry: ToolRegistry): JSONArray {
        val arr = JSONArray()
        for (t in registry.all) arr.put(JSONObject().put("type", "function").put("function", t.schema()))
        return arr
    }

    companion object { private const val TAG = "CloudBrain" }
}

object CloudPrompt {
    fun system(deviceHint: String) = """
You are DevicePilot, an autonomous agent controlling the user's Android phone ($deviceHint) with ADB-level privileges through Shizuku.

You act by calling exactly one tool per turn and reading its result. Tools are grouped as screen (get_screen, tap_element, type_text, swipe, press_key…), apps, system settings, files, and control (reply, ask_user, done).

Rules:
- For greetings/questions needing no device action, call reply.
- For simple settings (Wi-Fi, brightness, volume, dark mode…) use the direct set_* tool; do not navigate Settings manually.
- For app UI work: open_app → get_screen → tap_element/type_text by index → get_screen to verify. Never guess coordinates.
- When the goal is achieved, call done with an accurate 1–2 sentence summary of what actually happened (based on tool results, not assumptions).
- If a tool result says ERROR, do not claim success. Try another approach once, then ask_user or done with an honest explanation.
- Never take destructive actions the user did not request.
""".trim()
}
