package com.pocketllm.ui.chat.render

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pocketllm.R
import com.pocketllm.ui.theme.Spacing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.min

private data class ChartSeries(val name: String?, val values: List<Float>)
private data class ChartData(
    val type: String,
    val title: String?,
    val labels: List<String>,
    val series: List<ChartSeries>,
    val points: List<Pair<Float, Float>>,
)

private fun numbers(element: kotlinx.serialization.json.JsonElement?): List<Float>? {
    if (element !is JsonArray) return null
    val values = element.mapNotNull { item ->
        (item as? JsonPrimitive)?.content?.toFloatOrNull()?.takeIf { it.isFinite() }
    }
    return values.takeIf { it.size == element.size && it.isNotEmpty() }
}

private fun strings(element: kotlinx.serialization.json.JsonElement?): List<String> =
    (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

private fun parseChart(spec: String): ChartData? = runCatching {
    val root = Json.parseToJsonElement(spec) as? JsonObject ?: return null
    val type = (root["type"] as? JsonPrimitive)?.content?.lowercase() ?: return null
    if (type !in setOf("line", "bar", "pie", "scatter")) return null
    val title = (root["title"] as? JsonPrimitive)?.content

    val seriesList = (root["series"] as? JsonArray)?.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val values = numbers(obj["values"]) ?: return@mapNotNull null
        ChartSeries((obj["name"] as? JsonPrimitive)?.content, values)
    } ?: emptyList()

    val points = if (type == "scatter") {
        (root["points"] as? JsonArray)?.mapNotNull { element ->
            val pair = element as? JsonArray ?: return@mapNotNull null
            if (pair.size != 2) return@mapNotNull null
            val x = (pair[0] as? JsonPrimitive)?.content?.toFloatOrNull()
            val y = (pair[1] as? JsonPrimitive)?.content?.toFloatOrNull()
            if (x != null && y != null && x.isFinite() && y.isFinite()) x to y else null
        } ?: emptyList()
    } else {
        emptyList()
    }

    if (type == "scatter") {
        if (points.isEmpty()) return null
    } else if (seriesList.isEmpty()) {
        return null
    }
    // Never fill in or infer missing values — a malformed chart stays unrendered.
    val lengths = seriesList.map { it.values.size }.toSet()
    if (lengths.size > 1) return null
    ChartData(type, title, strings(root["labels"]), seriesList, points)
}.getOrNull()

private fun shade(alpha: Float): Float = when (alpha) {
    0f -> 1f
    else -> (1f - alpha * 0.22f).coerceIn(0.3f, 1f)
}

private fun DrawScope.drawAxes(color: Color) {
    val left = 36.dp.toPx()
    val bottom = size.height - 24.dp.toPx()
    drawLine(color, Offset(left, 8.dp.toPx()), Offset(left, bottom), strokeWidth = 1.dp.toPx(), alpha = 0.7f)
    drawLine(color, Offset(left, bottom), Offset(size.width - 8.dp.toPx(), bottom), strokeWidth = 1.dp.toPx(), alpha = 0.7f)
    repeat(3) { step ->
        val y = 8.dp.toPx() + (bottom - 8.dp.toPx()) * (step / 2f)
        drawLine(
            color,
            Offset(left, y),
            Offset(size.width - 8.dp.toPx(), y),
            strokeWidth = 1.dp.toPx(),
            alpha = 0.18f,
        )
    }
}

@Composable
fun ChartBlock(spec: String) {
    val scheme = MaterialTheme.colorScheme
    val data = remember(spec) { parseChart(spec) }
    if (data == null) {
        Text(stringResource(R.string.response_chart_invalid), style = MaterialTheme.typography.labelSmall)
        return
    }
    val axis = scheme.outlineVariant
    val ink = scheme.onSurface
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(Spacing.M)) {
            if (data.title != null) {
                Text(data.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(Spacing.S))
            }
            androidx.compose.foundation.Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            ) {
                when (data.type) {
                    "pie" -> drawPie(data, ink, axis)
                    "scatter" -> drawScatter(data, ink, axis)
                    "bar" -> drawBars(data, ink, axis)
                    else -> drawLines(data, ink, axis)
                }
            }
            Spacer(Modifier.height(Spacing.XS))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val range = if (data.type == "scatter") {
                    val xs = data.points.map { it.first }
                    val ys = data.points.map { it.second }
                    val lo = min(xs.minOrNull() ?: 0f, ys.minOrNull() ?: 0f)
                    val hi = max(xs.maxOrNull() ?: 0f, ys.maxOrNull() ?: 0f)
                    "%.2f – %.2f".format(lo, hi)
                } else {
                    val all = data.series.flatMap { it.values }
                    "${all.minOrNull() ?: 0f} – ${all.maxOrNull() ?: 0f}"
                }
                Text(range, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.width(Spacing.M))
                data.series.mapNotNull { it.name }.forEachIndexed { index, name ->
                    Text(
                        name,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = shade(index.toFloat() / 4f)),
                    )
                    Spacer(Modifier.width(Spacing.S))
                }
            }
        }
    }
}

