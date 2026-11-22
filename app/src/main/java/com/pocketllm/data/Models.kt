package com.pocketllm.data

import kotlinx.serialization.Serializable

/** A media attachment tied to a chat message, persisted with the session. */
@Serializable
data class AttachmentRef(
    val kind: String,  // "photo" | "voice"
    val path: String,  // absolute path in app cache storage
    val name: String,  // original file name
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val ts: Long = 0L,
    val attachments: List<AttachmentRef> = emptyList(),
) {
    val isUser: Boolean get() = role == ROLE_USER

    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/** A model file that exists on this device. */
@Serializable
data class InstalledModel(
    val id: String,
    val repo: String,
    val file: String,
    val displayName: String,
    val params: String,
    val quant: String,
    val sizeMb: Long,
    val path: String,
    val license: String? = null,
    val installedAt: Long = 0L,
    val mmprojPath: String? = null,
)

/**
 * An entry the user can download. The built-in list is curated so that people on
 * weak phones are not pushed towards models they cannot run.
 */
data class CatalogModel(
    val id: String,
    val name: String,
    val params: String,
    val publisher: String,
    val repo: String,
    val file: String,
    val sizeMb: Long,
    val quant: String,
    val minRamMb: Long,
    val license: String,
    val licenseUrl: String,
    val description: String,
    val gated: Boolean = false,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val mmprojFile: String? = null,
    val mmprojSizeMb: Long = 0L,
)

enum class Fit { GOOD, TIGHT, TOO_BIG }

/**
 * Share of physical RAM a model may claim before the device starts feeling
 * pressure. Keeping this conservative matters more than raw parameter count:
 * Android, the UI and the KV cache all need room at the same time.
 */
const val COMFORT_RAM_FRACTION = 0.55

private const val MIB = 1024L * 1024L

/** RAM a model may use while leaving the phone comfortable. */
fun comfortBudgetBytes(totalBytes: Long): Long = when {
    totalBytes >= 8000L * MIB -> (totalBytes * 0.65).toLong()
    totalBytes >= 6000L * MIB -> (totalBytes * 0.58).toLong()
    else -> (totalBytes * COMFORT_RAM_FRACTION).toLong()
}

fun comfortBudgetMb(totalRamMb: Long): Long = comfortBudgetBytes(totalRamMb * MIB) / MIB

/** What a model is good at, shown as badges on its catalog card. */
@Serializable
data class ModelCapabilities(
    val reasoning: Boolean = false,
    val vision: Boolean = false,
    val audio: Boolean = false,
    val coding: Boolean = false,
) {
    val any: Boolean get() = reasoning || vision || audio || coding
}

fun CatalogModel.fit(totalRamMb: Long): Fit {
    // Weights + runtime overhead (KV cache, compute buffers, Android itself),
    // but never below the curated requirement checked for this entry.
    val need = maxOf(sizeMb + 900, minRamMb)
    return when {
        need > totalRamMb * 0.90 -> Fit.TOO_BIG
        need > comfortBudgetMb(totalRamMb) -> Fit.TIGHT
        else -> Fit.GOOD
    }
}
