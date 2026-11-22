// JNI bridge between Kotlin and llama.cpp.
//
// Design notes:
//  - One Session per loaded model. All native calls are serialised on the Kotlin side.
//  - The session keeps the token history of the KV cache so the next turn only
//    decodes the delta (common prefix reuse). Long chats stay fast.
//  - Generation streams UTF-8 pieces back through a Kotlin callback so the UI
//    can render tokens as they arrive.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#ifdef POCKETLLM_VULKAN
#include <vulkan/vulkan.h>
#endif

#include "llama.h"
#include "ggml-backend.h"
#include "tools/mtmd/mtmd.h"
#include "tools/mtmd/mtmd-helper.h"
#include "tools/mtmd/mtmd-helper-common.h"

#define TAG "PocketSLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_batch batch{};
    bool batch_ready = false;
    mtmd_context *vision = nullptr;
    // Media turns fill s->tokens with placeholder ids; the next text turn must
    // rebuild the KV cache instead of treating those ids as a valid prefix.
    bool needsFullSync = false;

    // Tokens currently materialised in the KV cache.
    std::vector<llama_token> tokens;

    std::mutex mu;
    std::atomic<bool> cancel{false};
    int n_batch = 256;
    int n_ctx = 2048;
};

std::string g_last_error;
// Directory holding the runtime CPU backend variants (Android extractNativeLibs).
std::string g_lib_dir;

bool abort_cb(void *data) {
    auto *s = static_cast<Session *>(data);
    return s != nullptr && s->cancel.load(std::memory_order_relaxed);
}

// ---------------------------------------------------------------- UTF helpers

void appendUtf8(std::string &out, uint32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

// Byte-level tokens can split one multi-byte character across several pieces.
// Keep the trailing bytes until the sequence is complete, so emoji and other
// non-ASCII text reach Kotlin as valid UTF-8 instead of replacement characters.
void drainUtf8(std::string &pending, std::string &ready) {
    size_t i = 0;
    while (i < pending.size()) {
        const unsigned char c = static_cast<unsigned char>(pending[i]);
        int extra = 0;
        uint32_t cp = 0xFFFD;
        if (c < 0x80) {
            cp = c;
        } else if ((c & 0xE0) == 0xC0) {
            cp = c & 0x1F;
            extra = 1;
        } else if ((c & 0xF0) == 0xE0) {
            cp = c & 0x0F;
            extra = 2;
        } else if ((c & 0xF8) == 0xF0) {
            cp = c & 0x07;
            extra = 3;
        }

        if (i + static_cast<size_t>(extra) >= pending.size()) break;

        bool valid = true;
        for (int k = 1; k <= extra; ++k) {
            const unsigned char cc = static_cast<unsigned char>(pending[i + k]);
            if ((cc & 0xC0) != 0x80) {
                valid = false;
                break;
            }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!valid || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            cp = 0xFFFD;
            extra = 0;
        }

        appendUtf8(ready, cp);
        i += static_cast<size_t>(extra) + 1;
    }
    pending.erase(0, i);
}

std::string toUtf8(JNIEnv *env, jstring js) {
    if (js == nullptr) return {};
    const jsize len = env->GetStringLength(js);
    const jchar *chars = env->GetStringChars(js, nullptr);
    std::string out;
    out.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        appendUtf8(out, cp);
    }
    env->ReleaseStringChars(js, chars);
    return out;
}

jstring toJString(JNIEnv *env, const std::string &s) {
    std::vector<jchar> u16;
    u16.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        uint32_t cp = 0xFFFD;
        size_t extra = 0;
        if (c < 0x80) { cp = c; extra = 0; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; extra = 1; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; extra = 2; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; extra = 3; }

        if (i + extra < s.size()) {
            bool ok = true;
            for (size_t k = 1; k <= extra; ++k) {
                const unsigned char cc = static_cast<unsigned char>(s[i + k]);
                if ((cc & 0xC0) != 0x80) { ok = false; break; }
                cp = (cp << 6) | (cc & 0x3F);
            }
            if (!ok) { cp = 0xFFFD; extra = 0; }
        } else {
            cp = 0xFFFD;
            extra = 0;
        }

        if (cp >= 0x10000) {
            cp -= 0x10000;
            u16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            u16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        } else {
            u16.push_back(static_cast<jchar>(cp));
        }
        i += extra + 1;
    }
    return env->NewString(u16.data(), static_cast<jsize>(u16.size()));
}

// ------------------------------------------------------------------ utilities

void ensure_backend() {
    static std::once_flag flag;
    std::call_once(flag, [] {
#ifdef GGML_BACKEND_DL
        // Pick the best CPU kernel variant for this SoC (dotprod / i8mm / SVE).
        if (!g_lib_dir.empty()) {
            ggml_backend_load_all_from_path(g_lib_dir.c_str());
        } else {
            ggml_backend_load_all();
        }
#endif
        llama_backend_init();
    });
}

