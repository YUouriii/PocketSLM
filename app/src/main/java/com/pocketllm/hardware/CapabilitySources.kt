package com.pocketllm.hardware

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.opengl.EGL14
import android.opengl.GLES20
import com.pocketllm.engine.VulkanProbe
import java.io.File

/** Modular, independent collectors. Each source degrades to explicit unknown values. */
internal object CapabilitySources {

    private val FEATURE_FLAGS: Map<CpuFeature, Set<String>> = mapOf(
        CpuFeature.NEON to setOf("neon", "asimd"),
        CpuFeature.SVE to setOf("sve"),
        CpuFeature.SVE2 to setOf("sve2"),
        CpuFeature.AES to setOf("aes"),
        CpuFeature.SHA1 to setOf("sha1"),
        CpuFeature.SHA2 to setOf("sha2"),
        CpuFeature.FP16 to setOf("fp16", "fphp", "asimdhp"),
        CpuFeature.DOT_PRODUCT to setOf("dotprod", "asimddp"),
        CpuFeature.I8MM to setOf("i8mm"),
        CpuFeature.BF16 to setOf("bf16"),
        CpuFeature.AVX to setOf("avx"),
        CpuFeature.AVX2 to setOf("avx2"),
        CpuFeature.FMA to setOf("fma"),
    )

    fun readCapabilities(context: Context, probe: VulkanProbe): HardwareCapabilities {
        val now = System.currentTimeMillis()
        return HardwareCapabilities(
            soc = readSoc(probe),
            cpu = readCpu(),
            memory = readMemoryHardware(context),
            storage = readStorageHardware(context),
            graphics = readGraphics(context, probe),
            detectedAtMillis = now,
        )
    }

    fun readRuntime(context: Context): RuntimeConditions {
        val am = runCatching { context.getSystemService(ActivityManager::class.java) }.getOrNull()
        val mi = if (am != null) {
            runCatching {
                ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            }.getOrNull()
        } else null

        val storage = runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            StatFs(dir.absolutePath)
        }.getOrNull()

        val pm = runCatching { context.getSystemService(Context.POWER_SERVICE) as PowerManager }.getOrNull()

