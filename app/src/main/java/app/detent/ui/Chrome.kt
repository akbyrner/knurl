package app.detent.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// The look shared by every screen, taken from the design canvas.
val ROW_BG = Color(0xFF141416)
val ROW_BORDER = Color.White.copy(alpha = 0.06f)
val TEXT_SECONDARY = Color(0xFFA8A8AD)
val TEXT_TERTIARY = Color(0xFF8E8E93)
val GLYPH = Color(0xFFC4C4C4)

/** The phone's real battery level, drawn as the small battery from the design (not a copy of the system's one). */
@Composable
fun BatteryIndicator(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var level by remember { mutableStateOf(readLevel(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))) }
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                level = readLevel(intent)
                charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
                    it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    val fillColor = if (level <= 0.15f && !charging) Color(0xFFFF6B5E) else GLYPH
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(24.dp, 12.dp)
                .border(1.5.dp, GLYPH, RoundedCornerShape(3.dp))
                .padding(1.5.dp),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0.04f, 1f)).clip(RoundedCornerShape(1.dp)).background(fillColor))
        }
        Box(Modifier.padding(start = 1.dp).size(2.dp, 4.dp).clip(RoundedCornerShape(topEnd = 1.dp, bottomEnd = 1.dp)).background(GLYPH))
    }
}

private fun readLevel(intent: Intent?): Float {
    val l = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    return if (l >= 0 && scale > 0) l.toFloat() / scale else 0.7f
}

/** The title bar: a centred title, an optional glyph on the left, and the battery on the right. */
@Composable
fun ScreenHeader(title: String?, modifier: Modifier = Modifier, left: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxWidth().height(36.dp)) {
        if (left != null) Box(Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) { left() }
        if (title != null) {
            Text(
                title,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.01).em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BatteryIndicator(Modifier.align(Alignment.CenterEnd).padding(end = 13.dp))
    }
}

/** What is playing, as a card above the wheel on the menu screens. Tapping it (when [onClick] is given) opens Now Playing. */
@Composable
fun MiniPlayer(controller: DetentController, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            controller.player.refreshPosition()
            nowMs = System.currentTimeMillis()
            delay(500)
        }
    }
    val info = controller.nowPlayingInfo(nowMs) ?: return
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .background(ROW_BG)
            .border(1.dp, Color.White.copy(alpha = 0.07f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (info.art != null) Art(info.art, Modifier.size(44.dp), corner = 8.dp) else Box(Modifier.size(44.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    info.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.01).em,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(info.artist, color = TEXT_SECONDARY, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayPauseGlyph(playing = info.isPlaying)
        }
        // A thin line along the bottom edge shows how far into the song it is.
        val fraction = if (info.durationMs > 0) (info.positionMs.toFloat() / info.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(2.dp).background(Color.White))
    }
}

/** Pause bars while playing, a play triangle while paused. */
@Composable
fun PlayPauseGlyph(playing: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(14.dp, 16.dp)) {
        val u = size.width / 14f
        if (playing) {
            drawRoundRect(color, Offset(1 * u, 1 * u), Size(4 * u, 14 * u), androidx.compose.ui.geometry.CornerRadius(u))
            drawRoundRect(color, Offset(9 * u, 1 * u), Size(4 * u, 14 * u), androidx.compose.ui.geometry.CornerRadius(u))
        } else {
            drawPath(
                Path().apply {
                    moveTo(2 * u, 1 * u)
                    lineTo(2 * u, 15 * u)
                    lineTo(13 * u, 8 * u)
                    close()
                },
                color,
            )
        }
    }
}
