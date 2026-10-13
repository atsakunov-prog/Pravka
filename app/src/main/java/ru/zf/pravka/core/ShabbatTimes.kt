package ru.zf.pravka.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Время Шаббата для плитки «Сегодня» (Правка 5.0, этап 2; холст `Main`,
 * разложенный — «17:09 пт, свечи · исход сб 18:20»). Правда о главе и
 * праздниках — у сервиса «Шаббат» на компе (hebcal, глава с Раши); у телефона
 * своей дороги к нему пока нет, а свечи и исход — чистая астрономия по
 * координатам города погоды, поэтому плитка считает их сама и без сети.
 *
 * Свечи — за 18 минут до заката пятницы (обычай Хабада и большинства общин);
 * исход — когда солнце в субботу на 8,5° под горизонтом (появились три звезды
 * средней величины — тоже Хабад). Закат — по алгоритму «Almanac for Computers»
 * (US Naval Observatory, 1990): точность — минута-две, плитке хватает; точное
 * время — на странице Шаббата, плитка ведёт туда.
 */
object ShabbatTimes {

    /** Свечи — за столько минут до заката. */
    const val CANDLES_BEFORE_MIN = 18L
    /** Зенит заката: верхний край диска с рефракцией. */
    const val SUNSET_ZENITH = 90.833
    /** Зенит исхода: солнце на 8,5° под горизонтом. */
    const val EXIT_ZENITH = 98.5

    /** Ближайший Шаббат: пятница, свечи, исход; [now] — внутри него. */
    data class Times(val friday: LocalDate, val candles: Long, val exit: Long, val now: Boolean)

    /**
     * Шаббат, который идёт или будет следующим, на миг [now]: в субботу до
     * исхода — нынешний, после исхода — через неделю.
     */
    fun next(now: Long, lat: Double, lon: Double, zone: ZoneId = ZoneId.systemDefault()): Times? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val back = (today.dayOfWeek.value - DayOfWeek.FRIDAY.value + 7) % 7
        var friday = today.minusDays(back.toLong())
        repeat(2) {
            val candles = sunTime(friday, lat, lon, SUNSET_ZENITH, rising = false)?.minus(CANDLES_BEFORE_MIN * 60_000L) ?: return null
            val exit = sunTime(friday.plusDays(1), lat, lon, EXIT_ZENITH, rising = false) ?: return null
            if (now < exit) return Times(friday, candles, exit, now >= candles)
            friday = friday.plusDays(7)
        }
        return null
    }

    /**
     * Восход или заход солнца в день [date] для зенита [zenithDeg], миг в мс
     * (UTC). null — солнце в этот день до такого зенита не доходит (полярные
     * дни и ночи).
     */
    fun sunTime(date: LocalDate, lat: Double, lon: Double, zenithDeg: Double, rising: Boolean): Long? {
        val rad = Math.PI / 180
        val n = date.dayOfYear
        val lngHour = lon / 15
        val t = n + ((if (rising) 6.0 else 18.0) - lngHour) / 24
        val m = 0.9856 * t - 3.289
        val l = norm360(m + 1.916 * sin(m * rad) + 0.020 * sin(2 * m * rad) + 282.634)
        var ra = norm360(atan(0.91764 * tan(l * rad)) / rad)
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15
        val sinDec = 0.39782 * sin(l * rad)
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(zenithDeg * rad) - sinDec * sin(lat * rad)) / (cosDec * cos(lat * rad))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (rising) 360 - acos(cosH) / rad else acos(cosH) / rad) / 15
        val local = h + ra - 0.06571 * t - 6.622
        val ut = ((local - lngHour) % 24 + 24) % 24
        val midnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return midnight + (ut * 3_600_000).toLong()
    }

    private fun norm360(x: Double): Double = ((x % 360) + 360) % 360
}
