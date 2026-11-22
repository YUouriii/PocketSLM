package com.pocketllm.ui.chat.render

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketllm.R
import com.pocketllm.ui.theme.IconSize
import com.pocketllm.ui.theme.Spacing

private val COMMON = setOf(
    "if", "else", "for", "while", "return", "break", "continue", "class", "fun", "def",
    "function", "const", "let", "var", "val", "import", "from", "package", "public", "private",
    "protected", "static", "new", "this", "super", "try", "catch", "finally", "throw",
    "switch", "case", "default", "do", "in", "of", "not", "and", "or", "true", "false", "null",
    "None", "True", "False", "async", "await", "yield", "lambda", "interface", "enum",
    "struct", "type", "void", "int", "float", "double", "string", "bool", "boolean", "char",
)

private val KEYWORDS: Map<String, Set<String>> = mapOf(
    "python" to (COMMON + setOf("print", "self", "with", "as", "pass", "raise", "global", "elif", "is", "del", "except", "finally", "yield")),
    "kotlin" to (COMMON + setOf("data", "object", "companion", "override", "suspend", "when", "is", "sealed", "lateinit", "init", "out", "reified")),
    "java" to (COMMON + setOf("final", "abstract", "implements", "extends", "instanceof", "synchronized", "byte", "short", "long")),
    "javascript" to (COMMON + setOf("var", "function", "export", "default", "typeof", "instanceof", "delete", "void")),
    "typescript" to (COMMON + setOf("interface", "type", "export", "default", "typeof", "as", "readonly", "implements", "enum")),
    "c" to (COMMON + setOf("sizeof", "typedef", "struct", "union", "goto", "unsigned", "signed")),
    "cpp" to (COMMON + setOf("namespace", "template", "typename", "using", "virtual", "constexpr", "auto", "nullptr", "cout", "cin")),
    "sql" to setOf("SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "DROP", "ALTER", "JOIN", "LEFT", "RIGHT", "INNER", "ON", "GROUP", "BY", "ORDER", "LIMIT", "AS", "AND", "OR", "NOT", "NULL", "DISTINCT", "HAVING"),
    "json" to setOf("true", "false", "null"),
    "html" to setOf("div", "span", "p", "a", "body", "head", "html", "script", "style", "h1", "h2", "h3", "ul", "li", "table"),
    "css" to setOf("color", "background", "margin", "padding", "border", "display", "flex", "grid", "width", "height", "font-size"),
    "bash" to setOf("echo", "cd", "ls", "cat", "grep", "sudo", "apt", "mkdir", "rm", "cp", "mv", "export", "source", "if", "then", "fi", "for", "do", "done", "while", "true", "false"),
)

private val HASH_COMMENTS = setOf("python", "bash", "sh", "shell", "yaml", "yml", "ruby")

/** Monochrome highlighting: weight, shade and italic only — no accent colours. */
fun highlightCode(code: String, language: String, color: Color, muted: Color): AnnotatedString {
    val lang = language.lowercase()
    val keywords = KEYWORDS[lang] ?: COMMON
    val lineComment = when (lang) {
        "sql" -> "--"
        "html", "xml" -> "<!--"
        in HASH_COMMENTS -> "#"
        else -> "//"
    }
    val plain = SpanStyle(color = color)
    val keyword = SpanStyle(color = color, fontWeight = FontWeight.Bold)
    val stringStyle = SpanStyle(color = muted)
    val commentStyle = SpanStyle(color = muted.copy(alpha = 0.55f), fontStyle = FontStyle.Italic)
    val number = SpanStyle(color = color, fontWeight = FontWeight.SemiBold)
    val key = SpanStyle(color = color, fontWeight = FontWeight.SemiBold)

    return buildAnnotatedString {
        var blockComment = false
        var quote: Char? = null
        code.lines().forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) append('\n')
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    blockComment -> {
                        pushStyle(commentStyle)
                        val end = line.indexOf("*/", i)
                        if (end < 0) {
                            append(line.substring(i))
                            i = line.length
                        } else {
                            append(line.substring(i, end + 2))
                            i = end + 2
                            blockComment = false
                        }
                        pop()
                    }

                    quote != null -> {
                        pushStyle(stringStyle)
                        var end = i + 1
                        while (end < line.length) {
                            if (line[end] == '\\') {
                                end += 2
                                continue
                            }
                            if (line[end] == quote) {
                                end++
                                break
                            }
                            end++
                        }
                        append(line.substring(i, end.coerceAtMost(line.length)))
                        i = end
                        if (i > line.length || line.getOrNull(i - 1) == quote) quote = null
                        pop()
                    }

                    line.startsWith(lineComment, i) -> {
                        pushStyle(commentStyle)
                        append(line.substring(i))
                        pop()
                        i = line.length
                    }

                    c == '/' && line.getOrNull(i + 1) == '*' -> {
                        blockComment = true
                        pushStyle(commentStyle)
                        append("/*")
                        pop()
                        i += 2
                    }

                    c == '\'' || c == '"' || c == '`' -> {
                        quote = c
                        pushStyle(stringStyle)
                        append(c)
                        pop()
                        i++
                    }

                    c.isDigit() -> {
                        var end = i + 1
                        while (end < line.length && (line[end].isDigit() || line[end] == '.' || line[end] == 'x')) end++
                        pushStyle(number)
                        append(line.substring(i, end))
                        pop()
                        i = end
                    }

                    c.isLetter() || c == '_' || c == '$' -> {
                        var end = i + 1
                        while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_' || line[end] == '$')) end++
                        val word = line.substring(i, end)
                        val isKey = lang == "json" && line.getOrNull(end) == ':' && line.getOrNull(i - 1) == '"'
                        pushStyle(if (isKey || keywords.contains(word)) key else plain)
                        append(word)
                        pop()
                        i = end
                    }

                    else -> {
                        append(c)
                        i++
                    }
                }
            }
        }
    }
}

