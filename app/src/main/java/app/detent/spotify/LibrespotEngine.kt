package app.detent.spotify

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs librespot, an unofficial Spotify Connect player, as a child process. It shows up in the person's
 * Spotify account as a device called [DEVICE_NAME]. Its audio comes back through a named pipe as raw
 * 16-bit stereo 44.1 kHz sound, which this class plays with an AudioTrack. Playback is started and
 * controlled through the Web API ([SpotifyApi]); this class only owns the process and the sound.
 *
 * librespot says in its own README that connecting to Spotify this way is probably against Spotify's terms.
 */
class LibrespotEngine(context: Context) {
    enum class Status { Stopped, Starting, NeedsLogin, Authenticated, Failed }

    private val appContext = context.applicationContext
    private val binary = File(appContext.applicationInfo.nativeLibraryDir, "liblibrespot.so")
    private val dataDir = File(appContext.filesDir, "librespot")
    private val pcmPipe = File(appContext.cacheDir, "librespot-pcm")
    private val credentials = File(dataDir, "credentials.json")

    var status by mutableStateOf(Status.Stopped)
        private set

    /** A short reason when [status] is Failed. */
    var detail by mutableStateOf<String?>(null)
        private set

    @Volatile private var process: Process? = null
    @Volatile private var loginUrl: String? = null
    @Volatile private var stopping = false

    /** False when this build didn't include the librespot binary. */
    val available: Boolean get() = binary.exists()

    fun start() {
        synchronized(this) {
            if (process?.isAlive == true) {
                // Already running. If it is still waiting for the person to sign in, show the same page again
                // instead of starting a second player, which would fight over the sign-in port.
                if (status == Status.NeedsLogin) loginUrl?.let { openBrowser(it) }
                return
            }
            if (!available) {
                fail("Spotify player isn't included in this build")
                return
            }
            stopping = false
            status = Status.Starting
            detail = null
            dataDir.mkdirs()
            makePipe()

            val signedInBefore = credentials.exists()
            val command = mutableListOf(
                binary.path,
                "--name", DEVICE_NAME,
                "--backend", "pipe",
                "--device", pcmPipe.path,
                "--format", "S16",
                "--bitrate", "320",
                // Android's own volume does the work, so librespot passes the sound through at full level.
                "--volume-ctrl", "fixed",
                "--initial-volume", "100",
                // Device discovery on the local network isn't needed, and Android restricts it.
                "--disable-discovery",
                "--cache", dataDir.path,
            )
            if (!signedInBefore) command += listOf("--enable-oauth", "--oauth-port", OAUTH_PORT.toString())

            val builder = ProcessBuilder(command).directory(dataDir)
            builder.environment()["TMPDIR"] = appContext.cacheDir.path
            builder.environment()["HOME"] = dataDir.path
            val proc = try {
                builder.start()
            } catch (e: IOException) {
                fail("Couldn't start the Spotify player")
                return
            }
            process = proc

            thread(name = "librespot-out", isDaemon = true) { readOutput(proc) }
            thread(name = "librespot-log", isDaemon = true) { readLog(proc, signedInBefore) }
            thread(name = "librespot-audio", isDaemon = true) { pumpAudio(proc) }
            thread(name = "librespot-wait", isDaemon = true) {
                val code = proc.waitFor()
                // A newer process may already have replaced this one; then this exit is none of its business.
                if (process !== proc) return@thread
                if (!stopping) {
                    // A saved login that no longer works means the person has to sign in again.
                    if (signedInBefore && status != Status.Authenticated) credentials.delete()
                    if (status != Status.Failed) fail(if (signedInBefore) "Spotify login expired. Try again." else "Spotify player stopped ($code)")
                } else {
                    status = Status.Stopped
                }
                process = null
            }
        }
    }

    fun stop() {
        stopping = true
        process?.destroy()
        // The audio thread may be blocked opening the pipe; opening it for writing lets it move on and notice.
        // Opening a pipe can itself block, so do it off the calling thread.
        thread(name = "librespot-unblock", isDaemon = true) { runCatching { FileOutputStream(pcmPipe).close() } }
    }

    /** Signs the Spotify player out: stops it and forgets the saved login. */
    fun forgetLogin() {
        stop()
        credentials.delete()
        status = Status.Stopped
    }

    /**
     * Starts the player if needed and waits until it is signed in. A first-time login opens the browser and
     * can take a while, so the wait is long. Returns true once the player is ready.
     */
    suspend fun ensureAuthenticated(timeoutMs: Long = 180_000): Boolean {
        if (status == Status.Authenticated && process?.isAlive == true) return true
        start()
        val result = withTimeoutOrNull(timeoutMs) {
            snapshotFlow { status }.first { it == Status.Authenticated || it == Status.Failed || it == Status.Stopped }
        }
        return result == Status.Authenticated
    }

    private fun fail(reason: String) {
        detail = reason
        status = Status.Failed
    }

    private fun makePipe() {
        pcmPipe.delete()
        Os.mkfifo(pcmPipe.path, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
    }

    /** stdout carries text only (the sign-in link), because the sound goes through the pipe. */
    private fun readOutput(proc: Process) {
        proc.inputStream.bufferedReader().forEachLine { line ->
            if (line.startsWith("Browse to: ")) {
                val url = line.removePrefix("Browse to: ").trim()
                loginUrl = url
                status = Status.NeedsLogin
                openBrowser(url)
            }
        }
    }

    private fun openBrowser(url: String) {
        appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun readLog(proc: Process, signedInBefore: Boolean) {
        proc.errorStream.bufferedReader().forEachLine { line ->
            Log.d(TAG, line)
            when {
                line.contains("Authenticated as") -> {
                    status = Status.Authenticated
                    detail = null
                }
                line.contains("Address already in use") -> fail("Another Spotify player is still running. Try again.")
                line.contains("Failed to get Spotify access token") -> fail("Spotify sign-in failed. Try again.")
                line.contains("Invalid credentials", ignoreCase = true) ||
                    line.contains("Authentication failed", ignoreCase = true) ||
                    line.contains("Bad credentials", ignoreCase = true) -> {
                    if (signedInBefore) credentials.delete()
                    fail("Spotify rejected the login")
                }
            }
        }
    }

    /** Reads raw sound from the pipe and plays it. The pipe closes when Spotify pauses, so reopen for the next play. */
    private fun pumpAudio(proc: Process) {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 4, 32 * 1024))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val buffer = ByteArray(8 * 1024)
        try {
            while (proc.isAlive && !stopping) {
                // Blocks here until librespot opens the other end, which it does when playback starts.
                FileInputStream(pcmPipe).use { input ->
                    if (stopping) return@use
                    track.play()
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        track.write(buffer, 0, n)
                    }
                    track.stop()
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "audio pipe closed: ${e.message}")
        } finally {
            track.release()
        }
    }

    companion object {
        /** The name that appears in Spotify's device list. The Web API finds this device by it. */
        const val DEVICE_NAME = "Detent"
        private const val OAUTH_PORT = 5588
        private const val SAMPLE_RATE = 44_100
        private const val TAG = "librespot"
    }
}
