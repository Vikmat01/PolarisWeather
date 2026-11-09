package com.example.weather

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.widget.RemoteViews
import com.example.weather.R
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Four widget styles, all drawn to match the system (Honor) widgets: dark translucent card, big condensed digits. */
open class WeatherWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        runAsync { renderWidgets(context, manager, ids) }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) =
        runAsync { renderWidgets(context, manager, intArrayOf(id)) }

    private fun runAsync(block: () -> Unit) {
        val pending = goAsync()
        thread { try { block() } finally { pending.finish() } }
    }
}

/** 2: tick-ring "dial" like the system clock widget. */
class WeatherWidgetDial : WeatherWidget()

/** 3: week / hours grid like the system calendar widget. */
class WeatherWidgetCalendar : WeatherWidget()

/** 4: orange accent circle + hourly bars. */
class WeatherWidgetAccent : WeatherWidget()

private val WIDGET_CLASSES = listOf(
    WeatherWidget::class.java, WeatherWidgetDial::class.java, WeatherWidgetCalendar::class.java, WeatherWidgetAccent::class.java
)

fun saveForWidget(ctx: Context, w: Weather) {
    ctx.getSharedPreferences("weather", Context.MODE_PRIVATE).edit()
        .putString("lat", w.lat.toString()).putString("lon", w.lon.toString()).putString("wcity", w.city).apply()
    refreshWidgets(ctx)
}

fun refreshWidgets(ctx: Context) {
    val mgr = AppWidgetManager.getInstance(ctx)
    val ids = WIDGET_CLASSES.flatMap { mgr.getAppWidgetIds(ComponentName(ctx, it)).toList() }.toIntArray()
    if (ids.isNotEmpty()) thread { renderWidgets(ctx, mgr, ids) }
}

fun renderWidgets(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
    val prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
    val lat = prefs.getString("lat", null)?.toDoubleOrNull() ?: 55.7558
    val lon = prefs.getString("lon", null)?.toDoubleOrNull() ?: 37.6173
    val name = prefs.getString("wcity", "Москва") ?: "Москва"
    // The network can be briefly unavailable right after a widget is added or the phone wakes: retry a few times.
    var data: Weather? = null
    for (attempt in 0 until 4) {
        data = runCatching { runBlocking { fetchForecast(lat, lon, name) } }.getOrNull()
        if (data != null) break
        Thread.sleep(2000L)
    }
    if (data == null) return
    val f = prefs.getBoolean("f", false)
    val ms = prefs.getBoolean("ms", false)
    val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val land = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val density = ctx.resources.displayMetrics.density

    for (id in ids) {
        val style = when (mgr.getAppWidgetInfo(id)?.provider?.className?.substringAfterLast('.')) {
            "WeatherWidgetDial" -> 1
            "WeatherWidgetCalendar" -> 2
            "WeatherWidgetAccent" -> 3
            else -> 0
        }
        val o = mgr.getAppWidgetOptions(id)
        val wdp = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 146).coerceAtLeast(100)
        val cellH = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 146).coerceAtLeast(40)
        val slim = cellH < 100            // 2x1 strip: trimmed a bit at the top and bottom
        val inset = if (slim) 9 else 0    // dp
        val hdp = cellH - 2 * inset
        var wpx = wdp * density
        var hpx = hdp * density
        val k = minOf(1f, sqrt(800_000f / (wpx * hpx)))   // keep the bitmap small enough for RemoteViews
        wpx *= k; hpx *= k
        val bmp = Bitmap.createBitmap(wpx.toInt().coerceAtLeast(1), hpx.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(bmp.width / wdp.toFloat(), bmp.height / hdp.toFloat())   // draw in dp
        drawWidget(c, data, f, ms, style, wdp.toFloat(), hdp.toFloat())
        val rv = RemoteViews(ctx.packageName, R.layout.widget_weather)
        rv.setImageViewBitmap(R.id.widget_img, bmp)
        rv.setInt(R.id.widget_root, "setBackgroundResource", if (slim) R.drawable.widget_bg_20 else R.drawable.widget_bg_26)
        val ip = (inset * density).toInt()
        rv.setViewPadding(R.id.widget_root, 0, ip, 0, ip)
        rv.setOnClickPendingIntent(R.id.widget_root, open)
        mgr.updateAppWidget(id, rv)
    }
}

