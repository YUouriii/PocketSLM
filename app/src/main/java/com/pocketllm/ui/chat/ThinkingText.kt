package com.pocketllm.ui.chat

/** Visible answer and the optional reasoning block split out of a raw reply. */
data class AssistantParts(val thinking: String?, val answer: String)

private val THINK_BLOCK = Regex(
    "<\\s*think\\s*>(.*?)<\\s*/\\s*think\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val THINK_OPEN = Regex("<\\s*think\\s*>", RegexOption.IGNORE_CASE)
private val THINK_CLOSE = Regex("<\\s*/\\s*think\\s*>", RegexOption.IGNORE_CASE)
private val THINK_MARKER = Regex("<\\s*/?\\s*think\\s*>", RegexOption.IGNORE_CASE)

/**
 * Separates every reasoning block from the answer. Handles streaming tails,
 * multiple blocks, and templates that pre-open `<think>` so reasoning can never
 * leak into the rendered answer.
 */
fun splitThinking(content: String): AssistantParts {
    if (content.isBlank()) return AssistantParts(null, content)

    val thinking = StringBuilder()
    fun addThinking(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (thinking.isNotEmpty()) thinking.append("\n\n")
        thinking.append(clean)
    }

    THINK_BLOCK.findAll(content).forEach { match -> addThinking(match.groupValues[1]) }
    var answer = THINK_BLOCK.replace(content, "")

    // A close marker without an opener: the chat template opened it for us.
    val close = THINK_CLOSE.find(answer)
    if (close != null && THINK_OPEN.find(answer) == null) {
        addThinking(answer.substring(0, close.range.first))
        answer = answer.substring(close.range.last + 1)
    }

    // Still-streaming opener: everything after it is reasoning until it closes.
    val open = THINK_OPEN.find(answer)
    if (open != null) {
        val tail = answer.substring(open.range.last + 1)
        addThinking(tail)
        answer = answer.substring(0, open.range.first)
    }

    answer = THINK_MARKER.replace(answer, "").trim()
    return AssistantParts(thinking.toString().takeIf { it.isNotBlank() }, answer)
}
