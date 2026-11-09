package com.example.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------- Data ----------

data class Hour(val label: String, val temp: Double, val code: Int, val isDay: Boolean, val event: Int = 0)
data class Day(val date: String, val code: Int, val max: Double, val min: Double)
data class Weather(
    val city: String, val temp: Double, val feels: Double, val code: Int,
    val wind: Double, val windDir: Int, val humidity: Int, val hours: List<Hour>, val days: List<Day>,
    val lat: Double, val lon: Double, val isDay: Boolean,
    val localTime: String, val sunrise: String, val sunset: String
)

// ---------- Network (Open-Meteo, no key) ----------

/** Tries each URL in turn (7 s timeout each) and returns the first successful body. Some hosts are throttled/blocked on
 *  Russian networks (Cloudflare), so every request has fallbacks; the last working URL index is remembered. */
private val lastGood = java.util.concurrent.ConcurrentHashMap<String, Int>()
fun httpGetAny(key: String, urls: List<String>, timeoutMs: Int = 7000): String {
    val first = lastGood[key] ?: 0
    var error: Exception? = null
    for (n in urls.indices) {
        val i = (first + n) % urls.size
        try {
            val c = URL(urls[i]).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", "WeatherApp/1.0 (personal use)")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode in 200..299) {
                val body = c.inputStream.bufferedReader().use { it.readText() }
                lastGood[key] = i
                return body
            }
            error = Exception("HTTP ${c.responseCode}")
        } catch (e: Exception) { error = e }
    }
    throw error ?: Exception("no hosts")
}

suspend fun fetchForecast(lat: Double, lon: Double, name: String): Weather = withContext(Dispatchers.IO) {
    val q = "/v1/forecast?latitude=$lat&longitude=$lon" +
        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,is_day" +
        "&hourly=temperature_2m,weather_code,is_day" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
        "&timezone=auto&forecast_days=7"
    val w = JSONObject(
        httpGetAny(
            "forecast",
            listOf("https://api.open-meteo.com$q", "https://historical-forecast-api.open-meteo.com$q")
        )
    )
    val cur = w.getJSONObject("current")
    val d = w.getJSONObject("daily")
    val dates = d.getJSONArray("time"); val codes = d.getJSONArray("weather_code")
    val max = d.getJSONArray("temperature_2m_max"); val min = d.getJSONArray("temperature_2m_min")
    val rise = d.getJSONArray("sunrise"); val set = d.getJSONArray("sunset")
    val h = w.getJSONObject("hourly")
    val ht = h.getJSONArray("time"); val hv = h.getJSONArray("temperature_2m")
    val hc = h.getJSONArray("weather_code"); val hd = h.getJSONArray("is_day")
    val nowHour = cur.getString("time").substring(0, 13)
    val start = (0 until ht.length()).firstOrNull { ht.getString(it).substring(0, 13) >= nowHour } ?: 0
    val end = minOf(start + 24, ht.length())
    val items = ArrayList<Pair<String, Hour>>()
    for (i in start until end) {
        items.add(
            ht.getString(i) to Hour(
                if (i == start) "Сейчас" else ht.getString(i).substring(11, 16),
                hv.getDouble(i), hc.getInt(i), hd.getInt(i) == 1
            )
        )
    }
    val t0 = ht.getString(start); val t1 = ht.getString(end - 1)
    for (i in 0 until rise.length()) {
        val r = rise.getString(i); val sn = set.getString(i)
        if (r >= t0 && r <= t1) items.add(r to Hour(r.substring(11, 16), 0.0, 0, true, 1))
        if (sn >= t0 && sn <= t1) items.add(sn to Hour(sn.substring(11, 16), 0.0, 0, false, 2))
    }
    items.sortBy { it.first }
    Weather(
        city = name,
        temp = cur.getDouble("temperature_2m"),
        feels = cur.getDouble("apparent_temperature"),
        code = cur.getInt("weather_code"),
        wind = cur.getDouble("wind_speed_10m"),
        windDir = Math.round(cur.optDouble("wind_direction_10m", 0.0)).toInt(),
        humidity = cur.getInt("relative_humidity_2m"),
        hours = items.map { it.second },
        days = List(dates.length()) {
            Day(dates.getString(it), codes.getInt(it), max.getDouble(it), min.getDouble(it))
        },
        lat = lat, lon = lon, isDay = cur.getInt("is_day") == 1,
        localTime = cur.getString("time"), sunrise = rise.getString(0), sunset = set.getString(0)
    )
}

suspend fun fetchByCity(query: String): Weather {
    val enc = URLEncoder.encode(query.trim(), "UTF-8")
    val (lat, lon, name) = withContext(Dispatchers.IO) {
        runCatching {
            val geo = JSONObject(httpGetAny("geo", listOf("https://geocoding-api.open-meteo.com/v1/search?name=$enc&count=1&language=ru")))
            val p = geo.optJSONArray("results")?.getJSONObject(0) ?: throw Exception("City not found")
            Triple(p.getDouble("latitude"), p.getDouble("longitude"), p.getString("name"))
        }.getOrElse {
            // second geocoder: OpenStreetMap Nominatim
            val arr = org.json.JSONArray(
                httpGetAny("geo2", listOf("https://nominatim.openstreetmap.org/search?q=$enc&format=json&limit=1&accept-language=ru"))
            )
            if (arr.length() == 0) throw Exception("City not found")
            val o = arr.getJSONObject(0)
            Triple(o.getString("lat").toDouble(), o.getString("lon").toDouble(),
                o.optString("name").ifBlank { o.getString("display_name").substringBefore(",") })
        }
    }
    return fetchForecast(lat, lon, name)
}

