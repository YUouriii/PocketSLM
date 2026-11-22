package com.pocketllm.core

import java.util.Locale

fun formatMb(mb: Long): String = when {
    mb >= 1024 -> String.format(Locale.US, "%.1f GB", mb / 1024.0)
    else -> "$mb MB"
}

fun formatRam(mb: Long): String = when {
    mb >= 1024 -> String.format(Locale.US, "%.0f GB", mb / 1024.0)
    else -> "$mb MB"
}

fun formatSpeed(tokens: Int, elapsedMs: Long): String {
    if (elapsedMs <= 0L) return "0.0"
    return String.format(Locale.US, "%.1f", tokens * 1000.0 / elapsedMs)
}
