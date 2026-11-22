package com.pocketllm

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.pocketllm.core.DeviceProfile
import com.pocketllm.data.SessionStore
import com.pocketllm.data.HuggingFace
import com.pocketllm.data.ModelStore
import com.pocketllm.data.Settings
import com.pocketllm.engine.InferenceEngine
import com.pocketllm.engine.LlamaBridge
import com.pocketllm.engine.VulkanProbe
import com.pocketllm.hardware.HardwareRegistry
import com.pocketllm.runtime.RuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiny manual DI graph. One process-wide instance of the heavy objects so the
 * model stays loaded while the user moves between tabs.
 */
object Graph {

    private lateinit var appContext: Context

    lateinit var device: DeviceProfile
        private set

    lateinit var settings: Settings
        private set

    lateinit var models: ModelStore
        private set

    lateinit var chats: SessionStore
        private set

    lateinit var engine: InferenceEngine
        private set

    lateinit var runtime: RuntimeManager
        private set

    val hf: HuggingFace by lazy { HuggingFace() }

    /** One cached reader for the app's own benchmark records. */
    val measurements by lazy { com.pocketllm.selection.BenchmarkMeasurements(appContext) }

    /** Localized strings for small formatting done outside Composables. */
    fun string(resId: Int, vararg args: Any): String = appContext.getString(resId, *args)

    /** Set by the benchmark dev-hook so the UI does not reload a different model. */
    @Volatile
    var benchMode = false

    /**
     * One-time Vulkan capability probe. Cached in native code and here so no
     * model load ever has to fail just to discover the device is incompatible.
     */
    private val _vulkanProbe = MutableStateFlow(
        runCatching { VulkanProbe.parse(LlamaBridge.nativeVulkanProbe()) }
            .getOrDefault(VulkanProbe.parse("vulkan=0\ncompatible=0\nreason=probe_failed\n"))
    )
    val vulkanProbe = _vulkanProbe.asStateFlow()

    /**
     * Last known thermal status: -1 when the API is unavailable (older OS),
     * otherwise the PowerManager thermal status value.
     */
    private val _thermalStatus = MutableStateFlow(-1)
    val thermalStatus = _thermalStatus.asStateFlow()

    @Volatile
    private var thermalSevereCode = -1

    private val thermalScope = CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    fun init(context: Context) {
        appContext = context.applicationContext
        device = DeviceProfile.detect(appContext)
        // Foundation registry: static facts once, dynamic conditions on demand.
        // It reuses the existing probe result and starts no polling of its own.
        HardwareRegistry.init(appContext) { _vulkanProbe.value }
        settings = Settings(appContext, device.suggestedThreads())
        models = ModelStore(appContext)
        chats = SessionStore(appContext)
        engine = InferenceEngine(appContext.applicationInfo.nativeLibraryDir) {
            _vulkanProbe.value.compatible
        }
        runtime = RuntimeManager(engine) { HardwareRegistry.current() }
        Log.i("PocketSLM", "vulkan compatibility: ${_vulkanProbe.value}")
        thermalSevereCode = thermalCode("THERMAL_STATUS_SEVERE")
        startThermalWatcher()
    }

    fun context(): Context = appContext

    private fun thermalCode(name: String): Int = try {
        PowerManager::class.java.getField(name).getInt(null)
    } catch (t: Throwable) {
        -1
    }

    private fun readThermal(): Int = try {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        PowerManager::class.java.getMethod("getCurrentThermalStatus").invoke(pm) as Int
    } catch (t: Throwable) {
        -1
    }

    /**
     * Polls the thermal state. When the device is in sustained severe thermal
     * throttle the engine lowers generation to [InferenceEngine.thermalThreads]
     * and restores the previous setting once it cools down again. A short dwell
     * at the threshold prevents flapping between configurations.
     */
    private fun startThermalWatcher() {
        if (thermalSevereCode < 0) return
        thermalScope.launch {
            var severeSince: Long? = null
            var coolSince: Long? = null
            val pollMs = 5_000L
            while (true) {
                val status = readThermal()
                _thermalStatus.value = status
                val now = System.currentTimeMillis()
                if (status >= thermalSevereCode) {
                    if (severeSince == null) severeSince = now
                    coolSince = null
                    if (now - severeSince >= SEVERE_DWELL_MS) engine.onThermalSevere()
                } else {
                    severeSince = null
                    if (engine.thermalActive) {
                        if (coolSince == null) coolSince = now
                        if (now - coolSince >= COOL_DWELL_MS) engine.onThermalRecovered()
                    }
                }
                delay(pollMs)
            }
        }
    }

    private const val SEVERE_DWELL_MS = 30_000L
    private const val COOL_DWELL_MS = 60_000L
}

class PocketLlmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