// ---------- Drawing ----------

private val ACCENT = Color.rgb(224, 90, 60)
private val GRAY = Color.rgb(154, 154, 162)
private val SOFT = Color.rgb(207, 210, 214)
private val cond: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
private fun wt(weight: Int): Typeface = Typeface.create(Typeface.DEFAULT, weight, false)

private fun paint(size: Float, color: Int, tf: Typeface = Typeface.DEFAULT, align: Paint.Align = Paint.Align.LEFT) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = tf; textAlign = align }

private fun fit(t: String, p: Paint, maxW: Float) = t.take(p.breakText(t, true, maxW, null))

/** Shrinks the text size until the string fits maxW. */
private fun fitP(t: String, p: Paint, maxW: Float): Paint {
    val w = p.measureText(t)
    if (w > maxW) p.textSize *= maxW / w
    return p
}

private val iconPaths by lazy {
    IconKind.values().associateWith { k -> k.paths.map { androidx.core.graphics.PathParser.createPathFromPathData(it) } }
}

private fun icon(c: Canvas, kind: IconKind, x: Float, y: Float, size: Float, tint: Int? = null) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = tint ?: kind.tint.toInt()
    }
    c.save(); c.translate(x, y); c.scale(size / 24f, size / 24f)
    iconPaths[kind]?.forEach { c.drawPath(it, p) }
    c.restore()
}

/** Partly cloudy = small sun peeking out behind a cloud; clear = sun/moon; otherwise the usual icon. */
private fun weatherIcon(c: Canvas, w: Weather, x: Float, y: Float, size: Float, tint: Int? = null) {
    if (w.code in 1..2) {
        icon(c, if (w.isDay) IconKind.SUN else IconKind.MOON, x + size * 0.18f, y - size * 0.12f, size * 0.62f, tint)
        icon(c, IconKind.CLOUD, x - size * 0.02f, y + size * 0.14f, size * 0.86f, tint)
    } else icon(c, kindFor(w.code, w.isDay), x, y, size, tint)
}

/** Share of the day that has passed (0..1) from the city-local time "yyyy-MM-ddTHH:mm". */
private fun dayFraction(w: Weather): Float {
    val t = w.localTime
    val h = t.substring(11, 13).toIntOrNull() ?: 12
    val m = t.substring(14, 16).toIntOrNull() ?: 0
    return (h * 60 + m) / 1440f
}

private fun hourlyTemps(w: Weather, n: Int) = w.hours.filter { it.event == 0 }.take(n)

private fun drawWidget(c: Canvas, w: Weather, f: Boolean, ms: Boolean, style: Int, W: Float, H: Float) {
    // The translucent dark card (tint + hairline) is a native drawable on the widget root; only the content is drawn here.
    val compact = H < 100f || W > H * 2.1f
    if (compact) {
        val k = (H / 72f).coerceIn(1f, 2.4f)
        c.save(); c.scale(k, k)
        when (style) {
            1 -> compactDial(c, w, f, W / k, H / k)
            2 -> compactCalendar(c, w, f, W / k, H / k)
            3 -> compactAccent(c, w, f, W / k, H / k)
            else -> compactSystem(c, w, f, W / k, H / k)
        }
        c.restore()
    } else {
        val k = (min(W, H) / 170f).coerceIn(0.8f, 2.4f)
        c.save(); c.scale(k, k)
        when (style) {
            1 -> bigDial(c, w, f, W / k, H / k)
            2 -> bigCalendar(c, w, f, ms, W / k, H / k)
            3 -> bigAccent(c, w, f, W / k, H / k)
            else -> bigSystem(c, w, f, W / k, H / k)
        }
        c.restore()
    }
}

// ---- 1: like the system widgets ----

private fun bigSystem(c: Canvas, w: Weather, f: Boolean, W: Float, H: Float) {
    val pad = 15f
    c.drawText(fit(w.city, paint(12f, GRAY, wt(500)), W - 2 * pad), pad, 28f, paint(12f, GRAY, wt(500)))
    val tp = paint(min(75f, H * 0.44f), Color.WHITE, cond)
    c.drawText(temp(w.temp, f), pad - 2f, H * 0.57f, tp)
    weatherIcon(c, w, pad, H * 0.69f, 24f)
    val dp = fitP(describe(w.code), paint(12f, Color.WHITE), W - pad - 44f - pad)
    c.drawText(describe(w.code), pad + 34f, H * 0.69f + 17f, dp)
    c.drawText("Ощущается ${temp(w.feels, f)}", pad, H - 14f, paint(10.5f, GRAY))
}

