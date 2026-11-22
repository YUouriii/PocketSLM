package com.pocketllm.selection

import com.pocketllm.data.comfortBudgetBytes
import com.pocketllm.hardware.HardwareProfile

/**
 * Central, pure selection engine. It consumes a HardwareProfile, typed model
 * descriptors and optional measurements; it performs no hardware probing and
 * never mutates settings or loads a model itself.
 */
class ModelSelectionEngine {

    fun select(request: SelectionRequest): SelectionResult {
        val assessments = BackendSelector.assess(request.profile)
        val manual = request.manualModelId?.let { id -> request.candidates.firstOrNull { it.id == id } }
        val warnings = mutableListOf<String>()
        val reasons = mutableListOf<String>()

        var backend = request.manualBackend
        var backendReasons = emptyList<String>()
        val selectedModel: ModelDescriptor?

        if (manual != null) {
            selectedModel = manual
            reasons += "Explicit user model choice honoured: ${manual.displayName}"
            if (backend == null) {
                val chosen = BackendSelector.choose(request, manual, assessments)
                backend = chosen.backend
                backendReasons = chosen.reasons
                warnings.addAll(chosen.warnings)
            }
            if (request.manualBackend != null) {
                reasons += "Explicit backend override honoured: ${request.manualBackend}"
                if (request.manualBackend == Backend.VULKAN &&
                    (manual.backendSupport[Backend.VULKAN] ?: com.pocketllm.hardware.TriState.UNKNOWN) ==
                    com.pocketllm.hardware.TriState.UNKNOWN
                ) {
                    warnings += "Model Vulkan compatibility is unknown; explicit override respected"
                }
            }
            val status = assessments.firstOrNull { it.backend == backend }?.status
            if (status == BackendStatus.NOT_BUILT || status == BackendStatus.INCOMPATIBLE) {
                warnings += "Manual backend is not usable in this build; inference layer will fall back to CPU"
            }
        } else {
            val initial = BackendSelector.choose(request, null, assessments)
            backend = initial.backend
            backendReasons = initial.reasons
            selectedModel = chooseAutomatic(request, assessments, backend)
            // Re-evaluate once the model is known: measurements are model-specific.
            val reevaluated = BackendSelector.choose(request, selectedModel, assessments)
            backend = reevaluated.backend
            backendReasons = reevaluated.reasons
            warnings.addAll(reevaluated.warnings)
        }

        reasons.addAll(backendReasons)
        if (backend == null) {
            warnings += "No backend is usable"
            return SelectionResult(
                model = selectedModel, backend = null, gpuLayers = 0, fit = ModelFit.UNKNOWN,
                confidence = Confidence.LOW, basis = DecisionBasis.ESTIMATE, reasons = reasons,
                warnings = warnings, missingInformation = listOf("usable backend"),
                alternatives = emptyList(), backendStatuses = assessments,
                requiresUserChoice = true, manualOverride = manual != null,
            )
        }
        if (selectedModel == null) {
            reasons += "No sufficiently reliable installed model candidate"
            return SelectionResult(
                model = null, backend = backend, gpuLayers = BackendSelector.gpuLayersFor(backend, request.manualGpuLayers),
                fit = ModelFit.UNKNOWN, confidence = Confidence.LOW, basis = DecisionBasis.ESTIMATE,
                reasons = reasons, warnings = warnings,
                missingInformation = listOf("installed model with usable metadata"),
                alternatives = emptyList(), backendStatuses = assessments,
                requiresUserChoice = true, manualOverride = false,
            )
        }

        val status = assessments.firstOrNull { it.backend == backend }?.status
        val fitAssessment = ModelFitEvaluator.evaluate(selectedModel, request.profile, request.contextSize, status)
        reasons.addAll(fitAssessment.reasons)
        warnings.addAll(fitAssessment.warnings)

        val measured = BackendSelector.hasMatchingMeasurements(request, selectedModel)
        val basis = if (measured) DecisionBasis.MEASURED else DecisionBasis.ESTIMATE
        if (!measured) warnings += "No matching on-device measurements; selection is an estimate"

        val alternatives = request.candidates
            .filterNot { it.id == selectedModel.id }
            .map { candidate ->
                val alternativeFit = ModelFitEvaluator.evaluate(candidate, request.profile, request.contextSize, status)
                Alternative(candidate.id, alternativeFit.fit, alternativeFit.reasons.lastOrNull().orEmpty())
            }
            .sortedByDescending { rank(it.fit) }

        val insufficient = fitAssessment.fit == ModelFit.UNKNOWN ||
            (request.profile == null && fitAssessment.confidence == Confidence.LOW)
        if (insufficient) warnings += "Evidence insufficient; an explicit user choice is needed"

        return SelectionResult(
            model = selectedModel,
            backend = backend,
            gpuLayers = BackendSelector.gpuLayersFor(backend, request.manualGpuLayers),
            fit = fitAssessment.fit,
            confidence = fitAssessment.confidence,
            basis = basis,
            reasons = reasons,
            warnings = warnings,
            missingInformation = fitAssessment.missingInformation,
            alternatives = alternatives,
            backendStatuses = assessments,
            requiresUserChoice = insufficient,
            manualOverride = manual != null,
        )
    }

