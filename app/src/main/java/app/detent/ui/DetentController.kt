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
import app.detent.spotify.SpotifyRemote
import app.detent.spotify.SpotifyAlbum
import app.detent.spotify.SpotifyApi
import app.detent.spotify.SpotifyAuth
import app.detent.spotify.SpotifyException
import app.detent.spotify.SpotifyPlaylist
import app.detent.spotify.SpotifyArtist
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
    data object Sources : Screen
    data object SpotifyHome : Screen
    data object SpotifyPlaylists : Screen
    data class SpotifyPlaylistTracks(val id: String, val name: String) : Screen
    data object SpotifyLiked : Screen
    /** Saved Spotify albums, shown as the same Flow carousel as music on the phone. */
    data object SpotifyAlbumsFlow : Screen
    data object SpotifyArtists : Screen
    data class SpotifyArtistAlbums(val id: String, val name: String) : Screen
    data object SpotifyRecent : Screen
    data class SpotifyAlbumTracks(val id: String, val name: String) : Screen
    data object Albums : Screen
    data object Artists : Screen
    data class ArtistAlbums(val artist: String) : Screen
    data class AlbumTracks(val albumId: Long) : Screen
    data object Songs : Screen
    data object NowPlaying : Screen
    data object Settings : Screen
    /** What plays next, from the Spotify app or from the phone's own queue. */
    data object Queue : Screen
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
    /** "3 of 12": where the song sits in its album, when that is known. */
    val trackOf: String? = null,
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
    /** When set, the screen opens with an album-style header (cover, title, artist) instead of a plain title. */
    val header: ListHeader? = null,
    /** A track number shown at the left of each row. */
    val numberAt: ((Int) -> String)? = null,
    /** Rows drawn quieter, for choices that aren't available yet. */
    val dimAt: ((Int) -> Boolean)? = null,
    /** Rows that get a check mark, such as the music source in use. */
    val checkAt: ((Int) -> Boolean)? = null,
    /** Row height in dp when the default doesn't suit. */
    val rowHeightDp: Int? = null,
)

class ListHeader(val title: String, val subtitle: String, val art: Album?)

/** A Spotify list on its way: still loading, failed with a short reason, or ready. */
class SpotifyList<T>(val items: List<T> = emptyList(), val loading: Boolean = false, val error: String? = null) {
    val emptyText: String get() = if (loading) "Loading…" else error ?: "Nothing here yet"
}

/**
 * Wheel in, screen out. The wheel calls [tick] and [button]; the screens read [current] and [listModel].
 * Navigation is a stack: Select pushes, Menu pops, like the original.
 */
