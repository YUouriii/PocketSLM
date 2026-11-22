package com.pocketllm.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class Dust(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
    val phase: Float,
    /** Parallax speed: near stars drift a little faster. */
    val depth: Float,
)

private data class Flare(
    val x: Float,
    val y: Float,
    val phase: Float,
    val depth: Float,
    val rotation: Float,
    val scale: Float,
)

private data class Streak(
    val x: Float,
    val y: Float,
    val length: Float,
    val speed: Float,
    val phase: Float,
    val angle: Float,
)

private data class Sky(val dust: List<Dust>, val flares: List<Flare>, val streaks: List<Streak>)

/** A fresh, unseeded layout each time the field enters composition. */
private fun buildSky(): Sky {
    val random = Random(System.nanoTime())
    val dust = List(90 + random.nextInt(31)) {
        val depth = 0.5f + random.nextFloat() * 1.5f
        Dust(
            x = random.nextFloat(),
            y = random.nextFloat(),
            radius = (0.5f + random.nextFloat() * 1.1f) * depth,
            alpha = 0.14f + random.nextFloat() * 0.34f,
            phase = random.nextFloat(),
            depth = depth,
        )
    }
    // Only a few focal stars earn the full flare treatment.
    val flares = List(4) {
        Flare(
            x = random.nextFloat(),
            y = random.nextFloat(),
            phase = random.nextFloat(),
            depth = 0.6f + random.nextFloat() * 1.2f,
            rotation = (random.nextFloat() - 0.5f) * 0.5f,
            scale = 0.7f + random.nextFloat() * 0.7f,
        )
    }
    val streaks = List(2) {
        Streak(
            x = random.nextFloat(),
            y = 0.15f + random.nextFloat() * 0.7f,
            length = 0.07f + random.nextFloat() * 0.10f,
            speed = 1f + random.nextFloat(),
            phase = random.nextFloat(),
            angle = (random.nextFloat() - 0.5f) * 0.35f,
        )
    }
    return Sky(dust, flares, streaks)
}

/** Slow, smooth glow envelope in [0, 1]. */
private fun glow(phase: Float): Float =
    0.5f + 0.5f * cos(2f * Math.PI.toFloat() * phase)

private fun DrawScope.drawFlare(
    cx: Float,
    cy: Float,
    color: Color,
    density: Float,
    rotation: Float,
    scale: Float,
    intensity: Float,
) {
    val arm = 3.2f * density * scale
    val a = intensity.coerceIn(0f, 1f)

    // Soft lens haze around the core.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.16f * a), Color.Transparent),
            center = Offset(cx, cy),
            radius = arm * 3f,
        ),
        radius = arm * 3f,
        center = Offset(cx, cy),
    )

    val cosR = cos(rotation)
    val sinR = sin(rotation)

    // Anamorphic horizontal bar, the signature of a lens flare.
    val half = arm * 9f
    val dx = cosR * half
    val dy = sinR * half
    val start = Offset(cx - dx, cy - dy)
    val end = Offset(cx + dx, cy + dy)
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, color.copy(alpha = 0.30f * a), Color.Transparent),
            start = start,
            end = end,
        ),
        start = start,
        end = end,
        strokeWidth = 0.9f * density,
        cap = StrokeCap.Round,
    )

    // A short cross spike, kept quieter than the horizontal bar.
    val vHalf = arm * 4f
    val vStart = Offset(cx + sinR * vHalf, cy - cosR * vHalf)
    val vEnd = Offset(cx - sinR * vHalf, cy + cosR * vHalf)
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, color.copy(alpha = 0.18f * a), Color.Transparent),
            start = vStart,
            end = vEnd,
        ),
        start = vStart,
        end = vEnd,
        strokeWidth = 0.7f * density,
        cap = StrokeCap.Round,
    )

    // Two faint ghost reflections along the bar.
    for (sign in listOf(-1f, 1f)) {
        drawCircle(
            color = color,
            radius = arm * 0.55f,
            center = Offset(cx + dx * sign * 0.72f, cy + dy * sign * 0.72f),
            alpha = 0.06f * a,
        )
    }

    drawCircle(
        color = color,
        radius = arm * 0.62f * (0.9f + 0.2f * intensity),
        center = Offset(cx, cy),
        alpha = a,
    )
}

