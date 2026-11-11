package app.detent.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import app.detent.data.Album
import kotlin.random.Random

/** A small tile of faint white speckle, repeated over the plane so it reads as grained material like the wheel. */
private val GRAIN: ShaderBrush by lazy {
    val side = 128
    val rnd = Random(7)
    val pixels = IntArray(side * side) { (rnd.nextInt(0, 19) shl 24) or 0x00FFFFFF }
    val bitmap = android.graphics.Bitmap.createBitmap(pixels, side, side, android.graphics.Bitmap.Config.ARGB_8888)
    ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/**
 * The Graphite Glass plane every screen sits on: deep graphite with grain and a soft light from the top, so the screen
 * and the wheel read as one material. While something plays, a faint blurred wash of its cover colours the top;
 * [tall] gives Now Playing a bigger, stronger wash.
 */
@Composable
fun GraphitePlane(art: Album?, tall: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.background(GRAPHITE).clipToBounds()) {
        if (art != null && Build.VERSION.SDK_INT >= 31) {
            Art(
                art,
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .offset(x = (-80).dp, y = if (tall) (-100).dp else (-140).dp)
                    .size(550.dp, if (tall) 680.dp else 560.dp)
                    .alpha(if (tall) 0.5f else 0.4f)
                    .blur(60.dp, BlurredEdgeTreatment.Unbounded),
            )
        }
        // The wash fades into plain graphite towards the wheel.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to GRAPHITE.copy(alpha = 0f),
                    (if (tall) 0.45f else 0.40f) to GRAPHITE.copy(alpha = if (tall) 0.4f else 0.55f),
                    (if (tall) 0.68f else 0.62f) to GRAPHITE,
                    1f to GRAPHITE,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().drawBehind {
                // Soft light from the top edge.
                drawRect(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.07f), 1f to Color.Transparent,
                        center = Offset(size.width / 2, 0f), radius = size.width * 0.9f,
                    ),
                )
                drawRect(GRAIN)
            },
        )
    }
}
