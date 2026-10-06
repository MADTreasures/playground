// JNI bridge for Fluency: translation with llama.cpp, Swiss German ASR with whisper.cpp.
//
// Design notes
// - One LlamaSession per loaded GGUF model. The model stays warm in memory; the KV cache of
//   sequence 0 is reused across calls: only the suffix of a prompt that differs from the tokens
//   already in the cache is evaluated (live partial translations share instruction + text prefix).
// - Generated text is streamed to Kotlin as complete UTF-8 byte sequences (never split inside a
//   code point), so the Kotlin side can decode each chunk independently.
// - Generation can be cancelled from any thread; the abort callback also interrupts a running
//   prompt evaluation.
// - The GPU backend (Adreno, OpenCL) is loaded on demand (nativeEnableGpu). A model is loaded
//   either completely on the GPU or completely on the CPU; a CPU model gets an explicit empty
//   device list so it never touches the OpenCL backend.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <vector>

#include <dirent.h>
#include <dlfcn.h>

#include "ggml-backend.h"
#include "llama.h"
#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define FLOG(prio, ...) __android_log_print(prio, "FluencyNative", __VA_ARGS__)
#define LOG_I(...) FLOG(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOG_W(...) FLOG(ANDROID_LOG_WARN, __VA_ARGS__)
#define LOG_E(...) FLOG(ANDROID_LOG_ERROR, __VA_ARGS__)
#else
#define LOG_I(...) do { std::fprintf(stderr, "[fluency] " __VA_ARGS__); std::fputc('\n', stderr); } while (0)
#define LOG_W(...) LOG_I(__VA_ARGS__)
#define LOG_E(...) LOG_I(__VA_ARGS__)
#endif

namespace {

// ------------------------------------------------------------------------------------------ utils

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

std::string bytes_to_string(JNIEnv * env, jbyteArray arr) {
    if (arr == nullptr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

jbyteArray string_to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr != nullptr && !s.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    }
    return arr;
}

std::string jstring_to_string(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    // Callers only pass ASCII here (paths are converted with toByteArray where it matters).
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Length of the longest prefix of `s` that ends on a UTF-8 code point boundary.
size_t utf8_complete_prefix(const std::string & s) {
    const size_t n = s.size();
    size_t i = n;
    // walk back at most 3 bytes to find the start of the last code point
    size_t back = 0;
    while (i > 0 && back < 4) {
        const auto c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) != 0x80) { // lead byte or ASCII
            size_t len = 1;
            if ((c & 0xE0) == 0xC0) len = 2;
            else if ((c & 0xF0) == 0xE0) len = 3;
            else if ((c & 0xF8) == 0xF0) len = 4;
            return (n - (i - 1) >= len) ? n : i - 1;
        }
        --i;
        ++back;
    }
    return n; // invalid sequence: emit as is, Kotlin replaces malformed input
}

void log_callback(ggml_log_level level, const char * text, void * /*user*/) {
    if (level < GGML_LOG_LEVEL_WARN) return;
#ifdef __ANDROID__
    __android_log_write(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "FluencyNative", text);
#else
    std::fputs(text, stderr);
#endif
}

// ------------------------------------------------------------------------------------------ backends

std::mutex g_backend_mutex;
bool g_backends_ready = false;
std::string g_backend_report;
bool g_gpu_tried = false;
std::string g_gpu_report;

// ggml's backend registry is a plain global list: loading a backend (exclusive) must not overlap
// with model/context creation in another thread, which iterates the list (shared).
std::shared_mutex g_registry_mutex;

#ifdef FLUENCY_BACKEND_DL
// Same selection rule as ggml_backend_load_best(): every libggml-cpu-*.so exports a score
// function that returns 0 if the CPU lacks a required feature, otherwise a rank.
std::string pick_best_cpu_variant(const std::string & dir, std::string & report) {
    DIR * d = opendir(dir.c_str());
    if (d == nullptr) return {};
    std::string best;
    int best_score = 0;
    while (dirent * e = readdir(d)) {
        const std::string name = e->d_name;
        if (name.rfind("libggml-cpu-", 0) != 0 || name.size() < 3 || name.substr(name.size() - 3) != ".so") continue;
        const std::string path = dir + "/" + name;
        void * h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (h == nullptr) continue;
        using score_fn = int (*)();
        auto fn = reinterpret_cast<score_fn>(dlsym(h, "ggml_backend_score"));
        const int score = fn ? fn() : 0;
        report += name.substr(12, name.size() - 15) + "=" + std::to_string(score) + " ";
        if (score > best_score) {
            best_score = score;
            best = path;
        }
        dlclose(h);
    }
    closedir(d);
    return best;
}
#endif

