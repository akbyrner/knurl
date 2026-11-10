package app.detent.spotify

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.SpotifyConnectionTerminatedException
import com.spotify.android.appremote.api.error.SpotifyDisconnectedException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.PlayerState
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** What the Spotify app is playing right now, as last reported to us. */
class RemoteState(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val isPaused: Boolean,
    val shuffling: Boolean,
    /** Spotify's repeat number: 0 off, 1 this song, 2 everything. */
    val repeatMode: Int,
    /** Spotify's identifier for the cover art, used as the cache key. Null when the track has none. */
    val imageKey: String?,
    /** When this report arrived, so the position can keep moving between reports. */
    val atMs: Long,
) {
    fun positionAt(nowMs: Long): Long {
        val moved = if (isPaused) positionMs else positionMs + (nowMs - atMs)
        return moved.coerceIn(0L, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }
}

/**
 * Remote control of the official Spotify app through Spotify's App Remote library. The Spotify app does the
 * playing, in the background, with its own notification; Detent starts songs, steers playback, and gets a live
 * report of what is playing, including the cover art. Needs the Spotify app installed and signed in.
 *
 * Detent's package name and signing fingerprint must be registered on the app's Spotify dashboard page.
 */
class SpotifyRemote(
    context: Context,
    private val clientId: String,
    /** The visible Activity, used as the context for connecting so Spotify can open its "Allow" screen. */
    private val activityProvider: () -> Context? = { null },
) {
    private val appContext = context.applicationContext
    private var remote: SpotifyAppRemote? = null
    private var subscription: Subscription<PlayerState>? = null

    /** The newest report from the Spotify app, or null before the first one. */
    var state by mutableStateOf<RemoteState?>(null)
        private set

    /** A short reason in plain words when the last connection attempt failed. */
    var detail by mutableStateOf<String?>(null)
        private set

    /** Called on the main thread when a cover image has arrived: the image key and its bitmap. */
    var onImage: ((String, Bitmap) -> Unit)? = null

    /** Called once each time a connection is made, so settings like shuffle can be applied. */
    var onConnected: (() -> Unit)? = null

    private var lastImageKey: String? = null

    val installed: Boolean get() = SpotifyAppRemote.isSpotifyInstalled(appContext)
    val connected: Boolean get() = remote?.isConnected == true

    /** Connects if needed. Returns true once the Spotify app is reachable. The first time, Spotify asks to allow Detent. */
    suspend fun ensureConnected(timeoutMs: Long = 25_000): Boolean {
        if (connected) return true
        if (!installed) {
            detail = "The Spotify app isn't installed"
            return false
        }
        detail = null
        return withTimeoutOrNull(timeoutMs) { connect() } ?: run {
            detail = "The Spotify app didn't answer"
            false
        }
    }

    private suspend fun connect(): Boolean = suspendCancellableCoroutine { cont ->
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(REDIRECT_URI)
            // The first time, the Spotify app shows a screen asking to allow Detent.
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(
            activityProvider() ?: appContext,
            params,
            object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    remote = appRemote
                    watchPlayerState(appRemote)
                    onConnected?.invoke()
                    if (cont.isActive) cont.resume(true)
                }

                override fun onFailure(error: Throwable) {
                    Log.w(TAG, "connect failed: ${error::class.java.simpleName}: ${error.message}")
                    detail = explain(error)
                    if (cont.isActive) cont.resume(false)
                }
            },
        )
    }

    private fun explain(error: Throwable): String = when (error) {
        is CouldNotFindSpotifyApp -> "The Spotify app isn't installed"
        is NotLoggedInException -> "Open the Spotify app and sign in"
        is UserNotAuthorizedException -> "Spotify hasn't approved Detent yet"
        is AuthenticationFailedException -> "Spotify doesn't recognise Detent. Check its package name and fingerprint in the Spotify dashboard."
        is OfflineModeException -> "The Spotify app is in offline mode"
        is SpotifyConnectionTerminatedException, is SpotifyDisconnectedException -> "The Spotify app disconnected"
        else -> error.message?.take(80) ?: "Couldn't reach the Spotify app"
    }

    private fun watchPlayerState(appRemote: SpotifyAppRemote) {
        subscription?.cancel()
        subscription = appRemote.playerApi.subscribeToPlayerState()
            .setEventCallback { player ->
                val track = player.track
                if (track == null) {
                    state = null
                    return@setEventCallback
                }
                val imageKey = track.imageUri?.raw
                state = RemoteState(
                    uri = track.uri.orEmpty(),
                    title = track.name.orEmpty(),
                    artist = track.artist?.name.orEmpty(),
                    album = track.album?.name.orEmpty(),
                    durationMs = track.duration,
                    positionMs = player.playbackPosition,
                    isPaused = player.isPaused,
                    shuffling = player.playbackOptions?.isShuffling == true,
                    repeatMode = player.playbackOptions?.repeatMode ?: 0,
                    imageKey = imageKey,
                    atMs = System.currentTimeMillis(),
                )
                Log.d(TAG, "state: '${track.name}' pos=${player.playbackPosition} paused=${player.isPaused} shuffle=${player.playbackOptions?.isShuffling} repeat=${player.playbackOptions?.repeatMode}")
                if (imageKey != null && imageKey != lastImageKey) {
                    lastImageKey = imageKey
                    appRemote.imagesApi.getImage(track.imageUri, Image.Dimension.LARGE)
                        .setResultCallback { bitmap -> onImage?.invoke(imageKey, bitmap) }
                        .setErrorCallback { lastImageKey = null }
                }
            }
            .setErrorCallback { error ->
                Log.w(TAG, "player state: ${error.message}")
            } as Subscription<PlayerState>
    }

    fun disconnect() {
        subscription?.cancel()
        subscription = null
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        state = null
        lastImageKey = null
    }

    // --- Commands. Each does nothing when not connected; the caller connects first. ---

    /**
     * Plays a track, album or playlist. With [startIndex] it then jumps to that track inside the album or
     * playlist; [queueAfter] adds songs to the queue behind it.
     */
    fun play(uri: String, startIndex: Int? = null, queueAfter: List<String> = emptyList(), onStarted: () -> Unit = {}) {
        val api = remote?.playerApi ?: return
        api.play(uri).setResultCallback {
            if (startIndex != null && startIndex > 0) api.skipToIndex(uri, startIndex)
            queueAfter.forEach { api.queue(it) }
            onStarted()
        }.setErrorCallback { Log.w(TAG, "play failed: ${it.message}") }
    }

    fun pause() { remote?.playerApi?.pause()?.setErrorCallback { Log.w(TAG, "pause failed: ${it.message}") } }
    fun resume() { remote?.playerApi?.resume()?.setErrorCallback { Log.w(TAG, "resume failed: ${it.message}") } }
    fun next() { remote?.playerApi?.skipNext()?.setErrorCallback { Log.w(TAG, "skipNext failed: ${it.message}") } }
    fun previous() {
        remote?.playerApi?.skipPrevious()
            ?.setErrorCallback { Log.w(TAG, "skipPrevious failed: ${it.message}") }
    }

    /** Moves the playhead, and shows the new spot at once instead of waiting for the next report. */
    fun seekTo(positionMs: Long) {
        remote?.playerApi?.seekTo(positionMs)
        state = state?.let {
            RemoteState(it.uri, it.title, it.artist, it.album, it.durationMs, positionMs, it.isPaused, it.shuffling, it.repeatMode, it.imageKey, System.currentTimeMillis())
        }
    }

    fun setShuffle(enabled: Boolean) { remote?.playerApi?.setShuffle(enabled) }

    /** [mode] is Spotify's own number: 0 off, 1 this song, 2 everything. */
    fun setRepeat(mode: Int) { remote?.playerApi?.setRepeat(mode) }

    companion object {
        /** Must be added, exactly as written, to the Redirect URIs of the app on the Spotify dashboard. */
        const val REDIRECT_URI = "app.detent://spotify-remote"
        private const val TAG = "SpotifyRemote"
    }
}
