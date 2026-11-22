package com.pocketllm.ui.chat.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseSanitizationTest {

    @Test
    fun fencedCodeKeepsItsHtmlTags() {
        val reply = """
            Here is the example:

            ```html
            <div class="skills">
              <h1>My Skills</h1>
            </div>
            ```

            Hope that helps.
        """.trimIndent()

        val code = parseResponse(reply).filterIsInstance<RichBlock.Code>().single()

        assertTrue(code.code.contains("<div class=\"skills\">"))
        assertTrue(code.code.contains("<h1>My Skills</h1>"))
        assertTrue(code.code.contains("</div>"))
    }

    @Test
    fun inlineTextIsStillSanitized() {
        assertEquals("Hello world", stripTags("Hello <b>world</b>"))
        assertEquals("answer", stripTags("<script>alert(1)</script>answer"))
    }

    @Test
    fun characterReferencesAreDecoded() {
        assertEquals("&#128514; decoded", "😂 decoded", stripTags("&#128514; decoded"))
        assertEquals("a & b <c>", stripTags("a &amp; b &lt;c&gt;"))
        assertEquals("line\nbreak", stripTags("line&#10;break"))
    }

    @Test
    fun escapedMarkupIsShownAsTextNotDropped() {
        assertEquals("<script>", stripTags("&lt;script&gt;"))
    }

    @Test
    fun unknownReferencesAreLeftAlone() {
        assertEquals("&bogus; &#xZZ;", stripTags("&bogus; &#xZZ;"))
    }
}
