package com.pocketllm.runtime

/** Minimal loading surface; InferenceEngine implements it unchanged. */
interface ModelLoader {
    val loadedModelPath: String?
    val loadedMmprojPath: String?
    val loadedContextSize: Int
    val loadedThreads: Int
    val loadedKvQuant: String
    val loadedGpuLayers: Int

    suspend fun load(
        path: String,
        mmprojPath: String?,
        contextSize: Int,
        threads: Int,
        kvQuant: String,
        gpuLayers: Int,
    ): Result<Unit>

    suspend fun unload()
}
