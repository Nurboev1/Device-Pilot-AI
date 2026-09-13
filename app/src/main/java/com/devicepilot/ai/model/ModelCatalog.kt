package com.devicepilot.ai.model

/**
 * Models the user can switch between. Both are Qwen2.5-Instruct GGUFs (ChatML +
 * Hermes-style <tool_call> format) so the agent prompt is identical for each.
 */
data class ModelSpec(
    val id: String,
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

    val QWEN_0_5B = ModelSpec(
        id = "qwen2.5-0.5b",
        displayName = "Qwen2.5 0.5B Instruct",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        approxBytes = 491_000_000L,
        minRamGb = 3,
        contextSize = 4096,
        description = "Fastest. Fine for single-step commands; weaker at long multi-step tasks."
    )

    val QWEN_1_5B = ModelSpec(
        id = "qwen2.5-1.5b",
        displayName = "Qwen2.5 1.5B Instruct",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        approxBytes = 1_120_000_000L,
        minRamGb = 6,
        contextSize = 4096,
        description = "Recommended. Noticeably better planning and tool-call accuracy."
    )

    val all: List<ModelSpec> = listOf(QWEN_0_5B, QWEN_1_5B)

    val default: ModelSpec = QWEN_0_5B

    fun byId(id: String?): ModelSpec = all.firstOrNull { it.id == id } ?: default
}
