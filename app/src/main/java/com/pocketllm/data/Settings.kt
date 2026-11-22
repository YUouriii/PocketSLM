package com.pocketllm.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Controls whether the model reasons before answering and for how long. */
enum class ThinkingLevel(val label: String, val budget: Int) {
    OFF("Off", 0),
    LOW("Low", 96),
    MEDIUM("Medium", 256),
    HIGH("High", 640);

    companion object {
        fun fromStored(value: String?): ThinkingLevel =
            entries.firstOrNull { it.name == value } ?: MEDIUM
    }
}

/** Speed-based ranking preference. Never hides or disables models. */
enum class PerformancePreference(val stored: String) {
    RECOMMENDED("recommended"), BALANCED("balanced"), SHOW_ALL("show_all");

    companion object {
        fun fromStored(value: String?): PerformancePreference =
            entries.firstOrNull { it.stored == value } ?: RECOMMENDED
    }
}

/** Which PocketSLM-owned resources may be released when memory is tight. */
enum class MemoryMode(val stored: String) {
    PRESERVE("preserve"), BALANCED("balanced"), PRIORITIZE("prioritize");

    companion object {
        fun fromStored(value: String?): MemoryMode =
            entries.firstOrNull { it.stored == value } ?: PRESERVE
    }
}

data class SettingsState(
    val threads: Int = 4,
    val kvQuant: String = KV_AUTO,
    val gpuLayers: Int = 0,
    val contextSize: Int = 2048,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val maxTokens: Int = 512,
    val minReplyTokens: Int = 8,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.MEDIUM,
    val theme: String = THEME_SYSTEM,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val hfToken: String = "",
    val keepScreenOn: Boolean = true,
    val activeModelId: String? = null,
    val performancePreference: String = PerformancePreference.RECOMMENDED.stored,
    val memoryMode: String = MemoryMode.PRESERVE.stored,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant. Answer clearly and briefly. " +
                "Always reply in the same language the user writes in."
        /** The English-only prompt shipped before multilingual support. */
        const val LEGACY_SYSTEM_PROMPT = "You are a helpful assistant. Answer clearly and briefly."
        val CONTEXT_CHOICES = listOf(1024, 2048, 4096, 8192)
        val THINKING_LEVELS = ThinkingLevel.entries
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        val THEMES = listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)

        // KV cache types. The values are ggml_type enums (see llama.h / ggml.h).
        const val KV_F16 = "f16"
        const val KV_Q4_0 = "q4_0"
        const val KV_Q8_0 = "q8_0"
        const val KV_AUTO = "auto"
        val KV_CHOICES = listOf(KV_AUTO, KV_Q8_0, KV_Q4_0, KV_F16)
        /** Minimum tokens the sampler must emit before an end token is allowed. */
        val MIN_REPLY_CHOICES = listOf(0, 8, 16, 32)
        private const val GGML_TYPE_F16 = 1
        private const val GGML_TYPE_Q4_0 = 2
        private const val GGML_TYPE_Q8_0 = 8

        /**
         * Resolve the K/V cache types for the configured precision. [KV_AUTO]
         * keeps full precision on short contexts (KV is cheap there) and uses
         * Q8_0 from 2 KB up; Q8_0 is near-lossless while halving+ the KV memory.
         */
        fun kvTypes(kvQuant: String, contextSize: Int): Pair<Int, Int> = when (kvQuant) {
            KV_Q4_0 -> GGML_TYPE_Q4_0 to GGML_TYPE_Q4_0
            KV_Q8_0 -> GGML_TYPE_Q8_0 to GGML_TYPE_Q8_0
            KV_F16 -> GGML_TYPE_F16 to GGML_TYPE_F16
            else -> if (contextSize >= 2048) GGML_TYPE_Q8_0 to GGML_TYPE_Q8_0 else GGML_TYPE_F16 to GGML_TYPE_F16
        }
    }
}

class Settings(context: Context, defaultThreads: Int) {

