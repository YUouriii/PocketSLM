package com.pocketllm

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import android.os.SystemClock
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import android.util.Log
import com.pocketllm.data.ChatMessage
import com.pocketllm.data.SettingsState
import com.pocketllm.engine.GenEvent
import com.pocketllm.hardware.HardwareRegistry
import com.pocketllm.engine.GenParams
import com.pocketllm.engine.LlamaBridge
import com.pocketllm.ui.PocketLlmRoot
import java.io.File
import com.pocketllm.ui.theme.PocketLlmTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // Runtime conditions are refreshed when the app becomes active again,
        // not on a timer; static capabilities stay untouched.
        HardwareRegistry.refreshRuntime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the app's auto-load in sync with the benchmark configuration so
        // the ChatScreen cannot reload the model with different ctx/KV/GPU settings.
        if (intent?.getStringExtra("bench")?.isNotBlank() == true) {
            Graph.benchMode = true
            Graph.settings.update { s ->
                s.copy(
                    contextSize = intent?.getIntExtra("ctx", 0)?.takeIf { it > 0 } ?: s.contextSize,
                    threads = intent?.getIntExtra("threads", 0)?.takeIf { it > 0 } ?: s.threads,
                    kvQuant = intent?.getStringExtra("kv")?.takeIf { it.isNotBlank() } ?: s.kvQuant,
                    gpuLayers = intent?.getIntExtra("gpu", -1)?.takeIf { it >= 0 } ?: s.gpuLayers,
                )
            }
        }

        // Model switch hook used by automated on-device verification; a normal
        // launch never passes this extra and keeps the existing selection flow.
        intent?.getStringExtra("model")?.takeIf { it.isNotBlank() }?.let { modelId ->
            Graph.settings.update { it.copy(activeModelId = modelId) }
        }
        intent?.getStringExtra("perf")?.takeIf { it.isNotBlank() }?.let { value ->
            Graph.settings.update { it.copy(performancePreference = value) }
        }
        intent?.getStringExtra("mem")?.takeIf { it.isNotBlank() }?.let { value ->
            Graph.settings.update { it.copy(memoryMode = value) }
        }

        setContent {
            val theme = Graph.settings.state.collectAsStateWithLifecycle().value.theme
            val systemDark = isSystemInDarkTheme()
            val dark = when (theme) {
                SettingsState.THEME_DARK -> true
                SettingsState.THEME_LIGHT -> false
                else -> systemDark
            }
            PocketLlmTheme(darkTheme = dark) {
                val view = LocalView.current
                SideEffect {
                    val window = (view.context as Activity).window
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                    // Keep overscroll zones the same color as the theme background.
                    window.setBackgroundDrawable(ColorDrawable(if (dark) Color.parseColor("#0E0E10") else Color.WHITE))
                }
                PocketLlmRoot(intent?.getStringExtra("tab"))
            }
        }

        // Developer hook: `am start ... --es bench <path>` loads a model and
        // generates a short reply, logging prompt and token throughput.
        intent?.getStringExtra("bench")?.takeIf { it.isNotBlank() }?.let { path ->
            val threads = intent?.getIntExtra("threads", 0)?.takeIf { it > 0 }
            val ctx = intent?.getIntExtra("ctx", 0)?.takeIf { it > 0 }
            val cancelMs = intent?.getIntExtra("cancelMs", 0)?.takeIf { it > 0 }
            val kv = intent?.getStringExtra("kv")?.takeIf { it.isNotBlank() }
            val gpu = intent?.getIntExtra("gpu", -1)?.takeIf { it >= 0 }
            val trials = intent?.getIntExtra("trials", 1)?.coerceAtLeast(0) ?: 1
            val warmup = intent?.getIntExtra("warmup", 0)?.coerceAtLeast(0) ?: 0
            val sustainMs = intent?.getLongExtra("sustainMs", 0)?.takeIf { it > 0 } ?: 0L
            val promptText = intent?.getStringExtra("prompt")?.takeIf { it.isNotBlank() }
            val promptFile = intent?.getStringExtra("promptFile")?.takeIf { it.isNotBlank() }
            val maxTokens = intent?.getIntExtra("maxTokens", 0)?.takeIf { it > 0 }
            val temp = intent?.getFloatExtra("temp", -1f)?.takeIf { it >= 0f }
            val topP = intent?.getFloatExtra("topP", -1f)?.takeIf { it in 0f..1f }
            val topK = intent?.getIntExtra("topK", 0)?.takeIf { it > 0 }
            val benchId = intent?.getStringExtra("benchId")?.takeIf { it.isNotBlank() }
            val severe = intent?.getBooleanExtra("severe", false) == true
            val cool = intent?.getBooleanExtra("cool", false) == true
            lifecycleScope.launch {
                runBenchmark(
                    path, threads, ctx, cancelMs, kv, gpu, trials, warmup, sustainMs,
                    promptText, promptFile, severe, cool, maxTokens, temp, topP, topK, benchId,
                )
            }
        }
    }

    private suspend fun runBenchmark(
        path: String, threads: Int?, ctx: Int?, cancelMs: Int?, kv: String?, gpu: Int?,
        trials: Int, warmup: Int, sustainMs: Long, promptText: String?, promptFile: String?,
        severe: Boolean, cool: Boolean, maxTokensOverride: Int?, tempOverride: Float?,
        topPOverride: Float?, topKOverride: Int?, benchId: String?,
    ) {
        val cfg = Graph.settings.current
        val nThreads = threads ?: cfg.threads
        val nCtx = ctx ?: cfg.contextSize
        val kvQuant = kv ?: cfg.kvQuant
        val gpuLayers = gpu ?: cfg.gpuLayers
        val maxTokens = maxTokensOverride ?: 64
        val temperature = tempOverride ?: 0.7f
        val topP = topPOverride ?: 0.9f
        val topK = topKOverride ?: 40
        val repeatPenalty = 1.1f
        val params = GenParams(
            maxTokens = maxTokens,
            minTokens = intent?.getIntExtra("minTokens", 0) ?: 0,
            temperature = temperature,
            topP = topP,
            topK = topK,
            repeatPenalty = repeatPenalty,
            thinkingBudget = 0,
            thinkingSupported = false,
        )
        val runId = benchId ?: "bench-${System.currentTimeMillis()}"
        val benchPrompt = try {
            promptFile?.let { File(it).readText().trim() }?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.e("PocketSLM", "bench prompt file failed: ${t.message}")
            null
        } ?: promptText ?: "Write one short sentence about the sea."
        val promptHash = com.pocketllm.bench.BenchRecorder.sha256(benchPrompt)
        val followup = "Now describe a mountain in one sentence."
        val followupHash = com.pocketllm.bench.BenchRecorder.sha256(followup)

        val t0 = SystemClock.elapsedRealtime()
        val loaded = Graph.engine.load(path, null, nCtx, nThreads, kvQuant, gpuLayers)
        val loadMs = SystemClock.elapsedRealtime() - t0
        Log.i(
            "PocketSLM",
            "bench: load $loaded in ${loadMs}ms (threads=$nThreads, ctx=$nCtx, " +
                "kv=$kvQuant, gpuLayers=$gpuLayers, vulkanOK=${Graph.vulkanProbe.value.compatible}, " +
                "thermal=${Graph.thermalStatus.value}, battery=${Graph.device.batteryPct}, " +
                "ram=${rssMb()}MB, hwm=${vmHwmMb()}MB, " +
                "activeThreads=${Graph.engine.loadedThreads})",
        )
        if (loaded.isFailure) return

        val meta = org.json.JSONObject()
            .put("benchId", runId)
            .put("startedAt", System.currentTimeMillis())
            .put("llamaVersion", runCatching { LlamaBridge.nativeLlamaVersion() }.getOrDefault("unknown"))
            .put("appVersion", runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("unknown"))
            .put("appVersionCode", runCatching {
                val pi = packageManager.getPackageInfo(packageName, 0)
                if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            }.getOrDefault(-1L))
            .put("buildType", if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) "debug" else "release")
            .put("device", Graph.device.model)
            .put("androidRelease", android.os.Build.VERSION.RELEASE)
            .put("androidSdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .put("totalRamMb", Graph.device.totalRamMb)
            .put("bigCores", Graph.device.bigCores)
            .put("suggestedThreads", Graph.device.suggestedThreads())
            .put("modelPath", path)
            .put("modelDescription", Graph.engine.loadedModelInfo?.description ?: "unknown")
            .put("quant", Regex("Q\\d+(_K)?_[A-Z0-9]+|IQ\\d+_\\w+|F16|BF16").find(path)?.value ?: "unknown")
            .put("ctx", nCtx)
            .put("kv", kvQuant)
            .put("threads", nThreads)
            .put("gpuLayers", gpuLayers)
            .put("maxTokens", maxTokens)
            .put("temperature", temperature.toDouble())
            .put("topP", topP.toDouble())
            .put("topK", topK)
            .put("repeatPenalty", repeatPenalty.toDouble())
            .put("promptSha256", promptHash)
            .put("followupSha256", followupHash)
            .put("promptChars", benchPrompt.length)
            .put("warmupRuns", warmup)
            .put("measuredRuns", trials)
        com.pocketllm.bench.BenchRecorder.start(this, meta)

        if (severe) {
            Graph.engine.onThermalSevere()
            Log.i("PocketSLM", "bench: after severe activeThreads=${Graph.engine.loadedThreads}")
        }
        if (cancelMs != null) {
            lifecycleScope.launch {
                delay(cancelMs.toLong())
                Log.i("PocketSLM", "bench: cancelling")
                Graph.engine.cancel()
            }
        }

        fun writeRecord(
            phase: String, trialNo: Int, turn: Int, userPromptHash: String,
            userPrompt: String, tokens: Int, emittedPieces: Int, elapsedMs: Long, ttftMs: Long,
            status: String, error: String?, cancelled: Boolean,
            progress: List<Triple<Int, Long, Long>>, reply: String,
        ) {
            val tps = if (elapsedMs > 0) tokens * 1000.0 / elapsedMs else 0.0
            val rec = org.json.JSONObject()
                .put("benchId", runId)
                .put("phase", phase)
                .put("trial", trialNo)
                .put("turn", turn)
                .put("promptSha256", userPromptHash)
                .put("promptChars", userPrompt.length)
                .put("modelPath", path)
                .put("ctx", nCtx)
                .put("kv", kvQuant)
                .put("threads", nThreads)
                .put("gpuLayers", gpuLayers)
                .put("maxTokens", maxTokens)
                .put("temperature", temperature.toDouble())
                .put("topP", topP.toDouble())
                .put("topK", topK)
                .put("repeatPenalty", repeatPenalty.toDouble())
                .put("loadMs", loadMs)
                .put("tokens", tokens)
                .put("emittedPieces", emittedPieces)
                .put("elapsedMs", elapsedMs)
                .put("tokPerSec", tps)
                .put("ttftMs", ttftMs)
                .put("promptProcessingMs", org.json.JSONObject.NULL) // filled from native log
                .put("rssMb", rssMb())
                .put("hwmMb", vmHwmMb())
                .put("progress", com.pocketllm.bench.BenchRecorder.progress(progress))
                .put("status", status)
                .put("eosLikely", status == "complete" && tokens < maxTokens)
                .put("limitReached", status == "complete" && tokens >= maxTokens)
                .put("cancelled", cancelled)
                .put("error", error ?: org.json.JSONObject.NULL)
                .put("reply", reply)
                .put("ts", System.currentTimeMillis())
            com.pocketllm.bench.BenchRecorder.append(this@MainActivity, rec)
            Log.i("PocketSLM", "bench record: phase=$phase trial=$trialNo turn=$turn " +
                "status=$status tokens=$tokens tps=${"%.2f".format(tps)} ttft=${ttftMs}ms " +
                "promptSha=$userPromptHash")
        }

        val deadline = SystemClock.elapsedRealtime() + sustainMs
        val runCount = if (sustainMs > 0) Int.MAX_VALUE else warmup + trials
        var run = 0
        while (run < runCount) {
            run++
            if (sustainMs > 0 && SystemClock.elapsedRealtime() >= deadline) break
            val phase = if (run <= warmup) "warmup" else "measured"
            val trialNo = if (phase == "warmup") run else run - warmup

            val history = listOf(ChatMessage(ChatMessage.ROLE_USER, benchPrompt, System.currentTimeMillis()))
            val reply = StringBuilder()
            val progress = mutableListOf<Triple<Int, Long, Long>>()
            val genStart = SystemClock.elapsedRealtime()
            var firstTokenMs = -1L
            var tokenCount = 0
            var status = "incomplete"
            var errorText: String? = null
            var cancelled = false
            var nativeGenMs = -1L
            var nativeTokens = 0
            Graph.engine.generate(history, params).collect { event ->
                when (event) {
                    is GenEvent.Token -> {
                        if (firstTokenMs < 0) firstTokenMs = SystemClock.elapsedRealtime() - genStart
                        reply.append(event.text)
                        tokenCount++
                        if (tokenCount % 16 == 0) {
                            val r = rssMb(); val h = vmHwmMb()
                            progress.add(Triple(tokenCount, r, h))
                            Log.i("PocketSLM", "bench trial $trialNo progress: tok=$tokenCount ram=${r}MB hwm=${h}MB")
                        }
                    }
                    is GenEvent.Done -> {
                        status = "complete"; cancelled = event.cancelled
                        nativeGenMs = event.elapsedMs; nativeTokens = event.tokens
                    }
                    is GenEvent.Error -> { status = "error"; errorText = event.message }
                }
            }
            writeRecord(phase, trialNo, 1, promptHash, benchPrompt, nativeTokens, tokenCount,
                if (nativeGenMs >= 0) nativeGenMs else SystemClock.elapsedRealtime() - genStart,
                if (firstTokenMs >= 0) firstTokenMs else -1L, status, errorText, cancelled,
                progress.toList(), reply.toString().trim())

            val history2 = history + listOf(
                ChatMessage(ChatMessage.ROLE_ASSISTANT, reply.toString(), System.currentTimeMillis()),
                ChatMessage(ChatMessage.ROLE_USER, followup, System.currentTimeMillis()),
            )
            val reply2 = StringBuilder()
            val progress2 = mutableListOf<Triple<Int, Long, Long>>()
            val gen2Start = SystemClock.elapsedRealtime()
            var first2 = -1L
            var tokens2 = 0
            var status2 = "incomplete"
            var error2: String? = null
            var cancelled2 = false
            var nativeGenMs2 = -1L
            var nativeTokens2 = 0
            Graph.engine.generate(history2, params).collect { event ->
                when (event) {
                    is GenEvent.Token -> {
                        if (first2 < 0) first2 = SystemClock.elapsedRealtime() - gen2Start
                        reply2.append(event.text)
                        tokens2++
                        if (tokens2 % 16 == 0) {
                            val r = rssMb(); val h = vmHwmMb()
                            progress2.add(Triple(tokens2, r, h))
                        }
                    }
                    is GenEvent.Done -> {
                        status2 = "complete"; cancelled2 = event.cancelled
                        nativeGenMs2 = event.elapsedMs; nativeTokens2 = event.tokens
                    }
                    is GenEvent.Error -> { status2 = "error"; error2 = event.message }
                }
            }
            writeRecord(phase, trialNo, 2, followupHash, followup, nativeTokens2, tokens2,
                if (nativeGenMs2 >= 0) nativeGenMs2 else SystemClock.elapsedRealtime() - gen2Start,
                if (first2 >= 0) first2 else -1L, status2, error2, cancelled2,
                progress2.toList(), reply2.toString().trim())
        }

        if (cool) {
            Graph.engine.onThermalRecovered()
            Log.i("PocketSLM", "bench: after recovered activeThreads=${Graph.engine.loadedThreads}")
        }
    }

    /** App resident set size in MB (0 when unavailable). */
    private fun rssMb(): Long = readStatusKb("VmRSS") / 1024

    /** Peak resident set size in MB (VmHWM from /proc/self/status). */
    private fun vmHwmMb(): Long = readStatusKb("VmHWM") / 1024

    private fun readStatusKb(key: String): Long {
        return runCatching {
            java.io.File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("$key:") }?.let {
                    it.substringAfter(":").trim().substringBefore(" kB").trim().toLongOrNull() ?: 0L
                } ?: 0L
            }
        }.getOrDefault(0L)
    }
}
