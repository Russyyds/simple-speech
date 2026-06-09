#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#if defined(SIMPLE_SPEECH_WITH_LLAMA)
#include "llama.h"
#include "ggml-backend.h"
#endif

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr const char * kTag = "SimpleSpeechNative";
constexpr int kDefaultMaxPredictTokens = 64;
constexpr int kMaxAllowedPredictTokens = 4096;
constexpr uint32_t kCpuBatchTokens = 512;
constexpr uint32_t kGpuBatchTokens = 1024;
constexpr int kRepeatPenaltyLastN = 64;
constexpr float kRepeatPenalty = 1.15f;
constexpr int kSamplingTopK = 20;
constexpr float kSamplingTopP = 0.8f;
constexpr float kSamplingTemp = 0.7f;
constexpr uint32_t kSamplingSeed = 1;

std::string to_string(JNIEnv *env, jstring value) {
    if (!value) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring to_jstring(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

std::string lower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

bool contains_ci(const std::string &value, const std::string &needle) {
    return lower(value).find(lower(needle)) != std::string::npos;
}

std::vector<std::string> model_dir_candidates(const std::string &model) {
    if (model == "Hy-MT1.5-1.8B-1.25bit") {
        return {"Hy-MT1.5-1.8B-1.25bit", "Hy-MT1.5-1.8B-1.25bit-GGUF"};
    }
    if (model == "Hy-MT1.5-1.8B-4bit") {
        return {"Hy-MT1.5-1.8B-4bit", "HY-MT1.5-1.8B-GGUF"};
    }
    if (model == "Hy-MT1.5-1.8B-8bit") {
        return {"Hy-MT1.5-1.8B-8bit", "HY-MT1.5-1.8B-GGUF"};
    }
    if (model == "Hy-MT1.5-1.8B-2bit") {
        return {"Hy-MT1.5-1.8B-2bit", "Hy-MT1.5-1.8B-2bit-GGUF"};
    }
    return {model};
}

bool file_matches_model(const std::filesystem::path &path, const std::string &model) {
    const std::string name = path.filename().string();
    if (model == "Hy-MT1.5-1.8B-4bit") {
        return contains_ci(name, "4bit") || contains_ci(name, "q4");
    }
    if (model == "Hy-MT1.5-1.8B-8bit") {
        return contains_ci(name, "8bit") || contains_ci(name, "q8");
    }
    if (model == "Hy-MT1.5-1.8B-2bit") {
        return contains_ci(name, "2bit");
    }
    if (model == "Hy-MT1.5-1.8B-1.25bit") {
        return contains_ci(name, "1.25bit") || contains_ci(name, "stq");
    }
    return true;
}

std::filesystem::path model_path(const std::string &root, const std::string &model) {
    for (const std::string &candidate : model_dir_candidates(model)) {
        std::filesystem::path dir = std::filesystem::path(root) / candidate;
        if (!std::filesystem::exists(dir)) {
            continue;
        }

        std::filesystem::path fallback;
        for (const auto &entry : std::filesystem::directory_iterator(dir)) {
            if (!entry.is_regular_file() || entry.path().extension() != ".gguf") {
                continue;
            }
            if (fallback.empty()) {
                fallback = entry.path();
            }
            if (file_matches_model(entry.path(), model)) {
                return entry.path();
            }
        }

        if (!fallback.empty() && model != "Hy-MT1.5-1.8B-4bit" && model != "Hy-MT1.5-1.8B-8bit") {
            return fallback;
        }
    }
    return {};
}

#if defined(SIMPLE_SPEECH_WITH_LLAMA)

struct LlamaEngine {
    std::string path;
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler * sampler = nullptr;
    int n_threads = 4;
    int n_ctx = 8192;
    int n_gpu_layers = 0;

    ~LlamaEngine() {
        if (sampler) {
            llama_sampler_free(sampler);
        }
        if (ctx) {
            llama_free(ctx);
        }
        if (model) {
            llama_model_free(model);
        }
    }
};

std::mutex g_mutex;
std::unique_ptr<LlamaEngine> g_engine;
bool g_backend_ready = false;
bool g_cpu_backend_ready = false;
bool g_opencl_backend_ready = false;
void * g_opencl_runtime = nullptr;

bool preload_opencl_runtime(const std::string &backend_dir, std::string &error) {
    if (g_opencl_runtime) {
        return true;
    }
    dlerror();
    g_opencl_runtime = dlopen("libOpenCL.so", RTLD_NOW | RTLD_GLOBAL);
    if (g_opencl_runtime) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "loaded OpenCL runtime by soname libOpenCL.so");
        return true;
    }

    const char * soname_error = dlerror();
    const std::vector<std::string> paths = {
            "/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so"
    };
    std::ostringstream failures;
    failures << "libOpenCL.so";
    if (soname_error) {
        failures << " (" << soname_error << ")";
    }
    failures << "; ";
    for (const std::string &path : paths) {
        dlerror();
        g_opencl_runtime = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (g_opencl_runtime) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "loaded OpenCL runtime from %s", path.c_str());
            return true;
        }
        const char * dl_error = dlerror();
        failures << path;
        if (dl_error) {
            failures << " (" << dl_error << ")";
        }
        failures << "; ";
    }
    (void) backend_dir;
    error = "failed to load OpenCL runtime: " + failures.str();
    return false;
}

