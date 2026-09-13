package com.devicepilot.ai.model

/**
 * Models the user can switch between. Both are Qwen2.5-Instruct GGUFs (ChatML +
 * Hermes-style <tool_call> format) so the agent prompt is identical for each.
 */
enum class PromptStyle {
    /** Qwen2.5-Instruct: ChatML + Hermes <tool_call> */
    QWEN_HERMES,
    /** Hammer 2.1: task/format instruction blocks, JSON tool list, JSON array output */
    HAMMER
}

data class ModelSpec(
    val id: String,
    val promptStyle: PromptStyle,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    /** Approximate size, used for progress display before Content-Length is known. */
    val approxBytes: Long,
    /** Recommended minimum device RAM. */
    val minRamGb: Int,
    val contextSize: Int,
    val description: String
)

object ModelCatalog {

    val HAMMER_0_5B = ModelSpec(
        id = "hammer2.1-0.5b",
        promptStyle = PromptStyle.HAMMER,
        displayName = "Hammer 2.1 0.5B (function calling)",
        fileName = "hammer2.1-0.5b-q6_k.gguf",
        downloadUrl = "https://huggingface.co/Nekuromento/Hammer2.1-0.5b-Q6_K-GGUF/resolve/main/hammer2.1-0.5b-q6_k.gguf",
        approxBytes = 505_472_512L,
        minRamGb = 3,
        contextSize = 4096,
        description = "Recommended default. Fine-tuned specifically for tool calling on-device; far more reliable than a general 0.5B chat model."
    )

    val HAMMER_1_5B = ModelSpec(
        id = "hammer2.1-1.5b",
        promptStyle = PromptStyle.HAMMER,
        displayName = "Hammer 2.1 1.5B (function calling)",
        fileName = "hammer2.1-1.5b-q5_k_m.gguf",
        downloadUrl = "https://huggingface.co/Nekuromento/Hammer2.1-1.5b-Q5_K_M-GGUF/resolve/main/hammer2.1-1.5b-q5_k_m.gguf",
        approxBytes = 1_124_702_912L,
        minRamGb = 6,
        contextSize = 4096,
        description = "Best quality. Tool-calling specialist with multi-step planning; use for UI automation tasks."
    )

    val QWEN_0_5B = ModelSpec(
        id = "qwen2.5-0.5b",
        promptStyle = PromptStyle.QWEN_HERMES,
        displayName = "Qwen2.5 0.5B Instruct",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        approxBytes = 491_000_000L,
        minRamGb = 3,
        contextSize = 4096,
        description = "General chat model. Weak at choosing tools — mostly for comparison."
    )

    val QWEN_1_5B = ModelSpec(
        id = "qwen2.5-1.5b",
        promptStyle = PromptStyle.QWEN_HERMES,
        displayName = "Qwen2.5 1.5B Instruct",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        approxBytes = 1_120_000_000L,
        minRamGb = 6,
        contextSize = 4096,
        description = "General chat model with decent tool use and better conversation."
    )

    val all: List<ModelSpec> = listOf(HAMMER_0_5B, HAMMER_1_5B, QWEN_0_5B, QWEN_1_5B)

    val default: ModelSpec = HAMMER_0_5B

    fun byId(id: String?): ModelSpec = all.firstOrNull { it.id == id } ?: default
}
