package app.detent.ui

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
}

@Composable
private fun rememberArt(album: Album): ImageBitmap? {
    val resolver = LocalContext.current.contentResolver
    return produceState(initialValue = ArtCache.lru.get(album.id), album.id) {
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
fun Art(album: Album, modifier: Modifier) {
    val bmp = rememberArt(album)
    if (bmp != null) {
        Image(bmp, contentDescription = album.title, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        // Deterministic colour from the album id so each placeholder is distinct and stable.
        val h = ((album.id * 2654435761L) and 0xFFFF).toFloat() / 0xFFFF
        val top = Color.hsv(h * 360f, 0.55f, 0.85f)
        val bottom = Color.hsv((h * 360f + 40f) % 360f, 0.70f, 0.45f)
        Box(modifier.background(Brush.linearGradient(listOf(top, bottom))), contentAlignment = Alignment.Center) {
            Text(
                album.title.take(1).uppercase(),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** The cover with its fading mirror image underneath, as if standing on a glossy black floor. */
@Composable
fun ArtWithReflection(album: Album, size: Dp, reflectionFraction: Float = 0.5f) {
    Column(Modifier.width(size), horizontalAlignment = Alignment.CenterHorizontally) {
        Art(album, Modifier.size(size))
        Box(
            Modifier
                .size(size, size * reflectionFraction)
                .clipToBounds()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.Transparent)),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            Art(album, Modifier.size(size).graphicsLayer { scaleY = -1f })
        }
    }
}