void configure_opencl_runtime() {
#if defined(SIMPLE_SPEECH_WITH_OPENCL)
    setenv("GGML_OPENCL_ADRENO_XMEM_GEMM", "1", 0);
    setenv("GGML_OPENCL_ADRENO_USE_LARGE_BUFFER", "1", 0);
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "configured OpenCL env: ADRENO_XMEM_GEMM=%s, ADRENO_USE_LARGE_BUFFER=%s",
                        getenv("GGML_OPENCL_ADRENO_XMEM_GEMM") ? "on" : "off",
                        getenv("GGML_OPENCL_ADRENO_USE_LARGE_BUFFER") ? "on" : "off");
#else
    (void) 0;
#endif
}

bool load_backend_library(const std::string &backend_dir, const char *name, std::string &error) {
    if (backend_dir.empty()) {
        error = std::string("backend library directory is empty, cannot load ") + name;
        return false;
    }
    const std::filesystem::path path = std::filesystem::path(backend_dir) /
            (std::string("libggml-") + name + ".so");
    if (!std::filesystem::exists(path)) {
        error = "backend library not found: " + path.string();
        return false;
    }
    if (std::string(name) == "opencl") {
        configure_opencl_runtime();
        if (!preload_opencl_runtime(backend_dir, error)) {
            return false;
        }
    }
    ggml_backend_reg_t reg = ggml_backend_load(path.string().c_str());
    if (!reg) {
        error = "failed to load backend library: " + path.string();
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "loaded backend %s from %s", name, path.string().c_str());
    return true;
}

bool ensure_backends(const std::string &backend_dir, bool need_opencl, std::string &error) {
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }
#if defined(SIMPLE_SPEECH_WITH_OPENCL)
    if (!g_cpu_backend_ready) {
        if (!load_backend_library(backend_dir, "cpu", error)) {
            return false;
        }
        g_cpu_backend_ready = true;
    }
    if (need_opencl && !g_opencl_backend_ready) {
        if (!load_backend_library(backend_dir, "opencl", error)) {
            return false;
        }
        g_opencl_backend_ready = true;
    }
#else
    (void) backend_dir;
    (void) need_opencl;
    ggml_backend_load_all();
#endif
    return true;
}

int choose_threads() {
    const unsigned hw = std::thread::hardware_concurrency();
    if (hw <= 2) {
        return 2;
    }
    return static_cast<int>(std::min<unsigned>(8, hw - 1));
}

std::string token_to_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> piece(32);
    int n = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
    if (n < 0) {
        piece.resize(static_cast<size_t>(-n));
        n = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, true);
    }
    if (n <= 0) {
        return {};
    }
    return {piece.data(), static_cast<size_t>(n)};
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string &prompt) {
    int n_prompt = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                   nullptr, 0, true, true);
    __android_log_print(ANDROID_LOG_INFO, kTag, "tokenize n_prompt:%d", n_prompt);
    if (n_prompt <= 0) {
        return {};
    }
    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt));
    int n = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                           tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (n < 0) {
        return {};
    }
    tokens.resize(static_cast<size_t>(n));
    return tokens;
}

