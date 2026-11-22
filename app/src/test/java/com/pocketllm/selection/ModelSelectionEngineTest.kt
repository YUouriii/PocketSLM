package com.pocketllm.selection

import com.pocketllm.hardware.Architecture
import com.pocketllm.hardware.CpuCapabilities
import com.pocketllm.hardware.CpuFeature
import com.pocketllm.hardware.CpuFeatureState
import com.pocketllm.hardware.GraphicsCapabilities
import com.pocketllm.hardware.HardwareCapabilities
import com.pocketllm.hardware.HardwareProfile
import com.pocketllm.hardware.MemoryHardware
import com.pocketllm.hardware.RuntimeConditions
import com.pocketllm.hardware.SocFieldSource
import com.pocketllm.hardware.SocIdentity
import com.pocketllm.hardware.StorageHardware
import com.pocketllm.hardware.TriState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionEngineTest {

    private val engine = ModelSelectionEngine()
    private val mib = 1024L * 1024L

    private fun graphics(reason: String, available: Boolean = false, compatible: Boolean = false) =
        GraphicsCapabilities(
            platformFeatures = emptyMap(),
            vulkanProbeAvailable = available,
            vulkanProbeCompatible = compatible,
            vulkanDevice = "Test GPU",
            vulkanVendor = "",
            vulkanDriver = "",
            vulkanApi = "",
            vulkanMissing = "Vulkan1.1",
            vulkanReason = reason,
            vulkanChecks = emptyMap(),
        )

    private fun profile(
        ramMb: Long = 4096,
        availableMb: Long = 2048,
        heapMb: Long = 2048,
        gfx: GraphicsCapabilities = graphics("no_vulkan_backend"),
    ) = HardwareProfile(
        HardwareCapabilities(
            soc = SocIdentity(
                manufacturer = "QTI", model = "SM6225", boardPlatform = "test",
                gpuFamily = "adreno", gpuModel = "Test GPU",
                manufacturerSource = SocFieldSource.ANDROID_API,
                modelSource = SocFieldSource.ANDROID_API,
                boardSource = SocFieldSource.SYSTEM_PROPERTY,
                gpuFamilySource = SocFieldSource.SYSTEM_PROPERTY,
                gpuModelSource = SocFieldSource.GL_RENDERER,
            ),
            cpu = CpuCapabilities(
                primaryAbi = "arm64-v8a", supportedAbis = listOf("arm64-v8a"),
                architecture = Architecture.ARM64, is64Bit = true, processorCount = 8,
                flagsReadable = true, flagSource = "/proc/cpuinfo",
                features = listOf(CpuFeatureState(CpuFeature.NEON, TriState.YES)),
                unclassifiedFlags = listOf("crc32"),
            ),
            memory = MemoryHardware(
                totalBytes = ramMb * mib, source = "/proc/meminfo",
                systemReportedTotalBytes = ramMb * mib, kernelSwapCapacityBytes = null,
                kernelSwapCapacitySource = null, swapBackingDevice = null,
                swapBackingSource = null, zramDevices = emptyList(), zramSource = null,
            ),
            storage = StorageHardware("/data", 64L * mib * 1024, 4096),
            graphics = gfx,
            detectedAtMillis = 0L,
        ),
        RuntimeConditions(
            capturedAtMillis = 0L, availableMemoryBytes = availableMb * mib,
            appHeapLimitBytes = heapMb * mib, memoryClassMb = 256, largeMemoryClassMb = 512,
            lowMemory = false, lowMemoryThresholdBytes = null,
            availableStorageBytes = 8L * mib * 1024, freeStorageBytes = 8L * mib * 1024,
            thermalStatus = 0, thermalStatusName = "NONE", powerSaveMode = false,
            deviceIdleMode = false, batteryPercent = 100,
        ),
    )

    private fun model(
        id: String = "a",
        sizeMb: Long = 400,
        paramsB: Double? = 0.5,
        quant: String? = "Q4_K_M",
        vulkanSupport: TriState? = null,
        minRamMb: Long? = null,
    ) = ModelDescriptor(
        id = id, displayName = id, path = "/models/$id.gguf",
        fileSizeBytes = sizeMb * mib, paramsB = paramsB, quantization = quant,
        architecture = null, contextLength = null,
        backendSupport = vulkanSupport?.let { mapOf(Backend.VULKAN to it) } ?: emptyMap(),
        source = MetadataSource.CATALOG,
        minRamBytes = minRamMb?.times(mib),
    )

    private fun request(
        prof: HardwareProfile? = profile(),
        models: List<ModelDescriptor> = listOf(model()),
        measurements: MeasurementProvider = EmptyMeasurements,
        manualModelId: String? = null,
        manualBackend: Backend? = null,
    ) = SelectionRequest(
        profile = prof, candidates = models, contextSize = 2048, kvQuant = "Q8_0",
        threads = 4, manualModelId = manualModelId, manualBackend = manualBackend,
        manualGpuLayers = -1, measurements = measurements,
    )

    private fun measurements(vararg records: MeasurementRecord) = object : MeasurementProvider {
        override fun records() = records.toList()
    }

    @Test
    fun cpuOnlyBuildSelectsCpu() {
        val result = engine.select(request())
        assertEquals(Backend.CPU, result.backend)
        assertEquals(BackendStatus.NOT_BUILT, result.backendStatuses.first { it.backend == Backend.VULKAN }.status)
        assertFalse(result.requiresUserChoice)
    }

    @Test
    fun vulkanBackendNotBuiltIsNotHardwareUnsupported() {
        val result = engine.select(request())
        val status = result.backendStatuses.first { it.backend == Backend.VULKAN }
        assertEquals(BackendStatus.NOT_BUILT, status.status)
        assertTrue(status.reasons.any { it.contains("not compiled", true) })
    }

    @Test
    fun vulkanBuiltButProbeUnavailableFallsBackToCpu() {
        val result = engine.select(request(prof = profile(gfx = graphics("probe_failed"))))
        assertEquals(BackendStatus.UNKNOWN, result.backendStatuses.first { it.backend == Backend.VULKAN }.status)
        assertEquals(Backend.CPU, result.backend)
    }

    @Test
    fun incompatibleProbePreventsVulkan() {
        val result = engine.select(
            request(prof = profile(gfx = graphics("requirements_missing", true, false))),
        )
        assertEquals(BackendStatus.INCOMPATIBLE, result.backendStatuses.first { it.backend == Backend.VULKAN }.status)
        assertEquals(Backend.CPU, result.backend)
        assertTrue(result.backendStatuses.first { it.backend == Backend.VULKAN }.reasons.any { it.contains("Missing") })
    }

    @Test
    fun compatibleProbeSelectsVulkanForKnownCompatibleModel() {
        val result = engine.select(
            request(
                prof = profile(gfx = graphics("ok", true, true)),
                models = listOf(model(vulkanSupport = TriState.YES)),
            ),
        )
        assertEquals(Backend.VULKAN, result.backend)
        assertEquals(99, result.gpuLayers)
        assertTrue(result.reasons.any { it == "Device Vulkan compatibility: compatible" })
        assertTrue(result.reasons.any { it == "Model Vulkan compatibility: compatible" })
    }

    @Test
    fun compatibleProbeWithUnknownModelFallsBackToCpu() {
        val result = engine.select(
            request(prof = profile(gfx = graphics("ok", true, true))),
        )
        assertEquals(Backend.CPU, result.backend)
        assertTrue(result.reasons.any { it == "Device Vulkan compatibility: compatible" })
        assertTrue(result.reasons.any { it == "Model Vulkan compatibility: unknown" })
        assertTrue(result.reasons.any { it.contains("insufficient model-level evidence") })
        assertTrue(result.warnings.any { it.contains("Model Vulkan compatibility is unknown") })
        assertEquals(null, result.model?.backendSupport?.get(Backend.VULKAN))
    }

    @Test
    fun matchingVulkanMeasurementIsEvidenceForUnknownModel() {
        val match = MeasurementRecord(
            device = "QTI SM6225", abi = "arm64-v8a", modelPath = "/models/a.gguf",
            contextSize = 2048, kvQuant = "Q8_0", threads = 4,
            backend = Backend.VULKAN, tokensPerSecond = 30.0, gpuLayers = 99, status = "ok",
        )
        val cpu = match.copy(backend = Backend.CPU, gpuLayers = 0, tokensPerSecond = 6.0)
        val result = engine.select(
            request(
                prof = profile(gfx = graphics("ok", true, true)),
                measurements = measurements(match, cpu),
            ),
        )
        assertEquals(Backend.VULKAN, result.backend)
        assertEquals(DecisionBasis.MEASURED, result.basis)
    }

    @Test
    fun unknownHardwareYieldsUnknownAndUserChoice() {
        val result = engine.select(request(prof = null))
        assertNull(result.backend)
        assertEquals(ModelFit.UNKNOWN, result.fit)
        assertTrue(result.requiresUserChoice)
    }

    @Test
    fun unknownModelMetadataStaysUnknown() {
        val unknown = model().copy(fileSizeBytes = null, paramsB = null, quantization = null)
        val result = engine.select(request(models = listOf(unknown)))
        assertEquals(ModelFit.UNKNOWN, result.fit)
        assertTrue(result.missingInformation.isNotEmpty())
    }

    @Test
    fun modelSizesChangeFit() {
        val big = model("big", 6000, 7.0)
        val small = model("small", 300, 0.5)
        val bigResult = engine.select(request(models = listOf(big, small), manualModelId = "big"))
        val smallResult = engine.select(request(models = listOf(small)))
        assertEquals(ModelFit.NOT_RECOMMENDED, bigResult.fit)
        assertEquals("small", bigResult.alternatives.first().modelId)
        assertEquals(ModelFit.GOOD_FIT, smallResult.fit)
    }

    @Test
    fun sixGigabytePhonePrefersComfortableSmallerModel() {
        val small = model("small", 990, 1.5, minRamMb = 3000)
        val medium = model("medium", 1222, 2.0, minRamMb = 3500)
        val large = model("large", 1930, 3.0, minRamMb = 5000)
        val four = model("four", 2614, 4.0, minRamMb = 5500)
        val result = engine.select(
            request(
                prof = profile(ramMb = 5653, availableMb = 3000),
                models = listOf(four, large, medium, small),
            ),
        )
        assertEquals("small", result.model?.id)
    }

    @Test
    fun eightGigabytePhoneCanChooseLargerComfortableModel() {
        val small = model("small", 990, 1.5, minRamMb = 3000)
        val large = model("large", 1930, 3.0, minRamMb = 5000)
        val result = engine.select(
            request(
                prof = profile(ramMb = 8192, availableMb = 5000),
                models = listOf(small, large),
            ),
        )
        assertEquals("large", result.model?.id)
    }

    @Test
    fun manualSlowAndHeavyModelStillWins() {
        val small = model("small", 990, 1.5, minRamMb = 3000)
        val four = model("four", 2614, 4.0, minRamMb = 5500)
        val result = engine.select(
            request(
                prof = profile(ramMb = 5653, availableMb = 3000),
                models = listOf(small, four),
                manualModelId = "four",
            ),
        )
        assertEquals("four", result.model?.id)
        assertTrue(result.manualOverride)
    }

    @Test
    fun dalvikHeapLimitIsNotANativeMemoryCap() {
        val result = engine.select(
            request(prof = profile(ramMb = 8192, availableMb = 4096, heapMb = 128)),
        )
        assertEquals(ModelFit.GOOD_FIT, result.fit)
        assertTrue(result.warnings.any { it.contains("heap", true) })
    }

    @Test
    fun insufficientAvailableMemoryMakesFitTighter() {
        val result = engine.select(request(prof = profile(ramMb = 8192, availableMb = 64)))
        assertEquals(ModelFit.TIGHT_FIT, result.fit)
        assertTrue(result.reasons.any { it.contains("available RAM", true) })
    }

    @Test
    fun matchingMeasurementsCanPreferCpu() {
        val matchDevice = MeasurementRecord(
            device = "QTI SM6225", abi = "arm64-v8a", modelPath = "/models/a.gguf",
            contextSize = 2048, kvQuant = "Q8_0", threads = 4,
            backend = Backend.CPU, tokensPerSecond = 20.0, gpuLayers = 0, status = "ok",
        )
        val matchVulkan = matchDevice.copy(backend = Backend.VULKAN, gpuLayers = 99, tokensPerSecond = 8.0)
        val result = engine.select(
            request(
                prof = profile(gfx = graphics("ok", true, true)),
                measurements = measurements(matchDevice, matchVulkan),
            ),
        )
        assertEquals(Backend.CPU, result.backend)
        assertEquals(DecisionBasis.MEASURED, result.basis)
    }

    @Test
    fun nonMatchingMeasurementsAreIgnored() {
        val foreign = MeasurementRecord(
            device = "Other Device", abi = "x86_64", modelPath = "/models/a.gguf",
            contextSize = 2048, kvQuant = "Q8_0", threads = 4,
            backend = Backend.CPU, tokensPerSecond = 40.0, gpuLayers = 0, status = "ok",
        )
        val result = engine.select(
            request(
                prof = profile(gfx = graphics("ok", true, true)),
                measurements = measurements(foreign),
            ),
        )
        assertEquals(Backend.CPU, result.backend)
        assertEquals(DecisionBasis.ESTIMATE, result.basis)
        assertTrue(result.reasons.any { it.contains("insufficient model-level evidence") })
    }

    @Test
    fun manualModelAndBackendOverridesWin() {
        val big = model("big", 6000, 7.0)
        val small = model("small", 300, 0.5)
        val result = engine.select(
            request(
                models = listOf(big, small), manualModelId = "big",
                manualBackend = Backend.VULKAN,
            ),
        )
        assertEquals("big", result.model?.id)
        assertEquals(Backend.VULKAN, result.backend)
        assertTrue(result.manualOverride)
        assertTrue(result.warnings.any { it.contains("not usable", true) })
        assertTrue(result.warnings.any { it.contains("Model Vulkan compatibility is unknown") })
    }
}