private fun DrawScope.drawStreak(
    streak: Streak,
    drift: Float,
    isDark: Boolean,
    color: Color,
    density: Float,
) {
    // Appear briefly each cycle rather than looping as a permanent line.
    val t = (drift * streak.speed + streak.phase) % 1f
    val window = 0.12f
    if (t >= window) return
    val envelope = sin(Math.PI.toFloat() * t / window)

    val x = ((streak.x + drift * streak.speed) % 1f) * size.width
    val y = (streak.y + drift * streak.speed * streak.angle * 0.1f) * size.height
    val length = streak.length * size.width
    val cosA = cos(streak.angle)
    val sinA = sin(streak.angle)
    val head = Offset(x, y)
    val tail = Offset(x - cosA * length, y - sinA * length)
    val alpha = (if (isDark) 0.22f else 0.14f) * envelope

    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, color.copy(alpha = alpha * 0.35f), color.copy(alpha = alpha)),
            start = tail,
            end = head,
        ),
        start = tail,
        end = head,
        strokeWidth = 1.2f * density,
        cap = StrokeCap.Round,
    )
}

/**
 * A quiet starfield: ink dust on paper in light mode, glowing white dust in dark
 * mode. A handful of focal stars carry anamorphic lens flares, and faint light
 * streaks pass occasionally. Purely decorative, no interaction.
 */
@Composable
fun Starfield(modifier: Modifier = Modifier, animate: Boolean = true) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val isDark = scheme.background.red < 0.5f
    val density = LocalDensity.current.density
    val sky = remember { buildSky() }
    val color = if (isDark) Color.White else Color(0xFF0D0D0F)

    // Keep the last animated phase so pausing does not make the sky jump.
    val frozen = remember { floatArrayOf(0f, 0f) }

    val driftTransition = rememberInfiniteTransition(label = "starDrift")
    val drift = driftTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 120_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "driftProgress",
    )

    val shimmerTransition = rememberInfiniteTransition(label = "starShimmer")
    val shimmer = shimmerTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerPhase",
    )

    Canvas(modifier = modifier) {
        // While the model generates, the sky keeps its current phase but stops
        // requesting frames, leaving the GPU and CPU to inference.
        val driftValue = if (animate) drift.value.also { frozen[0] = it } else frozen[0]
        val shimmerValue = if (animate) shimmer.value.also { frozen[1] = it } else frozen[1]
        drawSky(sky, driftValue, shimmerValue, isDark, color, density)
    }
}

private fun DrawScope.drawSky(
    sky: Sky,
    drift: Float,
    shimmer: Float,
    isDark: Boolean,
    color: Color,
    density: Float,
) {
    sky.dust.forEach { star ->
        val cx = ((star.x + drift * star.depth) % 1f) * size.width
        val cy = star.y * size.height
        val intensity = glow(shimmer + star.phase)
        val baseAlpha = if (isDark) star.alpha else (star.alpha * 1.6f).coerceAtMost(0.8f)
        drawCircle(
            color = color,
            radius = star.radius * density * (0.95f + 0.10f * intensity),
            center = Offset(cx, cy),
            alpha = (baseAlpha * (0.8f + 0.2f * intensity)).coerceAtMost(1f),
        )
    }

    sky.flares.forEach { flare ->
        val cx = ((flare.x + drift * flare.depth) % 1f) * size.width
        val cy = flare.y * size.height
        drawFlare(
            cx = cx,
            cy = cy,
            color = color,
            density = density,
            rotation = flare.rotation,
            scale = flare.scale,
            intensity = 0.55f + 0.4f * glow(shimmer + flare.phase),
        )
    }

    sky.streaks.forEach { streak ->
        drawStreak(streak, drift, isDark, color, density)
    }
}
