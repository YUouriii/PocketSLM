package com.pocketllm.hardware

/**
 * Typed device capability model for future model/backend decisions.
 *
 * Unknown information is represented explicitly (null / UNKNOWN) rather than
 * being replaced by a guessed default. A capability that was checked and not
 * found is UNSUPPORTED, which is different from UNKNOWN.
 */

enum class TriState { YES, NO, UNKNOWN }

enum class Architecture { ARM64, ARM32, X86_64, X86, UNKNOWN }

enum class CpuFeature {
    NEON, SVE, SVE2, AES, SHA1, SHA2, FP16, DOT_PRODUCT, I8MM, BF16, AVX, AVX2, FMA,
}

enum class SocFieldSource { ANDROID_API, SYSTEM_PROPERTY, VULKAN_PROBE, GL_RENDERER, UNAVAILABLE }

/**
 * Identity reported by the OS. Deliberately separate from CPU flags: instruction
 * sets never identify a precise SoC. Unknown fields stay null with UNAVAILABLE source.
 */
data class SocIdentity(
    val manufacturer: String?,
    val model: String?,
    /** Board platform string, kept separate from the SoC model. */
    val boardPlatform: String?,
    /** GPU family such as "adreno", when the platform declares one. Not a model. */
    val gpuFamily: String?,
    val gpuModel: String?,
    val manufacturerSource: SocFieldSource,
    val modelSource: SocFieldSource,
    val boardSource: SocFieldSource,
    val gpuFamilySource: SocFieldSource,
    val gpuModelSource: SocFieldSource,
    /** Official Build.MANUFACTURER + Build.MODEL, used to match stored benchmarks. */
    val deviceName: String? = null,
)

data class CpuFeatureState(val feature: CpuFeature, val state: TriState)

data class CpuCapabilities(
    val primaryAbi: String?,
    val supportedAbis: List<String>,
    val architecture: Architecture,
    val is64Bit: Boolean?,
    val processorCount: Int?,
    /** Raw feature flags were readable from the OS. */
    val flagsReadable: Boolean,
    /** Source used for feature flags, or null when nothing was readable. */
    val flagSource: String?,
    /** every known feature is reported: YES (found), NO (absent), UNKNOWN (unreadable). */
    val features: List<CpuFeatureState>,
    /** flags present on the device that are not yet classified in [features]. */
    val unclassifiedFlags: List<String>,
)

data class MemoryHardware(
    /** Physical RAM only (MemTotal). Storage-backed swap extension is never added here. */
    val totalBytes: Long?,
    /** Where the physical RAM figure came from. */
    val source: String?,
    /** ActivityManager's own total, kept for comparison. */
    val systemReportedTotalBytes: Long?,
    /**
     * Kernel swap capacity from /proc/meminfo SwapTotal. This is NOT physical RAM
     * and NOT proof of any vendor "memory extension" feature.
     */
    val kernelSwapCapacityBytes: Long?,
    val kernelSwapCapacitySource: String?,
    /** Backing device for swap, e.g. /dev/block/zram0, from /proc/swaps. */
    val swapBackingDevice: String?,
    val swapBackingSource: String?,
    /** ZRAM devices discovered under /sys/block/zram*, when accessible. */
    val zramDevices: List<String>,
    val zramSource: String?,
    /**
     * Vendor memory extension cannot be verified from standard kernel swap data;
     * it is deliberately UNKNOWN unless a reliable system source identifies it.
     */
    val vendorMemoryExtension: TriState = TriState.UNKNOWN,
)

data class StorageHardware(
    val path: String?,
    val totalBytes: Long?,
    val blockSizeBytes: Long?,
)

/**
 * Graphics facts. Vulkan fields come from the app's existing one-time probe and
 * are deliberately split: a loader being present is not proof that the
 * llama.cpp Vulkan backend will work.
 */
data class GraphicsCapabilities(
    /** Platform-declared graphics/compute features, by PackageManager constant name. */
    val platformFeatures: Map<String, Boolean>,
    val vulkanProbeAvailable: Boolean,
    val vulkanProbeCompatible: Boolean,
    val vulkanDevice: String,
    val vulkanVendor: String,
    val vulkanDriver: String,
    val vulkanApi: String,
    val vulkanMissing: String,
    val vulkanReason: String,
    val vulkanChecks: Map<String, String>,
    /** The compatibility probe never verifies the inference backend itself. */
    val llamaVulkanBackend: TriState = TriState.UNKNOWN,
)

/** Relatively static facts; does not change while the app process is alive. */
data class HardwareCapabilities(
    val soc: SocIdentity,
    val cpu: CpuCapabilities,
    val memory: MemoryHardware,
    val storage: StorageHardware,
    val graphics: GraphicsCapabilities,
    val detectedAtMillis: Long,
)

/**
 * Conditions that change during use. Refresh explicitly via
 * [HardwareRegistry.refreshRuntime]; nothing here polls by itself.
 */
data class RuntimeConditions(
    val capturedAtMillis: Long,
    val availableMemoryBytes: Long?,
    val appHeapLimitBytes: Long?,
    val memoryClassMb: Int?,
    val largeMemoryClassMb: Int?,
    val lowMemory: Boolean?,
    val lowMemoryThresholdBytes: Long?,
    val availableStorageBytes: Long?,
    val freeStorageBytes: Long?,
    val thermalStatus: Int?,
    val thermalStatusName: String?,
    val powerSaveMode: Boolean?,
    val deviceIdleMode: Boolean?,
    val batteryPercent: Int?,
)

/** Complete profile returned by the registry. */
data class HardwareProfile(
    val capabilities: HardwareCapabilities,
    val runtime: RuntimeConditions,
) {
    val capturedAtMillis: Long get() = maxOf(capabilities.detectedAtMillis, runtime.capturedAtMillis)
}
