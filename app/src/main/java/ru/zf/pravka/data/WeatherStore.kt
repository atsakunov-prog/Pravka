package ru.zf.pravka.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.zf.pravka.core.WeatherDay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Погода для «Сегодня» (Правка 4.0, 07.10.2026; TASK «Данные»): своего
 * источника у приложения не было. Open-Meteo — без ключа и без библиотек,
 * через `HttpURLConnection`. Город из настроек переводится в координаты
 * геокодингом Open-Meteo один раз (пока город тот же); прогноз живёт в кэше
 * час. Без сети — последний кэш, без кэша — ряд скрыт (null): погоду не
 * выдумываем.
 *
 * Кэш — файл `weather.json` в базе (`DataRoot.dir`): расходный, его можно
 * стереть без потерь.
 */
class WeatherStore(private val context: Context) {

    private val file: File get() = File(DataRoot.dir(context), "weather.json")

    data class Result(val summary: WeatherDay.Summary?, val stale: Boolean, val error: String?)

    /** Сводка на [day] для города [city]; [force] — мимо часового кэша. */
    suspend fun today(city: String, day: LocalDate = LocalDate.now(), force: Boolean = false): Result = withContext(Dispatchers.IO) {
        if (city.isBlank()) return@withContext Result(null, false, null)
        val cache = runCatching { JSONObject(file.readText()) }.getOrNull()
        val fresh = cache != null && cache.optString("city") == city &&
            System.currentTimeMillis() - cache.optLong("at") < CACHE_MS && cache.has("forecast")
        if (fresh && !force) return@withContext Result(parse(cache!!.getJSONObject("forecast"), day), false, null)
        val out = runCatching {
            val (lat, lon) = coords(city, cache)
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&hourly=temperature_2m,precipitation,precipitation_probability,weather_code" +
                "&timezone=auto&forecast_days=3"
            val forecast = JSONObject(get(url))
            val save = JSONObject()
                .put("city", city).put("lat", lat).put("lon", lon)
                .put("at", System.currentTimeMillis())
                .put("forecast", forecast)
            runCatching { file.writeText(save.toString()) }
            Result(parse(forecast, day), false, null)
        }
        out.getOrElse { e ->
            val old = cache?.optJSONObject("forecast")?.takeIf { cache.optString("city") == city }
            Result(old?.let { parse(it, day) }, stale = true, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Координаты города — из кэша, если город тот же, иначе геокодинг Open-Meteo. */
    private fun coords(city: String, cache: JSONObject?): Pair<Double, Double> {
        if (cache != null && cache.optString("city") == city && cache.has("lat")) {
            return cache.getDouble("lat") to cache.getDouble("lon")
        }
        val q = URLEncoder.encode(city, "UTF-8")
        val r = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=ru&format=json"))
        val first = r.optJSONArray("results")?.optJSONObject(0) ?: error("город «$city» не нашёлся")
        return first.getDouble("latitude") to first.getDouble("longitude")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        try {
            val code = c.responseCode
            if (code !in 200..299) {
                val body = c.errorStream?.bufferedReader()?.readText().orEmpty()
                error("HTTP $code ${body.take(200)}")
            }
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val CACHE_MS = 3_600_000L

        /** Часы дня [day] из ответа Open-Meteo (`hourly.time` — местное время города). */
        fun parse(forecast: JSONObject, day: LocalDate): WeatherDay.Summary? {
            val h = forecast.optJSONObject("hourly") ?: return null
            val time = h.optJSONArray("time") ?: return null
            val temp = h.optJSONArray("temperature_2m") ?: return null
            val pr = h.optJSONArray("precipitation")
            val prob = h.optJSONArray("precipitation_probability")
            val code = h.optJSONArray("weather_code")
            val hours = (0 until time.length()).mapNotNull { i ->
                val t = runCatching { LocalDateTime.parse(time.getString(i)) }.getOrNull() ?: return@mapNotNull null
                if (t.toLocalDate() != day) return@mapNotNull null
                WeatherDay.Hour(
                    hour = t.hour,
                    temp = temp.optDouble(i, Double.NaN).takeIf { !it.isNaN() } ?: return@mapNotNull null,
                    precipMm = pr?.optDouble(i, 0.0) ?: 0.0,
                    prob = prob?.optInt(i, 0) ?: 0,
                    code = code?.optInt(i, 3) ?: 3,
                )
            }
            return WeatherDay.summary(hours, LocalDateTime.now().hour)
        }
    }
}
