package app.detent.ui

import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.detent.data.Album
import app.detent.data.Library
import app.detent.data.demoLibrary
import app.detent.playback.PlayerController
import app.detent.spotify.LibrespotEngine
import app.detent.spotify.SpotifyAlbum
import app.detent.spotify.SpotifyApi
import app.detent.spotify.SpotifyAuth
import app.detent.spotify.SpotifyException
import app.detent.spotify.SpotifyPlaylist
import app.detent.spotify.SpotifyTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Screen {
    data object MainMenu : Screen
    data object Flow : Screen
    data object SpotifyHome : Screen
    data object SpotifyPlaylists : Screen
    data class SpotifyPlaylistTracks(val id: String, val name: String) : Screen
    data object SpotifyLiked : Screen
    /** Saved Spotify albums, shown as the same Flow carousel as music on the phone. */
    data object SpotifyAlbumsFlow : Screen
    data class SpotifyAlbumTracks(val id: String, val name: String) : Screen
    data object Albums : Screen
    data object Artists : Screen
    data class ArtistAlbums(val artist: String) : Screen
    data class AlbumTracks(val albumId: Long) : Screen
    data object Songs : Screen
    data object NowPlaying : Screen
    data object Settings : Screen
}

/** Everything Now Playing needs, whether the song comes from the phone or from Spotify. */
class NowPlayingInfo(
    val title: String,
    val artist: String,
    val albumTitle: String,
    val art: Album?,
    val durationMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
)

/** What the wheel does on Now Playing. Select switches between the two. */
enum class NowPlayingMode { Volume, Scrub }

enum class RepeatSetting(val label: String, val playerMode: Int) {
    Off("Off", androidx.media3.common.Player.REPEAT_MODE_OFF),
    All("All", androidx.media3.common.Player.REPEAT_MODE_ALL),
    One("One", androidx.media3.common.Player.REPEAT_MODE_ONE),
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
    /** Text shown on the right of a row, such as a setting's current value. Replaces the chevron. */
    val valueAt: ((Int) -> String)? = null,
    /** Shown instead of rows when [count] is 0, for example while Spotify is still loading. */
    val emptyText: String = "Nothing here yet",
)

/** A Spotify list on its way: still loading, failed with a short reason, or ready. */
class SpotifyList<T>(val items: List<T> = emptyList(), val loading: Boolean = false, val error: String? = null) {
    val emptyText: String get() = if (loading) "Loading…" else error ?: "Nothing here yet"
}

/**
 * Wheel in, screen out. The wheel calls [tick] and [button]; the screens read [current] and [listModel].
 * Navigation is a stack: Select pushes, Menu pops, like the original.
 */
class DetentController(val appContext: android.content.Context, val player: PlayerController) {
    var library by mutableStateOf(demoLibrary)
        private set

    private val prefs = appContext.getSharedPreferences("detent", android.content.Context.MODE_PRIVATE)

    var shuffle by mutableStateOf(prefs.getBoolean("shuffle", false))
        private set

    var repeat by mutableStateOf(
        RepeatSetting.entries.getOrElse(prefs.getInt("repeat", 0)) { RepeatSetting.Off },
    )
        private set

    var nowPlayingMode by mutableStateOf(NowPlayingMode.Volume)
        private set

    init {
        player.setModes(shuffle, repeat.playerMode)
    }

    // --- Spotify: sign-in and library browsing ---------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val spotifyAuth = SpotifyAuth(appContext)
    private val spotifyApi = SpotifyApi(spotifyAuth)
    private val engine = LibrespotEngine(appContext)

    // --- Now playing, from either the phone's own player or Spotify -------------------------------

    private enum class Source { None, Local, Spotify }

    private var source = Source.None

    /** What Spotify is playing, as last reported. The position moves on its own between reports. */
    private var spotifyNow by mutableStateOf<SpotifyNow?>(null)

    private class SpotifyNow(val track: SpotifyTrack, val isPlaying: Boolean, val positionMs: Long, val atMs: Long) {
        fun positionAt(nowMs: Long): Long =
            if (isPlaying) (positionMs + (nowMs - atMs)).coerceIn(0L, track.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE) else positionMs
    }

