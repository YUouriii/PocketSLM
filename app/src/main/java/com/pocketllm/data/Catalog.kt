package com.pocketllm.data

import androidx.annotation.StringRes
import com.pocketllm.R

/**
 * Built-in starter catalog. Every entry was checked against the Hugging Face API:
 * the repository exists, is public, and contains the listed Q4_K_M file.
 *
 * Sizes are megabytes of the quantized file.
 */
object Catalog {

    private const val APACHE = "Apache-2.0"
    private const val APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
    private const val LLAMA = "Llama 3.2 Community License"
    private const val LLAMA_URL = "https://www.llama.com/llama3_2/license/"
    private const val MIT = "MIT"
    private const val MIT_URL = "https://opensource.org/license/mit"
    private const val LFM = "LFM Open License"
    private const val LFM_URL = "https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/blob/main/LICENSE"
    private const val GEMMA = "Gemma Terms of Use"
    private const val GEMMA_URL = "https://ai.google.dev/gemma/terms"
    private const val QWEN = "Qwen License"
    private const val QWEN_URL = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/blob/main/LICENSE"

    val starter: List<CatalogModel> = listOf(
        CatalogModel(
            id = "qwen2.5-0.5b",
            name = "Qwen2.5 0.5B Instruct",
            params = "0.5B",
            publisher = "Alibaba",
            repo = "bartowski/Qwen2.5-0.5B-Instruct-GGUF",
            file = "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
            sizeMb = 400,
            quant = "Q4_K_M",
            minRamMb = 2000,
            license = APACHE,
            licenseUrl = APACHE_URL,
            description = "Tiny and quick. The safest pick for 2-3 GB phones.",
        ),
        CatalogModel(
            id = "qwen3-0.6b",
            capabilities = ModelCapabilities(reasoning = true),
            name = "Qwen3 0.6B",
            params = "0.6B",
            publisher = "Alibaba",
            repo = "unsloth/Qwen3-0.6B-GGUF",
            file = "Qwen3-0.6B-Q4_K_M.gguf",
            sizeMb = 400,
            quant = "Q4_K_M",
            minRamMb = 2000,
            license = APACHE,
            licenseUrl = APACHE_URL,
            description = "Newest small Qwen. Supports a reasoning mode you can turn off.",
        ),
        CatalogModel(
            id = "llama32-1b",
            name = "Llama 3.2 1B Instruct",
            params = "1B",
            publisher = "Meta",
            repo = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            file = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeMb = 810,
            quant = "Q4_K_M",
            minRamMb = 2500,
            license = LLAMA,
            licenseUrl = LLAMA_URL,
            description = "Solid English chat quality for a 1B. Good default for 4 GB phones.",
        ),
        CatalogModel(
            id = "qwen2.5-1.5b",
            name = "Qwen2.5 1.5B Instruct",
            params = "1.5B",
            publisher = "Alibaba",
            repo = "bartowski/Qwen2.5-1.5B-Instruct-GGUF",
            file = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            sizeMb = 990,
            quant = "Q4_K_M",
            minRamMb = 3000,
            license = APACHE,
            licenseUrl = APACHE_URL,
            description = "Best quality per MB for 4 GB phones. Strong multilingual support.",
        ),
        CatalogModel(
            id = "deepseek-r1-1.5b",
            capabilities = ModelCapabilities(reasoning = true),
            name = "DeepSeek R1 Distill 1.5B",
            params = "1.5B",
            publisher = "DeepSeek",
            repo = "bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
            file = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            sizeMb = 1120,
            quant = "Q4_K_M",
            minRamMb = 3000,
            license = MIT,
            licenseUrl = MIT_URL,
            description = "Shows its reasoning steps. Slower because it thinks before answering.",
        ),
        CatalogModel(
            id = "qwen3-1.7b",
            capabilities = ModelCapabilities(reasoning = true),
            name = "Qwen3 1.7B",
            params = "1.7B",
            publisher = "Alibaba",
            repo = "unsloth/Qwen3-1.7B-GGUF",
            file = "Qwen3-1.7B-Q4_K_M.gguf",
            sizeMb = 1110,
            quant = "Q4_K_M",
            minRamMb = 3000,
            license = APACHE,
            licenseUrl = APACHE_URL,
            description = "The best all-round 1.7B right now. Disable thinking for fast replies.",
        ),
        CatalogModel(
            id = "smollm2-1.7b",
            name = "SmolLM2 1.7B Instruct",
            params = "1.7B",
            publisher = "Hugging Face",
            repo = "bartowski/SmolLM2-1.7B-Instruct-GGUF",
            file = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            sizeMb = 1060,
            quant = "Q4_K_M",
            minRamMb = 3000,
            license = APACHE,
            licenseUrl = APACHE_URL,
            description = "Fully open, trained on public data. Honest but less polished.",
        ),
        CatalogModel(
            id = "qwen2.5-3b",
            name = "Qwen2.5 3B Instruct",
            params = "3B",
            publisher = "Alibaba",
            repo = "bartowski/Qwen2.5-3B-Instruct-GGUF",
            file = "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            sizeMb = 1930,
            quant = "Q4_K_M",
            minRamMb = 5000,
            license = APACHE,
            licenseUrl = APACHE_URL,
            description = "Noticeably smarter, for 6 GB+ phones with a fast CPU.",
        ),
        CatalogModel(
            id = "lfm2.5-350m",
            name = "LFM2.5 350M",
            params = "350M",
            publisher = "LiquidAI",
            repo = "LiquidAI/LFM2.5-350M-GGUF",
            file = "LFM2.5-350M-Q4_K_M.gguf",
            sizeMb = 219,
            quant = "Q4_K_M",
            minRamMb = 1800,
            license = LFM,
            licenseUrl = LFM_URL,
            description = "Tiny Liquid model. Blazing fast, perfect for quick answers on any phone.",
        ),
        CatalogModel(
            id = "lfm2.5-1.2b",
            name = "LFM2.5 1.2B Instruct",
            params = "1.2B",
            publisher = "LiquidAI",
            repo = "LiquidAI/LFM2.5-1.2B-Instruct-GGUF",
            file = "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
            sizeMb = 697,
            quant = "Q4_K_M",
            minRamMb = 2500,
            license = LFM,
            licenseUrl = LFM_URL,
            description = "Liquid's best small model. Hybrid architecture, fast and strong on 4 GB phones.",
        ),
        CatalogModel(
            id = "lfm3-1.7b",
            name = "LFM3 1.7B",
            params = "1.7B",
            publisher = "LiquidAI",
            repo = "LiquidAI/LFM3-1.7B-GGUF",
            file = "LFM3-1.7B-Q4_K_M.gguf",
            sizeMb = 1049,
            quant = "Q4_K_M",
            minRamMb = 3000,
            license = LFM,
            licenseUrl = LFM_URL,
            description = "LiquidAI's newest generation. Dense hybrid attention, very fast for its quality.",
        ),
        CatalogModel(
            id = "lfm2.5-2.6b",
            name = "LFM2.5 2.6B",
            params = "2.6B",
            publisher = "LiquidAI",
            repo = "LiquidAI/LFM2.5-2.6B-GGUF",
            file = "LFM2.5-2.6B-Q4_K_M.gguf",
            sizeMb = 1597,
            quant = "Q4_K_M",
            minRamMb = 4500,
            license = LFM,
            licenseUrl = LFM_URL,
            description = "Liquid's strongest compact model. For 6 GB or more RAM.",
        ),
        CatalogModel(
            id = "lfm2.5-8b-a1b",
            name = "LFM2.5 8B-A1B",
            params = "8B-A1B",
            publisher = "LiquidAI",
            repo = "LiquidAI/LFM2.5-8B-A1B-GGUF",
            file = "LFM2.5-8B-A1B-Q4_K_M.gguf",
            sizeMb = 4917,
            quant = "Q4_K_M",
            minRamMb = 7500,
            license = LFM,
            licenseUrl = LFM_URL,
            description = "Mixture-of-experts with 1B active parameters. Near-8B quality on 8 GB phones.",
        ),
        CatalogModel(
            id = "qwen3.5-0.8b",
            capabilities = ModelCapabilities(reasoning = true),
            name = "Qwen3.5 0.8B",
            params = "0.8B",
            publisher = "Alibaba",
            repo = "unsloth/Qwen3.5-0.8B-GGUF",
            file = "Qwen3.5-0.8B-Q4_K_M.gguf",
            sizeMb = 508,
            quant = "Q4_K_M",
            minRamMb = 2000,
            license = APACHE,
            licenseUrl = APACHE_URL,
            description = "The newest Qwen. Tiny, fast and surprisingly capable on low-end phones.",
        ),
        CatalogModel(
            id = "qwen3.5-2b",
            capabilities = ModelCapabilities(reasoning = true),
            name = "Qwen3.5 2B",
            params = "2B",
            publisher = "Alibaba",
            repo = "unsloth/Qwen3.5-2B-GGUF",
            file = "Qwen3.5-2B-Q4_K_M.gguf",
            sizeMb = 1222,
            quant = "Q4_K_M",
            minRamMb = 3500,
            license = APACHE,
            licenseUrl = APACHE_URL,
            description = "The newest Qwen family. Big quality jump over the 2B class.",
        ),
        CatalogModel(
            id = "qwen3.5-4b",
            capabilities = ModelCapabilities(reasoning = true),
            name = "Qwen3.5 4B",
            params = "4B",
            publisher = "Alibaba",
            repo = "unsloth/Qwen3.5-4B-GGUF",
            file = "Qwen3.5-4B-Q4_K_M.gguf",
            sizeMb = 2614,
            quant = "Q4_K_M",
            minRamMb = 5500,
            license = APACHE,
            licenseUrl = APACHE_URL,
            description = "Qwen3.5 4B. Frontier-class reasoning for 8 GB phones.",
        ),
        CatalogModel(
            id = "smollm3-3b",
            capabilities = ModelCapabilities(reasoning = true),
            name = "SmolLM3 3B",
            params = "3B",
            publisher = "Hugging Face",
            repo = "ggml-org/SmolLM3-3B-GGUF",
            file = "SmolLM3-Q4_K_M.gguf",
            sizeMb = 1827,
            quant = "Q4_K_M",
            minRamMb = 4500,
            license = APACHE,
            licenseUrl = APACHE_URL,
            description = "Open weights, hybrid reasoning, fully transparent training. Apache-2.0.",
        ),
        CatalogModel(
            id = "phi-4-mini",
            capabilities = ModelCapabilities(coding = true),
            name = "Phi-4 mini Instruct",
            params = "4B",
            publisher = "Microsoft",
            repo = "unsloth/Phi-4-mini-instruct-GGUF",
            file = "Phi-4-mini-instruct-Q4_K_M.gguf",
            sizeMb = 2376,
            quant = "Q4_K_M",
            minRamMb = 5500,
            license = MIT,
            licenseUrl = MIT_URL,
            description = "Microsoft's smartest small model. Excellent reasoning and coding for its size.",
        ),
        CatalogModel(
            id = "gemma-3n-e2b",
            name = "Gemma 3n E2B",
            params = "E2B",
            publisher = "Google",
            repo = "unsloth/gemma-3n-E2B-it-GGUF",
            file = "gemma-3n-E2B-it-Q4_K_M.gguf",
            sizeMb = 2887,
            quant = "Q4_K_M",
            minRamMb = 6500,
            license = GEMMA,
            licenseUrl = GEMMA_URL,
            description = "Google's on-device Gemma. Matryoshka architecture, great multimodal-ish base.",
        ),
        CatalogModel(
            id = "qwen2.5-coder-3b",
            capabilities = ModelCapabilities(coding = true),
            name = "Qwen2.5 Coder 3B",
            params = "3B",
            publisher = "Alibaba",
            repo = "Qwen/Qwen2.5-Coder-3B-Instruct-GGUF",
            file = "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            sizeMb = 2007,
            quant = "Q4_K_M",
            minRamMb = 5000,
            license = QWEN,
            licenseUrl = QWEN_URL,
            description = "Best small coding model here. Great at writing and explaining code.",
        ),
        CatalogModel(
            id = "lfm3-omni-2b",
            name = "LFM3 Omni 2B",
            params = "2B",
            publisher = "LiquidAI",
            repo = "LiquidAI/LFM3-Omni-2B-GGUF",
            file = "LFM3-Omni-2B-Q4_K_M.gguf",
            sizeMb = 1049,
            quant = "Q4_K_M",
            minRamMb = 5000,
            license = LFM,
            licenseUrl = LFM_URL,
            description = "Liquid's omni model: vision, audio and text. Hears and sees on-device.",
            capabilities = ModelCapabilities(vision = true, audio = true),
            mmprojFile = "mmproj-LFM3-Omni-2B-Q4_K_M.gguf",
            mmprojSizeMb = 278,
        ),
        CatalogModel(
            id = "lfm2.5-audio-1.5b",
            name = "LFM2.5 Audio 1.5B",
            params = "1.5B",
            publisher = "LiquidAI",
            repo = "LiquidAI/LFM2.5-Audio-1.5B-GGUF",
            file = "LFM2.5-Audio-1.5B-Q4_0.gguf",
            sizeMb = 664,
            quant = "Q4_0",
            minRamMb = 4500,
            license = LFM,
            licenseUrl = LFM_URL,
            description = "Liquid's audio specialist. Speak to it instead of typing.",
            capabilities = ModelCapabilities(audio = true),
            mmprojFile = "mmproj-LFM2.5-Audio-1.5B-Q4_0.gguf",
            mmprojSizeMb = 209,
        ),
        CatalogModel(
            id = "llama32-3b",
            name = "Llama 3.2 3B Instruct",
            params = "3B",
            publisher = "Meta",
            repo = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            file = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeMb = 2020,
            quant = "Q4_K_M",
            minRamMb = 5000,
            license = LLAMA,
            licenseUrl = LLAMA_URL,
            description = "Best English writing here. Needs 6 GB+ and a strong CPU.",
        ),
    )