suspend fun reverseName(lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
    runCatching {
        val j = JSONObject(httpGetAny("rev", listOf("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=ru")))
        j.optString("city").ifBlank { j.optString("locality") }.ifBlank { throw Exception("empty") }
    }.recoverCatching {
        val j = JSONObject(httpGetAny("rev2", listOf("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&zoom=10&accept-language=ru")))
        val a = j.optJSONObject("address")
        a?.optString("city").orEmpty().ifBlank { a?.optString("town").orEmpty() }
            .ifBlank { a?.optString("village").orEmpty() }.ifBlank { throw Exception("empty") }
    }.getOrDefault("Моё место")
}

@SuppressLint("MissingPermission")
suspend fun getLocation(ctx: Context): Location? {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = lm.getProviders(true)
    providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { return it }
    val provider = when {
        LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
        LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
        else -> return null
    }
    return withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine<Location?> { cont ->
            lm.getCurrentLocation(provider, null, ctx.mainExecutor) { cont.resume(it) }
        }
    }
}

// ---------- Formatting ----------

fun describe(code: Int) = when (code) {
    0 -> "Ясно"; 1, 2 -> "Малооблачно"; 3 -> "Облачно"; 45, 48 -> "Туман"
    in 51..57 -> "Морось"; in 61..67 -> "Дождь"; in 71..77 -> "Снег"
    in 80..82 -> "Ливень"; 85, 86 -> "Снегопад"; in 95..99 -> "Гроза"; else -> "—"
}

fun weekday(date: String): String {
    val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date) ?: return date
    return SimpleDateFormat("EEE", Locale("ru")).format(d).replaceFirstChar { it.uppercase() }
}

fun temp(c: Double, f: Boolean) = "${Math.round(if (f) c * 9 / 5 + 32 else c)}°"
fun windText(kmh: Double, ms: Boolean) =
    if (ms) String.format(Locale.US, "%.1f м/с", kmh / 3.6) else "${Math.round(kmh)} км/ч"
/** Meteorological direction = where the wind blows FROM. */
fun windDirName(deg: Int): String =
    listOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")[(((deg % 360) + 360) % 360 + 22) / 45 % 8]

fun windDirLong(deg: Int): String =
    listOf("Северный", "Северо-восточный", "Восточный", "Юго-восточный", "Южный", "Юго-западный", "Западный", "Северо-западный")[(((deg % 360) + 360) % 360 + 22) / 45 % 8]

fun windWord(kmh: Double) = when {
    kmh < 2 -> "Штиль"; kmh < 12 -> "Слабый"; kmh < 29 -> "Умеренный"; kmh < 50 -> "Сильный"; else -> "Очень сильный"
}

/** Developer nickname shown at the bottom of the side panel. Change it to your own. */
const val DEV_NICK = "VikmΛt"

// ---------- Favorites ----------

data class Fav(val name: String, val lat: Double, val lon: Double)

fun loadFavs(prefs: SharedPreferences): List<Fav> = runCatching {
    val a = JSONArray(prefs.getString("favs", "[]"))
    List(a.length()) { val o = a.getJSONObject(it); Fav(o.getString("n"), o.getDouble("lat"), o.getDouble("lon")) }
}.getOrDefault(emptyList())

fun saveFavs(prefs: SharedPreferences, list: List<Fav>) {
    val a = JSONArray()
    list.forEach { a.put(JSONObject().put("n", it.name).put("lat", it.lat).put("lon", it.lon)) }
    prefs.edit().putString("favs", a.toString()).apply()
}

// ---------- Time of day -> colours ----------

enum class Phase(val a: Long, val b: Long, val c: Long) {
    NIGHT(0xFF4B3BFF, 0xFF8A3FFC, 0xFF2B1D8F),
    DAWN(0xFFFF7A59, 0xFFFF4FA3, 0xFF8A3FFC),
    DAY(0xFFFF4FA3, 0xFF8A3FFC, 0xFF3FA2FF),
    DUSK(0xFFFF8A3D, 0xFFE23B8A, 0xFF6A2BFF)
}

private fun mins(t: String) = t.substring(11, 13).toInt() * 60 + t.substring(14, 16).toInt()

/** Phase of the day at the weather's location (uses its own local time, sunrise and sunset). */
fun phaseOf(w: Weather): Phase = runCatching {
    val now = mins(w.localTime); val sr = mins(w.sunrise); val ss = mins(w.sunset)
    when {
        kotlin.math.abs(now - sr) <= 60 -> Phase.DAWN
        kotlin.math.abs(now - ss) <= 60 -> Phase.DUSK
        now in sr..ss -> Phase.DAY
        else -> Phase.NIGHT
    }
}.getOrElse { if (w.isDay) Phase.DAY else Phase.NIGHT }

// ---------- Animated numbers (count up/down when units or city change) ----------

@Composable
fun animNum(target: Double): Double {
    val v by animateFloatAsState(target.toFloat(), tween(550, easing = FastOutSlowInEasing), label = "num")
    return v.toDouble()
}

@Composable
fun tempAnim(c: Double, f: Boolean) = "${Math.round(animNum(if (f) c * 9 / 5 + 32 else c))}°"

@Composable
fun windAnim(kmh: Double, ms: Boolean): String {
    val v = animNum(if (ms) kmh / 3.6 else kmh)
    return if (ms) String.format(Locale.US, "%.1f м/с", v) else "${Math.round(v)} км/ч"
}

// ---------- Icons (Feather-style line icons, shared with the widget) ----------

