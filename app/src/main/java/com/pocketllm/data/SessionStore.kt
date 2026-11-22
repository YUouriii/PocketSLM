package com.pocketllm.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A single saved conversation. */
@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val userMessageCount: Int get() = messages.count { it.role == ChatMessage.ROLE_USER }

    val preview: String
        get() {
            val raw = messages.lastOrNull { it.content.isNotBlank() }?.content ?: return ""
            val answer = raw.substringAfterLast("</think>").trim().ifBlank {
                raw.removePrefix("<think>").substringBefore("</think>").trim()
            }
            return answer.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(72).orEmpty()
        }
}

/**
 * Persists every conversation as a named session so the user can run more
 * than one chat and switch back to any of them later.
 */
class SessionStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "sessions.json")
    private val legacyFile = File(appContext.filesDir, "chat.json")
    private val prefs =
        appContext.getSharedPreferences("pocketllm_sessions", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentId = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = _currentId.asStateFlow()

    init {
        // Load synchronously so no store operation can race the initial read and
        // overwrite the file with an empty list before the data is in memory.
        migrateLegacyChat()
        val stored = runCatching {
            json.decodeFromString<List<ChatSession>>(file.readText())
        }.getOrElse {
            // Never destroy an unreadable history: keep a copy so it can be
            // recovered (or reported) instead of being overwritten as empty.
            if (file.length() > 2) {
                runCatching {
                    file.copyTo(File(appContext.filesDir, "sessions.unreadable.json"), overwrite = true)
                }
            }
            emptyList()
        }

        _sessions.value = stored
            .filter { it.messages.isNotEmpty() }
            .sortedByDescending { it.updatedAt }
        val savedId = prefs.getString(KEY_CURRENT, null)
        _currentId.value = savedId?.takeIf { id -> _sessions.value.any { it.id == id } }
    }

    /** The legacy single-chat file becomes the first session instead of being lost. */
    private fun migrateLegacyChat() {
        if (!legacyFile.exists() || file.exists()) return
        val legacy = runCatching {
            json.decodeFromString<List<ChatMessage>>(legacyFile.readText())
        }.getOrNull()
        if (legacy != null && legacy.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val session = ChatSession(
                id = UUID.randomUUID().toString(),
                title = titleFrom(legacy) ?: "Chat",
                messages = legacy,
                createdAt = legacy.firstOrNull()?.ts ?: now,
                updatedAt = now,
            )
            file.writeText(json.encodeToString(listOf(session)))
            prefs.edit().putString(KEY_CURRENT, session.id).apply()
        }
        runCatching { legacyFile.delete() }
    }

    /** Always non-throwing: returns the id of the session to append to. */
    suspend fun currentOrCreate(seedTitle: String? = null): String = withContext(Dispatchers.IO) {
        val existing = _currentId.value
        if (existing != null && _sessions.value.any { it.id == existing }) {
            existing
        } else {
            createRecord(seedTitle)
        }
    }

    suspend fun createSession(seedTitle: String? = null): String =
        withContext(Dispatchers.IO) { createRecord(seedTitle) }

    private fun createRecord(seedTitle: String?): String {
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = truncateTitle(seedTitle) ?: "New chat",
            messages = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        _sessions.value = listOf(session) + _sessions.value
        persist(session.id)
        return session.id
    }

    fun current(): ChatSession? =
        _currentId.value?.let { id -> _sessions.value.firstOrNull { it.id == id } }

    suspend fun saveCurrent(messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        val id = _currentId.value ?: return@withContext
        val now = System.currentTimeMillis()
        _sessions.value = _sessions.value.map { session ->
            if (session.id == id) {
                // Keep the placeholder title; the model names the session after the first reply.
                session.copy(messages = messages, updatedAt = now)
            } else {
                session
            }
        }
        pruneEmpty()
        persist(id)
    }

    /** Stores a model-generated title for the session. */
    suspend fun rename(id: String, title: String) = withContext(Dispatchers.IO) {
        val cleaned = truncateTitle(title) ?: return@withContext
        _sessions.value = _sessions.value.map { session ->
            if (session.id == id) session.copy(title = cleaned) else session
        }
        persist(_currentId.value)
    }

    suspend fun select(id: String) = withContext(Dispatchers.IO) {
        _sessions.value = _sessions.value.sortedByDescending { it.updatedAt }
        if (_sessions.value.none { it.id == id }) return@withContext
        _currentId.value = id
        prefs.edit().putString(KEY_CURRENT, id).apply()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        _sessions.value = _sessions.value.filterNot { it.id == id }
        if (_currentId.value == id) {
            val next = _sessions.value.firstOrNull()?.id
            _currentId.value = next
            prefs.edit().putString(KEY_CURRENT, next).apply()
        }
        pruneEmpty()
        persist(_currentId.value)
    }

    /** Drop sessions that ended up with no messages, except the one in focus. */
    private fun pruneEmpty() {
        _sessions.value = _sessions.value.filter { it.id == _currentId.value || it.messages.isNotEmpty() }
    }

    private fun persist(currentId: String?) {
        _currentId.value = currentId
        pruneEmpty()
        runCatching { file.writeText(json.encodeToString(_sessions.value)) }
        prefs.edit().putString(KEY_CURRENT, currentId).apply()
    }

    private fun titleFrom(messages: List<ChatMessage>): String? =
        messages.firstOrNull { it.role == ChatMessage.ROLE_USER }
            ?.content
            ?.let(::truncateTitle)

    private fun truncateTitle(raw: String?): String? {
        val cleaned = raw?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null } ?: return null
        return if (cleaned.length <= MAX_TITLE) cleaned else cleaned.take(MAX_TITLE).trimEnd() + "…"
    }

    private companion object {
        const val KEY_CURRENT = "current_session"
        const val MAX_TITLE = 48
    }
}
