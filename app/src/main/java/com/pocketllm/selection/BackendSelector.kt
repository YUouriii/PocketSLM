package com.pocketllm.selection

import com.pocketllm.hardware.HardwareProfile
import com.pocketllm.hardware.TriState
import kotlin.math.max

/** Backend decision plus the evidence/warnings that must reach SelectionResult. */
data class BackendChoice(
    val backend: Backend?,
    val reasons: List<String>,
    val warnings: List<String>,
)

/**
 * Backend decisions use runtime evidence only. A platform Vulkan feature flag or
 * a Vulkan-capable GPU is never enough: the probe must actually pass, and the
 * backend must exist in this APK.
 */
object BackendSelector {

    fun assess(profile: HardwareProfile?): List<BackendAssessment> {
        val cpu = if (profile != null && (profile.capabilities.cpu.processorCount ?: 0) > 0) {
            BackendAssessment(
                Backend.CPU,
                BackendStatus.AVAILABLE,
                listOf("CPU reported ${profile.capabilities.cpu.processorCount} processors"),
            )
        } else {
            BackendAssessment(Backend.CPU, BackendStatus.UNKNOWN, listOf("Processor count unavailable"))
        }

        val graphics = profile?.capabilities?.graphics
        val vulkan = when {
            graphics == null -> BackendAssessment(
                Backend.VULKAN, BackendStatus.UNKNOWN, listOf("Hardware profile unavailable"),
            )

            graphics.vulkanReason.equals("no_vulkan_backend", ignoreCase = true) -> BackendAssessment(
                Backend.VULKAN,
                BackendStatus.NOT_BUILT,
                listOf("Vulkan backend is not compiled into this APK", "Hardware GPU support is unaffected"),
            )

            graphics.vulkanReason.equals("probe_failed", ignoreCase = true) ||
                graphics.vulkanReason.equals("registry_uninitialized", ignoreCase = true) -> BackendAssessment(
                Backend.VULKAN, BackendStatus.UNKNOWN, listOf("Vulkan probe did not produce a result"),
            )

            !graphics.vulkanProbeAvailable -> BackendAssessment(
                Backend.VULKAN,
                BackendStatus.UNAVAILABLE,
                listOf("Vulkan loader/driver not reported by the existing probe", graphics.vulkanReason),
            )

            !graphics.vulkanProbeCompatible -> BackendAssessment(
                Backend.VULKAN,
                BackendStatus.INCOMPATIBLE,
                listOfNotNull(
                    "Vulkan probe incompatible: ${graphics.vulkanReason.ifBlank { "requirements missing" }}".takeIf {
                        graphics.vulkanReason.isNotBlank()
                    },
                    graphics.vulkanMissing.takeIf { it.isNotBlank() }?.let { "Missing: $it" },
                ),
            )

            else -> BackendAssessment(
                Backend.VULKAN,
                BackendStatus.AVAILABLE,
                listOf("Vulkan probe compatible (${graphics.vulkanDevice})"),
            )
        }
        return listOf(cpu, vulkan)
    }