std::string build_prompt(const std::string &source, const std::string &target, const std::string &input) {
    (void) source;
    std::ostringstream user;
    user << "Translate the following segment into " << target
         << ", without additional explanation.\n\n"
         << input;

    std::ostringstream prompt;
    prompt << "<\xEF\xBD\x9Chy_begin\xE2\x96\x81of\xE2\x96\x81sentence\xEF\xBD\x9C>"
           << "<\xEF\xBD\x9Chy_User\xEF\xBD\x9C>"
           << user.str()
           << "<\xEF\xBD\x9Chy_Assistant\xEF\xBD\x9C>";
    return prompt.str();
}

bool has_repeated_tail(const std::vector<llama_token> &tokens, int ngram, int repeats) {
    const int total = ngram * repeats;
    if (ngram <= 0 || repeats <= 1 || static_cast<int>(tokens.size()) < total) {
        return false;
    }
    const int start = static_cast<int>(tokens.size()) - total;
    for (int r = 1; r < repeats; ++r) {
        for (int i = 0; i < ngram; ++i) {
            if (tokens[start + i] != tokens[start + r * ngram + i]) {
                return false;
            }
        }
    }
    return true;
}

double tokens_per_second(int32_t n_tokens, double ms) {
    return n_tokens > 0 && ms > 0.0 ? n_tokens * 1000.0 / ms : 0.0;
}

LlamaEngine * ensure_engine(const std::string &path, const std::string &backend_dir, bool use_gpu, std::string &error) {
    const bool can_use_gpu = use_gpu && !contains_ci(path, "1.25bit");
    if (!ensure_backends(backend_dir, can_use_gpu, error)) {
        return nullptr;
    }
    int gpu_layers = 0;
#if defined(SIMPLE_SPEECH_WITH_OPENCL)
    gpu_layers = can_use_gpu ? 99 : 0;
#else
    (void) use_gpu;
#endif
    if (g_engine && g_engine->path == path && g_engine->n_gpu_layers == gpu_layers) {
        return g_engine.get();
    }

    auto next = std::make_unique<LlamaEngine>();
    next->path = path;
    next->n_threads = choose_threads();
    next->n_gpu_layers = gpu_layers;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = gpu_layers;

    const auto load_start = std::chrono::steady_clock::now();
    __android_log_print(ANDROID_LOG_INFO, kTag, "loading model %s, ngl=%d", path.c_str(), gpu_layers);
    next->model = llama_model_load_from_file(path.c_str(), mparams);
    if (!next->model) {
        error = "llama.cpp 加载模型失败: " + path;
        return nullptr;
    }

    next->vocab = llama_model_get_vocab(next->model);
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = next->n_ctx;
    const uint32_t batch_tokens = gpu_layers > 0 ? kGpuBatchTokens : kCpuBatchTokens;
    cparams.n_batch = batch_tokens;
    cparams.n_ubatch = batch_tokens;
    cparams.n_threads = next->n_threads;
    cparams.n_threads_batch = next->n_threads;
    cparams.no_perf = false;
    next->ctx = llama_init_from_model(next->model, cparams);
    if (!next->ctx) {
        error = "llama."
                "cpp 创建 context 失败";
        return nullptr;
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = false;
    next->sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(next->sampler, llama_sampler_init_top_k(kSamplingTopK));
    llama_sampler_chain_add(next->sampler, llama_sampler_init_top_p(kSamplingTopP, 1));
    llama_sampler_chain_add(next->sampler, llama_sampler_init_penalties(
            kRepeatPenaltyLastN,
            kRepeatPenalty,
            0.0f,
            0.0f));
    llama_sampler_chain_add(next->sampler, llama_sampler_init_temp(kSamplingTemp));
    llama_sampler_chain_add(next->sampler, llama_sampler_init_dist(kSamplingSeed));

    g_engine = std::move(next);
    const auto load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - load_start).count();
    __android_log_print(ANDROID_LOG_INFO, kTag, "loaded model %s with %d threads, ngl=%d, batch=%u in %lld ms",
                        path.c_str(), g_engine->n_threads, g_engine->n_gpu_layers, batch_tokens,
                        static_cast<long long>(load_ms));
    return g_engine.get();
}

