package app.detent.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.detent.data.Library
import app.detent.data.demoLibrary
import app.detent.playback.PlayerController

sealed interface Screen {
    data object MainMenu : Screen
    data object Flow : Screen
    data object Albums : Screen
    data object Artists : Screen
    data class ArtistAlbums(val artist: String) : Screen
    data class AlbumTracks(val albumId: Long) : Screen
    data object Songs : Screen
    data object NowPlaying : Screen
}

/** One screen on the stack plus the highlighted row (or selected album, in Flow). */
data class Entry(val screen: Screen, val selected: Int = 0)

/** What a list screen shows. Rows are asked for by index so a long Songs list stays cheap. */
class ListModel(
    val title: String,
    val count: Int,
    val titleAt: (Int) -> String,
    val subtitleAt: ((Int) -> String)? = null,
    val chevron: Boolean = true,
)

/**
 * Wheel in, screen out. The wheel calls [tick] and [button]; the screens read [current] and [listModel].
 * Navigation is a stack: Select pushes, Menu pops, like the original.
 */
class DetentController(val appContext: android.content.Context, val player: PlayerController) {
    var library by mutableStateOf(demoLibrary)
        private set

    private val stack = mutableStateListOf(Entry(Screen.MainMenu, 0))

    val current: Entry get() = stack.last()

    fun loadLibrary(newLibrary: Library) {
        library = newLibrary
        // Selections can point past the end of a smaller library.
        for (i in stack.indices) stack[i] = stack[i].copy(selected = stack[i].selected.coerceIn(0, maxOf(0, rowCount(stack[i].screen) - 1)))
    }

    private fun mainMenuItems(): List<String> = buildList {
        add("Flow")
        add("Albums")
        add("Artists")
        add("Songs")
        add("Shuffle Songs")
        if (player.currentTrackId != null) add("Now Playing")
    }

    fun listModel(screen: Screen): ListModel? = when (screen) {
        Screen.MainMenu -> mainMenuItems().let { items ->
            ListModel("Detent", items.size, { items[it] }, chevron = true)
        }
        Screen.Albums -> library.albums.let { albums ->
            ListModel("Albums", albums.size, { albums[it].title }, { albums[it].artist })
        }
        Screen.Artists -> library.artists.let { artists ->
            ListModel("Artists", artists.size, { artists[it] })
        }
        is Screen.ArtistAlbums -> library.albumsBy(screen.artist).let { albums ->
            ListModel(screen.artist, albums.size, { albums[it].title })
        }
        is Screen.AlbumTracks -> library.tracksOf(screen.albumId).let { tracks ->
            ListModel(
                library.album(screen.albumId)?.title.orEmpty(),
                tracks.size,
                { tracks[it].title },
                { formatTime(tracks[it].durationMs) },
                chevron = false,
            )
        }
        Screen.Songs -> library.songs.let { songs ->
            ListModel("Songs", songs.size, { songs[it].title }, { songs[it].artist }, chevron = false)
        }
        Screen.Flow, Screen.NowPlaying -> null
    }

    private fun rowCount(screen: Screen): Int = when (screen) {
        Screen.Flow -> library.albums.size
        Screen.NowPlaying -> 0
        else -> listModel(screen)?.count ?: 0
    }

    private fun push(screen: Screen) {
        stack += Entry(screen, if (screen == Screen.Flow) library.albums.size / 2 else 0)
    }

    private fun setSelected(index: Int): Boolean {
        val entry = current
        val clamped = index.coerceIn(0, maxOf(0, rowCount(entry.screen) - 1))
        if (clamped == entry.selected) return false
        stack[stack.lastIndex] = entry.copy(selected = clamped)
        return true
    }

    /** +1 clockwise, -1 counter-clockwise. Returns true when something changed, for the haptic tick. */
    fun tick(direction: Int): Boolean = when (current.screen) {
        Screen.NowPlaying -> {
            player.stepVolume(direction)
            true
        }
        else -> setSelected(current.selected + direction)
    }

    fun button(button: WheelButton): Boolean {
        when (button) {
            WheelButton.Menu -> return back()
            WheelButton.Select -> select()
            WheelButton.PlayPause -> player.togglePlayPause()
            WheelButton.Next -> if (current.screen == Screen.Flow) setSelected(current.selected + 1) else player.next()
            WheelButton.Prev -> if (current.screen == Screen.Flow) setSelected(current.selected - 1) else player.previous()
        }
        return true
    }

    private fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    private fun select() {
        val entry = current
        val i = entry.selected
        when (val screen = entry.screen) {
            Screen.MainMenu -> when (mainMenuItems().getOrNull(i)) {
                "Flow" -> push(Screen.Flow)
                "Albums" -> push(Screen.Albums)
                "Artists" -> push(Screen.Artists)
                "Songs" -> push(Screen.Songs)
                "Shuffle Songs" -> {
                    if (library.tracks.any { it.uri != null }) {
                        player.playQueue(library.tracks.shuffled(), 0)
                        push(Screen.NowPlaying)
                    }
                }
                "Now Playing" -> push(Screen.NowPlaying)
            }
            Screen.Flow -> library.albums.getOrNull(i)?.let { push(Screen.AlbumTracks(it.id)) }
            Screen.Albums -> library.albums.getOrNull(i)?.let { push(Screen.AlbumTracks(it.id)) }
            Screen.Artists -> library.artists.getOrNull(i)?.let { push(Screen.ArtistAlbums(it)) }
            is Screen.ArtistAlbums -> library.albumsBy(screen.artist).getOrNull(i)?.let { push(Screen.AlbumTracks(it.id)) }
            is Screen.AlbumTracks -> {
                if (library.tracksOf(screen.albumId).any { it.uri != null }) {
                    player.playQueue(library.tracksOf(screen.albumId), i)
                    push(Screen.NowPlaying)
                }
            }
            Screen.Songs -> {
                if (library.songs.getOrNull(i)?.uri != null) {
                    player.playQueue(library.songs, i)
                    push(Screen.NowPlaying)
                }
            }
            Screen.NowPlaying -> player.togglePlayPause()
        }
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