class DetentController(
    val appContext: android.content.Context,
    val player: PlayerController,
    /** The Activity currently on screen, if any. Spotify's "Allow Detent" screen must be opened from one. */
    private val activityProvider: () -> android.content.Context? = { null },
) {
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
    private val remote = SpotifyRemote(appContext, spotifyAuth.clientId, activityProvider).also { r ->
        // Cover art for what the Spotify app is playing arrives as a bitmap; hand it to the art cache.
        r.onImage = { key, bitmap -> putArt(spotifyArtId(key), bitmap) }
        // Shuffle and repeat from Settings apply to Spotify too, each time we connect.
        r.onConnected = { pushModesToSpotify() }
    }

    // --- Now playing, from either the phone's own player or the Spotify app -----------------------

    private enum class Source { None, Local, Spotify }

    // Observable, so the menus and mini player redraw when the playing source changes.
    private var source by mutableStateOf(Source.None)

    private var seekJob: Job? = null

    val hasNowPlaying: Boolean
        get() = player.currentTrackId != null || (source == Source.Spotify && remote.state != null)

    /** What Now Playing draws. [nowMs] is the current time in milliseconds, so the bar keeps moving. */
    fun nowPlayingInfo(nowMs: Long): NowPlayingInfo? {
        if (source == Source.Spotify) {
            val s = remote.state ?: return null
            return NowPlayingInfo(
                title = s.title,
                artist = s.artist,
                albumTitle = s.album,
                art = Album(
                    id = spotifyArtId(s.imageKey ?: s.album),
                    title = s.album,
                    artist = s.artist,
                ),
                durationMs = s.durationMs,
                positionMs = s.positionAt(nowMs),
                isPlaying = !s.isPaused,
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
            trackOf = library.tracksOf(track.albumId).size.takeIf { it > 0 && track.trackNumber > 0 }
                ?.let { "${track.trackNumber} of $it" },
        )
    }

    private fun spotifyArtId(key: String): Long = key.hashCode().toLong() or SPOTIFY_ID_BIT

    private fun pushModesToSpotify() {
        remote.setShuffle(shuffle)
        // Spotify's repeat numbers: 0 off, 1 this song, 2 everything.
        remote.setRepeat(when (repeat) { RepeatSetting.Off -> 0; RepeatSetting.One -> 1; RepeatSetting.All -> 2 })
    }

    /** Debug builds only: connect to the Spotify app and log how it went. Used from adb during development. */
    fun debugConnectRemote() {
        scope.launch {
            val ok = remote.ensureConnected()
            android.util.Log.d("detent", "spotify remote connected=$ok detail=${remote.detail}")
            if (ok) {
                delay(2_000)
                android.util.Log.d("detent", "spotify now playing=${remote.state?.title} paused=${remote.state?.isPaused}")
            }
        }
    }

    private fun playLocal(tracks: List<app.detent.data.Track>, startIndex: Int) {
        if (source == Source.Spotify) remote.pause()
        source = Source.Local
        prefs.edit().putString("lastSource", "local").apply()
        player.playQueue(tracks, startIndex)
    }

    private var spotifyStarting = false

    /**
     * Starts a song, album or playlist in the Spotify app, in the background, then shows Now Playing.
     * The first time, Spotify asks (in its own app) to allow Detent.
     */
    private fun playSpotify(uri: String, startIndex: Int? = null, queueAfter: List<String> = emptyList()) {
        // A second Select while the first is still connecting would start a second attempt.
        if (spotifyStarting) return
        spotifyStarting = true
        scope.launch {
            try {
                player.pause()
                if (!remote.connected) showNotice("Connecting to Spotify…")
                if (!remote.ensureConnected()) {
                    showNotice(remote.detail ?: "Couldn't reach the Spotify app")
                    return@launch
                }
                // Spotify forgets shuffle and repeat when a new playlist starts, so set them again afterwards.
                remote.play(uri, startIndex, queueAfter, onStarted = { pushModesToSpotify() })
                source = Source.Spotify
                prefs.edit().putString("lastSource", "spotify").apply()
                if (current.screen != Screen.NowPlaying) push(Screen.NowPlaying)
            } finally {
                spotifyStarting = false
            }
        }
    }

    /**
     * Called each time the app comes to the front. If the last thing played came from the Spotify app, which keeps
     * playing after Detent is closed, reconnect to it so Now Playing shows what it is doing. When the phone's own
     * player was last, nothing is needed: its service keeps the queue.
     */
    fun restoreSession() {
        val wantSpotify = source == Source.Spotify ||
            (source == Source.None && player.currentTrackId == null && prefs.getString("lastSource", null) == "spotify")
        if (!wantSpotify || remote.connected || !spotifyAuth.signedIn || !remote.installed || spotifyStarting) return
        scope.launch {
            // Quiet: no notice if the Spotify app isn't reachable, the Now Playing entry just won't appear.
            val ok = remote.ensureConnected(10_000)
            if (ok && source == Source.None && player.currentTrackId == null) source = Source.Spotify
        }
    }

    /** Runs a command on the Spotify app, reconnecting first if it went away. */
    private fun withSpotify(block: SpotifyRemote.() -> Unit) {
        scope.launch {
            if (!remote.ensureConnected()) {
                showNotice(remote.detail ?: "Couldn't reach the Spotify app")
                return@launch
            }
            remote.block()
        }
    }

    private fun togglePlayPause() {
        if (source != Source.Spotify) {
            player.togglePlayPause()
            return
        }
        val paused = remote.state?.isPaused ?: false
        withSpotify { if (paused) resume() else pause() }
    }

    private fun skip(forward: Boolean) {
        if (source != Source.Spotify) {
            if (forward) player.next() else player.previous()
            return
        }
        withSpotify { if (forward) next() else previous() }
    }

    private fun seekBy(deltaMs: Long) {
        if (source != Source.Spotify) {
            player.seekBy(deltaMs)
            return
        }
        val s = remote.state ?: return
        val target = (s.positionAt(System.currentTimeMillis()) + deltaMs).coerceIn(0L, maxOf(0L, s.durationMs - 1))
        // The screen moves at once; many wheel ticks arrive in a burst, so only the last spot is sent to Spotify.
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(300)
            if (remote.connected) remote.seekTo(target)
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

    private val artists = mutableStateOf(SpotifyList<SpotifyArtist>())
    private val recent = mutableStateOf(SpotifyList<SpotifyAlbum>())
    private val artistAlbums = mutableStateMapOf<String, SpotifyList<SpotifyAlbum>>()

    /** Every Spotify album listed so far, so its tracks screen can show the cover whichever list it came from. */
    private val albumInfo = mutableMapOf<String, SpotifyAlbum>()

    private class HomeTile(val title: String, val subtitle: String, val glyph: String, val hue: Float)

    /** The Spotify home carousel: one tile per part of the library. */
    private val homeTiles = listOf(
        HomeTile("Liked Songs", "Songs you've liked", "♥", 285f),
        HomeTile("Playlists", "Your playlists", "♫", 205f),
        HomeTile("Albums", "Saved albums", "◉", 160f),
        HomeTile("Artists", "Artists you follow", "★", 35f),
        HomeTile("Recently Played", "Jump back in", "↻", 345f),
    )

    private fun homeAlbum(t: HomeTile) =
        Album(id = t.title.hashCode().toLong() or SPOTIFY_ID_BIT, title = t.title, artist = t.subtitle, glyph = t.glyph, hue = t.hue)

    private fun spotifyTile(id: String, title: String, subtitle: String, imageUrl: String?) =
        Album(id = id.hashCode().toLong() or SPOTIFY_ID_BIT, title = title, artist = subtitle, artUrl = imageUrl)

    /** Screens drawn as the Flow carousel: the phone's albums and most Spotify browsing. */
    fun isFlowScreen(screen: Screen): Boolean = when (screen) {
        Screen.Flow, Screen.SpotifyAlbumsFlow, Screen.SpotifyPlaylists, Screen.SpotifyArtists, Screen.SpotifyRecent,
        is Screen.SpotifyArtistAlbums -> true
        Screen.SpotifyHome -> spotifyAuth.configured && spotifySignedIn
        else -> false
    }

    /** The albums behind a Spotify Flow screen that shows albums. */
    private fun spotifyAlbumList(screen: Screen): SpotifyList<SpotifyAlbum>? = when (screen) {
        Screen.SpotifyAlbumsFlow -> spotifyAlbums.value
        Screen.SpotifyRecent -> recent.value
        is Screen.SpotifyArtistAlbums -> artistAlbums[screen.id] ?: SpotifyList(loading = true)
        else -> null
    }

    /** The tiles a Flow screen draws; the selected index counts into this list. */
    fun flowTiles(screen: Screen): List<Album> = when (screen) {
        Screen.Flow -> library.albums
        Screen.SpotifyHome -> homeTiles.map { homeAlbum(it) }
        Screen.SpotifyPlaylists -> playlists.value.items.map { spotifyTile(it.id, it.name, "${it.trackCount} songs", it.imageUrl) }
        Screen.SpotifyArtists -> artists.value.items.map { spotifyTile(it.id, it.name, "Artist", it.imageUrl) }
        else -> spotifyAlbumList(screen)?.items?.map { spotifyTile(it.id, it.name, it.artist, it.imageUrl) }.orEmpty()
    }

    /** What to say instead of a carousel while it is empty, loading or failed. */
    fun flowEmptyText(screen: Screen): String = when (screen) {
        Screen.Flow -> "No music found"
        Screen.SpotifyPlaylists -> playlists.value.emptyText
        Screen.SpotifyArtists -> artists.value.emptyText
        else -> spotifyAlbumList(screen)?.emptyText ?: "Nothing here yet"
    }

    fun flowTitle(screen: Screen): String = when (screen) {
        Screen.SpotifyPlaylists -> "Playlists"
        Screen.SpotifyArtists -> "Artists"
        Screen.SpotifyRecent -> "Recently Played"
        Screen.SpotifyAlbumsFlow -> "Albums"
        is Screen.SpotifyArtistAlbums -> screen.name
        Screen.SpotifyHome -> "Spotify"
        else -> "Flow"
    }

    private fun signOutSpotify() {
        spotifyAuth.signOut()
        prefs.edit().remove("lastSource").apply()
        remote.disconnect()
        if (source == Source.Spotify) source = Source.None
        spotifySignedIn = false
        playlists.value = SpotifyList()
        liked.value = SpotifyList()
        spotifyAlbums.value = SpotifyList()
        artists.value = SpotifyList()
        recent.value = SpotifyList()
        artistAlbums.clear()
        playlistTracks.clear()
        albumTracks.clear()
        albumInfo.clear()
        showNotice("Signed out of Spotify")
    }

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
        else -> homeTiles.map { it.title }
    }

    private fun spotifyTrackModel(title: String, list: SpotifyList<SpotifyTrack>, header: ListHeader? = null) = list.items.let { tracks ->
        ListModel(title, tracks.size, { tracks[it].title }, { tracks[it].artist }, chevron = false, emptyText = list.emptyText, header = header)
    }

    /** The Now Playing button in the wheel's corner: opens Now Playing, or goes back from it. Returns true when something changed. */
    fun toggleNowPlaying(): Boolean {
        if (current.screen == Screen.NowPlaying) return back()
        if (!hasNowPlaying) {
            showNotice("Nothing is playing")
            return false
        }
        push(Screen.NowPlaying)
        return true
    }

    // --- Like and the queue --------------------------------------------------------------------------

    /** Songs liked while playing from the phone's own files (there is no Liked Songs on the phone, so Detent keeps its own list). */
    private var localFavorites by mutableStateOf(prefs.getStringSet("favorites", emptySet()).orEmpty().toSet())

    /** Whether the Spotify song [spotifyLikedUri] is in Liked Songs, as last checked or set. */
    private var spotifyLikedUri by mutableStateOf<String?>(null)
    private var spotifyLikedValue by mutableStateOf(false)

    private val spotifyQueue = mutableStateOf(SpotifyList<SpotifyTrack>())

    init {
        // Each time the Spotify app moves to another song, ask whether it is liked.
        scope.launch {
            androidx.compose.runtime.snapshotFlow { if (source == Source.Spotify) remote.state?.uri else null }
                .collect { uri -> if (uri != null) refreshSpotifyLiked(uri) }
        }
    }

    private suspend fun refreshSpotifyLiked(uri: String) {
        if (!spotifyAuth.signedIn) return
        try {
            val saved = spotifyApi.isTrackSaved(uri)
            if (remote.state?.uri == uri) {
                spotifyLikedUri = uri
                spotifyLikedValue = saved
            }
        } catch (e: Exception) {
            // Leave the heart as it was; liking will report a real error if it still fails.
        }
    }

    /** Whether the heart is lit: the song playing now is liked. */
    val currentLiked: Boolean
        get() = if (source == Source.Spotify) {
            val uri = remote.state?.uri
            uri != null && uri == spotifyLikedUri && spotifyLikedValue
        } else {
            player.currentTrackId?.toString() in localFavorites
        }

    /** Identifies the song playing, so a screen can refresh when it changes. */
    val currentTrackKey: String?
        get() = if (source == Source.Spotify) remote.state?.uri else player.currentTrackId?.toString()

    /** The heart button: likes or unlikes what is playing. Returns true when something happened. */
    fun toggleLike(): Boolean {
        if (!hasNowPlaying) {
            showNotice("Nothing is playing")
            return false
        }
        if (source == Source.Spotify) {
            val uri = remote.state?.uri ?: return false
            if (!spotifyAuth.signedIn) {
                showNotice("Sign in to Spotify to like songs")
                return false
            }
            val want = !currentLiked
            spotifyLikedUri = uri
            spotifyLikedValue = want
            scope.launch {
                try {
                    spotifyApi.setTrackSaved(uri, want)
                    // The Liked Songs list is out of date now. Refresh it in place if it was loaded, keeping the rows
                    // on screen meanwhile (this button is visible on every screen, including that list).
                    if (liked.value.items.isNotEmpty()) load(liked) { spotifyApi.likedSongs() }
                    showNotice(if (want) "Added to Liked Songs" else "Removed from Liked Songs")
                } catch (e: Exception) {
                    if (spotifyLikedUri == uri) spotifyLikedValue = !want
                    showNotice((e as? SpotifyException)?.message ?: "Couldn't update Liked Songs")
                }
            }
        } else {
            val id = player.currentTrackId?.toString() ?: return false
            localFavorites = if (id in localFavorites) localFavorites - id else localFavorites + id
            prefs.edit().putStringSet("favorites", localFavorites).apply()
            showNotice(if (id in localFavorites) "Added to Favorites" else "Removed from Favorites")
        }
        return true
    }

    /** The queue button: opens Up Next, or goes back from it. */
    fun toggleQueue(): Boolean {
        if (current.screen == Screen.Queue) return back()
        if (!hasNowPlaying) {
            showNotice("Nothing is playing")
            return false
        }
        push(Screen.Queue)
        return true
    }

    /** Fetches Up Next from the Spotify app. The phone's own queue is read straight from its player. */
    fun loadQueue() {
        if (source == Source.Spotify) load(spotifyQueue) { spotifyApi.queue() }
    }

    private fun localUpcoming(): List<Pair<Int, app.detent.data.Track>> {
        player.currentTrackId // read so the list follows song changes
        shuffle
        return player.upcomingIndices().mapNotNull { index ->
            player.trackIdAt(index)?.let { library.track(it) }?.let { index to it }
        }
    }

    private fun queueModel(): ListModel {
        if (source == Source.Spotify) {
            val list = spotifyQueue.value
            return ListModel(
                "Up Next",
                list.items.size,
                { list.items[it].title },
                { list.items[it].artist },
                chevron = false,
                emptyText = if (list.loading || list.error != null) list.emptyText else "Nothing up next",
            )
        }
        val upcoming = localUpcoming()
        return ListModel("Up Next", upcoming.size, { upcoming[it].second.title }, { upcoming[it].second.artist }, chevron = false, emptyText = "Nothing up next")
    }

    /** The shuffle button beside the wheel. */
    fun toggleShuffle() {
        shuffle = !shuffle
        applyModes()
    }

    /** The repeat button beside the wheel: off, then all, then one, then off again. */
    fun cycleRepeat() {
        repeat = RepeatSetting.entries[(repeat.ordinal + 1) % RepeatSetting.entries.size]
        applyModes()
    }

    private fun applyModes() {
        prefs.edit().putBoolean("shuffle", shuffle).putInt("repeat", repeat.ordinal).apply()
        player.setModes(shuffle, repeat.playerMode)
        if (remote.connected) pushModesToSpotify()
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
        add("Playlists")
        add("Sources")
        add("Shuffle Songs")
        add("Settings")
        if (hasNowPlaying) add("Now Playing")
    }

    private val settingsItems: List<String>
        get() = listOf("Shuffle", "Repeat") + if (spotifySignedIn) listOf("Sign out of Spotify") else emptyList()

    /** Where music can come from: a title and a one-line description each. */
    private val sourceItems = listOf(
        "This phone" to "Music files stored on the phone",
        "Navidrome or Subsonic" to "Streams from your own server",
        "Spotify" to "Plays in the Spotify app, controlled here",
        "Apple Music" to "Later",
        "YouTube Music" to "Later",
    )

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
            val album = library.album(screen.albumId)
            ListModel(
                album?.title.orEmpty(),
                tracks.size,
                { tracks[it].title },
                chevron = false,
                header = ListHeader(album?.title.orEmpty(), album?.artist.orEmpty(), album),
                numberAt = { (tracks[it].trackNumber.takeIf { n -> n > 0 } ?: (it + 1)).toString() },
            )
        }
        Screen.Songs -> library.songs.let { songs ->
            ListModel("Songs", songs.size, { songs[it].title }, { songs[it].artist }, chevron = false)
        }
        Screen.Queue -> queueModel()
        Screen.Settings -> ListModel(
            "Settings",
            settingsItems.size,
            { settingsItems[it] },
            valueAt = {
                when (it) {
                    0 -> if (shuffle) "On" else "Off"
                    1 -> repeat.label
                    else -> ""
                }
            },
        )
        Screen.Sources -> ListModel(
            "Sources",
            sourceItems.size,
            { sourceItems[it].first },
            { sourceItems[it].second },
            rowHeightDp = 64,
            checkAt = { it == 0 },
            dimAt = { it >= 3 },
        )
        Screen.SpotifyHome -> spotifyHomeItems().let { items ->
            ListModel("Spotify", items.size, { items[it] }, chevron = items.size > 1)
        }
        is Screen.SpotifyPlaylistTracks -> {
            val playlist = playlists.value.items.firstOrNull { it.id == screen.id }
            val tracks = playlistTracks[screen.id] ?: SpotifyList(loading = true)
            spotifyTrackModel(
                screen.name,
                tracks,
                ListHeader(
                    screen.name,
                    if (tracks.loading) "" else "${tracks.items.size} songs",
                    playlist?.let { spotifyTile(it.id, it.name, "", it.imageUrl) },
                ),
            )
        }
        Screen.SpotifyLiked -> spotifyTrackModel(
            "Liked Songs",
            liked.value,
            ListHeader("Liked Songs", if (liked.value.loading) "" else "${liked.value.items.size} songs", homeAlbum(homeTiles[0])),
        )
        is Screen.SpotifyAlbumTracks -> {
            val album = albumInfo[screen.id]
            val flowAlbum = album?.let { spotifyTile(it.id, it.name, it.artist, it.imageUrl) }
            val list = albumTracks[screen.id] ?: SpotifyList(loading = true)
            list.items.let { tracks ->
                ListModel(
                    screen.name,
                    tracks.size,
                    { tracks[it].title },
                    chevron = false,
                    emptyText = list.emptyText,
                    header = ListHeader(screen.name, album?.artist.orEmpty(), flowAlbum),
                    numberAt = { (it + 1).toString() },
                )
            }
        }
        Screen.Flow, Screen.SpotifyAlbumsFlow, Screen.SpotifyPlaylists, Screen.SpotifyArtists, Screen.SpotifyRecent,
        is Screen.SpotifyArtistAlbums, Screen.NowPlaying -> null
    }

    private fun rowCount(screen: Screen): Int = when (screen) {
        Screen.NowPlaying -> 0
        else -> if (isFlowScreen(screen)) flowTiles(screen).size else listModel(screen)?.count ?: 0
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
            WheelButton.Next -> if (isFlowScreen(current.screen)) setSelected(current.selected + 1) else skip(forward = true)
            WheelButton.Prev -> if (isFlowScreen(current.screen)) setSelected(current.selected - 1) else skip(forward = false)
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
                "Playlists" -> {
                    if (spotifySignedIn) {
                        load(playlists) { spotifyApi.playlists() }
                        push(Screen.SpotifyPlaylists)
                    } else {
                        showNotice("Playlists come from Spotify. Sign in under Sources.")
                    }
                }
                "Sources" -> push(Screen.Sources)
                "Settings" -> push(Screen.Settings)
                "Now Playing" -> push(Screen.NowPlaying)
            }
            Screen.Sources -> when (i) {
                0 -> showNotice("Already playing music from this phone")
                1 -> showNotice("Subsonic isn't built yet")
                2 -> push(Screen.SpotifyHome)
                else -> showNotice("Later")
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
                "Artists" -> {
                    load(artists) { spotifyApi.followedArtists() }
                    push(Screen.SpotifyArtists)
                }
                "Recently Played" -> {
                    load(recent) { spotifyApi.recentAlbums() }
                    push(Screen.SpotifyRecent)
                }
            }
            Screen.SpotifyPlaylists -> playlists.value.items.getOrNull(i)?.let { p ->
                loadKeyed(playlistTracks, p.id) { spotifyApi.playlistTracks(p.id) }
                push(Screen.SpotifyPlaylistTracks(p.id, p.name))
            }
            Screen.SpotifyAlbumsFlow, Screen.SpotifyRecent, is Screen.SpotifyArtistAlbums ->
                spotifyAlbumList(screen)?.items?.getOrNull(i)?.let { a ->
                    albumInfo[a.id] = a
                    loadKeyed(albumTracks, a.id) { spotifyApi.albumTracks(a) }
                    push(Screen.SpotifyAlbumTracks(a.id, a.name))
                }
            Screen.SpotifyArtists -> artists.value.items.getOrNull(i)?.let { a ->
                loadKeyed(artistAlbums, a.id) { spotifyApi.artistAlbums(a.id) }
                push(Screen.SpotifyArtistAlbums(a.id, a.name))
            }
            is Screen.SpotifyPlaylistTracks -> playlistTracks[screen.id]?.items?.getOrNull(i)?.let {
                // The playlist's own position of this song (some entries, like podcasts, are skipped in our list).
                playSpotify("spotify:playlist:${screen.id}", startIndex = it.position.takeIf { p -> p >= 0 } ?: i)
            }
            is Screen.SpotifyAlbumTracks -> albumTracks[screen.id]?.items?.getOrNull(i)?.let {
                playSpotify("spotify:album:${screen.id}", startIndex = i)
            }
            Screen.SpotifyLiked -> liked.value.items.let { all ->
                // Liked Songs has no playlist address to jump into, so play the song and queue the next few behind it.
                if (i in all.indices) playSpotify(all[i].uri, queueAfter = all.drop(i + 1).take(30).map { it.uri })
            }
            Screen.Settings -> {
                when (i) {
                    0 -> toggleShuffle()
                    1 -> cycleRepeat()
                    else -> signOutSpotify()
                }
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
            Screen.Queue -> if (source == Source.Spotify) {
                // Spotify can't jump inside its queue, so skip ahead one song at a time.
                if (i in spotifyQueue.value.items.indices) {
                    scope.launch {
                        repeat(i + 1) {
                            remote.next()
                            delay(250)
                        }
                        delay(500)
                        loadQueue()
                    }
                }
            } else {
                localUpcoming().getOrNull(i)?.let { player.skipToIndex(it.first) }
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