std::string device_list() {
    std::string out;
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (!out.empty()) out += "; ";
        out += std::string(ggml_backend_dev_name(dev)) + " (" + ggml_backend_dev_description(dev) + ")";
    }
    return out;
}

ggml_backend_dev_t find_gpu_device() {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(dev);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) return dev;
    }
    return nullptr;
}

std::string gpu_name() {
    ggml_backend_dev_t gpu = find_gpu_device();
    if (gpu == nullptr) return {};
    const char * desc = ggml_backend_dev_description(gpu);
    return desc != nullptr && *desc ? desc : ggml_backend_dev_name(gpu);
}

// ------------------------------------------------------------------------------------------ llama

enum StopReason : int64_t {
    STOP_EOG = 0,
    STOP_MAX_TOKENS = 1,
    STOP_NEWLINE = 2,
    STOP_CANCELLED = 3,
    STOP_SINK = 4,
    STOP_ERROR = -1,
    STOP_PROMPT_TOO_LONG = -2,
};

struct LlamaSession {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::vector<llama_token> cache; // tokens currently stored in the KV cache (sequence 0)
    std::atomic<bool> cancel{false};
    std::mutex mu;                  // one generation at a time per session
    int n_ctx = 0;
    int n_batch = 0;
    bool gpu = false;
};

bool abort_cb(void * data) {
    return static_cast<LlamaSession *>(data)->cancel.load(std::memory_order_relaxed);
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special, bool parse_special) {
    int n = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, add_special, parse_special);
    if (n <= 0) return {};
    std::vector<llama_token> out(static_cast<size_t>(n));
    const int got = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), out.data(), n, add_special, parse_special);
    if (got < 0) return {};
    out.resize(static_cast<size_t>(got));
    return out;
}

std::string token_piece(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
    if (n >= 0) return std::string(buf, static_cast<size_t>(n));
    std::string big(static_cast<size_t>(-n), '\0');
    n = llama_token_to_piece(vocab, tok, &big[0], -n, 0, false);
    if (n < 0) return {};
    big.resize(static_cast<size_t>(n));
    return big;
}

// Keeps the KV cache consistent with session->cache after an aborted/failed decode.
void trim_kv_to_cache(LlamaSession * s) {
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(s->cache.size()), -1)) {
        llama_memory_clear(mem, true);
        s->cache.clear();
    }
}

struct Sink {
    JNIEnv * env;
    jobject obj;
    jmethodID method;

    bool emit(const std::string & bytes) {
        if (obj == nullptr || bytes.empty()) return true;
        jbyteArray arr = string_to_bytes(env, bytes);
        const jboolean keep_going = env->CallBooleanMethod(obj, method, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) return false;
        return keep_going == JNI_TRUE;
    }
};

bool has_visible_text(const std::string & s) {
    for (char c : s) {
        if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return true;
    }
    return false;
}

} // namespace

