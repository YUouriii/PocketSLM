package com.pocketllm.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkingTextTest {

    @Test
    fun completeBlockIsSeparated() {
        val parts = splitThinking("<think>weigh options</think>\n\nFinal answer")

        assertEquals("weigh options", parts.thinking)
        assertEquals("Final answer", parts.answer)
    }

    @Test
    fun streamingThinkingNeverLeaksIntoAnswer() {
        val parts = splitThinking("<think>still comparing")

        assertEquals("still comparing", parts.thinking)
        assertEquals("", parts.answer)
    }

    @Test
    fun templatePreOpenedBlockIsHandled() {
        val parts = splitThinking("private reasoning</think>\nAnswer")

        assertEquals("private reasoning", parts.thinking)
        assertEquals("Answer", parts.answer)
    }

    @Test
    fun multipleBlocksAreAllRemovedFromAnswer() {
        val parts = splitThinking("<think>one</think>Answer A<think>two</think>Answer B")

        assertEquals("one\n\ntwo", parts.thinking)
        assertEquals("Answer AAnswer B", parts.answer)
    }

    @Test
    fun answerOnlyContentIsUntouched() {
        val parts = splitThinking("Just an answer")

        assertNull(parts.thinking)
        assertEquals("Just an answer", parts.answer)
    }

    @Test
    fun spacedTagsAreRecognised() {
        val parts = splitThinking("< think >reasoning< / think >visible")

        assertEquals("reasoning", parts.thinking)
        assertEquals("visible", parts.answer)
    }

    @Test
    fun strayMarkerIsNotRendered() {
        val parts = splitThinking("</think>visible <think>")

        assertEquals("visible", parts.answer)
    }
}
