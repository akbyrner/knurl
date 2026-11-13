package app.knurl.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/** The line icons from the design: the library entries, the sources and the round buttons. */
enum class RowIcon { Flow, Albums, Artists, Songs, Playlists, Sources, Settings, Phone, Spotify, Server, YouTube, Download, Shuffle }

private fun p(vararg d: String): List<Path> = d.map { PathParser().parsePathString(it).toPath() }
private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) = Path().apply { addRoundRect(RoundRect(Rect(x, y, x + w, y + h), r, r)) }
private fun circle(cx: Float, cy: Float, r: Float) = Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }

private val ICONS: Map<RowIcon, List<Path>> by lazy {
    mapOf(
        RowIcon.Flow to p("M2 7.5l5-1.5v12l-5-1.5zM22 7.5l-5-1.5v12l5-1.5z") + rr(7f, 4f, 10f, 16f, 1.5f),
        RowIcon.Albums to listOf(rr(3f, 3f, 18f, 18f, 2.5f), circle(12f, 12f, 4f), circle(12f, 12f, 0.6f)),
        RowIcon.Artists to listOf(circle(12f, 8f, 4f)) + p("M4 21c1.2-4.5 4.3-6.5 8-6.5s6.8 2 8 6.5"),
        RowIcon.Songs to p("M9 18V5l11-2v13") + listOf(circle(6f, 18f, 3f), circle(17f, 16f, 3f)),
        RowIcon.Playlists to p("M3 6h12M3 11h12M3 16h7", "M17 17V8l4-1") + listOf(circle(15f, 17f, 2f)),
        RowIcon.Sources to listOf(rr(3f, 4f, 18f, 6f, 2f), rr(3f, 14f, 18f, 6f, 2f)) + p("M7 7h.01M7 17h.01"),
        RowIcon.Settings to p("M4 7h10M18 7h2M4 17h4M12 17h8") + listOf(circle(16f, 7f, 2f), circle(10f, 17f, 2f)),
        RowIcon.Phone to listOf(rr(6f, 2.5f, 12f, 19f, 2.5f)) + p("M11 18.5h2"),
        RowIcon.Spotify to listOf(circle(12f, 12f, 9f)) + p("M7.5 9.5c3-1 6.5-.7 9 .8M8 12.8c2.5-.7 5.3-.4 7.4.7M8.6 15.8c2-.5 4-.3 5.6.5"),
        RowIcon.Server to listOf(rr(3f, 4f, 18f, 6f, 2f), rr(3f, 14f, 18f, 6f, 2f)) + p("M7 7h.01M7 17h.01"),
        RowIcon.YouTube to listOf(circle(12f, 12f, 9f)) + p("M10 8.5v7l5.5-3.5z"),
        RowIcon.Download to p("M12 4v11M7 10l5 5 5-5M5 20h14"),
        // Drawn in the 72-unit square of the wheel buttons; see the transform below.
        RowIcon.Shuffle to p(
            "M20 27h6c5 0 7 3 10 9s5 9 10 9h6", "M48 41l4.5 4-4.5 4", "M20 45h6c3 0 5-1.2 6.6-3.4",
            "M39.4 30.4c1.6-2.2 3.6-3.4 6.6-3.4h6", "M48 23l4.5 4-4.5 4",
        ),
    )
}

@Composable
fun IconGlyph(icon: RowIcon, size: Dp, color: Color, modifier: Modifier = Modifier, stroke: Float = 1.9f) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        val paths = ICONS.getValue(icon)
        val line = Stroke(
            width = if (icon == RowIcon.Shuffle) 3.6f else stroke,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        withTransform({
            scale(k, k, pivot = Offset.Zero)
            if (icon == RowIcon.Shuffle) {
                scale(24f / 44f, 24f / 44f, pivot = Offset.Zero)
                translate(-14f, -14f)
            }
        }) {
            paths.forEach { drawPath(it, color, style = line) }
        }
    }
}
