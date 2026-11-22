package com.pocketllm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStagingTest {

    @Test
    fun stagedNameIsNeverAGgufName() {
        val file = "bartowski/Qwen2.5-3B-Instruct-GGUF/Qwen2.5-3B-Instruct-Q4_K_M.gguf"
        val finalName = modelFileName(file)
        val partName = modelPartName(file)

        assertTrue(finalName.endsWith(".gguf"))
        assertFalse("a staging file must not be scanned as an installed model", partName.endsWith(".gguf"))
        assertEquals("bartowski_Qwen2.5-3B-Instruct-GGUF_Qwen2.5-3B-Instruct-Q4_K_M.gguf", finalName)
        assertEquals("$finalName.part", partName)
    }

    @Test
    fun ggufHeaderDetection() {
        assertTrue(hasGgufHeader("GGUF".toByteArray()))
        assertTrue(hasGgufHeader("GGUF some trailing bytes".toByteArray()))
        assertFalse(hasGgufHeader("GGU".toByteArray()))
        assertFalse(hasGgufHeader("<html>not a model".toByteArray()))
        assertFalse(hasGgufHeader("gguf".toByteArray()))
    }
}
