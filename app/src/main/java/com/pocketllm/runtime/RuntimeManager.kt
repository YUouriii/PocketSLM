package com.pocketllm.runtime

import android.util.Log
import com.pocketllm.data.MemoryMode
import com.pocketllm.hardware.HardwareProfile
import com.pocketllm.selection.Backend
import com.pocketllm.selection.EmptyMeasurements
import com.pocketllm.selection.MeasurementProvider
import com.pocketllm.selection.ModelDescriptor
import com.pocketllm.selection.ModelFit

enum class LoadOutcome { LOADED, ALREADY_LOADED, REJECTED, FAILED, UNLOADED }

data class LoadRequest(
    val model: ModelDescriptor,
    val mmprojPath: String?,
    val contextSize: Int,
    val threads: Int,
    val kvQuant: String,
    val gpuLayers: Int,
    /** Explicit user choice bypasses automatic admission rejection. */
    val manualOverride: Boolean = false,
    val fit: ModelFit? = null,
    val backend: Backend? = null,
    val measurements: MeasurementProvider = EmptyMeasurements,
    /** Generation must never be interrupted by a model swap. */
    val generationActive: Boolean = false,
    val memoryMode: String = MemoryMode.PRESERVE.stored,
)

data class RuntimeLoadResult(
    val outcome: LoadOutcome,
    val admission: AdmissionResult? = null,
    val plan: LoadPlan? = null,
    val reason: String = "",
    val error: Throwable? = null,
)

/**
 * Executes memory-aware load plans: snapshot, plan, unload/replan when replacing,
 * then load. The native loader itself is the unchanged InferenceEngine.
 */
class RuntimeManager(
    private val loader: ModelLoader,
    private val snapshotProvider: MemorySnapshotProvider? = null,
    private val optimizer: MemoryOptimizer = MemoryOptimizer(),
    private val planner: LoadPlanner = LoadPlanner(),
    private val profileProvider: () -> HardwareProfile?,
) {

    private val snapshots: MemorySnapshotProvider
        get() = snapshotProvider ?: HardwareSnapshotProvider(profileProvider)

    suspend fun ensureLoaded(request: LoadRequest): RuntimeLoadResult {
        if (isAlreadyLoaded(request)) {
            return RuntimeLoadResult(LoadOutcome.ALREADY_LOADED, reason = "Model already loaded with these parameters")
        }

        var snapshot = snapshots.capture(loader)
        var plan = planner.plan(request, snapshot, request.generationActive, profileProvider())
        log("before", plan)

        if (plan.blockedByGeneration) {
            return reject(plan, "Rejected: replacement while generation is active")
        }
        if (plan.requiresUnload) {
            if (loader.loadedModelPath == null) {
                snapshot = snapshots.capture(loader)
                plan = planner.plan(request, snapshot, request.generationActive, profileProvider())
            } else {
                loader.unload()
                snapshot = snapshots.capture(loader)
                plan = planner.plan(request, snapshot, request.generationActive, profileProvider())
                log("afterUnload", plan)
            }
            if (plan.blockedByGeneration) {
                return reject(plan, "Rejected: replacement while generation is active")
            }
        }
        if (!plan.mayLoad && request.memoryMode != MemoryMode.PRESERVE.stored) {
            val optimization = optimizer.plan(
                mode = MemoryMode.fromStored(request.memoryMode),
                hasCurrentModel = loader.loadedModelPath != null,
                generationActive = request.generationActive,
                lowMemory = plan.snapshot.isLowMemory,
            )
            if (optimization.unloadCurrent && loader.loadedModelPath != null && !request.generationActive) {
                loader.unload()
                snapshot = snapshots.capture(loader)
                plan = planner.plan(request, snapshot, request.generationActive, profileProvider())
                log("afterOptimization", plan)
            }
            if (!plan.mayLoad) {
                val advice = (optimization.actions + optimization.suggestions)
                    .joinToString(". ") { AdviceText.localize(it) }
                return reject(plan, "${plan.reasons.lastOrNull().orEmpty()} $advice".trim())
            }
        }
        if (!plan.mayLoad) {
            return reject(plan, plan.reasons.lastOrNull().orEmpty())
        }

        val result = runCatching {
            loader.load(
                request.model.path,
                request.mmprojPath,
                request.contextSize,
                request.threads,
                request.kvQuant,
                request.gpuLayers,
            ).getOrThrow()
        }
        return result.fold(
            onSuccess = {
                RuntimeLoadResult(
                    LoadOutcome.LOADED,
                    planner.admission(plan),
                    plan,
                    "Loaded ${request.model.displayName}",
                )
            },
            onFailure = { error ->
                // Failed loads must not leave a half-initialised native model behind.
                runCatching { loader.unload() }
                RuntimeLoadResult(
                    LoadOutcome.FAILED,
                    planner.admission(plan),
                    plan,
                    error.message ?: "Model load failed",
                    error,
                )
            },
        )
    }

    suspend fun unload(): RuntimeLoadResult {
        if (loader.loadedModelPath == null) {
            return RuntimeLoadResult(LoadOutcome.ALREADY_LOADED, reason = "No model loaded")
        }
        loader.unload()
        val snapshot = snapshots.capture(loader)
        return RuntimeLoadResult(
            LoadOutcome.UNLOADED,
            plan = LoadPlan(
                LoadPlanOutcome.READY,
                snapshot,
                ModelMemoryBreakdown(
                    null, null, null, null, null, null, null,
                    MemoryEvidence.UNKNOWN,
                    com.pocketllm.selection.Confidence.LOW,
                    listOf("Model unloaded"),
                ),
                listOf("No model loaded"),
                emptyList(),
                false,
                false,
            ),
            reason = "Model unloaded",
        )
    }

    private fun reject(plan: LoadPlan, reason: String) = RuntimeLoadResult(
        LoadOutcome.REJECTED,
        planner.admission(plan),
        plan,
        reason,
    )

    private fun log(stage: String, plan: LoadPlan) {
        // android.util.Log is not available in plain JVM unit tests.
        runCatching {
            Log.i(
                TAG,
                "$stage outcome=${plan.outcome} available=${plan.snapshot.availableBytes} " +
                    "physical=${plan.snapshot.physicalBytes} low=${plan.snapshot.lowMemory} " +
                    "budget=${plan.breakdown.budgetBytes} evidence=${plan.breakdown.evidence} " +
                    "unload=${plan.requiresUnload} generationBlocked=${plan.blockedByGeneration}",
            )
        }
    }

    private fun isAlreadyLoaded(request: LoadRequest): Boolean =
        loader.loadedModelPath == request.model.path &&
            loader.loadedMmprojPath == request.mmprojPath &&
            loader.loadedContextSize == request.contextSize &&
            loader.loadedThreads == request.threads &&
            loader.loadedKvQuant == request.kvQuant &&
            loader.loadedGpuLayers == request.gpuLayers

    private companion object {
        const val TAG = "Runtime"
    }
}
