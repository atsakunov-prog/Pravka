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
 *
 * 07.10.2026 (баги №4, №9): в запросе ещё «ощущается как» по часам и
 * дневной прогноз на десять дней — для листа по тапу на погоду. Кэш без
 * дневного прогноза (прежняя сборка) считается несвежим и обновляется.
 */
class WeatherStore(private val context: Context) {

    private val file: File get() = File(DataRoot.dir(context), "weather.json")

    /**
     * Сводка дня для ряда, часы этого дня и десять дней вперёд — для листа.
     * [city] — как город назван в настройках (подзаголовок листа).
     */
    data class Result(
        val summary: WeatherDay.Summary?,
        val stale: Boolean,
        val error: String?,
        val hours: List<WeatherDay.Hour> = emptyList(),
        val days: List<WeatherDay.Day> = emptyList(),
        val city: String = "",
    )

    /** Сводка на [day] для города [city]; [force] — мимо часового кэша. */
    suspend fun today(city: String, day: LocalDate = LocalDate.now(), force: Boolean = false): Result = withContext(Dispatchers.IO) {
        if (city.isBlank()) return@withContext Result(null, false, null)
        val cache = runCatching { JSONObject(file.readText()) }.getOrNull()
        val fresh = cache != null && cache.optString("city") == city &&
            System.currentTimeMillis() - cache.optLong("at") < CACHE_MS &&
            cache.optJSONObject("forecast")?.has("daily") == true
        if (fresh && !force) return@withContext result(cache!!.getJSONObject("forecast"), day, city, stale = false, error = null)
        val out = runCatching {
            val (lat, lon) = coords(city, cache)
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&hourly=temperature_2m,apparent_temperature,precipitation,precipitation_probability,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max," +
                "apparent_temperature_min,precipitation_sum,precipitation_probability_max" +
                "&timezone=auto&forecast_days=$DAYS"
            val forecast = JSONObject(get(url))
            val save = JSONObject()
                .put("city", city).put("lat", lat).put("lon", lon)
                .put("at", System.currentTimeMillis())
                .put("forecast", forecast)
            runCatching { file.writeText(save.toString()) }
            result(forecast, day, city, stale = false, error = null)
        }
        out.getOrElse { e ->
            val old = cache?.optJSONObject("forecast")?.takeIf { cache.optString("city") == city }
            val why = "${e.javaClass.simpleName}: ${e.message}"
            if (old != null) result(old, day, city, stale = true, error = why) else Result(null, stale = true, error = why, city = city)
        }
    }

    private fun result(forecast: JSONObject, day: LocalDate, city: String, stale: Boolean, error: String?): Result {
        val hours = hours(forecast, day)
        val now = LocalDateTime.now()
        return Result(
            summary = WeatherDay.summary(hours, now.hour),
            stale = stale,
            error = error,
            hours = hours,
            days = days(forecast).filter { !it.date.isBefore(now.toLocalDate()) },
            city = city,
        )
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

        private const val DAYS = 10

        /** Сводка дня [day] для ряда «Сегодня». */
        fun parse(forecast: JSONObject, day: LocalDate): WeatherDay.Summary? =
            WeatherDay.summary(hours(forecast, day), LocalDateTime.now().hour)

        /** Часы дня [day] из ответа Open-Meteo (`hourly.time` — местное время города). */
        fun hours(forecast: JSONObject, day: LocalDate): List<WeatherDay.Hour> {
            val h = forecast.optJSONObject("hourly") ?: return emptyList()
            val time = h.optJSONArray("time") ?: return emptyList()
            val temp = h.optJSONArray("temperature_2m") ?: return emptyList()
            val feels = h.optJSONArray("apparent_temperature")
            val pr = h.optJSONArray("precipitation")
            val prob = h.optJSONArray("precipitation_probability")
            val code = h.optJSONArray("weather_code")
            return (0 until time.length()).mapNotNull { i ->
                val t = runCatching { LocalDateTime.parse(time.getString(i)) }.getOrNull() ?: return@mapNotNull null
                if (t.toLocalDate() != day) return@mapNotNull null
                WeatherDay.Hour(
                    hour = t.hour,
                    temp = temp.optDouble(i, Double.NaN).takeIf { !it.isNaN() } ?: return@mapNotNull null,
                    precipMm = pr?.optDouble(i, 0.0) ?: 0.0,
                    prob = prob?.optInt(i, 0) ?: 0,
                    code = code?.optInt(i, 3) ?: 3,
                    feels = feels?.optDouble(i, Double.NaN) ?: Double.NaN,
                )
            }
        }

        /** Десять дней из `daily` Open-Meteo; нет блока (старый кэш) — пусто. */
        fun days(forecast: JSONObject): List<WeatherDay.Day> {
            val d = forecast.optJSONObject("daily") ?: return emptyList()
            val time = d.optJSONArray("time") ?: return emptyList()
            val max = d.optJSONArray("temperature_2m_max") ?: return emptyList()
            val min = d.optJSONArray("temperature_2m_min") ?: return emptyList()
            val code = d.optJSONArray("weather_code")
            val fMax = d.optJSONArray("apparent_temperature_max")
            val fMin = d.optJSONArray("apparent_temperature_min")
            val sum = d.optJSONArray("precipitation_sum")
            val prob = d.optJSONArray("precipitation_probability_max")
            return (0 until time.length()).mapNotNull { i ->
                val date = runCatching { LocalDate.parse(time.getString(i)) }.getOrNull() ?: return@mapNotNull null
                val hi = max.optDouble(i, Double.NaN).takeIf { !it.isNaN() } ?: return@mapNotNull null
                val lo = min.optDouble(i, Double.NaN).takeIf { !it.isNaN() } ?: return@mapNotNull null
                WeatherDay.Day(
                    date = date,
                    code = code?.optInt(i, 3) ?: 3,
                    min = lo,
                    max = hi,
                    feelsMin = fMin?.optDouble(i, Double.NaN) ?: Double.NaN,
                    feelsMax = fMax?.optDouble(i, Double.NaN) ?: Double.NaN,
                    precipMm = sum?.optDouble(i, 0.0) ?: 0.0,
                    prob = prob?.optInt(i, 0) ?: 0,
                )
            }
        }
    }
}
