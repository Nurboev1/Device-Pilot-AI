#ifndef DEVICEPILOT_LLAMA_H
#define DEVICEPILOT_LLAMA_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef int32_t llama_pos;
typedef int32_t llama_token;
typedef int32_t llama_seq_id;

struct llama_model;
struct llama_context;

struct llama_model_params {
    int32_t n_gpu_layers;
    int32_t main_gpu;
    const float * tensor_split;
    bool vocab_only;
    bool use_mmap;
    bool use_mlock;
    bool check_tensors;
};

struct llama_context_params {
    uint32_t seed;
    uint32_t n_ctx;
    uint32_t n_batch;
    uint32_t n_ubatch;
    uint32_t n_seq_max;
    int32_t  n_threads;
    int32_t  n_threads_batch;
    bool     embeddings;
    bool     offload_kqv;
    bool     flash_attn;
};

#ifdef __cplusplus
}
#endif

#endif // DEVICEPILOT_LLAMA_H