// ================================================================================================
extern "C" {

// ---------------------------------------------------------------------------------- backends/info

JNIEXPORT jstring JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeInitBackends(JNIEnv * env, jobject, jstring jlibDir) {
    std::lock_guard<std::mutex> lock(g_backend_mutex);
    if (!g_backends_ready) {
        std::unique_lock<std::shared_mutex> registry(g_registry_mutex);
        llama_log_set(log_callback, nullptr);
        whisper_log_set(log_callback, nullptr);
        std::string report;
#ifdef FLUENCY_BACKEND_DL
        const std::string dir = jstring_to_string(env, jlibDir);
        const std::string cpu = pick_best_cpu_variant(dir, report);
        if (!cpu.empty()) {
            if (ggml_backend_load(cpu.c_str()) == nullptr) report += "| CPU load failed ";
            else report += "| CPU: " + cpu.substr(cpu.rfind('/') + 1) + " ";
        } else {
            report += "| no CPU variant found ";
        }
#else
        (void) jlibDir;
        report += "static backends ";
#endif
        llama_backend_init();
        report += "| devices: " + device_list();
        g_backend_report = report;
        g_backends_ready = true;
        LOG_I("backends: %s", report.c_str());
    }
    return env->NewStringUTF(g_backend_report.c_str());
}

JNIEXPORT jstring JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeSystemInfo(JNIEnv * env, jobject) {
    std::string info = llama_print_system_info();
    info += "\nllama.cpp " + std::string(llama_version());
    return env->NewStringUTF(info.c_str());
}

/**
 * Loads the OpenCL backend (once per process) and reports what happened. The Adreno kernels are
 * compiled when the first model is loaded onto the GPU; [jcacheDir] keeps the compiled binaries,
 * so that only the very first start pays for the compilation.
 */
JNIEXPORT jstring JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeEnableGpu(JNIEnv * env, jobject, jstring jlibDir,
                                                                    jstring jcacheDir) {
    std::lock_guard<std::mutex> lock(g_backend_mutex);
    if (!g_gpu_tried) {
        g_gpu_tried = true;
#ifdef FLUENCY_BACKEND_DL
        std::unique_lock<std::shared_mutex> registry(g_registry_mutex);
        const std::string cache = jstring_to_string(env, jcacheDir);
        if (!cache.empty()) setenv("GGML_OPENCL_KERNEL_CACHE_DIR", cache.c_str(), 1);
        const std::string ocl = jstring_to_string(env, jlibDir) + "/libggml-opencl.so";
        if (ggml_backend_load(ocl.c_str()) == nullptr) {
            g_gpu_report = "OpenCL-Backend nicht ladbar (kein libOpenCL.so?)";
        } else {
            const std::string name = gpu_name();
            g_gpu_report = name.empty() ? "OpenCL geladen, aber keine unterstützte GPU" : "GPU: " + name;
        }
#else
        (void) jlibDir;
        (void) jcacheDir;
        g_gpu_report = "dieser Build hat kein GPU-Backend";
#endif
        LOG_I("gpu: %s", g_gpu_report.c_str());
    }
    return env->NewStringUTF(g_gpu_report.c_str());
}

/** Name of the GPU device that models can be loaded onto, or null. */
JNIEXPORT jstring JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeGpuName(JNIEnv * env, jobject) {
    std::shared_lock<std::shared_mutex> registry(g_registry_mutex);
    const std::string name = gpu_name();
    return name.empty() ? nullptr : env->NewStringUTF(name.c_str());
}

// ----------------------------------------------------------------------------------- model/session

JNIEXPORT jlong JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeLoad(JNIEnv * env, jobject, jbyteArray jpath, jint nCtx,
                                                               jint nBatch, jint nThreads, jint nThreadsBatch,
                                                               jboolean useGpu) {
    const std::string path = bytes_to_string(env, jpath);
    std::shared_lock<std::shared_mutex> registry(g_registry_mutex);

    llama_model_params mp = llama_model_default_params();
    ggml_backend_dev_t gpu = useGpu ? find_gpu_device() : nullptr;
    if (useGpu && gpu == nullptr) LOG_W("no GPU device - loading %s on the CPU", path.c_str());
    // all layers on the GPU, or none: {nullptr} is an empty list (CPU only, OpenCL stays untouched)
    ggml_backend_dev_t devices[2] = {gpu, nullptr};
    mp.devices = devices;
    mp.n_gpu_layers = gpu != nullptr ? 999 : 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) {
        LOG_E("failed to load model %s", path.c_str());
        return 0;
    }

    auto * s = new LlamaSession();
    s->model = model;
    s->vocab = llama_model_get_vocab(model);
    s->gpu = gpu != nullptr;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(nCtx);
    cp.n_batch = static_cast<uint32_t>(nBatch);
    cp.n_ubatch = static_cast<uint32_t>(nBatch);
    cp.n_seq_max = 1;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreadsBatch;
    cp.swa_full = true;          // allows prefix reuse for sliding-window models (Gemma 3)
    cp.op_offload = s->gpu;
    cp.no_perf = true;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = s;

    s->ctx = llama_init_from_model(model, cp);
    if (s->ctx == nullptr) {
        LOG_E("failed to create context for %s", path.c_str());
        llama_model_free(model);
        delete s;
        return 0;
    }
    s->n_ctx = static_cast<int>(llama_n_ctx(s->ctx));
    s->n_batch = static_cast<int>(llama_n_batch(s->ctx));
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jboolean JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeUsesGpu(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<LlamaSession *>(handle);
    return s != nullptr && s->gpu ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<LlamaSession *>(handle);
    if (s == nullptr) return;
    s->cancel.store(true);
    {
        std::lock_guard<std::mutex> lock(s->mu); // wait for a running generation to finish
        if (s->ctx) llama_free(s->ctx);
        if (s->model) llama_model_free(s->model);
        s->ctx = nullptr;
        s->model = nullptr;
    }
    delete s;
}

JNIEXPORT void JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<LlamaSession *>(handle);
    if (s != nullptr) s->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeSetThreads(JNIEnv *, jobject, jlong handle, jint nThreads,
                                                                     jint nThreadsBatch) {
    auto * s = reinterpret_cast<LlamaSession *>(handle);
    if (s == nullptr) return;
    std::lock_guard<std::mutex> lock(s->mu);
    llama_set_n_threads(s->ctx, nThreads, nThreadsBatch);
}

JNIEXPORT jbyteArray JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeMeta(JNIEnv * env, jobject, jlong handle, jstring jkey) {
    auto * s = reinterpret_cast<LlamaSession *>(handle);
    if (s == nullptr) return nullptr;
    const std::string key = jstring_to_string(env, jkey);
    std::vector<char> buf(1 << 16);
    const int n = llama_model_meta_val_str(s->model, key.c_str(), buf.data(), buf.size());
    if (n < 0) return nullptr;
    return string_to_bytes(env, std::string(buf.data(), std::min(static_cast<size_t>(n), buf.size() - 1)));
}

JNIEXPORT jstring JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeDescribe(JNIEnv * env, jobject, jlong handle) {
    auto * s = reinterpret_cast<LlamaSession *>(handle);
    if (s == nullptr) return nullptr;
    char desc[256];
    llama_model_desc(s->model, desc, sizeof(desc));
    char line[512];
    std::snprintf(line, sizeof(line), "%s | %.2f B params | %.2f GiB | ctx %d | %s", desc,
                  llama_model_n_params(s->model) / 1e9, llama_model_size(s->model) / 1024.0 / 1024.0 / 1024.0,
                  s->n_ctx, s->gpu ? "GPU" : "CPU");
    return env->NewStringUTF(line);
}

JNIEXPORT jint JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeTokenCount(JNIEnv * env, jobject, jlong handle, jbyteArray jtext,
                                                                     jboolean parseSpecial) {
    auto * s = reinterpret_cast<LlamaSession *>(handle);
    if (s == nullptr) return -1;
    return static_cast<jint>(tokenize(s->vocab, bytes_to_string(env, jtext), false, parseSpecial == JNI_TRUE).size());
}

JNIEXPORT jbyteArray JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeApplyChatTemplate(JNIEnv * env, jobject, jlong handle,
                                                                            jbyteArray juser) {
    auto * s = reinterpret_cast<LlamaSession *>(handle);
    if (s == nullptr) return nullptr;
    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    if (tmpl == nullptr) return nullptr;
    const std::string user = bytes_to_string(env, juser);
    llama_chat_message msg{"user", user.c_str()};
    std::vector<char> buf(user.size() * 2 + 512);
    int n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), static_cast<int32_t>(buf.size()));
    if (n < 0) return nullptr; // template not supported by the built-in (non-jinja) formatter
    if (static_cast<size_t>(n) > buf.size()) {
        buf.resize(static_cast<size_t>(n));
        n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), static_cast<int32_t>(buf.size()));
        if (n < 0) return nullptr;
    }
    return string_to_bytes(env, std::string(buf.data(), static_cast<size_t>(n)));
}

