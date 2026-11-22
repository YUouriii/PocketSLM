package com.pocketllm.ui.models

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketllm.Graph
import com.pocketllm.hardware.HardwareRegistry
import com.pocketllm.core.DeviceProfile
import com.pocketllm.data.SettingsState
import com.pocketllm.selection.Backend
import com.pocketllm.selection.PerformanceAnalyzer
import com.pocketllm.selection.PerformanceRecommendation
import com.pocketllm.selection.SelectionCoordinator
import com.pocketllm.data.CatalogModel
import com.pocketllm.data.DownloadProgress
import com.pocketllm.data.Fit
import com.pocketllm.data.HfFile
import com.pocketllm.data.HfRepo
import com.pocketllm.data.InstalledModel
import com.pocketllm.data.fit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class ModelFilter { RECOMMENDED, ALL, INSTALLED }

data class RepoDetailState(
    val repo: HfRepo,
    val files: List<HfFile> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val license: String? = null,
)

data class ModelsUiState(
    val device: DeviceProfile = Graph.device,
    val installed: List<InstalledModel> = emptyList(),
    val downloads: Map<String, DownloadProgress> = emptyMap(),
    val activeId: String? = null,
    val filter: ModelFilter = ModelFilter.RECOMMENDED,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<HfRepo> = emptyList(),
    val searchRan: Boolean = false,
    val searchError: String? = null,
    val detail: RepoDetailState? = null,
    val deleteTarget: InstalledModel? = null,
    val message: String? = null,
    val performance: Map<String, PerformanceRecommendation> = emptyMap(),
    val performancePreference: String = "recommended",
) {
    fun fitOf(model: CatalogModel): Fit = model.fit(device.totalRamMb)
}

class ModelsViewModel : ViewModel() {

    private val models = Graph.models
    private val settings = Graph.settings
    private val hf = Graph.hf

