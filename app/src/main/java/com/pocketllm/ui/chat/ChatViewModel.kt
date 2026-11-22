package com.pocketllm.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import android.util.Log
import com.pocketllm.Graph
import com.pocketllm.R
import com.pocketllm.core.formatSpeed
import com.pocketllm.data.Catalog
import com.pocketllm.data.ChatMessage
import com.pocketllm.data.AttachmentRef
import com.pocketllm.data.ChatSession
import com.pocketllm.data.InstalledModel
import com.pocketllm.data.ModelCapabilities
import com.pocketllm.data.ThinkingLevel
import com.pocketllm.engine.GenEvent
import com.pocketllm.engine.GenParams
import com.pocketllm.engine.InferenceEngine
import com.pocketllm.selection.SelectionCoordinator
import com.pocketllm.runtime.LoadOutcome
import com.pocketllm.runtime.LoadRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val generating: Boolean = false,
    val activeModel: InstalledModel? = null,
    val modelStatus: InferenceEngine.Status = InferenceEngine.Status.NoModel,
    val error: String? = null,
    val stats: String? = null,
    val keepScreenOn: Boolean = true,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.MEDIUM,
    val attachments: List<Attachment> = emptyList(),
    val sessions: List<ChatSession> = emptyList(),
    val currentSessionId: String? = null,
    val sessionsOpen: Boolean = false,
    val sessionToDelete: ChatSession? = null,
) {
    val currentSession: ChatSession? get() = sessions.firstOrNull { it.id == currentSessionId }
}

/** What the active model can take as input. */
sealed interface Attachment {
    val id: String
    val uri: String
    val label: String

    data class Photo(
        override val id: String,
        override val uri: String,
        override val label: String = "Photo",
    ) : Attachment

    data class Voice(
        override val id: String,
        override val uri: String,
        override val label: String = "Voice clip",
        val durationMs: Long = 0L,
    ) : Attachment
}

class ChatViewModel : ViewModel() {

    private val engine = Graph.engine
    private val models = Graph.models
    private val settings = Graph.settings
    private val chats = Graph.chats