enum class IconKind(val tint: Long, val paths: List<String>) {
    SUN(0xFFFFD27A, listOf("M17 12a5 5 0 1 1-10 0a5 5 0 1 1 10 0z M12 1v2 M12 21v2 M4.22 4.22l1.42 1.42 M18.36 18.36l1.42 1.42 M1 12h2 M21 12h2 M4.22 19.78l1.42-1.42 M18.36 5.64l1.42-1.42")),
    MOON(0xFFDCDFFF, listOf("M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z")),
    SUNRISE(0xFFFFB86B, listOf("M17 18a5 5 0 0 0-10 0 M12 2v7 M4.22 10.22l1.42 1.42 M1 18h2 M21 18h2 M18.36 11.64l1.42-1.42 M23 22H1 M8 6l4-4 4 4")),
    SUNSET(0xFFFF8FB8, listOf("M17 18a5 5 0 0 0-10 0 M12 9V2 M4.22 10.22l1.42 1.42 M1 18h2 M21 18h2 M18.36 11.64l1.42-1.42 M23 22H1 M16 5l-4 4-4-4")),
    CLOUD(0xFFFFFFFF, listOf("M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z")),
    RAIN(0xFFFFFFFF, listOf("M16 13v8 M8 13v8 M12 15v8 M20 16.58A5 5 0 0 0 18 8h-1.26A8 8 0 1 0 4 14.25")),
    SNOW(0xFFFFFFFF, listOf("M20 17.58A5 5 0 0 0 18 8h-1.26A8 8 0 1 0 4 16.25 M8 16h.01 M8 20h.01 M12 18h.01 M12 22h.01 M16 16h.01 M16 20h.01"))
}

fun kindFor(code: Int, isDay: Boolean, event: Int = 0): IconKind = when {
    event == 1 -> IconKind.SUNRISE
    event == 2 -> IconKind.SUNSET
    code in 0..1 -> if (isDay) IconKind.SUN else IconKind.MOON
    code in 71..77 || code in 85..86 -> IconKind.SNOW
    code in 51..67 || code in 80..82 || code in 95..99 -> IconKind.RAIN
    else -> IconKind.CLOUD
}

@Composable
fun WIcon(kind: IconKind, modifier: Modifier = Modifier.size(28.dp)) {
    val paths = remember(kind) { kind.paths.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier) {
        scale(size.width / 24f, Offset.Zero) {
            paths.forEach {
                drawPath(it, Color(kind.tint), style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// ---------- Glass ----------

val Dim = Color(0xFFB0A8BC)
val Glass28 = RoundedCornerShape(28.dp)

/** Real liquid glass (Kyant0/backdrop): blur + vibrancy + lens refraction. Android 13+. */
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    shape: CornerBasedShape = Glass28,
    lensHeight: Dp = 16.dp,
    lensAmount: Dp = 32.dp
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        blur(4f.dp.toPx())
        lens(lensHeight.toPx(), lensAmount.toPx())
    },
    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.08f)) }
)

/**
 * Glass button. The press "squish" goes through drawBackdrop's layerBlock, so the content scales
 * but the blurred/refracted backdrop stays put (a plain graphicsLayer scale would drag the backdrop along).
 */
fun Modifier.glassButton(
    backdrop: Backdrop,
    shape: CornerBasedShape = Glass28,
    lensHeight: Dp = 16.dp,
    lensAmount: Dp = 32.dp,
    onClick: () -> Unit
): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press = remember { Animatable(0f) }
    LaunchedEffect(pressed) { press.animateTo(if (pressed) 1f else 0f, spring(0.5f, 300f, 0.001f)) }
    this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(4f.dp.toPx())
            lens(lensHeight.toPx(), lensAmount.toPx())
        },
        layerBlock = {
            val sc = 1f - 0.1f * press.value
            scaleX = sc
            scaleY = sc
        },
        onDrawSurface = { drawRect(Color.White.copy(alpha = 0.08f)) }
    ).clickable(interactionSource = src, indication = null, onClick = onClick)
}

/** Black canvas with slowly drifting light; the colours follow the time of day and fade between phases. */
@Composable
fun GlassBackground(phase: Phase, modifier: Modifier = Modifier) {
    val clock by rememberInfiniteTransition(label = "bg").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(36000, easing = LinearEasing)), label = "clock"
    )
    val ca by animateColorAsState(Color(phase.a), tween(2000), label = "ca")
    val cb by animateColorAsState(Color(phase.b), tween(2000), label = "cb")
    val cc by animateColorAsState(Color(phase.c), tween(2000), label = "cc")
    Canvas(modifier.fillMaxSize()) {
        drawRect(Color.Black)
        fun blob(color: Color, cx: Float, cy: Float, r: Float) {
            val c = Offset(cx, cy)
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = c, radius = r), r, c)
        }
        val w = size.width; val h = size.height
        val t = clock
        // blobs orbit on slow Lissajous-like paths, breathe in size and pulse in brightness
        blob(ca.copy(alpha = 0.55f + 0.10f * sin(t * 2f)),
            w * (0.50f + 0.38f * cos(t)), h * (0.20f + 0.10f * sin(t * 2f)), w * (0.75f + 0.08f * sin(t * 3f)))
        blob(cb.copy(alpha = 0.48f + 0.10f * cos(t * 3f)),
            w * (0.50f + 0.40f * sin(t * 2f + 1f)), h * (0.52f + 0.14f * cos(t + 2f)), w * (0.80f + 0.08f * cos(t * 2f)))
        blob(cc.copy(alpha = 0.40f + 0.08f * sin(t * 4f)),
            w * (0.50f + 0.35f * cos(t * 3f + 2f)), h * (0.86f + 0.06f * sin(t * 2f)), w * (0.75f + 0.07f * sin(t * 2f + 1f)))
        blob(ca.copy(alpha = 0.22f),
            w * (0.50f + 0.45f * sin(t * 5f)), h * (0.68f + 0.20f * cos(t * 3f)), w * 0.45f)
    }
}