JNIEXPORT void JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeResetCache(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<LlamaSession *>(handle);
    if (s == nullptr) return;
    std::lock_guard<std::mutex> lock(s->mu);
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->cache.clear();
}

/**
 * Runs one generation. Returns
 *   [promptTokens, reusedTokens, generatedTokens, prefillMicros, decodeMicros, stopReason]
 * The sink's `boolean onText(byte[])` receives complete UTF-8 chunks; returning false stops.
 */
JNIEXPORT jlongArray JNICALL
Java_ch_madtreasures_fluency_engine_llm_LlamaNative_nativeGenerate(JNIEnv * env, jobject, jlong handle, jbyteArray jprompt,
                                                                   jboolean addBos, jint maxTokens, jfloat repeatPenalty,
                                                                   jboolean stopAtNewline, jobject jsink) {
    int64_t stats[6] = {0, 0, 0, 0, 0, STOP_ERROR};
    auto finish = [&]() {
        jlongArray out = env->NewLongArray(6);
        env->SetLongArrayRegion(out, 0, 6, reinterpret_cast<const jlong *>(stats));
        return out;
    };

    auto * s = reinterpret_cast<LlamaSession *>(handle);
    if (s == nullptr) return finish();
    std::lock_guard<std::mutex> lock(s->mu);
    s->cancel.store(false);

    Sink sink{env, jsink, nullptr};
    if (jsink != nullptr) {
        jclass cls = env->GetObjectClass(jsink);
        sink.method = env->GetMethodID(cls, "onText", "([B)Z");
        env->DeleteLocalRef(cls);
        if (sink.method == nullptr) return finish();
    }

    const std::string prompt = bytes_to_string(env, jprompt);
    std::vector<llama_token> toks = tokenize(s->vocab, prompt, addBos == JNI_TRUE, true);
    stats[0] = static_cast<int64_t>(toks.size());
    if (toks.empty()) return finish();
    if (static_cast<int>(toks.size()) + 8 > s->n_ctx) {
        stats[5] = STOP_PROMPT_TOO_LONG;
        return finish();
    }
    const int max_new = std::max(1, std::min<int>(maxTokens, s->n_ctx - static_cast<int>(toks.size()) - 1));

    // ---- reuse the cached prefix
    size_t n_keep = 0;
    while (n_keep < s->cache.size() && n_keep < toks.size() && s->cache[n_keep] == toks[n_keep]) ++n_keep;
    if (n_keep == toks.size()) --n_keep; // the last prompt token must be evaluated to get logits
    if (n_keep < s->cache.size()) {
        if (!llama_memory_seq_rm(llama_get_memory(s->ctx), 0, static_cast<llama_pos>(n_keep), -1)) {
            llama_memory_clear(llama_get_memory(s->ctx), true);
            n_keep = 0;
        }
        s->cache.resize(n_keep);
    }
    stats[1] = static_cast<int64_t>(n_keep);

    // ---- prompt evaluation
    const int64_t t0 = now_us();
    llama_batch batch = llama_batch_init(s->n_batch, 0, 1);
    for (size_t i = n_keep; i < toks.size(); i += static_cast<size_t>(s->n_batch)) {
        const int n = static_cast<int>(std::min(static_cast<size_t>(s->n_batch), toks.size() - i));
        batch.n_tokens = n;
        for (int j = 0; j < n; ++j) {
            batch.token[j] = toks[i + j];
            batch.pos[j] = static_cast<llama_pos>(i + j);
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = (i + j == toks.size() - 1) ? 1 : 0;
        }
        const int rc = llama_decode(s->ctx, batch);
        if (rc != 0) {
            llama_batch_free(batch);
            trim_kv_to_cache(s);
            stats[3] = now_us() - t0;
            stats[5] = s->cancel.load() ? STOP_CANCELLED : STOP_ERROR;
            if (stats[5] == STOP_ERROR) LOG_E("llama_decode (prompt) failed: %d", rc);
            return finish();
        }
        s->cache.insert(s->cache.end(), toks.begin() + static_cast<long>(i), toks.begin() + static_cast<long>(i) + n);
    }
    llama_batch_free(batch);
    const int64_t t1 = now_us();
    stats[3] = t1 - t0;

    // ---- generation: greedy (+ optional repetition penalty over generated tokens only)
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (repeatPenalty > 1.0f) {
        // penalties are only applied to the top candidates (cheap, same result as penalised greedy)
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(s->vocab), 64, repeatPenalty, 0.0f, 0.0f));
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string pending;   // generated bytes not yet sent
    std::string sent;      // everything sent so far (for the newline rule)
    int64_t stop = STOP_MAX_TOKENS;
    int n_gen = 0;
    while (n_gen < max_new) {
        if (s->cancel.load()) { stop = STOP_CANCELLED; break; }
        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) { stop = STOP_EOG; break; }
        ++n_gen;
        if (!llama_vocab_is_control(s->vocab, tok)) pending += token_piece(s->vocab, tok);

        bool stop_now = false;
        if (stopAtNewline == JNI_TRUE) {
            size_t nl;
            while ((nl = pending.find('\n')) != std::string::npos) {
                if (has_visible_text(sent) || has_visible_text(pending.substr(0, nl))) {
                    pending.resize(nl);
                    stop_now = true;
                    break;
                }
                pending.erase(0, nl + 1); // ignore leading empty lines
            }
        }
        const size_t ready = stop_now ? pending.size() : utf8_complete_prefix(pending);
        if (ready > 0) {
            const std::string chunk = pending.substr(0, ready);
            pending.erase(0, ready);
            sent += chunk;
            if (!sink.emit(chunk)) { stop = STOP_SINK; break; }
        }
        if (stop_now) { stop = STOP_NEWLINE; break; }

        llama_batch one = llama_batch_get_one(&tok, 1);
        const int rc = llama_decode(s->ctx, one);
        if (rc != 0) {
            trim_kv_to_cache(s);
            stop = s->cancel.load() ? STOP_CANCELLED : STOP_ERROR;
            if (stop == STOP_ERROR) LOG_E("llama_decode (gen) failed: %d", rc);
            break;
        }
        s->cache.push_back(tok);
    }
    if (!pending.empty() && stop != STOP_SINK && stop != STOP_CANCELLED) sink.emit(pending);
    llama_sampler_free(smpl);

    stats[2] = n_gen;
    stats[4] = now_us() - t1;
    stats[5] = stop;
    return finish();
}

