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

    private suspend fun get(url: String): JSONObject {
        var refreshed = false
        var attempts = 0
        while (true) {
            val token = auth.accessToken(forceRefresh = refreshed) ?: throw SpotifyException("Not signed in")
            val (code, body, retryAfter) = withContext(Dispatchers.IO) { request(url, token) }
            when {
                code in 200..299 -> return JSONObject(body)
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

    private fun request(url: String, token: String): Triple<Int, String, Long?> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "Bearer $token")
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
