package app.knurl.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val TOGGLE_BLUE = Color(0xFF2563EB)

/** Settings: a theme picker, the wheel's feel, then the general rows. The wheel steps through them top to bottom. */
@Composable
fun SettingsScreen(controller: KnurlController, selected: Int, modifier: Modifier = Modifier, onTap: () -> Unit = {}) {
    fun press(row: Int) {
        if (controller.openSetting(row)) onTap()
    }
    Column(modifier) {
        ScreenHeader("Settings", Modifier.padding(bottom = 6.dp))
        MonoLabel("Theme", Modifier.padding(start = 20.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeChoice.entries.forEachIndexed { i, t ->
                ThemeTile(t, chosen = controller.theme == t, focused = selected == i, modifier = Modifier.weight(1f)) { press(i) }
            }
        }

        MonoLabel("Wheel", Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingRow("Click sound", selected == SettingsRow.CLICK, toggle = controller.clickSound) { press(SettingsRow.CLICK) }
            SettingRow("Haptic ticks", selected == SettingsRow.HAPTIC, toggle = controller.hapticTicks) { press(SettingsRow.HAPTIC) }
            SettingRow("Wheel speed", selected == SettingsRow.SPEED, value = controller.wheelSpeedLabel) { press(SettingsRow.SPEED) }
        }

        MonoLabel("General", Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingRow("Sources", selected == SettingsRow.SOURCES, value = "${controller.sourcesOn} on", chevron = true) { press(SettingsRow.SOURCES) }
            SettingRow("About Knurl", selected == SettingsRow.ABOUT, value = "v0.1", chevron = true) { press(SettingsRow.ABOUT) }
            if (controller.spotifySignedIn) {
                SettingRow("Sign out of Spotify", selected == SettingsRow.SIGN_OUT) { press(SettingsRow.SIGN_OUT) }
            }
        }
    }
}

@Composable
private fun ThemeTile(theme: ThemeChoice, chosen: Boolean, focused: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val later = theme == ThemeChoice.Silver
    val shape = RoundedCornerShape(12.dp)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .alpha(if (later) 0.55f else 1f)
                .clip(shape)
                .background(
                    when (theme) {
                        ThemeChoice.GraphiteGlass -> Brush.verticalGradient(0f to Color(0xFF1F4B44), 0.7f to Color(0xFF18181B))
                        ThemeChoice.Graphite -> Brush.verticalGradient(listOf(Color(0xFF1F1F22), Color(0xFF1F1F22)))
                        ThemeChoice.Silver -> Brush.verticalGradient(listOf(Color(0xFFF5F5F7), Color(0xFFE2E3E7)))
                    },
                )
                .border(if (focused) 2.dp else 1.dp, if (focused) BONE else Color.White.copy(alpha = 0.12f), shape),
        ) {
            val bars = when (theme) {
                ThemeChoice.GraphiteGlass -> listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.18f))
                ThemeChoice.Graphite -> listOf(Color(0xFF2563EB), Color(0xFF2C2C31), Color(0xFF2C2C31))
                ThemeChoice.Silver -> listOf(Color(0xFF2563EB), Color.White, Color.White)
            }
            bars.forEachIndexed { k, c ->
                Box(Modifier.padding(start = 10.dp, end = 10.dp).offset(y = (14 + 12 * k).dp).fillMaxWidth().height(7.dp).clip(RoundedCornerShape(3.dp)).background(c))
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 14.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (theme == ThemeChoice.Silver) Color.White else Color(0xFF26262B)),
            )
            if (chosen) {
                Canvas(Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp)) {
                    val u = size.width / 16f
                    drawCircle(TOGGLE_BLUE)
                    drawLine(Color.White, Offset(4.5f * u, 8.2f * u), Offset(7f * u, 10.5f * u), 1.8f * u, StrokeCap.Round)
                    drawLine(Color.White, Offset(7f * u, 10.5f * u), Offset(11.5f * u, 5.5f * u), 1.8f * u, StrokeCap.Round)
                }
            }
        }
        Text(
            theme.label,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            color = if (focused) BONE else Color(0xFFC9C9CC),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    on: Boolean,
    toggle: Boolean? = null,
    value: String? = null,
    chevron: Boolean = value != null && toggle == null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .height(46.dp)
            .then(if (on) Modifier.shadow(8.dp, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .clip(shape)
            .background(if (on) Color.White.copy(alpha = 0.92f) else ROW_BG)
            .border(1.dp, if (on) Color.White.copy(alpha = 0.9f) else ROW_BORDER, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = if (on) INK else BONE,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.01).em,
        )
        if (value != null) {
            Text(
                value.uppercase(),
                color = if (on) Color(0xFF55555C) else Color(0xFFA8A9AF),
                fontFamily = PlexMono,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.12.em,
            )
        }
        if (toggle != null) Toggle(toggle)
        if (chevron) {
            Canvas(Modifier.padding(start = 10.dp).size(width = 9.dp, height = 14.dp)) {
                val stroke = 2.dp.toPx()
                val inset = stroke / 2
                val c = if (on) INK else Color.White.copy(alpha = 0.55f)
                drawLine(c, Offset(inset, inset), Offset(size.width - inset, size.height / 2), stroke, StrokeCap.Round)
                drawLine(c, Offset(size.width - inset, size.height / 2), Offset(inset, size.height - inset), stroke, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun Toggle(on: Boolean) {
    Box(
        Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(13.dp)).background(if (on) TOGGLE_BLUE else Color(0xFF45454B)),
    ) {
        Box(
            Modifier
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(3.dp)
                .size(20.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
