package app.detent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.detent.data.Album

// The selected row is bright white glass with dark text.
private val SELECTED = Color.White.copy(alpha = 0.92f)
private val CHEVRON = Color.White.copy(alpha = 0.55f)
private val SUB_GRAY = Color(0xFF9A9AA0)

/**
 * A menu or list on the graphite plane: a header, then frosted rows with a white one for the selected row.
 * [top] is an optional block between the header and the rows (Up Next puts what is playing there).
 */
@Composable
fun ListScreen(model: ListModel, selected: Int, modifier: Modifier = Modifier, top: (@Composable () -> Unit)? = null) {
    val state = rememberLazyListState()
    val rowHeight = (model.rowHeightDp ?: if (model.subtitleAt != null && model.numberAt == null) 56 else 40).dp
    val corner = if (rowHeight >= 56.dp) 16.dp else 14.dp

    // Keep the highlighted row on screen: scroll only when it moves past either edge.
    LaunchedEffect(selected, model.count) {
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) {
            state.scrollToItem(selected)
            return@LaunchedEffect
        }
        val first = visible.first().index
        val last = visible.last().index
        when {
            selected < first -> state.animateScrollToItem(selected)
            selected > last -> state.animateScrollToItem(maxOf(0, selected - (last - first)))
        }
    }

    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            val header = model.header
            if (header != null) {
                Box(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    Row(Modifier.padding(start = 20.dp, end = 56.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (header.art != null) Art(header.art, Modifier.size(72.dp).shadow(10.dp, RoundedCornerShape(10.dp)), corner = 10.dp, letterSize = 28.sp)
                        Column(Modifier.padding(start = 14.dp)) {
                            MonoLabel(header.kind)
                            Text(
                                header.title.uppercase(),
                                color = BONE,
                                fontFamily = ArchivoExpanded,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.05.em,
                                lineHeight = 26.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(header.subtitle, color = Color(0xFFC9C9CC), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    BatteryIndicator(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 13.dp))
                }
            } else {
                ScreenHeader(model.title, Modifier.padding(bottom = 12.dp), label = model.label)
            }
            top?.invoke()
            if (model.count == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        model.emptyText,
                        color = Color(0xFFA0A0A8),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth(), state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(model.count) { i ->
                        ListRow(model, i, i == selected, rowHeight, corner)
                    }
                }
            }
        }
        if (model.rail && model.count > 0) {
            val letter = model.titleAt(selected.coerceIn(0, model.count - 1)).trimStart().firstOrNull()?.uppercaseChar()
            Column(Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 8.dp).width(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                for (c in 'A'..'Z') {
                    Text(
                        c.toString(),
                        color = if (c == letter) ORANGE else Color(0xFF6F7177),
                        fontFamily = PlexMono,
                        fontSize = 10.sp,
                        fontWeight = if (c == letter) FontWeight.SemiBold else FontWeight.Medium,
                        lineHeight = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListRow(model: ListModel, i: Int, isSelected: Boolean, rowHeight: androidx.compose.ui.unit.Dp, corner: androidx.compose.ui.unit.Dp) {
    val dim = model.dimAt?.invoke(i) == true
    val shape = RoundedCornerShape(corner)
    val thumb = model.thumbAt?.invoke(i)
    val icon = model.iconAt?.invoke(i)
    val chip = model.chipAt?.invoke(i)
    val rightInset = if (model.rail) 30.dp else 12.dp
    Row(
        Modifier
            .padding(start = 12.dp, end = rightInset)
            .fillMaxWidth()
            .height(rowHeight)
            .then(if (isSelected) Modifier.shadow(8.dp, shape, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .clip(shape)
            .background(if (isSelected) SELECTED else ROW_BG)
            .border(1.dp, if (isSelected) SELECTED else ROW_BORDER, shape)
            .drawWithContent {
                drawContent()
                // The faint light top edge of a glass row.
                if (!isSelected) drawRect(GLASS_EDGE, Offset.Zero, Size(size.width, 1.dp.toPx()))
            }
            .padding(start = if (thumb != null || icon != null) 6.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        model.numberAt?.let { number ->
            Text(
                number(i).padStart(2, '0'),
                modifier = Modifier.width(22.dp),
                textAlign = TextAlign.End,
                color = if (isSelected) Color(0xFF55555C) else LABEL_GRAY,
                fontFamily = PlexMono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Box(Modifier.width(14.dp))
        }
        if (thumb != null) {
            val size = if (model.roundThumbs) 36.dp else if (rowHeight >= 56.dp) 44.dp else 32.dp
            Art(
                thumb,
                Modifier.size(size),
                corner = if (model.roundThumbs) 18.dp else if (rowHeight >= 56.dp) 8.dp else 6.dp,
                letterSize = (size.value * 0.4f).sp,
            )
            Box(Modifier.width(12.dp))
        }
        if (icon != null) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (isSelected) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                IconGlyph(icon, 20.dp, if (isSelected) INK else if (dim) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.88f))
            }
            Box(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            val hasThumb = thumb != null
            Text(
                model.titleAt(i),
                color = when { isSelected -> INK; dim -> Color(0xFF8B8B92); else -> Color(0xFFECEBE6) },
                fontSize = when { model.roundThumbs -> 15.sp; hasThumb && rowHeight >= 56.dp -> 15.sp; hasThumb -> 14.sp; model.numberAt != null -> 15.sp; icon != null -> 16.sp; else -> 16.sp },
                fontWeight = if (hasThumb || icon != null) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = (-0.01).em,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (model.numberAt == null) model.subtitleAt?.let { sub ->
                if (model.monoSubtitle) {
                    Text(
                        sub(i).uppercase(),
                        color = if (isSelected) Color(0xFF55555C) else SUB_GRAY,
                        fontFamily = PlexMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.06.em,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        sub(i),
                        color = if (isSelected) Color(0xFF55555C) else SUB_GRAY,
                        fontSize = if (hasThumb) 12.sp else 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (model.playingAt?.invoke(i) == true) PlayingBars()
        if (chip != null) Chip(chip, isSelected)
        if (model.grip) Grip(isSelected)
        val value = model.valueAt
        when {
            value != null -> Text(
                value(i),
                modifier = Modifier.padding(start = 12.dp),
                color = if (isSelected) Color(0xFF55555C) else Color(0xFFA8A9AF),
                fontFamily = PlexMono,
                fontSize = 11.sp,
                letterSpacing = 0.12.em,
            )
            model.checkAt?.invoke(i) == true -> Check(CHECK_BLUE.takeIf { !isSelected } ?: INK)
            model.chevron && !dim -> Chevron(if (isSelected) INK else CHEVRON)
        }
    }
}

private val CHECK_BLUE = Color(0xFF5AA0FF)

/** Three bars, orange: this is the song that is playing. */
@Composable
private fun PlayingBars() {
    Canvas(Modifier.padding(start = 10.dp).size(14.dp)) {
        val u = size.width / 14f
        drawRoundRect(ORANGE, Offset(1 * u, 5 * u), Size(2.5f * u, 8 * u), CornerRadius(u))
        drawRoundRect(ORANGE, Offset(5.75f * u, 1 * u), Size(2.5f * u, 12 * u), CornerRadius(u))
        drawRoundRect(ORANGE, Offset(10.5f * u, 7 * u), Size(2.5f * u, 6 * u), CornerRadius(u))
    }
}

/** Two-line drag handle drawn as four short stripes. */
@Composable
private fun Grip(isSelected: Boolean) {
    Canvas(Modifier.padding(start = 10.dp).size(14.dp, 10.dp)) {
        val c = if (isSelected) Color.Black.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.4f)
        for (k in 0 until 3) drawRect(c, Offset(0f, k * 4.dp.toPx()), Size(size.width, 2.dp.toPx()))
    }
}

/** A small engraved tag at the right of a row: ON, SIGN IN, LATER. */
@Composable
private fun Chip(text: String, isSelected: Boolean) {
    val (color, border) = when {
        isSelected -> INK to Color.Black.copy(alpha = 0.25f)
        text == "ON" -> Color(0xFFA8D0FF) to Color(0xFF6FB1FF).copy(alpha = 0.5f)
        text == "LATER" -> Color(0xFF6F7177) to Color.White.copy(alpha = 0.10f)
        else -> Color(0xFFC9C9CC) to Color.White.copy(alpha = 0.18f)
    }
    Text(
        text,
        modifier = Modifier.padding(start = 10.dp).border(1.dp, border, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        color = color,
        fontFamily = PlexMono,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.14.em,
    )
}

/** Albums as a grid of covers, three across. The wheel steps through them in reading order. */
@Composable
fun AlbumGridScreen(title: String, label: String, albums: List<Album>, selected: Int, modifier: Modifier = Modifier) {
    val state = rememberLazyGridState()
    LaunchedEffect(selected, albums.size) {
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) {
            state.scrollToItem(selected)
            return@LaunchedEffect
        }
        val first = visible.first().index
        val last = visible.last().index
        when {
            selected < first -> state.animateScrollToItem((selected / 3) * 3)
            selected > last -> state.animateScrollToItem(maxOf(0, ((selected / 3) * 3) - (last - first + 1) + 3))
        }
    }
    Column(modifier) {
        ScreenHeader(title, Modifier.padding(bottom = 8.dp), label = label)
        if (albums.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No albums yet", color = Color(0xFFA0A0A8), fontSize = 15.sp)
            }
        } else {
            LazyVerticalGrid(
                GridCells.Fixed(3),
                Modifier.fillMaxSize(),
                state = state,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(albums.size) { i ->
                    val album = albums[i]
                    val on = i == selected
                    val shape = RoundedCornerShape(9.dp)
                    Column {
                        Art(
                            album,
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .shadow(if (on) 14.dp else 8.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                                .then(if (on) Modifier.border(3.dp, BONE, shape) else Modifier),
                            corner = 9.dp,
                            letterSize = 30.sp,
                        )
                        Text(
                            album.title,
                            modifier = Modifier.padding(top = 6.dp),
                            color = BONE,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(album.artist, color = SUB_GRAY, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chevron(color: Color) {
    Canvas(Modifier.padding(start = 12.dp).size(width = 9.dp, height = 14.dp)) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2
        drawLine(color, Offset(inset, inset), Offset(size.width - inset, size.height / 2), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width - inset, size.height / 2), Offset(inset, size.height - inset), stroke, StrokeCap.Round)
    }
}

@Composable
private fun Check(color: Color) {
    Canvas(Modifier.padding(start = 12.dp).size(width = 18.dp, height = 14.dp)) {
        val u = size.width / 18f
        val stroke = 2.4f * u
        drawLine(color, Offset(1.5f * u, 7.5f * u), Offset(6.5f * u, 12.5f * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(6.5f * u, 12.5f * u), Offset(16.5f * u, 1.5f * u), stroke, StrokeCap.Round)
    }
}

