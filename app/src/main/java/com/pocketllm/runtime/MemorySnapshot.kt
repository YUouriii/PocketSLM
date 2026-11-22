package com.pocketllm.runtime

import com.pocketllm.hardware.HardwareRegistry

/**
 * Point-in-time memory picture. Physical RAM only: swap/ZRAM/memory extension
 * are deliberately absent. Loaded-model state is captured with the snapshot so
 * replacement decisions use current app usage, not just device totals.
 */
data class MemorySnapshot(
    val capturedAtMillis: Long,
    val physicalBytes: Long?,
    val availableBytes: Long?,
    val lowMemory: Boolean?,
    val appHeapLimitBytes: Long?,
    val memoryClassMb: Int?,
    val largeMemoryClassMb: Int?,
    val thermalStatusName: String?,
    val loadedModelPath: String?,
    val loadedContextSize: Int?,
    val loadedThreads: Int?,
    val loadedGpuLayers: Int?,
    val source: String,
) {
    val isLowMemory: Boolean get() = lowMemory == true
}

/** Snapshot source is injectable so planning can be tested without devices. */
fun interface MemorySnapshotProvider {
    fun capture(loader: ModelLoader): MemorySnapshot
}

/** Refreshes dynamic runtime conditions from the existing registry before each read. */
class HardwareSnapshotProvider(
    private val profileProvider: () -> com.pocketllm.hardware.HardwareProfile?,
) : MemorySnapshotProvider {
    override fun capture(loader: ModelLoader): MemorySnapshot {
        val refreshed = runCatching { HardwareRegistry.refreshRuntime() }.getOrNull()
        val profile = profileProvider()
        val runtime = refreshed ?: profile?.runtime
        return MemorySnapshot(
            capturedAtMillis = System.currentTimeMillis(),
            physicalBytes = profile?.capabilities?.memory?.totalBytes,
            availableBytes = runtime?.availableMemoryBytes,
            lowMemory = runtime?.lowMemory,
            appHeapLimitBytes = runtime?.appHeapLimitBytes,
            memoryClassMb = runtime?.memoryClassMb,
            largeMemoryClassMb = runtime?.largeMemoryClassMb,
            thermalStatusName = runtime?.thermalStatusName,
            loadedModelPath = loader.loadedModelPath,
            loadedContextSize = loader.loadedContextSize,
            loadedThreads = loader.loadedThreads,
            loadedGpuLayers = loader.loadedGpuLayers,
            source = if (refreshed != null) "HardwareRegistry.refreshRuntime" else "HardwareRegistry.current",
        )
    }
}
