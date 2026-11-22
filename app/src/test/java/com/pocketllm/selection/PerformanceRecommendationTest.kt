package com.pocketllm.selection

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceRecommendationTest {

    private fun profile() = HardwareProfile(
        HardwareCapabilities(
            SocIdentity(
                "QTI", "SM6225", "board", "adreno", "Adreno (TM) 610",
                SocFieldSource.ANDROID_API, SocFieldSource.ANDROID_API,
                SocFieldSource.SYSTEM_PROPERTY, SocFieldSource.SYSTEM_PROPERTY, SocFieldSource.GL_RENDERER,
            ),
            CpuCapabilities(
                "arm64-v8a", listOf("arm64-v8a"), Architecture.ARM64, true, 8,
                true, "/proc/cpuinfo", emptyList(), emptyList(),
            ),
            MemoryHardware(
                6000L * 1024 * 1024, "/proc/meminfo", 6000L * 1024 * 1024,
                null, null, null, null, emptyList(), null,
            ),
            StorageHardware("/data", 128L * 1024 * 1024, 4096),
            GraphicsCapabilities(
                emptyMap(), false, false, "unknown", "", "", "", "", "", emptyMap(), TriState.UNKNOWN,
            ),
            0L,
        ),
        RuntimeConditions(
            0L, 3000L * 1024 * 1024, 512L * 1024 * 1024, 256, 512, false, null,
            10L * 1024 * 1024, 10L * 1024 * 1024, 0, "NONE", false, false, 100,
        ),
    )

    private fun model(id: String = "a") = ModelDescriptor(
        id = id, displayName = id, path = "/models/$id.gguf",
        fileSizeBytes = 1200L * 1024 * 1024, paramsB = 2.0,
        quantization = "Q4_K_M", architecture = null, contextLength = null,
        backendSupport = emptyMap(), source = MetadataSource.CATALOG,
    )

    private fun record(
        path: String = "/models/a.gguf",
        tok: Double = 7.0,
        device: String = "QTI SM6225",
        abi: String = "arm64-v8a",
        quant: String = "Q4_K_M",
        backend: Backend = Backend.CPU,
        context: Int = 2048,
        kv: String = "Q8_0",
        threads: Int = 4,
    ) = MeasurementRecord(
        device = device, abi = abi, modelPath = path, contextSize = context,
        kvQuant = kv, threads = threads, backend = backend, tokensPerSecond = tok,
        gpuLayers = 0, status = "ok", rssMb = 1500, hwmMb = 2100,
        ttftMs = 420L, quantization = quant,
    )

    private fun provider(vararg records: MeasurementRecord) = object : MeasurementProvider {
        override fun records() = records.toList()
    }

    private fun recommend(
        records: MeasurementProvider,
        model: ModelDescriptor = model(),
        pref: String = "recommended",
    ) = PerformanceAnalyzer.recommend(
        model = model, profile = profile(), contextSize = 2048, kvQuant = "Q8_0",
        threads = 4, backend = Backend.CPU, measurements = records.records(),
    ).let { it to PerformanceAnalyzer.priority(it, pref) }

    @Test
    fun categoryBoundaries() {
        assertEquals(PerformanceCategory.SMOOTH, PerformanceAnalyzer.categorize(6.0))
        assertEquals(PerformanceCategory.USABLE, PerformanceAnalyzer.categorize(3.0))
        assertEquals(PerformanceCategory.USABLE, PerformanceAnalyzer.categorize(5.99))
        assertEquals(PerformanceCategory.SLOW, PerformanceAnalyzer.categorize(2.99))
        assertEquals(PerformanceCategory.UNKNOWN, PerformanceAnalyzer.categorize(null))
    }

    @Test
    fun matchingMeasurementIsUsed() {
        val (rec, priority) = recommend(provider(record(tok = 7.0)))
        assertEquals(PerformanceCategory.SMOOTH, rec.category)
        assertEquals(7.0, rec.tokensPerSecond ?: 0.0, 0.001)
        assertEquals(420L, rec.ttftMs)
        assertEquals(2100L * 1024 * 1024, rec.memoryBytes)
        assertTrue(rec.isMeasured)
        assertTrue(priority > 0)
    }

    @Test
    fun incompatibleMeasurementsAreRejected() {
        val foreignDevice = record(device = "Other Device")
        val wrongQuant = record(quant = "F16")
        val differentBackend = record(backend = Backend.VULKAN)
        for (bad in listOf(foreignDevice, wrongQuant, differentBackend)) {
            val rec = recommend(provider(bad)).first
            assertEquals(PerformanceCategory.UNKNOWN, rec.category)
            assertNull(rec.tokensPerSecond)
        }
    }

    @Test
    fun unknownPerformanceWithoutReliableMeasurement() {
        val rec = recommend(provider()).first
        assertEquals(PerformanceCategory.UNKNOWN, rec.category)
        assertEquals(DecisionBasis.ESTIMATE, rec.evidence)
        assertTrue(rec.warnings.any { it.contains("No matching", true) })
    }

    @Test
    fun matchingMeasurementWithDifferentRuntimeConfigIsApproximate() {
        val rec = recommend(provider(record(kv = "q4_0", threads = 8))).first
        assertEquals(PerformanceCategory.SMOOTH, rec.category)
        assertTrue(rec.isMeasured)
        assertTrue(rec.approximate)
        assertEquals(Confidence.MEDIUM, rec.confidence)
    }

    @Test
    fun manualChoiceOverridesRecommendation() {
        val fast = model("fast")
        val slow = model("slow")
        val records = provider(record(path = fast.path, tok = 9.0), record(path = slow.path, tok = 2.0))
        val result = ModelSelectionEngine().select(
            SelectionRequest(
                profile = profile(), candidates = listOf(fast, slow), contextSize = 2048,
                kvQuant = "Q8_0", threads = 4, manualModelId = "slow",
                measurements = records, performancePreference = "recommended",
            ),
        )
        assertEquals("slow", result.model?.id)
        assertTrue(result.manualOverride)
    }

    @Test
    fun preferenceOrdersAutomaticCandidatesWithoutHiding() {
        val fast = model("fast")
        val slow = model("slow")
        val records = provider(record(path = fast.path, tok = 9.0), record(path = slow.path, tok = 2.0))
        val result = ModelSelectionEngine().select(
            SelectionRequest(
                profile = profile(), candidates = listOf(slow, fast), contextSize = 2048,
                kvQuant = "Q8_0", threads = 4, measurements = records,
                performancePreference = "recommended",
            ),
        )
        assertEquals("fast", result.model?.id)
        assertEquals(1, result.alternatives.size)
    }
}
