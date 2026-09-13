package com.devicepilot.ai.llm

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed class ModelState {
    data object Unloaded : ModelState()
    data object Loading : ModelState()
    data class Loaded(val modelPath: String, val memoryBytes: Long) : ModelState()
    data class FallbackMode(val message: String) : ModelState()
}

class LlamaEngine(private val context: Context) {

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Unloaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private var nativeHandle: Long = 0L
    private var isNativeLibLoaded = false

    init {
        try {
            System.loadLibrary("devicepilot-jni")
            isNativeLibLoaded = true
            Log.i(TAG, "Successfully loaded native library: devicepilot-jni")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "devicepilot-jni not loaded: ${e.message}. Using fallback engine.")
            isNativeLibLoaded = false
        }
    }

    /**
     * Initializes the model from local device storage or assets
     */
    suspend fun loadModel() = withContext(Dispatchers.IO) {
        _modelState.value = ModelState.Loading

        val extDir = context.getExternalFilesDir(null)
        val targetInExt = File(extDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf")

        val candidatePaths = listOf(
            targetInExt,
            File("/storage/emulated/0/Android/data/com.devicepilot.ai/files/qwen2.5-0.5b-instruct-q4_k_m.gguf"),
            File(context.filesDir, "models/qwen2.5-0.5b-instruct-q4_k_m.gguf"),
            File(context.filesDir, "qwen2.5-0.5b-instruct-q4_k_m.gguf"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "qwen2.5-0.5b-instruct-q4_k_m.gguf"),
            File("/sdcard/Download/qwen2.5-0.5b-instruct-q4_k_m.gguf"),
            File("/storage/emulated/0/Download/qwen2.5-0.5b-instruct-q4_k_m.gguf")
        )

        var foundFile = candidatePaths.firstOrNull { it.exists() && it.canRead() }

        // If not directly accessible via normal file permissions, try copying from Download
        if (foundFile == null) {
            val downloadFiles = listOf(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "qwen2.5-0.5b-instruct-q4_k_m.gguf"),
                File("/sdcard/Download/qwen2.5-0.5b-instruct-q4_k_m.gguf"),
                File("/storage/emulated/0/Download/qwen2.5-0.5b-instruct-q4_k_m.gguf")
            )
            for (df in downloadFiles) {
                if (df.exists() && df.canRead()) {
                    try {
                        Log.i(TAG, "Copying model from ${df.absolutePath} to ${targetInExt.absolutePath}")
                        df.copyTo(targetInExt, overwrite = true)
                        if (targetInExt.exists()) {
                            foundFile = targetInExt
                            break
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed copying model: ${e.message}")
                    }
                }
            }
        }

        if (foundFile != null && isNativeLibLoaded) {
            try {
                val handle = nativeInitModel(foundFile.absolutePath, nThreads = 4, nCtx = 2048)
                if (handle != 0L) {
                    nativeHandle = handle
                    val fileSize = foundFile.length()
                    _modelState.value = ModelState.Loaded(foundFile.name, fileSize)
                    Log.i(TAG, "Model loaded successfully from ${foundFile.absolutePath}")
                    return@withContext
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Native init error: ${t.message}", t)
            }
        }

        // Fallback mode if GGUF file is not yet placed on device
        if (isNativeLibLoaded) {
            val handle = nativeInitModel("fallback_virtual_model", 4, 1024)
            nativeHandle = handle
            _modelState.value = ModelState.FallbackMode("Virtual Qwen2.5 Engine (GGUF not found)")
        } else {
            _modelState.value = ModelState.FallbackMode("Rule & Pattern Engine")
        }
    }

    /**
     * Streams generated tokens from native inference loop
     */
    fun generateStream(prompt: String): Flow<String> = channelFlow {
        withContext(Dispatchers.IO) {
            if (isNativeLibLoaded && nativeHandle != 0L) {
                try {
                    nativeGenerate(nativeHandle, prompt) { token ->
                        trySend(token)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Native generate error: ${e.message}", e)
                    trySend("Error generating response: ${e.message}")
                }
            } else {
                val simulatedTokens = simulateTokens(prompt)
                for (t in simulatedTokens) {
                    trySend(t)
                    kotlinx.coroutines.delay(20)
                }
            }
        }
    }.buffer(Channel.UNLIMITED)

    private fun simulateTokens(prompt: String): List<String> {
        val userPrompt = if ("<|im_start|>user\n" in prompt) {
            prompt.substringAfterLast("<|im_start|>user\n").substringBefore("<|im_end|>")
        } else {
            prompt
        }
        val lower = userPrompt.lowercase().trim()
        return when {
            "brightness" in lower || "ravshan" in lower || "yorug" in lower -> {
                listOf("{\n", "  \"tool\": ", "\"set_screen_brightness\",\n", "  \"args\": {\"level\": 180}\n", "}")
            }
            "volume" in lower || "ovoz" in lower -> {
                listOf("{\n", "  \"tool\": ", "\"set_media_volume\",\n", "  \"args\": {\"level\": 10}\n", "}")
            }
            "wifi" in lower || "wi-fi" in lower -> {
                val state = "off" !in lower && "disable" !in lower && "ochir" !in lower
                listOf("{\n", "  \"tool\": ", "\"toggle_wifi\",\n", "  \"args\": {\"state\": $state}\n", "}")
            }
            "uninstall" in lower || "remove" in lower -> {
                listOf("{\n", "  \"tool\": ", "\"silent_uninstall_app\",\n", "  \"args\": {\"packageName\": \"com.dummy.app\"}\n", "}")
            }
            "list" in lower || "apps" in lower || "ilova" in lower -> {
                listOf("{\n", "  \"tool\": ", "\"list_installed_apps\",\n", "  \"args\": {\"filter\": \"third_party\"}\n", "}")
            }
            lower.contains("who are you") || lower.contains("kim bu") || lower.contains("kimsan") -> {
                listOf("Men ", "DevicePilot ", "AI — ", "qurilmangizni ", "boshqaruvchi ", "mahalliy ", "sun'iy ", "intellektman.")
            }
            lower.contains("hi") || lower.contains("salom") || lower.contains("hello") -> {
                listOf("Salom! ", "Qurilmangiz ", "sozlamalarini ", "o'zgartirish ", "uchun ", "buyruq ", "bering.")
            }
            else -> {
                "Tushundim. Men qurilmangizning ravshanligi, Wi-Fi holati, ovoz balandligi va ilovalarni boshqara olaman. Biror amalni bajartirmoqchimisiz?"
                    .split(" ")
                    .map { "$it " }
            }
        }
    }

    fun release() {
        if (isNativeLibLoaded && nativeHandle != 0L) {
            nativeFreeModel(nativeHandle)
            nativeHandle = 0L
        }
        _modelState.value = ModelState.Unloaded
    }

    // JNI Declarations
    private external fun nativeInitModel(modelPath: String, nThreads: Int, nCtx: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, callback: TokenCallback): String
    private external fun nativeFreeModel(handle: Long)

    fun interface TokenCallback {
        fun onToken(token: String)
    }

    companion object {
        private const val TAG = "LlamaEngine"
    }
}
