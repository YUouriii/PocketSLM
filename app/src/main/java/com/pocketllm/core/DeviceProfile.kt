package com.pocketllm.core

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import java.io.File

enum class DeviceTier { LIGHT, MID, STRONG }

/**
 * What the phone can actually do. Used to warn users before they download a
 * model their device cannot run, since the app targets a very wide hardware range.
 */
data class DeviceProfile(
    val totalRamMb: Long,
    val availRamMb: Long,
    val cores: Int,
    val bigCores: Int,
    val abi: String,
    val sdkInt: Int,
    val freeStorageMb: Long,
    val model: String,
    val batteryPct: Int = -1,
) {
    val tier: DeviceTier
        get() = when {
            totalRamMb >= 7500 -> DeviceTier.STRONG
            totalRamMb >= 5000 -> DeviceTier.MID
            else -> DeviceTier.LIGHT
        }

    /**
     * Threads for token generation. On big.LITTLE parts the big cluster alone
     * measured clearly faster than adding the little cores: a 0.8B model on a
     * 4+4 Snapdragon 680 reached ~10 tok/s with 4 threads versus ~7 tok/s with
     * 8. Leave the little cores for Android and the UI.
     */
    fun suggestedThreads(): Int {
        val suggested = if (bigCores >= cores) cores - 2 else bigCores
        return suggested.coerceIn(2, 8).coerceAtMost(cores)
    }

    companion object {
        fun detect(context: Context): DeviceProfile {
            val am = context.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)

            val cores = Runtime.getRuntime().availableProcessors()
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val stat = StatFs(dir.absolutePath)

            val batteryPct = try {
                context.getSystemService(BatteryManager::class.java)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } catch (t: Throwable) {
                -1
            }

            return DeviceProfile(
                totalRamMb = mi.totalMem / (1024 * 1024),
                availRamMb = mi.availMem / (1024 * 1024),
                cores = cores,
                bigCores = bigCoreCount(cores),
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                sdkInt = Build.VERSION.SDK_INT,
                freeStorageMb = stat.availableBytes / (1024 * 1024),
                model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                batteryPct = batteryPct,
            )
        }

        /**
         * Reads the max frequency of each core. Cores within 10% of the fastest
         * form the big cluster on every normal big.LITTLE layout.
         */
        private fun bigCoreCount(cores: Int): Int {
            val freqs = (0 until cores).mapNotNull { i ->
                runCatching {
                    File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                        .readText().trim().toLong()
                }.getOrNull()
            }
            if (freqs.isEmpty()) return (cores / 2).coerceIn(2, 6)
            val fastest = freqs.max()
            return freqs.count { it >= fastest * 0.9 }.coerceIn(2, freqs.size)
        }
    }
}
