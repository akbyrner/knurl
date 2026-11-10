package app.detent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The phone *is* the iPod. One black plane edge to edge: the current screen takes roughly the top
 * two thirds, the wheel sits on the same black below it. No bezel, no seam.
 */
@Composable
fun DetentApp(controller: DetentController) {
    val haptics = remember { Haptics(controller.appContext) }

    BoxWithConstraints(Modifier.fillMaxSize().background(PLANE)) {
        // The wheel is sized from the phone, so the view above it keeps about two thirds of the height.
        val wheel = minOf(maxWidth * 0.62f, maxHeight * 0.30f)

        Column(Modifier.fillMaxSize()) {
            val entry = controller.current
            val screenModifier = Modifier.weight(1f).fillMaxWidth().statusBarsPadding()
            when (val screen = entry.screen) {
                Screen.Flow -> AlbumFlow(controller.library.albums, entry.selected, screenModifier)
                Screen.NowPlaying -> NowPlayingScreen(controller.library, controller.player, screenModifier)
                else -> {
                    val model = controller.listModel(screen)
                    if (model != null) ListScreen(model, entry.selected, screenModifier) else Box(screenModifier)
                }
            }

            Box(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 12.dp, bottom = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                ClickWheel(
                    modifier = Modifier.size(wheel),
                    ringColor = WHEEL,
                    labelColor = LABEL,
                    centerColor = HUB,
                    rimColor = RIM,
                    onTick = { dir ->
                        if (controller.tick(dir)) haptics.tick()
                    },
                    onButton = { btn ->
                        if (controller.button(btn)) haptics.press()
                    },
                )
            }
        }
    }
}

private val PLANE = Color.Black
private val WHEEL = Color(0xFF232325)
private val HUB = Color(0xFF131315)
private val LABEL = Color(0xFFC4C4C4)
private val RIM = Color.White.copy(alpha = 0.10f)
