package com.pocketllm.ui.chat.render

import androidx.compose.ui.text.style.TextDirection

/**
 * Picks paragraph direction from the dominant script. Arabic, Hebrew, Persian
 * and Urdu all live in the RTL Unicode blocks; Latin/CJK stay LTR.
 */
fun textDirectionOf(text: String): TextDirection {
    // Unicode bidi: the first strong character sets the paragraph direction.
    for (c in text) {
        val code = c.code
        if (code in 0x0590..0x08FF || code in 0xFB1D..0xFEFF) return TextDirection.Rtl
        if (c.isLetter()) return TextDirection.Ltr
    }
    return TextDirection.Ltr
}

/** True when the text contains enough RTL script to read right-to-left. */
fun containsRtlScript(text: String): Boolean = textDirectionOf(text) == TextDirection.Rtl