    private var pollJob: Job? = null
    private var seekJob: Job? = null

    val hasNowPlaying: Boolean get() = player.currentTrackId != null || spotifyNow != null

    /** What Now Playing draws. [nowMs] is the current time in milliseconds, so the bar keeps moving. */
    fun nowPlayingInfo(nowMs: Long): NowPlayingInfo? {
        if (source == Source.Spotify) {
            val s = spotifyNow ?: return null
            val t = s.track
            return NowPlayingInfo(
                title = t.title,
                artist = t.artist,
                albumTitle = t.albumName,
                art = Album(
                    id = (t.imageUrl ?: t.albumName).hashCode().toLong() or SPOTIFY_ID_BIT,
                    title = t.albumName,
                    artist = t.artist,
                    artUrl = t.imageUrl,
                ),
                durationMs = t.durationMs,
                positionMs = s.positionAt(nowMs),
                isPlaying = s.isPlaying,
            )
        }
        val track = player.currentTrackId?.let { library.track(it) } ?: return null
        return NowPlayingInfo(
            title = track.title,
            artist = track.artist,
            albumTitle = track.albumTitle,
            art = library.album(track.albumId),
            durationMs = if (player.durationMs > 0) player.durationMs else track.durationMs,
            positionMs = player.positionMs,
            isPlaying = player.isPlaying,
        )
    }

    /** Debug builds only: start the Spotify player and log how far it gets. Used from adb during development. */
    fun debugStartPlayer() {
        scope.launch {
            engine.start()
            repeat(6) {
                delay(1_000)
                android.util.Log.d("detent", "player status=${engine.status} detail=${engine.detail}")
            }
            engine.stop()
        }
    }

    private fun playLocal(tracks: List<app.detent.data.Track>, startIndex: Int) {
        if (source == Source.Spotify) spotifyCommand { spotifyApi.pause() }
        stopPolling()
        source = Source.Local
        player.playQueue(tracks, startIndex)
    }

    /**
     * Starts Spotify playback on this phone: brings up the player (signing in the first time), waits for the
     * Detent device to show up in the account, then asks Spotify to play there.
     */
    private fun playSpotify(contextUri: String?, uris: List<String>?, offsetUri: String?) {
        scope.launch {
            if (!engine.available) {
                showNotice("This build has no Spotify player")
                return@launch
            }
            player.pause()
            val coldStart = engine.status != LibrespotEngine.Status.Authenticated
            if (coldStart) showNotice(if (engine.status == LibrespotEngine.Status.Stopped) "Starting Spotify player…" else "Waiting for Spotify sign-in…")
            if (!engine.ensureAuthenticated()) {
                showNotice(engine.detail ?: "Spotify sign-in didn't finish")
                return@launch
            }
            val device = findDetentDevice()
            if (device == null) {
                showNotice("Detent didn't appear in your Spotify devices")
                return@launch
            }
            try {
                spotifyApi.play(device.id, contextUri, uris, offsetUri)
            } catch (e: SpotifyException) {
                showNotice(e.message ?: "Spotify couldn't play that")
                return@launch
            }
            source = Source.Spotify
            refreshSpotify(afterDelayMs = 700)
            startPolling()
            if (current.screen != Screen.NowPlaying) push(Screen.NowPlaying)
        }
    }

    private suspend fun findDetentDevice(): app.detent.spotify.SpotifyDevice? {
        repeat(20) {
            val found = try {
                spotifyApi.devices().firstOrNull { it.name == LibrespotEngine.DEVICE_NAME }
            } catch (e: Exception) {
                null
            }
            if (found != null) return found
            delay(1_000)
        }
        return null
    }

    private suspend fun refreshSpotify(afterDelayMs: Long = 0) {
        if (afterDelayMs > 0) delay(afterDelayMs)
        try {
            val p = spotifyApi.playback() ?: return
            spotifyNow = SpotifyNow(p.track, p.isPlaying, p.progressMs, System.currentTimeMillis())
        } catch (e: Exception) {
            // A missed update is fine; the next one catches up.
        }
    }

