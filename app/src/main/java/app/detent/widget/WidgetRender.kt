package app.detent.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.widget.RemoteViews
import app.detent.MainActivity
import app.detent.R
import java.io.File

/** What the widgets draw, as last saved by [WidgetSync]. Widgets read this so they work even when the app process is new. */
class WidgetState(
    val has: Boolean,
    val title: String,
    val artist: String,
    val album: String,
    val playing: Boolean,
    val fraction: Float,
    val positionMs: Long,
    val durationMs: Long,
    val queue: List<Pair<String, String>>,
)

private val ORANGE = Color.parseColor("#FF6A1A")

object WidgetRender {
    const val ACTION = "app.detent.WIDGET_ACTION"
    const val EXTRA_WHAT = "what"
    const val EXTRA_PIN = "pin"

    private fun prefs(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    fun file(c: Context, name: String) = File(c.filesDir, "widget_$name.png")

    fun readState(c: Context): WidgetState {
        val p = prefs(c)
        val dur = p.getLong("dur", 0L)
        var pos = p.getLong("pos", 0L)
        val playing = p.getBoolean("playing", false)
        // Wall-clock time since it was saved, so a widget redrawn later still shows about the right spot.
        if (playing) pos += System.currentTimeMillis() - p.getLong("stamp", System.currentTimeMillis())
        pos = pos.coerceIn(0L, if (dur > 0) dur else pos)
        return WidgetState(
            has = p.getBoolean("has", false),
            title = p.getString("title", "") ?: "",
            artist = p.getString("artist", "") ?: "",
            album = p.getString("album", "") ?: "",
            playing = playing,
            fraction = if (dur > 0) pos.toFloat() / dur else 0f,
            positionMs = pos,
            durationMs = dur,
            queue = (0..2).mapNotNull { i ->
                p.getString("q${i}t", null)?.takeIf { it.isNotEmpty() }?.let { it to (p.getString("q${i}a", "") ?: "") }
            },
        )
    }

    private fun bitmap(c: Context, name: String): Bitmap? = file(c, name).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    private fun clock(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(t / 60, t % 60)
    }

    private fun openApp(c: Context, extra: Pair<String, Int>? = null, code: Int = 0): PendingIntent {
        val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        extra?.let { i.putExtra(it.first, it.second) }
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(c: Context, what: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, code,
            Intent(c, WidgetActionReceiver::class.java).setAction(ACTION).putExtra(EXTRA_WHAT, what),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // --- drawing ---------------------------------------------------------------------------------

    private fun rounded(src: Bitmap, px: Int, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), radius, radius, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        val side = minOf(src.width, src.height)
        val from = android.graphics.Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
        c.drawBitmap(src, from, RectF(0f, 0f, px.toFloat(), px.toFloat()), p)
        return out
    }

    private fun placeholder(px: Int, radius: Float, letter: String): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, px.toFloat(), px.toFloat(), Color.parseColor("#3FAE8C"), Color.parseColor("#154A44"), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), radius, radius, p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 255, 255, 255); textSize = px * 0.4f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        c.drawText(letter.take(1).uppercase(), px / 2f, px / 2f + t.textSize * 0.35f, t)
        return out
    }

    private fun cover(c: Context, name: String, px: Int, radius: Float, letter: String): Bitmap =
        bitmap(c, name)?.let { rounded(it, px, radius) } ?: placeholder(px, radius, letter)

    private fun steelShader(cx: Float, cy: Float) = SweepGradient(
        cx, cy,
        intArrayOf(Color.parseColor("#ECEEF1"), Color.parseColor("#8E9198"), Color.parseColor("#E4E6E9"), Color.parseColor("#7F8289"), Color.parseColor("#ECEEF1")),
        floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
    )

    private fun glyph(c: Canvas, cx: Float, cy: Float, size: Float, playing: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#18181B") }
        if (playing) {
            val w = size * 0.28f
            c.drawRoundRect(RectF(cx - size * 0.38f, cy - size / 2, cx - size * 0.38f + w, cy + size / 2), w / 4, w / 4, p)
            c.drawRoundRect(RectF(cx + size * 0.38f - w, cy - size / 2, cx + size * 0.38f, cy + size / 2), w / 4, w / 4, p)
        } else {
            c.drawPath(Path().apply { moveTo(cx - size * 0.36f, cy - size / 2); lineTo(cx - size * 0.36f, cy + size / 2); lineTo(cx + size * 0.5f, cy); close() }, p)
        }
    }

    /** The steel play/pause button. */
    private fun steel(px: Int, playing: Boolean): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = px / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = steelShader(r, r)
        c.drawCircle(r, r, r - px * 0.04f, p)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = px * 0.02f; p.color = Color.argb(100, 255, 255, 255)
        c.drawCircle(r, r, r - px * 0.05f, p)
        glyph(c, r, r, px * 0.36f, playing)
        return out
    }

    /** The knob: an orange arc round the steel disc shows how far into the song it is. */
    private fun knob(px: Int, fraction: Float, playing: Boolean): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = px / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(115, 0, 0, 0)
        c.drawCircle(r, r, r - 1f, p)
        val ring = px * 0.058f
        val box = RectF(r - r * 0.81f, r - r * 0.81f, r + r * 0.81f, r + r * 0.81f)
        p.style = Paint.Style.STROKE; p.strokeWidth = ring; p.strokeCap = Paint.Cap.ROUND
        p.color = Color.argb(56, 255, 255, 255)
        c.drawArc(box, 0f, 360f, false, p)
        p.color = ORANGE
        if (fraction > 0.005f) c.drawArc(box, -90f, 360f * fraction.coerceIn(0f, 1f), false, p)
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        p.shader = steelShader(r, r)
        c.drawCircle(r, r, r * 0.62f, p)
        p.shader = null
        glyph(c, r, r, px * 0.23f, playing)
        return out
    }

    private fun mark(px: Int): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = px / 20f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.parseColor("#A4A7AE"); c.drawCircle(10 * s, 10 * s, 9 * s, p)
        p.color = Color.parseColor("#18181B"); c.drawCircle(10 * s, 10 * s, 4 * s, p)
        p.color = ORANGE; c.drawRoundRect(RectF(9 * s, 1.5f * s, 11 * s, 6.5f * s), s, s, p)
        return out
    }

    private fun dp(c: Context, v: Float) = (v * c.resources.displayMetrics.density)

    private fun fillText(rv: RemoteViews, s: WidgetState) {
        rv.setTextViewText(R.id.title, if (s.has) s.title else "Nothing playing")
        rv.setTextViewText(R.id.artist, if (s.has) s.artist else "Open Knurl to pick something")
    }

    private fun transport(c: Context, rv: RemoteViews, s: WidgetState) {
        rv.setImageViewBitmap(R.id.play, steel(dp(c, 46f).toInt() * 2, s.playing))
        rv.setOnClickPendingIntent(R.id.play, action(c, "toggle", 1))
        rv.setOnClickPendingIntent(R.id.prev, action(c, "prev", 2))
        rv.setOnClickPendingIntent(R.id.next, action(c, "next", 3))
    }

    // --- the four widgets -------------------------------------------------------------------------

    fun medium(c: Context): RemoteViews {
        val s = readState(c)
        val rv = RemoteViews(c.packageName, R.layout.widget_now_medium)
        fillText(rv, s)
        rv.setImageViewBitmap(R.id.art, cover(c, "art", dp(c, 96f).toInt() * 2, dp(c, 14f) * 2, s.album.ifEmpty { s.title }))
        rv.setImageViewBitmap(R.id.mark, mark(dp(c, 14f).toInt() * 2))
        rv.setProgressBar(R.id.progress, 1000, (s.fraction * 1000).toInt(), false)
        transport(c, rv, s)
        rv.setOnClickPendingIntent(R.id.root, openApp(c, code = 10))
        return rv
    }

    fun large(c: Context): RemoteViews {
        val s = readState(c)
        val rv = RemoteViews(c.packageName, R.layout.widget_now_large)
        rv.setTextViewText(R.id.title, if (s.has) s.title else "Nothing playing")
        rv.setTextViewText(R.id.artist, if (s.has) "${s.artist} · ${s.album}" else "Open Knurl to pick something")
        rv.setImageViewBitmap(R.id.art, cover(c, "art", dp(c, 120f).toInt() * 2, dp(c, 14f) * 2, s.album.ifEmpty { s.title }))
        rv.setImageViewBitmap(R.id.mark, mark(dp(c, 14f).toInt() * 2))
        rv.setProgressBar(R.id.progress, 1000, (s.fraction * 1000).toInt(), false)
        rv.setTextViewText(R.id.pos, clock(s.positionMs))
        rv.setTextViewText(R.id.left, "-" + clock(s.durationMs - s.positionMs))
        transport(c, rv, s)
        val ids = listOf(Triple(R.id.q0, R.id.q0t, R.id.q0a), Triple(R.id.q1, R.id.q1t, R.id.q1a), Triple(R.id.q2, R.id.q2t, R.id.q2a))
        val thumbs = listOf(R.id.q0i, R.id.q1i, R.id.q2i)
        ids.forEachIndexed { i, (row, t, a) ->
            val item = s.queue.getOrNull(i)
            rv.setViewVisibility(row, if (item != null) android.view.View.VISIBLE else android.view.View.INVISIBLE)
            if (item != null) {
                rv.setTextViewText(t, item.first)
                rv.setTextViewText(a, item.second)
                rv.setImageViewBitmap(thumbs[i], cover(c, "q$i", dp(c, 30f).toInt() * 2, dp(c, 6f) * 2, item.first))
            }
        }
        rv.setOnClickPendingIntent(R.id.root, openApp(c, code = 11))
        return rv
    }

    fun small(c: Context, options: android.os.Bundle?): RemoteViews {
        val s = readState(c)
        val rv = RemoteViews(c.packageName, R.layout.widget_now_small)
        fillText(rv, s)
        val density = c.resources.displayMetrics.density
        val w = (((options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0).takeIf { it > 0 } ?: 110) * density * 1.5f).toInt()
        val h = (((options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0).takeIf { it > 0 } ?: 110) * density * 1.5f).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val radius = dp(c, 26f) * 1.5f
        val art = bitmap(c, "art")
        val clip = Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW) }
        cv.save(); cv.clipPath(clip)
        if (art != null) {
            // Fill the widget with the cover, centred and cropped.
            val scale = maxOf(w.toFloat() / art.width, h.toFloat() / art.height)
            val dw = art.width * scale; val dh = art.height * scale
            cv.drawBitmap(art, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            cv.drawColor(Color.parseColor("#1F4B44"))
        }
        val shade = Paint().apply { shader = LinearGradient(0f, h * 0.3f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(190, 0, 0, 0), Shader.TileMode.CLAMP) }
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
        cv.restore()
        val kn = (dp(c, 52f) * 1.5f).toInt()
        val knob = knob(kn, s.fraction, s.playing)
        cv.drawBitmap(knob, w - kn - dp(c, 10f) * 1.5f, dp(c, 10f) * 1.5f, null)
        rv.setImageViewBitmap(R.id.face, out)
        rv.setOnClickPendingIntent(R.id.play, action(c, "toggle", 1))
        rv.setOnClickPendingIntent(R.id.root, openApp(c, code = 12))
        return rv
    }

    private fun pinTile(c: Context, i: Int, px: Int): Bitmap {
        val r = dp(c, 9f) * 2
        return when (i) {
            0 -> Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b ->
                val cv = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.shader = LinearGradient(0f, 0f, px.toFloat(), px.toFloat(), intArrayOf(Color.parseColor("#4F46E5"), Color.parseColor("#9333EA"), Color.parseColor("#DB2777")), null, Shader.TileMode.CLAMP)
                cv.drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), r, r, p)
                p.shader = null; p.color = Color.WHITE
                val s = px / 72f * 0.55f
                cv.save(); cv.translate(px / 2f - 36f * s, px / 2f - 36f * s); cv.scale(s, s)
                cv.drawPath(androidx.core.graphics.PathParser.createPathFromPathData("M36 51C22 41 19 34 19 29a8.5 8.5 0 0 1 17-3 8.5 8.5 0 0 1 17 3c0 5-3 12-17 22z"), p)
                cv.restore()
            }
            1 -> cover(c, "p1", px, r, "A")
            2 -> cover(c, "p2", px, r, "R")
            else -> Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b ->
                val cv = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.color = Color.parseColor("#2F2F35"); cv.drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), r, r, p)
                p.color = Color.parseColor("#D0D0D4"); p.style = Paint.Style.STROKE; p.strokeWidth = px * 0.05f
                cv.drawRoundRect(RectF(px * 0.36f, px * 0.24f, px * 0.64f, px * 0.76f), px * 0.06f, px * 0.06f, p)
            }
        }
    }

    fun pinned(c: Context): RemoteViews {
        val rv = RemoteViews(c.packageName, R.layout.widget_pinned)
        rv.setImageViewBitmap(R.id.mark, mark(dp(c, 12f).toInt() * 2))
        val ids = listOf(R.id.p0, R.id.p1, R.id.p2, R.id.p3)
        ids.forEachIndexed { i, id ->
            rv.setImageViewBitmap(id, pinTile(c, i, dp(c, 56f).toInt() * 2))
            rv.setOnClickPendingIntent(id, openApp(c, EXTRA_PIN to i, code = 20 + i))
        }
        rv.setOnClickPendingIntent(R.id.root, openApp(c, code = 19))
        return rv
    }

    fun providers(c: Context) = listOf(WidgetNowSmall::class.java, WidgetPinned::class.java, WidgetNowMedium::class.java, WidgetNowLarge::class.java)

    fun updateAll(c: Context) {
        val mgr = AppWidgetManager.getInstance(c)
        for (cls in providers(c)) {
            val ids = mgr.getAppWidgetIds(ComponentName(c, cls))
            if (ids.isNotEmpty()) update(c, mgr, cls, ids)
        }
    }

    fun update(c: Context, mgr: AppWidgetManager, cls: Class<*>, ids: IntArray) {
        for (id in ids) {
            val rv = when (cls) {
                WidgetNowSmall::class.java -> small(c, mgr.getAppWidgetOptions(id))
                WidgetPinned::class.java -> pinned(c)
                WidgetNowMedium::class.java -> medium(c)
                else -> large(c)
            }
            mgr.updateAppWidget(id, rv)
        }
    }

    @Suppress("unused")
    private val unused = SystemClock.elapsedRealtime()
}
