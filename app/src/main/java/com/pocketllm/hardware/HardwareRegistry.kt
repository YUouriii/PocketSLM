package com.pocketllm.hardware

import android.content.Context
import android.util.Log
import com.pocketllm.engine.VulkanProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single entry point for device capability information.
 *
 * Static capabilities are read once at [init]; runtime conditions are read on
 * demand by [refreshRuntime] (or both via [refresh]). Nothing here starts a
 * polling loop, and the registry has no dependency on UI or inference code.
 */
object HardwareRegistry {

    private val _capabilities = MutableStateFlow<HardwareCapabilities?>(null)
    val capabilities: StateFlow<HardwareCapabilities?> = _capabilities.asStateFlow()

    private val _runtime = MutableStateFlow<RuntimeConditions?>(null)
    val runtime: StateFlow<RuntimeConditions?> = _runtime.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var vulkanProbeProvider: (() -> VulkanProbe)? = null

    fun init(context: Context, vulkanProbeProvider: () -> VulkanProbe) {
        appContext = context.applicationContext
        this.vulkanProbeProvider = vulkanProbeProvider
        refresh()
    }

    /** Re-reads the relatively static hardware facts. */
    @Synchronized
    fun refreshCapabilities(): HardwareCapabilities {
        val context = checkNotNull(appContext) { "HardwareRegistry.init() has not been called" }
        val probe = checkNotNull(vulkanProbeProvider) { "HardwareRegistry.init() has not been called" }()
        return CapabilitySources.readCapabilities(context, probe).also {
            _capabilities.value = it
            Log.i(TAG, "capabilities=${summarize(it)}")
        }
    }

    /**
     * Re-reads only dynamic conditions. Safe to call whenever a caller needs a
     * fresh snapshot; no background work or polling is started.
     */
    @Synchronized
    fun refreshRuntime(): RuntimeConditions {
        val context = checkNotNull(appContext) { "HardwareRegistry.init() has not been called" }
        return CapabilitySources.readRuntime(context).also {
            _runtime.value = it
            Log.i(TAG, "runtime=${summarize(it)}")
        }
    }

    @Synchronized
    fun refresh(): HardwareProfile {
        refreshCapabilities()
        return HardwareProfile(checkNotNull(_capabilities.value) {
            "capability refresh failed"
        }, refreshRuntime())
    }

    /** Current profile, or null before [init] has completed. */
    fun current(): HardwareProfile? {
        val caps = _capabilities.value ?: return null
        val runtime = _runtime.value ?: return null
        return HardwareProfile(caps, runtime)
    }

    fun requireCurrent(): HardwareProfile = checkNotNull(current()) {
        "HardwareRegistry.init() has not completed"
    }

    private fun summarize(caps: HardwareCapabilities): String = buildString {
        append("soc=").append(caps.soc.manufacturer ?: "unknown")
        append('/').append(caps.soc.model ?: "unknown")
        append(",board=").append(caps.soc.boardPlatform ?: "unknown")
        append(",gpu=").append(caps.soc.gpuModel ?: caps.soc.gpuFamily ?: "unknown")
        append(",gpuSource=").append(caps.soc.gpuModelSource)
        append(",abi=").append(caps.cpu.primaryAbi ?: "unknown")
        append(",arch=").append(caps.cpu.architecture)
        append(",cores=").append(caps.cpu.processorCount ?: "unknown")
        append(",flagsReadable=").append(caps.cpu.flagsReadable)
        append(",ramMb=").append(caps.memory.totalBytes?.div(MB) ?: "unknown")
        append(",ramSource=").append(caps.memory.source ?: "unknown")
        append(",swapCapacityMb=").append(caps.memory.kernelSwapCapacityBytes?.div(MB) ?: "unknown")
        append(",swapBacking=").append(caps.memory.swapBackingDevice ?: "unknown")
        append(",zram=").append(caps.memory.zramDevices.joinToString("+").ifBlank { "none" })
        append(",memExtension=").append(caps.memory.vendorMemoryExtension)
        append(",vulkanAvailable=").append(caps.graphics.vulkanProbeAvailable)
        append(",vulkanCompatible=").append(caps.graphics.vulkanProbeCompatible)
        val present = caps.cpu.features.filter { it.state == TriState.YES }.map { it.feature.name }
        append(",features=").append(present.joinToString("+").ifBlank { "none" })
        append(",unknownFeatures=").append(caps.cpu.features.count { it.state == TriState.UNKNOWN })
        append(",unclassifiedFlags=").append(caps.cpu.unclassifiedFlags.size)
        append(",platformGfxFeatures=").append(caps.graphics.platformFeatures.size)
    }

    private fun summarize(rt: RuntimeConditions): String = buildString {
        append("availRamMb=").append(rt.availableMemoryBytes?.div(MB) ?: "unknown")
        append(",lowMemory=").append(rt.lowMemory?.toString() ?: "unknown")
        append(",thermal=").append(rt.thermalStatusName ?: rt.thermalStatus?.toString() ?: "unknown")
        append(",powerSave=").append(rt.powerSaveMode?.toString() ?: "unknown")
        append(",idle=").append(rt.deviceIdleMode?.toString() ?: "unknown")
        append(",battery=").append(rt.batteryPercent?.toString() ?: "unknown")
        append(",freeStorageMb=").append(rt.availableStorageBytes?.div(MB) ?: "unknown")
    }

    private const val TAG = "HardwareRegistry"
    private const val MB = 1024L * 1024L
}
