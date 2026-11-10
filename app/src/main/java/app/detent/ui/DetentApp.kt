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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                Screen.SpotifyAlbumsFlow -> {
                    val albums = controller.spotifyFlowAlbums
                    if (albums.isEmpty()) {
                        ListScreen(ListModel("Albums", 0, { "" }, emptyText = controller.spotifyAlbumsEmptyText), 0, screenModifier)
                    } else {
                        AlbumFlow(albums, entry.selected, screenModifier)
                    }
                }
                Screen.NowPlaying -> NowPlayingScreen(controller, controller.nowPlayingMode, screenModifier)
                else -> {
                    val model = controller.listModel(screen)
                    if (model != null) {
                        // Menus keep a small "now playing" card above the wheel while something plays.
                        Column(screenModifier) {
                            ListScreen(model, entry.selected, Modifier.weight(1f))
                            if (controller.hasNowPlaying) MiniPlayer(controller, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    } else {
                        Box(screenModifier)
                    }
                }
            }

            Box(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 12.dp, bottom = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                ClickWheel(
                    modifier = Modifier.size(wheel),
                    onTick = { dir ->
                        if (controller.tick(dir)) haptics.tick()
                    },
                    onButton = { btn ->
                        if (controller.button(btn)) haptics.press()
                    },
                )
            }
        }

        // A short message such as "Signed in to Spotify", floating between the screen and the wheel.
        controller.notice?.let { text ->
            Text(
                text,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Above the wheel, below the list, so it never covers a row.
                    .padding(top = maxHeight * 0.52f, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE6303034))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val PLANE = Color.Black