Session *from_handle(jlong handle) {
    return reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
}

int decode_tokens(Session *s, const llama_token *toks, int count, int start_pos, bool logits_last) {
    int rc = 0;
    for (int off = 0; off < count; off += s->n_batch) {
        const int n = std::min(s->n_batch, count - off);
        llama_batch &b = s->batch;
        b.n_tokens = n;
        for (int i = 0; i < n; ++i) {
            b.token[i] = toks[off + i];
            b.pos[i] = start_pos + off + i;
            b.n_seq_id[i] = 1;
            b.seq_id[i][0] = 0;
            b.logits[i] = (logits_last && (off + i) == count - 1) ? 1 : 0;
        }
        rc = llama_decode(s->ctx, b);
        if (rc != 0) return rc;
    }
    return rc;
}

bool tokenize(Session *s, const std::string &text, bool add_special, std::vector<llama_token> &out) {
    const int n_max = static_cast<int>(text.size()) + 8;
    out.resize(static_cast<size_t>(n_max));
    int n = llama_tokenize(s->vocab, text.data(), static_cast<int32_t>(text.size()),
                           out.data(), n_max, add_special, true);
    if (n < 0) {
        out.resize(static_cast<size_t>(-n));
        n = llama_tokenize(s->vocab, text.data(), static_cast<int32_t>(text.size()),
                           out.data(), static_cast<int32_t>(out.size()), add_special, true);
    }
    if (n < 0) {
        g_last_error = "tokenize failed";
        return false;
    }
    out.resize(static_cast<size_t>(n));
    return true;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeInit(JNIEnv *env, jobject, jstring jlibDir) {
    if (jlibDir != nullptr) g_lib_dir = toUtf8(env, jlibDir);
    ensure_backend();

    // Forward llama.cpp logs to logcat (they would otherwise be lost).
    static std::once_flag log_bridge_flag;
    std::call_once(log_bridge_flag, [] {
        llama_log_set([](ggml_log_level level, const char * text, void *) {
            // Keep only warnings and errors; INFO and progress (CONT) flood logcat.
            if (text != nullptr && (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR)) {
                LOGI("llama[%d]: %s", level, text);
            }
        }, nullptr);
    });

    static std::once_flag log_flag;
    std::call_once(log_flag, [] {
        LOGI("backends: (libs in %s)", g_lib_dir.c_str());
        for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
            auto reg = ggml_backend_reg_get(i);
            LOGI("  reg %zu: %s", i, ggml_backend_reg_name(reg));
        }
        const size_t n = ggml_backend_dev_count();
        for (size_t i = 0; i < n; ++i) {
            auto dev = ggml_backend_dev_get(i);
            LOGI("  dev %zu: %s", i, ggml_backend_dev_description(dev));
        }
        // Report which CPU kernel variant was dlopened (helps diagnose perf).
        if (FILE *f = fopen("/proc/self/maps", "r")) {
            char line[512];
            std::string seen;
            while (fgets(line, sizeof(line), f) != nullptr) {
                const char *p = strstr(line, "libggml-cpu");
                if (p == nullptr) continue;
                const char *end = strstr(p, ".so");
                if (end == nullptr) continue;
                std::string lib(p, end + 3 - p);
                if (seen.find(lib) == std::string::npos) {
                    if (!seen.empty()) seen += ", ";
                    seen += lib;
                }
            }
            fclose(f);
            LOGI("cpu backends loaded: %s", seen.empty() ? "(none)" : seen.c_str());
        }
    });
}

