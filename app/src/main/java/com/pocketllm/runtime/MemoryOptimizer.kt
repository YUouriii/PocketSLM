package com.pocketllm.runtime

import com.pocketllm.data.MemoryMode

/**
 * Plans only PocketSLM-owned resource release. Android never lets an app safely
 * reclaim another app's memory, and this class never tries: no force-stop,
 * killBackgroundProcesses, task killing or hidden APIs.
 */
data class OptimizationResult(
    val mode: MemoryMode,
    val actions: List<String>,
    val suggestions: List<String>,
    val unloadCurrent: Boolean,
    val replanRequired: Boolean,
    /** Always zero: other applications are never terminated by PocketSLM. */
    val otherAppsTerminated: Int = 0,
)

class MemoryOptimizer {

    fun plan(
        mode: MemoryMode,
        hasCurrentModel: Boolean,
        generationActive: Boolean,
        lowMemory: Boolean,
    ): OptimizationResult {
        val actions = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        var unload = false

        when (mode) {
            MemoryMode.PRESERVE -> {
                actions += "No other application was touched"
                suggestions += "Prefer a smaller compatible model"
                suggestions += "Consider reducing context size if the model supports it"
            }

            MemoryMode.BALANCED -> {
                if (hasCurrentModel && !generationActive) {
                    unload = true
                    actions += "Unload the inactive PocketSLM model before retrying"
                } else if (generationActive && hasCurrentModel) {
                    suggestions += "Cannot unload the model while generation is active"
                }
                actions += "No other application was touched"
            }

            MemoryMode.PRIORITIZE -> {
                if (hasCurrentModel && !generationActive) {
                    unload = true
                    actions += "Unload the inactive PocketSLM model before retrying"
                } else if (generationActive && hasCurrentModel) {
                    suggestions += "Cannot unload the model while generation is active"
                }
                actions += "Release unused PocketSLM-owned caches where their lifecycle permits"
                suggestions += "If memory is still insufficient, close other apps manually"
            }
        }
        if (lowMemory) suggestions += "Android reports low memory; deferring is safer than forcing a load"

        return OptimizationResult(
            mode = mode,
            actions = actions,
            suggestions = suggestions,
            unloadCurrent = unload,
            replanRequired = unload,
        )
    }
}
