#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <fstream>
#include <cstring>
#include <mutex>
#include <atomic>
#include <algorithm>
#include "llama.h"

#define LOG_TAG "DevicePilot-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

static void jni_llama_log_callback(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, "llama.cpp", "%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, "llama.cpp", "%s", text);
    }
}

struct LlamaEngineState {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string model_path;
    int n_threads = 4;
    int n_ctx = 4096;
    int n_batch = 512;
    uint64_t model_size_bytes = 0;
    std::mutex eval_mutex;
    std::atomic<bool> cancel_requested{false};

    // Tokens currently resident in the KV cache (sequence 0). Used for prefix reuse:
    // the agent loop re-sends the whole conversation each step, so only the new
    // suffix has to be prefilled.
    std::vector<llama_token> cached_tokens;
};

static std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    std::vector<llama_token> tokens(text.length() + 64);
    int n = llama_tokenize(vocab, text.c_str(), (int32_t) text.length(),
                           tokens.data(), (int32_t) tokens.size(), add_special, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), (int32_t) text.length(),
                           tokens.data(), (int32_t) tokens.size(), add_special, true);
    }
    if (n < 0) n = 0;
    tokens.resize(n);
    return tokens;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_devicepilot_ai_llm_LlamaEngine_nativeInitModel(
        JNIEnv *env, jobject, jstring j_model_path, jint n_threads, jint n_ctx) {
    if (j_model_path == nullptr) return 0;

    const char *cpath = env->GetStringUTFChars(j_model_path, nullptr);
    std::string model_path(cpath);
    env->ReleaseStringUTFChars(j_model_path, cpath);

    int actual_threads = n_threads > 0 ? n_threads : 4;
    int actual_ctx = n_ctx > 0 ? n_ctx : 4096;

    std::ifstream file(model_path, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        LOGW("Model file not found: %s", model_path.c_str());
        return 0;
    }
    uint64_t file_size = (uint64_t) file.tellg();
    file.close();

    llama_log_set(jni_llama_log_callback, nullptr);
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    model_params.use_mmap = true;

    llama_model *model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!model) {
        LOGE("Failed to load model: %s", model_path.c_str());
        return 0;
    }
    const llama_vocab *vocab = llama_model_get_vocab(model);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = (uint32_t) actual_ctx;
    ctx_params.n_batch = 512;
    ctx_params.n_ubatch = 512;
    ctx_params.n_threads = actual_threads;
    ctx_params.n_threads_batch = actual_threads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create llama_context");
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
    state->n_batch = 512;
    state->model_size_bytes = file_size;

    LOGI("Model loaded: %s (ctx=%d, threads=%d)", model_path.c_str(), actual_ctx, actual_threads);
    return reinterpret_cast<jlong>(state);
}

/**
 * Generates a completion for `prompt`.
 *  - Reuses the KV cache for the longest common token prefix with the previous call.
 *  - Streams pieces through callback.onToken(String).
 *  - Stops on EOG, max_tokens, or when nativeCancel() is called from another thread.
 * Returns the full generated text, or a string starting with "Error:".
 */
