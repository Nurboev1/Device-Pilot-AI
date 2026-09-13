package com.devicepilot.ai.agent

import android.util.Log
import com.devicepilot.ai.llm.LlamaEngine
import com.devicepilot.ai.model.PromptStyle
import com.devicepilot.ai.tools.Risk
import com.devicepilot.ai.tools.ToolArgs
import com.devicepilot.ai.tools.ToolRegistry
import com.devicepilot.ai.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject

/**
 * The ReAct loop:  prompt → model → parse tool call → (confirm?) → execute → feed result → repeat.
 *
 * Confirmation and ask_user are modelled as suspension points: the runner waits on a
 * CompletableDeferred that the UI completes.
 */
class AgentRunner(
    private val engine: LlamaEngine,
    private val registry: ToolRegistry,
    private val config: Config = Config(),
    private val autoConfirm: () -> Boolean = { false }
) {
    data class Config(
        val maxSteps: Int = 25,
        val maxTokensPerStep: Int = 320,
        val temperature: Float = 0.15f,
        val maxConsecutiveParseFailures: Int = 3
    )

    /** Conversation memory across tasks (user turns, replies, done summaries). */
    private val history = mutableListOf<Turn>()

    private val hermesGrammar: String by lazy { ToolGrammar.build(registry) }
    private val hammerGrammar: String by lazy { HammerPrompt.grammar(registry) }

    private fun style(): PromptStyle = engine.loadedSpec?.promptStyle ?: PromptStyle.QWEN_HERMES

    /** Constant prefix of every prompt (system + tools); prefilled once so the first step is fast. */
    fun promptPrefix(deviceHint: String): String = when (style()) {
        PromptStyle.HAMMER -> HammerPrompt.prefix(registry, deviceHint)
        PromptStyle.QWEN_HERMES -> AgentPrompt.build(AgentPrompt.system(registry, deviceHint), emptyList()).substringBefore("<|im_start|>assistant")
    }

    private fun buildPrompt(prefix: String, turns: List<Turn>): String = when (style()) {
        PromptStyle.HAMMER -> HammerPrompt.build(prefix, turns)
        PromptStyle.QWEN_HERMES -> prefix + AgentPrompt.build("", turns)
    }

    private fun grammar(): String = if (style() == PromptStyle.HAMMER) hammerGrammar else hermesGrammar

    @Volatile private var pendingConfirmation: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingAnswer: CompletableDeferred<String>? = null

    fun confirm(approved: Boolean) { pendingConfirmation?.complete(approved) }
    fun answer(text: String) { pendingAnswer?.complete(text) }
    fun cancel() {
        engine.cancel()
        pendingConfirmation?.complete(false)
        pendingAnswer?.cancel()
    }
    fun clearHistory() { history.clear(); engine.resetCache() }

    fun run(userText: String, deviceHint: String): Flow<AgentEvent> = channelFlow {
        val prefix = promptPrefix(deviceHint)
        history += Turn.User(userText)
        // Keep memory bounded: only keep the last few tasks' textual turns + this task's turns.
        trimHistory()

        val taskTurns = mutableListOf<Turn>()
        var parseFailures = 0
        var stepIndex = 0
        val recentCalls = ArrayDeque<String>()

        try {
            while (stepIndex < config.maxSteps) {
                currentCoroutineContext().ensureActive()
                val prompt = buildPrompt(prefix, history + taskTurns)

                // --- Think --------------------------------------------------------
                val buf = StringBuilder()
                val raw = withContext(Dispatchers.IO) {
                    engine.generate(prompt, config.maxTokensPerStep, config.temperature, grammar()) { tok ->
                        buf.append(tok)
                        trySend(AgentEvent.Thinking(buf.toString()))
                    }
                }
                if (raw.startsWith("Error:")) {
                    send(AgentEvent.Failed(raw.removePrefix("Error:").trim()))
                    return@channelFlow
                }

                val call = ToolCallParser.parse(raw)
                if (call == null && raw.replace(Regex("[`\\s]"), "") == "[]") {
                    // Hammer "no function applies" signal.
                    val msg = "I don't have a tool for that. Could you rephrase or ask for something else?"
                    history += Turn.Text(msg)
                    send(AgentEvent.Reply(msg))
                    return@channelFlow
                }
                if (call == null) {
                    // Model answered in plain text. Treat as a reply if it looks like prose and
                    // no tool has been used yet; otherwise nudge it back into the format.
                    val prose = raw.trim()
                    if (prose.isNotBlank() && taskTurns.none { it is Turn.ToolOutput } && !prose.contains("{")) {
                        history += Turn.Text(prose)
                        send(AgentEvent.Reply(prose))
                        return@channelFlow
                    }
                    parseFailures++
                    if (parseFailures >= config.maxConsecutiveParseFailures) {
                        val fallback = prose.ifBlank { "I couldn't produce a valid action." }
                        history += Turn.Text(fallback)
                        send(AgentEvent.Reply(fallback))
                        return@channelFlow
                    }
                    taskTurns += Turn.Text(prose.take(300))
                    taskTurns += Turn.ToolOutput("system", "Invalid format. Respond with exactly one <tool_call>{\"name\":..., \"arguments\":{...}}</tool_call>.")
                    continue
                }
                parseFailures = 0

                val tool = registry[call.name]
                if (tool == null) {
                    taskTurns += Turn.Assistant(call.thought, call.normalizedJson)
                    taskTurns += Turn.ToolOutput(call.name, "Unknown tool '${call.name}'. Use only tools from the catalog.")
                    stepIndex++
                    continue
                }
                val args = ToolArgs(call.arguments)

                // --- Control tools --------------------------------------------------
                when (tool.name) {
                    "done" -> {
                        val summary = args.string("summary") ?: "Task complete."
                        history += Turn.Text(summary)
                        send(AgentEvent.Done(summary, stepIndex))
                        return@channelFlow
                    }
                    "reply" -> {
                        val msg = args.string("message") ?: raw.trim()
                        history += Turn.Text(msg)
                        send(AgentEvent.Reply(msg))
                        return@channelFlow
                    }
                    "ask_user" -> {
                        val q = args.string("question") ?: "Could you clarify?"
                        taskTurns += Turn.Assistant(call.thought, call.normalizedJson)
                        send(AgentEvent.AskUser(q))
                        val deferred = CompletableDeferred<String>()
                        pendingAnswer = deferred
                        val reply = try { deferred.await() } finally { pendingAnswer = null }
                        taskTurns += Turn.ToolOutput("ask_user", "User answered: $reply")
                        stepIndex++
                        continue
                    }
                }

                // --- Loop detection -------------------------------------------------
                val sig = call.normalizedJson
                recentCalls.addLast(sig); if (recentCalls.size > 6) recentCalls.removeFirst()
                if (recentCalls.count { it == sig } >= 3 && tool.name != "get_screen" && tool.name != "wait") {
                    taskTurns += Turn.Assistant(call.thought, call.normalizedJson)
                    taskTurns += Turn.ToolOutput(tool.name, "You have repeated this exact call 3 times without progress. Try a different approach, or call ask_user / done.")
                    stepIndex++
                    continue
                }

                // --- Confirmation gate ---------------------------------------------
                var step = AgentStep(
                    index = stepIndex, toolName = tool.name, argsJson = args.toString(),
                    summary = runCatching { tool.summarize(args) }.getOrDefault(tool.name),
                    risk = tool.risk, thought = call.thought, status = StepStatus.EXECUTING
                )
                if (tool.risk == Risk.CONFIRM && !autoConfirm()) {
                    step = step.copy(status = StepStatus.AWAITING_CONFIRMATION)
                    send(AgentEvent.NeedsConfirmation(step))
                    val deferred = CompletableDeferred<Boolean>()
                    pendingConfirmation = deferred
                    val approved = try { deferred.await() } finally { pendingConfirmation = null }
                    if (!approved) {
                        step = step.copy(status = StepStatus.DENIED, output = "User declined this action.")
                        send(AgentEvent.StepUpdated(step))
                        taskTurns += Turn.Assistant(call.thought, call.normalizedJson)
                        taskTurns += Turn.ToolOutput(tool.name, "The user DECLINED this action. Do not retry it. Ask what to do instead or call done.")
                        stepIndex++
                        continue
                    }
                    step = step.copy(status = StepStatus.EXECUTING)
                }
                send(AgentEvent.StepStarted(step))

                // --- Execute -------------------------------------------------------
                val t0 = System.currentTimeMillis()
                val result: ToolResult = try {
                    tool.execute(args)
                } catch (e: IllegalArgumentException) {
                    ToolResult.fail("Bad arguments: ${e.message}. Required: ${tool.params.filter { it.required }.joinToString { it.name + ":" + it.type }}")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Tool ${tool.name} crashed", t)
                    ToolResult.fail("Tool error: ${t.message}")
                }
                step = step.copy(
                    status = if (result.success) StepStatus.SUCCESS else StepStatus.FAILED,
                    output = result.output, command = result.command, artifactPath = result.artifactPath,
                    durationMs = System.currentTimeMillis() - t0
                )
                send(AgentEvent.StepUpdated(step))

                taskTurns += Turn.Assistant(call.thought, call.normalizedJson)
                taskTurns += Turn.ToolOutput(tool.name, (if (result.success) "" else "ERROR: ") + result.output)
                stepIndex++
            }
            send(AgentEvent.Failed("Reached the step limit (${config.maxSteps}) without finishing. Try breaking the task into smaller parts."))
        } catch (e: CancellationException) {
            send(AgentEvent.Cancelled)
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Agent loop crashed", t)
            val cause = (t as? ExceptionInInitializerError)?.cause ?: t
            send(AgentEvent.Failed("${cause::class.java.simpleName}: ${cause.message ?: "no message"}"))
        }
    }.buffer(Channel.UNLIMITED)

    private fun trimHistory() {
        // Keep the last 6 textual turns of prior conversation to preserve context cheaply.
        while (history.size > 7) history.removeAt(0)
    }

    companion object { private const val TAG = "AgentRunner" }
}
