package app.detent.data

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val artUri: Uri? = null,
)

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val albumId: Long,
    val albumTitle: String,
    val trackNumber: Int,
    val durationMs: Long,
    /** Null for demo tracks, which have nothing to play. */
    val uri: Uri?,
)

/** Everything on the phone (or the demo set), already sorted the way the menus show it. */
class Library(val albums: List<Album>, val tracks: List<Track>, val isDemo: Boolean = false) {
    private val albumById = albums.associateBy { it.id }
    private val tracksByAlbum = tracks.groupBy { it.albumId }
    private val trackById = tracks.associateBy { it.id }

    /** Artist names, A to Z. */
    val artists: List<String> = albums.map { it.artist }.distinct().sortedBy { it.lowercase() }

    /** All tracks, sorted by title for the Songs list. */
    val songs: List<Track> = tracks.sortedBy { it.title.lowercase() }

    fun album(id: Long): Album? = albumById[id]
    fun track(id: Long): Track? = trackById[id]
    fun tracksOf(albumId: Long): List<Track> = tracksByAlbum[albumId].orEmpty()
    fun albumsBy(artist: String): List<Album> = albums.filter { it.artist == artist }
}

/** Tracks from the device's MediaStore. Needs READ_MEDIA_AUDIO granted. */
fun loadLibrary(resolver: ContentResolver): Library {
    val media = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val albumsUri = MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.DURATION,
    )
    val tracks = mutableListOf<Track>()
    resolver.query(media, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val track = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        while (c.moveToNext()) {
            val rowId = c.getLong(id)
            tracks += Track(
                id = rowId,
                title = c.getString(title)?.takeIf { it.isNotBlank() } ?: "Unknown Song",
                artist = c.getString(artist)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown Artist",
                albumId = c.getLong(albumId),
                albumTitle = c.getString(album)?.takeIf { it.isNotBlank() } ?: "Unknown Album",
                // MediaStore stores disc number * 1000 + track number
                trackNumber = c.getInt(track) % 1000,
                durationMs = c.getLong(duration),
                uri = ContentUris.withAppendedId(media, rowId),
            )
        }
    }
    return buildLibrary(tracks) { ContentUris.withAppendedId(albumsUri, it) }
}

/** One album per album id. The album's artist is the track artist, or "Various Artists" when tracks disagree. */
private fun buildLibrary(tracks: List<Track>, artUri: (Long) -> Uri?): Library {
    val albums = tracks.groupBy { it.albumId }.map { (albumId, list) ->
        val artists = list.map { it.artist }.distinct()
        Album(
            id = albumId,
            title = list.first().albumTitle,
            artist = if (artists.size == 1) artists.first() else "Various Artists",
            artUri = artUri(albumId),
        )
    }.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))

    val orderedTracks = tracks.sortedWith(compareBy({ it.albumId }, { it.trackNumber }, { it.title.lowercase() }))
    return Library(albums, orderedTracks)
}

fun loadArt(resolver: ContentResolver, uri: Uri, px: Int): Bitmap? =
    try {
        resolver.loadThumbnail(uri, Size(px, px), null)
    } catch (_: Exception) {
        null // album has no art
    }

/** Placeholder library so the menus are usable with no music or no permission. Nothing in it plays. */
val demoLibrary: Library = run {
    val demo = listOf(
        "Kind of Blue" to "Miles Davis",
        "Discovery" to "Daft Punk",
        "In Rainbows" to "Radiohead",
        "Rumours" to "Fleetwood Mac",
        "Blonde" to "Frank Ocean",
        "Random Access Memories" to "Daft Punk",
        "Illmatic" to "Nas",
        "Currents" to "Tame Impala",
        "Abbey Road" to "The Beatles",
        "Is This It" to "The Strokes",
        "Funky Kingston" to "Toots & The Maytals",
        "Mezzanine" to "Massive Attack",
    )
    val albums = demo.mapIndexed { i, (t, a) -> Album(id = -1L - i, title = t, artist = a) }
    val tracks = albums.flatMap { album ->
        (1..4).map { n ->
            Track(
                id = album.id * 100 - n,
                title = "Track $n",
                artist = album.artist,
                albumId = album.id,
                albumTitle = album.title,
                trackNumber = n,
                durationMs = 200_000L,
                uri = null,
            )
        }
    }
    Library(albums.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() })), tracks, isDemo = true)
}