        val thermal = pm?.let { readThermal(it) }
        val battery = runCatching {
            val manager = context.getSystemService(BatteryManager::class.java)
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it >= 0 }
        }.getOrNull()

        return RuntimeConditions(
            capturedAtMillis = System.currentTimeMillis(),
            availableMemoryBytes = mi?.availMem,
            appHeapLimitBytes = runCatching { Runtime.getRuntime().maxMemory() }.getOrNull(),
            memoryClassMb = am?.let { runCatching { it.memoryClass }.getOrNull() },
            largeMemoryClassMb = am?.let { runCatching { it.largeMemoryClass }.getOrNull() },
            lowMemory = mi?.lowMemory,
            lowMemoryThresholdBytes = mi?.threshold,
            availableStorageBytes = storage?.let { runCatching { it.availableBytes }.getOrNull() },
            freeStorageBytes = storage?.let { runCatching { it.freeBytes }.getOrNull() },
            thermalStatus = thermal?.first,
            thermalStatusName = thermal?.second,
            powerSaveMode = pm?.let { runCatching { it.isPowerSaveMode }.getOrNull() },
            deviceIdleMode = pm?.let { runCatching { it.isDeviceIdleMode }.getOrNull() },
            batteryPercent = battery,
        )
    }

    private fun readSoc(probe: VulkanProbe): SocIdentity {
        val apiManufacturer = if (Build.VERSION.SDK_INT >= 31) {
            Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
        } else null
        val apiModel = if (Build.VERSION.SDK_INT >= 31) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
        } else null

        val manufacturer = apiManufacturer ?: systemProperty("ro.soc.manufacturer")
        val manufacturerSource = if (apiManufacturer != null) SocFieldSource.ANDROID_API
        else if (manufacturer != null) SocFieldSource.SYSTEM_PROPERTY
        else SocFieldSource.UNAVAILABLE

        val model = apiModel ?: systemProperty("ro.soc.model")
        val modelSource = if (apiModel != null) SocFieldSource.ANDROID_API
        else if (model != null) SocFieldSource.SYSTEM_PROPERTY
        else SocFieldSource.UNAVAILABLE

        val board = systemProperty("ro.board.platform")
        val family = systemProperty("ro.hardware.vulkan")
        val gpuProperty = systemProperty("ro.gpu.model") ?: systemProperty("ro.vendor.gpu.model")
        val probeDevice = probe.device.takeIf {
            it.isNotBlank() && !it.equals("unknown", ignoreCase = true)
        }
        // A precise GPU model is only reported when the OS, a read-only GL query
        // or the existing probe actually provides one. Never guessed from family.
        val glRenderer = readGlRenderer()
        val gpuModel = glRenderer ?: gpuProperty ?: probeDevice
        val gpuModelSource = when {
            glRenderer != null -> SocFieldSource.GL_RENDERER
            gpuProperty != null -> SocFieldSource.SYSTEM_PROPERTY
            probeDevice != null -> SocFieldSource.VULKAN_PROBE
            else -> SocFieldSource.UNAVAILABLE
        }
        return SocIdentity(
            manufacturer = manufacturer,
            model = model,
            boardPlatform = board,
            gpuFamily = family,
            gpuModel = gpuModel,
            manufacturerSource = manufacturerSource,
            modelSource = modelSource,
            boardSource = if (board != null) SocFieldSource.SYSTEM_PROPERTY else SocFieldSource.UNAVAILABLE,
            gpuFamilySource = if (family != null) SocFieldSource.SYSTEM_PROPERTY else SocFieldSource.UNAVAILABLE,
            gpuModelSource = gpuModelSource,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().takeIf { it.isNotBlank() },
        )
    }

    /** One-time, read-only GL renderer query; no model lifecycle or UI impact. */
    private fun readGlRenderer(): String? {
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            if (surface == EGL14.EGL_NO_SURFACE) return null
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return null
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            GLES20.glGetString(GLES20.GL_RENDERER)?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                    )
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    EGL14.eglTerminate(display)
                }
            }
        }
    }

    /** Generic system-property read, no device-specific key rewriting. */
    private fun systemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val value = clazz.getMethod("get", String::class.java).invoke(null, key) as? String
        value?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
    }.getOrNull()

    /** MemTotal is physical RAM; SwapTotal is kernel swap capacity, not vendor memory extension. */
    private fun readMemInfo(): Map<String, Long> = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 3 && parts[2] == "kB") {
                    parts[0].removeSuffix(":") to (parts[1].toLongOrNull()?.times(1024L) ?: return@mapNotNull null)
                } else null
            }.take(24).toMap()
        }
    }.getOrDefault(emptyMap())

    private fun readCpu(): CpuCapabilities {
        val abis = runCatching { Build.SUPPORTED_ABIS?.toList() }.getOrNull() ?: emptyList()
        val primary = abis.firstOrNull()
        val architecture = when {
            primary == null -> Architecture.UNKNOWN
            primary.startsWith("arm64") -> Architecture.ARM64
            primary.startsWith("armeabi") || primary == "arm" -> Architecture.ARM32
            primary.startsWith("x86_64") -> Architecture.X86_64
            primary == "x86" -> Architecture.X86
            else -> Architecture.UNKNOWN
        }
        val is64 = when (architecture) {
            Architecture.ARM64, Architecture.X86_64 -> true
            Architecture.ARM32, Architecture.X86 -> false
            Architecture.UNKNOWN -> null
        }

        val flags = readCpuFlags()
        val readable = flags != null
        val present = flags?.toSet() ?: emptySet()
        val allAliases = FEATURE_FLAGS.values.flatten().toSet()
        return CpuCapabilities(
            primaryAbi = primary,
            supportedAbis = abis,
            architecture = architecture,
            is64Bit = is64,
            processorCount = runCatching { Runtime.getRuntime().availableProcessors() }.getOrNull(),
            flagsReadable = readable,
            flagSource = if (readable) "/proc/cpuinfo" else null,
            features = FEATURE_FLAGS.map { (feature, aliases) ->
                CpuFeatureState(
                    feature = feature,
                    state = when {
                        !readable -> TriState.UNKNOWN
                        aliases.any { it in present } -> TriState.YES
                        else -> TriState.NO
                    },
                )
            },
            unclassifiedFlags = flags?.filterNot { it in allAliases }?.distinct() ?: emptyList(),
        )
    }

    /** Reads the first Features/flags line. Null means unavailable, not "no features". */
    private fun readCpuFlags(): List<String>? = runCatching {
        File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { line ->
                line.startsWith("Features", ignoreCase = true) || line.startsWith("flags", ignoreCase = true)
            }
        }
    }.getOrNull()?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }

    /** First backing device listed by the kernel, with provenance. */
    private fun readSwapBacking(): Pair<String?, String?> = runCatching {
        val lines = File("/proc/swaps").readLines()
        val device = lines.drop(1).mapNotNull { line ->
            line.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.startsWith("/dev/") }
        }.firstOrNull()
        device to "/proc/swaps"
    }.getOrDefault(null to null)

    /** Standard ZRAM block devices, when sysfs is accessible. */
    private fun readZramDevices(): Pair<List<String>, String?> = runCatching {
        // Some SELinux policies allow stat of a known node but deny listing /sys/block.
        val devices = (0 until 8)
            .map { "zram$it" }
            .filter { File("/sys/block/$it").exists() }
        devices to if (devices.isEmpty()) null else "/sys/block/zram*"
    }.getOrDefault(emptyList<String>() to null)

    private fun readMemoryHardware(context: Context): MemoryHardware {
        val am = runCatching { context.getSystemService(ActivityManager::class.java) }.getOrNull()
        val systemTotal = am?.let {
            runCatching {
                ActivityManager.MemoryInfo().also { info -> it.getMemoryInfo(info) }.totalMem
            }.getOrNull()
        }
        val memInfo = readMemInfo()
        val physical = memInfo["MemTotal"] ?: systemTotal
        val swapBacking = readSwapBacking()
        val zram = readZramDevices()
        return MemoryHardware(
            totalBytes = physical,
            source = if (memInfo.containsKey("MemTotal")) "/proc/meminfo" else
                if (systemTotal != null) "ActivityManager" else null,
            systemReportedTotalBytes = systemTotal,
            kernelSwapCapacityBytes = memInfo["SwapTotal"],
            kernelSwapCapacitySource = if (memInfo.containsKey("SwapTotal")) "/proc/meminfo" else null,
            swapBackingDevice = swapBacking.first,
            swapBackingSource = swapBacking.second,
            zramDevices = zram.first,
            zramSource = zram.second,
        )
    }

    private fun readStorageHardware(context: Context): StorageHardware {
        val stat = runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            StatFs(dir.absolutePath)
        }.getOrNull()
        return StorageHardware(
            path = runCatching {
                (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath
            }.getOrNull(),
            totalBytes = stat?.let { runCatching { it.totalBytes }.getOrNull() },
            blockSizeBytes = stat?.let { s ->
                runCatching {
                    s.javaClass.getMethod("getBlockSizeLong").invoke(s) as? Long
                }.getOrNull()
            },
        )
    }

    private fun readGraphics(context: Context, probe: VulkanProbe): GraphicsCapabilities {
        val features = runCatching {
            val pm = context.packageManager
            PackageManager::class.java.fields
                .filter { field ->
                    field.type == String::class.java &&
                        listOf("VULKAN", "OPENGLES", "GRAPHICS").any { it in field.name }
                }
                .mapNotNull { field ->
                    val name = runCatching { field.get(null) as? String }.getOrNull() ?: return@mapNotNull null
                    field.name to runCatching { pm.hasSystemFeature(name) }.getOrDefault(false)
                }
                .toMap()
        }.getOrDefault(emptyMap())

        return GraphicsCapabilities(
            platformFeatures = features,
            vulkanProbeAvailable = probe.available,
            vulkanProbeCompatible = probe.compatible,
            vulkanDevice = probe.device,
            vulkanVendor = probe.vendor,
            vulkanDriver = probe.driver,
            vulkanApi = probe.api,
            vulkanMissing = probe.missing,
            vulkanReason = probe.reason,
            vulkanChecks = probe.checks,
        )
    }

    private fun readThermal(pm: PowerManager): Pair<Int, String?>? {
        val status = runCatching {
            PowerManager::class.java.getMethod("getCurrentThermalStatus").invoke(pm) as? Int
        }.getOrNull() ?: return null
        val name = runCatching {
            PowerManager::class.java.fields
                .filter { it.type == Int::class.javaPrimitiveType && it.name.startsWith("THERMAL_STATUS_") }
                .firstOrNull { runCatching { it.getInt(null) == status }.getOrDefault(false) }
                ?.name
                ?.removePrefix("THERMAL_STATUS_")
        }.getOrNull()
        return status to name
    }
}