    private fun chooseAutomatic(
        request: SelectionRequest,
        assessments: List<BackendAssessment>,
        backend: Backend?,
    ): ModelDescriptor? {
        val status = assessments.firstOrNull { it.backend == backend }?.status
        val scored = request.candidates.map { candidate ->
            val fit = ModelFitEvaluator.evaluate(candidate, request.profile, request.contextSize, status)
            val performance = PerformanceAnalyzer.recommend(
                model = candidate,
                profile = request.profile,
                contextSize = request.contextSize,
                kvQuant = request.kvQuant,
                threads = request.threads,
                backend = backend,
                measurements = request.measurements.records(),
            )
            val score = rank(fit.fit) * 10 +
                PerformanceAnalyzer.priority(performance, request.performancePreference) * 2 +
                when (fit.confidence) {
                    Confidence.HIGH -> 3
                    Confidence.MEDIUM -> 2
                    Confidence.LOW -> 0
                } +
                comfortBonus(candidate, request.profile)
            Triple(candidate, fit, score)
        }.filter { (_, fit, _) -> fit.fit != ModelFit.NOT_RECOMMENDED && fit.fit != ModelFit.UNSUPPORTED }
        // Equal evidence: prefer the largest model that still leaves headroom,
        // then the smaller file. Measurements above override this tie-break.
        return scored.maxWithOrNull(
            compareBy<Triple<ModelDescriptor, FitAssessment, Double>> { it.third }
                .thenBy { it.first.minRamBytes ?: 0L }
                .thenByDescending { it.first.fileSizeBytes ?: Long.MAX_VALUE },
        )?.first
    }

    /**
     * Rewards a directory-tested minimum that leaves real headroom. Parameter
     * count is deliberately absent: fewer parameters do not guarantee more
     * tokens per second, so speed only comes from measurements.
     */
    private fun comfortBonus(candidate: ModelDescriptor, profile: HardwareProfile?): Double {
        val physical = profile?.capabilities?.memory?.totalBytes ?: return 0.0
        if (physical <= 0) return 0.0
        val minimum = candidate.minRamBytes ?: return 0.5
        return when {
            minimum <= comfortBudgetBytes(physical) -> 2.0
            minimum <= physical * 0.65 -> 0.5
            else -> 0.0
        }
    }

    private fun rank(fit: ModelFit): Int = when (fit) {
        ModelFit.GOOD_FIT -> 4
        ModelFit.TIGHT_FIT -> 3
        ModelFit.UNKNOWN -> 1
        ModelFit.NOT_RECOMMENDED -> 0
        ModelFit.UNSUPPORTED -> -1
    }
}
