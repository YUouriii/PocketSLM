package com.pocketllm.ui.chat.render

import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDirectionTest {

    @Test
    fun arabicIsRtl() {
        assertEquals(TextDirection.Rtl, textDirectionOf("مرحبا كيف حالك؟"))
        assertTrue(containsRtlScript("ما هي عاصمة تونس؟"))
    }

    @Test
    fun englishIsLtr() {
        assertEquals(TextDirection.Ltr, textDirectionOf("Hello, how are you?"))
        assertFalse(containsRtlScript("The capital is Tunis."))
    }

    @Test
    fun mixedTextFollowsTheDominantScript() {
        assertEquals(TextDirection.Rtl, textDirectionOf("هذا نص عربي مع English words"))
        assertEquals(TextDirection.Ltr, textDirectionOf("Mostly English with one كلمة"))
    }
}