// ---------- "Sky" theme: colours follow time of day AND weather ----------

enum class Cond { CLEAR, CLOUDY, RAIN, SNOW, STORM }

fun condOf(code: Int): Cond = when {
    code in 95..99 -> Cond.STORM
    code in 71..77 || code in 85..86 -> Cond.SNOW
    code in 51..67 || code in 80..82 -> Cond.RAIN
    code in 2..3 || code in 45..48 -> Cond.CLOUDY
    else -> Cond.CLEAR
}

private fun pick(p: Phase, day: LongArray, dawn: LongArray, dusk: LongArray, night: LongArray) = when (p) {
    Phase.DAY -> day; Phase.DAWN -> dawn; Phase.DUSK -> dusk; Phase.NIGHT -> night
}

/** Top / middle / bottom colour of the sky. */
fun skyPalette(p: Phase, c: Cond): LongArray = when (c) {
    Cond.CLEAR -> pick(p,
        longArrayOf(0xFF2E7BE8, 0xFF5BAEF5, 0xFF9FD3FA), longArrayOf(0xFF3B3F8F, 0xFFE8708A, 0xFFFFC27A),
        longArrayOf(0xFF2B2A6B, 0xFFD9586E, 0xFFFF9A55), longArrayOf(0xFF050818, 0xFF101B3F, 0xFF22336B))
    Cond.CLOUDY -> pick(p,
        longArrayOf(0xFF5F7C99, 0xFF8FA3B5, 0xFFB4C2CE), longArrayOf(0xFF4A4A63, 0xFF9A7A86, 0xFFC9A98E),
        longArrayOf(0xFF3E3A56, 0xFF87606B, 0xFFB98A6E), longArrayOf(0xFF0E1220, 0xFF1A2236, 0xFF2A3347))
    Cond.RAIN -> pick(p,
        longArrayOf(0xFF3C4D63, 0xFF566A80, 0xFF7E91A3), longArrayOf(0xFF2E3345, 0xFF4B4A5E, 0xFF6C5F66),
        longArrayOf(0xFF2E3345, 0xFF4B4A5E, 0xFF6C5F66), longArrayOf(0xFF090D18, 0xFF121A2A, 0xFF1D2738))
    Cond.SNOW -> pick(p,
        longArrayOf(0xFF6F86A0, 0xFF93A8BF, 0xFFBCCBDA), longArrayOf(0xFF6A6E8C, 0xFFB79BAE, 0xFFE3C9C0),
        longArrayOf(0xFF6A6E8C, 0xFFB79BAE, 0xFFE3C9C0), longArrayOf(0xFF1B2335, 0xFF2D3A52, 0xFF47587A))
    Cond.STORM -> pick(p,
        longArrayOf(0xFF2B2F45, 0xFF3F4560, 0xFF5E6480), longArrayOf(0xFF231F38, 0xFF3D3050, 0xFF5A4259),
        longArrayOf(0xFF231F38, 0xFF3D3050, 0xFF5A4259), longArrayOf(0xFF05060F, 0xFF0D0F22, 0xFF181A33))
}

/** 0..1 position of the sun (day) or moon (night) along its arc. */
fun skyProgress(w: Weather): Float = runCatching {
    val now = mins(w.localTime); val sr = mins(w.sunrise); val ss = mins(w.sunset)
    when {
        now in sr..ss -> (now - sr).toFloat() / (ss - sr).coerceAtLeast(1)
        now < sr && sr - now <= 60 -> 0f
        now > ss && now - ss <= 60 -> 1f
        else -> {
            val len = ((sr - ss + 1440) % 1440).coerceAtLeast(1)
            (((now - ss + 1440) % 1440).toFloat() / len).coerceIn(0f, 1f)
        }
    }
}.getOrDefault(0.5f)

@Composable
fun SkyBackground(phase: Phase, cond: Cond, sunP: Float, modifier: Modifier = Modifier) {
    val pal = skyPalette(phase, cond)
    val c0 by animateColorAsState(Color(pal[0]), tween(2000), label = "s0")
    val c1 by animateColorAsState(Color(pal[1]), tween(2000), label = "s1")
    val c2 by animateColorAsState(Color(pal[2]), tween(2000), label = "s2")
    val clouds by animateFloatAsState(when (cond) { Cond.CLEAR -> 0.08f; Cond.CLOUDY -> 0.9f; else -> 1f }, tween(1800), label = "clouds")
    val orb by animateFloatAsState(when (cond) { Cond.CLEAR -> 1f; Cond.CLOUDY -> 0.35f; else -> 0.08f }, tween(1800), label = "orb")
    val inf = rememberInfiniteTransition(label = "sky")
    val drift by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(90000, easing = LinearEasing)), label = "drift")
    val fall by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "fall")
    val slow by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "slow")
    val night = phase == Phase.NIGHT
    val pi = Math.PI.toFloat()
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(c0, c1, c2)))
        // sun or moon travelling along an arc
        val oc = Offset(w * (0.14f + 0.72f * sunP), h * (0.30f - 0.18f * sin(pi * sunP)))
        val glow = if (night) Color(0xFFDDE6FF) else Color(0xFFFFE9A8)
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.55f * orb), Color.Transparent), center = oc, radius = w * 0.55f), w * 0.55f, oc)
        drawCircle(glow.copy(alpha = 0.95f * orb), 20.dp.toPx(), oc)
        // drifting clouds
        val cloudCol = if (cond == Cond.RAIN || cond == Cond.STORM) Color(0xFF8FA0B8) else Color.White
        for (k in 0 until 6) {
            val cc = Offset((((k * 0.23f + drift * (1 + k % 2)) % 1f) * 1.5f - 0.25f) * w, h * (0.12f + 0.11f * k))
            val r = w * (0.28f + 0.05f * (k % 3))
            drawCircle(Brush.radialGradient(listOf(cloudCol.copy(alpha = 0.26f * clouds), Color.Transparent), center = cc, radius = r), r, cc)
        }
        if (cond == Cond.RAIN || cond == Cond.STORM) {
            for (i in 0 until (if (cond == Cond.STORM) 70 else 45)) {
                val x = ((i * 0.618034f) % 1f) * w
                val y = ((fall * (1 + i % 3) + i * 0.37f) % 1f) * (h + 40.dp.toPx()) - 20.dp.toPx()
                drawLine(Color(0xFFBFD4FF).copy(alpha = 0.30f), Offset(x, y), Offset(x - 5.dp.toPx(), y + 20.dp.toPx()), 1.2.dp.toPx(), StrokeCap.Round)
            }
        }
        if (cond == Cond.SNOW) {
            for (i in 0 until 40) {
                val x = ((i * 0.618034f) % 1f) * w + sin(2f * pi * slow + i) * 12.dp.toPx()
                val y = ((slow * (1 + i % 2) + i * 0.29f) % 1f) * (h + 20.dp.toPx()) - 10.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.6f), (1.4f + i % 3).dp.toPx(), Offset(x, y))
            }
        }
        // soft scrim so white text stays readable on bright skies
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.16f), Color.Black.copy(alpha = 0.40f))))
    }
}

