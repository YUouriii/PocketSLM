package com.pocketllm.selection

import android.content.Context
import com.pocketllm.data.Catalog
import com.pocketllm.data.InstalledModel
import com.pocketllm.data.SettingsState
import com.pocketllm.hardware.HardwareRegistry

/** Maps existing app data into typed selection input and runs the pure engine. */
object SelectionCoordinator {

    private val engine = ModelSelectionEngine()
    private val MIB = 1024L * 1024L
    private val PARAMS = Regex("(\\d+(?:\\.\\d+)?)\\s*[Bb]")

    fun descriptors(installed: List<InstalledModel>): List<ModelDescriptor> {
        val catalog = Catalog.starter
        return installed.map { model ->
            // Only catalog metadata is trusted. Auto-detected files carry
            // filename guesses in InstalledModel, which must not be used here.
            val entry = catalog.firstOrNull { it.id == model.id }
                ?: catalog.firstOrNull { it.repo == model.repo && it.file == model.file }
            ModelDescriptor(
                id = model.id,
                displayName = model.displayName,
                path = model.path,
                fileSizeBytes = model.sizeMb.takeIf { it > 0 }?.times(MIB),
                paramsB = entry?.params?.let { PARAMS.find(it)?.groupValues?.get(1)?.toDoubleOrNull() },
                quantization = entry?.quant?.ifBlank { null },
                architecture = null,
                contextLength = null,
                backendSupport = emptyMap(),
                source = if (entry != null) MetadataSource.CATALOG else MetadataSource.FILE_DETECTED,
                minRamBytes = entry?.minRamMb?.takeIf { it > 0 }?.times(MIB),
            )
        }
    }

    fun select(
        context: Context,
        settings: SettingsState,
        installed: List<InstalledModel>,
        measurements: MeasurementProvider = BenchmarkMeasurements(context),
    ): SelectionResult = engine.select(
        SelectionRequest(
            profile = HardwareRegistry.current(),
            candidates = descriptors(installed),
            contextSize = settings.contextSize,
            kvQuant = settings.kvQuant,
            threads = settings.threads,
            manualModelId = settings.activeModelId,
            manualBackend = if (settings.gpuLayers > 0) Backend.VULKAN else null,
            manualGpuLayers = settings.gpuLayers.takeIf { it >= 0 } ?: -1,
            measurements = measurements,
            performancePreference = settings.performancePreference,
        )
    )
}
