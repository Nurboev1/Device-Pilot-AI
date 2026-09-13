package com.devicepilot.ai.model

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BrainMode { CLOUD, LOCAL }

data class CloudProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyUrl: String,
    val models: List<String>
)

data class CloudConfig(
    val providerId: String,
    val providerName: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String
)

object CloudProviders {
    val OPENAI = CloudProvider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "https://platform.openai.com/api-keys",
        listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1"))
    val GROQ = CloudProvider("groq", "Groq (free tier)", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "https://console.groq.com/keys",
        listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "qwen/qwen3-32b", "moonshotai/kimi-k2-instruct"))
    val OPENROUTER = CloudProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", "https://openrouter.ai/keys",
        listOf("openai/gpt-4o-mini", "anthropic/claude-3.5-haiku", "google/gemini-2.0-flash-001", "qwen/qwen-2.5-72b-instruct", "meta-llama/llama-3.3-70b-instruct"))
    val GEMINI = CloudProvider("gemini", "Google Gemini (free tier)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "https://aistudio.google.com/apikey",
        listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite", "gemini-3-flash-preview"))
    val DEEPSEEK = CloudProvider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "https://platform.deepseek.com/api_keys",
        listOf("deepseek-chat"))
    val CUSTOM = CloudProvider("custom", "Custom (OpenAI-compatible)", "http://192.168.1.100:11434/v1", "qwen2.5:7b", "",
        listOf())

    val all = listOf(GEMINI, GROQ, OPENAI, OPENROUTER, DEEPSEEK, CUSTOM)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: GEMINI
}

/** Persists brain mode + cloud credentials. Keys live in app-private SharedPreferences. */
class BrainSettings(context: Context) {

    private val prefs = context.getSharedPreferences("devicepilot_brain", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(BrainMode.valueOf(prefs.getString("mode", BrainMode.CLOUD.name)!!))
    val mode: StateFlow<BrainMode> = _mode.asStateFlow()

    private val _cloud = MutableStateFlow(load())
    val cloud: StateFlow<CloudConfig> = _cloud.asStateFlow()

    private fun load(): CloudConfig {
        val p = CloudProviders.byId(prefs.getString("provider", null))
        return CloudConfig(
            providerId = p.id,
            providerName = p.name,
            baseUrl = prefs.getString("baseUrl_${p.id}", null) ?: p.baseUrl,
            model = prefs.getString("model_${p.id}", null) ?: p.defaultModel,
            apiKey = prefs.getString("key_${p.id}", "") ?: ""
        )
    }

    fun setMode(m: BrainMode) {
        prefs.edit().putString("mode", m.name).apply(); _mode.value = m
    }

    fun setProvider(p: CloudProvider) {
        prefs.edit().putString("provider", p.id).apply(); _cloud.value = load()
    }

    fun update(baseUrl: String? = null, model: String? = null, apiKey: String? = null) {
        val id = _cloud.value.providerId
        prefs.edit().apply {
            baseUrl?.let { putString("baseUrl_$id", it.trim()) }
            model?.let { putString("model_$id", it.trim()) }
            apiKey?.let { putString("key_$id", it.trim()) }
        }.apply()
        _cloud.value = load()
    }
}
