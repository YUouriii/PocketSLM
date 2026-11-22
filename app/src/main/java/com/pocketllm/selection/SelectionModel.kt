package com.pocketllm.selection

import com.pocketllm.hardware.HardwareProfile
import com.pocketllm.hardware.TriState

/** /sdcard and /storage/emulated/0 are the same path on Android. */
fun sameModelPath(a: String?, b: String?): Boolean {
    if (a == null || b == null) return a == b
    fun normalize(path: String) = path.replace('\\', '/').replace("/sdcard/", "/storage/emulated/0/")
    return normalize(a) == normalize(b)
}

/** Backend candidates the engine may choose. CPU is always the safe fallback. */
enum class Backend { CPU, VULKAN }

/**
 * Why a backend cannot be used. NOT_BUILT is deliberately different from
 * hardware that does not support Vulkan: the APK simply has no Vulkan backend.
 */
enum class BackendStatus { AVAILABLE, NOT_BUILT, UNAVAILABLE, INCOMPATIBLE, UNKNOWN }

enum class ModelFit { GOOD_FIT, TIGHT_FIT, NOT_RECOMMENDED, UNSUPPORTED, UNKNOWN }

enum class Confidence { HIGH, MEDIUM, LOW }

enum class DecisionBasis { ESTIMATE, MEASURED }

enum class MetadataSource { CATALOG, FILE_DETECTED, UNKNOWN }

/**
 * Model facts used for selection. Fields that cannot be trusted are null:
 * in particular no architecture or quantisation is ever read from a filename.
 */
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val path: String,
    val fileSizeBytes: Long?,
    val paramsB: Double?,
    val quantization: String?,
    val architecture: String?,
    val contextLength: Int?,
    val backendSupport: Map<Backend, TriState>,
    val source: MetadataSource,
    /** Curated minimum RAM for this model, when the catalog knows it. */
    val minRamBytes: Long? = null,
)

data class BackendAssessment(
    val backend: Backend,
    val status: BackendStatus,
    val reasons: List<String>,
)

/** One real benchmark record, or null fields when not available. */
data class MeasurementRecord(
    val device: String?,
    val abi: String?,
    val modelPath: String?,
    val contextSize: Int?,
    val kvQuant: String?,
    val threads: Int?,
    val backend: Backend?,
    val tokensPerSecond: Double?,
    val gpuLayers: Int?,
    val status: String?,
    /** Resident memory observed on this device, in MiB. */
    val rssMb: Long? = null,
    /** High-water mark observed on this device, in MiB. */
    val hwmMb: Long? = null,
    val loadMs: Long? = null,
    val ttftMs: Long? = null,
    val quantization: String? = null,
)

interface MeasurementProvider {
    fun records(): List<MeasurementRecord>
}

object EmptyMeasurements : MeasurementProvider {
    override fun records(): List<MeasurementRecord> = emptyList()
}

data class SelectionRequest(
    val profile: HardwareProfile?,
    val candidates: List<ModelDescriptor>,
    val contextSize: Int,
    val kvQuant: String,
    val threads: Int,
    /** Explicit user choice. Never overridden by automatic selection. */
    val manualModelId: String? = null,
    val manualBackend: Backend? = null,
    /** -1 means no explicit GPU-layer override. */
    val manualGpuLayers: Int = -1,
    val measurements: MeasurementProvider = EmptyMeasurements,
    val performancePreference: String = "recommended",
)

data class Alternative(
    val modelId: String,
    val fit: ModelFit,
    val reason: String,
)

data class SelectionResult(
    val model: ModelDescriptor?,
    val backend: Backend?,
    val gpuLayers: Int,
    val fit: ModelFit,
    val confidence: Confidence,
    val basis: DecisionBasis,
    val reasons: List<String>,
    val warnings: List<String>,
    val missingInformation: List<String>,
    val alternatives: List<Alternative>,
    val backendStatuses: List<BackendAssessment>,
    val requiresUserChoice: Boolean,
    val manualOverride: Boolean,
) {
    /** Ready only when a model, backend and usable fit were all determined. */
    val canLoad: Boolean get() = model != null && backend != null && !requiresUserChoice &&
        fit != ModelFit.UNKNOWN && fit != ModelFit.UNSUPPORTED
}
