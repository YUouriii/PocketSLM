package com.pocketllm.runtime

import com.pocketllm.data.MemoryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryOptimizerTest {

    private val optimizer = MemoryOptimizer()

    @Test
    fun preserveNeverUnloadsOrTouchesOtherApps() {
        val result = optimizer.plan(MemoryMode.PRESERVE, true, false, false)
        assertFalse(result.unloadCurrent)
        assertEquals(0, result.otherAppsTerminated)
        assertTrue(result.suggestions.any { it.contains("smaller", true) })
        assertTrue(result.suggestions.any { it.contains("context", true) })
    }

    @Test
    fun balancedUnloadsInactiveModelAndReplans() {
        val result = optimizer.plan(MemoryMode.BALANCED, true, false, false)
        assertTrue(result.unloadCurrent)
        assertTrue(result.replanRequired)
        assertEquals(0, result.otherAppsTerminated)
    }

    @Test
    fun prioritizeAIAddsManualAdviceButNeverKillsApps() {
        val result = optimizer.plan(MemoryMode.PRIORITIZE, true, false, true)
        assertTrue(result.unloadCurrent)
        assertTrue(result.suggestions.any { it.contains("close other apps", true) })
        assertEquals(0, result.otherAppsTerminated)
    }

    @Test
    fun activeGenerationIsProtectedInEveryMode() {
        for (mode in MemoryMode.entries) {
            val result = optimizer.plan(mode, true, true, false)
            assertFalse("$mode unloaded during generation", result.unloadCurrent)
            assertEquals(0, result.otherAppsTerminated)
        }
    }

    @Test
    fun lowMemoryAddsDeferralAdvice() {
        val result = optimizer.plan(MemoryMode.BALANCED, false, false, true)
        assertTrue(result.suggestions.any { it.contains("low memory", true) })
        assertEquals(0, result.otherAppsTerminated)
    }
}
