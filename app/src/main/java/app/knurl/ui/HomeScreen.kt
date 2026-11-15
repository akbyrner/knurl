package app.knurl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import app.knurl.data.Album

private val GUNMETAL = Color(0xFF2A2A2F)
private val HEART: Path by lazy {
    PathParser().parsePathString("M36 51C22 41 19 34 19 29a8.5 8.5 0 0 1 17-3 8.5 8.5 0 0 1 17 3c0 5-3 12-17 22z").toPath()
}

/**
 * The home screen: a greeting with the round Shuffle and Settings buttons, a shelf of pinned things, then the
 * library as a list. The wheel steps through the buttons, the pinned tiles and the rows in that order.
 */
@Composable
fun HomeScreen(controller: KnurlController, selected: Int, modifier: Modifier = Modifier, onTap: () -> Unit = {}) {
    val entries = controller.menuEntries()
    fun isOn(e: MenuEntry) = entries.getOrNull(selected) == e
    fun open(e: MenuEntry) {
        if (controller.openMenu(e)) onTap()
    }

    Column(modifier) {
        // Greeting on the left; battery, Shuffle and Settings on the right.
        Box(Modifier.fillMaxWidth().height(44.dp)) {
            MonoLabel(controller.greeting(), Modifier.align(Alignment.CenterStart).padding(start = 20.dp), color = Color(0xFFA8A9AF))
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                BatteryIndicator(Modifier.padding(end = 14.dp))
                RoundButton(RowIcon.Shuffle, "Shuffle Songs", isOn(MenuEntry.Shuffle)) { open(MenuEntry.Shuffle) }
                Box(Modifier.size(8.dp))
                RoundButton(RowIcon.Settings, "Settings", isOn(MenuEntry.Settings)) { open(MenuEntry.Settings) }
            }
        }

        MonoLabel("Pinned", Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val album = controller.pinnedAlbum
            val recent = controller.pinnedRecent
            PinTile("Liked Songs", isOn(MenuEntry.PinLiked), { open(MenuEntry.PinLiked) }, Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF9333EA), Color(0xFFDB2777)))), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(30.dp)) {
                        withTransform({ scale(size.width / 72f, size.width / 72f, pivot = Offset.Zero) }) { drawPath(HEART, Color.White) }
                    }
                }
            }
            PinTile(album?.title ?: "Last played", isOn(MenuEntry.PinAlbum), { open(MenuEntry.PinAlbum) }, Modifier.weight(1f)) {
                PinArt(album)
            }
            PinTile("Recently Added", isOn(MenuEntry.PinRecent), { open(MenuEntry.PinRecent) }, Modifier.weight(1f)) {
                PinArt(recent)
            }
            PinTile("On This Phone", isOn(MenuEntry.PinPhone), { open(MenuEntry.PinPhone) }, Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(GUNMETAL), contentAlignment = Alignment.Center) {
                    IconGlyph(RowIcon.Phone, 28.dp, Color(0xFFD0D0D4))
                }
            }
        }

        MonoLabel("Library", Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LibraryRow(RowIcon.Flow, "Flow", isOn(MenuEntry.Flow)) { open(MenuEntry.Flow) }
            LibraryRow(RowIcon.Albums, "Albums", isOn(MenuEntry.Albums)) { open(MenuEntry.Albums) }
            LibraryRow(RowIcon.Artists, "Artists", isOn(MenuEntry.Artists)) { open(MenuEntry.Artists) }
            LibraryRow(RowIcon.Songs, "Songs", isOn(MenuEntry.Songs)) { open(MenuEntry.Songs) }
            LibraryRow(RowIcon.Playlists, "Playlists", isOn(MenuEntry.Playlists)) { open(MenuEntry.Playlists) }
            LibraryRow(RowIcon.Sources, "Sources", isOn(MenuEntry.Sources)) { open(MenuEntry.Sources) }
        }
    }
}

@Composable
private fun PinArt(album: Album?) {
    if (album != null) {
        Art(album, Modifier.fillMaxWidth().aspectRatio(1f), letterSize = 28.sp)
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(GUNMETAL))
    }
}

@Composable
private fun PinTile(name: String, on: Boolean, onClick: () -> Unit, modifier: Modifier, art: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(8.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .border(if (on) 3.dp else 1.dp, if (on) BONE else Color.White.copy(alpha = 0.08f), shape),
        ) { art() }
        Text(
            name,
            modifier = Modifier.padding(top = 5.dp),
            color = if (on) BONE else Color(0xFFC9C9CC),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RoundButton(icon: RowIcon, description: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (on) Color.White.copy(alpha = 0.92f) else GLASS)
            .border(1.dp, if (on) Color.White.copy(alpha = 0.9f) else GLASS_BORDER, CircleShape)
            .androidSemantics(description),
        contentAlignment = Alignment.Center,
    ) {
        IconGlyph(icon, 20.dp, if (on) INK else Color.White.copy(alpha = 0.88f))
    }
}

@Composable
private fun LibraryRow(icon: RowIcon, title: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .height(38.dp)
            .then(if (on) Modifier.shadow(8.dp, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .clip(shape)
            .background(if (on) Color.White.copy(alpha = 0.92f) else ROW_BG)
            .border(1.dp, if (on) Color.White.copy(alpha = 0.9f) else ROW_BORDER, shape)
            .padding(start = 5.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(if (on) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) { IconGlyph(icon, 18.dp, if (on) INK else Color.White.copy(alpha = 0.88f)) }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            color = if (on) INK else BONE,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.01).em,
        )
        HomeChevron(if (on) INK else Color.White.copy(alpha = 0.55f))
    }
}

@Composable
private fun HomeChevron(color: Color) {
    Canvas(Modifier.size(width = 9.dp, height = 14.dp)) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2
        drawLine(color, Offset(inset, inset), Offset(size.width - inset, size.height / 2), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(size.width - inset, size.height / 2), Offset(inset, size.height - inset), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

private fun Modifier.androidSemantics(description: String): Modifier = this.then(
    Modifier.semantics {
        contentDescription = description
        role = Role.Button
    },
)
