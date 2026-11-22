package com.pocketllm.runtime

import com.pocketllm.hardware.HardwareProfile
import com.pocketllm.selection.Confidence
import com.pocketllm.selection.DecisionBasis
import com.pocketllm.selection.ModelDescriptor
import com.pocketllm.selection.ModelFit
import com.pocketllm.selection.ModelFitEvaluator

enum class AdmissionDecision { ALLOW, ALLOW_WITH_WARNING, REJECT }

/** Typed memory picture used by callers that consume [AdmissionResult]. Swap is never included. */
data class MemoryEstimate(
    val modelBytes: Long?,
    val estimatedNeedBytes: Long?,
    val physicalBytes: Long?,
    val availableBytes: Long?,
    val appHeapBytes: Long?,
    val basis: DecisionBasis,
    val confidence: Confidence,
    val notes: List<String>,
    val breakdown: ModelMemoryBreakdown? = null,
    val snapshot: MemorySnapshot? = null,
)

data class AdmissionResult(
    val decision: AdmissionDecision,
    val estimate: MemoryEstimate,
    val reasons: List<String>,
    val warnings: List<String>,
) {
    val allowed: Boolean get() = decision != AdmissionDecision.REJECT
}

/**
 * Decides whether a model may be loaded with the current profile. Reuses the
 * selection engine's fit evaluator so there is one memory model in the app.
 */
class AdmissionPlanner {

    fun admit(
        model: ModelDescriptor,
        profile: HardwareProfile?,
        contextSize: Int,
        fit: ModelFit? = null,
        manualOverride: Boolean = false,
    ): AdmissionResult {
        val assessment = ModelFitEvaluator.evaluate(model, profile, contextSize)
        val effectiveFit = fit ?: assessment.fit
        val reasons = assessment.reasons.toMutableList()
        val warnings = assessment.warnings.toMutableList()

        val decision = when {
            effectiveFit == ModelFit.GOOD_FIT -> AdmissionDecision.ALLOW
            effectiveFit == ModelFit.TIGHT_FIT -> {
                reasons += "Tight fit admitted conservatively"
                warnings += "Tight fit: generation may be slow or memory constrained"
                AdmissionDecision.ALLOW_WITH_WARNING
            }

            manualOverride -> {
                reasons += "Manual override admits model despite fit=$effectiveFit"
                warnings += "Manual override of a memory warning"
                AdmissionDecision.ALLOW_WITH_WARNING
            }

            else -> {
                reasons += "Rejected: fit=$effectiveFit is not safe for automatic loading"
                AdmissionDecision.REJECT
            }
        }

        val estimate = MemoryEstimate(
            modelBytes = model.fileSizeBytes,
            estimatedNeedBytes = assessment.estimatedNeedBytes,
            physicalBytes = profile?.capabilities?.memory?.totalBytes,
            availableBytes = profile?.runtime?.availableMemoryBytes,
            appHeapBytes = profile?.runtime?.appHeapLimitBytes,
            basis = assessment.basis,
            confidence = assessment.confidence,
            notes = reasons,
        )
        return AdmissionResult(decision, estimate, reasons, warnings)
    }
}