// ---------------------------------------------------------------------------------- whisper.cpp

struct WhisperSession {
    whisper_context * ctx = nullptr;
    std::atomic<bool> cancel{false};
    std::mutex mu;
};

static bool whisper_abort_cb(void * data) {
    return static_cast<WhisperSession *>(data)->cancel.load(std::memory_order_relaxed);
}

JNIEXPORT jlong JNICALL
Java_ch_madtreasures_fluency_engine_asr_WhisperCppNative_nativeInit(JNIEnv * env, jobject, jbyteArray jpath, jboolean useGpu) {
    const std::string path = bytes_to_string(env, jpath);
    std::shared_lock<std::shared_mutex> registry(g_registry_mutex);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = useGpu == JNI_TRUE;
    cp.flash_attn = true;
    whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), cp);
    if (ctx == nullptr) {
        LOG_E("whisper: failed to load %s", path.c_str());
        return 0;
    }
    auto * s = new WhisperSession();
    s->ctx = ctx;
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_ch_madtreasures_fluency_engine_asr_WhisperCppNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<WhisperSession *>(handle);
    if (s == nullptr) return;
    s->cancel.store(true);
    {
        std::lock_guard<std::mutex> lock(s->mu);
        whisper_free(s->ctx);
        s->ctx = nullptr;
    }
    delete s;
}

