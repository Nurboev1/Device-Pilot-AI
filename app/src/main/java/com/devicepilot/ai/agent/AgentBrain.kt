package com.devicepilot.ai.agent

import com.devicepilot.ai.tools.ToolRegistry

/**
 * Where the agent's next action comes from. Implementations: on-device llama.cpp
 * ([LocalBrain]) or an OpenAI-compatible HTTP API ([CloudBrain]).
 */
interface AgentBrain {
    val isReady: Boolean
    val label: String

    /**
     * Produce the raw model output for the next step. Must contain a tool call the
     * [ToolCallParser] can read (or plain text for a conversational reply).
     * @param turns full conversation for this session (history + current task)
     */
    suspend fun nextStep(
        registry: ToolRegistry,
        deviceHint: String,
        turns: List<Turn>,
        onToken: (String) -> Unit
    ): String

    fun cancel()

    /** Called when the conversation is cleared. */
    fun reset() {}

    /** Optional: precompute anything expensive (e.g. prefill system prompt). */
    suspend fun warmUp(registry: ToolRegistry, deviceHint: String) {}
}
