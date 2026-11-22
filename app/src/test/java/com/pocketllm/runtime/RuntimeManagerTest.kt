package com.pocketllm.runtime

import com.pocketllm.hardware.Architecture
import com.pocketllm.hardware.CpuCapabilities
import com.pocketllm.hardware.GraphicsCapabilities
import com.pocketllm.hardware.HardwareCapabilities
import com.pocketllm.hardware.HardwareProfile
import com.pocketllm.hardware.MemoryHardware
import com.pocketllm.hardware.RuntimeConditions
import com.pocketllm.hardware.SocFieldSource
import com.pocketllm.hardware.SocIdentity
import com.pocketllm.hardware.StorageHardware
import com.pocketllm.hardware.TriState
import com.pocketllm.selection.Backend
import com.pocketllm.selection.MeasurementRecord
import com.pocketllm.selection.MetadataSource
import com.pocketllm.selection.ModelDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeManagerTest {

    private val mib = 1024L * 1024L

    private class FakeLoader : ModelLoader {
        override var loadedModelPath: String? = null
        override var loadedMmprojPath: String? = null
        override var loadedContextSize: Int = 0
        override var loadedThreads: Int = 0
        override var loadedKvQuant: String = ""
        override var loadedGpuLayers: Int = 0
        var loadCalls = 0
        var unloadCalls = 0
        var fail = false
        var lastGpuLayers = -1

        override suspend fun load(
            path: String,
            mmprojPath: String?,
            contextSize: Int,
            threads: Int,
            kvQuant: String,
            gpuLayers: Int,
        ): Result<Unit> {
            loadCalls++
            lastGpuLayers = gpuLayers
            if (fail) return Result.failure(IllegalStateException("native load failed"))
            loadedModelPath = path
            loadedMmprojPath = mmprojPath
            loadedContextSize = contextSize
            loadedThreads = threads
            loadedKvQuant = kvQuant
            loadedGpuLayers = gpuLayers
            return Result.success(Unit)
        }

        override suspend fun unload() {
            unloadCalls++
            loadedModelPath = null
        }
    }

    private fun profile(ramMb: Long, availableMb: Long, swapMb: Long = 0) = HardwareProfile(
        HardwareCapabilities(
            soc = SocIdentity(
                "QTI", "SM6225", "board", "adreno", "Test GPU",
                SocFieldSource.ANDROID_API, SocFieldSource.ANDROID_API,
                SocFieldSource.SYSTEM_PROPERTY, SocFieldSource.SYSTEM_PROPERTY, SocFieldSource.GL_RENDERER,
            ),
            cpu = CpuCapabilities(
                "arm64-v8a", listOf("arm64-v8a"), Architecture.ARM64, true, 8,
                true, "/proc/cpuinfo", emptyList(), emptyList(),
            ),
            memory = MemoryHardware(
                totalBytes = ramMb * mib, source = "/proc/meminfo",
                systemReportedTotalBytes = ramMb * mib,
                kernelSwapCapacityBytes = if (swapMb > 0) swapMb * mib else null,
                kernelSwapCapacitySource = if (swapMb > 0) "/proc/meminfo" else null,
                swapBackingDevice = null, swapBackingSource = null,
                zramDevices = emptyList(), zramSource = null,
            ),
            storage = StorageHardware("/data", 128L * mib, 4096),
            graphics = GraphicsCapabilities(
                emptyMap(), false, false, "unknown", "", "", "", "", "", emptyMap(),
                TriState.UNKNOWN,
            ),
            detectedAtMillis = 0L,
        ),
        RuntimeConditions(
            0L, availableMb * mib, 256L * mib, 256, 512, false, null,
            16L * mib, 16L * mib, 0, "NONE", false, false, 100,
        ),
    )

    private fun snapshot(occupied: Boolean, availableMb: Long) = MemorySnapshot(
        capturedAtMillis = 0L,
        physicalBytes = 8192L * mib,
        availableBytes = availableMb * mib,
        lowMemory = false,
        appHeapLimitBytes = 256L * mib,
        memoryClassMb = 256,
        largeMemoryClassMb = 512,
        thermalStatusName = "NONE",
        loadedModelPath = if (occupied) "/models/old.gguf" else null,
        loadedContextSize = if (occupied) 2048 else 0,
        loadedThreads = if (occupied) 4 else 0,
        loadedGpuLayers = 0,
        source = "test snapshot",
    )

    private fun model(id: String, sizeMb: Long) = ModelDescriptor(        id = id, displayName = id, path = "/models/$id.gguf",
        fileSizeBytes = sizeMb * mib, paramsB = 0.5, quantization = "Q4_K_M",
        architecture = null, contextLength = null, backendSupport = mapOf(Backend.CPU to TriState.YES),
        source = MetadataSource.CATALOG,
    )

    private fun request(
        descriptor: ModelDescriptor,
        threads: Int = 4,
        manual: Boolean = false,
    ) = LoadRequest(
        model = descriptor, mmprojPath = null, contextSize = 2048, threads = threads,
        kvQuant = "Q8_0", gpuLayers = 0, manualOverride = manual,
    )

    @Test
    fun tooLargeModelIsRejectedBeforeLoading() = runBlocking {
        val loader = FakeLoader()
        val manager = RuntimeManager(loader) { profile(4096, 2048) }
        val result = manager.ensureLoaded(request(model("huge", 6000)))
        assertEquals(LoadOutcome.REJECTED, result.outcome)
        assertEquals(0, loader.loadCalls)
        assertEquals(0, loader.unloadCalls)
    }

    @Test
    fun swapIsNotCountedAsPhysicalRam() = runBlocking {
        val loader = FakeLoader()
        val manager = RuntimeManager(loader) { profile(2048, 1024, swapMb = 8192) }
        val result = manager.ensureLoaded(request(model("big", 4000)))
        assertEquals(LoadOutcome.REJECTED, result.outcome)
        assertEquals(2048L * mib, result.admission?.estimate?.physicalBytes)
        assertEquals(false, result.admission?.estimate?.notes?.any { it.contains("swap", true) } == true)
    }

    @Test
    fun tightFitIsAdmittedWithWarning() = runBlocking {
        val loader = FakeLoader()
        val manager = RuntimeManager(loader) { profile(4096, 4096) }
        val result = manager.ensureLoaded(request(model("tight", 2000)))
        assertEquals(LoadOutcome.LOADED, result.outcome)
        assertEquals(AdmissionDecision.ALLOW_WITH_WARNING, result.admission?.decision)
        assertTrue(result.admission!!.warnings.isNotEmpty())
        assertEquals(1, loader.loadCalls)
    }

    @Test
    fun alreadyLoadedModelSkipsAdmissionAndLoad() = runBlocking {
        val loader = FakeLoader()
        val descriptor = model("loaded", 6000)
        loader.loadedModelPath = descriptor.path
        loader.loadedMmprojPath = null
        loader.loadedContextSize = 2048
        loader.loadedThreads = 4
        loader.loadedKvQuant = "Q8_0"
        loader.loadedGpuLayers = 0
        val manager = RuntimeManager(loader) { profile(2048, 512) }
        val result = manager.ensureLoaded(request(descriptor))
        assertEquals(LoadOutcome.ALREADY_LOADED, result.outcome)
        assertEquals(0, loader.loadCalls)
        assertEquals(0, loader.unloadCalls)
    }

    @Test
    fun switchingModelUnloadsPreviousModelFirst() = runBlocking {
        val loader = FakeLoader()
        loader.loadedModelPath = "/models/old.gguf"
        val manager = RuntimeManager(loader) { profile(8192, 4096) }
        val result = manager.ensureLoaded(request(model("new", 400)))
        assertEquals(LoadOutcome.LOADED, result.outcome)
        assertEquals(1, loader.unloadCalls)
        assertEquals(1, loader.loadCalls)
    }

    @Test
    fun manualOverrideLoadsDespiteRejection() = runBlocking {
        val loader = FakeLoader()
        val manager = RuntimeManager(loader) { profile(2048, 1024) }
        val result = manager.ensureLoaded(request(model("huge", 6000), manual = true))
        assertEquals(LoadOutcome.LOADED, result.outcome)
        assertEquals(AdmissionDecision.ALLOW_WITH_WARNING, result.admission?.decision)
        assertEquals(1, loader.loadCalls)
    }

    @Test
    fun failedNativeLoadIsReported() = runBlocking {
        val loader = FakeLoader().also { it.fail = true }
        val manager = RuntimeManager(loader) { profile(8192, 4096) }
        val result = manager.ensureLoaded(request(model("ok", 400)))
        assertEquals(LoadOutcome.FAILED, result.outcome)
        assertTrue(result.error is IllegalStateException)
    }

    @Test
    fun sameModelWithDifferentThreadsReloads() = runBlocking {
        val loader = FakeLoader()
        val descriptor = model("ok", 400)
        loader.loadedModelPath = descriptor.path
        loader.loadedMmprojPath = null
        loader.loadedContextSize = 2048
        loader.loadedThreads = 2
        loader.loadedKvQuant = "Q8_0"
        loader.loadedGpuLayers = 0
        val manager = RuntimeManager(loader) { profile(8192, 4096) }
        val result = manager.ensureLoaded(request(descriptor, threads = 6))
        assertEquals(LoadOutcome.LOADED, result.outcome)
        assertEquals(1, loader.loadCalls)
    }

    @Test
    fun generationActivePreventsReplacement() = runBlocking {
        val loader = FakeLoader()
        loader.loadedModelPath = "/models/old.gguf"
        val manager = RuntimeManager(loader) { profile(8192, 4096) }
        val req = request(model("new", 400)).copy(generationActive = true)
        val result = manager.ensureLoaded(req)
        assertEquals(LoadOutcome.REJECTED, result.outcome)
        assertEquals(0, loader.unloadCalls)
        assertEquals(0, loader.loadCalls)
        assertEquals(true, result.plan?.blockedByGeneration)
    }

    @Test
    fun memoryIsRefreshedAndPlanRecalculatedAfterUnload() = runBlocking {
        val loader = FakeLoader()
        loader.loadedModelPath = "/models/old.gguf"
        var captures = 0
        val snapshots = MemorySnapshotProvider { current ->
            captures++
            snapshot(current.loadedModelPath != null, if (current.loadedModelPath != null) 256 else 6144)
        }
        val manager = RuntimeManager(loader, snapshots) { profile(8192, 4096) }
        val result = manager.ensureLoaded(request(model("new", 400)))
        assertEquals(LoadOutcome.LOADED, result.outcome)
        assertEquals(2, captures)
        assertEquals(1, loader.unloadCalls)
        assertEquals(false, result.plan?.requiresUnload)
    }

    @Test
    fun matchingMeasurementBecomesMeasuredEvidence() = runBlocking {
        val loader = FakeLoader()
        val descriptor = model("ok", 400)
        val profile = profile(8192, 4096)
        val record = MeasurementRecord(
            device = "QTI SM6225", abi = "arm64-v8a", modelPath = descriptor.path,
            contextSize = 2048, kvQuant = "Q8_0", threads = 4,
            backend = Backend.CPU, tokensPerSecond = 20.0, gpuLayers = 0, status = "ok",
            rssMb = 900, hwmMb = 1200, loadMs = 900L,
        )
        val measurements = object : com.pocketllm.selection.MeasurementProvider {
            override fun records() = listOf(record)
        }
        val manager = RuntimeManager(loader) { profile }
        val result = manager.ensureLoaded(
            request(descriptor).copy(measurements = measurements, backend = Backend.CPU),
        )
        assertEquals(MemoryEvidence.MEASURED, result.plan?.breakdown?.evidence)
        assertEquals(1200L * mib, result.plan?.breakdown?.peakLoadingBytes)
    }

    @Test
    fun failedLoadCleansUpNativeModel() = runBlocking {
        val loader = FakeLoader().also { it.fail = true }
        loader.loadedModelPath = "/models/old.gguf"
        val manager = RuntimeManager(loader) { profile(8192, 4096) }
        val result = manager.ensureLoaded(request(model("new", 400)))
        assertEquals(LoadOutcome.FAILED, result.outcome)
        assertEquals(null, loader.loadedModelPath)
        assertTrue(loader.unloadCalls >= 1)
    }

    @Test
    fun manualBackendAndGpuLayersArePreserved() = runBlocking {
        val loader = FakeLoader()
        val manager = RuntimeManager(loader) { profile(2048, 1024) }
        val req = request(model("huge", 6000), manual = true).copy(
            backend = Backend.VULKAN, gpuLayers = 99,
        )
        val result = manager.ensureLoaded(req)
        assertEquals(LoadOutcome.LOADED, result.outcome)
        assertEquals(99, loader.lastGpuLayers)
    }
}
