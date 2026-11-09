package com.example.weather

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

// Radar: Rain Viewer free tier (past 2 h, zoom <= 7, ~100 requests/min per IP).
// Base map: Esri Dark Gray (no key) + labels; falls back to OpenStreetMap (darkened) if Esri is unreachable.

data class Frame(val time: Long, val path: String)

private class RateGate(private val perMinute: Int) {
    private val times = ArrayDeque<Long>()
    private val mutex = Mutex()
    suspend fun acquire() {
        mutex.withLock {
            while (true) {
                val now = System.currentTimeMillis()
                while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
                if (times.size < perMinute) { times.addLast(now); return }
                delay(60_000 - (now - times.first()) + 50)
            }
        }
    }
}

private object Tiles {
    private val cache = LruCache<String, Bitmap>(220)
    private val gate = Semaphore(6)        // base map + labels
    private val radarGate = Semaphore(3)   // radar never starves the base map
    private val rate = RateGate(85)   // stay under the free-tier limit
    private val failed = ConcurrentHashMap<String, Long>()
    // Downloads live in their own scope: scrubbing the slider cancels UI coroutines, but never a download in flight.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap<String, Deferred<Bitmap?>>()
    /** Bumped whenever a tile arrives, so the canvas redraws. */
    var version by mutableIntStateOf(0)

    fun peek(url: String): Bitmap? = cache.get(url)

    suspend fun get(url: String, radar: Boolean): Bitmap? {
        cache.get(url)?.let { return it }
        if ((failed[url] ?: 0L) > System.currentTimeMillis() - 8_000) return null
        return inflight.computeIfAbsent(url) { scope.async { load(url, radar) } }.await()
    }

    private suspend fun load(url: String, radar: Boolean): Bitmap? {
        try {
            if (radar) rate.acquire()   // wait for the rate budget BEFORE taking a download slot
            return (if (radar) radarGate else gate).withPermit {
                val bmp = runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "WeatherApp/1.0 (personal use)")
                    conn.connectTimeout = 8000; conn.readTimeout = 8000
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                if (bmp != null) { cache.put(url, bmp); version++ } else failed[url] = System.currentTimeMillis()
                bmp
            }
        } finally {
            inflight.remove(url)
        }
    }
}

private suspend fun fetchFrames(): Pair<String, List<Frame>>? = withContext(Dispatchers.IO) {
    runCatching {
        val j = JSONObject(httpGetAny("frames", listOf("https://api.rainviewer.com/public/weather-maps.json")))
        val past = j.getJSONObject("radar").getJSONArray("past")
        val all = List(past.length()) {
            val o = past.getJSONObject(it); Frame(o.getLong("time"), o.getString("path"))
        }
        j.getString("host") to all.takeLast(7)   // last hour, 10-minute steps
    }.getOrNull()
}

private fun mercX(lon: Double) = (lon + 180.0) / 360.0
private fun mercY(lat: Double): Double {
    val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
}
private fun wrapX(x: Int, n: Int) = ((x % n) + n) % n
private fun esriBase(z: Int, x: Int, y: Int) =
    "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/$z/$y/$x"
private fun esriLabels(z: Int, x: Int, y: Int) =
    "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/$z/$y/$x"
private fun osmUrl(z: Int, x: Int, y: Int) = "https://tile.openstreetmap.org/$z/$x/$y.png"
private val darkFilter = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(-0.6f, 0f, 0f, 0f, 153f, 0f, -0.6f, 0f, 0f, 153f, 0f, 0f, -0.6f, 0f, 153f, 0f, 0f, 0f, 1f, 0f))
)
private fun radarUrl(host: String, f: Frame, z: Int, x: Int, y: Int) = "$host${f.path}/512/$z/$x/$y/2/1_1.png"