JNIEXPORT jstring JNICALL
Java_com_devicepilot_ai_llm_LlamaEngine_nativeGenerate(
        JNIEnv *env, jobject, jlong handle, jstring j_prompt,
        jint max_tokens, jfloat temperature, jobject callback) {
    auto *state = reinterpret_cast<LlamaEngineState *>(handle);
    if (!state || !state->ctx) return env->NewStringUTF("Error: engine not initialized.");
    if (j_prompt == nullptr) return env->NewStringUTF("");

    const char *cprompt = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt(cprompt);
    env->ReleaseStringUTFChars(j_prompt, cprompt);

    jmethodID on_token = nullptr;
    if (callback) {
        jclass cls = env->GetObjectClass(callback);
        if (cls) on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    }
    auto emit = [&](const std::string &s) {
        if (callback && on_token) {
            jstring js = env->NewStringUTF(s.c_str());
            env->CallVoidMethod(callback, on_token, js);
            env->DeleteLocalRef(js);
        }
    };

    std::lock_guard<std::mutex> lock(state->eval_mutex);
    state->cancel_requested = false;

    const llama_vocab *vocab = state->vocab;
    std::vector<llama_token> prompt_tokens = tokenize(vocab, prompt, true);
    const int n_prompt = (int) prompt_tokens.size();
    if (n_prompt == 0) return env->NewStringUTF("Error: empty prompt after tokenization.");

    int gen_budget = max_tokens > 0 ? max_tokens : 256;
    if (n_prompt + gen_budget > state->n_ctx) {
        gen_budget = state->n_ctx - n_prompt;
        if (gen_budget < 16) {
            LOGE("Prompt too long: %d tokens (ctx %d)", n_prompt, state->n_ctx);
            return env->NewStringUTF("Error: context window exceeded. Prompt is too long.");
        }
    }

    // --- Prefix cache reuse -------------------------------------------------
    llama_memory_t mem = llama_get_memory(state->ctx);
    int n_common = 0;
    const int n_cached = (int) state->cached_tokens.size();
    while (n_common < n_cached && n_common < n_prompt &&
           state->cached_tokens[n_common] == prompt_tokens[n_common]) {
        n_common++;
    }
    // We must decode at least one token to get logits for sampling.
    if (n_common == n_prompt) n_common = n_prompt - 1;

    if (n_common > 0) {
        if (!llama_memory_seq_rm(mem, 0, n_common, -1)) {
            LOGW("seq_rm failed, clearing cache");
            llama_memory_clear(mem, true);
            n_common = 0;
        }
    } else {
        llama_memory_clear(mem, true);
    }
    LOGI("Prompt %d tokens, reusing %d cached, prefilling %d", n_prompt, n_common, n_prompt - n_common);

    // --- Prefill (chunked by n_batch) ---------------------------------------
    for (int i = n_common; i < n_prompt; i += state->n_batch) {
        if (state->cancel_requested) {
            state->cached_tokens.assign(prompt_tokens.begin(), prompt_tokens.begin() + i);
            return env->NewStringUTF("");
        }
        int n = std::min(state->n_batch, n_prompt - i);
        llama_batch batch = llama_batch_get_one(prompt_tokens.data() + i, n);
        if (llama_decode(state->ctx, batch) != 0) {
            LOGE("llama_decode failed during prefill at %d", i);
            state->cached_tokens.clear();
            llama_memory_clear(mem, true);
            return env->NewStringUTF("Error: decode failed during prompt processing.");
        }
    }
    state->cached_tokens = prompt_tokens;

    // --- Sampler --------------------------------------------------------------
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));
    float temp = temperature;
    if (temp <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // --- Generation ---------------------------------------------------------
    std::string full_response;
    std::string pending_utf8; // holds incomplete multibyte sequences between pieces
    for (int step = 0; step < gen_budget; ++step) {
        if (state->cancel_requested) break;

        llama_token tok = llama_sampler_sample(smpl, state->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (n > 0) {
            pending_utf8.append(buf, n);
            // Emit only complete UTF-8 sequences so NewStringUTF never sees a torn char.
            size_t valid = pending_utf8.size();
            for (size_t k = pending_utf8.size(); k > 0 && k > pending_utf8.size() - 4; --k) {
                unsigned char c = (unsigned char) pending_utf8[k - 1];
                if ((c & 0xC0) == 0x80) continue;
                size_t need = (c >= 0xF0) ? 4 : (c >= 0xE0) ? 3 : (c >= 0xC0) ? 2 : 1;
                if (k - 1 + need > pending_utf8.size()) valid = k - 1;
                break;
            }
            if (valid > 0) {
                std::string piece = pending_utf8.substr(0, valid);
                pending_utf8.erase(0, valid);
                full_response += piece;
                emit(piece);
            }
        }

        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(state->ctx, batch) != 0) {
            LOGE("llama_decode failed at generation step %d", step);
            break;
        }
        state->cached_tokens.push_back(tok);
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(full_response.c_str());
}

JNIEXPORT void JNICALL
Java_com_devicepilot_ai_llm_LlamaEngine_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto *state = reinterpret_cast<LlamaEngineState *>(handle);
    if (state) state->cancel_requested = true;
}

JNIEXPORT void JNICALL
Java_com_devicepilot_ai_llm_LlamaEngine_nativeResetCache(JNIEnv *, jobject, jlong handle) {
    auto *state = reinterpret_cast<LlamaEngineState *>(handle);
    if (!state || !state->ctx) return;
    std::lock_guard<std::mutex> lock(state->eval_mutex);
    llama_memory_clear(llama_get_memory(state->ctx), true);
    state->cached_tokens.clear();
}

JNIEXPORT void JNICALL
Java_com_devicepilot_ai_llm_LlamaEngine_nativeFreeModel(JNIEnv *, jobject, jlong handle) {
    auto *state = reinterpret_cast<LlamaEngineState *>(handle);
    if (!state) return;
    state->cancel_requested = true;
    {
        std::lock_guard<std::mutex> lock(state->eval_mutex);
        if (state->ctx) { llama_free(state->ctx); state->ctx = nullptr; }
        if (state->model) { llama_model_free(state->model); state->model = nullptr; }
    }
    delete state;
}

} // extern "C"
