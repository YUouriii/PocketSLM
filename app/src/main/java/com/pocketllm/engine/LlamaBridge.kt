package com.pocketllm.engine

/**
 * Receives token pieces from native code. Return false to stop generation.
 * Called on the thread that started generation.
 */
interface TokenCallback {
    fun onToken(piece: String): Boolean
}

/**
 * Raw JNI surface. Keep in sync with app/src/main/cpp/llama_jni.cpp.
 */
object LlamaBridge {

    init {
        System.loadLibrary("pocketllm")
    }

    external fun nativeInit(libDir: String)
    external fun nativeVulkanProbe(): String
    external fun nativeLlamaVersion(): String
    external fun nativeLoadModel(path: String, mmprojPath: String?, nCtx: Int, nThreads: Int, typeK: Int, typeV: Int, nGpuLayers: Int): Long
    external fun nativeMediaSupport(handle: Long): Int
    external fun nativeFree(handle: Long)
    external fun nativeReset(handle: Long)
    external fun nativeCancel(handle: Long)
    external fun nativeModelInfo(handle: Long): String?
    external fun nativeChatTemplate(handle: Long): String?
    external fun nativeFormatChat(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String?

    external fun nativeTokenize(handle: Long, text: String, addSpecial: Boolean): IntArray?
    external fun nativeSyncPrompt(handle: Long, tokens: IntArray, reserve: Int): Int
    external fun nativePrepareMedia(
        handle: Long,
        prompt: String,
        mediaKinds: IntArray,
        mediaPaths: Array<String>,
    ): Int
    external fun nativeGenerate(
        handle: Long,
        maxTokens: Int,
        temp: Float,
        topK: Int,
        topP: Float,
        repeatPenalty: Float,
        minTokens: Int,
        thinkingBudget: Int,
        startInThink: Boolean,
        callback: TokenCallback,
    ): Int

    external fun nativeLastError(): String?
}
