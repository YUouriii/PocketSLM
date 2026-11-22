package com.pocketllm.engine

import android.os.SystemClock
import com.pocketllm.data.ChatMessage
import com.pocketllm.data.SettingsState
import com.pocketllm.runtime.ModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ModelInfo(
    val path: String,
    val description: String,
    val trainContext: Int,
    val contextSize: Int,
    val paramsB: Double,
)

data class GenParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val maxTokens: Int = 512,
    /** End-of-generation tokens are suppressed until this many tokens are out. */
    val minTokens: Int = 0,
    val repeatPenalty: Float = 1.1f,
    /** -1 = unlimited thinking, 0 = no thinking, >0 = max reasoning tokens. */
    val thinkingBudget: Int = -1,
    /** Set false for models whose chat template cannot emit a think block at all. */
    val thinkingSupported: Boolean = true,
)

enum class MediaSupport {
    UNSUPPORTED, VISION, AUDIO, VISION_AND_AUDIO,
}

/** One media input for a generation turn. Path must outlive the turn. */
data class MediaAttachment(
    val kind: MediaKind,
    /** Absolute path to the file on disk, e.g. in cacheDir. */
    val path: String,
)

enum class MediaKind { IMAGE, AUDIO }

sealed interface GenEvent {
    data class Token(val text: String) : GenEvent
    data class Done(
        val tokens: Int,
        val elapsedMs: Long,
        val cancelled: Boolean,
        /** True when generation ran out of the configured token budget. */
        val hitLimit: Boolean = false,
    ) : GenEvent
    data class Error(val message: String) : GenEvent
}

/**
 * Owns the native model and serialises all inference. Generation is a cold flow:
 * the UI renders pieces as they arrive and cancels simply by stopping collection.
 */
