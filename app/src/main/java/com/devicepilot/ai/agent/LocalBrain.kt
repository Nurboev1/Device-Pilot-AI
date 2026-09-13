package com.devicepilot.ai.agent

import com.devicepilot.ai.llm.LlamaEngine
import com.devicepilot.ai.model.PromptStyle
import com.devicepilot.ai.tools.ToolRegistry

/** On-device inference through llama.cpp with grammar-constrained tool calls. */
class LocalBrain(
    private val engine: LlamaEngine,
    private val maxTokens: Int = 320,
    private val temperature: Float = 0.15f
) : AgentBrain {

    override val isReady: Boolean get() = engine.isReady
    override val label: String get() = engine.loadedSpec?.displayName ?: "Local model"

    private var hermesGrammar: String? = null
    private var hammerGrammar: String? = null

    private fun style(): PromptStyle = engine.loadedSpec?.promptStyle ?: PromptStyle.QWEN_HERMES

    fun promptPrefix(registry: ToolRegistry, deviceHint: String): String = when (style()) {
        PromptStyle.HAMMER -> HammerPrompt.prefix(registry, deviceHint)
        PromptStyle.QWEN_HERMES -> AgentPrompt.build(AgentPrompt.system(registry, deviceHint), emptyList()).substringBefore("<|im_start|>assistant")
    }

    private fun buildPrompt(prefix: String, turns: List<Turn>): String = when (style()) {
        PromptStyle.HAMMER -> HammerPrompt.build(prefix, turns)
        PromptStyle.QWEN_HERMES -> prefix + AgentPrompt.build("", turns)
    }

    private fun grammar(registry: ToolRegistry): String = when (style()) {
        PromptStyle.HAMMER -> hammerGrammar ?: HammerPrompt.grammar(registry).also { hammerGrammar = it }
        PromptStyle.QWEN_HERMES -> hermesGrammar ?: ToolGrammar.build(registry).also { hermesGrammar = it }
    }

    override suspend fun nextStep(registry: ToolRegistry, deviceHint: String, turns: List<Turn>, onToken: (String) -> Unit): String {
        if (!engine.isReady) return "Error: no local model loaded. Open settings and choose a model."
        val prompt = buildPrompt(promptPrefix(registry, deviceHint), turns)
        return engine.generate(prompt, maxTokens, temperature, grammar(registry), onToken)
    }

    override fun cancel() = engine.cancel()
    override fun reset() = engine.resetCache()

    override suspend fun warmUp(registry: ToolRegistry, deviceHint: String) {
        if (engine.isReady) engine.warmUp(promptPrefix(registry, deviceHint))
    }
}