    fun byId(id: String): CatalogModel? = starter.firstOrNull { it.id == id }

    /** Which languages a model family is known to handle. */
    enum class LanguageSupport { MULTILINGUAL, ENGLISH, UNKNOWN }

    /**
     * Curated by model family, not by filename. Qwen, DeepSeek, Gemma, Phi,
     * SmolLM3 and LFM are published as multilingual (Liquid lists Arabic among
     * their supported languages); the English-first small families are not.
     * Community uploads without catalog metadata stay UNKNOWN.
     */
    fun languageSupport(id: String?): LanguageSupport {
        if (id.isNullOrBlank()) return LanguageSupport.UNKNOWN
        val key = id.lowercase()
        return when {
            key.contains("qwen") || key.contains("deepseek") || key.contains("gemma") ||
                key.contains("smollm3") || key.contains("phi-4") || key.contains("lfm") ->
                LanguageSupport.MULTILINGUAL
            key.contains("smollm2") || key.contains("llama32") -> LanguageSupport.ENGLISH
            else -> LanguageSupport.UNKNOWN
        }
    }

    fun languageLabel(id: String?): String = when (languageSupport(id)) {
        LanguageSupport.MULTILINGUAL -> "Multilingual"
        LanguageSupport.ENGLISH -> "English-focused"
        LanguageSupport.UNKNOWN -> "Language support unknown"
    }