/** Compass: ticks, a "С" at north and a needle pointing where the wind is blowing TO (opposite of the meteorological "from" angle). */
@Composable
fun WindCompass(fromDeg: Int, modifier: Modifier = Modifier) {
    val angle by animateFloatAsState(
        fromDeg + 180f, spring(dampingRatio = 0.65f, stiffness = 90f), label = "windDir"
    )
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f - 1.dp.toPx()
        // face
        drawCircle(Color.White.copy(alpha = 0.06f), r, c)
        drawCircle(Color.White.copy(alpha = 0.28f), r, c, style = Stroke(1.dp.toPx()))
        // ticks every 15°, longer at 45° steps, longest at the four sides
        for (i in 0 until 24) {
            val a = i * 15f
            val len = when { i % 6 == 0 -> 7.dp.toPx(); i % 3 == 0 -> 5.dp.toPx(); else -> 3.dp.toPx() }
            val alpha = if (i % 6 == 0) 0.75f else if (i % 3 == 0) 0.45f else 0.22f
            rotate(a, c) {
                drawLine(Color.White.copy(alpha = alpha), Offset(c.x, c.y - r + 1.dp.toPx()), Offset(c.x, c.y - r + 1.dp.toPx() + len), 1.dp.toPx(), StrokeCap.Round)
            }
        }
        // north letter, inside the ring, always upright
        drawIntoCanvas { cv ->
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(230, 255, 255, 255)
                textSize = 9.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
            }
            cv.nativeCanvas.drawText("С", c.x, c.y - r + 17.dp.toPx(), p)
        }
        // needle
        rotate(angle, c) {
            val tip = r * 0.50f; val tail = r * 0.30f; val half = r * 0.13f
            val head = Path().apply {
                moveTo(c.x, c.y - tip); lineTo(c.x + half, c.y + r * 0.04f); lineTo(c.x - half, c.y + r * 0.04f); close()
            }
            val back = Path().apply {
                moveTo(c.x, c.y + tail); lineTo(c.x + half, c.y + r * 0.04f); lineTo(c.x - half, c.y + r * 0.04f); close()
            }
            drawPath(back, Color.White.copy(alpha = 0.35f))
            drawPath(head, Color.White)
        }
        drawCircle(Color(0xFF1B1E3A), 2.2.dp.toPx(), c)
        drawCircle(Color.White, 2.2.dp.toPx(), c, style = Stroke(1.dp.toPx()))
    }
}

// ---------- Windmill: spin speed follows the wind ----------

@Composable
fun Windmill(windKmh: Double, modifier: Modifier = Modifier) {
    var angle by remember { mutableFloatStateOf(0f) }
    val speed by rememberUpdatedState(windKmh)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dps = (speed * 14.0).coerceIn(12.0, 900.0).toFloat()
                    angle = (angle + dps * (now - last) / 1e9f) % 360f
                }
                last = now
            }
        }
    }
    Canvas(modifier) {
        val hub = Offset(size.width / 2f, size.height * 0.36f)
        val len = size.height * 0.33f
        val base = size.height * 0.97f
        val white = Color.White.copy(alpha = 0.95f)
        // slim tower, tapered, with softly curved sides
        val tower = Path().apply {
            val span = base - hub.y
            moveTo(hub.x - 2.2.dp.toPx(), hub.y)
            cubicTo(hub.x - 2.4.dp.toPx(), hub.y + span * 0.5f, hub.x - 3.4.dp.toPx(), base - span * 0.15f, hub.x - 4.2.dp.toPx(), base)
            lineTo(hub.x + 4.2.dp.toPx(), base)
            cubicTo(hub.x + 3.4.dp.toPx(), base - span * 0.15f, hub.x + 2.4.dp.toPx(), hub.y + span * 0.5f, hub.x + 2.2.dp.toPx(), hub.y)
            close()
        }
        drawPath(tower, white.copy(alpha = 0.85f))
        // leaf-shaped, airfoil-like blades
        val blade = Path().apply {
            moveTo(hub.x, hub.y)
            cubicTo(hub.x + 0.16f * len, hub.y - 0.20f * len, hub.x + 0.14f * len, hub.y - 0.65f * len, hub.x, hub.y - len)
            cubicTo(hub.x - 0.05f * len, hub.y - 0.65f * len, hub.x - 0.04f * len, hub.y - 0.20f * len, hub.x, hub.y)
            close()
        }
        for (k in 0 until 3) rotate(angle + k * 120f, hub) { drawPath(blade, white) }
        drawCircle(white, 4.5.dp.toPx(), hub)
        drawCircle(Color.Black.copy(alpha = 0.35f), 1.6.dp.toPx(), hub)
    }
}