    /** Spotify has no push channel here, so ask now and then. The bar moves smoothly in between. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(4_000)
                refreshSpotify()
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Runs a playback command, showing the new state at once and a short message if Spotify refuses. */
    private fun spotifyCommand(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: SpotifyException) {
                showNotice(e.message ?: "Spotify error")
            }
        }
    }

    private fun togglePlayPause() {
        if (source != Source.Spotify) {
            player.togglePlayPause()
            return
        }
        val s = spotifyNow ?: return
        val now = System.currentTimeMillis()
        spotifyNow = SpotifyNow(s.track, !s.isPlaying, s.positionAt(now), now)
        spotifyCommand { if (s.isPlaying) spotifyApi.pause() else spotifyApi.resume() }
    }

    private fun skip(forward: Boolean) {
        if (source != Source.Spotify) {
            if (forward) player.next() else skip(forward = false)
            return
        }
        spotifyCommand {
            if (forward) spotifyApi.next() else spotifyApi.previous()
            refreshSpotify(afterDelayMs = 600)
        }
    }

    private fun seekBy(deltaMs: Long) {
        if (source != Source.Spotify) {
            player.seekBy(deltaMs)
            return
        }
        val s = spotifyNow ?: return
        val now = System.currentTimeMillis()
        val target = (s.positionAt(now) + deltaMs).coerceIn(0L, maxOf(0L, s.track.durationMs - 1))
        spotifyNow = SpotifyNow(s.track, s.isPlaying, target, now)
        // Many wheel ticks arrive in a burst; only the last position is sent to Spotify.
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(400)
            try {
                spotifyApi.seek(target)
            } catch (e: SpotifyException) {
                showNotice(e.message ?: "Spotify error")
            }
        }
    }

    var spotifySignedIn by mutableStateOf(spotifyAuth.signedIn)
        private set

    /** A short message drawn over the screen for a moment, or null. */
    var notice by mutableStateOf<String?>(null)
        private set

    private val playlists = mutableStateOf(SpotifyList<SpotifyPlaylist>())
    private val liked = mutableStateOf(SpotifyList<SpotifyTrack>())
    private val spotifyAlbums = mutableStateOf(SpotifyList<SpotifyAlbum>())
    private val playlistTracks = mutableStateMapOf<String, SpotifyList<SpotifyTrack>>()
    private val albumTracks = mutableStateMapOf<String, SpotifyList<SpotifyTrack>>()

    /** Saved Spotify albums in the shape Flow draws. Index matches [spotifyAlbums]. */
    val spotifyFlowAlbums: List<Album>
        get() = spotifyAlbums.value.items.map {
            Album(id = it.id.hashCode().toLong() or SPOTIFY_ID_BIT, title = it.name, artist = it.artist, artUrl = it.imageUrl)
        }

    val spotifyAlbumsEmptyText: String get() = spotifyAlbums.value.emptyText

    fun showNotice(text: String) {
        notice = text
        scope.launch {
            delay(3_000)
            if (notice == text) notice = null
        }
    }

    /** Called when Spotify sends the browser back to the app after login. */
    fun onAuthRedirect(uri: Uri) {
        scope.launch {
            val problem = spotifyAuth.completeLogin(uri)
            spotifySignedIn = spotifyAuth.signedIn
            showNotice(if (problem == null) "Signed in to Spotify" else "Spotify sign-in failed: $problem")
        }
    }

    private fun <T> load(state: MutableState<SpotifyList<T>>, fetch: suspend () -> List<T>) {
        if (state.value.loading) return
        state.value = SpotifyList(state.value.items, loading = true)
        scope.launch { state.value = fetchOrError(fetch) }
    }

    private fun <T> loadKeyed(map: MutableMap<String, SpotifyList<T>>, key: String, fetch: suspend () -> List<T>) {
        if (map[key]?.loading == true) return
        map[key] = SpotifyList(map[key]?.items.orEmpty(), loading = true)
        scope.launch { map[key] = fetchOrError(fetch) }
    }

    private suspend fun <T> fetchOrError(fetch: suspend () -> List<T>): SpotifyList<T> =
        try {
            SpotifyList(fetch())
        } catch (e: SpotifyException) {
            SpotifyList(error = e.message ?: "Spotify error")
        } catch (e: Exception) {
            SpotifyList(error = "Couldn't reach Spotify")
        }

    private fun spotifyHomeItems(): List<String> = when {
        !spotifyAuth.configured -> listOf("Spotify isn't set up")
        !spotifySignedIn -> listOf("Sign in with Spotify")
        else -> listOf("Playlists", "Liked Songs", "Albums", "Sign out")
    }

    private fun isFlow(screen: Screen) = screen == Screen.Flow || screen == Screen.SpotifyAlbumsFlow

    private fun spotifyTrackModel(title: String, list: SpotifyList<SpotifyTrack>) = list.items.let { tracks ->
        ListModel(title, tracks.size, { tracks[it].title }, { tracks[it].artist }, chevron = false, emptyText = list.emptyText)
    }

    private fun applyModes() {
        prefs.edit().putBoolean("shuffle", shuffle).putInt("repeat", repeat.ordinal).apply()
        player.setModes(shuffle, repeat.playerMode)
    }

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
        add("Spotify")
        add("Settings")
        if (hasNowPlaying) add("Now Playing")
    }

    private val settingsItems = listOf("Shuffle", "Repeat")

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
        Screen.Settings -> ListModel(
            "Settings",
            settingsItems.size,
            { settingsItems[it] },
            valueAt = {
                when (it) {
                    0 -> if (shuffle) "On" else "Off"
                    else -> repeat.label
                }
            },
        )
        Screen.SpotifyHome -> spotifyHomeItems().let { items ->
            ListModel("Spotify", items.size, { items[it] }, chevron = items.size > 1)
        }
        Screen.SpotifyPlaylists -> playlists.value.let { list ->
            ListModel(
                "Playlists",
                list.items.size,
                { list.items[it].name },
                { "${list.items[it].trackCount} songs" },
                emptyText = list.emptyText,
            )
        }
        is Screen.SpotifyPlaylistTracks ->
            spotifyTrackModel(screen.name, playlistTracks[screen.id] ?: SpotifyList(loading = true))
        Screen.SpotifyLiked -> spotifyTrackModel("Liked Songs", liked.value)
        is Screen.SpotifyAlbumTracks ->
            spotifyTrackModel(screen.name, albumTracks[screen.id] ?: SpotifyList(loading = true))
        Screen.Flow, Screen.SpotifyAlbumsFlow, Screen.NowPlaying -> null
    }

    private fun rowCount(screen: Screen): Int = when (screen) {
        Screen.Flow -> library.albums.size
        Screen.SpotifyAlbumsFlow -> spotifyAlbums.value.items.size
        Screen.NowPlaying -> 0
        else -> listModel(screen)?.count ?: 0
    }

    private fun push(screen: Screen) {
        if (screen == Screen.NowPlaying) nowPlayingMode = NowPlayingMode.Volume
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
            if (nowPlayingMode == NowPlayingMode.Scrub) {
                // About 60 ticks cover a whole song, between 1 and 10 seconds each.
                val duration = nowPlayingInfo(System.currentTimeMillis())?.durationMs ?: 0L
                seekBy(direction * (duration / 60).coerceIn(1_000L, 10_000L))
            } else {
                player.stepVolume(direction)
            }
            true
        }
        else -> setSelected(current.selected + direction)
    }

    fun button(button: WheelButton): Boolean {
        when (button) {
            WheelButton.Menu -> return back()
            WheelButton.Select -> select()
            WheelButton.PlayPause -> togglePlayPause()
            WheelButton.Next -> if (isFlow(current.screen)) setSelected(current.selected + 1) else skip(forward = true)
            WheelButton.Prev -> if (isFlow(current.screen)) setSelected(current.selected - 1) else player.previous()
        }
        return true
    }

    private fun back(): Boolean {
        // Menu leaves scrubbing first, like the original, and only then goes back a screen.
        if (current.screen == Screen.NowPlaying && nowPlayingMode == NowPlayingMode.Scrub) {
            nowPlayingMode = NowPlayingMode.Volume
            return true
        }
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
                        playLocal(library.tracks.shuffled(), 0)
                        push(Screen.NowPlaying)
                    }
                }
                "Spotify" -> push(Screen.SpotifyHome)
                "Settings" -> push(Screen.Settings)
                "Now Playing" -> push(Screen.NowPlaying)
            }
            Screen.SpotifyHome -> when (spotifyHomeItems().getOrNull(i)) {
                "Sign in with Spotify" -> spotifyAuth.startLogin()
                "Playlists" -> {
                    load(playlists) { spotifyApi.playlists() }
                    push(Screen.SpotifyPlaylists)
                }
                "Liked Songs" -> {
                    load(liked) { spotifyApi.likedSongs() }
                    push(Screen.SpotifyLiked)
                }
                "Albums" -> {
                    load(spotifyAlbums) { spotifyApi.savedAlbums() }
                    push(Screen.SpotifyAlbumsFlow)
                }
                "Sign out" -> {
                    spotifyAuth.signOut()
                    engine.forgetLogin()
                    stopPolling()
                    spotifyNow = null
                    if (source == Source.Spotify) source = Source.None
                    spotifySignedIn = false
                    playlists.value = SpotifyList()
                    liked.value = SpotifyList()
                    spotifyAlbums.value = SpotifyList()
                    playlistTracks.clear()
                    albumTracks.clear()
                    showNotice("Signed out of Spotify")
                }
            }
            Screen.SpotifyPlaylists -> playlists.value.items.getOrNull(i)?.let { p ->
                loadKeyed(playlistTracks, p.id) { spotifyApi.playlistTracks(p.id) }
                push(Screen.SpotifyPlaylistTracks(p.id, p.name))
            }
            Screen.SpotifyAlbumsFlow -> spotifyAlbums.value.items.getOrNull(i)?.let { a ->
                loadKeyed(albumTracks, a.id) { spotifyApi.albumTracks(a) }
                push(Screen.SpotifyAlbumTracks(a.id, a.name))
            }
            is Screen.SpotifyPlaylistTracks -> playlistTracks[screen.id]?.items?.getOrNull(i)?.let {
                playSpotify(contextUri = "spotify:playlist:${screen.id}", uris = null, offsetUri = it.uri)
            }
            is Screen.SpotifyAlbumTracks -> albumTracks[screen.id]?.items?.getOrNull(i)?.let {
                playSpotify(contextUri = "spotify:album:${screen.id}", uris = null, offsetUri = it.uri)
            }
            Screen.SpotifyLiked -> liked.value.items.let { all ->
                // The Web API takes at most 100 tracks at a time, so queue the selected song and the ones after it.
                if (i in all.indices) playSpotify(contextUri = null, uris = all.drop(i).take(100).map { it.uri }, offsetUri = null)
            }
            Screen.Settings -> {
                if (i == 0) shuffle = !shuffle else repeat = RepeatSetting.entries[(repeat.ordinal + 1) % RepeatSetting.entries.size]
                applyModes()
            }
            Screen.Flow -> library.albums.getOrNull(i)?.let { push(Screen.AlbumTracks(it.id)) }
            Screen.Albums -> library.albums.getOrNull(i)?.let { push(Screen.AlbumTracks(it.id)) }
            Screen.Artists -> library.artists.getOrNull(i)?.let { push(Screen.ArtistAlbums(it)) }
            is Screen.ArtistAlbums -> library.albumsBy(screen.artist).getOrNull(i)?.let { push(Screen.AlbumTracks(it.id)) }
            is Screen.AlbumTracks -> {
                if (library.tracksOf(screen.albumId).any { it.uri != null }) {
                    playLocal(library.tracksOf(screen.albumId), i)
                    push(Screen.NowPlaying)
                }
            }
            Screen.Songs -> {
                if (library.songs.getOrNull(i)?.uri != null) {
                    playLocal(library.songs, i)
                    push(Screen.NowPlaying)
                }
            }
            Screen.NowPlaying -> nowPlayingMode =
                if (nowPlayingMode == NowPlayingMode.Volume) NowPlayingMode.Scrub else NowPlayingMode.Volume
        }
    }
}

/** Marks album ids made from Spotify ids so they can't collide with MediaStore's small integers. */
private const val SPOTIFY_ID_BIT = 1L shl 62

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
