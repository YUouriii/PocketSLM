package com.pocketllm.runtime

import com.pocketllm.Graph
import com.pocketllm.R

/**
 * The planner speaks in canonical English keys; this maps those keys to the
 * active locale when the advice is actually shown to the user.
 */
object AdviceText {

    fun localize(text: String): String = when (text) {
        "No other application was touched" -> Graph.string(R.string.mem_no_other_touched)
        "Prefer a smaller compatible model" -> Graph.string(R.string.mem_prefer_smaller)
        "Consider reducing context size if the model supports it" -> Graph.string(R.string.mem_reduce_context)
        "Unload the inactive PocketLLM model before retrying",
        "Unload the inactive PocketSLM model before retrying",
        -> Graph.string(R.string.mem_unload_inactive)

        "Cannot unload the model while generation is active" -> Graph.string(R.string.mem_cannot_unload_active)
        "Release unused PocketLLM-owned caches where their lifecycle permits",
        "Release unused PocketSLM-owned caches where their lifecycle permits",
        -> Graph.string(R.string.mem_release_caches)

        "If memory is still insufficient, close other apps manually" -> Graph.string(R.string.mem_close_apps)
        "Android reports low memory; deferring is safer than forcing a load" -> Graph.string(R.string.mem_low_memory)
        else -> text
    }
}