std::string run_llama_translation(
        const std::string &path,
        const std::string &backend_dir,
        const std::string &source,
        const std::string &target,
        const std::string &input,
        bool use_gpu,
        int max_predict_tokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    max_predict_tokens = std::max(1, std::min(kMaxAllowedPredictTokens, max_predict_tokens));
    std::string error;

    LlamaEngine * engine = ensure_engine(path, backend_dir, use_gpu, error);
    if (!engine) {
        return error;
    }

    llama_memory_clear(llama_get_memory(engine->ctx), true);
    llama_sampler_reset(engine->sampler);
    llama_perf_context_reset(engine->ctx);
    llama_perf_sampler_reset(engine->sampler);

    const std::string prompt = build_prompt(source, target, input);

    __android_log_print(ANDROID_LOG_INFO, kTag, "prompt:%s, use_gpu:%d", prompt.c_str(), use_gpu);
    std::vector<llama_token> tokens = tokenize(engine->vocab, prompt);
    if (tokens.empty()) {
        return "输入文本分词失败";
    }
    if (static_cast<int>(tokens.size()) + max_predict_tokens > static_cast<int>(llama_n_ctx(engine->ctx))) {
        return "输入过长，超过当前 context 窗口";
    }

    const auto infer_start = std::chrono::steady_clock::now();
    __android_log_print(ANDROID_LOG_INFO, kTag, "translation start: prompt_tokens=%zu max_predict=%d",
                        tokens.size(), max_predict_tokens);

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_model_has_encoder(engine->model)) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "running encoder");
        if (llama_encode(engine->ctx, batch) != 0) {
            return "llama_encode 失败";
        }
        llama_synchronize(engine->ctx);
        llama_token decoder_start = llama_model_decoder_start_token(engine->model);
        if (decoder_start == LLAMA_TOKEN_NULL) {
            decoder_start = llama_vocab_bos(engine->vocab);
        }
        llama_sampler_accept(engine->sampler, decoder_start);
        batch = llama_batch_get_one(&decoder_start, 1);
    } else {
        for (llama_token token : tokens) {
            llama_sampler_accept(engine->sampler, token);
        }
    }

    std::string output;
    std::vector<llama_token> generated;
    int n_pos = 0;
    const int n_prompt = static_cast<int>(tokens.size());
    int generated_count = 0;
    long long prefill_ms = -1;
    std::chrono::steady_clock::time_point prefill_end;
    for (int i = 0; i < max_predict_tokens && n_pos + batch.n_tokens < n_prompt + max_predict_tokens; ++i) {
        if (llama_decode(engine->ctx, batch) != 0) {
            return "llama_decode 失败";
        }
        if (i == 0) {
            llama_synchronize(engine->ctx);
            prefill_end = std::chrono::steady_clock::now();
            prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                    prefill_end - infer_start).count();
            __android_log_print(ANDROID_LOG_INFO, kTag, "prefill synchronized in %lld ms",
                                static_cast<long long>(prefill_ms));
        } else {
            llama_synchronize(engine->ctx);
        }
        n_pos += batch.n_tokens;

        llama_token next = llama_sampler_sample(engine->sampler, engine->ctx, -1);
        if (llama_vocab_is_eog(engine->vocab, next)) {
            break;
        }

        llama_sampler_accept(engine->sampler, next);
        generated.push_back(next);
        ++generated_count;
        output += token_to_piece(engine->vocab, next);
        if (has_repeated_tail(generated, 1, 8) ||
                has_repeated_tail(generated, 2, 5) ||
                has_repeated_tail(generated, 4, 4)) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "stopping repeated output at %d/%d tokens",
                                i + 1, max_predict_tokens);
            break;
        }
        batch = llama_batch_get_one(&next, 1);
        if ((i + 1) % 8 == 0) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "decoded %d/%d tokens", i + 1, max_predict_tokens);
        }
    }

    if (output.empty()) {
        return "模型没有生成翻译结果";
    }
    llama_synchronize(engine->ctx);
    const auto infer_end = std::chrono::steady_clock::now();
    const auto infer_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            infer_end - infer_start).count();
    const long long decode_ms = prefill_ms >= 0 ? std::max<long long>(1,
            std::chrono::duration_cast<std::chrono::milliseconds>(infer_end - prefill_end).count()) :
            std::max<long long>(1, infer_ms);
    const llama_perf_context_data perf = llama_perf_context(engine->ctx);
    const llama_perf_sampler_data sampler_perf = llama_perf_sampler(engine->sampler);
    const double wall_prefill_tok_per_s = prefill_ms > 0 ? tokens.size() * 1000.0 / prefill_ms : 0.0;
    const double wall_decode_tok_per_s = generated_count > 0 ? generated_count * 1000.0 / decode_ms : 0.0;
    const double perf_prefill_tok_per_s = tokens_per_second(perf.n_p_eval, perf.t_p_eval_ms);
    const double perf_decode_tok_per_s = tokens_per_second(perf.n_eval, perf.t_eval_ms);
    const double prefill_tok_per_s = perf_prefill_tok_per_s > 0.0 ? perf_prefill_tok_per_s : wall_prefill_tok_per_s;
    const double decode_tok_per_s = perf_decode_tok_per_s > 0.0 ? perf_decode_tok_per_s : wall_decode_tok_per_s;
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "translation synchronized in %lld ms, wall_prefill=%lld ms %.2f tok/s, wall_decode=%lld ms generated=%d %.2f tok/s, output_bytes=%zu",
                        static_cast<long long>(infer_ms), static_cast<long long>(prefill_ms),
                        wall_prefill_tok_per_s, decode_ms, generated_count, wall_decode_tok_per_s, output.size());
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "llama perf: p_eval=%.2f ms/%d tok %.2f tok/s, eval=%.2f ms/%d tok %.2f tok/s, sample=%.2f ms/%d tok, reused=%d",
                        perf.t_p_eval_ms, perf.n_p_eval, perf_prefill_tok_per_s,
                        perf.t_eval_ms, perf.n_eval, perf_decode_tok_per_s,
                        sampler_perf.t_sample_ms, sampler_perf.n_sample, perf.n_reused);
    std::ostringstream result;
    result << output
           << "\n\n<|simple_speech_stats|>"
           << "prefill=" << prefill_tok_per_s
           << ";decode=" << decode_tok_per_s;
    return result.str();
}

