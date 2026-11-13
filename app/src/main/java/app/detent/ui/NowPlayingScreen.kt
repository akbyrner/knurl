package app.detent.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
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

    Column(modifier) {
        ScreenHeader("Now Playing", big = false, left = { PlayPauseGlyph(playing = info?.isPlaying == true, color = GLYPH) })
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // The cover sits above a frosted card holding the song details and the progress bar; it takes the room the card leaves.
            val art = minOf(maxWidth - 60.dp, maxHeight - CARD_HEIGHT - 20.dp)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
                    if (album != null) {
                        val shape = RoundedCornerShape(14.dp)
                        Art(
                            album,
                            Modifier.size(art).shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black),
                            corner = 14.dp,
                            letterSize = (art.value * 0.26f).sp,
                        )
                    } else {
                        Box(Modifier.size(art))
                    }
                }
                Box(Modifier.weight(1f))
                val cardShape = RoundedCornerShape(18.dp)
                Column(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(GLASS)
                        .border(1.dp, GLASS_BORDER, cardShape)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    if (info != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            InfoRow(InfoKind.Song) {
                                Text(info.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            InfoRow(InfoKind.Artist) {
                                Text(info.artist, color = INFO_TEXT, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            InfoRow(InfoKind.Album) {
                                Text(
                                    buildAnnotatedString {
                                        append(info.albumTitle)
                                        info.trackOf?.let { withStyle(SpanStyle(color = TEXT_SECONDARY, fontFamily = PlexMono, fontSize = 12.sp)) { append(" · ${it.replace(" of ", "/")}") } }
                                    },
                                    color = INFO_TEXT,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else {
                        Text("Nothing playing", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.height(12.dp))
                    if (showVolume) {
                        Bar(player.volume)
                        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                            Text("VOLUME", color = Color(0xFFB0B0B5), fontFamily = PlexMono, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.em)
                        }
                    } else {
                        val duration = info?.durationMs ?: 0L
                        val position = info?.positionMs ?: 0L
                        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                        val scrubbing = mode == NowPlayingMode.Scrub
                        Bar(fraction)
                        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                            val timeColor = if (scrubbing) Color.White else Color(0xFFB0B0B5)
                            Text(formatClock(position), color = timeColor, fontFamily = PlexMono, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.em, modifier = Modifier.weight(1f))
                            if (scrubbing) {
                                Text("SCRUB", color = ORANGE, fontFamily = PlexMono, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.12.em, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                            }
                            Text(
                                "-" + formatClock(duration - position),
                                color = timeColor,
                                fontFamily = PlexMono,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.06.em,
                                modifier = if (scrubbing) Modifier.weight(1f) else Modifier,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
                Box(Modifier.height(8.dp))
            }
        }
    }
}

private val CARD_HEIGHT = 148.dp
private val INFO_TEXT = Color(0xFFD0D0D4)
private val INFO_ICON = Color.White.copy(alpha = 0.6f)

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
                        INFO_ICON,
                        style = stroke,
                    )
                    drawCircle(INFO_ICON, 2 * u, Offset(4 * u, 13 * u))
                    drawCircle(INFO_ICON, 2 * u, Offset(12 * u, 11.5f * u))
                }
                InfoKind.Artist -> {
                    drawCircle(INFO_ICON, 2.9f * u, Offset(8 * u, 5.3f * u), style = stroke)
                    drawPath(
                        Path().apply {
                            moveTo(2.6f * u, 14.6f * u)
                            cubicTo(3.4f * u, 11.6f * u, 5.5f * u, 10.1f * u, 8 * u, 10.1f * u)
                            cubicTo(10.5f * u, 10.1f * u, 12.6f * u, 11.6f * u, 13.4f * u, 14.6f * u)
                        },
                        INFO_ICON,
                        style = stroke,
                    )
                }
                InfoKind.Album -> {
                    drawCircle(INFO_ICON, 6.4f * u, Offset(8 * u, 8 * u), style = stroke)
                    drawCircle(INFO_ICON, 1.7f * u, Offset(8 * u, 8 * u))
                }
            }
        }
        Box(Modifier.size(10.dp, 1.dp))
        content()
    }
}

/** A thin bar, white where it has played, with one orange tick as the playhead. Used for both progress and volume. */
@Composable
private fun Bar(fraction: Float) {
    val f = fraction.coerceIn(0f, 1f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(TRACK)) {
            Box(Modifier.fillMaxWidth(f).height(4.dp).background(BONE))
        }
        Box(
            Modifier
                .offset(x = (maxWidth - 3.dp) * f)
                .size(3.dp, 14.dp)
                .shadow(4.dp, RoundedCornerShape(2.dp), ambientColor = ORANGE, spotColor = ORANGE)
                .clip(RoundedCornerShape(2.dp))
                .background(ORANGE),
        )
    }
}
