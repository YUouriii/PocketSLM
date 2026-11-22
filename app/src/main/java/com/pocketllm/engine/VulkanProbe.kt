package com.pocketllm.engine

/**
 * Result of the one-time Vulkan device capability probe.
 *
 * "available" means a Vulkan loader/driver exists; "compatible" means the
 * physical device actually supports the features llama.cpp's Vulkan backend
 * requires. The two are deliberately separate: a device can expose Vulkan but
 * still be unable to run LLM inference on it.
 */
data class VulkanProbe(
    val available: Boolean,
    val compatible: Boolean,
    val device: String,
    val vendor: String,
    val driver: String,
    val api: String,
    val checks: Map<String, String>,
    val missing: String,
    val reason: String,
    val raw: String,
) {
    companion object {
        fun parse(raw: String): VulkanProbe {
            val m = raw.lineSequence().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
            }.toMap()
            return VulkanProbe(
                available = m["vulkan"] == "1",
                compatible = m["compatible"] == "1",
                device = m["device"] ?: "unknown",
                vendor = m["vendor"] ?: "",
                driver = m["driver"] ?: "",
                api = m["api"] ?: "",
                checks = m,
                missing = m["missing"] ?: "",
                reason = m["reason"] ?: "",
                raw = raw,
            )
        }
    }
}
