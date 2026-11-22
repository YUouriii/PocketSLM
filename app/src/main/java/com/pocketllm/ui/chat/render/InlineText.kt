package com.pocketllm.ui.chat.render

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** Inline markdown: emphasis, strong, strike, code and inline maths. Links render as plain text. */
fun inlineMarkdown(
    text: String,
    base: TextStyle,
    color: Color,
    codeBackground: Color,
): AnnotatedString = buildAnnotatedString {
    val code = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = codeBackground,
        color = color,
    )
    val math = SpanStyle(fontFamily = FontFamily.Serif, color = color)

    fun render(input: String, style: SpanStyle, depth: Int) {
        if (depth > 6) {
            append(input)
            return
        }
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\\' && i + 1 < input.length) {
                append(input[i + 1])
                i += 2
                continue
            }
            if (c == '[') {
                val labelEnd = input.indexOf(']', i + 1)
                if (labelEnd > i && labelEnd + 1 < input.length && input[labelEnd + 1] == '(') {
                    val urlEnd = input.indexOf(')', labelEnd + 2)
                    if (urlEnd > labelEnd) {
                        render(input.substring(i + 1, labelEnd), style, depth + 1)
                        i = urlEnd + 1
                        continue
                    }
                }
            }
            val marker: String?
            val innerStyle: SpanStyle?
            when {
                input.startsWith("**", i) -> {
                    marker = "**"
                    innerStyle = style.copy(fontWeight = FontWeight.Bold)
                }

                input.startsWith("__", i) -> {
                    marker = "__"
                    innerStyle = style.copy(fontWeight = FontWeight.Bold)
                }

                input.startsWith("~~", i) -> {
                    marker = "~~"
                    innerStyle = style.copy(textDecoration = TextDecoration.LineThrough)
                }

                c == '`' -> {
                    marker = "`"
                    innerStyle = code
                }

                c == '*' || c == '_' -> {
                    marker = c.toString()
                    innerStyle = style.copy(fontStyle = FontStyle.Italic)
                }

                c == '$' -> {
                    marker = "$"
                    innerStyle = math
                }

                else -> {
                    marker = null
                    innerStyle = null
                }
            }
            if (marker != null && innerStyle != null) {
                val close = input.indexOf(marker, i + marker.length)
                if (close > i + marker.length - 1) {
                    val inner = input.substring(i + marker.length, close)
                    if (inner.isNotBlank()) {
                        if (marker == "$") {
                            append(latexToAnnotated(inner, base, color))
                        } else {
                            pushStyle(innerStyle)
                            render(inner, innerStyle, depth + 1)
                            pop()
                        }
                        i = close + marker.length
                        continue
                    }
                } else {
                    // Unfinished marker (streaming): keep it verbatim rather than
                    // half-consuming it and showing a stray character.
                    append(marker)
                    i += marker.length
                    continue
                }
            }
            append(c)
            i++
        }
    }
    render(text, SpanStyle(color = color), 0)
}

@Composable
fun RichText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // RTL paragraphs must fill the column: a wrap-content Text would stay
    // pinned to the left edge even though its lines read right-to-left.
    val rtl = containsRtlScript(text)
    val box = if (rtl) modifier.fillMaxWidth() else modifier
    androidx.compose.foundation.text.selection.SelectionContainer(box) {
        androidx.compose.material3.Text(
            text = inlineMarkdown(stripTags(text), style, scheme.onSurface, scheme.surfaceVariant),
            style = style.copy(textDirection = textDirectionOf(text)),
            color = scheme.onSurface,
            modifier = if (rtl) Modifier.fillMaxWidth() else Modifier,
        )
    }
}

/** Removes raw tags from model output; nothing here is ever parsed or executed as HTML. */
fun stripTags(input: String): String = decodeEntities(
    input
        .replace(Regex("<(script|style)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<[^>]+>"), ""),
)

private val NUMERIC_ENTITY = Regex("&#(x?)([0-9A-Fa-f]{1,7});")

private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
)

/**
 * Turns HTML character references from pasted or model text into plain
 * characters. Never re-parses the result as markup: it is only ever drawn.
 */
fun decodeEntities(input: String): String {
    if (input.indexOf('&') < 0) return input
    val numeric = NUMERIC_ENTITY.replace(input) { match ->
        val hex = match.groupValues[1] == "x"
        val digits = match.groupValues[2]
        val code = if (hex) digits.toIntOrNull(16) else digits.toIntOrNull()
        if (code == null || !isDrawableCodePoint(code)) match.value else String(Character.toChars(code))
    }
    return NAMED_ENTITY.replace(numeric) { match ->
        NAMED_ENTITIES[match.groupValues[1]] ?: match.value
    }
}

private val NAMED_ENTITY = Regex("&([A-Za-z]{2,6});")

private fun isDrawableCodePoint(code: Int): Boolean =
    (code == 0x0A || code == 0x0D || code == 0x09 || code >= 0x20) &&
        code <= 0x10FFFF &&
        code !in 0xD800..0xDFFF
