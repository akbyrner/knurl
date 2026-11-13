package app.knurl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.CompositionLocalProvider
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
 * The phone *is* the iPod. One graphite plane edge to edge: the current screen takes roughly the top
 * two thirds, the wheel sits on the same plane below it. No bezel, no seam.
 */
@Composable
fun KnurlApp(controller: KnurlController) {
    val haptics = remember { Haptics(controller.appContext) }

    val view = androidx.compose.ui.platform.LocalView.current
    // One place for the feel of a press, so Settings can switch the sound and the buzz off.
    val tickFeel = {
        if (controller.hapticTicks) haptics.tick()
        if (controller.clickSound) view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
    }
    val pressFeel = {
        if (controller.hapticTicks) haptics.press()
        if (controller.clickSound) view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
    }

    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = InterFamily)) {
    BoxWithConstraints(Modifier.fillMaxSize().background(GRAPHITE)) {
        GraphitePlane(
            art = controller.nowPlayingInfo(System.currentTimeMillis())?.art,
            tall = controller.current.screen == Screen.NowPlaying,
            modifier = Modifier.fillMaxSize(),
            wash = controller.theme == ThemeChoice.GraphiteGlass,
        )
        // The wheel is sized from the phone, so the view above it keeps about two thirds of the height.
        val wheel = minOf(maxWidth * 0.62f, maxHeight * 0.30f)

        Column(Modifier.fillMaxSize()) {
            val entry = controller.current
            // Up Next follows the song: fetch again when it changes while that screen is open.
            if (entry.screen == Screen.Queue) androidx.compose.runtime.LaunchedEffect(controller.currentTrackKey) { controller.loadQueue() }
            // The status bar is hidden, so only the camera cut-out needs room at the top.
            val screenModifier = Modifier.weight(1f).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))
            val screen = entry.screen
            val grid = controller.gridAlbums(screen)
            when {
                screen == Screen.NowPlaying -> NowPlayingScreen(controller, controller.nowPlayingMode, screenModifier)
                screen == Screen.MainMenu -> {
                    // The home screen keeps a small "now playing" card above the wheel while something plays.
                    // The card sits centred in the space between the last row and the controls.
                    MenuWithMiniPlayer(
                        list = { HomeScreen(controller, entry.selected, onTap = pressFeel) },
                        mini = {
                            MiniPlayer(
                                controller,
                                Modifier.padding(horizontal = 16.dp),
                                selected = controller.menuEntries().getOrNull(entry.selected) == MenuEntry.NowPlaying,
                                onClick = { if (controller.toggleNowPlaying()) pressFeel() },
                            )
                        },
                        modifier = screenModifier,
                    )
                }
                screen == Screen.Settings -> SettingsScreen(controller, entry.selected, screenModifier, onTap = pressFeel)
                grid != null -> AlbumGridScreen(controller.gridTitle(screen), controller.gridLabel(screen), grid, entry.selected, screenModifier)
                controller.isFlowScreen(screen) -> {
                    val tiles = controller.flowTiles(screen)
                    if (tiles.isEmpty()) {
                        ListScreen(ListModel(controller.flowTitle(screen), 0, { "" }, emptyText = controller.flowEmptyText(screen)), 0, screenModifier)
                    } else {
                        // Keyed by screen so each carousel starts at its own selection instead of sweeping from the last one.
                        androidx.compose.runtime.key(screen) { AlbumFlow(tiles, entry.selected, screenModifier, label = controller.flowLabel(screen), dots = screen == Screen.SpotifyHome) }
                    }
                }
                else -> {
                    val model = controller.listModel(screen)
                    if (model == null) {
                        Box(screenModifier)
                    } else if (screen == Screen.Queue) {
                        // What is playing now sits above the rows that follow it.
                        ListScreen(model, entry.selected, screenModifier, top = {
                            MiniPlayer(controller, Modifier.padding(horizontal = 12.dp))
                            MonoLabel("Up next", Modifier.padding(start = 20.dp, top = 14.dp, bottom = 8.dp))
                        })
                    } else {
                        ListScreen(model, entry.selected, screenModifier)
                    }
                }
            }

            Box(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 12.dp, bottom = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                ClickWheel(
                    modifier = Modifier.size(wheel),
                    tickDegrees = controller.tickDegrees,
                    onTick = { dir ->
                        if (controller.tick(dir)) tickFeel()
                    },
                    onButton = { btn ->
                        if (controller.button(btn)) pressFeel()
                    },
                )
                // Shuffle and repeat sit at the wheel's top corners, on the same black plane.
                val modeSize = wheel * 0.218f
                ModeButton(
                    icon = ModeIcon.Shuffle,
                    lit = controller.shuffle,
                    description = if (controller.shuffle) "Shuffle on" else "Shuffle off",
                    size = modeSize,
                    onClick = {
                        controller.toggleShuffle()
                        pressFeel()
                    },
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 18.dp).offset(y = (-12).dp),
                )
                ModeButton(
                    icon = ModeIcon.Repeat,
                    lit = controller.repeat != RepeatSetting.Off,
                    description = when (controller.repeat) {
                        RepeatSetting.Off -> "Repeat off"
                        RepeatSetting.All -> "Repeat all"
                        RepeatSetting.One -> "Repeat song"
                    },
                    size = modeSize,
                    badge = if (controller.repeat == RepeatSetting.One) "1" else null,
                    onClick = {
                        controller.cycleRepeat()
                        pressFeel()
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 18.dp).offset(y = (-12).dp),
                )
                // Like at the bottom-left corner (lit when the song is liked), Up Next at the bottom-right.
                ModeButton(
                    icon = ModeIcon.Heart,
                    lit = controller.currentLiked,
                    description = if (controller.currentLiked) "Liked" else "Like",
                    size = modeSize,
                    onClick = {
                        if (controller.toggleLike()) pressFeel()
                    },
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp),
                )
                ModeButton(
                    icon = ModeIcon.Queue,
                    lit = entry.screen == Screen.Queue,
                    description = "Up Next",
                    size = modeSize,
                    onClick = {
                        if (controller.toggleQueue()) pressFeel()
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp),
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

        SplashOverlay()
    }
    }
}

/** A list with the now-playing card centred in whatever room the list leaves above the controls. */
@Composable
private fun MenuWithMiniPlayer(list: @Composable () -> Unit, mini: @Composable () -> Unit, modifier: Modifier) {
    Layout(
        content = {
            Box { list() }
            Box { mini() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val margin = 8.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val card = measurables[1].measure(loose)
        val list = measurables[0].measure(loose.copy(maxHeight = (constraints.maxHeight - card.height - 2 * margin).coerceAtLeast(0)))
        val cardY = list.height + (constraints.maxHeight - list.height - card.height) / 2
        layout(constraints.maxWidth, constraints.maxHeight) {
            list.place(0, 0)
            card.place(0, cardY)
        }
    }
}
