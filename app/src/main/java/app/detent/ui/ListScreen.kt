package app.detent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SELECTED = Brush.verticalGradient(listOf(Color(0xFF2A73D6), Color(0xFF1856B8)))
private val DIVIDER = Color.White.copy(alpha = 0.12f)
private val CHEVRON = Color(0xFF8A8F98)

/** A menu or list on the black plane: a title bar, then rows with a blue highlight on the selected one. */
@Composable
fun ListScreen(model: ListModel, selected: Int, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    val rowHeight = if (model.subtitleAt != null) 58.dp else 46.dp

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
        Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 48.dp), contentAlignment = Alignment.Center) {
            Text(
                model.title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (model.count == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(model.emptyText, color = Color(0xFFA0A0A8), fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(model.count) { i ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .then(if (i == selected) Modifier.background(SELECTED) else Modifier.drawBehind {
                                drawLine(DIVIDER, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                            })
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                model.titleAt(i),
                                color = Color.White,
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            model.subtitleAt?.let { sub ->
                                Text(
                                    sub(i),
                                    color = if (i == selected) Color.White else Color(0xFFA0A0A8),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        model.valueAt?.let { value ->
                            Text(
                                value(i),
                                modifier = Modifier.padding(start = 12.dp),
                                color = if (i == selected) Color.White else Color(0xFFA0A0A8),
                                fontSize = 17.sp,
                            )
                        } ?: if (model.chevron) Chevron(if (i == selected) Color.White else CHEVRON) else Unit
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
