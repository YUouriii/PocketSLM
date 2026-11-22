package com.pocketllm.selection

import com.pocketllm.data.comfortBudgetBytes
import com.pocketllm.hardware.HardwareProfile
import kotlin.math.roundToLong

data class FitAssessment(
    val fit: ModelFit,
    val basis: DecisionBasis,
    val estimatedNeedBytes: Long?,
    val reasons: List<String>,
    val warnings: List<String>,
    val missingInformation: List<String>,
    val confidence: Confidence,
)

/**
 * Conservative, documented estimate. File size is real metadata; runtime
 * overhead is an estimate because GGUF layer/KV dimensions are not exposed
 * before loading. Swap/ZRAM/vendor extension are never counted as RAM.
 */
object ModelFitEvaluator {

    private const val MIB = 1024L * 1024L
    private const val BASE_OVERHEAD_MIB = 700L
    private const val CONTEXT_OVERHEAD_MIB = 128L

    fun evaluate(
        descriptor: ModelDescriptor,
        profile: HardwareProfile?,
        contextSize: Int,
        backendStatus: BackendStatus? = null,
    ): FitAssessment {
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val size = descriptor.fileSizeBytes
        if (size == null || size <= 0) {
            return FitAssessment(
                ModelFit.UNKNOWN, DecisionBasis.ESTIMATE, null,
                listOf("Model size unknown"), listOf("Cannot estimate fit without a file size"),
                listOf("file size"), Confidence.LOW,
            )
        }

        val contextBlocks = (contextSize.toLong() + 2047L) / 2048L
        val overhead = (BASE_OVERHEAD_MIB + CONTEXT_OVERHEAD_MIB * contextBlocks) * MIB
        val curated = descriptor.minRamBytes?.takeIf { it > 0 }
        val need = maxOf(size + overhead, curated ?: 0L)
        reasons += "File ${mib(size)} MiB + estimated runtime/KV overhead ${mib(overhead)} MiB"
        reasons += "Need estimate ${mib(need)} MiB for context $contextSize"
        if (curated != null) {
            reasons += "Catalog minimum ${mib(curated)} MiB RAM for this model"
        }

        if (descriptor.quantization == null) missing += "quantization"
        if (descriptor.paramsB == null) missing += "parameter count"
        if (descriptor.architecture == null) missing += "model architecture"
        if (descriptor.contextLength == null) missing += "model context capability"
        if (missing.isNotEmpty()) warnings += "Estimate uncertainty: ${missing.joinToString(", ")} unknown"
        if (profile?.capabilities?.memory?.kernelSwapCapacityBytes != null) {
            warnings += "Kernel swap capacity excluded from physical RAM"
        }

        if (backendStatus == BackendStatus.NOT_BUILT || backendStatus == BackendStatus.INCOMPATIBLE) {
            warnings += "Selected backend unavailable; CPU fit estimate used"
        }

        val heap = profile?.runtime?.appHeapLimitBytes
        if (heap != null && need > heap) {
            // GGUF weights/KV live in native memory, outside the Dalvik heap.
            // Record the limit as context, not as a hard rejection.
            warnings += "Need exceeds Dalvik heap limit (${mib(heap)} MiB); native allocations are outside the JVM heap"
        }

        val physical = profile?.capabilities?.memory?.totalBytes
        if (physical == null || physical <= 0) {
            return FitAssessment(
                ModelFit.UNKNOWN, DecisionBasis.ESTIMATE, need,
                reasons + "Physical RAM unavailable",
                warnings, missing + "physical RAM", Confidence.LOW,
            )
        }

        val available = profile.runtime.availableMemoryBytes
        val fit = when {
            need > physical * 0.90 -> {
                reasons += "Need >90% of physical RAM (${mib(physical)} MiB)"
                ModelFit.NOT_RECOMMENDED
            }

            need > comfortBudgetBytes(physical) -> {
                reasons += "Need > comfortable RAM budget (${mib(physical)} MiB physical)"
                ModelFit.TIGHT_FIT
            }

            available != null && need > available -> {
                reasons += "Need exceeds currently available RAM (${mib(available)} MiB; caches may be reclaimable)"
                ModelFit.TIGHT_FIT
            }

            else -> {
                reasons += "Need within conservative physical-RAM budget"
                ModelFit.GOOD_FIT
            }
        }
        val confidence = when {
            missing.size >= 3 -> Confidence.LOW
            missing.isNotEmpty() -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }.takeIf { fit != ModelFit.UNKNOWN } ?: Confidence.LOW

        return FitAssessment(fit, DecisionBasis.ESTIMATE, need, reasons, warnings, missing, confidence)
    }

    private fun mib(bytes: Long): Long = (bytes.toDouble() / MIB).roundToLong()
}
