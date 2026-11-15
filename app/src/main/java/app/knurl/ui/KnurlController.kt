package app.knurl.ui

import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.knurl.data.Album
import app.knurl.data.Library
import app.knurl.data.demoLibrary
import app.knurl.playback.PlayerController
import app.knurl.spotify.SpotifyRemote
import app.knurl.spotify.SpotifyAlbum
import app.knurl.spotify.SpotifyApi
import app.knurl.spotify.SpotifyAuth
import app.knurl.spotify.SpotifyException
import app.knurl.spotify.SpotifyPlaylist
import app.knurl.spotify.SpotifyArtist
import app.knurl.spotify.SpotifyTrack
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
    /** Albums, newest arrival first. */
    data object RecentAlbums : Screen
    /** Liked Songs and the playlists, as a list. */
    data object Playlists : Screen
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
    /** The small engraved line under the title. */
    val label: String? = null,
    /** A cover (or avatar) at the left of each row. */
    val thumbAt: ((Int) -> Album?)? = null,
    val roundThumbs: Boolean = false,
    /** Marks the row of the song that is playing. */
    val playingAt: ((Int) -> Boolean)? = null,
    /** Drag grip at the right of each row, for Up Next. */
    val grip: Boolean = false,
    /** Subtitle drawn in small engraved capitals instead of plain text. */
    val monoSubtitle: Boolean = false,
    /** An A-Z rail down the right edge that follows the selected row. */
    val rail: Boolean = false,
    /** A small tag at the right of a row, such as ON or SIGN IN. */
    val chipAt: ((Int) -> String?)? = null,
    val iconAt: ((Int) -> RowIcon?)? = null,
)

class ListHeader(val title: String, val subtitle: String, val art: Album?, val kind: String = "Album")

/** What the home screen's wheel can land on, top to bottom. */
enum class MenuEntry { Shuffle, Settings, PinLiked, PinAlbum, PinRecent, PinPhone, Flow, Albums, Artists, Songs, Playlists, Sources, NowPlaying }

enum class ThemeChoice(val label: String) { GraphiteGlass("Graphite Glass"), Graphite("Graphite"), Silver("Silver") }

/** The Settings screen's rows in wheel order: three themes, then the rest. */
object SettingsRow {
    const val THEMES = 3
    const val CLICK = 3
    const val HAPTIC = 4
    const val SPEED = 5
    const val SOURCES = 6
    const val ABOUT = 7
    const val SIGN_OUT = 8
}

/** A Spotify list on its way: still loading, failed with a short reason, or ready. */
class SpotifyList<T>(val items: List<T> = emptyList(), val loading: Boolean = false, val error: String? = null) {
    val emptyText: String get() = if (loading) "Loading…" else error ?: "Nothing here yet"
}

/**
 * Wheel in, screen out. The wheel calls [tick] and [button]; the screens read [current] and [listModel].
 * Navigation is a stack: Select pushes, Menu pops, like the original.
 */
