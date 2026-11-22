package com.pocketllm.ui.chat.render

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketllm.R
import com.pocketllm.ui.chat.render.ListItem
import com.pocketllm.ui.theme.IconSize
import com.pocketllm.ui.theme.Spacing

@Composable
fun TableBlock(header: List<String>, rows: List<List<String>>) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                header.forEach { cell ->
                    TableCell(cell, header = true)
                }
            }
            HorizontalDivider(color = scheme.outlineVariant)
            rows.forEachIndexed { rowIndex, row ->
                Row {
                    row.forEach { cell -> TableCell(cell, header = false) }
                }
                if (rowIndex != rows.lastIndex) {
                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, header: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .widthIn(min = 88.dp, max = 320.dp)
            .padding(horizontal = Spacing.M, vertical = Spacing.S),
    ) {
        Text(
            text = stripTags(text),
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                textDirection = textDirectionOf(text),
            ),
            color = if (header) scheme.onSurface else scheme.onSurfaceVariant,
        )
    }
}

@Composable
fun QuoteBlock(text: String, style: TextStyle) {
    val scheme = MaterialTheme.colorScheme
    val rtl = containsRtlScript(text)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.XS)
            .drawBehind {
                drawRect(
                    color = scheme.onSurfaceVariant.copy(alpha = 0.45f),
                    topLeft = Offset(if (rtl) size.width - 2.dp.toPx() else 0f, 0f),
                    size = Size(2.dp.toPx(), size.height),
                )
            },
    ) {
        RichText(
            stripTags(text),
            style,
            if (rtl) Modifier.padding(end = Spacing.M) else Modifier.padding(start = Spacing.M),
        )
    }
}

@Composable
fun ListBlockView(items: List<ListItem>, ordered: Boolean, style: TextStyle) {
    // Mirror the whole block so markers sit on the right for Arabic/Hebrew.
    val rtl = items.any { containsRtlScript(it.text) }
    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        Column(Modifier.padding(vertical = Spacing.XS)) {
            items.forEachIndexed { index, item ->
                Row(
                    Modifier.padding(start = (item.depth * 12).dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    when {
                        item.checked != null -> Icon(
                            imageVector = if (item.checked == true) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(IconSize.Medium),
                        )

                        ordered -> Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(22.dp),
                        )

                        else -> Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(22.dp),
                        )
                    }
                    Spacer(Modifier.width(Spacing.XS))
                    RichText(item.text, style, Modifier.weight(1f))
                }
            }
        }
    }
}

private val DECORATION = Regex("\\[[^]]*\\]|\\([^)]*\\)")

/** Only explicit A --> B edges are drawn; anything else falls back to code. */
private fun parseFlow(lines: List<String>): List<Pair<String, String>> = lines.mapNotNull { line ->
    val parts = line.split("-->").map { it.replace(DECORATION, "").trim() }
    if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
        parts[0] to parts[1]
    } else {
        null
    }
}

@Composable
fun FlowBlock(lines: List<String>, style: TextStyle) {
    val edges = parseFlow(lines)
    if (edges.isEmpty()) {
        Text(stringResource(R.string.response_flow_invalid), style = MaterialTheme.typography.labelSmall)
        return
    }
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        edges.forEach { (from, to) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowNode(from)
                Spacer(Modifier.width(Spacing.S))
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSize.Medium),
                )
                Spacer(Modifier.width(Spacing.S))
                FlowNode(to)
            }
            Spacer(Modifier.height(Spacing.XS))
        }
    }
}

@Composable
private fun FlowNode(label: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceVariant,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Text(
            stripTags(label),
            style = MaterialTheme.typography.labelMedium.copy(
                textDirection = textDirectionOf(label),
            ),
            color = scheme.onSurface,
            modifier = Modifier.padding(horizontal = Spacing.M, vertical = Spacing.S),
        )
    }
}
