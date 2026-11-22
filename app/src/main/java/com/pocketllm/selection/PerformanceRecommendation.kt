package com.pocketllm.selection

import com.pocketllm.hardware.HardwareProfile

/** Speed categories are based on measured tokens/second, never on parameter count. */
enum class PerformanceCategory { SMOOTH, USABLE, SLOW, UNKNOWN }

data class PerformanceRecommendation(
    val category: PerformanceCategory,
    val tokensPerSecond: Double?,
    val ttftMs: Long?,
    val memoryBytes: Long?,
    val evidence: DecisionBasis,
    val confidence: Confidence,
    val warnings: List<String>,
    /** True when the measurement was recorded with a different KV/thread config. */
    val approximate: Boolean = false,
) {
    val isMeasured: Boolean get() = evidence == DecisionBasis.MEASURED && tokensPerSecond != null
}

object PerformanceAnalyzer {

    private const val MIB = 1024L * 1024L

    /** Boundaries are inclusive at the lower end: 3 tok/s is usable, 6 is smooth. */
    fun categorize(tokensPerSecond: Double?): PerformanceCategory = when {
        tokensPerSecond == null || tokensPerSecond.isNaN() || tokensPerSecond <= 0.0 -> PerformanceCategory.UNKNOWN
        tokensPerSecond >= 6.0 -> PerformanceCategory.SMOOTH
        tokensPerSecond >= 3.0 -> PerformanceCategory.USABLE
        else -> PerformanceCategory.SLOW
    }

    fun recommend(
        model: ModelDescriptor,
        profile: HardwareProfile?,
        contextSize: Int,
        kvQuant: String,
        threads: Int,
        backend: Backend?,
        measurements: List<MeasurementRecord>,
    ): PerformanceRecommendation {
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
                (model.quantization == null || record.quantization == null ||
                    record.quantization.equals(model.quantization, ignoreCase = true)) &&
                (backend == null || record.backend == backend) &&
                (record.contextSize == null || record.contextSize == contextSize) &&
                (record.kvQuant == null || record.kvQuant == kvQuant) &&
                (record.threads == null || record.threads == threads) &&
                record.tokensPerSecond != null && record.tokensPerSecond > 0.0
        }

        val used = matching.ifEmpty {
            if (deviceAliases.isEmpty() || abi == null) emptyList() else measurements.filter { record ->
                (record.status.equals("ok", ignoreCase = true) || record.status.equals("complete", ignoreCase = true)) &&
                    deviceAliases.any { it.equals(record.device, ignoreCase = true) } &&
                    record.abi == abi &&
                    sameModelPath(record.modelPath, model.path) &&
                    (model.quantization == null || record.quantization == null ||
                        record.quantization.equals(model.quantization, ignoreCase = true)) &&
                    (backend == null || record.backend == backend) &&
                    (record.contextSize == null || record.contextSize == contextSize) &&
                    record.tokensPerSecond != null && record.tokensPerSecond > 0.0
            }
        }
        val configDiffers = matching.isEmpty() && used.isNotEmpty()

        fun medianDoubles(values: List<Double>): Double? = values.sorted().takeIf { it.isNotEmpty() }
            ?.let { it[it.size / 2] }
        fun medianLongs(values: List<Long>): Long? = values.sorted().takeIf { it.isNotEmpty() }
            ?.let { it[it.size / 2] }

        val tokens = medianDoubles(used.mapNotNull { it.tokensPerSecond })
        val ttft = medianLongs(used.mapNotNull { it.ttftMs?.takeIf { v -> v > 0 } })
        val memory = medianLongs(used.mapNotNull { it.hwmMb?.takeIf { v -> v > 0 } })
            ?.times(MIB)
            ?: medianLongs(used.mapNotNull { it.rssMb?.takeIf { v -> v > 0 } })?.times(MIB)

        val thermalState = profile?.runtime?.thermalStatusName
        val warnings = mutableListOf<String>()
        if (thermalState != null && thermalState !in setOf("NONE", "LIGHT")) {
            warnings += "Device is $thermalState; measured speed may be lower"
        }
        if (used.isEmpty()) warnings += "No matching on-device measurement"
        if (configDiffers) warnings += "Measurement config differs (KV/threads); treat speed as approximate"

        return PerformanceRecommendation(
            category = categorize(tokens),
            tokensPerSecond = tokens,
            ttftMs = ttft,
            memoryBytes = memory,
            evidence = if (tokens != null) DecisionBasis.MEASURED else DecisionBasis.ESTIMATE,
            confidence = when {
                tokens == null -> Confidence.LOW
                configDiffers -> Confidence.MEDIUM
                ttft != null -> Confidence.HIGH
                else -> Confidence.MEDIUM
            },
            warnings = warnings,
            approximate = configDiffers,
        )
    }

    /** Preference affects ordering and emphasis only; no model is hidden or disabled. */
    fun priority(recommendation: PerformanceRecommendation, preference: String): Int {
        val base = when (recommendation.category) {
            PerformanceCategory.SMOOTH -> 3
            PerformanceCategory.USABLE -> 2
            PerformanceCategory.SLOW -> 1
            PerformanceCategory.UNKNOWN -> 0
        }
        return when (preference) {
            "recommended" -> base
            "balanced" -> if (base >= 2) base + 1 else base
            else -> 0
        }
    }
}
