package com.pocketllm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogComfortTest {

    @Test
    fun sixGigabytePhoneRecommendsFifteenBClass() {
        val recommended = Catalog.recommended(5653)
        val ids = recommended.map { it.id }

        assertEquals("1.5B", recommended.first().params)
        assertTrue(ids.contains("qwen2.5-1.5b"))
        assertFalse(ids.contains("qwen3.5-2b"))
        assertFalse(ids.contains("qwen2.5-3b"))
        assertFalse(ids.contains("qwen3.5-4b"))
    }

    @Test
    fun eightGigabytePhoneAllowsThreeBButNotFourB() {
        val ids = Catalog.recommended(8192).map { it.id }

        assertTrue(ids.contains("qwen2.5-3b"))
        assertFalse(ids.contains("qwen3.5-4b"))
    }

    @Test
    fun twelveGigabytePhoneAllowsFourB() {
        val ids = Catalog.recommended(12288).map { it.id }

        assertTrue(ids.contains("qwen3.5-4b"))
    }

    @Test
    fun fitUsesCuratedMinimumInsteadOfFileSizeAlone() {
        val threeB = Catalog.byId("qwen2.5-3b")!!
        val fourB = Catalog.byId("qwen3.5-4b")!!

        assertEquals(Fit.TIGHT, threeB.fit(5653))
        assertEquals(Fit.TOO_BIG, fourB.fit(5653))
        assertEquals(Fit.GOOD, Catalog.byId("qwen2.5-1.5b")!!.fit(5653))
    }
}
