package com.devicepilot.ai.llm

import android.util.Log
import com.devicepilot.ai.model.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed class ModelState {
    data object Unloaded : ModelState()
    data class Downloading(val spec: ModelSpec, val fraction: Float, val downloadedBytes: Long, val totalBytes: Long) : ModelState()
    data class Loading(val spec: ModelSpec) : ModelState()
    data class Loaded(val spec: ModelSpec, val fileBytes: Long) : ModelState()
    data class Error(val spec: ModelSpec?, val message: String) : ModelState()
}

/**
 * Thin Kotlin wrapper over the llama.cpp JNI bridge.
 * A single model is resident at a time; [load] swaps models.
 */
class LlamaEngine {

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Unloaded)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private var nativeHandle: Long = 0L
    private val nativeLibLoaded: Boolean
    private val loadMutex = Mutex()
    @Volatile var loadedSpec: ModelSpec? = null
        private set

    init {
        nativeLibLoaded = try {
            System.loadLibrary("devicepilot-jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "devicepilot-jni not available: ${e.message}")
            false
        }
    }

    val isReady: Boolean get() = nativeLibLoaded && nativeHandle != 0L

    fun setState(state: ModelState) {
        _modelState.value = state
    }

    suspend fun load(spec: ModelSpec, file: File) = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            if (!nativeLibLoaded) {
                _modelState.value = ModelState.Error(spec, "Native inference library failed to load. Build the app with the llama.cpp submodule.")
                return@withLock
            }
            if (loadedSpec?.id == spec.id && nativeHandle != 0L) {
                _modelState.value = ModelState.Loaded(spec, file.length())
                return@withLock
            }
            _modelState.value = ModelState.Loading(spec)
            releaseInternal()
            try {
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                val handle = nativeInitModel(file.absolutePath, threads, spec.contextSize)
                if (handle == 0L) {
                    _modelState.value = ModelState.Error(spec, "Failed to initialize ${spec.displayName}. The file may be corrupt — try re-downloading.")
                    return@withLock
                }
                nativeHandle = handle
                loadedSpec = spec
                _modelState.value = ModelState.Loaded(spec, file.length())
                Log.i(TAG, "Loaded ${spec.displayName} from ${file.absolutePath}")
            } catch (t: Throwable) {
                Log.e(TAG, "Native init error", t)
                _modelState.value = ModelState.Error(spec, t.message ?: "Native init error")
            }
        }
    }

    /**
     * Streams generated text. Completes when generation ends or [cancel] is called.
     */
    fun generateStream(prompt: String, maxTokens: Int = 384, temperature: Float = 0.2f, grammar: String? = null): Flow<String> = channelFlow {
        withContext(Dispatchers.IO) {
            if (!isReady) {
                trySend("Error: no model loaded.")
                return@withContext
            }
            try {
                val result = nativeGenerate(nativeHandle, prompt, maxTokens, temperature, grammar) { token -> trySend(token) }
                if (result.startsWith("Error:")) trySend(result)
            } catch (t: Throwable) {
                Log.e(TAG, "Generation error", t)
                trySend("Error: ${t.message}")
            }
        }
    }.buffer(Channel.UNLIMITED)

    /** Non-streaming convenience for the agent loop. */
    suspend fun generate(prompt: String, maxTokens: Int = 384, temperature: Float = 0.2f, grammar: String? = null, onToken: ((String) -> Unit)? = null): String =
        withContext(Dispatchers.IO) {
            if (!isReady) return@withContext "Error: no model loaded."
            nativeGenerate(nativeHandle, prompt, maxTokens, temperature, grammar) { onToken?.invoke(it) }
        }

    /** Prefills [prompt] into the KV cache without generating, so the next call that shares this prefix is fast. */
    suspend fun warmUp(prompt: String) = withContext(Dispatchers.IO) {
        if (!isReady) return@withContext
        try { nativeGenerate(nativeHandle, prompt, 0, 0f, null) { } } catch (t: Throwable) { Log.w(TAG, "warmUp failed: ${t.message}") }
    }

    fun cancel() {
        if (isReady) nativeCancel(nativeHandle)
    }

    fun resetCache() {
        if (isReady) nativeResetCache(nativeHandle)
    }

    private fun releaseInternal() {
        if (nativeLibLoaded && nativeHandle != 0L) {
            nativeFreeModel(nativeHandle)
            nativeHandle = 0L
            loadedSpec = null
        }
    }

    fun release() {
        releaseInternal()
        _modelState.value = ModelState.Unloaded
    }

    private external fun nativeInitModel(modelPath: String, nThreads: Int, nCtx: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, grammar: String?, callback: TokenCallback): String
    private external fun nativeCancel(handle: Long)
    private external fun nativeResetCache(handle: Long)
    private external fun nativeFreeModel(handle: Long)

    fun interface TokenCallback {
        fun onToken(token: String)
    }

    companion object {
        private const val TAG = "LlamaEngine"
    }
}