class InferenceEngine(
    private val nativeLibDir: String = "",
    /** Gate GPU offload on the one-time Vulkan capability probe. */
    private val gpuAllowed: () -> Boolean = { true },
) : ModelLoader {

    sealed interface Status {
        data object NoModel : Status
        data object Loading : Status
        data class Ready(val info: ModelInfo) : Status
        data class Failed(val message: String) : Status
    }

    private val inferenceDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val lock = Mutex()

    private var handle: Long = 0L
    private var info: ModelInfo? = null
    private var loadedMmproj: String? = null
    private val _mediaSupport = kotlinx.coroutines.flow.MutableStateFlow(MediaSupport.UNSUPPORTED)
    val mediaSupport: kotlinx.coroutines.flow.StateFlow<MediaSupport> = _mediaSupport.asStateFlow()
    private var mediaSupportValue: Int = 0

    private val _status = MutableStateFlow<Status>(Status.NoModel)
    val status: StateFlow<Status> = _status.asStateFlow()

    override val loadedModelPath: String? get() = info?.path
    override val loadedMmprojPath: String? get() = loadedMmproj
    val loadedModelInfo: ModelInfo? get() = info
    /** Load-time settings, tracked so changing them in Settings triggers a reload. */
    override var loadedContextSize: Int = 0
        private set
    override var loadedThreads: Int = 0
        private set
    override var loadedKvQuant: String = SettingsState.KV_AUTO
        private set
    override var loadedGpuLayers: Int = 0
        private set

    override suspend fun load(path: String, mmprojPath: String?, contextSize: Int, threads: Int, kvQuant: String, gpuLayers: Int): Result<Unit> =
        withContext(inferenceDispatcher) {
            lock.withLock {
                if (info?.path == path && handle != 0L && loadedMmproj == mmprojPath &&
                    loadedContextSize == contextSize && loadedThreads == threads &&
                    loadedKvQuant == kvQuant && loadedGpuLayers == gpuLayers
                ) {
                    return@withLock Result.success(Unit)
                }
                loadLocked(path, mmprojPath, contextSize, threads, kvQuant, gpuLayers)
            }
        }

    /** Caller must hold [lock]. */
    private fun loadLocked(path: String, mmprojPath: String?, contextSize: Int, threads: Int, kvQuant: String, gpuLayers: Int): Result<Unit> {
        _status.value = Status.Loading
        unloadLocked()
        mediaSupportValue = 0
        loadedMmproj = mmprojPath
        loadedContextSize = contextSize
        loadedThreads = threads
        loadedKvQuant = kvQuant
        loadedGpuLayers = gpuLayers
        // Never attempt a GPU load on a device the probe found incompatible.
        val effectiveGpuLayers = if (gpuAllowed()) gpuLayers else 0

        val (typeK, typeV) = SettingsState.kvTypes(kvQuant, contextSize)
        LlamaBridge.nativeInit(nativeLibDir)
        val h = runCatching {
            LlamaBridge.nativeLoadModel(path, mmprojPath, contextSize, threads, typeK, typeV, effectiveGpuLayers)
        }.getOrDefault(0L)

        if (h == 0L) {
            val message = LlamaBridge.nativeLastError() ?: "Could not load model"
            _status.value = Status.Failed(message)
            return Result.failure(IllegalStateException(message))
        }

        handle = h
        val parsed = parseInfo(path, LlamaBridge.nativeModelInfo(h))
        info = parsed
        mediaSupportValue = LlamaBridge.nativeMediaSupport(h)
        _mediaSupport.value = when {
            mediaSupportValue and 2 != 0 -> MediaSupport.AUDIO
            mediaSupportValue and 1 != 0 -> MediaSupport.VISION
            else -> MediaSupport.UNSUPPORTED
        }
        _status.value = Status.Ready(parsed)
        return Result.success(Unit)
    }

    // -- Thermal throttling --------------------------------------------------

    /** Generation threads used while the device is in severe thermal throttle. */
    var thermalThreads: Int = 2
        set(value) {
            field = value.coerceIn(1, 8)
        }

    /** True between [onThermalSevere] and [onThermalRecovered]. */
    @Volatile var thermalActive = false
        private set

    private var thermalOriginalThreads = 0

    /** The device is in severe thermal state: drop to [thermalThreads] for now. */
    suspend fun onThermalSevere() {
        lock.withLock {
            if (thermalActive) return@withLock
            val m = info
            if (m == null || handle == 0L) return@withLock
            thermalActive = true
            thermalOriginalThreads = loadedThreads
            if (loadedThreads > thermalThreads) {
                loadLocked(m.path, loadedMmproj, loadedContextSize, thermalThreads, loadedKvQuant, loadedGpuLayers)
            }
        }
    }

    /** Back to normal after cooling: restore the pre-throttle thread count. */
    suspend fun onThermalRecovered() {
        lock.withLock {
            if (!thermalActive) return@withLock
            thermalActive = false
            val m = info
            if (m != null && handle != 0L && loadedThreads < thermalOriginalThreads) {
                loadLocked(m.path, loadedMmproj, loadedContextSize, thermalOriginalThreads, loadedKvQuant, loadedGpuLayers)
            }
        }
    }

    override suspend fun unload() = withContext(inferenceDispatcher) {
        lock.withLock {
            unloadLocked()
            _status.value = Status.NoModel
        }
    }

    fun cancel() {
        val h = handle
        if (h != 0L) runCatching { LlamaBridge.nativeCancel(h) }
    }

    /** Drops the KV cache, e.g. when the user starts a new chat. */
    suspend fun resetContext() = withContext(inferenceDispatcher) {
        lock.withLock {
            val h = handle
            if (h != 0L) runCatching { LlamaBridge.nativeReset(h) }
        }
    }

    private fun unloadLocked() {
        if (handle != 0L) {
            runCatching { LlamaBridge.nativeFree(handle) }
            handle = 0L
        }
        info = null
    }

    private fun parseInfo(path: String, raw: String?): ModelInfo {
        val parts = raw?.split('|').orEmpty()
        return ModelInfo(
            path = path,
            description = parts.getOrNull(0) ?: "model",
            trainContext = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            contextSize = parts.getOrNull(2)?.toIntOrNull() ?: 0,
            paramsB = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0,
        )
    }

    fun generate(
        history: List<ChatMessage>,
        params: GenParams,
        media: List<MediaAttachment> = emptyList(),
    ): Flow<GenEvent> = channelFlow {
        val h = handle
        if (h == 0L) {
            send(GenEvent.Error("No model loaded"))
            return@channelFlow
        }

        val scope = this

        lock.withLock {
            val nCtx = info?.contextSize ?: 2048
            val maxNew = params.maxTokens.coerceIn(16, (nCtx / 2).coerceAtLeast(16))
            // Leave room for the actual answer even at the highest thinking level.
            val budget = if (params.thinkingBudget < 0) {
                -1
            } else {
                params.thinkingBudget.coerceIn(0, (maxNew - 48).coerceAtLeast(0))
            }

            val sync = if (media.isEmpty()) {
                syncPrompt(h, history, maxNew, params.thinkingSupported, params.thinkingBudget)
            } else {
                prepareMedia(h, history, media, params, maxNew)
            }
            if (!sync.ok) {
                if (sync.cancelled) {
                    // Aborted before generation started (Stop during prompt decode).
                    send(GenEvent.Done(0, 0, cancelled = true))
                } else {
                    send(GenEvent.Error(LlamaBridge.nativeLastError() ?: "Prompt does not fit in context"))
                }
                return@withLock
            }

            val callback = object : TokenCallback {
                override fun onToken(piece: String): Boolean {
                    scope.trySend(GenEvent.Token(piece))
                    return scope.isActive
                }
            }

            val start = SystemClock.elapsedRealtime()
            val produced = LlamaBridge.nativeGenerate(
                h,
                maxNew,
                params.temperature,
                params.topK,
                params.topP,
                params.repeatPenalty,
                params.minTokens,
                budget,
                sync.startInThink,
                callback,
            )
            val elapsed = SystemClock.elapsedRealtime() - start

            if (produced < 0) {
                val message = LlamaBridge.nativeLastError()
                if (!message.isNullOrBlank()) send(GenEvent.Error(message))
            } else {
                // Hitting the budget is a different outcome from a natural end,
                // and the user deserves to know the reply was cut short.
                send(
                    GenEvent.Done(
                        tokens = produced,
                        elapsedMs = elapsed,
                        cancelled = !scope.isActive,
                        hitLimit = produced >= maxNew && scope.isActive,
                    ),
                )
            }
        }
    }.buffer(Channel.UNLIMITED).flowOn(inferenceDispatcher)

    /**
     * Formats the whole conversation with the model's own chat template, then
     * decodes only the part that is not already in the KV cache. Old turns are
     * dropped until the prompt fits.
     */
    private data class PromptSync(
        val ok: Boolean,
        val startInThink: Boolean,
        val cancelled: Boolean = false,
    )

    /**
     * Media path: build the same chat-templated prompt (with any think-opener),
     * tokenize it together with the media bitmaps, and let native code decode
     * everything into the KV cache. Media turns never reuse the prompt prefix.
     */
    private fun prepareMedia(
        handle: Long,
        history: List<ChatMessage>,
        media: List<MediaAttachment>,
        params: GenParams,
        maxNew: Int,
    ): PromptSync {
        val nCtx = info?.contextSize ?: 2048
        val roles = ArrayList<String>(history.size)
        val contents = ArrayList<String>(history.size)
        history.forEach {
            roles.add(it.role)
            contents.add(it.content)
        }
        val formatted = LlamaBridge.nativeFormatChat(
            handle,
            roles.toTypedArray(),
            contents.toTypedArray(),
            true,
        ) ?: return PromptSync(false, false)

        val effective = when {
            params.thinkingSupported && params.thinkingBudget != 0 -> {
                val trimmed = formatted.trimEnd()
                if (trimmed.endsWith("<think>")) trimmed else trimmed + "<think>\n"
            }
            params.thinkingSupported -> appendEmptyThinkBlock(formatted)
            else -> formatted // No thinking template: inject nothing.
        }
        val startInThink = effective.trimEnd().endsWith("<think>")

        val n = LlamaBridge.nativePrepareMedia(
            handle,
            effective,
            media.map { it.kind.ordinal }.toIntArray(),
            media.map { it.path }.toTypedArray(),
        )
        return when {
            n >= 0 -> PromptSync(true, startInThink)
            n == -2 -> PromptSync(false, false, cancelled = true)
            else -> PromptSync(false, false)
        }
    }

    private fun syncPrompt(
        handle: Long,
        history: List<ChatMessage>,
        maxNew: Int,
        thinkingSupported: Boolean,
        thinkingBudget: Int,
    ): PromptSync {
        val nCtx = info?.contextSize ?: 2048
        var working = history

        repeat(8) {
            val roles = ArrayList<String>(working.size)
            val contents = ArrayList<String>(working.size)
            working.forEach {
                roles.add(it.role)
                contents.add(it.content)
            }

            val formatted = LlamaBridge.nativeFormatChat(
                handle,
                roles.toTypedArray(),
                contents.toTypedArray(),
                true,
            ) ?: return PromptSync(false, false)

            val effective = when {
                thinkingSupported && thinkingBudget != 0 -> {
                    // Start the think block ourselves so budget tracking always
                    // works, even if the model would not emit <think> on its own.
                    val trimmed = formatted.trimEnd()
                    if (trimmed.endsWith("<think>")) formatted else formatted + "<think>\n"
                }
                thinkingSupported -> appendEmptyThinkBlock(formatted)
                else -> formatted // Models with no thinking template: inject nothing.
            }

            val tokens = LlamaBridge.nativeTokenize(handle, effective, false)
                ?: return PromptSync(false, false)

            if (tokens.size + maxNew <= nCtx) {
                val n = LlamaBridge.nativeSyncPrompt(handle, tokens, maxNew)
                return when {
                    n >= 0 -> PromptSync(true, effective.trimEnd().endsWith("<think>"))
                    n == -2 -> PromptSync(false, false, cancelled = true)
                    else -> PromptSync(false, false)
                }
            }

            working = dropOldest(working) ?: return PromptSync(false, false)
        }
        return PromptSync(false, false)
    }

    /**
     * Injects the empty think block that Qwen3 produces when it is told not to
     * think. This is the deterministic way to skip reasoning; the budget-based
     * path in the native layer is only a backstop.
     */
    private fun appendEmptyThinkBlock(formatted: String): String {
        val trimmed = formatted.trimEnd()
        return when {
            trimmed.endsWith("<think>") -> trimmed + "\n\n</think>\n\n"
            formatted.contains("<|im_start|>assistant") -> formatted + "<think>\n\n</think>\n\n"
            else -> formatted
        }
    }

    private fun dropOldest(messages: List<ChatMessage>): List<ChatMessage>? {
        val hasSystem = messages.firstOrNull()?.role == ChatMessage.ROLE_SYSTEM
        val body = if (hasSystem) messages.drop(1) else messages
        if (body.isEmpty()) return null

        val trimmed = if (body.size <= 2) body.drop(1) else body.drop(2)
        if (trimmed.isEmpty()) return null

        return if (hasSystem) listOf(messages.first()) + trimmed else trimmed
    }
}
