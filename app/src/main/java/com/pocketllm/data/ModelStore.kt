package com.pocketllm.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/** File naming for downloads. Kept pure so the staging rules can be tested. */
internal fun modelFileName(file: String): String = file.replace('/', '_').replace('\\', '_')

/** Partial downloads live under a non-GGUF name and can never look installed. */
internal fun modelPartName(file: String): String = modelFileName(file) + ".part"

internal fun hasGgufHeader(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0] == 0x47.toByte() && bytes[1] == 0x47.toByte() &&
        bytes[2] == 0x55.toByte() && bytes[3] == 0x46.toByte()

@Serializable
data class PendingDownload(
    val downloadId: Long,
    val id: String,
    val repo: String,
    val file: String,
    val displayName: String,
    val params: String,
    val quant: String,
    val sizeMb: Long,
    val license: String? = null,
    val mmprojRepo: String? = null,
    val mmprojFile: String? = null,
    val mmprojSizeMb: Long = 0L,
)

data class DownloadProgress(
    val id: String,
    val bytes: Long,
    val total: Long,
    val status: Status,
    val message: String? = null,
) {
    enum class Status { PENDING, RUNNING, DONE, FAILED }

    val fraction: Float
        get() = if (total > 0L) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * Owns the models folder and talks to the system DownloadManager, so big files
 * keep downloading with the app in the background and survive restarts.
 */
class ModelStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val dm = context.getSystemService(DownloadManager::class.java)

    /** Catalog id -> mmproj file name for the models that need one. */
    private val CompanionMap: Map<String, String> by lazy {
        Catalog.starter.mapNotNull { model -> model.mmprojFile?.let { model.id to it } }.toMap()
    }

    /** Same directory DownloadManager is told to write into. */
    val modelsDir: File = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")

    private val indexFile = File(context.filesDir, "models-index.json")
    private val pendingFile = File(context.filesDir, "downloads-pending.json")

    private val _installed = MutableStateFlow<List<InstalledModel>>(emptyList())
    val installed: StateFlow<List<InstalledModel>> = _installed.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    private var pending: MutableList<PendingDownload> = mutableListOf()

    init {
        modelsDir.mkdirs()
        pending = readPending().toMutableList()
        refreshInstalled()
        sync()
    }

    fun fileNameFor(file: String): String = modelFileName(file)

    /** Downloads are staged under a non-GGUF name so a partial file can never
     * be mistaken for an installed model by the folder scan. */
    private fun partNameFor(file: String): String = modelPartName(file)

    /** True only for a file with a real GGUF header. Guards against partial
     * downloads, HTML error pages and LFS pointers being registered as models. */
    private fun isPlausibleGguf(file: File): Boolean {
        if (!file.isFile || file.length() < 16L) return false
        return runCatching {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 && hasGgufHeader(magic)
            }
        }.getOrDefault(false)
    }

    /** Final paths currently being written, including legacy downloads that
     * wrote straight to the .gguf name. Excluded from the installed scan. */
    private fun pendingFinalPaths(): Set<String> = pending
        .flatMap { listOf(fileNameFor(it.file), it.mmprojFile?.let(::fileNameFor)) }
        .filterNotNull()
        .map { File(modelsDir, it).absolutePath }
        .toSet()

    /** True while this model (or another entry writing the same file) is downloading. */
    @Synchronized
    fun isDownloading(file: String, id: String? = null): Boolean = pending.any { p ->
        p.id == id || p.file.equals(file, true) || fileNameFor(p.file) == fileNameFor(file)
    }

    @Synchronized
    fun refreshInstalled() {
        val index = readIndex().associateBy { it.id }.toMutableMap()
        val writing = pendingFinalPaths()
        val onDisk = modelsDir.listFiles { f ->
            f.isFile && f.name.endsWith(".gguf", true) &&
                !f.name.startsWith("mmproj-", ignoreCase = true) &&
                !f.name.startsWith("tokenizer-", ignoreCase = true) &&
                !f.name.startsWith("vocoder-", ignoreCase = true) &&
                f.absolutePath !in writing &&
                isPlausibleGguf(f)
        }
            .orEmpty()

        onDisk.forEach { f ->
            if (index.values.none { it.path == f.absolutePath }) {
                index["file:${f.name}"] = InstalledModel(
                    id = "file:${f.name}",
                    repo = "",
                    file = f.name,
                    displayName = f.nameWithoutExtension,
                    params = guessParams(f.name),
                    quant = guessQuant(f.name),
                    sizeMb = f.length() / (1024 * 1024),
                    path = f.absolutePath,
                    installedAt = f.lastModified(),
                )
            }
        }

        val alive = index.values.filter { entry ->
            val f = File(entry.path)
            f.exists() && isPlausibleGguf(f)
        }.map { entry ->
            if (entry.mmprojPath != null) {
                entry
            } else {
                CompanionMap[entry.id]?.let { f -> File(modelsDir, fileNameFor(f)).takeIf { it.exists() } }
                    ?.let { entry.copy(mmprojPath = it.absolutePath) }
                    ?: entry
            }
        }
        // One file, one installed entry. If a catalog entry and an auto-detected
        // file entry share the same path, keep the catalog one.
        val byPath = LinkedHashMap<String, InstalledModel>()
        for (entry in alive) {
            val existing = byPath[entry.path]
            if (existing == null ||
                (existing.id.startsWith("file:") && !entry.id.startsWith("file:"))
            ) {
                byPath[entry.path] = entry
            }
        }
        val deduped = byPath.values.toList()
        _installed.value = deduped.sortedByDescending { it.installedAt }
        writeIndex(deduped)
    }

    @Synchronized
    fun download(
        id: String,
        name: String,
        params: String,
        quant: String,
        repo: String,
        file: String,
        sizeMb: Long,
        url: String,
        token: String,
        license: String?,
        mmprojFile: String? = null,
        mmprojSizeMb: Long = 0L,
    ): Boolean {
        // Never delete the installed copy: a failed or cancelled download must
        // leave the user with the model they already had. Downloads are staged
        // in a .part file and only promoted on success.
        if (pending.any { it.id == id || it.file.equals(file, true) }) return false
        val part = File(modelsDir, partNameFor(file))
        if (part.exists()) runCatching { part.delete() }
        mmprojFile?.let { mf ->
            val companionPart = File(modelsDir, partNameFor(mf))
            if (companionPart.exists()) runCatching { companionPart.delete() }
        }

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription(file)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "models", part.name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        if (token.isNotBlank()) request.addRequestHeader("Authorization", "Bearer $token")

        val downloadId = runCatching { dm.enqueue(request) }.getOrNull()
        if (downloadId == null) {
            _downloads.update {
                it + (id to DownloadProgress(id, 0, sizeMb * 1024 * 1024, DownloadProgress.Status.FAILED,
                    message = "could not start download"))
            }
            return false
        }

        pending.add(
            PendingDownload(
                downloadId = downloadId,
                id = id,
                repo = repo,
                file = file,
                displayName = name,
                params = params,
                quant = quant,
                sizeMb = sizeMb,
                license = license,
                mmprojRepo = repo.takeIf { mmprojFile != null },
                mmprojFile = mmprojFile,
                mmprojSizeMb = mmprojSizeMb,
            )
        )
        val mmprojRequest = mmprojFile?.let { mf -> buildRequest(repo, mf, name + " mmproj", mmprojSizeMb, token) }
        val companionId = mmprojRequest?.let { req -> runCatching { dm.enqueue(req) }.getOrNull() }
        mmprojFile?.let { mf ->
            pending.add(
                PendingDownload(
                    downloadId = companionId ?: 0L,
                    id = "$id#mmproj",
                    repo = repo,
                    file = mf,
                    displayName = "$name mmproj",
                    params = "",
                    quant = guessQuant(mf),
                    sizeMb = mmprojSizeMb,
                    license = license,
                )
            )
        }
        writePending()
        _downloads.update {
            it + (id to DownloadProgress(id, 0, sizeMb * 1024 * 1024, DownloadProgress.Status.PENDING))
        }
        return true
    }

    /** Builds the shared DownloadManager request for the mmproj companion. */
    private fun buildRequest(
        repo: String,
        file: String,
        title: String,
        sizeMb: Long,
        token: String,
    ): DownloadManager.Request {
        return DownloadManager.Request(Uri.parse(hfUrl(repo, file)))
            .setTitle(title)
            .setDescription(file)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "models", partNameFor(file))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .also { req ->
                if (token.isNotBlank()) req.addRequestHeader("Authorization", "Bearer $token")
            }
    }

    private fun hfUrl(repo: String, file: String): String =
        "https://huggingface.co/$repo/resolve/main/$file"

    /**
     * Moves a completed staged download into its final name. Older pending
     * records may still point at a download that wrote straight to the final
     * name, so that case is accepted too. Invalid files are never registered.
     */
    private fun promote(p: PendingDownload): File? {
        val final = File(modelsDir, fileNameFor(p.file))
        val part = File(modelsDir, partNameFor(p.file))
        if (part.exists()) {
            if (!isPlausibleGguf(part)) return null
            if (final.exists() && !final.delete()) return null
            if (!part.renameTo(final)) return null
        }
        return final.takeIf(::isPlausibleGguf)
    }

    /** Removes only files that belong to an unfinished download. */
    private fun discardPartial(p: PendingDownload) {
        runCatching { File(modelsDir, partNameFor(p.file)).delete() }
        val final = File(modelsDir, fileNameFor(p.file))
        if (final.exists() && !isPlausibleGguf(final)) runCatching { final.delete() }
    }

    /** Polls DownloadManager. Cheap enough to call on every UI tick. */
    @Synchronized
    fun sync() {
        if (pending.isEmpty()) return
        var pendingChanged = false

        for (p in pending.toList()) {
            val cursor = runCatching {
                dm.query(DownloadManager.Query().setFilterById(p.downloadId))
            }.getOrNull()

            if (cursor == null) {
                // Query failed (e.g. DownloadManager briefly unavailable).
                // Keep the record; the next tick will resolve it.
                continue
            }
            if (!cursor.moveToFirst()) {
                // Removed/cancelled outside the app. Drop the staged file and
                // the progress card instead of leaving a permanent "downloading".
                discardPartial(p)
                pending.remove(p)
                pendingChanged = true
                _downloads.update { it - p.id }
                continue
            }

            cursor.use { c ->
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    .coerceAtLeast(0)
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val complete = promote(p)
                        if (complete == null) {
                            discardPartial(p)
                            pending.remove(p)
                            pendingChanged = true
                            _downloads.update {
                                it + (
                                    p.id to DownloadProgress(
                                        p.id, bytes, total, DownloadProgress.Status.FAILED,
                                        message = "file missing or not a valid GGUF",
                                    )
                                    )
                            }
                        } else {
                            if (p.id.endsWith("#mmproj")) {
                                patchCompanionPath(p.file)
                            } else {
                                register(p)
                            }
                            pending.remove(p)
                            pendingChanged = true
                            _downloads.update {
                                it + (
                                    p.id to DownloadProgress(
                                        p.id, complete.length(), complete.length(),
                                        DownloadProgress.Status.DONE,
                                    )
                                    )
                            }
                        }
                    }

                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        discardPartial(p)
                        pending.remove(p)
                        pendingChanged = true
                        _downloads.update {
                            it + (
                                p.id to DownloadProgress(
                                    p.id, bytes, total, DownloadProgress.Status.FAILED,
                                    message = "code $reason",
                                )
                                )
                        }
                    }

                    else -> _downloads.update {
                        it + (p.id to DownloadProgress(p.id, bytes, total, DownloadProgress.Status.RUNNING))
                    }
                }
            }
        }

        if (pendingChanged) {
            writePending()
            refreshInstalled()
        }
    }

    @Synchronized
    fun delete(model: InstalledModel) {
        // A replacement download may be in flight; don't let it resurrect the
        // model right after the user deletes it.
        cancel(model.id)
        runCatching { File(model.path).delete() }
        runCatching { File(modelsDir, partNameFor(model.file)).delete() }
        // Remove the mmproj companion too, if one was downloaded for this model.
        CompanionMap[model.id]?.let { File(modelsDir, fileNameFor(it)).delete() }
        // Also drop the stored companion path.
        val index = readIndex().filterNot { it.id == model.id }
        writeIndex(index)
        refreshInstalled()
        _downloads.update { it - model.id }
    }

    @Synchronized
    fun cancel(id: String) {
        var changed = false
        listOf(id, "$id#mmproj").forEach { key ->
            val p = pending.firstOrNull { it.id == key }
            if (p != null) {
                runCatching { dm.remove(p.downloadId) }
                discardPartial(p)
                pending.remove(p)
                changed = true
            }
            // Also clear finished FAILED/DONE cards so the button is not stuck.
            if (_downloads.value.containsKey(key)) {
                _downloads.update { it - key }
                changed = true
            }
        }
        if (changed) {
            writePending()
            refreshInstalled()
        }
    }

    /** Records where the mmproj companion for a catalog model lives, if it exists. */
    private fun patchCompanionPath(mmprojFileName: String) {
        val companion = File(modelsDir, fileNameFor(mmprojFileName))
        if (!companion.exists()) return
        val updated = readIndex().map { entry ->
            if (entry.mmprojPath == null && CompanionMap[entry.id] == mmprojFileName) {
                entry.copy(mmprojPath = companion.absolutePath)
            } else {
                entry
            }
        }
        writeIndex(updated)
    }

    private fun register(p: PendingDownload) {
        val target = File(modelsDir, fileNameFor(p.file))
        if (!target.exists()) return

        val mmprojPath = CompanionMap[p.id]?.let { mmproj -> File(modelsDir, fileNameFor(mmproj)) }
            ?.takeIf { it.exists() }
            ?.absolutePath

        val entry = InstalledModel(
            id = p.id,
            repo = p.repo,
            file = p.file,
            displayName = p.displayName,
            params = p.params,
            quant = p.quant,
            sizeMb = target.length() / (1024 * 1024),
            path = target.absolutePath,
            license = p.license,
            installedAt = System.currentTimeMillis(),
            mmprojPath = mmprojPath,
        )
        val index = readIndex().filterNot { it.id == entry.id } + entry
        writeIndex(index)
    }

    private fun readIndex(): List<InstalledModel> =
        runCatching {
            if (indexFile.exists()) json.decodeFromString<List<InstalledModel>>(indexFile.readText())
            else emptyList()
        }.getOrElse {
            // Keep the unreadable copy before auto-detection rewrites the index.
            if (indexFile.length() > 2) {
                runCatching {
                    indexFile.copyTo(File(context.filesDir, "models-index.unreadable.json"), overwrite = true)
                }
            }
            emptyList()
        }

    private fun writeIndex(list: List<InstalledModel>) {
        runCatching { indexFile.writeText(json.encodeToString(list)) }
    }

    private fun readPending(): List<PendingDownload> =
        runCatching {
            if (pendingFile.exists()) json.decodeFromString<List<PendingDownload>>(pendingFile.readText())
            else emptyList()
        }.getOrDefault(emptyList())

    private fun writePending() {
        runCatching { pendingFile.writeText(json.encodeToString(pending.toList())) }
    }

    private fun guessQuant(name: String): String {
        val upper = name.uppercase()
        return Regex("Q\\d+_K_[MS]|Q\\d+_\\d|IQ\\d+_\\w+|F16|BF16")
            .find(upper)?.value ?: "GGUF"
    }

    private fun guessParams(name: String): String {
        return Regex("(\\d+\\.?\\d*)B", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.let { "${it}B" } ?: ""
    }
}