private fun numbered(code: String, color: Color): AnnotatedString = buildAnnotatedString {
    val dim = SpanStyle(color = color.copy(alpha = 0.55f))
    code.lines().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        pushStyle(dim)
        append((index + 1).toString().padStart(3))
        append("  ")
        pop()
        append(line)
    }
}

/** Native code container: language label, copy, collapse and optional line numbers. */
@Composable
fun CodeBlock(
    code: String,
    language: String,
    onCopied: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    var showNumbers by remember { mutableStateOf(false) }
    // A fence may carry extra text (e.g. ```kotlin title=demo); only the first
    // token is the language, and the label/highlighter must agree on it.
    val lang = language.trim().substringBefore(' ').substringBefore('\t')
    val lines = remember(code) { code.lines() }
    val long = lines.size > 12
    val displayed = if (long && !expanded) lines.take(12).joinToString("\n") else code

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceVariant,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.M, end = Spacing.XS, top = Spacing.XS, bottom = Spacing.XS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = lang.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showNumbers = !showNumbers }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.FormatListNumbered,
                        contentDescription = androidx.compose.ui.res.stringResource(R.string.response_line_numbers),
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(IconSize.Medium),
                    )
                }
                if (long) {
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = androidx.compose.ui.res.stringResource(
                                if (expanded) R.string.response_collapse_code else R.string.response_expand_code,
                            ),
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(IconSize.Medium),
                        )
                    }
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(code))
                    onCopied()
                }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = androidx.compose.ui.res.stringResource(R.string.response_copy_code),
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(IconSize.Medium),
                    )
                }
            }
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = Spacing.M, end = Spacing.M, bottom = Spacing.M),
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text = if (showNumbers) {
                            numbered(displayed, scheme.onSurfaceVariant)
                        } else {
                            highlightCode(displayed, lang, scheme.onSurface, scheme.onSurfaceVariant)
                        },
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                            color = scheme.onSurface,
                        ),
                    )
                }
            }
        }
    }
}
