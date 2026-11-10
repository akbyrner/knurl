package app.detent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.detent.data.Library
import app.detent.playback.PlayerController
import kotlinx.coroutines.delay

private val TRACK = Color.White.copy(alpha = 0.18f)
private val FILL = Brush.verticalGradient(listOf(Color(0xFF4A95EA), Color(0xFF2A73D6)))

/** The album and its reflection on the black plane, track info, and a progress bar that turns into a volume bar. */
@Composable
fun NowPlayingScreen(library: Library, player: PlayerController, modifier: Modifier = Modifier) {
    val track = player.currentTrackId?.let { library.track(it) }
    val album = track?.let { library.album(it.albumId) }

    // The screen asks for the position while it is showing; the player only reports changes.
    LaunchedEffect(player.isPlaying) {
        while (true) {
            player.refreshPosition()
            delay(500)
        }
    }

    // Show the volume bar for a moment after each wheel tick, then go back to progress.
    var showVolume by remember { mutableStateOf(false) }
    LaunchedEffect(player.volumeChangedAt) {
        if (player.volumeChangedAt != 0L) {
            showVolume = true
            delay(1500)
            showVolume = false
        }
    }

    BoxWithConstraints(modifier.background(Color.Black)) {
        val art = minOf(maxWidth * 0.58f, maxHeight * 0.44f)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (album != null) {
                ArtWithReflection(album, art, reflectionFraction = 0.4f)
            } else {
                Box(Modifier.height(art))
            }
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    track?.title ?: "Nothing playing",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (track != null) {
                    Text(track.artist, color = Color(0xFFC9C9C9), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.albumTitle, color = Color(0xFFA0A0A8), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.weight(1f))
            if (showVolume) {
                Bar(player.volume)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                    Text("Volume", color = Color(0xFFB0B0B0), fontSize = 13.sp)
                }
            } else {
                val duration = if (player.durationMs > 0) player.durationMs else track?.durationMs ?: 0L
                val fraction = if (duration > 0) (player.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Bar(fraction)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
                    Text(formatTime(player.positionMs), color = Color(0xFFB0B0B0), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("-" + formatTime(duration - player.positionMs), color = Color(0xFFB0B0B0), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(TRACK)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).background(FILL))
    }
}
