package app.detent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

enum class WheelButton { Menu, Next, PlayPause, Prev, Select }

/** Degrees of finger travel around the ring per detent, like the real wheel. */
private const val TICK_DEGREES = 16.0

/**
 * The click wheel. It's drawn and hit-tested as one circle:
 *  - dragging around the ring rotates it and emits [onTick] (+1 clockwise / -1 counter-clockwise)
 *  - tapping a quadrant of the ring fires Menu / Next / PlayPause / Prev
 *  - tapping the centre fires Select
 * Angle is tracked with atan2 around the wheel centre (not x/y deltas), so
 * rotation speed is the same wherever on the ring you touch.
 */
@Composable
fun ClickWheel(
    modifier: Modifier = Modifier,
    ringColor: Color,
    labelColor: Color,
    centerColor: Color,
    rimColor: Color = Color.Black.copy(alpha = 0.10f),
    onTick: (Int) -> Unit,
    onButton: (WheelButton) -> Unit,
) {
    val tick by rememberUpdatedState(onTick)
    val button by rememberUpdatedState(onButton)
    val slop = LocalViewConfiguration.current.touchSlop

    Canvas(
        modifier
            .aspectRatio(1f)
            .pointerInput(slop) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val outer = size.width / 2f
                    val inner = outer * CENTER_RATIO

                    val startVec = down.position - center
                    val startDist = startVec.getDistance()
                    if (startDist > outer) return@awaitEachGesture
                    val startedInCenter = startDist < inner

                    var lastAngle = atan2(startVec.y, startVec.x)
                    var accumulated = 0.0 // radians since last detent
                    var travelled = 0f // px, to tell a tap from a drag
                    var rotating = false

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!rotating) {
                                val btn = if (startedInCenter) {
                                    WheelButton.Select
                                } else {
                                    quadrant(startVec)
                                }
                                button(btn)
                            }
                            change.consume()
                            break
                        }
                        travelled += change.positionChange().getDistance()
                        if (!startedInCenter && travelled > slop) rotating = true
                        if (rotating) {
                            val v = change.position - center
                            val a = atan2(v.y, v.x)
                            var d = (a - lastAngle).toDouble()
                            if (d > PI) d -= 2 * PI
                            if (d < -PI) d += 2 * PI
                            lastAngle = a
                            accumulated += d
                            val step = Math.toRadians(TICK_DEGREES)
                            while (abs(accumulated) >= step) {
                                val dir = if (accumulated > 0) 1 else -1
                                accumulated -= dir * step
                                tick(dir)
                            }
                        }
                        change.consume()
                    }
                }
            },
    ) {
        drawWheel(ringColor, labelColor, centerColor, rimColor)
    }
}

private const val CENTER_RATIO = 0.36f

/** Which quadrant of the ring a touch landed in. Screen y grows downward. */
private fun quadrant(v: Offset): WheelButton {
    val deg = Math.toDegrees(atan2(v.y, v.x).toDouble()) // 0=right, 90=down, -90=up
    return when {
        deg >= -135 && deg < -45 -> WheelButton.Menu
        deg >= -45 && deg < 45 -> WheelButton.Next
        deg >= 45 && deg < 135 -> WheelButton.PlayPause
        else -> WheelButton.Prev
    }
}

private fun DrawScope.drawWheel(ring: Color, label: Color, center: Color, rim: Color) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)

    // ring, with a soft top-light like the real thing
    drawCircle(
        brush = Brush.linearGradient(
            0f to ring.lighten(0.06f),
            1f to ring.darken(0.06f),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        ),
        radius = r,
        center = c,
    )
    drawCircle(rim, radius = r, center = c, style = Stroke(width = 2f))

    // centre button
    drawCircle(
        brush = Brush.linearGradient(
            0f to center.lighten(0.05f),
            1f to center.darken(0.05f),
            start = Offset(c.x - r * CENTER_RATIO, c.y - r * CENTER_RATIO),
            end = Offset(c.x + r * CENTER_RATIO, c.y + r * CENTER_RATIO),
        ),
        radius = r * CENTER_RATIO,
        center = c,
    )
    drawCircle(rim, radius = r * CENTER_RATIO, center = c, style = Stroke(width = 2f))

    val labelR = r * 0.68f
    val glyph = r * 0.09f

    // MENU label (top)
    drawMenuLabel(Offset(c.x, c.y - labelR), r * 0.13f, label)
    // next / prev (right / left)
    drawSkip(Offset(c.x + labelR, c.y), glyph, label, forward = true)
    drawSkip(Offset(c.x - labelR, c.y), glyph, label, forward = false)
    // play/pause (bottom)
    drawPlayPause(Offset(c.x, c.y + labelR), glyph, label)
}

private fun DrawScope.drawMenuLabel(at: Offset, size: Float, color: Color) {
    // Five bars-and-caps stand-in for "MENU" without shipping a font yet:
    // drawn as a 3-line list glyph, which reads clearly and scales cleanly.
    val w = size * 1.5f
    val gap = size * 0.55f
    for (i in -1..1) {
        drawLine(
            color,
            Offset(at.x - w / 2, at.y + i * gap),
            Offset(at.x + w / 2, at.y + i * gap),
            strokeWidth = size * 0.28f,
        )
    }
}

private fun DrawScope.drawTriangle(tip: Offset, h: Float, color: Color, pointRight: Boolean) {
    val dir = if (pointRight) 1f else -1f
    val p = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - dir * h, tip.y - h * 0.9f)
        lineTo(tip.x - dir * h, tip.y + h * 0.9f)
        close()
    }
    drawPath(p, color, style = Fill)
}

private fun DrawScope.drawSkip(at: Offset, g: Float, color: Color, forward: Boolean) {
    val dir = if (forward) 1f else -1f
    // two triangles + a bar, ⏭ / ⏮
    drawTriangle(Offset(at.x + dir * g * 0.2f, at.y), g, color, forward)
    drawTriangle(Offset(at.x + dir * g * 1.2f, at.y), g, color, forward)
    drawLine(
        color,
        Offset(at.x + dir * g * 1.45f, at.y - g * 0.9f),
        Offset(at.x + dir * g * 1.45f, at.y + g * 0.9f),
        strokeWidth = g * 0.35f,
    )
}

private fun DrawScope.drawPlayPause(at: Offset, g: Float, color: Color) {
    drawTriangle(Offset(at.x - g * 0.15f, at.y), g, color, pointRight = true)
    val bx = at.x + g * 0.55f
    drawLine(color, Offset(bx, at.y - g * 0.9f), Offset(bx, at.y + g * 0.9f), strokeWidth = g * 0.35f)
    drawLine(color, Offset(bx + g * 0.6f, at.y - g * 0.9f), Offset(bx + g * 0.6f, at.y + g * 0.9f), strokeWidth = g * 0.35f)
}

private fun Color.lighten(f: Float) = Color(
    (red + f).coerceAtMost(1f), (green + f).coerceAtMost(1f), (blue + f).coerceAtMost(1f), alpha,
)

private fun Color.darken(f: Float) = Color(
    (red - f).coerceAtLeast(0f), (green - f).coerceAtLeast(0f), (blue - f).coerceAtLeast(0f), alpha,
)
