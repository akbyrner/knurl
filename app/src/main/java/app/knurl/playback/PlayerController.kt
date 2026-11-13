package app.knurl.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.knurl.data.Track

/** What the screens read, and what the wheel drives. All calls must happen on the main thread. */
class PlayerController(context: Context) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var controller: MediaController? = null
    private val waiting = mutableListOf<(MediaController) -> Unit>()

    var isPlaying by mutableStateOf(false)
        private set

    /** Track id of the loaded track, or null when nothing has been played yet. */
    var currentTrackId by mutableStateOf<Long?>(null)
        private set

    var durationMs by mutableLongStateOf(0L)
        private set

    var positionMs by mutableLongStateOf(0L)
        private set

    /** 0..1, shown briefly on Now Playing after the wheel changes the volume. */
    var volume by mutableFloatStateOf(currentVolume())
        private set

    /** Bumps every time the volume changes so the screen knows to show the bar again. */
    var volumeChangedAt by mutableLongStateOf(0L)
        private set

    private var shuffleEnabled = false
    private var repeatMode = Player.REPEAT_MODE_OFF

    init {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.shuffleModeEnabled = shuffleEnabled
            c.repeatMode = repeatMode
            c.addListener(listener)
            sync(c)
            waiting.forEach { it(c) }
            waiting.clear()
        }, ContextCompat.getMainExecutor(appContext))
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    private fun sync(player: Player) {
        isPlaying = player.isPlaying
        currentTrackId = player.currentMediaItem?.mediaId?.toLongOrNull()
        durationMs = player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0L
        positionMs = player.currentPosition
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else waiting += block
    }

    /** Replaces the queue and starts playing at [startIndex]. Demo tracks (no uri) are skipped. */
    fun playQueue(tracks: List<Track>, startIndex: Int) {
        val playable = tracks.withIndex().filter { it.value.uri != null }
        if (playable.isEmpty()) return
        val items = playable.map { it.value.toMediaItem() }
        val start = playable.indexOfFirst { it.index >= startIndex }.let { if (it < 0) 0 else it }
        withController { c ->
            c.setMediaItems(items, start, 0L)
            c.prepare()
            c.play()
        }
    }

    /** Shuffle and repeat ([Player.REPEAT_MODE_OFF], [Player.REPEAT_MODE_ALL] or [Player.REPEAT_MODE_ONE]). */
    fun setModes(shuffle: Boolean, repeat: Int) {
        shuffleEnabled = shuffle
        repeatMode = repeat
        withController { c ->
            c.shuffleModeEnabled = shuffle
            c.repeatMode = repeat
        }
    }

    /** Moves the playhead by [deltaMs], staying inside the song. The position shows the new spot at once. */
    fun seekBy(deltaMs: Long) = withController { c ->
        val duration = if (c.duration != androidx.media3.common.C.TIME_UNSET) c.duration else durationMs
        val target = (c.currentPosition + deltaMs).coerceIn(0L, maxOf(0L, duration - 1))
        c.seekTo(target)
        positionMs = target
    }

    fun pause() = withController { c -> c.pause() }

    fun togglePlayPause() = withController { c -> if (c.isPlaying) c.pause() else c.play() }

    fun next() = withController { c -> if (c.hasNextMediaItem()) c.seekToNextMediaItem() }

    /** Like a real iPod: past the first few seconds it restarts the song, otherwise goes back. */
    fun previous() = withController { c ->
        if (c.currentPosition > 3_000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    /** Positions in the queue of the songs that play after this one, in the order they will play (shuffle included). */
    fun upcomingIndices(limit: Int = 50): List<Int> {
        val c = controller ?: return emptyList()
        val timeline = c.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val out = mutableListOf<Int>()
        var i = c.currentMediaItemIndex
        while (out.size < limit) {
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            if (i == androidx.media3.common.C.INDEX_UNSET) break
            out += i
        }
        return out
    }

    fun trackIdAt(index: Int): Long? = controller?.getMediaItemAt(index)?.mediaId?.toLongOrNull()

    /** Jumps to a song in the queue and plays it. */
    fun skipToIndex(index: Int) = withController { c ->
        c.seekToDefaultPosition(index)
        c.play()
    }

    /** Called by the screen while it is showing; the controller itself only reports changes. */
    fun refreshPosition() {
        controller?.let { positionMs = it.currentPosition }
    }

    /** Wheel ticks on Now Playing change the phone's music volume, one step per tick. */
    fun stepVolume(direction: Int) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0,
        )
        volume = currentVolume()
        volumeChangedAt = System.nanoTime()
    }

    private fun currentVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun Track.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(albumTitle)
                    .build(),
            )
            .build()
}