    /**
     * Picks a backend from assessments and matching measurements. Callers must
     * pass manual overrides separately, since those always win.
     */
    fun choose(
        request: SelectionRequest,
        model: ModelDescriptor?,
        assessments: List<BackendAssessment>,
    ): BackendChoice {
        val status = { backend: Backend -> assessments.firstOrNull { it.backend == backend }?.status }
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val cpu = status(Backend.CPU) == BackendStatus.AVAILABLE
        val vkStatus = status(Backend.VULKAN)
        val vulkan = vkStatus == BackendStatus.AVAILABLE
        val modelVulkan = model?.backendSupport?.get(Backend.VULKAN) ?: TriState.UNKNOWN

        val deviceState = when (vkStatus) {
            BackendStatus.AVAILABLE -> "compatible"
            BackendStatus.NOT_BUILT -> "backend not built"
            BackendStatus.INCOMPATIBLE -> "incompatible"
            BackendStatus.UNAVAILABLE -> "unavailable"
            BackendStatus.UNKNOWN -> "unknown"
            null -> "unknown"
        }
        reasons += "Device Vulkan compatibility: $deviceState"
        reasons += "Model Vulkan compatibility: " + when (modelVulkan) {
            TriState.YES -> "compatible"
            TriState.NO -> "incompatible"
            TriState.UNKNOWN -> "unknown"
        }

        if (!vulkan) {
            reasons += "Automatic decision: CPU fallback because device Vulkan is $deviceState"
            warnings += if (vkStatus == BackendStatus.NOT_BUILT) {
                "Vulkan backend is not compiled into this APK; CPU inference is used"
            } else {
                "Vulkan backend is not usable; CPU inference is used"
            }
            return BackendChoice(if (cpu) Backend.CPU else null, reasons, warnings)
        }

        if (modelVulkan == TriState.NO) {
            reasons += "Automatic decision: CPU because model metadata rules out Vulkan"
            return BackendChoice(Backend.CPU, reasons, warnings)
        }

        if (modelVulkan == TriState.UNKNOWN) {
            val hasVulkanEvidence = matchingRecords(request, model).any { it.backend == Backend.VULKAN }
            if (!hasVulkanEvidence) {
                reasons += "Automatic decision: CPU due to insufficient model-level evidence"
                warnings += "Model Vulkan compatibility is unknown and no matching on-device Vulkan measurement exists"
                return BackendChoice(Backend.CPU, reasons, warnings)
            }
            warnings += "Model Vulkan compatibility is unknown; a matching on-device Vulkan measurement is used as evidence"
        }

        val measured = measuredComparison(request, model)
        if (measured != null) {
            val (cpuRate, vulkanRate) = measured
            if (cpuRate > vulkanRate * 1.15) {
                reasons += "Matching measurements favour CPU (${fmt(cpuRate)} vs ${fmt(vulkanRate)} tok/s)"
                reasons += "Automatic decision: CPU"
                return BackendChoice(Backend.CPU, reasons, warnings)
            }
            reasons += "Matching measurements do not favour CPU; automatic decision: Vulkan"
        } else {
            reasons += "No matching measurements; automatic decision: Vulkan (estimate)"
        }
        return BackendChoice(Backend.VULKAN, reasons, warnings)
    }

    /**
     * Only records that match device, ABI, model, context and config are used.
     * null means "no reliable measurement", never a guessed value.
     */
    /** True only when at least one genuinely matching on-device record exists. */
    fun hasMatchingMeasurements(request: SelectionRequest, model: ModelDescriptor?): Boolean =
        matchingRecords(request, model).isNotEmpty()

    private fun matchingRecords(request: SelectionRequest, model: ModelDescriptor?): List<MeasurementRecord> {
        val profile = request.profile ?: return emptyList()
        val deviceAliases = listOfNotNull(
            profile.capabilities.soc.let { listOfNotNull(it.manufacturer, it.model).joinToString(" ") }
                .takeIf { it.isNotBlank() },
            profile.capabilities.soc.deviceName?.takeIf { it.isNotBlank() },
        ).toSet()
        val abi = profile.capabilities.cpu.primaryAbi
        val modelPath = model?.path
        if (deviceAliases.isEmpty() || abi == null || modelPath == null) return emptyList()
        return request.measurements.records()
            .filter { it.status.equals("ok", ignoreCase = true) || it.status.equals("complete", ignoreCase = true) }
            .filter { record -> deviceAliases.any { it.equals(record.device, ignoreCase = true) } }
            .filter { it.abi == abi }
            .filter { sameModelPath(it.modelPath, modelPath) }
            .filter { it.contextSize == request.contextSize }
            .filter { it.kvQuant == null || it.kvQuant == request.kvQuant }
            .filter { it.threads == null || it.threads == request.threads }
            .filter { it.tokensPerSecond != null && it.tokensPerSecond > 0.0 }
    }

    private fun measuredComparison(request: SelectionRequest, model: ModelDescriptor?): Pair<Double, Double>? {
        val byBackend = matchingRecords(request, model)
            .groupBy { it.backend ?: if ((it.gpuLayers ?: 0) > 0) Backend.VULKAN else Backend.CPU }

        fun rate(backend: Backend): Double? = byBackend[backend]
            ?.mapNotNull { it.tokensPerSecond }
            ?.sorted()
            ?.let { values -> values[values.size / 2] }
        val cpuRate = rate(Backend.CPU) ?: return null
        val vulkanRate = rate(Backend.VULKAN) ?: return null
        return cpuRate to vulkanRate
    }

    private fun fmt(value: Double): String = String.format("%.1f", max(0.0, value))

    fun gpuLayersFor(backend: Backend?, manual: Int): Int = when {
        manual >= 0 -> manual
        backend == Backend.VULKAN -> 99
        else -> 0
    }
}
