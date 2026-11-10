package app.detent.spotify

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SpotifyPlaylist(val id: String, val name: String, val trackCount: Int)

data class SpotifyAlbum(val id: String, val name: String, val artist: String, val imageUrl: String?, val trackCount: Int)

data class SpotifyTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val albumName: String,
    val durationMs: Long,
    val imageUrl: String?,
)

data class SpotifyDevice(val id: String, val name: String, val isActive: Boolean)

data class SpotifyPlayback(val track: SpotifyTrack, val isPlaying: Boolean, val progressMs: Long, val deviceName: String?)

/** A failed request, with text safe to show on the small screen. */
class SpotifyException(message: String) : Exception(message)

/** Reads the signed-in person's library through Spotify's Web API. Every list is capped so one screen can't burn the quota. */
class SpotifyApi(private val auth: SpotifyAuth) {

    suspend fun playlists(): List<SpotifyPlaylist> =
        pages("/v1/me/playlists?limit=50").mapNotNull { p ->
            val id = p.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SpotifyPlaylist(
                id = id,
                name = p.optString("name", "Playlist"),
                // The track count has moved between "tracks" and "items" in Spotify's responses.
                trackCount = p.optJSONObject("tracks")?.optInt("total")
                    ?: p.optJSONObject("items")?.optInt("total") ?: 0,
            )
        }

    suspend fun likedSongs(): List<SpotifyTrack> =
        pages("/v1/me/tracks?limit=50").mapNotNull { trackFrom(it.optJSONObject("track")) }

    suspend fun savedAlbums(): List<SpotifyAlbum> =
        pages("/v1/me/albums?limit=50").mapNotNull { albumFrom(it.optJSONObject("album")) }

    /** Playlist contents. Spotify has been renaming this endpoint, so try the old name, then the new one. */
    suspend fun playlistTracks(id: String): List<SpotifyTrack> {
        val items = try {
            pages("/v1/playlists/$id/tracks?limit=100")
        } catch (e: SpotifyException) {
            if (e.message?.startsWith("HTTP 404") == true) pages("/v1/playlists/$id/items?limit=100") else throw e
        }
        return items.mapNotNull { trackFrom(it.optJSONObject("track") ?: it.optJSONObject("item")) }
    }

    // --- Playback control: tells Spotify what the Detent device should play --------------------------------

    suspend fun devices(): List<SpotifyDevice> {
        val list = get("https://api.spotify.com/v1/me/player/devices").optJSONArray("devices") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val d = list.optJSONObject(i) ?: return@mapNotNull null
            val id = d.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SpotifyDevice(id, d.optString("name"), d.optBoolean("is_active"))
        }
    }

    /**
     * Starts playback on [deviceId]. Give either a [contextUri] (a playlist or album) with an optional
     * [offsetUri] for the track to start at, or a list of track [uris].
     */
    suspend fun play(deviceId: String, contextUri: String? = null, uris: List<String>? = null, offsetUri: String? = null) {
        val body = JSONObject().apply {
            contextUri?.let { put("context_uri", it) }
            uris?.let { put("uris", JSONArray(it)) }
            offsetUri?.let { put("offset", JSONObject().put("uri", it)) }
        }
        send("PUT", "https://api.spotify.com/v1/me/player/play?device_id=$deviceId", body.toString())
    }

    suspend fun pause() { send("PUT", "https://api.spotify.com/v1/me/player/pause", null) }
    suspend fun resume() { send("PUT", "https://api.spotify.com/v1/me/player/play", null) }
    suspend fun next() { send("POST", "https://api.spotify.com/v1/me/player/next", null) }
    suspend fun previous() { send("POST", "https://api.spotify.com/v1/me/player/previous", null) }
    suspend fun seek(positionMs: Long) {
        send("PUT", "https://api.spotify.com/v1/me/player/seek?position_ms=$positionMs", null)
    }

    /** What is playing right now, or null when nothing is. */
    suspend fun playback(): SpotifyPlayback? {
        val json = send("GET", "https://api.spotify.com/v1/me/player", null) ?: return null
        val track = trackFrom(json.optJSONObject("item")) ?: return null
        return SpotifyPlayback(
            track = track,
            isPlaying = json.optBoolean("is_playing"),
            progressMs = json.optLong("progress_ms", 0),
            deviceName = json.optJSONObject("device")?.optString("name"),
        )
    }

    suspend fun albumTracks(album: SpotifyAlbum): List<SpotifyTrack> =
        pages("/v1/albums/${album.id}/tracks?limit=50").mapNotNull { t ->
            trackFrom(t)?.copy(albumName = album.name, imageUrl = album.imageUrl)
        }

    private fun albumFrom(a: JSONObject?): SpotifyAlbum? {
        a ?: return null
        val id = a.optString("id").takeIf { it.isNotEmpty() } ?: return null
        return SpotifyAlbum(
            id = id,
            name = a.optString("name", "Album"),
            artist = a.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: "Unknown Artist",
            imageUrl = a.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
            trackCount = a.optInt("total_tracks", 0),
        )
    }

    /** Skips podcasts episodes, local files and tracks Spotify no longer lists (they come back null or without a uri). */
    private fun trackFrom(t: JSONObject?): SpotifyTrack? {
        t ?: return null
        if (t.optString("type", "track") != "track") return null
        val uri = t.optString("uri").takeIf { it.startsWith("spotify:track:") } ?: return null
        val album = t.optJSONObject("album")
        return SpotifyTrack(
            uri = uri,
            title = t.optString("name", "Unknown Song"),
            artist = t.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: "Unknown Artist",
            albumName = album?.optString("name").orEmpty(),
            durationMs = t.optLong("duration_ms", 0),
            imageUrl = album?.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
        )
    }

    /** Follows "next" links, up to [MAX_ITEMS] entries. */
    private suspend fun pages(firstPath: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var url: String? = "https://api.spotify.com$firstPath"
        while (url != null && out.size < MAX_ITEMS) {
            val json = get(url)
            val items: JSONArray = json.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) items.optJSONObject(i)?.let { out += it }
            url = json.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }
        return out
    }

    private suspend fun get(url: String): JSONObject = send("GET", url, null) ?: JSONObject()

    /** One call to the Web API with sign-in, one token refresh on 401 and a short wait on 429. Null for an empty (204) answer. */
    private suspend fun send(method: String, url: String, jsonBody: String?): JSONObject? {
        var refreshed = false
        var attempts = 0
        while (true) {
            val token = auth.accessToken(forceRefresh = refreshed) ?: throw SpotifyException("Not signed in")
            val (code, body, retryAfter) = withContext(Dispatchers.IO) { request(method, url, token, jsonBody) }
            when {
                code in 200..299 -> return body.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
                code == 401 && !refreshed -> refreshed = true
                code == 429 && attempts < 2 -> {
                    attempts++
                    delay((retryAfter ?: 2L).coerceIn(1L, 10L) * 1000)
                }
                else -> {
                    val reason = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
                    throw SpotifyException("HTTP $code" + (reason?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""))
                }
            }
        }
    }

    private fun request(method: String, url: String, token: String, jsonBody: String?): Triple<Int, String, Long?> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (method != "GET") {
                // PUT and POST need a body, even an empty one, or Spotify answers 411.
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write((jsonBody ?: "").toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Triple(code, text, conn.getHeaderField("Retry-After")?.toLongOrNull())
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val MAX_ITEMS = 500
    }
}
