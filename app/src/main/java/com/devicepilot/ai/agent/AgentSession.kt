package com.devicepilot.ai.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.devicepilot.ai.llm.LlamaEngine
import com.devicepilot.ai.llm.ModelState
import com.devicepilot.ai.model.DownloadState
import com.devicepilot.ai.model.ModelManager
import com.devicepilot.ai.model.ModelSpec
import com.devicepilot.ai.service.AgentForegroundService
import com.devicepilot.ai.shizuku.ShizukuManager
import com.devicepilot.ai.shizuku.ShizukuShell
import com.devicepilot.ai.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID

enum class Sender { USER, AGENT, SYSTEM }

/** Everything the chat UI renders. */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: Sender,
    val text: String = "",
    val steps: List<AgentStep> = emptyList(),
    val thinking: String? = null,
    val isActive: Boolean = false,
    val pendingConfirmation: AgentStep? = null,
    val pendingQuestion: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Process-wide agent state. Lives in the Application so the agent keeps running
 * while the user (or the agent itself) switches to other apps.
 */
class AgentSession(private val app: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val engine = LlamaEngine()
    val models = ModelManager(app)
    val registry = ToolRegistry(app)
    private val runner by lazy { AgentRunner(engine, registry, autoConfirm = { _autoConfirm.value }) }

    private val _messages = MutableStateFlow(listOf(welcome()))
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _autoConfirm = MutableStateFlow(false)
    val autoConfirm: StateFlow<Boolean> = _autoConfirm.asStateFlow()

    val modelState: StateFlow<ModelState> = engine.modelState

    private var taskJob: Job? = null
    private var activeMessageId: String? = null
    private var awaitingAnswer = false

    init {
        // Mirror download progress into the model state so the UI has one source of truth.
        models.download.onEach { d ->
            if (d is DownloadState.Downloading) {
                engine.setState(ModelState.Downloading(models.selected.value, d.fraction, d.downloaded, d.total))
            }
        }.launchIn(scope)
        scope.launch { prepareModel(models.selected.value) }
    }

    // ---------------------------------------------------------------- models

    fun selectModel(spec: ModelSpec) {
        if (isRunning.value) return
        models.select(spec)
        scope.launch { prepareModel(spec) }
    }

    fun retryModel() = scope.launch { prepareModel(models.selected.value) }

    fun deleteModel(spec: ModelSpec) {
        if (engine.loadedSpec?.id == spec.id) engine.release()
        models.delete(spec)
    }

    private suspend fun prepareModel(spec: ModelSpec) {
        try {
            engine.setState(ModelState.Downloading(spec, 0f, 0, spec.approxBytes))
            val file = models.ensureDownloaded(spec)
            engine.load(spec, file)
            warmUp()
        } catch (t: Throwable) {
            Log.e(TAG, "prepareModel failed", t)
            engine.setState(ModelState.Error(spec, t.message ?: "Download failed"))
        }
    }

    fun setAutoConfirm(v: Boolean) { _autoConfirm.value = v }

    @Volatile private var cachedHint: String? = null

    /** Prefill the (constant) system prompt so the first user message doesn't pay for it. */
    private suspend fun warmUp() {
        if (!engine.isReady) return
        val hint = deviceHint().also { cachedHint = it }
        val prefix = AgentPrompt.build(runner.systemPrompt(hint), emptyList()).substringBefore("<|im_start|>assistant")
        engine.warmUp(prefix)
        Log.i(TAG, "Prompt warm-up complete")
    }

    // ---------------------------------------------------------------- chat

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return

        if (awaitingAnswer) {
            awaitingAnswer = false
            append(ChatMessage(sender = Sender.USER, text = t))
            activeMessageId?.let { id -> update(id) { it.copy(pendingQuestion = null) } }
            runner.answer(t)
            return
        }
        if (_isRunning.value) return

        append(ChatMessage(sender = Sender.USER, text = t))
        val agentMsg = ChatMessage(sender = Sender.AGENT, isActive = true, thinking = "")
        append(agentMsg)
        activeMessageId = agentMsg.id
        _isRunning.value = true
        startService()

        taskJob = scope.launch {
            val hint = cachedHint ?: deviceHint().also { cachedHint = it }
            val userText = if (ShizukuManager.isReady()) t else "$t\n(Note: Shizuku is NOT connected, device tools will fail. If a device action is needed, reply telling the user to start Shizuku and grant permission.)"
            runner.run(userText, hint).collect { ev -> handle(agentMsg.id, ev) }
        }.also { job ->
            job.invokeOnCompletion {
                _isRunning.value = false
                awaitingAnswer = false
                update(agentMsg.id) { m -> m.copy(isActive = false, thinking = null, pendingConfirmation = null, pendingQuestion = null) }
                stopService()
            }
        }
    }

    fun confirm(approved: Boolean) {
        activeMessageId?.let { id -> update(id) { it.copy(pendingConfirmation = null) } }
        runner.confirm(approved)
    }

    fun stop() {
        runner.cancel()
        taskJob?.cancel()
    }

    fun clearChat() {
        if (isRunning.value) stop()
        runner.clearHistory()
        _messages.value = listOf(welcome())
    }

    private fun handle(msgId: String, ev: AgentEvent) {
        when (ev) {
            is AgentEvent.Thinking -> update(msgId) { it.copy(thinking = ev.partialText) }
            is AgentEvent.StepStarted -> update(msgId) { m ->
                m.copy(thinking = "", steps = m.steps.filter { it.index != ev.step.index } + ev.step, pendingConfirmation = null)
            }
            is AgentEvent.StepUpdated -> update(msgId) { m ->
                val exists = m.steps.any { it.index == ev.step.index }
                m.copy(steps = if (exists) m.steps.map { if (it.index == ev.step.index) ev.step else it } else m.steps + ev.step)
            }
            is AgentEvent.NeedsConfirmation -> update(msgId) { m -> m.copy(thinking = null, pendingConfirmation = ev.step) }
            is AgentEvent.AskUser -> { awaitingAnswer = true; update(msgId) { it.copy(thinking = null, pendingQuestion = ev.question) } }
            is AgentEvent.Reply -> update(msgId) { it.copy(text = ev.text, thinking = null) }
            is AgentEvent.Done -> update(msgId) { it.copy(text = ev.summary, thinking = null) }
            is AgentEvent.Failed -> update(msgId) { it.copy(text = "⚠ ${ev.reason}", thinking = null) }
            AgentEvent.Cancelled -> update(msgId) { it.copy(text = it.text.ifBlank { "Stopped." }, thinking = null) }
        }
        AgentForegroundService.updateStatus(app, currentStatusLine(msgId))
    }

    private fun currentStatusLine(msgId: String): String {
        val m = _messages.value.firstOrNull { it.id == msgId } ?: return "Working…"
        m.pendingConfirmation?.let { return "Waiting for confirmation: ${it.summary}" }
        m.pendingQuestion?.let { return "Waiting for your answer" }
        m.steps.lastOrNull()?.let { return "Step ${it.index + 1}: ${it.summary}" }
        return "Thinking…"
    }

    private suspend fun deviceHint(): String {
        // Must be stable across the session so the prefilled system prompt stays cache-valid.
        val r = ShizukuShell.run("echo \"Android $(getprop ro.build.version.release), $(getprop ro.product.manufacturer) $(getprop ro.product.model)\"")
        return r.stdout.trim().ifBlank { "Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" }
    }

    private fun startService() {
        try {
            val i = Intent(app, AgentForegroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        } catch (t: Throwable) { Log.w(TAG, "startService failed: ${t.message}") }
    }

    private fun stopService() {
        try { app.stopService(Intent(app, AgentForegroundService::class.java)) } catch (_: Throwable) { }
    }

    private fun append(m: ChatMessage) { _messages.value = _messages.value + m }
    private fun update(id: String, f: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == id) f(it) else it }
    }

    private fun welcome() = ChatMessage(
        sender = Sender.AGENT,
        text = "I'm DevicePilot — an on-device agent that can see your screen, tap, type, open apps, and change system settings through Shizuku.\n\nTry: \"Turn on dark mode and set brightness to 40%\", \"Open Telegram and search for Ali\", or \"Which apps have camera permission?\""
    )

    companion object { private const val TAG = "AgentSession" }
}
