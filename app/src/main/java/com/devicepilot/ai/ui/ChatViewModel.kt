package com.devicepilot.ai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devicepilot.ai.llm.LlamaEngine
import com.devicepilot.ai.llm.ModelState
import com.devicepilot.ai.llm.ParsedResponse
import com.devicepilot.ai.llm.ToolRouter
import com.devicepilot.ai.shizuku.ShizukuManager
import com.devicepilot.ai.shizuku.ShizukuState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class MessageSender {
    USER, BOT, SYSTEM
}

data class ToolCallInfo(
    val toolName: String,
    val args: Map<String, Any?>,
    val command: String,
    val output: String,
    val isSuccess: Boolean,
    val isExecuting: Boolean = false
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val toolCall: ToolCallInfo? = null,
    val isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val llamaEngine = LlamaEngine(application.applicationContext)

    val shizukuState: StateFlow<ShizukuState> = ShizukuManager.shizukuState
    val modelState: StateFlow<ModelState> = llamaEngine.modelState

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    init {
        viewModelScope.launch {
            llamaEngine.loadModel()
        }

        // Welcome message
        _messages.value = listOf(
            ChatMessage(
                sender = MessageSender.BOT,
                text = "Welcome to **DevicePilot AI**!\n\nI can execute privileged system operations via Shizuku and on-device Qwen2.5 intelligence. Try asking me to change brightness, toggle Wi-Fi, change volume, or list installed applications."
            )
        )
    }

    fun requestShizukuPermission() {
        ShizukuManager.requestPermission()
    }

    fun refreshShizukuStatus() {
        ShizukuManager.updateStatus()
    }

    fun sendMessage(userText: String) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty() || _isGenerating.value) return

        val userMessage = ChatMessage(
            sender = MessageSender.USER,
            text = trimmed
        )

        val botMessageId = UUID.randomUUID().toString()

        // 1. Direct tool match check for instant execution (<10ms)
        val directTool = ToolRouter.tryDirectToolMatch(trimmed)
        if (directTool != null) {
            val initialBotMessage = ChatMessage(
                id = botMessageId,
                sender = MessageSender.BOT,
                text = "Executing privileged tool: `${directTool.toolName}`...",
                isStreaming = false,
                toolCall = ToolCallInfo(
                    toolName = directTool.toolName,
                    args = directTool.args,
                    command = "Shizuku ADB API",
                    output = "Executing...",
                    isSuccess = true,
                    isExecuting = true
                )
            )

            _messages.value = _messages.value + userMessage + initialBotMessage

            viewModelScope.launch {
                val executed = ToolRouter.executeIfTool(directTool) as ParsedResponse.ToolCall
                val result = executed.executionResult
                val success = result?.isSuccess ?: false
                val outputText = result?.output ?: "Unknown error"
                val summary = if (success) {
                    "Action completed successfully:\n**${directTool.toolName}**"
                } else {
                    "Action failed:\n$outputText"
                }

                updateMessage(botMessageId) { msg ->
                    msg.copy(
                        text = summary,
                        toolCall = ToolCallInfo(
                            toolName = directTool.toolName,
                            args = directTool.args,
                            command = result?.command ?: "shizuku exec",
                            output = outputText,
                            isSuccess = success,
                            isExecuting = false
                        )
                    )
                }
            }
            return
        }

        // 2. General Conversation via LLM
        val initialBotMessage = ChatMessage(
            id = botMessageId,
            sender = MessageSender.BOT,
            text = "",
            isStreaming = true
        )

        // Only take the last 2 text messages to keep prompt minimal and fast (<30 tokens)
        val history = _messages.value
            .filter { it.text.isNotBlank() && !it.isStreaming && it.toolCall == null }
            .takeLast(2)
            .map { (if (it.sender == MessageSender.USER) "user" else "assistant") to it.text }

        _messages.value = _messages.value + userMessage + initialBotMessage
        _isGenerating.value = true

        viewModelScope.launch {
            val formattedPrompt = ToolRouter.formatPrompt(trimmed, history)
            val fullBuffer = StringBuilder()

            try {
                llamaEngine.generateStream(formattedPrompt).collect { token ->
                    fullBuffer.append(token)
                    updateMessage(botMessageId) { msg ->
                        msg.copy(text = fullBuffer.toString())
                    }
                }

                // Completed streaming, now parse output for tools
                val finalRaw = fullBuffer.toString()
                val parsed = ToolRouter.parseOutput(finalRaw)

                when (parsed) {
                    is ParsedResponse.ToolCall -> {
                        // Mark tool as executing
                        updateMessage(botMessageId) { msg ->
                            msg.copy(
                                text = "Executing privileged tool: `${parsed.toolName}`...",
                                isStreaming = false,
                                toolCall = ToolCallInfo(
                                    toolName = parsed.toolName,
                                    args = parsed.args,
                                    command = "Calling Shizuku ADB API",
                                    output = "Executing...",
                                    isSuccess = true,
                                    isExecuting = true
                                )
                            )
                        }

                        // Execute privileged tool call via Shizuku
                        val executed = ToolRouter.executeIfTool(parsed) as ParsedResponse.ToolCall
                        val result = executed.executionResult

                        updateMessage(botMessageId) { msg ->
                            val success = result?.isSuccess ?: false
                            val outputText = result?.output ?: "Unknown error"
                            val summary = if (success) {
                                "Action completed successfully:\n**${parsed.toolName}**"
                            } else {
                                "Action failed:\n$outputText"
                            }

                            msg.copy(
                                text = summary,
                                toolCall = ToolCallInfo(
                                    toolName = parsed.toolName,
                                    args = parsed.args,
                                    command = result?.command ?: "shizuku exec",
                                    output = outputText,
                                    isSuccess = success,
                                    isExecuting = false
                                )
                            )
                        }
                    }
                    is ParsedResponse.Text -> {
                        updateMessage(botMessageId) { msg ->
                            msg.copy(text = finalRaw, isStreaming = false)
                        }
                    }
                }
            } catch (t: Throwable) {
                updateMessage(botMessageId) { msg ->
                    msg.copy(
                        text = "Error processing request: ${t.message}",
                        isStreaming = false
                    )
                }
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == id) transform(msg) else msg
        }
    }

    fun clearChat() {
        _messages.value = listOf(
            ChatMessage(
                sender = MessageSender.BOT,
                text = "Chat cleared. Ready for your instructions!"
            )
        )
    }

    override fun onCleared() {
        super.onCleared()
        llamaEngine.release()
    }
}