JNIEXPORT void JNICALL
Java_ch_madtreasures_fluency_engine_asr_WhisperCppNative_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<WhisperSession *>(handle);
    if (s != nullptr) s->cancel.store(true);
}

/** Transcribes 16 kHz mono float PCM. Returns UTF-8 text or null on error. */
JNIEXPORT jbyteArray JNICALL
Java_ch_madtreasures_fluency_engine_asr_WhisperCppNative_nativeTranscribe(JNIEnv * env, jobject, jlong handle,
                                                                         jfloatArray jpcm, jstring jlang, jint nThreads,
                                                                         jint audioCtx) {
    auto * s = reinterpret_cast<WhisperSession *>(handle);
    if (s == nullptr || jpcm == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(s->mu);
    s->cancel.store(false);

    const jsize n = env->GetArrayLength(jpcm);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());
    const std::string lang = jstring_to_string(env, jlang);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = nThreads;
    p.language = lang.empty() ? "auto" : lang.c_str();
    p.detect_language = false;
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = true;
    p.print_special = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.temperature_inc = 0.0f; // no fallback re-decoding: latency over robustness
    // Whisper always encodes a 30 s window (1500 frames); a smaller context for short utterances
    // makes the encoder several times faster. 0 = full window.
    p.audio_ctx = audioCtx;
    p.abort_callback = whisper_abort_cb;
    p.abort_callback_user_data = s;

    if (whisper_full(s->ctx, p, pcm.data(), static_cast<int>(pcm.size())) != 0) return nullptr;
    std::string text;
    const int segs = whisper_full_n_segments(s->ctx);
    for (int i = 0; i < segs; ++i) text += whisper_full_get_segment_text(s->ctx, i);
    return string_to_bytes(env, text);
}

} // extern "C"
