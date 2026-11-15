package app.knurl.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.StrokeCap

enum class WheelButton { Menu, Next, PlayPause, Prev, Select }

/** Degrees of finger travel around the ring per detent, like the real wheel. */
private const val TICK_DEGREES = 24.0

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
    onTick: (Int) -> Unit,
    onButton: (WheelButton) -> Unit,
    /** Degrees of finger travel around the ring for one detent: a smaller number is a faster wheel. */
    tickDegrees: Double = TICK_DEGREES,
    /** How many options the screen has, and which is selected: the ring shows one tick for each (up to 24). */
    options: Int = 1,
    selected: Int = 0,
) {
    val degrees by rememberUpdatedState(tickDegrees)
    val tick by rememberUpdatedState(onTick)
    val button by rememberUpdatedState(onButton)
    val slop = LocalViewConfiguration.current.touchSlop
    val grain = remember { grainShader() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val mono = remember { androidx.core.content.res.ResourcesCompat.getFont(context, app.knurl.R.font.plex_mono_semibold) }

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
                            val step = Math.toRadians(degrees)
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
        drawWheel(grain, mono, options, selected)
    }
}

/** The centre button's share of the wheel's radius: 64 of 163 in the design. */
private const val CENTER_RATIO = 64f / 163f

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

/** A small tile of faint random speckle, repeated over the wheel for the grain the design shows. */
private fun grainShader(): ShaderBrush {
    val tile = 128
    val rnd = java.util.Random(7)
    val px = IntArray(tile * tile) {
        val gray = 120 + rnd.nextInt(136)
        val alpha = rnd.nextInt(24) // up to about 9% opacity
        (alpha shl 24) or (gray shl 16) or (gray shl 8) or gray
    }
    val bitmap = android.graphics.Bitmap.createBitmap(px, tile, tile, android.graphics.Bitmap.Config.ARGB_8888)
    return ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

private val LABEL = Color(0xFFDCDCE0)

/**
 * Draws the wheel in the design's own 328-unit space, scaled to fit: a dark glossy disc with grain, a light top
 * edge, a recessed centre button and the MENU, skip and play/pause labels.
 */
private fun DrawScope.drawWheel(grain: ShaderBrush, mono: android.graphics.Typeface?, options: Int, selected: Int) {
    val k = size.minDimension / 328f
    fun p(x: Float, y: Float) = Offset(x * k, y * k)
    val c = p(164f, 164f)
    val box = size.minDimension

    // Dark surface, lit from the top.
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFF36363C), 0.55f to Color(0xFF1F1F23), 1f to Color(0xFF131315),
            center = Offset(box * 0.5f, box * 0.28f), radius = box * 0.78f,
        ),
        163f * k, c,
    )
    // Fine grain over the surface.
    drawCircle(grain, 163f * k, c)
    // Soft sheen across the top.
    drawCircle(
        Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.12f), 1f to Color.Transparent,
            center = Offset(box * 0.5f, 0f), radius = box * 0.62f,
        ),
        163f * k, c,
    )
    // Light edge, brighter at the top.
    drawCircle(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.30f), 0.5f to Color.White.copy(alpha = 0.04f), 1f to Color.White.copy(alpha = 0.12f),
        ),
        162.2f * k, c, style = Stroke(1.6f * k),
    )

    // One orange tick per option on screen (up to 24), evenly spaced all the way round; the selected one glows.
    // Longer lists map onto the 24 ticks by position.
    val n = options.coerceAtLeast(1)
    val t = minOf(n, 24)
    val active = minOf(t - 1, selected.coerceIn(0, n - 1) * t / n)
    for (i in 0 until t) {
        val ang = Math.toRadians(-90.0 + i * 360.0 / t)
        val on = i == active
        val r1 = if (on) 142f else 147f
        val r2 = if (on) 159f else 157f
        val a = p(164f + r1 * cos(ang).toFloat(), 164f + r1 * sin(ang).toFloat())
        val b = p(164f + r2 * cos(ang).toFloat(), 164f + r2 * sin(ang).toFloat())
        if (on) {
            drawLine(Color(0xFFFF7A2A).copy(alpha = 0.7f), a, b, 10f * k, StrokeCap.Round)
            drawLine(Color(0xFFFFB27A), a, b, 4.5f * k, StrokeCap.Round)
        } else {
            drawLine(ORANGE.copy(alpha = 0.45f), a, b, 4f * k, StrokeCap.Round)
        }
    }

    // Centre button: a dark well, then the button, then its rim.
    drawCircle(Color(0xFF050506), 64f * k, c)
    drawCircle(Color.Black.copy(alpha = 0.6f), 64.5f * k, c, style = Stroke(2f * k))
    drawCircle(Brush.verticalGradient(listOf(Color(0xFF0D0D0F), Color(0xFF27272B)), startY = c.y - 58f * k, endY = c.y + 58f * k), 58f * k, c)
    drawCircle(
        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.White.copy(alpha = 0.16f)), startY = c.y - 57f * k, endY = c.y + 57f * k),
        57.3f * k, c, style = Stroke(1.4f * k),
    )

    // MENU
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = LABEL.toArgb()
            textSize = 18f * k
            letterSpacing = 0.22f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = mono ?: android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
        }
        canvas.nativeCanvas.drawText("MENU", 166f * k, 64f * k, paint)
    }

    fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        drawPath(Path().apply { moveTo(x1 * k, y1 * k); lineTo(x2 * k, y2 * k); lineTo(x3 * k, y3 * k); close() }, LABEL)
    }
    fun bar(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(LABEL, p(x, y), Size(w * k, h * k), CornerRadius(1f * k))

    // Next
    triangle(256f, 153f, 256f, 175f, 270f, 164f)
    triangle(270f, 153f, 270f, 175f, 284f, 164f)
    bar(284f, 153f, 4f, 22f)
    // Previous
    triangle(72f, 153f, 72f, 175f, 58f, 164f)
    triangle(58f, 153f, 58f, 175f, 44f, 164f)
    bar(40f, 153f, 4f, 22f)
    // Play / pause
    triangle(147f, 253f, 147f, 275f, 165f, 264f)
    bar(170f, 253f, 4.5f, 22f)
    bar(178.5f, 253f, 4.5f, 22f)
}