// ---------- UI ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black, surface = Color.Black,
                    onBackground = Color.White, onSurface = Color.White
                )
            ) { WeatherScreen() }
        }
    }
}

/** Click with a springy "press in, bounce back" animation. Put it first in the chain so the whole button scales. */
fun Modifier.bounceClick(onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    this.graphicsLayer { scaleX = sc; scaleY = sc }
        .clickable(interactionSource = src, indication = null, onClick = onClick)
}

@Composable
fun StarIcon(filled: Boolean, modifier: Modifier = Modifier) {
    val pop by animateFloatAsState(
        if (filled) 1.18f else 1f,
        spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessLow),
        label = "pop"
    )
    Canvas(modifier.graphicsLayer { scaleX = pop; scaleY = pop }) {
        val c = center; val ro = size.minDimension * 0.46f; val ri = ro * 0.42f
        val p = Path()
        for (i in 0 until 10) {
            val a = Math.toRadians((-90 + i * 36).toDouble())
            val r = if (i % 2 == 0) ro else ri
            val x = c.x + (r * cos(a)).toFloat(); val y = c.y + (r * sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        val col = Color(0xFFFFD27A)
        if (filled) drawPath(p, col) else drawPath(p, col, style = Stroke(1.8.dp.toPx(), join = StrokeJoin.Round))
    }
}

/** Segmented switch: tap or drag. The thumb is a real liquid-glass lens that refracts the labels under it. */
@Composable
fun Seg(options: List<String>, selected: Int, backdrop: Backdrop, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val h = 44.dp
    val last = options.lastIndex
    val spec = spring<Float>(dampingRatio = 0.75f, stiffness = 380f)
    val pos = remember { Animatable(selected.toFloat()) }   // thumb position in "option units", 0..last
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(selected) { if (!dragging) pos.animateTo(selected.toFloat(), spec) }
    val labels = rememberLayerBackdrop()
    val both = rememberCombinedBackdrop(backdrop, labels)

    // the switch fills the width it is given (two of them share the row), so it can never run off-screen
    BoxWithConstraints(
        modifier.liquidGlass(backdrop, RoundedCornerShape(50), 8.dp, 14.dp).padding(3.dp)
    ) {
        val w = maxWidth / options.size
        val wPx = with(LocalDensity.current) { w.toPx() }
        Box(
            Modifier.pointerInput(selected, wPx) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        val t = pos.value.roundToInt().coerceIn(0, last)
                        if (t != selected) onSelect(t)
                        scope.launch { pos.animateTo(t.toFloat(), spec) }
                    },
                    onDragCancel = {
                        dragging = false
                        scope.launch { pos.animateTo(selected.toFloat(), spec) }
                    },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        scope.launch { pos.snapTo((pos.value + dx / wPx).coerceIn(0f, last.toFloat())) }
                    }
                )
            }
        ) {
            Row(Modifier.layerBackdrop(labels)) {
                options.forEachIndexed { i, o ->
                    val near = (1f - kotlin.math.abs(pos.value - i)).coerceIn(0f, 1f)
                    Box(Modifier.size(w, h).bounceClick { onSelect(i) }, contentAlignment = Alignment.Center) {
                        Text(o, color = lerp(Dim, Color.White, near), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Box(
                Modifier.offset { IntOffset((pos.value * wPx).roundToInt(), 0) }
                    .drawBackdrop(
                        backdrop = both,
                        shape = { RoundedCornerShape(50) },
                        // real refraction again; chromatic aberration stays off, it caused the coloured fringes
                        effects = { vibrancy(); lens(8f.dp.toPx(), 12f.dp.toPx()) },
                        onDrawSurface = { drawRect(Color.White.copy(alpha = 0.14f)) }
                    )
                    .size(w, h)
            )
        }
    }
}

@Composable
fun Stat(label: String, value: String) {
    Column {
        Text(label, color = Dim, fontSize = 11.sp, letterSpacing = 1.5.sp)
        Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun WeatherScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("weather", Context.MODE_PRIVATE) }
    var query by remember { mutableStateOf(prefs.getString("city", "Москва") ?: "Москва") }
    var fahrenheit by remember { mutableStateOf(prefs.getBoolean("f", false)) }
    var ms by remember { mutableStateOf(prefs.getBoolean("ms", false)) }
    var data by remember { mutableStateOf<Weather?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop {
        drawRect(Color.Black)   // keep the backdrop free of transparent pixels
        drawContent()
    }

    fun loadCity() {
        scope.launch {
            loading = true; error = null
            runCatching { fetchByCity(query) }
                .onSuccess { data = it; prefs.edit().putString("city", query).apply(); saveForWidget(ctx, it) }
                .onFailure { error = "Не удалось загрузить. Проверьте название города и интернет." }
            loading = false
        }
    }

    fun loadHere() {
        scope.launch {
            loading = true; error = null
            val loc = getLocation(ctx)
            if (loc == null) error = "Не удалось определить местоположение. Включите геолокацию."
            else runCatching { fetchForecast(loc.latitude, loc.longitude, reverseName(loc.latitude, loc.longitude)) }
                .onSuccess { data = it; query = it.city; saveForWidget(ctx, it) }
                .onFailure { error = "Не удалось загрузить погоду. Проверьте интернет." }
            loading = false
        }
    }

    val favorites = remember { mutableStateListOf<Fav>().apply { addAll(loadFavs(prefs)) } }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    var showMap by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var searchBounds by remember { mutableStateOf(Rect.Zero) }
    var theme by remember { mutableIntStateOf(prefs.getInt("theme", 0)) }
    BackHandler(enabled = showMap) { showMap = false }
    val phase = data?.let { phaseOf(it) } ?: Phase.DAY

    fun toggleFav() {
        val w = data ?: return
        val i = favorites.indexOfFirst { it.name == w.city }
        if (i >= 0) favorites.removeAt(i) else favorites.add(Fav(w.city, w.lat, w.lon))
        saveFavs(prefs, favorites)
    }

    fun loadFav(f: Fav) {
        scope.launch {
            drawer.close()
            loading = true; error = null
            runCatching { fetchForecast(f.lat, f.lon, f.name) }
                .onSuccess { data = it; query = it.city; saveForWidget(ctx, it) }
                .onFailure { error = "Не удалось загрузить погоду. Проверьте интернет." }
            loading = false
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) loadHere() else error = "Нет доступа к геолокации."
    }
    LaunchedEffect(Unit) { loadCity() }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !showMap,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xF00B0614), drawerContentColor = Color.White) {
                Column(Modifier.fillMaxHeight().padding(horizontal = 20.dp, vertical = 24.dp)) {
                    Text("ТЕМА", color = Dim, fontSize = 12.sp, letterSpacing = 3.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("Неон", "Небо").forEachIndexed { i, name ->
                            Box(
                                Modifier.weight(1f)
                                    .bounceClick { theme = i; prefs.edit().putInt("theme", i).apply() }
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = if (theme == i) 0.24f else 0.08f))
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) { Text(name, color = if (theme == i) Color.White else Dim, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    Text("ГОРОДА", color = Dim, fontSize = 12.sp, letterSpacing = 3.sp)
                    Spacer(Modifier.height(16.dp))
                    data?.let { w ->
                        val fav = favorites.any { it.name == w.city }
                        Box(
                            Modifier.fillMaxWidth().bounceClick { toggleFav() }.clip(RoundedCornerShape(18.dp))
                                .background(Color.White.copy(alpha = 0.10f)).padding(16.dp)
                        ) {
                            Text(
                                if (fav) "Убрать «${w.city}» из избранного" else "Добавить «${w.city}» в избранное",
                                color = Color.White, fontSize = 15.sp
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    if (favorites.isEmpty()) Text("Пока пусто", color = Dim, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
                    favorites.toList().forEach { f ->
                        val current = f.name == data?.city
                        Row(
                            Modifier.fillMaxWidth().bounceClick { loadFav(f) }.clip(RoundedCornerShape(18.dp))
                                .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                f.name, color = if (current) Color.White else Dim, fontSize = 18.sp,
                                fontWeight = if (current) FontWeight.Medium else FontWeight.Light,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "×", color = Dim, fontSize = 24.sp,
                                modifier = Modifier.bounceClick { favorites.remove(f); saveFavs(prefs, favorites) }.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // --- updates from GitHub Releases ---
                    val updScope = rememberCoroutineScope()
                    var update by remember { mutableStateOf<UpdateInfo?>(null) }
                    var updMsg by remember { mutableStateOf("") }
                    val checkUpdates: (Boolean) -> Unit = { manual ->
                        updScope.launch {
                            if (manual) updMsg = "Проверяю…"
                            runCatching { Updater.check(ctx) }
                                .onSuccess { u -> update = u; if (manual) updMsg = if (u == null) "Установлена последняя версия" else "" }
                                .onFailure { if (manual) updMsg = "Не удалось проверить обновления" }
                            prefs.edit().putLong("upd_t", System.currentTimeMillis()).apply()
                        }
                    }
                    LaunchedEffect(Unit) {
                        if (System.currentTimeMillis() - prefs.getLong("upd_t", 0L) > 12 * 3600_000L) checkUpdates(false)
                    }
                    Text(
                        text = update?.let { "Доступно обновление ${it.version} — установить" }
                            ?: updMsg.ifEmpty { "Проверить обновления" },
                        color = if (update != null) Color(0xFFE5A8FF) else Color(0xFF8A8494),
                        fontSize = 13.sp,
                        modifier = Modifier.bounceClick { update?.let { Updater.downloadAndInstall(ctx, it) } ?: checkUpdates(true) }
                            .padding(vertical = 8.dp)
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
                    Spacer(Modifier.height(14.dp))
                    Text("Приложение распространяется по свободной лицензии", color = Color(0xFF6B6674), fontSize = 10.sp, lineHeight = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Polaris Weather ${Updater.installedVersion(ctx)} · Разработчик: $DEV_NICK", color = Color(0xFF8A8494), fontSize = 11.sp)
                }
            }
        }
    ) {
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // Any tap outside the search field drops focus and hides the keyboard.
            // Initial pass + no consume: buttons and lists underneath still get the tap.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!searchBounds.contains(down.position)) {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
            }
        }
    ) {
        if (theme == 0) GlassBackground(phase, Modifier.layerBackdrop(backdrop))
        else SkyBackground(
            phase, data?.let { condOf(it.code) } ?: Cond.CLEAR, data?.let { skyProgress(it) } ?: 0.5f,
            Modifier.layerBackdrop(backdrop)
        )
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).glassButton(backdrop, RoundedCornerShape(50), 12.dp, 24.dp) { scope.launch { drawer.open() } },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(22.dp)) {
                        val sw = 2.dp.toPx()
                        for (k in 0..2) {
                            val yy = size.height * (0.2f + 0.3f * k)
                            drawLine(Color.White, Offset(0f, yy), Offset(size.width, yy), sw, cap = StrokeCap.Round)
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).liquidGlass(backdrop, RoundedCornerShape(50), 12.dp, 24.dp)) {
                    TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Город", color = Dim) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { loadCity(); focusManager.clearFocus(); keyboard?.hide() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth().onGloballyPositioned { searchBounds = it.boundsInRoot() }
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(56.dp).glassButton(backdrop, RoundedCornerShape(50), 12.dp, 24.dp) {
                            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) loadHere()
                            else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(24.dp)) {
                        val c = center; val r = size.minDimension * 0.28f; val sw = 2.dp.toPx()
                        drawCircle(Color.White, r, c, style = Stroke(sw))
                        drawCircle(Color.White, r * 0.35f, c)
                        drawLine(Color.White, Offset(c.x, 0f), Offset(c.x, size.height * 0.22f), sw)
                        drawLine(Color.White, Offset(c.x, size.height * 0.78f), Offset(c.x, size.height), sw)
                        drawLine(Color.White, Offset(0f, c.y), Offset(size.width * 0.22f, c.y), sw)
                        drawLine(Color.White, Offset(size.width * 0.78f, c.y), Offset(size.width, c.y), sw)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Seg(listOf("°C", "°F"), if (fahrenheit) 1 else 0, backdrop, Modifier.weight(1f)) {
                    fahrenheit = it == 1; prefs.edit().putBoolean("f", fahrenheit).apply(); refreshWidgets(ctx)
                }
                Seg(listOf("км/ч", "м/с"), if (ms) 1 else 0, backdrop, Modifier.weight(1f)) {
                    ms = it == 1; prefs.edit().putBoolean("ms", ms).apply(); refreshWidgets(ctx)
                }
            }

            error?.let { Text(it, color = Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 24.dp)) }
            if (loading && data == null) {
                Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                }
            }

            data?.let { w ->
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 16.dp).liquidGlass(backdrop).padding(28.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(w.city.uppercase(), color = Dim, fontSize = 14.sp, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
                                StarIcon(
                                    favorites.any { it.name == w.city },
                                    Modifier.size(30.dp).bounceClick { toggleFav() }
                                )
                            }
                            Text(tempAnim(w.temp, fahrenheit), fontSize = 96.sp, fontWeight = FontWeight.Light, color = Color.White)
                            Text(describe(w.code), fontSize = 22.sp, color = Color.White)
                            Spacer(Modifier.height(20.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                                Stat("ОЩУЩАЕТСЯ", tempAnim(w.feels, fahrenheit))
                                Stat("ВЛАЖНОСТЬ", "${w.humidity}%")
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().liquidGlass(backdrop).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Windmill(w.wind, Modifier.size(72.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("ВЕТЕР", color = Dim, fontSize = 11.sp, letterSpacing = 1.5.sp)
                                Text(
                                    windAnim(w.wind, ms), fontSize = 30.sp, fontWeight = FontWeight.Normal,
                                    color = Color.White, maxLines = 1, softWrap = false
                                )
                                Text(windWord(w.wind), color = Dim, fontSize = 15.sp, maxLines = 1, softWrap = false)
                                Text(
                                    "${windDirName(w.windDir)} · ${w.windDir}°",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 15.sp, maxLines = 1, softWrap = false
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            WindCompass(w.windDir, Modifier.size(54.dp))
                        }
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth().glassButton(backdrop) { showMap = true }.padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("КАРТА ОСАДКОВ", color = Dim, fontSize = 11.sp, letterSpacing = 1.5.sp)
                                Text("Радар за последний час", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Light)
                            }
                            WIcon(IconKind.RAIN, Modifier.size(34.dp))
                        }
                    }
                    item {
                        Column(Modifier.fillMaxWidth().liquidGlass(backdrop).padding(vertical = 20.dp)) {
                            Text("ПО ЧАСАМ", color = Dim, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(horizontal = 24.dp))
                            Spacer(Modifier.height(14.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                items(w.hours) { h ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(h.label, color = Dim, fontSize = 13.sp)
                                        Spacer(Modifier.height(10.dp))
                                        WIcon(kindFor(h.code, h.isDay, h.event), Modifier.size(28.dp))
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            if (h.event == 1) "Восход" else if (h.event == 2) "Закат" else tempAnim(h.temp, fahrenheit),
                                            color = Color.White, fontSize = if (h.event == 0) 19.sp else 14.sp, fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(Modifier.fillMaxWidth().liquidGlass(backdrop).padding(horizontal = 24.dp, vertical = 8.dp)) {
                            w.days.forEachIndexed { i, day ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (i == 0) "Сегодня" else weekday(day.date), color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                                    Text(describe(day.code), color = Dim, fontSize = 15.sp, modifier = Modifier.weight(1.3f))
                                    Text(tempAnim(day.max, fahrenheit), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                    Text("  ${tempAnim(day.min, fahrenheit)}", color = Dim, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                }
                                if (i < w.days.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showMap,
            enter = fadeIn(tween(250)) + scaleIn(tween(250), 0.94f),
            exit = fadeOut(tween(200)) + scaleOut(tween(200), 0.94f)
        ) {
            data?.let { RadarMap(it.lat, it.lon, it.city) { showMap = false } }
        }
    }
    }
}