namespace {
// True when llama.cpp has a Vulkan backend registered (ggml_vk_instance_init).
bool vulkanAvailable() {
    return ggml_backend_reg_by_name("Vulkan") != nullptr;
}

#ifdef POCKETLLM_VULKAN
// Query the physical device directly instead of relying on backend registration:
// a Vulkan loader/driver can exist while the device cannot run llama.cpp shaders.
// Checked once per process; the caller caches the result in Java as well.
static std::string probeVulkanDevice() {
    std::ostringstream o;
    o << "vulkan=1\n";

    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "PocketSLM";
    app.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    app.pEngineName = "PocketSLM-probe";
    app.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    app.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo ici{};
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&ici, nullptr, &instance) != VK_SUCCESS) {
        o << "compatible=0\nreason=instance_create_failed\n";
        return o.str();
    }

    uint32_t devCount = 0;
    vkEnumeratePhysicalDevices(instance, &devCount, nullptr);
    std::vector<VkPhysicalDevice> devices(devCount);
    if (devCount == 0 || vkEnumeratePhysicalDevices(instance, &devCount, devices.data()) != VK_SUCCESS) {
        vkDestroyInstance(instance, nullptr);
        o << "compatible=0\nreason=no_physical_device\n";
        return o.str();
    }

    // Prefer the first device with a compute queue.
    VkPhysicalDevice dev = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties props{};
    bool computeQueue = false;
    for (auto candidate : devices) {
        uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> queues(qCount);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &qCount, queues.data());
        for (const auto &q : queues) {
            if (q.queueFlags & VK_QUEUE_COMPUTE_BIT) computeQueue = true;
        }
        if (computeQueue) {
            dev = candidate;
            vkGetPhysicalDeviceProperties(dev, &props);
            break;
        }
    }
    if (dev == VK_NULL_HANDLE) {
        vkDestroyInstance(instance, nullptr);
        o << "compatible=0\nreason=no_compute_queue\n";
        return o.str();
    }

    // Device extensions. GGML_VULKAN requires VK_KHR_16bit_storage when the
    // device is Vulkan 1.0; on 1.1 it is core but still reported here.
    uint32_t extCount = 0;
    vkEnumerateDeviceExtensionProperties(dev, nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> exts(extCount);
    vkEnumerateDeviceExtensionProperties(dev, nullptr, &extCount, exts.data());
    bool ext16bit = false;
    bool extF16I8 = false;
    for (const auto &e : exts) {
        if (strcmp(e.extensionName, "VK_KHR_16bit_storage") == 0) ext16bit = true;
        if (strcmp(e.extensionName, "VK_KHR_shader_float16_int8") == 0) extF16I8 = true;
    }

    const bool api11 = props.apiVersion >= VK_API_VERSION_1_1;
    VkPhysicalDeviceVulkan11Features f11{};
    f11.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
    VkPhysicalDeviceVulkan12Features f12{};
    f12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
    VkPhysicalDevice16BitStorageFeatures extF16{};
    extF16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES;
    VkPhysicalDeviceFeatures2 f2{};
    f2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    if (api11) {
        f2.pNext = &f11;
        f11.pNext = &f12;
    } else if (ext16bit) {
        f2.pNext = &extF16;
    }
    if (api11 || ext16bit) vkGetPhysicalDeviceFeatures2(dev, &f2);

    const bool storage16 = api11 ? (f11.storageBuffer16BitAccess == VK_TRUE)
                                 : (extF16.storageBuffer16BitAccess == VK_TRUE);
    const bool shaderF16 = api11 ? (f12.shaderFloat16 == VK_TRUE) : false;
    const bool shaderInt8 = api11 ? (f12.shaderInt8 == VK_TRUE) : false;

    // Mandatory for ggml-vulkan (see ggml-vulkan.cpp: "Unsupported device"):
    // Vulkan 1.1+ with storageBuffer16BitAccess and a compute queue.
    const bool compatible = api11 && storage16 && computeQueue;

    o << "device=" << props.deviceName << "\n";
    o << "vendor=0x" << std::hex << props.vendorID << std::dec << "\n";
    o << "driver=0x" << std::hex << props.driverVersion << std::dec << "\n";
    o << "api=" << VK_VERSION_MAJOR(props.apiVersion) << "." << VK_VERSION_MINOR(props.apiVersion) << "." << VK_VERSION_PATCH(props.apiVersion) << "\n";
    o << "computeQueue=" << (computeQueue ? 1 : 0) << "\n";
    o << "ext.VK_KHR_16bit_storage=" << (ext16bit ? 1 : 0) << "\n";
    o << "ext.VK_KHR_shader_float16_int8=" << (extF16I8 ? 1 : 0) << "\n";
    o << "feature.storageBuffer16BitAccess=" << (storage16 ? 1 : 0) << "\n";
    o << "feature.shaderFloat16=" << (shaderF16 ? 1 : 0) << "\n";
    o << "feature.shaderInt8=" << (shaderInt8 ? 1 : 0) << "\n";
    o << "compatible=" << (compatible ? 1 : 0) << "\n";
    if (!compatible) {
        o << "missing=";
        bool first = true;
        auto add = [&](const char *name) {
            if (!first) o << ",";
            o << name;
            first = false;
        };
        if (!api11) add("Vulkan1.1");
        if (!storage16) add("storageBuffer16BitAccess");
        if (!computeQueue) add("computeQueue");
        o << "\n";
    }

    vkDestroyInstance(instance, nullptr);
    return o.str();
}
#endif
}

JNIEXPORT jstring JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeLlamaVersion(JNIEnv *env, jobject) {
    const char *v = llama_version();
    return env->NewStringUTF(v != nullptr ? v : "unknown");
}

