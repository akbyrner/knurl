package app.detent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private var splashDone = false

/** The mark: a spun-steel knob with a knurled edge, a dark cap, and one orange tick. */
@Composable
fun KnurlMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 200f
        val c = Offset(100f * k, 100f * k)
        // Brushed steel: light and dark bands around the disc.
        rotate(110f, c) {
            drawCircle(
                Brush.sweepGradient(
                    0f to Color(0xFFECEEF1), 0.25f to Color(0xFF8E9198), 0.5f to Color(0xFFE4E6E9), 0.75f to Color(0xFF7F8289), 1f to Color(0xFFECEEF1),
                    center = c,
                ),
                100f * k, c,
            )
        }
        // The knurled edge: short ridges all the way round.
        drawCircle(
            Color(0xFF2A2B30).copy(alpha = 0.45f), 93f * k, c,
            style = Stroke(9f * k, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.2f * k, 5.9f * k), 0f)),
        )
        drawCircle(Color.White.copy(alpha = 0.35f), 86.5f * k, c, style = Stroke(1f * k))
        // The cap.
        drawCircle(
            Brush.radialGradient(0f to Color(0xFF3A3A40), 1f to Color(0xFF121214), center = Offset(100f * k, 70f * k), radius = 140f * k),
            40f * k, c,
        )
        drawCircle(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.White.copy(alpha = 0.5f)), startY = 60f * k, endY = 140f * k),
            40f * k, c, style = Stroke(2f * k),
        )
        drawRoundRect(ORANGE, Offset(97f * k, 15f * k), Size(6f * k, 28f * k), CornerRadius(3f * k))
    }
}

/** Shown once when the app starts: the mark, the name and a bar that fills while the library is read. */
@Composable
fun SplashOverlay() {
    var show by remember { mutableStateOf(!splashDone) }
    var go by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(if (go) 1f else 0f, tween(1400, easing = LinearEasing), label = "splash")
    LaunchedEffect(Unit) {
        go = true
        if (!splashDone) {
            delay(1500)
            splashDone = true
            show = false
        }
    }
    AnimatedVisibility(visible = show, exit = fadeOut(tween(350))) {
        Box(Modifier.fillMaxSize().background(GRAPHITE), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                KnurlMark(160.dp)
                Text(
                    "DETENT",
                    modifier = Modifier.padding(top = 36.dp),
                    color = BONE,
                    fontFamily = ArchivoExpanded,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.08.em,
                )
                MonoLabel("Click-wheel audio", Modifier.padding(top = 8.dp), size = 12)
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(horizontal = 120.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.05f, 1f)).height(3.dp).background(ORANGE))
                }
                MonoLabel("Reading library", Modifier.padding(top = 12.dp))
            }
        }
    }
}
