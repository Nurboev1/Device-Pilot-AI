#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <thread>
#include <chrono>
#include <sstream>
#include <fstream>
#include <cstring>
#include <mutex>
#include <algorithm>
#include "llama.h"

#define LOG_TAG "DevicePilot-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

static void jni_llama_log_callback(ggml_log_level level, const char * text, void * user_data) {
    (void)user_data;
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, "llama.cpp", "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, "llama.cpp", "%s", text);
    } else if (level == GGML_LOG_LEVEL_INFO) {
        __android_log_print(ANDROID_LOG_INFO, "llama.cpp", "%s", text);
    }
}

struct LlamaEngineState {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string model_path;
    int n_threads = 4;
    int n_ctx = 2048;
    bool is_initialized = false;
    uint64_t model_size_bytes = 0;
    std::mutex eval_mutex;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_devicepilot_ai_llm_LlamaEngine_nativeInitModel(
        JNIEnv *env,
        jobject /* thiz */,
        jstring j_model_path,
        jint n_threads,
        jint n_ctx) {
    if (j_model_path == nullptr) {
        LOGE("nativeInitModel: model_path is null");
        return 0;
    }

    const char *model_path_cstr = env->GetStringUTFChars(j_model_path, nullptr);
    std::string model_path(model_path_cstr);
    env->ReleaseStringUTFChars(j_model_path, model_path_cstr);

    int actual_threads = n_threads > 0 ? n_threads : 4;
    int actual_ctx = n_ctx > 0 ? n_ctx : 2048;

    LOGI("Initializing model from: %s with %d threads, %d context window",
         model_path.c_str(), actual_threads, actual_ctx);

    std::ifstream file(model_path, std::ios::binary | std::ios::ate);
    uint64_t file_size = 0;
    if (file.is_open()) {
        file_size = static_cast<uint64_t>(file.tellg());
        file.close();
        LOGI("Found valid model file, size: %llu bytes", (unsigned long long)file_size);
    } else {
        LOGW("Model file not found or inaccessible at path: %s", model_path.c_str());
        return 0;
    }

    // Set up logging to logcat
    llama_log_set(jni_llama_log_callback, nullptr);

    // Initialize llama backend
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    llama_model * model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!model) {
        LOGE("Failed to load llama model from file: %s", model_path.c_str());
        return 0;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    if (!vocab) {
        LOGE("Failed to retrieve vocab from llama model");
        llama_model_free(model);
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(actual_ctx);
    ctx_params.n_batch = 512;
    ctx_params.n_ubatch = 512;
    ctx_params.n_threads = actual_threads > 0 ? actual_threads : 4;
    ctx_params.n_threads_batch = actual_threads > 0 ? actual_threads : 4;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    llama_context * ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to initialize llama_context from model");
        llama_model_free(model);
        return 0;
    }

    auto *state = new LlamaEngineState();
    state->model = model;
    state->ctx = ctx;
    state->vocab = vocab;
    state->model_path = model_path;
    state->n_threads = actual_threads;
    state->n_ctx = actual_ctx;
    state->is_initialized = true;
    state->model_size_bytes = file_size;

    LOGI("Successfully initialized real llama.cpp model from %s", model_path.c_str());
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT jstring JNICALL
Java_com_devicepilot_ai_llm_LlamaEngine_nativeGenerate(
        JNIEnv *env,
        jobject /* thiz */,
        jlong handle,
        jstring j_prompt,
        jobject callback) {
    auto *state = reinterpret_cast<LlamaEngineState *>(handle);
    if (!state || !state->is_initialized || !state->model || !state->ctx || !state->vocab) {
        LOGE("nativeGenerate: Invalid or uninitialized handle");
        return env->NewStringUTF("Error: LlamaEngine handle is not initialized.");
    }

    if (j_prompt == nullptr) {
        return env->NewStringUTF("");
    }

    const char *prompt_cstr = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(j_prompt, prompt_cstr);

    // Setup callback method reference
    jclass callback_class = nullptr;
    jmethodID on_token_method = nullptr;
    if (callback != nullptr) {
        callback_class = env->GetObjectClass(callback);
        if (callback_class != nullptr) {
            on_token_method = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
        }
    }

    auto emit_token = [&](const std::string &token_str) {
        if (callback != nullptr && on_token_method != nullptr) {
            jstring j_token = env->NewStringUTF(token_str.c_str());
            env->CallVoidMethod(callback, on_token_method, j_token);
            env->DeleteLocalRef(j_token);
        }
    };

    std::lock_guard<std::mutex> lock(state->eval_mutex);

    // Tokenize prompt
    const llama_vocab * vocab = state->vocab;
    std::vector<llama_token> prompt_tokens(prompt.length() + 64);
    int n_prompt = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.length()),
                                  prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()),
                                  true, true);
    if (n_prompt < 0) {
        prompt_tokens.resize(-n_prompt);
        n_prompt = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.length()),
                                  prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()),
                                  true, true);
    }
    if (n_prompt <= 0) {
        LOGE("Failed to tokenize prompt");
        return env->NewStringUTF("Error: Failed to tokenize prompt.");
    }
    prompt_tokens.resize(n_prompt);

    LOGI("Processing prompt with %d tokens", n_prompt);

    // Clear KV cache / memory for a fresh turn
    llama_memory_t mem = llama_get_memory(state->ctx);
    if (mem) {
        llama_memory_clear(mem, true);
    }

    // Initialize sampler chain
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("Decoding prompt tokens...");
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()));
    if (llama_decode(state->ctx, batch) != 0) {
        LOGE("llama_decode failed on prompt");
        llama_sampler_free(smpl);
        return env->NewStringUTF("Error: Failed during prompt decode.");
    }
    LOGI("Prompt decoded successfully. Starting generation...");

    std::string full_response;
    int max_gen_tokens = 256;

    for (int step = 0; step < max_gen_tokens; ++step) {
        llama_token new_token_id = llama_sampler_sample(smpl, state->ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token_id)) {
            LOGI("End of generation reached at step %d", step);
            break;
        }

        char piece_buf[256];
        int n_chars = llama_token_to_piece(vocab, new_token_id, piece_buf, sizeof(piece_buf), 0, false);
        if (n_chars > 0) {
            std::string piece(piece_buf, n_chars);
            emit_token(piece);
            full_response += piece;
        }

        // Decode sampled token
        batch = llama_batch_get_one(&new_token_id, 1);
        if (llama_decode(state->ctx, batch) != 0) {
            LOGE("llama_decode failed at step %d", step);
            break;
        }
    }

    llama_sampler_free(smpl);
    LOGI("Generation finished. Generated %zu chars", full_response.length());
    return env->NewStringUTF(full_response.c_str());
}

JNIEXPORT void JNICALL
Java_com_devicepilot_ai_llm_LlamaEngine_nativeFreeModel(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jlong handle) {
    auto *state = reinterpret_cast<LlamaEngineState *>(handle);
    if (state != nullptr) {
        LOGI("Freeing LlamaEngine state");
        std::lock_guard<std::mutex> lock(state->eval_mutex);
        if (state->ctx) {
            llama_free(state->ctx);
            state->ctx = nullptr;
        }
        if (state->model) {
            llama_model_free(state->model);
            state->model = nullptr;
        }
        delete state;
    }
    llama_backend_free();
}

} // extern "C"
