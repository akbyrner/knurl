package app.knurl.widget

import android.content.Context
import app.knurl.data.Album
import app.knurl.data.loadArt
import app.knurl.data.loadArtFromUrl
import app.knurl.ui.KnurlController
import app.knurl.ui.cachedArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

/** Saves what is playing for the widgets (text, progress, cover, the next songs, the pins) and redraws them. */
object WidgetSync {
    private suspend fun save(c: Context, name: String, album: Album?, px: Int) = withContext(Dispatchers.IO) {
        val f = WidgetRender.file(c, name)
        val bmp = album?.let {
            cachedArt(it.id)
                ?: it.artUri?.let { uri -> loadArt(c.contentResolver, uri, px) }
                ?: it.artUrl?.let { url -> loadArtFromUrl(url) }
        }
        if (bmp == null) { f.delete(); return@withContext }
        val scaled = if (bmp.width > px) android.graphics.Bitmap.createScaledBitmap(bmp, px, px * bmp.height / bmp.width, true) else bmp
        FileOutputStream(f).use { scaled.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    suspend fun push(c: Context, controller: KnurlController) {
        val info = controller.nowPlayingInfo(System.currentTimeMillis())
        val queue = if (info != null) controller.widgetUpcoming() else emptyList()
        val e = c.getSharedPreferences("widgets", Context.MODE_PRIVATE).edit()
        e.putBoolean("has", info != null)
        e.putString("title", info?.title ?: "")
        e.putString("artist", info?.artist ?: "")
        e.putString("album", info?.albumTitle ?: "")
        e.putBoolean("playing", info?.isPlaying == true)
        e.putLong("pos", info?.positionMs ?: 0L)
        e.putLong("dur", info?.durationMs ?: 0L)
        e.putLong("stamp", System.currentTimeMillis())
        for (i in 0..2) {
            e.putString("q${i}t", queue.getOrNull(i)?.title ?: "")
            e.putString("q${i}a", queue.getOrNull(i)?.artist ?: "")
        }
        e.apply()
        save(c, "art", info?.art, 512)
        for (i in 0..2) save(c, "q$i", queue.getOrNull(i)?.art, 96)
        save(c, "p1", controller.pinnedAlbum, 160)
        save(c, "p2", controller.pinnedRecent, 160)
        WidgetRender.updateAll(c)
    }
}
