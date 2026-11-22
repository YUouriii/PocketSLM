package com.pocketllm.ui.chat.render

/**
 * Streaming-friendly, deliberately small model of an assistant reply.
 * Parsing is pure and memoised by the caller, so only a changed message is re-parsed.
 */
sealed interface RichBlock {
    data class Heading(val level: Int, val text: String) : RichBlock
    data class Paragraph(val text: String) : RichBlock
    data class ListBlock(val items: List<ListItem>, val ordered: Boolean) : RichBlock
    data class Quote(val text: String) : RichBlock
    data object Divider : RichBlock
    data class Code(val language: String, val code: String, val complete: Boolean) : RichBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : RichBlock
    data class Math(val tex: String, val block: Boolean) : RichBlock
    data class Chart(val spec: String) : RichBlock
    data class Flow(val lines: List<String>) : RichBlock
}

data class ListItem(val text: String, val checked: Boolean?, val depth: Int)

private val FENCE = Regex("^\\s*(```+|~~~+)")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val RULE = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")
private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")
private val ORDERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val TASK = Regex("^\\[([ xX])\\]\\s*(.*)$")
private val TABLE_SEPARATOR =
    Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$")

object ResponseParser {

    fun parse(input: String): List<RichBlock> {
        val lines = input.replace("\r\n", "\n")
            .replace("<br>", "\n")
            .replace("<br/>", "\n")
            .replace("<br />", "\n")
            .lines()
        val blocks = mutableListOf<RichBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            when {
                FENCE.containsMatchIn(line) -> i = parseFence(lines, i, blocks)
                line.trimStart().startsWith("$$") -> i = parseMath(lines, i, blocks)
                RULE.matches(line) -> {
                    blocks += RichBlock.Divider
                    i++
                }

                HEADING.matchEntire(line) != null -> {
                    val m = HEADING.matchEntire(line)!!
                    blocks += RichBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())
                    i++
                }

                line.trimStart().startsWith(">") -> i = parseQuote(lines, i, blocks)
                i + 1 < lines.size && line.contains('|') && TABLE_SEPARATOR.matches(lines[i + 1]) ->
                    i = parseTable(lines, i, blocks)

                BULLET.matchEntire(line) != null || ORDERED.matchEntire(line) != null ->
                    i = parseList(lines, i, blocks)

                else -> i = parseParagraph(lines, i, blocks)
            }
        }
        return blocks
    }

    private fun parseFence(lines: List<String>, start: Int, out: MutableList<RichBlock>): Int {
        val marker = FENCE.find(lines[start])!!.value.take(3)
        val language = lines[start].trim().removePrefix(marker).trim()
        val body = StringBuilder()
        var i = start + 1
        while (i < lines.size && !lines[i].trimStart().startsWith(marker)) {
            body.append(lines[i]).append('\n')
            i++
        }
        val complete = i < lines.size
        if (complete) i++
        val code = body.toString().trimEnd('\n')
        when (language.lowercase()) {
            "chart", "charts" -> out += RichBlock.Chart(code)
            "flow", "flowchart", "mermaid" -> out += RichBlock.Flow(code.lines().filter { it.isNotBlank() })
            else -> out += RichBlock.Code(language, code, complete)
        }
        return i
    }

    private fun parseMath(lines: List<String>, start: Int, out: MutableList<RichBlock>): Int {
        val first = lines[start].trim().removePrefix("$$")
        if (first.endsWith("$$") && first.length > 2) {
            out += RichBlock.Math(first.removeSuffix("$$").trim(), block = true)
            return start + 1
        }
        val tex = StringBuilder(first)
        var i = start + 1
        while (i < lines.size && !lines[i].contains("$$")) {
            tex.append('\n').append(lines[i])
            i++
        }
        if (i < lines.size) {
            tex.append('\n').append(lines[i].substringBefore("$$"))
            i++
        }
        out += RichBlock.Math(tex.toString().trim(), block = true)
        return i
    }

    private fun parseQuote(lines: List<String>, start: Int, out: MutableList<RichBlock>): Int {
        val quoted = mutableListOf<String>()
        var i = start
        while (i < lines.size && lines[i].trimStart().startsWith(">")) {
            quoted += lines[i].trimStart().removePrefix(">").trimStart()
            i++
        }
        out += RichBlock.Quote(quoted.joinToString("\n").trim())
        return i
    }

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    private fun parseTable(lines: List<String>, start: Int, out: MutableList<RichBlock>): Int {
        val header = cells(lines[start])
        val rows = mutableListOf<List<String>>()
        var i = start + 2
        while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
            rows += cells(lines[i])
            i++
        }
        out += RichBlock.Table(header, rows)
        return i
    }

    private fun parseList(lines: List<String>, start: Int, out: MutableList<RichBlock>): Int {
        val first = BULLET.matchEntire(lines[start])
        val ordered = first == null
        val items = mutableListOf<ListItem>()
        var i = start
        while (i < lines.size && lines[i].isNotBlank()) {
            val bullet = BULLET.matchEntire(lines[i])
            val number = ORDERED.matchEntire(lines[i])
            val indent = (bullet?.groupValues?.get(1) ?: number?.groupValues?.get(1) ?: "").length
            val text = (bullet?.groupValues?.get(3) ?: number?.groupValues?.get(3) ?: "").trim()
            if ((ordered && number == null) || (!ordered && bullet == null) || text.isEmpty()) break
            val task = TASK.matchEntire(text)
            items += ListItem(
                text = task?.groupValues?.get(2)?.trim() ?: text,
                checked = task?.groupValues?.get(1)?.let { it.equals("x", ignoreCase = true) },
                depth = (indent / 2).coerceIn(0, 3),
            )
            i++
        }
        out += RichBlock.ListBlock(items, ordered)
        return i
    }

    private fun startsBlock(line: String): Boolean = line.isBlank() ||
        FENCE.containsMatchIn(line) ||
        line.trimStart().startsWith("$$") ||
        line.trimStart().startsWith(">") ||
        RULE.matches(line) ||
        HEADING.matchEntire(line) != null ||
        BULLET.matchEntire(line) != null ||
        ORDERED.matchEntire(line) != null

    private fun parseParagraph(lines: List<String>, start: Int, out: MutableList<RichBlock>): Int {
        val text = StringBuilder(lines[start].trim())
        var i = start + 1
        while (i < lines.size) {
            val next = lines[i]
            if (startsBlock(next)) break
            if (i + 1 < lines.size && next.contains('|') && TABLE_SEPARATOR.matches(lines[i + 1])) break
            text.append('\n').append(next.trim())
            i++
        }
        out += RichBlock.Paragraph(text.toString())
        return i
    }
}
