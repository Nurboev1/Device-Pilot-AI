package com.devicepilot.ai.agent

import com.devicepilot.ai.tools.Risk

enum class StepStatus { THINKING, AWAITING_CONFIRMATION, EXECUTING, SUCCESS, FAILED, DENIED }

data class AgentStep(
    val index: Int,
    val toolName: String,
    val argsJson: String,
    val summary: String,
    val risk: Risk,
    val thought: String? = null,
    val status: StepStatus = StepStatus.THINKING,
    val output: String = "",
    val command: String? = null,
    val artifactPath: String? = null,
    val durationMs: Long = 0
)

sealed class AgentEvent {
    data class Thinking(val partialText: String) : AgentEvent()
    data class StepStarted(val step: AgentStep) : AgentEvent()
    data class StepUpdated(val step: AgentStep) : AgentEvent()
    data class NeedsConfirmation(val step: AgentStep) : AgentEvent()
    data class AskUser(val question: String) : AgentEvent()
    data class Reply(val text: String) : AgentEvent()
    data class Done(val summary: String, val steps: Int) : AgentEvent()
    data class Failed(val reason: String) : AgentEvent()
    data object Cancelled : AgentEvent()
}