JNIEXPORT jstring JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeVulkanProbe(JNIEnv *env, jobject) {
    static std::string cached;
    static std::once_flag flag;
    std::call_once(flag, [] {
#ifdef POCKETLLM_VULKAN
        cached = probeVulkanDevice();
#else
        cached = "vulkan=0\ncompatible=0\nreason=no_vulkan_backend\n";
#endif
        LOGI("vulkan probe:\n%s", cached.c_str());
    });
    return env->NewStringUTF(cached.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeLoadModel(JNIEnv *env, jobject, jstring jpath, jstring jmmproj, jint n_ctx, jint n_threads, jint type_k, jint type_v, jint n_gpu_layers) {
    ensure_backend();
    const std::string path = toUtf8(env, jpath);
    const std::string mmproj = jmmproj ? toUtf8(env, jmmproj) : std::string{};

    const bool vulkan = vulkanAvailable();
    if (n_gpu_layers > 0 && !vulkan) {
        LOGI("gpu: n_gpu_layers=%d requested but no Vulkan backend available, staying on CPU", n_gpu_layers);
        n_gpu_layers = 0;
    }

    auto *s = new Session();
    s->n_ctx = std::max(256, static_cast<int>(n_ctx));
    s->n_batch = std::min(256, s->n_ctx);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP; // weights stay on disk, pages in on demand
    mp.use_extra_bufts = false;

    // With devices == NULL llama.cpp enumerates every registered backend.
    // Some phones register a Vulkan device that cannot actually be used (Adreno
    // without 16-bit storage) and throw during load. Restrict CPU-only loads
    // to the CPU device so those phones still work.
    ggml_backend_dev_t cpu_devs[2] = { ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU), nullptr };
    auto use_cpu_only = [&]() {
        if (cpu_devs[0] != nullptr) mp.devices = cpu_devs;
    };
    if (n_gpu_layers <= 0) use_cpu_only();

    s->model = llama_model_load_from_file(path.c_str(), mp);
    if (s->model == nullptr && n_gpu_layers > 0) {
        // A backend can register successfully yet be unusable for inference
        // (e.g. Adreno 610 without VK_KHR_16bit_storage support). Retry on CPU
        // so the model still loads.
        LOGI("gpu: model load with n_gpu_layers=%d failed, retrying on CPU", n_gpu_layers);
        mp.n_gpu_layers = 0;
        use_cpu_only();
        s->model = llama_model_load_from_file(path.c_str(), mp);
        if (s->model != nullptr) n_gpu_layers = 0;
    }
    if (s->model == nullptr) {
        g_last_error = "Could not load model: " + path;
        delete s;
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(s->n_ctx);
    cp.n_batch = static_cast<uint32_t>(s->n_batch);
    cp.n_ubatch = static_cast<uint32_t>(s->n_batch);
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.n_seq_max = 1;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.type_k = static_cast<enum ggml_type>(type_k);
    cp.type_v = static_cast<enum ggml_type>(type_v);
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = s;

    s->ctx = llama_init_from_model(s->model, cp);
    if (s->ctx == nullptr) {
        g_last_error = "Could not create context (out of memory?)";
        llama_model_free(s->model);
        delete s;
        return 0;
    }

    s->vocab = llama_model_get_vocab(s->model);
    s->batch = llama_batch_init(s->n_batch, 0, 1);
    s->batch_ready = true;
    s->tokens.reserve(static_cast<size_t>(s->n_ctx));

    if (!mmproj.empty()) {
        const struct mtmd_context_params vparams = mtmd_context_params_default();
        s->vision = mtmd_init_from_file(mmproj.c_str(), s->model, vparams);
        if (s->vision == nullptr) {
            // Loading continues; nativeSupportsMedia reports that media is unavailable.
            g_last_error = "model loaded but the mmproj projector failed to initialise: " + mmproj;
            LOGE("%s", g_last_error.c_str());
        } else {
            mtmd_helper_log_set([](ggml_log_level level, const char * text, void *) {
                (void) level;
                LOGI("mtmd: %s", text);
            }, nullptr);
            LOGI("mmproj ready: %s", mmproj.c_str());
        }
    }

    char desc[256] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));
    LOGI("loaded %s (n_ctx=%d threads=%d type_k=%s type_v=%s n_gpu_layers=%d)", desc, s->n_ctx, n_threads,
         ggml_type_name(cp.type_k), ggml_type_name(cp.type_v), n_gpu_layers);

    return static_cast<jlong>(reinterpret_cast<intptr_t>(s));
}

JNIEXPORT jint JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeMediaSupport(JNIEnv *, jobject, jlong handle) {
    Session *s = from_handle(handle);
    if (s == nullptr || s->vision == nullptr) return 0;
    int support = 0;
    if (mtmd_support_vision(s->vision)) support |= 1;
    if (mtmd_support_audio(s->vision)) support |= 2;
    return support;
}