#endif

std::string status_for(const std::string &root) {
    std::ostringstream oss;
    oss << "模型目录: " << root;
#if defined(SIMPLE_SPEECH_WITH_LLAMA)
    oss << "\nllama.cpp: enabled";
#else
    oss << "\nllama.cpp: placeholder backend";
#endif
#if defined(SIMPLE_SPEECH_WITH_OPENCL)
    oss << "\nAdreno OpenCL: enabled for non-1.25bit models";
#else
    oss << "\nAdreno OpenCL: disabled; Hy-MT 1.25bit uses CPU";
#endif
    return oss.str();
}

std::string translate_impl(
        const std::string &root,
        const std::string &backend_dir,
        const std::string &model,
        const std::string &source,
        const std::string &target,
        const std::string &input,
        bool use_gpu,
        int max_predict_tokens) {
    const auto path = model_path(root, model);
    if (path.empty()) {
        return "未找到 " + model + " 的 GGUF 文件。\n请将模型放到设备目录:\n" + root + "/" + model + "/";
    }

#if defined(SIMPLE_SPEECH_WITH_LLAMA)
    return run_llama_translation(path.string(), backend_dir, source, target, input, use_gpu, max_predict_tokens);
#else
    std::ostringstream oss;
    oss << "已找到模型，但当前 APK 使用占位后端。\n"
        << "模型: " << path.string() << "\n"
        << "方向: " << source << " -> " << target << "\n"
        << "请按 README 使用包含 Hy-MT 1.25bit/STQ1_0 支持的 llama.cpp 重新编译。";
    return oss.str();
#endif
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_tencent_simplespeech_NativeSpeechEngine_nativeStatus(JNIEnv *env, jclass, jstring model_root) {
    return to_jstring(env, status_for(to_string(env, model_root)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tencent_simplespeech_NativeSpeechEngine_translate(
        JNIEnv *env,
        jclass,
        jstring model_root,
        jstring native_library_dir,
        jstring model_name,
        jstring source_language,
        jstring target_language,
        jstring input,
        jboolean use_gpu,
        jint max_predict_tokens) {
    return to_jstring(env, translate_impl(
            to_string(env, model_root),
            to_string(env, native_library_dir),
            to_string(env, model_name),
            to_string(env, source_language),
            to_string(env, target_language),
            to_string(env, input),
            use_gpu == JNI_TRUE,
            max_predict_tokens > 0 ? static_cast<int>(max_predict_tokens) : kDefaultMaxPredictTokens));
}