@Composable
fun RadarMap(lat: Double, lon: Double, city: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val backdrop = rememberLayerBackdrop {
        drawRect(Color(0xFF0B0714))
        drawContent()
    }
    var cx by remember { mutableDoubleStateOf(mercX(lon)) }
    var cy by remember { mutableDoubleStateOf(mercY(lat)) }
    var zoom by remember { mutableFloatStateOf(6f) }
    var view by remember { mutableStateOf(IntSize.Zero) }
    var tick by remember { mutableIntStateOf(0) }
    var host by remember { mutableStateOf("") }
    var frames by remember { mutableStateOf<List<Frame>>(emptyList()) }
    var idx by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    /** Tiles on screen. extra = 1 gives the sharper base-map level (4x tiles, half the size on screen). */
    fun visible(extra: Int = 0): List<Triple<Int, Int, Int>> {
        if (view.width == 0) return emptyList()
        val z = floor(zoom).toInt() + extra
        val n = 1 shl z
        val tilePx = 256f * density * 2f.pow(zoom - z)
        val tx = cx * n; val ty = cy * n
        val x0 = floor(tx - view.width / 2f / tilePx).toInt()
        val x1 = floor(tx + view.width / 2f / tilePx).toInt()
        val y0 = max(0, floor(ty - view.height / 2f / tilePx).toInt())
        val y1 = min(n - 1, floor(ty + view.height / 2f / tilePx).toInt())
        return buildList { for (y in y0..y1) for (x in x0..x1) add(Triple(z, x, y)) }
    }

    LaunchedEffect(Unit) {
        val r = fetchFrames()
        if (r == null) failed = true else { host = r.first; frames = r.second; idx = r.second.lastIndex }
    }

    // load missing tiles for what is on screen
    val vis = visible()
    val visB = visible(1)
    val key = "${vis.firstOrNull()}${vis.lastOrNull()}${visB.firstOrNull()}${visB.lastOrNull()}$idx${frames.size}"
    LaunchedEffect(key) {
        for ((z, x, y) in visB) {
            val xm = wrapX(x, 1 shl z)
            launch { if (Tiles.get(esriBase(z, xm, y), false) == null) Tiles.get(osmUrl(z, xm, y), false); tick++ }
            launch { Tiles.get(esriLabels(z, xm, y), false); tick++ }
        }
        for ((z, x, y) in vis) {
            val xm = wrapX(x, 1 shl z)
            frames.getOrNull(idx)?.let { f -> launch { Tiles.get(radarUrl(host, f, z, xm, y), true); tick++ } }
        }
    }

    // prefetch the other frames for the visible area (nearest to the current one first), so scrubbing is instant
    val prefetchKey = "${vis.firstOrNull()}${vis.lastOrNull()}${frames.size}"
    LaunchedEffect(prefetchKey) {
        if (frames.isEmpty()) return@LaunchedEffect
        delay(900)   // only once the view has settled: no prefetch storm while panning/zooming
        val order = frames.indices.sortedBy { kotlin.math.abs(it - idx) }
        for (i in order) for ((z, x, y) in vis) {
            launch { Tiles.get(radarUrl(host, frames[i], z, wrapX(x, 1 shl z), y), true) }
        }
    }

    // play: preload the next frame's tiles, then show it
    LaunchedEffect(playing, frames) {
        while (playing && frames.isNotEmpty()) {
            val next = (idx + 1) % frames.size
            val f = frames[next]
            coroutineScope {
                visible().map { (z, x, y) -> async { Tiles.get(radarUrl(host, f, z, wrapX(x, 1 shl z), y), true) } }.awaitAll()
            }
            tick++
            idx = next
            delay(if (next == frames.lastIndex) 1400L else 450L)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0714))) {
        Canvas(
            Modifier.fillMaxSize().layerBackdrop(backdrop)
                .onSizeChanged { view = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        zoom = (zoom + log2(zoomChange)).coerceIn(3f, 7.99f)   // free radar tiles end at zoom 7
                        val world = 256f * density * 2f.pow(zoom)
                        cx = ((cx - pan.x / world) % 1.0 + 1.0) % 1.0
                        cy = (cy - pan.y / world).coerceIn(0.02, 0.98)
                    }
                }
        ) {
            tick.let { }; Tiles.version.let { }   // redraw whenever a tile arrives
            fun at(z: Int, x: Int, y: Int): IntOffset {
                val n = 1 shl z
                val tp = 256f * density * 2f.pow(zoom - z)
                return IntOffset(
                    (view.width / 2f + ((x - cx * n) * tp).toFloat()).roundToInt(),
                    (view.height / 2f + ((y - cy * n) * tp).toFloat()).roundToInt()
                )
            }
            fun sz(z: Int): IntSize {
                val d = ceil(256f * density * 2f.pow(zoom - z)).toInt() + 1
                return IntSize(d, d)
            }
            val frame = frames.getOrNull(idx)
            /** Draws the tile, or - while it is still loading - the matching part of a coarser tile, so the map never goes blank. */
            fun tile(z: Int, x: Int, y: Int, srcs: List<Pair<(Int, Int, Int) -> String, ColorFilter?>>, alpha: Float = 1f): Boolean {
                val xm = wrapX(x, 1 shl z)
                for (k in 0..3) {
                    val pz = z - k
                    if (pz < 0) break
                    for ((urlOf, filter) in srcs) {
                        val bmp = Tiles.peek(urlOf(pz, xm shr k, y shr k)) ?: continue
                        if (k == 0) drawImage(bmp.asImageBitmap(), dstOffset = at(z, x, y), dstSize = sz(z), alpha = alpha, colorFilter = filter)
                        else {
                            val m = (1 shl k) - 1
                            val sw = bmp.width shr k; val sh = bmp.height shr k
                            drawImage(
                                bmp.asImageBitmap(), srcOffset = IntOffset((xm and m) * sw, (y and m) * sh), srcSize = IntSize(sw, sh),
                                dstOffset = at(z, x, y), dstSize = sz(z), alpha = alpha, colorFilter = filter
                            )
                        }
                        return true
                    }
                }
                return false
            }
            // 1) base map
            val baseSrcs = listOf<Pair<(Int, Int, Int) -> String, ColorFilter?>>(::esriBase to null, ::osmUrl to darkFilter)
            for ((z, x, y) in visible(1)) tile(z, x, y, baseSrcs)
            // 2) radar: the exact frame if loaded, otherwise the nearest loaded frame (never blank)
            if (frame != null) {
                for ((z, x, y) in visible()) {
                    for (d in 0 until frames.size) {
                        var drawn = false
                        for (i in (if (d == 0) listOf(idx) else listOf(idx - d, idx + d))) {
                            val f = frames.getOrNull(i) ?: continue
                            val srcs = listOf<Pair<(Int, Int, Int) -> String, ColorFilter?>>(
                                { zz: Int, xx: Int, yy: Int -> radarUrl(host, f, zz, xx, yy) } to null
                            )
                            if (tile(z, x, y, srcs, 0.8f)) { drawn = true; break }
                        }
                        if (drawn) break
                    }
                }
            }
            // 3) labels on top
            val labelSrcs = listOf<Pair<(Int, Int, Int) -> String, ColorFilter?>>(::esriLabels to null)
            for ((z, x, y) in visible(1)) tile(z, x, y, labelSrcs)
            // 4) the city marker
            val world = 256f * density * 2f.pow(zoom)
            val mp = Offset(
                view.width / 2f + ((mercX(lon) - cx) * world).toFloat(),
                view.height / 2f + ((mercY(lat) - cy) * world).toFloat()
            )
            drawCircle(Color(0x66E060B0), 16.dp.toPx(), mp)
            drawCircle(Color(0xFFB03A8A), 7.dp.toPx(), mp)
            drawCircle(Color.White, 7.dp.toPx(), mp, style = Stroke(2.dp.toPx()))
        }

        // top bar: back + city
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).glassButton(backdrop, RoundedCornerShape(50), 10.dp, 20.dp) { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(18.dp)) {
                    val p = Path().apply {
                        moveTo(size.width * 0.65f, size.height * 0.12f)
                        lineTo(size.width * 0.30f, size.height * 0.50f)
                        lineTo(size.width * 0.65f, size.height * 0.88f)
                    }
                    drawPath(p, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.height(44.dp).liquidGlass(backdrop, RoundedCornerShape(50), 10.dp, 20.dp).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) { Text(city.uppercase(), color = Color.White, fontSize = 14.sp, letterSpacing = 3.sp) }
        }

        if (failed) {
            Text("Нет данных радара", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
        }

        // bottom panel: play, time slider, attribution
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp)
                .liquidGlass(backdrop, RoundedCornerShape(28.dp), 12.dp, 24.dp).padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).glassButton(backdrop, RoundedCornerShape(50), 10.dp, 20.dp) { if (frames.size > 1) playing = !playing },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(16.dp)) {
                        if (playing) {
                            drawLine(Color.White, Offset(size.width * 0.28f, size.height * 0.1f), Offset(size.width * 0.28f, size.height * 0.9f), 3.dp.toPx(), StrokeCap.Round)
                            drawLine(Color.White, Offset(size.width * 0.72f, size.height * 0.1f), Offset(size.width * 0.72f, size.height * 0.9f), 3.dp.toPx(), StrokeCap.Round)
                        } else {
                            val p = Path().apply {
                                moveTo(size.width * 0.2f, size.height * 0.08f)
                                lineTo(size.width * 0.92f, size.height * 0.5f)
                                lineTo(size.width * 0.2f, size.height * 0.92f)
                                close()
                            }
                            drawPath(p, Color.White)
                        }
                    }
                }
                if (frames.size > 1) {
                    Slider(
                        value = idx.toFloat(),
                        onValueChange = { playing = false; idx = it.roundToInt().coerceIn(0, frames.lastIndex) },
                        valueRange = 0f..frames.lastIndex.toFloat(),
                        steps = (frames.size - 2).coerceAtLeast(0),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        )
                    )
                } else Spacer(Modifier.weight(1f))
                val label = frames.getOrNull(idx)?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.time * 1000)) } ?: "…"
                Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.widthIn(min = 48.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Weather data by Rain Viewer · © Esri, © OpenStreetMap",
                color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp,
                modifier = Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.rainviewer.com/"))) }
            )
        }
    }
}
