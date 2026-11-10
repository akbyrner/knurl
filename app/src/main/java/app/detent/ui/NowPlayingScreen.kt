package app.detent.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val TRACK = Color.White.copy(alpha = 0.18f)

/** The cover and its reflection on the black plane, song details with a small symbol each, and a thin progress bar. */
@Composable
fun NowPlayingScreen(
    controller: DetentController,
    mode: NowPlayingMode,
    modifier: Modifier = Modifier,
) {
    val player = controller.player

    // The phone's player only reports changes, and Spotify's position is worked out from the clock,
    // so this screen asks for a fresh reading twice a second while it is showing.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            player.refreshPosition()
            nowMs = System.currentTimeMillis()
            delay(500)
        }
    }
    val info = controller.nowPlayingInfo(nowMs)
    val album = info?.art

    // Show the volume bar for a moment after each wheel tick, then go back to progress.
    var showVolume by remember { mutableStateOf(false) }
    LaunchedEffect(player.volumeChangedAt) {
        if (player.volumeChangedAt != 0L) {
            showVolume = true
            delay(1500)
            showVolume = false
        }
    }

    Column(modifier.background(Color.Black)) {
        ScreenHeader("Now Playing", left = { PlayPauseGlyph(playing = info?.isPlaying == true, color = GLYPH) })
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // As in the design: the cover takes nearly the whole width (16dp margins), as tall as fits above the details.
            val art = minOf(maxWidth - 32.dp, maxHeight - 130.dp)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
                    if (album != null) {
                        // A soft glow from the cover's own colours, behind it. Needs Android 12 for the blur.
                        if (Build.VERSION.SDK_INT >= 31) {
                            Art(
                                album,
                                Modifier.padding(top = art * 0.1f).size(art * 0.8f, art * 0.7f).alpha(0.45f).blur(56.dp).clip(CircleShape),
                            )
                        }
                        Art(album, Modifier.size(art), corner = 12.dp, letterSize = (art.value * 0.26f).sp)
                    } else {
                        Box(Modifier.size(art))
                    }
                }
                Box(Modifier.weight(1f))
                if (info != null) {
                    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        InfoRow(InfoKind.Song) {
                            Text(info.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        InfoRow(InfoKind.Artist) {
                            Text(info.artist, color = Color(0xFFC9C9C9), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        InfoRow(InfoKind.Album) {
                            Text(
                                buildAnnotatedString {
                                    append(info.albumTitle)
                                    info.trackOf?.let { withStyle(SpanStyle(color = TEXT_TERTIARY)) { append(" · $it") } }
                                },
                                color = Color(0xFFC9C9C9),
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Text("Nothing playing", modifier = Modifier.padding(horizontal = 24.dp), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                if (showVolume) {
                    Bar(player.volume)
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp)) {
                        Text("Volume", color = TEXT_SECONDARY, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    val duration = info?.durationMs ?: 0L
                    val position = info?.positionMs ?: 0L
                    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    val scrubbing = mode == NowPlayingMode.Scrub
                    Bar(fraction)
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp)) {
                        val timeColor = if (scrubbing) Color.White else TEXT_SECONDARY
                        Text(formatTime(position), color = timeColor, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        if (scrubbing) {
                            Text("Scrub", color = Color(0xFF5AA0FF), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        }
                        Text(
                            "-" + formatTime(duration - position),
                            color = timeColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = if (scrubbing) Modifier.weight(1f) else Modifier,
                            textAlign = TextAlign.End,
                        )
                    }
                }
                }
            }
        }
    }
}

private enum class InfoKind { Song, Artist, Album }

@Composable
private fun InfoRow(kind: InfoKind, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(16.dp)) {
            val u = size.width / 16f
            val stroke = Stroke(1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (kind) {
                InfoKind.Song -> {
                    drawPath(
                        Path().apply {
                            moveTo(6 * u, 13 * u); lineTo(6 * u, 3.2f * u); lineTo(14 * u, 1.6f * u); lineTo(14 * u, 11.5f * u)
                        },
                        TEXT_TERTIARY,
                        style = stroke,
                    )
                    drawCircle(TEXT_TERTIARY, 2 * u, Offset(4 * u, 13 * u))
                    drawCircle(TEXT_TERTIARY, 2 * u, Offset(12 * u, 11.5f * u))
                }
                InfoKind.Artist -> {
                    drawCircle(TEXT_TERTIARY, 2.9f * u, Offset(8 * u, 5.3f * u), style = stroke)
                    drawPath(
                        Path().apply {
                            moveTo(2.6f * u, 14.6f * u)
                            cubicTo(3.4f * u, 11.6f * u, 5.5f * u, 10.1f * u, 8 * u, 10.1f * u)
                            cubicTo(10.5f * u, 10.1f * u, 12.6f * u, 11.6f * u, 13.4f * u, 14.6f * u)
                        },
                        TEXT_TERTIARY,
                        style = stroke,
                    )
                }
                InfoKind.Album -> {
                    drawCircle(TEXT_TERTIARY, 6.4f * u, Offset(8 * u, 8 * u), style = stroke)
                    drawCircle(TEXT_TERTIARY, 1.7f * u, Offset(8 * u, 8 * u))
                }
            }
        }
        Box(Modifier.size(10.dp, 1.dp))
        content()
    }
}

/** A thin bar with a round knob, used for both progress and volume. */
@Composable
private fun Bar(fraction: Float) {
    val f = fraction.coerceIn(0f, 1f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(TRACK)) {
            Box(Modifier.fillMaxWidth(f).height(4.dp).background(Color.White))
        }
        Box(Modifier.offset(x = (maxWidth - 12.dp) * f).size(12.dp).clip(CircleShape).background(Color.White))
    }
}