class KnurlController(
    val appContext: android.content.Context,
    val player: PlayerController,
    /** The Activity currently on screen, if any. Spotify's "Allow Knurl" screen must be opened from one. */
    private val activityProvider: () -> android.content.Context? = { null },
) {
    var library by mutableStateOf(demoLibrary)
        private set

    private val prefs = appContext.getSharedPreferences("knurl", android.content.Context.MODE_PRIVATE)

    var shuffle by mutableStateOf(prefs.getBoolean("shuffle", false))
        private set

    var repeat by mutableStateOf(
        RepeatSetting.entries.getOrElse(prefs.getInt("repeat", 0)) { RepeatSetting.Off },
    )
        private set

    var nowPlayingMode by mutableStateOf(NowPlayingMode.Volume)
        private set

    var theme by mutableStateOf(ThemeChoice.entries.getOrElse(prefs.getInt("theme", 0)) { ThemeChoice.GraphiteGlass })
        private set
    var clickSound by mutableStateOf(prefs.getBoolean("clickSound", true))
        private set
    var hapticTicks by mutableStateOf(prefs.getBoolean("hapticTicks", true))
        private set
    /** 0 slow, 1 medium, 2 fast: how far a finger travels around the ring for one detent. */
    var wheelSpeed by mutableStateOf(prefs.getInt("wheelSpeed", 1).coerceIn(0, 2))
        private set

    val wheelSpeedLabel: String get() = listOf("Slow", "Medium", "Fast")[wheelSpeed]
    val tickDegrees: Double get() = listOf(32.0, 24.0, 16.0)[wheelSpeed]

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
            android.util.Log.d("knurl", "spotify remote connected=$ok detail=${remote.detail}")
            if (ok) {
                delay(2_000)
                android.util.Log.d("knurl", "spotify now playing=${remote.state?.title} paused=${remote.state?.isPaused}")
            }
        }
    }

    private fun playLocal(tracks: List<app.knurl.data.Track>, startIndex: Int) {
        if (source == Source.Spotify) remote.pause()
        source = Source.Local
        prefs.edit().putString("lastSource", "local").apply()
        tracks.getOrNull(startIndex)?.let { lastAlbumId = it.albumId; prefs.edit().putLong("lastAlbum", it.albumId).apply() }
        player.playQueue(tracks, startIndex)
    }

    private var spotifyStarting = false

    /** The album last played from the phone, pinned on the home screen. */
    private var lastAlbumId by mutableStateOf(prefs.getLong("lastAlbum", 0L).takeIf { it != 0L })

    /**
     * Starts a song, album or playlist in the Spotify app, in the background, then shows Now Playing.
     * The first time, Spotify asks (in its own app) to allow Knurl.
     */
    private fun playSpotify(uri: String, startIndex: Int? = null, queueAfter: List<String> = emptyList(), trackList: List<String>? = null) {
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
                // A plain list of songs (Liked Songs): hand Spotify the list itself so it plays in order from the chosen
                // song, instead of adding songs to the queue, which would leave them behind to play ahead of later plays.
                if (trackList != null) {
                    val started = try {
                        val device = spotifyApi.devices().let { d -> d.firstOrNull { it.isActive } ?: d.firstOrNull() }
                        if (device != null) {
                            remote.setShuffle(shuffle)
                            spotifyApi.play(device.id, uris = trackList)
                            true
                        } else false
                    } catch (e: Exception) { false }
                    if (started) {
                        source = Source.Spotify
                        prefs.edit().putString("lastSource", "spotify").apply()
                        scope.launch { delay(1_200); pushModesToSpotify() }
                        if (current.screen != Screen.NowPlaying) push(Screen.NowPlaying)
                        return@launch
                    }
                }
                // Spotify forgets shuffle and repeat when a new playlist starts, so set them again afterwards.
                remote.play(uri, startIndex, queueAfter, shuffle = shuffle, onStarted = {
                    pushModesToSpotify()
                    // Spotify can switch shuffle back on a moment after a playlist starts, so say it again.
                    scope.launch {
                        delay(1_200)
                        pushModesToSpotify()
                    }
                })
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
     * playing after Knurl is closed, reconnect to it so Now Playing shows what it is doing. When the phone's own
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

    // --- Home-screen widgets ---------------------------------------------------------------------

    val artVersion: Int get() = app.knurl.ui.artVersion

    fun widgetToggle() = togglePlayPause()
    fun widgetSkip(forward: Boolean) = skip(forward)

    class WidgetNext(val title: String, val artist: String, val art: Album?)

    /** The next three songs, for the large widget. */
    fun widgetUpcoming(): List<WidgetNext> = if (source == Source.Spotify) {
        loadQueue()
        spotifyQueue.value.items.take(3).map { WidgetNext(it.title, it.artist, thumbOfTrack(it)) }
    } else {
        localUpcoming().take(3).map { (_, t) -> WidgetNext(t.title, t.artist, library.album(t.albumId)) }
    }

    /** A tap on one of the four pins in the Pinned widget. */
    fun playPin(index: Int) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        when (index) {
            0 -> openMenu(MenuEntry.PinLiked)
            1, 2 -> (if (index == 1) pinnedAlbum else pinnedRecent)?.let { a ->
                val tracks = library.tracksOf(a.id)
                if (tracks.any { it.uri != null }) {
                    playLocal(tracks, 0)
                    push(Screen.NowPlaying)
                } else push(Screen.AlbumTracks(a.id))
            }
            else -> openMenu(MenuEntry.Shuffle)
        }
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

    /** The small label at the top of a Flow screen. */
    fun flowLabel(screen: Screen): String = when (screen) {
        Screen.Flow -> "Flow · Albums"
        Screen.SpotifyHome -> "Spotify · Library"
        else -> "Spotify · ${flowTitle(screen)}"
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
        ListModel(
            title, tracks.size, { tracks[it].title }, { tracks[it].artist },
            chevron = false, emptyText = list.emptyText, header = header,
            rowHeightDp = if (header == null) 44 else null,
            thumbAt = if (header == null) { i -> thumbOfTrack(tracks[i]) } else null,
            playingAt = { tracks[it].uri == currentTrackKey },
        )
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

    /** Songs liked while playing from the phone's own files (there is no Liked Songs on the phone, so Knurl keeps its own list). */
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

    private fun localUpcoming(): List<Pair<Int, app.knurl.data.Track>> {
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
                rowHeightDp = 44, grip = true, label = "Playing now",
                thumbAt = { thumbOfTrack(list.items[it]) },
            )
        }
        val upcoming = localUpcoming()
        return ListModel(
            "Up Next", upcoming.size, { upcoming[it].second.title }, { upcoming[it].second.artist },
            chevron = false, emptyText = "Nothing up next", rowHeightDp = 44, grip = true, label = "Playing now",
            thumbAt = { library.album(upcoming[it].second.albumId) },
        )
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

    private val stack = mutableStateListOf(Entry(Screen.MainMenu, MenuEntry.Flow.ordinal))

    val current: Entry get() = stack.last()

    fun loadLibrary(newLibrary: Library) {
        library = newLibrary
        // Selections can point past the end of a smaller library.
        for (i in stack.indices) stack[i] = stack[i].copy(selected = stack[i].selected.coerceIn(0, maxOf(0, rowCount(stack[i].screen) - 1)))
    }

    fun menuEntries(): List<MenuEntry> = MenuEntry.entries.filter { it != MenuEntry.NowPlaying || hasNowPlaying }

    /** The tiles on the home screen's Pinned shelf. */
    val pinnedLiked: Album get() = homeAlbum(homeTiles[0])
    val pinnedAlbum: Album? get() = lastAlbumId?.let { library.album(it) } ?: library.albums.firstOrNull()
    val pinnedRecent: Album? get() = library.recentAlbums.firstOrNull()

    /** "Good morning", "Good afternoon" or "Good evening". */
    fun greeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour < 5 -> "Good evening"
            hour < 12 -> "Good morning"
            hour < 18 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    /** How many music sources are switched on, for the Settings line. */
    val sourcesOn: Int get() = 1 + if (spotifySignedIn) 1 else 0

    /** A tap on a Settings row: land on it and press it, as the wheel would. */
    fun openSetting(row: Int): Boolean {
        if (current.screen != Screen.Settings || row !in 0 until settingsCount) return false
        setSelected(row)
        select()
        return true
    }

    private val settingsCount: Int get() = if (spotifySignedIn) SettingsRow.SIGN_OUT + 1 else SettingsRow.SIGN_OUT

    private fun selectTheme(t: ThemeChoice) {
        if (t == ThemeChoice.Silver) {
            showNotice("Silver is coming later")
            return
        }
        theme = t
        prefs.edit().putInt("theme", t.ordinal).apply()
    }

    private fun cycleWheelSpeed() {
        wheelSpeed = (wheelSpeed + 1) % 3
        prefs.edit().putInt("wheelSpeed", wheelSpeed).apply()
    }

    /** The albums behind a grid screen. */
    fun gridAlbums(screen: Screen): List<Album>? = when (screen) {
        Screen.Albums -> library.albumsByTitle
        Screen.RecentAlbums -> library.recentAlbums
        is Screen.ArtistAlbums -> library.albumsBy(screen.artist)
        else -> null
    }

    fun gridTitle(screen: Screen): String = when (screen) {
        Screen.Albums -> "Albums"
        Screen.RecentAlbums -> "Recently Added"
        is Screen.ArtistAlbums -> screen.artist
        else -> ""
    }

    fun gridLabel(screen: Screen): String = when (screen) {
        Screen.Albums -> "A–Z · by title"
        Screen.RecentAlbums -> "Newest first"
        else -> "Albums"
    }

    private fun thumbOfTrack(t: SpotifyTrack) = Album(id = (t.imageUrl ?: t.uri).hashCode().toLong() or SPOTIFY_ID_BIT, title = t.albumName, artist = t.artist, artUrl = t.imageUrl)

    /** Whether this phone track is the one playing. */
    private fun isPlayingLocal(track: app.knurl.data.Track) = source != Source.Spotify && player.currentTrackId == track.id

    /** Where music can come from: a title and a one-line description each. */
    private class SourceItem(val title: String, val subtitle: String, val icon: RowIcon)

    private val sourceItems = listOf(
        SourceItem("This phone", "Music files on the device", RowIcon.Phone),
        SourceItem("Navidrome or Subsonic", "Your own server", RowIcon.Server),
        SourceItem("Spotify", "Needs a Premium account", RowIcon.Spotify),
        SourceItem("Apple Music", "Not yet", RowIcon.Songs),
        SourceItem("YouTube Music", "Not yet", RowIcon.YouTube),
    )

    fun listModel(screen: Screen): ListModel? = when (screen) {
        Screen.Artists -> library.artists.let { artists ->
            ListModel(
                "Artists", artists.size, { artists[it] },
                { i -> library.albumsBy(artists[i]).let { a -> if (a.size == 1) a[0].title else "${a.size} albums" } },
                chevron = false, label = "A–Z", rail = true, roundThumbs = true, rowHeightDp = 48,
                thumbAt = { library.albumsBy(artists[it]).firstOrNull() },
            )
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
                playingAt = { isPlayingLocal(tracks[it]) },
            )
        }
        Screen.Songs -> library.songs.let { songs ->
            ListModel(
                "Songs", songs.size, { songs[it].title }, { "${songs[it].artist} · ${songs[it].albumTitle}" },
                chevron = false, label = "A–Z · by title", rowHeightDp = 44,
                thumbAt = { library.album(songs[it].albumId) },
                playingAt = { isPlayingLocal(songs[it]) },
            )
        }
        Screen.Playlists -> playlists.value.items.let { items ->
            ListModel(
                "Playlists", items.size + 1,
                { if (it == 0) "Liked Songs" else items[it - 1].name },
                { if (it == 0) "Spotify" else "Spotify · ${items[it - 1].trackCount} songs" },
                chevron = false, label = "All sources", rowHeightDp = 56, monoSubtitle = true,
                thumbAt = { if (it == 0) pinnedLiked else items[it - 1].let { p -> spotifyTile(p.id, p.name, "", p.imageUrl) } },
            )
        }
        Screen.Queue -> queueModel()
        Screen.Sources -> ListModel(
            "Sources",
            sourceItems.size,
            { sourceItems[it].title },
            { sourceItems[it].subtitle },
            rowHeightDp = 62,
            chevron = false,
            dimAt = { it >= 3 },
            label = "Where your music comes from",
            iconAt = { sourceItems[it].icon },
            chipAt = { i ->
                when (i) {
                    0 -> "ON"
                    1 -> "LATER"
                    2 -> if (spotifySignedIn) "ON" else "SIGN IN"
                    else -> "LATER"
                }
            },
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
                    "Playlist",
                ),
            )
        }
        Screen.SpotifyLiked -> spotifyTrackModel(
            "Liked Songs",
            liked.value,
            ListHeader("Liked Songs", if (liked.value.loading) "" else "${liked.value.items.size} songs", homeAlbum(homeTiles[0]), "Playlist"),
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
                    playingAt = { tracks[it].uri == currentTrackKey },
                )
            }
        }
        Screen.MainMenu, Screen.Settings, Screen.Albums, Screen.RecentAlbums, is Screen.ArtistAlbums,
        Screen.Flow, Screen.SpotifyAlbumsFlow, Screen.SpotifyPlaylists, Screen.SpotifyArtists, Screen.SpotifyRecent,
        is Screen.SpotifyArtistAlbums, Screen.NowPlaying -> null
    }

    /** How many ticks the wheel's ring shows: the volume notches or song notches on Now Playing, else one per row. */
    fun wheelOptions(screen: Screen): Int = when {
        screen != Screen.NowPlaying -> rowCount(screen)
        nowPlayingMode == NowPlayingMode.Scrub -> SCRUB_NOTCHES
        else -> app.knurl.playback.VOLUME_STEPS
    }

    fun wheelSelected(screen: Screen, selected: Int): Int = when {
        screen != Screen.NowPlaying -> selected
        nowPlayingMode == NowPlayingMode.Scrub -> {
            val i = nowPlayingInfo(System.currentTimeMillis())
            if (i != null && i.durationMs > 0) Math.round(i.positionMs.toFloat() / i.durationMs * (SCRUB_NOTCHES - 1)) else 0
        }
        else -> player.volumeStep
    }

    fun rowCount(screen: Screen): Int = when (screen) {
        Screen.NowPlaying -> 0
        Screen.MainMenu -> menuEntries().size
        Screen.Settings -> settingsCount
        else -> gridAlbums(screen)?.size
            ?: if (isFlowScreen(screen)) flowTiles(screen).size else listModel(screen)?.count ?: 0
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
                // One click moves the playhead one notch: a notch is 5% of the song.
                val duration = nowPlayingInfo(System.currentTimeMillis())?.durationMs ?: 0L
                seekBy(direction * (duration / (SCRUB_NOTCHES - 1)).coerceAtLeast(1_000L))
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

    /** A tap on something on the home screen: land on it and open it, as the wheel would. */
    fun openMenu(item: MenuEntry): Boolean {
        if (current.screen != Screen.MainMenu) return false
        val index = menuEntries().indexOf(item)
        if (index < 0) return false
        setSelected(index)
        select()
        return true
    }

    private fun select() {
        val entry = current
        val i = entry.selected
        when (val screen = entry.screen) {
            Screen.MainMenu -> when (menuEntries().getOrNull(i)) {
                MenuEntry.Flow -> push(Screen.Flow)
                MenuEntry.Albums -> push(Screen.Albums)
                MenuEntry.Artists -> push(Screen.Artists)
                MenuEntry.Songs, MenuEntry.PinPhone -> push(Screen.Songs)
                MenuEntry.Shuffle -> {
                    if (library.tracks.any { it.uri != null }) {
                        playLocal(library.tracks.shuffled(), 0)
                        push(Screen.NowPlaying)
                    }
                }
                MenuEntry.Playlists -> {
                    if (spotifySignedIn) {
                        load(playlists) { spotifyApi.playlists() }
                        push(Screen.Playlists)
                    } else {
                        showNotice("Playlists come from Spotify. Sign in under Sources.")
                    }
                }
                MenuEntry.PinLiked -> {
                    if (spotifySignedIn) {
                        load(liked) { spotifyApi.likedSongs() }
                        push(Screen.SpotifyLiked)
                    } else {
                        showNotice("Liked Songs come from Spotify. Sign in under Sources.")
                    }
                }
                MenuEntry.PinAlbum -> pinnedAlbum?.let { push(Screen.AlbumTracks(it.id)) }
                MenuEntry.PinRecent -> push(Screen.RecentAlbums)
                MenuEntry.Sources -> push(Screen.Sources)
                MenuEntry.Settings -> push(Screen.Settings)
                MenuEntry.NowPlaying -> push(Screen.NowPlaying)
                null -> {}
            }
            Screen.Playlists -> if (i == 0) {
                load(liked) { spotifyApi.likedSongs() }
                push(Screen.SpotifyLiked)
            } else playlists.value.items.getOrNull(i - 1)?.let { p ->
                loadKeyed(playlistTracks, p.id) { spotifyApi.playlistTracks(p.id) }
                push(Screen.SpotifyPlaylistTracks(p.id, p.name))
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
                if (i in all.indices) playSpotify(all[i].uri, queueAfter = all.drop(i + 1).take(30).map { it.uri }, trackList = all.drop(i).take(100).map { it.uri })
            }
            Screen.Settings -> when (i) {
                in 0 until SettingsRow.THEMES -> selectTheme(ThemeChoice.entries[i])
                SettingsRow.CLICK -> { clickSound = !clickSound; prefs.edit().putBoolean("clickSound", clickSound).apply() }
                SettingsRow.HAPTIC -> { hapticTicks = !hapticTicks; prefs.edit().putBoolean("hapticTicks", hapticTicks).apply() }
                SettingsRow.SPEED -> cycleWheelSpeed()
                SettingsRow.SOURCES -> push(Screen.Sources)
                SettingsRow.ABOUT -> showNotice("Knurl 0.1")
                else -> signOutSpotify()
            }
            Screen.Flow -> library.albums.getOrNull(i)?.let { push(Screen.AlbumTracks(it.id)) }
            Screen.Albums, Screen.RecentAlbums, is Screen.ArtistAlbums ->
                gridAlbums(screen)?.getOrNull(i)?.let { push(Screen.AlbumTracks(it.id)) }
            Screen.Artists -> library.artists.getOrNull(i)?.let { push(Screen.ArtistAlbums(it)) }
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

/** Minutes and seconds with a leading zero, as the Knurl screens show them: 01:47. */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** The song bar has a notch every 5%: 21 of them, 0 to 100. */
const val SCRUB_NOTCHES = 21
