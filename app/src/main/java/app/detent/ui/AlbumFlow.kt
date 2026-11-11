package app.detent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import app.detent.data.Album
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
) {
    val position = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) {
        position.animateTo(selected.toFloat(), spring(dampingRatio = 0.82f, stiffness = 260f))
    }

    Column(modifier) {
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
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.01).em,
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
