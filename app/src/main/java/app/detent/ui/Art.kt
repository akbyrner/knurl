package app.detent.ui

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import app.detent.data.Album
import app.detent.data.loadArt
import app.detent.data.loadArtFromUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object ArtCache {
    val lru = LruCache<Long, ImageBitmap>(48)
    val missing = HashSet<Long>()

    /** Counts art that arrived from outside (the Spotify app), so tiles waiting for it draw again. */
    var version by androidx.compose.runtime.mutableIntStateOf(0)
}

/** Adds cover art that came as a ready bitmap, such as from the Spotify app. */
fun putArt(albumId: Long, bitmap: android.graphics.Bitmap) {
    ArtCache.lru.put(albumId, bitmap.asImageBitmap())
    ArtCache.missing -= albumId
    ArtCache.version++
}

@Composable
private fun rememberArt(album: Album): ImageBitmap? {
    val resolver = LocalContext.current.contentResolver
    val version = ArtCache.version
    return produceState(initialValue = ArtCache.lru.get(album.id), album.id, version) {
        if (value == null) value = ArtCache.lru.get(album.id)
        val uri = album.artUri
        val url = album.artUrl
        if (value == null && (uri != null || url != null) && album.id !in ArtCache.missing) {
            val bmp = withContext(Dispatchers.IO) {
                if (url != null) loadArtFromUrl(url) else loadArt(resolver, uri!!, 512)
            }
            if (bmp != null) {
                val ib = bmp.asImageBitmap()
                ArtCache.lru.put(album.id, ib)
                value = ib
            } else {
                ArtCache.missing += album.id
            }
        }
    }.value
}

/** Album art, or a stable coloured tile with the album's first letter when the files carry none. */
@Composable
fun Art(album: Album, modifier: Modifier, corner: Dp = 0.dp, letterSize: TextUnit = 64.sp) {
    val bmp = rememberArt(album)
    val shape = RoundedCornerShape(corner)
    // A faint inner edge keeps dark covers from melting into the black plane.
    val framed = modifier.clip(shape).then(if (corner > 0.dp) Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), shape) else Modifier)
    if (bmp != null) {
        Image(bmp, contentDescription = album.title, modifier = framed, contentScale = ContentScale.Crop)
    } else {
        // Deterministic colour from the album id so each placeholder is distinct and stable.
        val h = ((album.id * 2654435761L) and 0xFFFF).toFloat() / 0xFFFF
        val top = Color.hsv(h * 360f, 0.55f, 0.85f)
        val bottom = Color.hsv((h * 360f + 40f) % 360f, 0.70f, 0.45f)
        Box(framed.background(Brush.linearGradient(listOf(top, bottom))), contentAlignment = Alignment.Center) {
            Text(
                album.title.take(1).uppercase(),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = letterSize,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * The cover with its fading mirror image underneath, as if standing on a glossy black floor.
 * [reflectionHeight] is how much of the mirror image shows; [mirrorAlpha] is how bright its top edge is.
 */
@Composable
fun ArtWithReflection(
    album: Album,
    size: Dp,
    corner: Dp = 8.dp,
    reflectionHeight: Dp = size * 0.48f,
    mirrorAlpha: Float = 0.34f,
) {
    Column(Modifier.width(size), horizontalAlignment = Alignment.CenterHorizontally) {
        Art(album, Modifier.size(size), corner = corner, letterSize = (size.value * 0.26f).sp)
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(size, reflectionHeight)
                .clipToBounds()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = mirrorAlpha), Color.Transparent)),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            Art(album, Modifier.size(size).graphicsLayer { scaleY = -1f }, corner = corner, letterSize = (size.value * 0.26f).sp)
        }
    }
}