    private val _state = MutableStateFlow(ModelsUiState(activeId = settings.current.activeModelId))
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            models.installed.collect { list ->
                _state.update { current ->
                    val performance = performanceMap(list, current.performancePreference)
                    current.copy(
                        installed = orderInstalled(list, performance, current.performancePreference),
                        performance = performance,
                    )
                }
            }
        }
        viewModelScope.launch {
            models.downloads.collect { map -> _state.update { it.copy(downloads = map) } }
        }
        viewModelScope.launch {
            settings.state.collect { s ->
                _state.update {
                    val performance = performanceMap(it.installed, s.performancePreference)
                    it.copy(
                        activeId = s.activeModelId,
                        performancePreference = s.performancePreference,
                        installed = orderInstalled(it.installed, performance, s.performancePreference),
                        performance = performance,
                    )
                }
            }
        }
        // Only poll the system downloader while a download is actually in flight.
        viewModelScope.launch {
            models.downloads.collect { map ->
                val active = map.values.any {
                    it.status == DownloadProgress.Status.PENDING ||
                        it.status == DownloadProgress.Status.RUNNING
                }
                if (active && (syncJob == null || syncJob?.isCancelled == true)) {
                    syncJob = launch {
                        while (isActive) {
                            withContext(Dispatchers.IO) { models.sync() }
                            delay(800)
                        }
                    }
                } else if (!active) {
                    syncJob?.cancel()
                    syncJob = null
                }
            }
        }
        // A download started before this screen existed may still be pending.
        models.sync()
    }

    private var syncJob: kotlinx.coroutines.Job? = null

    override fun onCleared() {
        syncJob?.cancel()
        super.onCleared()
    }

    fun setFilter(filter: ModelFilter) = _state.update { it.copy(filter = filter) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return

        _state.update { it.copy(searching = true, searchError = null, searchRan = true) }
        viewModelScope.launch {
            runCatching { hf.search(query, settings.current.hfToken) }
                .onSuccess { results ->
                    _state.update { it.copy(results = results, searching = false) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(searching = false, results = emptyList(), searchError = e.message)
                    }
                }
        }
    }

    fun clearSearch() = _state.update {
        it.copy(query = "", results = emptyList(), searchRan = false, searchError = null)
    }

    private var repoRequestId = 0

    fun openRepo(repo: HfRepo) {
        val requestId = ++repoRequestId
        _state.update { it.copy(detail = RepoDetailState(repo)) }
        viewModelScope.launch {
            runCatching { hf.detail(repo.id, settings.current.hfToken) }
                .onSuccess { detail ->
                    if (requestId != repoRequestId) return@onSuccess
                    _state.update {
                        it.copy(
                            detail = RepoDetailState(
                                repo = repo,
                                files = detail.files,
                                loading = false,
                                license = detail.license,
                            )
                        )
                    }
                }
                .onFailure { e ->
                    if (requestId != repoRequestId) return@onFailure
                    _state.update {
                        it.copy(detail = it.detail?.copy(loading = false, error = e.message))
                    }
                }
        }
    }

    fun closeRepo() {
        repoRequestId++
        _state.update { it.copy(detail = null) }
    }

    fun downloadCatalog(model: CatalogModel) {
        models.download(
            id = model.id,
            name = model.name,
            params = model.params,
            quant = model.quant,
            repo = model.repo,
            file = model.file,
            sizeMb = model.sizeMb,
            url = hf.downloadUrl(model.repo, model.file),
            token = settings.current.hfToken,
            license = model.license,
            mmprojFile = model.mmprojFile,
            mmprojSizeMb = model.mmprojSizeMb,
        )
    }

    fun downloadRepoFile(repo: HfRepo, file: HfFile) {
        val shortName = repo.id.substringAfterLast('/')
        val name = shortName.replace("-GGUF", "").replace("_", " ")
        val params = Regex("(\\d+\\.?\\d*)B", RegexOption.IGNORE_CASE)
            .find(shortName)?.groupValues?.get(1)?.let { "${it}B" } ?: ""
        val quant = Regex("Q\\d+_K_[MS]|Q\\d+_\\d|IQ\\d+_\\w+|F16|BF16")
            .find(file.name.uppercase())?.value ?: "GGUF"

        models.download(
            id = "${repo.id}|${file.name}",
            name = name,
            params = params,
            quant = quant,
            repo = repo.id,
            file = file.name,
            sizeMb = file.sizeMb,
            url = hf.downloadUrl(repo.id, file.name),
            token = settings.current.hfToken,
            license = _state.value.detail?.license,
        )
        closeRepo()
    }

    fun use(model: InstalledModel) {
        settings.update { it.copy(activeModelId = model.id) }
    }

    fun isActive(model: InstalledModel): Boolean = _state.value.activeId == model.id

    fun askDelete(model: InstalledModel) = _state.update { it.copy(detail = null, message = null, deleteTarget = model) }

    fun cancelDelete() = _state.update { it.copy(deleteTarget = null) }

    fun confirmDelete() {
        val target = _state.value.deleteTarget ?: return
        models.delete(target)
        if (_state.value.activeId == target.id) {
            settings.update { it.copy(activeModelId = null) }
        }
        _state.update { it.copy(deleteTarget = null) }
    }

    fun cancelDownload(id: String) = models.cancel(id)

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun performanceMap(
        installed: List<InstalledModel>,
        @Suppress("UNUSED_PARAMETER") preference: String,
    ): Map<String, PerformanceRecommendation> {
        val s = settings.current
        val profile = HardwareRegistry.current()
        val backend = if (s.gpuLayers > 0) Backend.VULKAN else Backend.CPU
        val records = Graph.measurements.records()
        return SelectionCoordinator.descriptors(installed).associate { descriptor ->
            val recommendation = PerformanceAnalyzer.recommend(
                model = descriptor,
                profile = profile,
                contextSize = s.contextSize,
                kvQuant = s.kvQuant,
                threads = s.threads,
                backend = backend,
                measurements = records,
            )
            if (recommendation.isMeasured) {
                Log.i(
                    "Performance",
                    "${descriptor.id} ${recommendation.category} " +
                        "${recommendation.tokensPerSecond} tok/s ttft=${recommendation.ttftMs}ms",
                )
            }
            descriptor.id to recommendation
        }
    }

    fun catalogFor(filter: ModelFilter): List<CatalogModel> = when (filter) {
        ModelFilter.RECOMMENDED -> rankRecommended(
            com.pocketllm.data.Catalog.recommended(_state.value.device.totalRamMb),
        )
        ModelFilter.ALL -> com.pocketllm.data.Catalog.starter
        ModelFilter.INSTALLED -> emptyList()
    }

    /**
     * Measured speed wins first; unmeasured models keep the comfortable
     * largest-class-first order. No model is removed here.
     */
    private fun orderInstalled(
        installed: List<InstalledModel>,
        performance: Map<String, PerformanceRecommendation>,
        preference: String,
    ): List<InstalledModel> = installed.sortedWith(
        compareByDescending<InstalledModel> { model ->
            performance[model.id]?.let { PerformanceAnalyzer.priority(it, preference) } ?: -1
        }.thenBy { it.displayName.lowercase(Locale.US) },
    )

    private fun rankRecommended(models: List<CatalogModel>): List<CatalogModel> {
        val performance = _state.value.performance
        val preference = _state.value.performancePreference
        return models.sortedWith(
            compareByDescending<CatalogModel> { model ->
                performance[model.id]?.let { PerformanceAnalyzer.priority(it, preference) } ?: -1
            }.thenByDescending { it.minRamMb }.thenBy { it.sizeMb },
        )
    }

    fun installedFor(id: String, file: String): InstalledModel? =
        _state.value.installed.firstOrNull { it.id == id || it.file.equals(file, true) }

    fun progressFor(id: String): DownloadProgress? = _state.value.downloads[id]

    fun isDownloading(model: InstalledModel): Boolean = models.isDownloading(model.file, model.id)
}

/** Small helpers shared with the UI. */
fun bytesLabel(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    else -> String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024))
}