private fun compactSystem(c: Canvas, w: Weather, f: Boolean, W: Float, H: Float) {
    val pad = 13f
    val ts = min(46f, H * 0.62f)
    val total = ts * 0.72f + 6f + 10f
    val top = (H - total) / 2f
    val base = top + ts * 0.72f
    c.drawText(temp(w.temp, f), pad - 1f, base, paint(ts, Color.WHITE, cond))
    c.drawText(fit(w.city, paint(10f, GRAY, wt(500)), W / 2f), pad, base + 6f + 9f, paint(10f, GRAY, wt(500)))
    val isz = min(H * 0.5f, 34f)
    weatherIcon(c, w, W - pad - isz, (H - isz) / 2f, isz)
}

// ---- 2: dial ----

private fun bigDial(c: Canvas, w: Weather, f: Boolean, W: Float, H: Float) {
    val cx = W / 2f; val cy = H / 2f
    val half = min(W, H) / 2f
    val done = dayFraction(w) * 60f
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    for (i in 0 until 60) {
        val a = Math.toRadians((i * 6 - 90).toDouble())
        val cs = cos(a).toFloat(); val sn = sin(a).toFloat()
        val m = 1f / max(abs(cs), abs(sn))
        val ln = (if (i % 5 == 0) 7f else 4f) * m * 0.8f
        val ra = (half - 13f) * m * 0.97f
        val rb = ra - ln
        p.color = Color.argb(if (i <= done) 255 else 90, 255, 255, 255)
        p.strokeWidth = if (i % 5 == 0) 2f else 1.5f
        c.drawLine(cx + cs * ra, cy + sn * ra, cx + cs * rb, cy + sn * rb, p)
    }
    c.drawText(fit(w.city, paint(11f, GRAY, wt(500)), W * 0.5f), cx, cy - 38f, paint(11f, GRAY, wt(500), Paint.Align.CENTER))
    c.drawText(temp(w.temp, f), cx, cy + 20f, paint(min(62f, half * 0.74f), Color.WHITE, cond, Paint.Align.CENTER))
    weatherIcon(c, w, cx - 11f, cy + 29f, 22f)
}

private fun compactDial(c: Canvas, w: Weather, f: Boolean, W: Float, H: Float) {
    val pad = 15f
    val n = 30
    val done = dayFraction(w) * n
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 1.5f }
    for (i in 0 until n) {
        val x = pad + i * (W - 2 * pad) / (n - 1)
        p.color = Color.argb(if (i <= done) 255 else 90, 255, 255, 255)
        c.drawLine(x, 8f, x, if (i % 5 == 0) 15f else 12.5f, p)
    }
    val ts = min(38f, H * 0.5f)
    c.drawText(temp(w.temp, f), pad - 1f, H - 22f - 2f + ts * 0.15f, paint(ts, Color.WHITE, cond))
    val sub = "${w.city} · ${describe(w.code).lowercase()}"
    c.drawText(fit(sub, paint(9.5f, GRAY), W - 2 * pad - 50f), pad, H - 9f, paint(9.5f, GRAY))
    val isz = min(28f, H * 0.38f)
    weatherIcon(c, w, W - pad - isz, H / 2f - isz * 0.4f, isz)
}

// ---- 3: calendar ----

