package ru.zf.pravka.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

// Курс ЦБ на день — для надиктованного в валюте («двадцать долларов за
// Claude»). Это ПРЕДВАРИТЕЛЬНЫЕ рубли: настоящие придут со сверкой (у
// Тинькова — его пересчёт, у «Плати по миру» — курс своего пополнения), и
// запись так и помечена. Поэтому здесь нет ни хранения на диск, ни ретраев:
// не вышло — `MoneyVoice.ROUGH`, и это честно видно на записи.
class CbrRates(private val http: OkHttpClient, private val log: (String) -> Unit = {}) {

    private val cache = ConcurrentHashMap<LocalDate, Map<String, Double>>()
    private val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** Курсы на [day]: код валюты → рублей за одну единицу. Пусто — не достали. */
    suspend fun on(day: LocalDate): Map<String, Double> {
        cache[day]?.let { return it }
        val parsed = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("https://www.cbr.ru/scripts/XML_daily.asp?date_req=" + day.format(fmt))
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    // ЦБ отдаёт windows-1251 — байты, не строка из заголовка.
                    parse(String(resp.body!!.bytes(), charset("windows-1251")))
                }
            }.onFailure { log("деньги: курс ЦБ на $day не достал — ${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(emptyMap())
        }
        if (parsed.isNotEmpty()) cache[day] = parsed
        return parsed
    }

    companion object {
        private val VALUTE = Regex(
            "<CharCode>([A-Z]{3})</CharCode>\\s*<Nominal>(\\d+)</Nominal>.*?<Value>([\\d,]+)</Value>",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** «Nominal» не всегда 1: драмы идут за сотню — делим, иначе курс в сто раз мимо. */
        fun parse(xml: String): Map<String, Double> = VALUTE.findAll(xml).mapNotNull { m ->
            val nominal = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val value = m.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            if (nominal <= 0) null else m.groupValues[1] to value / nominal
        }.toMap()
    }
}
