package com.pocketllm.ui.chat.render

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

private val SYMBOLS = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
    "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι", "kappa" to "κ",
    "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ",
    "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "chi" to "χ",
    "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
    "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
    "leq" to "≤", "geq" to "≥", "neq" to "≠", "approx" to "≈", "equiv" to "≡",
    "infty" to "∞", "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮",
    "partial" to "∂", "nabla" to "∇", "sqrt" to "√", "rightarrow" to "→",
    "leftarrow" to "←", "leftrightarrow" to "↔", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
    "to" to "→", "mapsto" to "↦", "ldots" to "…", "dots" to "…", "cdots" to "⋯",
    "forall" to "∀", "exists" to "∃", "in" to "∈", "notin" to "∉", "subset" to "⊂",
    "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "angle" to "∠", "degree" to "°",
    "mathbb" to "", "mathrm" to "", "mathbf" to "", "text" to "", "operatorname" to "",
    "left" to "", "right" to "", "displaystyle" to "", "quad" to "  ", "qquad" to "    ",
)

private fun groupAt(s: String, start: Int): Pair<String, Int> {
    if (start >= s.length || s[start] != '{') return "" to start
    var level = 0
    for (i in start until s.length) {
        when (s[i]) {
            '{' -> level++
            '}' -> {
                level--
                if (level == 0) return s.substring(start + 1, i) to i + 1
            }
        }
    }
    return s.substring(start + 1) to s.length
}

/** Renders the common LaTeX subset used by local models into styled text. */
fun latexToAnnotated(tex: String, base: TextStyle, color: Color): AnnotatedString = buildAnnotatedString {
    val small = SpanStyle(
        fontSize = base.fontSize * 0.82f,
        fontFamily = base.fontFamily,
        color = color,
    )
    val normal = SpanStyle(fontSize = base.fontSize, fontFamily = base.fontFamily, color = color)

    fun render(input: String, style: SpanStyle, depth: Int) {
        if (depth > 8) {
            append(input)
            return
        }
        var i = 0
        while (i < input.length) {
            when (val c = input[i]) {
                '\\' -> {
                    if (i + 1 >= input.length) {
                        append('\\')
                        i++
                        continue
                    }
                    val next = input[i + 1]
                    if (next.isLetter()) {
                        val end = (i + 2 until input.length).firstOrNull { !input[it].isLetter() }
                            ?: input.length
                        val command = input.substring(i + 1, end)
                        i = end
                        when (command) {
                            "frac", "dfrac", "tfrac" -> {
                                val num = groupAt(input, i)
                                val den = groupAt(input, num.second)
                                pushStyle(small)
                                render(num.first, small, depth + 1)
                                pop()
                                append("⁄")
                                pushStyle(small)
                                render(den.first, small, depth + 1)
                                pop()
                                i = den.second
                            }

                            "sqrt" -> {
                                val arg = groupAt(input, i)
                                append("√")
                                pushStyle(small.copy(textDecoration = TextDecoration.Underline))
                                render(arg.first, small.copy(textDecoration = TextDecoration.Underline), depth + 1)
                                pop()
                                i = arg.second
                            }

                            "overline", "bar" -> {
                                val arg = groupAt(input, i)
                                pushStyle(style.copy(textDecoration = TextDecoration.Underline))
                                render(arg.first, style.copy(textDecoration = TextDecoration.Underline), depth + 1)
                                pop()
                                i = arg.second
                            }

                            "text", "mathrm", "mathbf", "mathbb", "operatorname" -> {
                                val arg = groupAt(input, i)
                                render(arg.first, normal, depth + 1)
                                i = arg.second
                            }

                            "," , ";", ":", "!", " " -> append(' ')
                            else -> append(SYMBOLS[command] ?: command)
                        }
                    } else {
                        when (next) {
                            '\\' -> append('\n')
                            '%' -> append('%')
                            '$' -> append('$')
                            '{', '}' -> append(next)
                            else -> append(next)
                        }
                        i += 2
                    }
                }

                '^', '_' -> {
                    i++
                    if (i >= input.length) break
                    val arg: String
                    if (input[i] == '{') {
                        val group = groupAt(input, i)
                        arg = group.first
                        i = group.second
                    } else {
                        arg = input[i].toString()
                        i++
                    }
                    val shifted = style.copy(
                        fontSize = base.fontSize * 0.78f,
                        baselineShift = if (c == '^') BaselineShift.Superscript else BaselineShift.Subscript,
                    )
                    pushStyle(shifted)
                    render(arg, shifted, depth + 1)
                    pop()
                }

                '{' -> {
                    val group = groupAt(input, i)
                    render(group.first, style, depth + 1)
                    i = group.second
                }

                else -> {
                    append(c)
                    i++
                }
            }
        }
    }
    render(tex.trim(), normal, 0)
}

/** Block equation in a quiet hairline panel, matching ThinCard styling. */
@Composable
fun BlockMath(tex: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = scheme.surfaceVariant,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Text(
            text = latexToAnnotated(tex, MaterialTheme.typography.bodyLarge, scheme.onSurface),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif),
        )
    }
}