private fun bigCalendar(c: Canvas, w: Weather, f: Boolean, ms: Boolean, W: Float, H: Float) {
    val pad = 14f
    c.drawText(fit(w.city, paint(13f, Color.WHITE, wt(600)), W * 0.6f), pad, 27f, paint(13f, Color.WHITE, wt(600)))
    c.drawText(temp(w.temp, f), W - pad, 27f, paint(13f, Color.WHITE, wt(600), Paint.Align.RIGHT))
    val n = min(7, w.days.size)
    val colW = (W - 2 * pad) / n
    for (i in 0 until n) {
        val d = w.days[i]
        val cx = pad + colW * (i + 0.5f)
        if (i == 0) c.drawCircle(cx, 49f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
        c.drawText(weekday(d.date), cx, 53f, paint(9.5f, if (i == 0) Color.WHITE else GRAY, wt(700), Paint.Align.CENTER))
        icon(c, kindFor(d.code, true), cx - 7f, 60f, 14f)
        c.drawText(temp(d.max, f), cx, 93f, paint(11f, Color.WHITE, wt(600), Paint.Align.CENTER))
        c.drawText(temp(d.min, f), cx, 107f, paint(9.5f, GRAY, align = Paint.Align.CENTER))
    }
    c.drawLine(pad, H - 46f, W - pad, H - 46f, Paint().apply { color = Color.argb(31, 255, 255, 255); strokeWidth = 1f })
    val line = "${describe(w.code)} · ветер ${windText(w.wind, ms)} ${windDirName(w.windDir)}"
    c.drawText(line, pad, H - 26f, fitP(line, paint(11f, SOFT), W - 2 * pad))
    c.drawText("Ощущается ${temp(w.feels, f)}", pad, H - 11f, paint(9.5f, GRAY))
}

private fun compactCalendar(c: Canvas, w: Weather, f: Boolean, W: Float, H: Float) {
    val items = hourlyTemps(w, 5)
    val n = max(1, items.size)
    val pad = 10f
    val cw = (W - 2 * pad) / n
    val top = (H - 62f) / 2f
    for ((i, h) in items.withIndex()) {
        val cx = pad + cw * (i + 0.5f)
        c.drawText(h.label, cx, top + 10f, paint(8.5f, GRAY, wt(700), Paint.Align.CENTER))
        icon(c, kindFor(h.code, h.isDay), cx - 8f, top + 17f, 16f)
        c.drawText(temp(h.temp, f), cx, top + 53f, paint(12f, Color.WHITE, wt(700), Paint.Align.CENTER))
    }
}

// ---- 4: accent ----

private fun bigAccent(c: Canvas, w: Weather, f: Boolean, W: Float, H: Float) {
    val pad = 15f
    c.drawText(fit(w.city, paint(12f, GRAY, wt(500)), W - 2 * pad - 60f), pad, 28f, paint(12f, GRAY, wt(500)))
    c.drawCircle(W - pad - 23f, 41f, 23f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
    weatherIcon(c, w, W - pad - 23f - 12f, 41f - 12f, 24f, Color.WHITE)
    c.drawText(temp(w.temp, f), pad - 2f, H * 0.59f, paint(min(70f, H * 0.41f), Color.WHITE, cond))
    val desc = "${describe(w.code)} · ощущ. ${temp(w.feels, f)}"
    c.drawText(desc, pad, H * 0.59f + 22f, fitP(desc, paint(11f, SOFT), W - 2 * pad))
    val hs = hourlyTemps(w, 12)
    if (hs.isNotEmpty()) {
        val lo = hs.minOf { it.temp }; val hi = hs.maxOf { it.temp }
        val bw = (W - 2 * pad) / hs.size
        for ((i, h) in hs.withIndex()) {
            val bh = 13f + (if (hi - lo < 0.01) 0.5f else ((h.temp - lo) / (hi - lo)).toFloat()) * 26f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (i == 0) ACCENT else Color.argb(56, 255, 255, 255) }
            c.drawRoundRect(RectF(pad + i * bw, H - 15f - bh, pad + (i + 1) * bw - 3f, H - 15f), 3f, 3f, p)
        }
    }
}

private fun compactAccent(c: Canvas, w: Weather, f: Boolean, W: Float, H: Float) {
    val pad = 13f
    val ts = min(46f, H * 0.62f)
    val total = ts * 0.72f + 6f + 10f
    val top = (H - total) / 2f
    val base = top + ts * 0.72f
    c.drawText(temp(w.temp, f), pad - 1f, base, paint(ts, Color.WHITE, cond))
    c.drawText(fit(w.city, paint(10f, GRAY, wt(500)), W / 2f), pad, base + 6f + 9f, paint(10f, GRAY, wt(500)))
    val r = min(20f, H * 0.3f)
    c.drawCircle(W - pad - r, H / 2f, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
    weatherIcon(c, w, W - pad - r - r * 0.55f, H / 2f - r * 0.55f, r * 1.1f, Color.WHITE)
}