    /** Everything the active model can do with media. */
    val activeCapabilities: kotlinx.coroutines.flow.StateFlow<ModelCapabilities> = run {
        combine(settings.state, models.installed) { cfg, installed ->
            val active = installed.firstOrNull { it.id == cfg.activeModelId }
            Catalog.capabilitiesOf(active?.id)
        }.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
            initialValue = Catalog.capabilitiesOf(
                models.installed.value.firstOrNull { it.id == settings.current.activeModelId }?.id,
            ),
        )
    }

    private val _state = MutableStateFlow(
        ChatUiState(
            keepScreenOn = settings.current.keepScreenOn,
            thinkingLevel = settings.current.thinkingLevel,
        )
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var genJob: Job? = null

    init {
        // Data is already loaded synchronously in the store's init block.
        val initial = chats.current()
        _state.update {
            it.copy(
                messages = initial?.messages ?: emptyList(),
                sessions = chats.sessions.value,
                currentSessionId = chats.currentId.value,
            )
        }

        viewModelScope.launch {
            combine(settings.state, models.installed) { s, installed -> s to installed }
                .collect { (s, installed) ->
                    val manual = installed.firstOrNull { it.id == s.activeModelId }
                    // Automatic mode applies only when the user has no explicit model.
                    val auto = if (manual == null && !Graph.benchMode) {
                        runCatching {
                            SelectionCoordinator.select(
                                Graph.context(),
                                s,
                                installed,
                                Graph.measurements,
                            )
                        }.getOrNull()
                    } else null
                    val effective = manual ?: auto?.model?.let { chosen ->
                        installed.firstOrNull { it.id == chosen.id }
                    }
                    val gpuLayers = if (manual != null) s.gpuLayers else (auto?.gpuLayers ?: s.gpuLayers)
                    if (auto != null) {
                        Log.i(
                            "Selection",
                            "model=${auto.model?.id ?: "none"} backend=${auto.backend} " +
                                "fit=${auto.fit} confidence=${auto.confidence} basis=${auto.basis} " +
                                "manual=${auto.manualOverride} reasons=${auto.reasons.joinToString("; ")}",
                        )
                    }
                    _state.update {
                        it.copy(
                            activeModel = effective,
                            keepScreenOn = s.keepScreenOn,
                            thinkingLevel = s.thinkingLevel,
                        )
                    }
                    if (!Graph.benchMode && effective != null && (
                        engine.loadedModelPath != effective.path ||
                            engine.loadedMmprojPath != effective.mmprojPath ||
                            engine.loadedContextSize != s.contextSize ||
                            engine.loadedThreads != s.threads ||
                            engine.loadedGpuLayers != gpuLayers
                        )
                    ) {
                        val descriptor = auto?.model?.takeIf { it.id == effective.id }
                            ?: SelectionCoordinator.descriptors(installed).firstOrNull { it.id == effective.id }
                        if (descriptor != null) {
                            val outcome = Graph.runtime.ensureLoaded(
                                LoadRequest(
                                    model = descriptor,
                                    mmprojPath = effective.mmprojPath,
                                    contextSize = s.contextSize,
                                    threads = s.threads,
                                    kvQuant = s.kvQuant,
                                    gpuLayers = gpuLayers,
                                    manualOverride = manual != null,
                                    fit = auto?.fit,
                                    backend = auto?.backend,
                                    measurements = Graph.measurements,
                                    generationActive = _state.value.generating,
                                    memoryMode = s.memoryMode,
                                ),
                            )
                            if (outcome.outcome == LoadOutcome.REJECTED) {
                                _state.update {
                                    it.copy(activeModel = null, error = outcome.reason.ifBlank { "Model rejected by memory check" })
                                }
                            }
                        }
                    }
                }
        }

        viewModelScope.launch {
            engine.status.collect { status ->
                _state.update {
                    it.copy(
                        modelStatus = status,
                        error = (status as? InferenceEngine.Status.Failed)?.message ?: it.error,
                    )
                }
            }
        }
    }

    fun onInputChange(text: String) = _state.update { it.copy(input = text) }

    fun setThinkingLevel(level: ThinkingLevel) = settings.update { it.copy(thinkingLevel = level) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun send() {
        val current = _state.value
        val text = current.input.trim()
        if ((text.isEmpty() && current.attachments.isEmpty()) || current.generating) return

        if (current.activeModel == null) {
            _state.update { it.copy(error = "Pick a model in the Models tab first") }
            return
        }
        if (current.modelStatus is InferenceEngine.Status.Loading) return

        val pendingAttachments = current.attachments
        val markers = pendingAttachments.joinToString(" ") { "<__media__>" }
        val userContent = if (markers.isBlank()) text else "$text $markers"
        val user = ChatMessage(
            ChatMessage.ROLE_USER,
            userContent,
            System.currentTimeMillis(),
            pendingAttachments.map {
                AttachmentRef(
                    kind = when (it) {
                        is Attachment.Photo -> "photo"
                        is Attachment.Voice -> "voice"
                    },
                    path = it.uri,
                    name = it.label,
                )
            },
        )

        viewModelScope.launch {
            // Seed as "New chat" so the model can name it from the first reply.
            val sessionId = chats.currentOrCreate(null)
            val withPlaceholder = current.messages +
                user +
                ChatMessage(ChatMessage.ROLE_ASSISTANT, "", System.currentTimeMillis() + 1)

            _state.update {
                it.copy(
                    messages = withPlaceholder,
                    input = "",
                    generating = true,
                    error = null,
                    stats = null,
                    sessions = chats.sessions.value,
                    currentSessionId = sessionId,
                    attachments = emptyList(),
                )
            }
            // Persist the prompt now so the conversation survives a process death mid-generation.
            chats.saveCurrent(withPlaceholder)
            _state.update { it.copy(sessions = chats.sessions.value) }
            startGeneration(withPlaceholder)
        }
    }

    private fun startGeneration(withPlaceholder: List<ChatMessage>) {
        val cfg = settings.current
        val activeCaps = activeCapabilities.value
        val history = buildList {
            if (cfg.systemPrompt.isNotBlank()) {
                add(ChatMessage(ChatMessage.ROLE_SYSTEM, cfg.systemPrompt))
            }
            addAll(withPlaceholder.dropLast(1))
        }
        val params = GenParams(
            temperature = cfg.temperature,
            topP = cfg.topP,
            topK = cfg.topK,
            maxTokens = cfg.maxTokens,
            minTokens = cfg.minReplyTokens,
            thinkingBudget = if (activeCaps.reasoning) cfg.thinkingLevel.budget else -1,
            thinkingSupported = activeCaps.reasoning,
        )

        // Every marker in the conversation maps to one media file, in order.
        val media = withPlaceholder
            .flatMap { it.attachments }
            .map { ref ->
                com.pocketllm.engine.MediaAttachment(
                    kind = if (ref.kind == "photo") com.pocketllm.engine.MediaKind.IMAGE else com.pocketllm.engine.MediaKind.AUDIO,
                    path = ref.path,
                )
            }

        genJob = viewModelScope.launch {
            val reply = StringBuilder()
            var lastUiAt = 0L
            try {
                engine.generate(history, params, media).collect { event ->
                    when (event) {
                        is GenEvent.Token -> {
                            reply.append(event.text)
                            // Reparse/recompose at a steady, human-readable rate
                            // instead of competing with inference for every piece.
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUiAt >= UI_UPDATE_INTERVAL_MS) {
                                lastUiAt = now
                                updateAssistant(reply.toString())
                            }
                        }

                        is GenEvent.Done -> {
                            if (reply.isBlank()) {
                                // An empty reply would poison the next turn with
                                // consecutive user messages. Drop the placeholder.
                                _state.update { current ->
                                    current.copy(
                                        messages = current.messages.filterNot {
                                            it.role == ChatMessage.ROLE_ASSISTANT && it.content.isBlank()
                                        },
                                        error = if (event.cancelled) {
                                            null
                                        } else {
                                            "The model returned an empty reply. Try regenerating or switching models."
                                        },
                                        stats = null,
                                    )
                                }
                                return@collect
                            }
                            updateAssistant(reply.toString())
                            _state.update {
                                it.copy(
                                    stats = if (event.tokens > 0) {
                                        Graph.string(
                                            if (event.hitLimit) R.string.chat_stats_limited
                                            else R.string.chat_stats,
                                            event.tokens,
                                            formatSpeed(event.tokens, event.elapsedMs),
                                        )
                                    } else {
                                        null
                                    },
                                )
                            }
                            maybeAutoTitle()
                        }

                        is GenEvent.Error -> _state.update { it.copy(error = event.message) }
                    }
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    _state.update { it.copy(error = t.message ?: "Generation failed") }
                }
            } finally {
                if (reply.isNotBlank()) updateAssistant(reply.toString())
                _state.update { it.copy(generating = false) }
                saveChat()
            }
        }
    }

    fun stop() {
        engine.cancel()
        genJob?.cancel()
        genJob = null
        _state.update { it.copy(generating = false) }
    }

    /** Adds a pending attachment to the input area (max 2). Returns false if full. */
    fun addAttachment(attachment: Attachment): Boolean {
        if (_state.value.attachments.size >= 2) return false
        _state.update { it.copy(attachments = it.attachments + attachment) }
        return true
    }

    fun removeAttachment(id: String) = _state.update {
        it.copy(attachments = it.attachments.filterNot { a -> a.id == id })
    }

    /** Drops the assistant reply and generates it again with the current settings. */
    fun regenerate() {
        val current = _state.value
        if (current.generating) return
        val trimmed = current.messages.toMutableList()
        while (trimmed.isNotEmpty() && !trimmed.last().isUser) {
            trimmed.removeAt(trimmed.lastIndex)
        }
        if (trimmed.isEmpty()) return

        val withPlaceholder = trimmed +
            ChatMessage(ChatMessage.ROLE_ASSISTANT, "", System.currentTimeMillis())
        _state.update {
            it.copy(messages = withPlaceholder, generating = true, stats = null, error = null)
        }
        startGeneration(withPlaceholder)
    }

    fun newChat() {
        stop()
        viewModelScope.launch {
            engine.resetContext()
            chats.createSession()
            _state.update {
                it.copy(
                    messages = emptyList(),
                    stats = null,
                    error = null,
                    sessions = chats.sessions.value,
                    currentSessionId = chats.currentId.value,
                    sessionsOpen = false,
                    sessionToDelete = null,
                )
            }
        }
    }

    fun openSessions() = _state.update { it.copy(sessionsOpen = true, sessionToDelete = null) }

    fun closeSessions() = _state.update { it.copy(sessionsOpen = false) }

    fun selectSession(id: String) {
        if (_state.value.generating) stop()
        viewModelScope.launch {
            chats.select(id)
            engine.resetContext()
            _state.update {
                it.copy(
                    messages = chats.current()?.messages ?: emptyList(),
                    stats = null,
                    error = null,
                    sessions = chats.sessions.value,
                    currentSessionId = chats.currentId.value,
                    sessionsOpen = false,
                    sessionToDelete = null,
                )
            }
        }
    }

    fun requestDeleteSession(session: ChatSession) =
        _state.update { it.copy(sessionToDelete = session, sessionsOpen = false) }

    fun cancelDeleteSession() = _state.update { it.copy(sessionToDelete = null) }

    fun confirmDeleteSession() {
        val target = _state.value.sessionToDelete ?: return
        val deletingCurrent = _state.value.currentSessionId == target.id
        viewModelScope.launch {
            if (!deletingCurrent) chats.saveCurrent(_state.value.messages)
            chats.delete(target.id)
            engine.resetContext()
            _state.update {
                it.copy(
                    messages = if (deletingCurrent) chats.current()?.messages ?: emptyList() else _state.value.messages,
                    stats = if (deletingCurrent) null else it.stats,
                    sessions = chats.sessions.value,
                    currentSessionId = chats.currentId.value,
                    sessionToDelete = null,
                )
            }
        }
    }

    private fun updateAssistant(text: String) {
        _state.update { current ->
            val messages = current.messages.toMutableList()
            val index = messages.indexOfLast { it.role == ChatMessage.ROLE_ASSISTANT }
            if (index >= 0) messages[index] = messages[index].copy(content = text)
            current.copy(messages = messages)
        }
    }

    /**
     * Names the conversation from the user's own words. This deliberately does
     * not ask the model: a second prefill + decoding pass doubled the cost of
     * the first reply on low-end phones for a cosmetic label.
     */
    private fun maybeAutoTitle() {
        viewModelScope.launch {
            val state = _state.value
            val sessionId = state.currentSessionId ?: return@launch
            val session = chats.sessions.value.firstOrNull { it.id == sessionId } ?: return@launch
            if (session.title != "New chat") return@launch

            val userText = state.messages
                .firstOrNull { it.role == ChatMessage.ROLE_USER }
                ?.content
                ?.replace("<__media__>", "")
                ?: return@launch
            val finalTitle = fallbackTitle(userText) ?: return@launch

            chats.rename(sessionId, finalTitle)
            _state.update { it.copy(sessions = chats.sessions.value) }
        }
    }

    private fun fallbackTitle(text: String): String? =
        text.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(48)
            ?.takeIf { it.isNotBlank() }

    private fun saveChat() {
        viewModelScope.launch {
            val cleaned = _state.value.messages.filter { it.content.isNotBlank() }
            chats.saveCurrent(cleaned)
            _state.update {
                it.copy(
                    sessions = chats.sessions.value,
                    currentSessionId = chats.currentId.value,
                )
            }
        }
    }

    private companion object {
        /** 20 fps is well above reading speed and leaves CPU for inference. */
        const val UI_UPDATE_INTERVAL_MS = 50L
    }
}
