package app.detent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val SELECTED = Brush.verticalGradient(listOf(Color(0xFF3A76F0), Color(0xFF2563EB)))
private val CHEVRON = Color(0xFF8A8F98)
private val CHECK = Color(0xFF5AA0FF)

/** A menu or list on the black plane: a header, then rounded rows with a blue highlight on the selected one. */
@Composable
fun ListScreen(model: ListModel, selected: Int, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    val rowHeight = (model.rowHeightDp ?: if (model.subtitleAt != null && model.numberAt == null) 56 else 42).dp
    val corner = if (rowHeight >= 60.dp) 14.dp else 12.dp

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

    Column(modifier.background(Color.Black)) {
        val header = model.header
        if (header != null) {
            Box(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Row(Modifier.padding(start = 20.dp, end = 56.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (header.art != null) Art(header.art, Modifier.size(64.dp), corner = 8.dp, letterSize = 26.sp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(
                            header.title,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.02).em,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(header.subtitle, color = TEXT_SECONDARY, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                BatteryIndicator(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 13.dp))
            }
        } else {
            ScreenHeader(model.title, Modifier.padding(bottom = 4.dp))
        }
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
            LazyColumn(Modifier.fillMaxSize(), state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(model.count) { i ->
                    val isSelected = i == selected
                    val dim = model.dimAt?.invoke(i) == true
                    val shape = RoundedCornerShape(corner)
                    Row(
                        Modifier
                            .padding(horizontal = 10.dp)
                            .fillMaxWidth()
                            .height(rowHeight)
                            .clip(shape)
                            .background(if (isSelected) SELECTED else Brush.verticalGradient(listOf(ROW_BG, ROW_BG)))
                            .border(1.dp, if (isSelected) Color.Transparent else ROW_BORDER, shape)
                            .drawWithContent {
                                drawContent()
                                // The light top edge of the selected row.
                                if (isSelected) drawRect(Color.White.copy(alpha = 0.18f), Offset.Zero, Size(size.width, 1.dp.toPx()))
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        model.numberAt?.let { number ->
                            Text(
                                number(i),
                                modifier = Modifier.width(22.dp),
                                textAlign = TextAlign.End,
                                color = if (isSelected) Color.White else Color(0xFF9A9AA0),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Box(Modifier.width(14.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                model.titleAt(i),
                                color = if (dim && !isSelected) Color(0xFF8B8B92) else Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = (-0.01).em,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (model.numberAt == null) model.subtitleAt?.let { sub ->
                                Text(
                                    sub(i),
                                    color = if (isSelected) Color.White else TEXT_SECONDARY,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        val value = model.valueAt
                        when {
                            value != null -> Text(
                                value(i),
                                modifier = Modifier.padding(start = 12.dp),
                                color = if (isSelected) Color.White else TEXT_SECONDARY,
                                fontSize = 17.sp,
                            )
                            model.checkAt?.invoke(i) == true -> Check(CHECK.takeIf { !isSelected } ?: Color.White)
                            model.chevron && !dim -> Chevron(if (isSelected) Color.White else CHEVRON)
                        }
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
