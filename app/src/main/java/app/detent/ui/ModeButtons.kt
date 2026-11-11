package app.detent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

enum class ModeIcon { Shuffle, Repeat, Heart, Queue }

private val DISC_TOP = Color(0xFF36363C)
private val DISC_MID = Color(0xFF1F1F23)
private val DISC_BOTTOM = Color(0xFF131315)
private val ICON_OFF = Color(0xFFC9C9CE)
private val ICON_ON = Color(0xFFA8D0FF)
private val BLUE = Color(0xFF3B82F6)
private val RIM_ON = Color(0xFF6FB1FF)
private val BADGE = Color(0xFF2563EB)

// The two glyphs from the design, drawn in a 72-unit square.
private val SHUFFLE_PATHS: List<Path> by lazy {
    listOf(
        "M20 27h6c5 0 7 3 10 9s5 9 10 9h6",
        "M48 41l4.5 4-4.5 4",
        "M20 45h6c3 0 5-1.2 6.6-3.4",
        "M39.4 30.4c1.6-2.2 3.6-3.4 6.6-3.4h6",
        "M48 23l4.5 4-4.5 4",
    ).map { PathParser().parsePathString(it).toPath() }
}
private val REPEAT_PATHS: List<Path> by lazy {
    listOf(
        "M24 34v-3a5 5 0 0 1 5-5h18",
        "M43 22l4.5 4-4.5 4",
        "M48 38v3a5 5 0 0 1-5 5H25",
        "M29 42l-4.5 4 4.5 4",
    ).map { PathParser().parsePathString(it).toPath() }
}

// A heart, for liking the song.
private val HEART_PATHS: List<Path> by lazy {
    listOf("M36 51C22 41 19 34 19 29a8.5 8.5 0 0 1 17-3 8.5 8.5 0 0 1 17 3c0 5-3 12-17 22z")
        .map { PathParser().parsePathString(it).toPath() }
}

// Three lines of a list and a small play arrow: what plays next.
private val QUEUE_PATHS: List<Path> by lazy {
    listOf("M21 28h22", "M21 36h22", "M21 44h12", "M40 41l10 5.5-10 5.5z")
        .map { PathParser().parsePathString(it).toPath() }
}

/**
 * A round, matte button like the wheel: dark with a light top edge when off; when on it gets a blue rim, a blue
 * glow around it and a lit icon. [badge] is the small blue "1" that marks repeat-one.
 */
@Composable
fun ModeButton(
    icon: ModeIcon,
    lit: Boolean,
    description: String,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Box(
        modifier
            .size(size)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(onClick) { detectTapGestures { onClick() } },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawModeButton(icon, lit, badge) }
    }
}

private fun DrawScope.drawModeButton(icon: ModeIcon, lit: Boolean, badge: String?) {
    val k = size.minDimension / 72f
    val c = Offset(36f * k, 36f * k)
    val r = 35f * k

    if (lit) {
        // Glow around the rim: strongest at the edge, fading out over about a third of the radius.
        val reach = r * 1.4f
        drawCircle(
            Brush.radialGradient(
                0.70f to BLUE.copy(alpha = 0.55f), 1f to Color.Transparent,
                center = c, radius = reach,
            ),
            reach, c,
        )
    }

    // The disc, lit from the top like the wheel.
    drawCircle(
        Brush.radialGradient(
            0f to DISC_TOP, 0.6f to DISC_MID, 1f to DISC_BOTTOM,
            center = Offset(c.x, 72f * k * 0.28f), radius = 72f * k * 0.78f,
        ),
        r, c,
    )
    if (lit) {
        // A faint blue tint creeping in from the edge.
        drawCircle(
            Brush.radialGradient(0.55f to BLUE.copy(alpha = 0f), 1f to BLUE.copy(alpha = 0.4f), center = c, radius = r),
            r, c,
        )
        drawCircle(RIM_ON, 34.2f * k, c, style = Stroke(1.6f * k))
    } else {
        drawCircle(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.32f), 0.5f to Color.White.copy(alpha = 0.04f), 1f to Color.White.copy(alpha = 0.12f),
                startY = c.y - 34f * k, endY = c.y + 34f * k,
            ),
            34.3f * k, c, style = Stroke(1.4f * k),
        )
    }

    val paths = when (icon) {
        ModeIcon.Shuffle -> SHUFFLE_PATHS
        ModeIcon.Repeat -> REPEAT_PATHS
        ModeIcon.Heart -> HEART_PATHS
        ModeIcon.Queue -> QUEUE_PATHS
    }
    withTransform({ scale(k, k, pivot = Offset.Zero) }) {
        if (lit && icon == ModeIcon.Heart) {
            // A liked heart is filled in.
            paths.forEach { drawPath(it, ICON_ON.copy(alpha = 0.45f)) }
        }
        if (lit) {
            // Soft halo behind the lit icon.
            val halo = Stroke(6.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            paths.forEach { drawPath(it, ICON_ON.copy(alpha = 0.35f), style = halo) }
        }
        val line = Stroke(2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        paths.forEach { drawPath(it, if (lit) ICON_ON else ICON_OFF, style = line) }
    }

    if (badge != null) {
        drawCircle(GRAPHITE, 11f * k, Offset(55f * k, 17f * k))
        drawCircle(BADGE, 10f * k, Offset(55f * k, 17f * k))
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.White.toArgb()
                textSize = 13f * k
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 700, false)
            }
            canvas.nativeCanvas.drawText(badge, 55f * k, 21.5f * k, paint)
        }
    }
}
