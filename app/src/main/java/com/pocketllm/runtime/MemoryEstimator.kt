package com.pocketllm.runtime

import com.pocketllm.hardware.HardwareProfile
import com.pocketllm.selection.Backend
import com.pocketllm.selection.Confidence
import com.pocketllm.selection.MeasurementRecord
import com.pocketllm.selection.ModelDescriptor
import com.pocketllm.selection.sameModelPath

enum class MemoryEvidence { MEASURED, METADATA, CONSERVATIVE, UNKNOWN }

/**
 * Memory decomposition. Null means "not known", never zero. In particular no
 * Transformer KV formula is applied: the app has no reliable architecture
 * metadata or per-layer dimensions before loading, and hybrid/recurrent models
 * do not follow that formula.
 */
data class ModelMemoryBreakdown(
    val weightsBytes: Long?,
    val kvCacheBytes: Long?,
    val computeBuffersBytes: Long?,
    val graphOverheadBytes: Long?,
    val peakLoadingBytes: Long?,
    val steadyStateBytes: Long?,
    val budgetBytes: Long?,
    val evidence: MemoryEvidence,
    val confidence: Confidence,
    val uncertainties: List<String>,
) {
    companion object {
        const val MIB = 1024L * 1024L
    }
}

/**
 * Estimates memory for one load. Measured RSS/high-water marks from this app on
 * this device take precedence; otherwise file size plus conservative runtime
 * reserves are used and clearly marked as estimates.
 */
class MemoryEstimator {

    fun estimate(
        model: ModelDescriptor,
        profile: HardwareProfile?,
        snapshot: MemorySnapshot,
        contextSize: Int,
        kvQuant: String,
        measurements: List<MeasurementRecord>,
        backend: Backend?,
    ): ModelMemoryBreakdown {
        val uncertainties = mutableListOf<String>()
        val deviceAliases = listOfNotNull(
            profile?.capabilities?.soc?.let { listOfNotNull(it.manufacturer, it.model).joinToString(" ") }
                ?.takeIf { it.isNotBlank() },
            profile?.capabilities?.soc?.deviceName?.takeIf { it.isNotBlank() },
        ).toSet()
        val abi = profile?.capabilities?.cpu?.primaryAbi
        val matching = if (deviceAliases.isEmpty() || abi == null) emptyList() else measurements.filter { record ->
            (record.status.equals("ok", ignoreCase = true) || record.status.equals("complete", ignoreCase = true)) &&
                deviceAliases.any { it.equals(record.device, ignoreCase = true) } &&
                record.abi == abi &&
                sameModelPath(record.modelPath, model.path) &&
                (record.contextSize == null || record.contextSize == contextSize) &&
                (record.kvQuant == null || record.kvQuant == kvQuant) &&
                (backend == null || record.backend == backend)
        }

        fun median(values: List<Long>): Long? = values.sorted().takeIf { it.isNotEmpty() }
            ?.let { it[it.size / 2] }

        val measuredSteady = median(matching.mapNotNull { it.rssMb?.takeIf { v -> v > 0 } })
            ?.times(ModelMemoryBreakdown.MIB)
        val measuredPeak = median(matching.mapNotNull { it.hwmMb?.takeIf { v -> v > 0 } })
            ?.times(ModelMemoryBreakdown.MIB)

        val weights = model.fileSizeBytes?.takeIf { it > 0 }
        if (weights == null) uncertainties += "Model file size unknown"

        val contextBlocks = (contextSize.toLong() + 2047L) / 2048L
        val graph = 256L * ModelMemoryBreakdown.MIB
        val compute = (384L + 128L * contextBlocks) * ModelMemoryBreakdown.MIB
        val estimatedSteady = weights?.let { it + graph + compute }
        val estimatedPeak = estimatedSteady?.let { it + (it * 0.25f).toLong() + 128L * ModelMemoryBreakdown.MIB }

        val steady = measuredSteady ?: estimatedSteady
        val peak = measuredPeak?.let { maxOf(it, measuredSteady ?: 0L) } ?: estimatedPeak
        val budget = peak?.let { (it * 1.10f).toLong() }

        if (steady == null || peak == null || budget == null) {
            uncertainties += "Cannot estimate memory without a model size or matching measurement"
        }
        if (measuredSteady == null) uncertainties += "No matching on-device RSS measurement"
        if (measuredPeak == null) uncertainties += "No matching on-device peak measurement"
        uncertainties += "KV-cache/compute decomposition unavailable without GGUF metadata"
        if (model.architecture == null) uncertainties += "Model architecture unknown (no KV formula applied)"
        if (model.quantization == null) uncertainties += "Quantization unknown"
        if (snapshot.isLowMemory) uncertainties += "Android reported low-memory state"

        val measured = measuredSteady != null || measuredPeak != null
        val evidence = when {
            measured && measuredSteady != null && measuredPeak != null -> MemoryEvidence.MEASURED
            measured -> MemoryEvidence.MEASURED
            weights != null -> MemoryEvidence.CONSERVATIVE
            else -> MemoryEvidence.UNKNOWN
        }
        val confidence = when {
            evidence == MemoryEvidence.MEASURED && uncertainties.size <= 4 -> Confidence.HIGH
            evidence == MemoryEvidence.MEASURED -> Confidence.MEDIUM
            weights != null && model.quantization != null -> Confidence.MEDIUM
            weights != null -> Confidence.LOW
            else -> Confidence.LOW
        }

        return ModelMemoryBreakdown(
            weightsBytes = weights,
            kvCacheBytes = null,
            computeBuffersBytes = if (measured) null else compute,
            graphOverheadBytes = if (measured) null else graph,
            peakLoadingBytes = peak,
            steadyStateBytes = steady,
            budgetBytes = budget,
            evidence = evidence,
            confidence = confidence,
            uncertainties = uncertainties,
        )
    }
}