    /** Localized label for the same information. */
    @StringRes
    fun languageLabelRes(id: String?): Int = when (languageSupport(id)) {
        LanguageSupport.MULTILINGUAL -> R.string.lang_multilingual
        LanguageSupport.ENGLISH -> R.string.lang_english_focused
        LanguageSupport.UNKNOWN -> R.string.lang_unknown
    }

    /** Capability metadata for any model id, or none for community/uploaded files. */
    fun capabilitiesOf(id: String?): ModelCapabilities =
        id?.let(::byId)?.capabilities ?: ModelCapabilities()

    /** The companion file the multimodal models need to see, hear or understand input. */
    fun displayMmproj(id: String): String = byId(id)?.mmprojFile?.orEmpty() ?: ""


    /**
     * Models that leave comfortable headroom on this device. The largest
     * comfortable class comes first, then smaller files, so a 6 GB phone sees
     * 1.5B-class models before 2B/3B/4B ones. Measurements can reorder this
     * list later; nothing is hidden from the "All" filter.
     */
    fun recommended(totalRamMb: Long): List<CatalogModel> =
        starter.filter { it.fit(totalRamMb) == Fit.GOOD }
            .sortedWith(compareByDescending<CatalogModel> { it.minRamMb }.thenBy { it.sizeMb })
}