JNIEXPORT void JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    Session *s = from_handle(handle);
    if (s == nullptr) return;
    if (s->vision != nullptr) mtmd_free(s->vision);
    if (s->batch_ready) llama_batch_free(s->batch);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

JNIEXPORT void JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeReset(JNIEnv *, jobject, jlong handle) {
    Session *s = from_handle(handle);
    if (s == nullptr) return;
    std::lock_guard<std::mutex> lock(s->mu);
    s->cancel.store(false);
    s->tokens.clear();
    llama_memory_clear(llama_get_memory(s->ctx), true);
}

JNIEXPORT void JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeCancel(JNIEnv *, jobject, jlong handle) {
    Session *s = from_handle(handle);
    if (s == nullptr) return;
    s->cancel.store(true);
}

JNIEXPORT jstring JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeModelInfo(JNIEnv *env, jobject, jlong handle) {
    Session *s = from_handle(handle);
    if (s == nullptr) return nullptr;
    char desc[256] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));
    char buf[512];
    snprintf(buf, sizeof(buf), "%s|%d|%d|%.2f",
             desc,
             llama_model_n_ctx_train(s->model),
             s->n_ctx,
             static_cast<double>(llama_model_n_params(s->model)) / 1e9);
    return toJString(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeChatTemplate(JNIEnv *env, jobject, jlong handle) {
    Session *s = from_handle(handle);
    if (s == nullptr) return nullptr;
    const char *tmpl = llama_model_chat_template(s->model, nullptr);
    return tmpl ? toJString(env, tmpl) : nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeFormatChat(
        JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray contents, jboolean add_assistant) {
    Session *s = from_handle(handle);
    if (s == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> role_store(static_cast<size_t>(n));
    std::vector<std::string> content_store(static_cast<size_t>(n));
    std::vector<llama_chat_message> msgs(static_cast<size_t>(n));

    for (jsize i = 0; i < n; ++i) {
        auto jr = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto jc = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        role_store[i] = toUtf8(env, jr);
        content_store[i] = toUtf8(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
        msgs[i].role = role_store[i].c_str();
        msgs[i].content = content_store[i].c_str();
    }

    const char *tmpl = llama_model_chat_template(s->model, nullptr);
    if (tmpl == nullptr) tmpl = "chatml";

    int32_t needed = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                               add_assistant == JNI_TRUE, nullptr, 0);
    if (needed < 0) {
        g_last_error = "chat template failed";
        return nullptr;
    }
    std::string out(static_cast<size_t>(needed) + 16, '\0');
    int32_t written = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(),
                                                add_assistant == JNI_TRUE, out.data(),
                                                static_cast<int32_t>(out.size()));
    if (written < 0) {
        g_last_error = "chat template failed";
        return nullptr;
    }
    out.resize(static_cast<size_t>(written));
    return toJString(env, out);
}

JNIEXPORT jintArray JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeTokenize(JNIEnv *env, jobject, jlong handle, jstring jtext, jboolean add_special) {
    Session *s = from_handle(handle);
    if (s == nullptr) return nullptr;

    std::vector<llama_token> toks;
    if (!tokenize(s, toUtf8(env, jtext), add_special == JNI_TRUE, toks)) return nullptr;

    jintArray arr = env->NewIntArray(static_cast<jsize>(toks.size()));
    if (arr == nullptr) return nullptr;
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(toks.size()),
                           reinterpret_cast<const jint *>(toks.data()));
    return arr;
}

// Decodes only the part of `tokens` that is not already in the KV cache.
// Returns the number of tokens now cached, or -1 if it does not fit.
// Evaluates a prompt containing mtmd media markers and the files they reference.
// Returns the number of tokens now cached, or -1 on failure. Media bypass the
// token-prefix cache entirely: the KV cache is rebuilt for every media turn.
JNIEXPORT jint JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativePrepareMedia(JNIEnv *env, jobject, jlong handle,
        jstring jprompt, jintArray jkinds, jobjectArray jpaths) {
    Session *s = from_handle(handle);
    if (s == nullptr) return -1;

    std::lock_guard<std::mutex> lock(s->mu);
    s->cancel.store(false);

    if (s->vision == nullptr) {
        g_last_error = "this model was loaded without an mmproj projector";
        return -1;
    }

    const std::string prompt = toUtf8(env, jprompt);
    const jsize kindCount = jkinds ? env->GetArrayLength(jkinds) : 0;
    const jsize pathCount = jpaths ? env->GetArrayLength(jpaths) : 0;
    if (kindCount != pathCount) {
        g_last_error = "media kind and path count mismatch";
        return -1;
    }

    std::vector<int> kinds;
    std::vector<std::string> paths;
    if (kindCount > 0) {
        std::vector<jint> rawKinds(static_cast<size_t>(kindCount));
        env->GetIntArrayRegion(jkinds, 0, kindCount, rawKinds.data());
        for (jsize i = 0; i < kindCount; ++i) {
            kinds.push_back(static_cast<int>(rawKinds[static_cast<size_t>(i)]));
            auto jp = static_cast<jstring>(env->GetObjectArrayElement(jpaths, i));
            paths.push_back(toUtf8(env, jp));
            env->DeleteLocalRef(jp);
        }
    }

    std::vector<mtmd::bitmap> bitmaps;
    std::vector<const mtmd_bitmap *> bitmapPtrs;
    auto init_opt = mtmd_helper_init_opt_default();
    for (int kind : kinds) {
        (void) kind;
    }
    for (const auto &p : paths) {
        auto wrapper = mtmd_helper_bitmap_init_from_file(s->vision, p.c_str(), false, init_opt);
        if (wrapper.bitmap == nullptr) {
            g_last_error = "failed to load media: " + p;
            return -1;
        }
        bitmaps.emplace_back(mtmd::bitmap(wrapper.bitmap));
        bitmapPtrs.push_back(wrapper.bitmap);
    }

    // Split the prompt at every default marker so each text segment is tokenized
    // naturally and each media marker interleaves its bitmap.
    const std::string marker = mtmd_default_marker();
    std::vector<std::string> segments;
    size_t start = 0;
    while (true) {
        const size_t pos = prompt.find(marker, start);
        if (pos == std::string::npos) break;
        segments.push_back(prompt.substr(start, pos - start));
        start = pos + marker.size();
    }
    segments.push_back(prompt.substr(start));
    if (bitmaps.size() != segments.size() - 1) {
        g_last_error = "media markers do not match attached media";
        return -1;
    }

    std::vector<mtmd_input_text> texts(segments.size(), {nullptr, 0, false, true});
    std::vector<mtmd_input_part> parts;
    for (size_t i = 0; i < segments.size(); ++i) {
        texts[i] = {segments[i].c_str(), segments[i].size(), /* add_special */ false, /* parse_special */ true};
        parts.push_back({&texts[i], nullptr});
        if (i < bitmaps.size()) {
            parts.push_back({nullptr, bitmaps[i].ptr.get()});
        }
    }
    std::vector<const mtmd_input_part *> partsPtr;
    for (const auto &p : parts) partsPtr.push_back(&p);

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    int32_t rc = mtmd_tokenize_from_parts(
        s->vision, chunks, partsPtr.data(), partsPtr.size(), /* add_bos */ true);
    if (rc != 0) {
        g_last_error = "failed to tokenize media prompt (code " + std::to_string(rc) + ")";
        mtmd_input_chunks_free(chunks);
        return -1;
    }

    // Rebuild the KV cache from scratch. Media turns always re-derive it.
    llama_memory_seq_rm(llama_get_memory(s->ctx), 0, 0, -1);
    s->tokens.clear();

    llama_pos n_past = 0;
    const size_t chunkCount = mtmd_input_chunks_size(chunks);
    for (size_t i = 0; i < chunkCount; ++i) {
        auto chunk = mtmd_input_chunks_get(chunks, i);
        const auto type = mtmd_input_chunk_get_type(chunk);
        llama_pos newNPast = n_past;
        if (type == MTMD_INPUT_CHUNK_TYPE_TEXT) {
            rc = mtmd_helper_eval_chunk_single(s->vision, s->ctx, chunk, n_past, 0, s->n_batch,
                i == chunkCount - 1, &newNPast);
        } else {
            mtmd::batch_ptr batch(mtmd_batch_init(s->vision));
            rc = mtmd_batch_add_chunk(batch.get(), chunk);
            if (rc == 0) {
                rc = mtmd_batch_encode(batch.get());
                float * embd = mtmd_batch_get_output_embd(batch.get(), chunk);
                rc = mtmd_helper_decode_image_chunk(s->vision, s->ctx, chunk, embd, n_past, 0,
                    s->n_batch, &newNPast, nullptr, nullptr);
            }
        }
        if (rc != 0) {
            mtmd_input_chunks_free(chunks);
            if (s->cancel.load()) {
                g_last_error = "";
                return -2;
            }
            g_last_error = "failed to evaluate media chunk " + std::to_string(i);
            return -1;
        }
        n_past = newNPast;
    }

    s->tokens.clear();
    s->tokens.resize(static_cast<size_t>(n_past), 0);
    s->needsFullSync = true;
    mtmd_input_chunks_free(chunks);
    return static_cast<jint>(n_past);
}

JNIEXPORT jint JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeSyncPrompt(JNIEnv *env, jobject, jlong handle, jintArray jtokens, jint reserve) {    Session *s = from_handle(handle);
    if (s == nullptr) return -1;

    const jsize n = env->GetArrayLength(jtokens);
    std::vector<llama_token> toks(static_cast<size_t>(n));
    env->GetIntArrayRegion(jtokens, 0, n, reinterpret_cast<jint *>(toks.data()));

    std::lock_guard<std::mutex> lock(s->mu);
    s->cancel.store(false);

    if (static_cast<int>(n) + reserve > s->n_ctx) return -1;

    // Reuse the longest common prefix of the cache and the new prompt. Append-only
    // turns decode just the tail; divergent turns drop the stale suffix first.
    // Truncation is safe on recurrent/hybrid memories: each cell holds the state
    // at its position, so only the removed tail is lost.
    size_t limit = std::min(s->tokens.size(), static_cast<size_t>(n));
    if (s->needsFullSync) limit = 0;
    size_t reused = 0;
    while (reused < limit && s->tokens[reused] == toks[reused]) ++reused;

    int start = static_cast<int>(reused);
    if (start < static_cast<int>(s->tokens.size())) {
        if (!llama_memory_seq_rm(llama_get_memory(s->ctx), 0, start, -1)) {
            llama_memory_clear(llama_get_memory(s->ctx), true);
            s->tokens.clear();
            start = 0;
        } else {
            s->tokens.resize(static_cast<size_t>(start));
        }
    }
    s->needsFullSync = false;

    const int64_t t_start = ggml_time_us();
    int rc = 0;
    if (start < static_cast<int>(n)) {
        rc = decode_tokens(s, toks.data() + start, static_cast<int>(n) - start, start, true);
        if (rc != 0) {
            if (s->cancel.load(std::memory_order_relaxed)) {
                // Aborted by the user (Stop). Leave the cache empty so the next
                // turn rebuilds cleanly instead of trusting a partial prefix.
                llama_memory_clear(llama_get_memory(s->ctx), true);
                s->tokens.clear();
                g_last_error = "";
                return -2;
            }
            // Fall back to a clean rebuild instead of failing the turn.
            llama_memory_clear(llama_get_memory(s->ctx), true);
            s->tokens.clear();
            rc = decode_tokens(s, toks.data(), static_cast<int>(n), 0, true);
            if (rc != 0) {
                if (rc == 2) {
                    g_last_error = "";
                    return -2;
                }
                g_last_error = "prompt decode failed (" + std::to_string(rc) + ")";
                return -1;
            }
            s->tokens.assign(toks.begin(), toks.end());
        } else {
            s->tokens.insert(s->tokens.end(), toks.begin() + start, toks.end());
        }
    }

    const double ms = (ggml_time_us() - t_start) / 1000.0;
    LOGI("prompt: %d tokens (%d reused) in %.0f ms", static_cast<int>(n), start, ms);

    return static_cast<jint>(s->tokens.size());
}

JNIEXPORT jint JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeGenerate(
        JNIEnv *env, jobject, jlong handle, jint max_tokens, jfloat temp, jint top_k,
        jfloat top_p, jfloat repeat_penalty, jint min_tokens, jint thinking_budget,
        jboolean start_in_think, jobject callback) {
    Session *s = from_handle(handle);
    if (s == nullptr) return -1;

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID cb_method = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)Z");
    if (cb_method == nullptr) {
        g_last_error = "callback method missing";
        return -1;
    }

    std::lock_guard<std::mutex> lock(s->mu);
    s->cancel.store(false);

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler *chain = llama_sampler_chain_init(sp);
    // Suppress end tokens until the minimum reply length is reached. Small
    // instruct models otherwise stop after one word on some prompts.
    llama_sampler *minLenBias = nullptr;
    int32_t minLenIndex = -1;
    if (min_tokens > 0) {
        std::vector<llama_logit_bias> bias;
        const int32_t n_vocab = llama_vocab_n_tokens(s->vocab);
        for (int32_t id = 0; id < n_vocab; ++id) {
            if (llama_vocab_is_eog(s->vocab, id)) {
                bias.push_back({id, -1000.0f});
            }
        }
        if (!bias.empty()) {
            minLenBias = llama_sampler_init_logit_bias(
                    n_vocab, static_cast<int32_t>(bias.size()), bias.data());
            if (minLenBias != nullptr) {
                minLenIndex = llama_sampler_chain_n(chain);
                llama_sampler_chain_add(chain, minLenBias);
            }
        }
    }
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(
            llama_vocab_n_tokens(s->vocab), 64, repeat_penalty, 0.0f, 0.0f));
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto emit = [&](const std::string &text) -> bool {
        if (text.empty()) return true;
        jstring js = toJString(env, text);
        const jboolean keep_going = env->CallBooleanMethod(callback, cb_method, js);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keep_going == JNI_TRUE;
    };

    const int64_t t_start = ggml_time_us();
    int produced = 0;
    int stop_reason = 0; // 0 = length, 1 = eog, 2 = cancelled, 3 = error

    std::string pending;
    std::string emitted;
    bool think_opened = start_in_think == JNI_TRUE;
    bool think_closed = false;
    bool forced_close = false;
    bool need_force_close = think_opened && thinking_budget == 0;
    int think_tokens = 0;

    // The prompt already opened the think block; echo it to the UI so the
    // message content carries the opening tag as well.
    if (think_opened) {
        pending.append("<think>\n");
        std::string opener;
        drainUtf8(pending, opener);
        emitted += opener;
        if (!emit(opener)) {
            llama_sampler_free(chain);
            s->cancel.store(false);
            return 0;
        }
    }

    for (int i = 0; i < max_tokens; ++i) {
        if (s->cancel.load(std::memory_order_relaxed)) {
            stop_reason = 2;
            break;
        }
        if (static_cast<int>(s->tokens.size()) >= s->n_ctx - 1) {
            stop_reason = 0;
            break;
        }

        // Close the reasoning block once the budget is spent. The forced tokens
        // go into the KV cache so the model continues with the answer.
        if (need_force_close) {
            need_force_close = false;
            std::vector<llama_token> close_tokens;
            if (!tokenize(s, "</think>\n", false, close_tokens) || close_tokens.empty()) {
                g_last_error = "could not tokenize think terminator";
                stop_reason = 3;
                break;
            }
            const int pos = static_cast<int>(s->tokens.size());
            if (decode_tokens(s, close_tokens.data(), static_cast<int>(close_tokens.size()), pos, true) != 0) {
                if (s->cancel.load(std::memory_order_relaxed)) {
                    stop_reason = 2;
                } else {
                    g_last_error = "decode failed during thinking cut-off";
                    stop_reason = 3;
                }
                break;
            }
            s->tokens.insert(s->tokens.end(), close_tokens.begin(), close_tokens.end());
            forced_close = true;
            think_closed = true;
            if (!emit("</think>\n")) {
                stop_reason = 2;
                break;
            }
        }

        const llama_token id = llama_sampler_sample(chain, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) {
            stop_reason = 1;
            break;
        }
        llama_sampler_accept(chain, id);

        char piece[512];
        const int len = llama_token_to_piece(s->vocab, id, piece, sizeof(piece), 0, true);
        if (len > 0) pending.append(piece, static_cast<size_t>(len));

        std::string ready;
        drainUtf8(pending, ready);
        if (!ready.empty()) {
            emitted += ready;
            if (emitted.size() > 4096) emitted.erase(0, emitted.size() - 4096);
            if (!emit(ready)) {
                stop_reason = 2;
                break;
            }
        }

        s->tokens.push_back(id);

        llama_batch &b = s->batch;
        b.n_tokens = 1;
        b.token[0] = id;
        b.pos[0] = static_cast<llama_pos>(s->tokens.size() - 1);
        b.n_seq_id[0] = 1;
        b.seq_id[0][0] = 0;
        b.logits[0] = 1;

        if (llama_decode(s->ctx, b) != 0) {
            if (s->cancel.load(std::memory_order_relaxed)) {
                stop_reason = 2;
            } else {
                g_last_error = "decode failed during generation";
                stop_reason = 3;
            }
            break;
        }
        ++produced;

        if (minLenBias != nullptr && produced >= min_tokens) {
            llama_sampler_chain_remove(chain, minLenIndex);
            llama_sampler_free(minLenBias);
            minLenBias = nullptr;
        }

        if (!think_closed) {
            if (!think_opened && emitted.find("<think>") != std::string::npos) {
                think_opened = true;
            }
            if (think_opened) {
                ++think_tokens;
                if (emitted.find("</think>") != std::string::npos) {
                    think_closed = true;
                } else if (!forced_close && thinking_budget >= 0 && think_tokens >= thinking_budget) {
                    need_force_close = true;
                }
            }
        }
    }

    if (stop_reason != 2 && !pending.empty()) {
        std::string tail;
        for (unsigned char c : pending) appendUtf8(tail, 0xFFFD);
        emit(tail);
        pending.clear();
    }

    llama_sampler_free(chain);
    s->cancel.store(false);

    const double ms = (ggml_time_us() - t_start) / 1000.0;
    if (ms > 0) {
        LOGI("generated %d tokens in %.0f ms (%.2f tok/s)", produced, ms, produced * 1000.0 / ms);
    }

    if (stop_reason == 3) return -1;
    return produced;
}

JNIEXPORT jstring JNICALL
Java_com_pocketllm_engine_LlamaBridge_nativeLastError(JNIEnv *env, jobject) {
    return toJString(env, g_last_error);
}

} // extern "C"
