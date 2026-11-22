package com.pocketllm.runtime

import com.pocketllm.selection.Confidence
import com.pocketllm.selection.DecisionBasis

enum class LoadPlanOutcome {
    READY,
    READY_WITH_WARNING,
    UNLOAD_CURRENT_FIRST,
    DEFER_LOW_MEMORY,
    BLOCK_INSUFFICIENT_MEMORY,
    UNKNOWN,
}

data class LoadPlan(
    val outcome: LoadPlanOutcome,
    val snapshot: MemorySnapshot,
    val breakdown: ModelMemoryBreakdown,
    val reasons: List<String>,
    val warnings: List<String>,
    val requiresUnload: Boolean,
    val blockedByGeneration: Boolean,
) {
    val mayLoad: Boolean get() = !blockedByGeneration &&
        outcome in setOf(LoadPlanOutcome.READY, LoadPlanOutcome.READY_WITH_WARNING)
}

/**
 * Produces a concrete load plan from a fresh snapshot. Does not touch native
 * memory; the runtime manager executes the plan.
 */
class LoadPlanner(
    private val estimator: MemoryEstimator = MemoryEstimator(),
) {

    fun plan(
        request: LoadRequest,
        snapshot: MemorySnapshot,
        generationActive: Boolean = false,
        profile: com.pocketllm.hardware.HardwareProfile? = null,
    ): LoadPlan {
        val breakdown = estimator.estimate(
            model = request.model,
            profile = profile,
            snapshot = snapshot,
            contextSize = request.contextSize,
            kvQuant = request.kvQuant,
            measurements = request.measurements.records(),
            backend = request.backend,
        )
        val reasons = mutableListOf<String>()
        val warnings = breakdown.uncertainties.toMutableList()
        reasons += "Snapshot ${snapshot.source}: physical=${mb(snapshot.physicalBytes)} MiB, " +
            "available=${mb(snapshot.availableBytes)} MiB, lowMemory=${snapshot.lowMemory}"
        reasons += "Estimated budget=${mb(breakdown.budgetBytes)} MiB " +
            "(peak=${mb(breakdown.peakLoadingBytes)}, steady=${mb(breakdown.steadyStateBytes)}), " +
            "evidence=${breakdown.evidence}"

        if (snapshot.isLowMemory) {
            reasons += "Deferred: Android reports low memory"
            return LoadPlan(
                LoadPlanOutcome.DEFER_LOW_MEMORY, snapshot, breakdown, reasons,
                warnings + "Low-memory state; no model load attempted", false, false,
            )
        }

        val available = snapshot.availableBytes
        val budget = breakdown.budgetBytes
        if (snapshot.physicalBytes == null || available == null || budget == null) {
            reasons += "Unknown: insufficient memory evidence"
            return LoadPlan(
                LoadPlanOutcome.UNKNOWN, snapshot, breakdown, reasons, warnings, false, false,
            )
        }

        val replacement = snapshot.loadedModelPath != null && snapshot.loadedModelPath != request.model.path
        if (replacement) {
            reasons += "Replacement requested: unload ${snapshot.loadedModelPath} before loading ${request.model.path}"
            return LoadPlan(
                LoadPlanOutcome.UNLOAD_CURRENT_FIRST, snapshot, breakdown, reasons,
                warnings + "Current model must be released before replacement",
                requiresUnload = true,
                blockedByGeneration = generationActive,
            )
        }

        val decision = when {
            available >= budget * 1.10f -> {
                reasons += "Available memory exceeds budget with safety margin"
                LoadPlanOutcome.READY
            }

            available >= budget -> {
                reasons += "Available memory covers estimate but has little margin"
                warnings += "Little memory headroom; generation may be constrained"
                LoadPlanOutcome.READY_WITH_WARNING
            }

            request.manualOverride -> {
                reasons += "Manual override admits load despite memory warning"
                warnings += "Manual override of a memory admission decision"
                LoadPlanOutcome.READY_WITH_WARNING
            }

            else -> {
                reasons += "Blocked: available memory below estimated budget"
                LoadPlanOutcome.BLOCK_INSUFFICIENT_MEMORY
            }
        }
        return LoadPlan(decision, snapshot, breakdown, reasons, warnings, false, false)
    }

    /** Bridges the new plan to the older AdmissionResult field on RuntimeLoadResult. */
    fun admission(plan: LoadPlan): AdmissionResult {
        val decision = when (plan.outcome) {
            LoadPlanOutcome.READY, LoadPlanOutcome.UNLOAD_CURRENT_FIRST -> AdmissionDecision.ALLOW
            LoadPlanOutcome.READY_WITH_WARNING -> AdmissionDecision.ALLOW_WITH_WARNING
            else -> AdmissionDecision.REJECT
        }
        return AdmissionResult(
            decision = decision,
            estimate = MemoryEstimate(
                modelBytes = plan.breakdown.weightsBytes,
                estimatedNeedBytes = plan.breakdown.budgetBytes,
                physicalBytes = plan.snapshot.physicalBytes,
                availableBytes = plan.snapshot.availableBytes,
                appHeapBytes = plan.snapshot.appHeapLimitBytes,
                basis = if (plan.breakdown.evidence == MemoryEvidence.MEASURED) {
                    DecisionBasis.MEASURED
                } else {
                    DecisionBasis.ESTIMATE
                },
                confidence = plan.breakdown.confidence,
                notes = plan.reasons,
                breakdown = plan.breakdown,
                snapshot = plan.snapshot,
            ),
            reasons = plan.reasons,
            warnings = plan.warnings,
        )
    }

    private fun mb(bytes: Long?): String = bytes?.let { "${it / ModelMemoryBreakdown.MIB}" } ?: "unknown"
}