private fun valueRange(data: ChartData): Pair<Float, Float> {
    val values = if (data.type == "scatter") data.points.flatMap { listOf(it.first, it.second) }
    else data.series.flatMap { it.values }
    var lo = values.minOrNull() ?: 0f
    var hi = values.maxOrNull() ?: 1f
    if (lo == hi) {
        lo -= 1f
        hi += 1f
    }
    val pad = (hi - lo) * 0.08f
    return lo - pad to hi + pad
}

private fun DrawScope.drawLines(data: ChartData, ink: Color, axis: Color) {
    drawAxes(axis)
    val (lo, hi) = valueRange(data)
    val left = 36.dp.toPx()
    val right = size.width - 8.dp.toPx()
    val top = 8.dp.toPx()
    val bottom = size.height - 24.dp.toPx()
    data.series.forEachIndexed { s, series ->
        val path = Path()
        series.values.forEachIndexed { i, value ->
            val x = if (series.values.size == 1) (left + right) / 2f
            else left + (right - left) * i / (series.values.size - 1)
            val y = bottom - (bottom - top) * (value - lo) / (hi - lo)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(ink, radius = 3.dp.toPx(), center = Offset(x, y), alpha = shade(s.toFloat() / 4f))
        }
        drawPath(path, ink, alpha = shade(s.toFloat() / 4f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}

private fun DrawScope.drawBars(data: ChartData, ink: Color, axis: Color) {
    drawAxes(axis)
    val (lo, hi) = valueRange(data)
    val left = 36.dp.toPx()
    val right = size.width - 8.dp.toPx()
    val top = 8.dp.toPx()
    val bottom = size.height - 24.dp.toPx()
    val groups = data.series.maxOf { it.values.size }
    val groupWidth = (right - left) / groups
    val barWidth = groupWidth * 0.7f / data.series.size
    data.series.forEachIndexed { s, series ->
        series.values.forEachIndexed { i, value ->
            val x = left + groupWidth * i + groupWidth * 0.15f + barWidth * s
            val y = bottom - (bottom - top) * (value - lo) / (hi - lo)
            drawRect(ink, Offset(x, y), Size(barWidth, bottom - y), alpha = shade(s.toFloat() / 4f))
        }
    }
}

private fun DrawScope.drawScatter(data: ChartData, ink: Color, axis: Color) {
    drawAxes(axis)
    val (lo, hi) = valueRange(data)
    val left = 36.dp.toPx()
    val right = size.width - 8.dp.toPx()
    val top = 8.dp.toPx()
    val bottom = size.height - 24.dp.toPx()
    data.points.forEach { (x, y) ->
        val px = left + (right - left) * (x - lo) / (hi - lo)
        val py = bottom - (bottom - top) * (y - lo) / (hi - lo)
        drawCircle(ink, radius = 4.dp.toPx(), center = Offset(px, py), alpha = 0.8f)
    }
}

private fun DrawScope.drawPie(data: ChartData, ink: Color, axis: Color) {
    val values = data.series.firstOrNull()?.values ?: emptyList()
    val total = values.sum()
    if (total <= 0f) return
    val diameter = min(size.width - 16.dp.toPx(), size.height - 16.dp.toPx())
    val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
    var angle = -90f
    values.forEachIndexed { index, value ->
        val sweep = 360f * value / total
        drawArc(
            color = ink,
            startAngle = angle,
            sweepAngle = sweep,
            useCenter = true,
            topLeft = topLeft,
            size = Size(diameter, diameter),
            alpha = shade(index.toFloat() / 5f),
        )
        angle += sweep
    }
    drawCircle(axis, radius = diameter * 0.42f, center = topLeft + Offset(diameter / 2f, diameter / 2f))
}