    private val prefs = context.getSharedPreferences("pocketllm.settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read(defaultThreads))
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    val current: SettingsState get() = _state.value

    fun update(transform: (SettingsState) -> SettingsState) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit()
            .putInt(KEY_THREADS, next.threads)
            .putString(KEY_KV_QUANT, next.kvQuant)
            .putInt(KEY_GPU_LAYERS, next.gpuLayers)
            .putInt(KEY_CONTEXT, next.contextSize)
            .putFloat(KEY_TEMP, next.temperature)
            .putFloat(KEY_TOP_P, next.topP)
            .putInt(KEY_TOP_K, next.topK)
            .putInt(KEY_MAX_TOKENS, next.maxTokens)
            .putInt(KEY_MIN_REPLY, next.minReplyTokens)
            .putString(KEY_THINKING, next.thinkingLevel.name)
            .putString(KEY_THEME, next.theme)
            .putString(KEY_SYSTEM, next.systemPrompt)
            .putString(KEY_TOKEN, next.hfToken)
            .putBoolean(KEY_SCREEN_ON, next.keepScreenOn)
            .putString(KEY_ACTIVE, next.activeModelId)
            .putString(KEY_PERF_PREF, next.performancePreference)
            .putString(KEY_MEMORY_MODE, next.memoryMode)
            .apply()
    }

    private fun read(defaultThreads: Int) = SettingsState(
        threads = prefs.getInt(KEY_THREADS, defaultThreads),
        kvQuant = prefs.getString(KEY_KV_QUANT, SettingsState.KV_AUTO) ?: SettingsState.KV_AUTO,
        gpuLayers = prefs.getInt(KEY_GPU_LAYERS, 0).coerceIn(0, 100),
        contextSize = prefs.getInt(KEY_CONTEXT, 2048),
        temperature = prefs.getFloat(KEY_TEMP, 0.7f),
        topP = prefs.getFloat(KEY_TOP_P, 0.9f),
        topK = prefs.getInt(KEY_TOP_K, 40),
        maxTokens = prefs.getInt(KEY_MAX_TOKENS, 512),
        minReplyTokens = prefs.getInt(KEY_MIN_REPLY, 8),
        thinkingLevel = ThinkingLevel.fromStored(prefs.getString(KEY_THINKING, null)),
        theme = prefs.getString(KEY_THEME, SettingsState.THEME_SYSTEM) ?: SettingsState.THEME_SYSTEM,
        systemPrompt = (prefs.getString(KEY_SYSTEM, SettingsState.DEFAULT_SYSTEM_PROMPT)
            ?: SettingsState.DEFAULT_SYSTEM_PROMPT).let { stored ->
            // Upgrade the untouched old default so existing installs also get
            // the reply-in-the-user's-language rule; custom prompts stay as-is.
            if (stored.trim() == SettingsState.LEGACY_SYSTEM_PROMPT) {
                SettingsState.DEFAULT_SYSTEM_PROMPT
            } else {
                stored
            }
        },
        hfToken = prefs.getString(KEY_TOKEN, "") ?: "",
        keepScreenOn = prefs.getBoolean(KEY_SCREEN_ON, true),
        activeModelId = prefs.getString(KEY_ACTIVE, null),
        performancePreference = PerformancePreference.fromStored(
            prefs.getString(KEY_PERF_PREF, PerformancePreference.RECOMMENDED.stored),
        ).stored,
        memoryMode = MemoryMode.fromStored(
            prefs.getString(KEY_MEMORY_MODE, MemoryMode.PRESERVE.stored),
        ).stored,
    )

    private companion object {
        const val KEY_THREADS = "threads"
        const val KEY_KV_QUANT = "kvQuant"
        const val KEY_GPU_LAYERS = "gpuLayers"
        const val KEY_CONTEXT = "context"
        const val KEY_TEMP = "temp"
        const val KEY_TOP_P = "topP"
        const val KEY_TOP_K = "topK"
        const val KEY_MAX_TOKENS = "maxTokens"
        const val KEY_MIN_REPLY = "minReplyTokens"
        const val KEY_THINKING = "thinkingLevel"
        const val KEY_THEME = "theme"
        const val KEY_SYSTEM = "system"
        const val KEY_TOKEN = "hfToken"
        const val KEY_SCREEN_ON = "keepScreenOn"
        const val KEY_ACTIVE = "activeModel"
        const val KEY_PERF_PREF = "performancePreference"
        const val KEY_MEMORY_MODE = "memoryMode"
    }
}
