package app.knurl.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.knurl.data.Album
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Flow: black stage, selected album front and centre, the rest fanned out with reflections. */
@Composable
fun AlbumFlow(
    albums: List<Album>,
    selected: Int,
    modifier: Modifier = Modifier,
    label: String = "Flow",
    /** One dot per stop instead of a dial of ticks: for the short Spotify home. */
    dots: Boolean = false,
) {
    val position = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) {
        position.animateTo(selected.toFloat(), spring(dampingRatio = 0.82f, stiffness = 260f))
    }

    Box(modifier) {
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cover = minOf(maxWidth * 0.64f, maxHeight * 0.80f)
            val p = position.value
            val first = max(0, floor(p - VISIBLE).toInt())
            val last = min(albums.lastIndex, ceil(p + VISIBLE).toInt())
            if (first <= last) {
                // far to near, so the centre cover paints last (on top)
                (first..last).sortedByDescending { abs(it - p) }.forEach { i ->
                    key(albums[i].id) {
                        FlowItem(albums[i], offset = i - p, cover = cover)
                    }
                }
            }
        }

        val current = albums.getOrNull(position.value.roundToInt().coerceIn(0, max(0, albums.lastIndex)))
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                current?.title.orEmpty(),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.02).em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                current?.artist.orEmpty(),
                color = TEXT_SECONDARY,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Box(Modifier.height(14.dp))
            PositionMarker(count = albums.size, position = position.value, dots = dots)
        }
    }
    MonoLabel(label, Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 12.dp))
    BatteryIndicator(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 13.dp))
    }
}

/**
 * Where you are in the list. A short list gets one dot per stop with an orange pill on the current one; a long one gets
 * a dial of ticks that slides past a fixed orange marker, like turning the wheel.
 */
@Composable
private fun PositionMarker(count: Int, position: Float, dots: Boolean) {
    if (count <= 1) return
    if (dots) {
        Row(Modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until count) {
                if (i == position.roundToInt()) {
                    Box(Modifier.size(22.dp, 8.dp).shadow(6.dp, RoundedCornerShape(4.dp), ambientColor = ORANGE, spotColor = ORANGE).clip(RoundedCornerShape(4.dp)).background(ORANGE))
                } else {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
                }
            }
        }
    } else {
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(24.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().height(16.dp).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                val gap = 7.dp.toPx()
                val mid = size.width / 2f
                                // Only the ticks that can be seen.
                var k = 0
                while (k < count) {
                    val tx = mid + (k - position) * gap
                    if (tx in 0f..size.width) drawLine(Color.White.copy(alpha = 0.28f), Offset(tx, 0f), Offset(tx, size.height), 1.dp.toPx())
                    k++
                }
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            }
            Box(Modifier.size(3.dp, 24.dp).shadow(4.dp, RoundedCornerShape(2.dp), ambientColor = ORANGE, spotColor = ORANGE).clip(RoundedCornerShape(2.dp)).background(ORANGE))
        }
    }
}

private const val VISIBLE = 4f
private const val TILT_DEGREES = 62f

@Composable
private fun FlowItem(album: Album, offset: Float, cover: Dp) {
    val density = LocalDensity.current
    val coverPx = with(density) { cover.toPx() }
    val a = abs(offset)
    val sign = if (offset < 0) -1f else 1f
    // first neighbour sits well clear of the centre cover, the rest stack tightly
    val x = sign * (min(a, 1f) * coverPx * 0.66f + max(a - 1f, 0f) * coverPx * 0.16f)
    val angle = -(offset.coerceIn(-1f, 1f)) * TILT_DEGREES
    val scale = 1f - min(a, 1f) * 0.12f

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = x
                rotationY = angle
                cameraDistance = 12f * density.density
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        ArtWithReflection(album, cover, corner = 8.dp, reflectionHeight = cover * 0.48f, mirrorAlpha = 0.34f)
    }
}
