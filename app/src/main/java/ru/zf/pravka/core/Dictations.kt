package ru.zf.pravka.core

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import org.json.JSONObject

/**
 * История диктовок — экран «Правка» (Правка 5.0, этап 5, план §4; 13.10.2026): все диктовки
 * владельца с телефона и с компьютера, одна на сервере. Здесь — без Android: разбор журналов
 * телефона и записей компа, склейка тейка с его чисткой, фильтр, поиск, итоги дня, подписи.
 *
 * Откуда что. Телефон: тейк — строка `transcriptions.jsonl` (`TranscriptionLog`), чистка —
 * строка `history.jsonl` (`HistoryLog`); связи между ними в журналах нет — чистка пишется
 * секундами позже, когда ответил Claude, поэтому тейк находит свою чистку по времени и по
 * словам ([phone]). Компьютер («Диктовка» на ПК): `pravka.take` и `pravka.clean` из архива с
 * одним ключом «pc-<мс>» — склейка по ключу ([pc]); телефон забирает их `/sync`
 * (`data/PcDictations.kt`), свои телефонные с сервера не берёт — они у него и так.
 */
object Dictations {

    /** Откуда диктовка: телефон своим микрофоном, гарнитура, компьютер. */
    enum class Origin { PHONE, HEADSET, PC }

    /** Фильтр экрана: все, телефон (с гарнитурой), компьютер. */
    enum class Filter(val label: String) { ALL("Все"), PHONE("Телефон"), PC("Компьютер") }

    /** Одна диктовка целиком: что сказал, что записалось, чем распознано и почищено. */
    data class Item(
        /** Ключ строки: у телефона «t<мс>-<номер>», у компа — ключ архива «pc-<мс>». */
        val key: String,
        val atMs: Long,
        val origin: Origin,
        /** Короткое имя источника: «телефон», «Shokz», «компьютер». */
        val device: String,
        /** Движок распознавания как записан: «google», «gigaam cpu по ходу»… */
        val engine: String,
        val audioMs: Long,
        /** «Сказал» — распознанное до чистки. */
        val said: String,
        /** «Записалось» — после чистки (у компа без Сонета — после словаря); null — не чистилось. */
        val wrote: String?,
        val ok: Boolean,
        val error: String? = null,
        val cleanModel: String? = null,
        val cleanUsd: Double = 0.0,
        val cleanMs: Long = 0,
        val cleanError: String? = null,
        /** Режим чистки: CLEAN, BUSINESS, SOFTEN (у компа ещё стиль prose). */
        val cleanMode: String? = null,
        /** Только у компа: pasted, clipboard, cancelled, empty, error. */
        val status: String? = null,
        /** Только у телефона: звук тейка в `TakeAudio` (имя файла). */
        val audio: String? = null,
    ) {
        /** Что показывать строкой списка: записавшееся, а нет его — сказанное. */
        val text: String get() = wrote?.takeIf { it.isNotBlank() } ?: said
    }

    /** Тейк телефона — строка `transcriptions.jsonl`. */
    data class Take(
        val ms: Long,
        val engine: String,
        val audioMs: Long,
        val text: String,
        val ok: Boolean,
        val error: String?,
        val mic: String?,
        val audio: String?,
    )

    /** Чистка телефона — строка `history.jsonl`. */
    data class Clean(
        val ms: Long,
        val mode: String,
        val model: String,
        val input: String,
        val output: String,
        val latencyMs: Long,
        val costUsd: Double,
        val error: String?,
    )

    /** Чистки диктовки — режимы кнопки «П»; ассистент и прочее диктовкой не считаются. */
    private val DICTATION_MODES = setOf("CLEAN", "BUSINESS", "SOFTEN")

    /** Окно, в котором тейк ищет свою чистку: Claude отвечает секундами, длинная — до минут. */
    private const val CLEAN_BEFORE_MS = 2_000L
    private const val CLEAN_AFTER_MS = 180_000L

    /** Время строки журнала телефона («2026-10-13T12:44:05+03:00»); не разобралось — 0. */
    fun stamp(ts: String): Long = runCatching { OffsetDateTime.parse(ts).toInstant().toEpochMilli() }.getOrDefault(0L)

    fun takeOf(o: JSONObject): Take? {
        val ms = stamp(o.optString("ts"))
        if (ms == 0L) return null
        return Take(
            ms = ms,
            engine = o.optString("engine"),
            audioMs = o.optLong("audio_ms"),
            text = o.optString("text"),
            ok = o.optBoolean("ok", true),
            error = o.optString("error").takeIf { o.has("error") && it.isNotBlank() },
            mic = o.optString("mic").takeIf { o.has("mic") && it.isNotBlank() },
            audio = o.optString("audio").takeIf { o.has("audio") && it.isNotBlank() },
        )
    }

    fun cleanOf(o: JSONObject): Clean? {
        val ms = stamp(o.optString("ts"))
        if (ms == 0L) return null
        return Clean(
            ms = ms,
            mode = o.optString("mode"),
            model = o.optString("model"),
            input = o.optString("input"),
            output = o.optString("output"),
            latencyMs = o.optLong("latency_ms"),
            costUsd = o.optDouble("cost_usd", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            error = o.optString("error").takeIf { o.has("error") && it.isNotBlank() },
        )
    }

    /**
     * Диктовки телефона: каждый тейк и его чистка. Чистка — первая свободная в окне
     * [CLEAN_BEFORE_MS]…[CLEAN_AFTER_MS] после тейка, в которой есть хотя бы половина его слов
     * (на вход модели идёт текст после словаря, а в поле мог быть и прежний текст). Тейк без
     * чистки — тоже диктовка (Засечка, еда, дела: их разбирает не чистка «П»).
     */
    fun phone(takes: List<Take>, cleans: List<Clean>): List<Item> {
        val cs = cleans.filter { it.mode in DICTATION_MODES }.sortedBy { it.ms }
        val used = BooleanArray(cs.size)
        var from = 0
        val out = ArrayList<Item>(takes.size)
        for ((n, t) in takes.sortedBy { it.ms }.withIndex()) {
            while (from < cs.size && cs[from].ms < t.ms - CLEAN_BEFORE_MS) from++
            var hit = -1
            if (t.ok && t.text.isNotBlank()) {
                val words = words(t.text)
                var j = from
                while (j < cs.size && cs[j].ms <= t.ms + CLEAN_AFTER_MS) {
                    if (!used[j] && overlap(words, cs[j].input) >= 0.5) { hit = j; break }
                    j++
                }
            }
            val c = if (hit >= 0) cs[hit].also { used[hit] = true } else null
            val (origin, device) = deviceOf(t.mic)
            out += Item(
                key = "t${t.ms}-$n",
                atMs = t.ms,
                origin = origin,
                device = device,
                engine = t.engine,
                audioMs = t.audioMs,
                said = t.text,
                wrote = c?.let { if (it.error == null) it.output.ifBlank { null } else null },
                ok = t.ok && t.text.isNotBlank(),
                error = t.error ?: if (t.text.isBlank()) "пустой результат" else null,
                cleanModel = c?.model?.ifBlank { null },
                cleanUsd = c?.costUsd ?: 0.0,
                cleanMs = c?.latencyMs ?: 0L,
                cleanError = c?.error,
                cleanMode = c?.mode,
                audio = t.audio,
            )
        }
        return out
    }

    /**
     * Диктовки компа из архива: тейки и чистки по ключу «pc-<мс>». «Записалось» — выход Сонета,
     * а если его не звали — что ушло в окно (`final`, после словаря).
     */
    fun pc(takes: Map<String, JSONObject>, cleans: Map<String, JSONObject>): List<Item> =
        takes.mapNotNull { (key, d) ->
            val ms = stamp(d.optString("at")).takeIf { it > 0 } ?: d.optLong("id").takeIf { it > 0 } ?: return@mapNotNull null
            val c = cleans[key]
            val cleaned = c?.optString("output")?.takeIf { it.isNotBlank() && c.optString("error").isBlank() }
            val said = d.optString("text")
            val ok = d.optBoolean("ok", said.isNotBlank())
            Item(
                key = key,
                atMs = ms,
                origin = Origin.PC,
                device = "компьютер",
                engine = d.optString("engine"),
                audioMs = d.optLong("audio_ms"),
                said = said,
                wrote = cleaned ?: d.optString("final").trim().takeIf { it.isNotBlank() && it != said.trim() },
                ok = ok,
                error = d.optString("error").ifBlank { null },
                cleanModel = c?.optString("model")?.ifBlank { null },
                cleanUsd = c?.optDouble("cost_usd", 0.0)?.takeIf { !it.isNaN() } ?: 0.0,
                cleanMs = c?.optLong("latency_ms") ?: 0L,
                cleanError = c?.optString("error")?.ifBlank { null },
                cleanMode = c?.let { cl -> cl.optString("style").ifBlank { cl.optString("mode") }.ifBlank { null } },
                status = d.optString("status").ifBlank { null },
            )
        }

    /** Всё вместе, свежие сверху. */
    fun merge(vararg parts: List<Item>): List<Item> = parts.flatMap { it }.sortedByDescending { it.atMs }

    /** Фильтр «все / телефон / компьютер» и поиск по сказанному и записавшемуся (ё = е, регистр не важен). */
    fun view(items: List<Item>, filter: Filter, query: String = ""): List<Item> {
        val q = norm(query.trim())
        return items.filter { i ->
            when (filter) {
                Filter.ALL -> true
                Filter.PHONE -> i.origin != Origin.PC
                Filter.PC -> i.origin == Origin.PC
            } && (q.isEmpty() || norm(i.said).contains(q) || norm(i.wrote.orEmpty()).contains(q))
        }
    }

    /** Итог дня: сколько диктовок, сколько в них голоса, сколько не распознано. */
    data class DayStats(val count: Int, val audioMs: Long, val missed: Int)

    fun stats(items: List<Item>, day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayStats {
        val of = items.filter { dayOf(it.atMs, zone) == day }
        return DayStats(of.size, of.sumOf { it.audioMs }, of.count { !it.ok })
    }

    fun dayOf(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** Дни подряд, свежие сверху, внутри — свежие сверху. */
    fun byDay(items: List<Item>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate, List<Item>>> =
        items.groupBy { dayOf(it.atMs, zone) }.toSortedMap(compareByDescending { it }).map { (d, l) -> d to l.sortedByDescending { it.atMs } }

    // ------------------------------------------------------------------ подписи

    /**
     * Кто слушал: «телефон», «гарнитура «OpenComm2 by Shokz_II»», при смене — «телефон → …»
     * (`TakeHealth`). Важно последнее; имя гарнитуры — коротко: «Shokz».
     */
    fun deviceOf(mic: String?): Pair<Origin, String> {
        val last = mic?.substringAfterLast("→")?.trim().orEmpty()
        if (!last.contains("гарнитур")) return Origin.PHONE to "телефон"
        val name = last.substringAfter("«", "").substringBeforeLast("»", "").trim()
        return Origin.HEADSET to shortHeadset(name)
    }

    /** «OpenComm2 by Shokz_II» → «Shokz»; без имени — «гарнитура». */
    fun shortHeadset(name: String): String {
        if (name.isBlank()) return "гарнитура"
        val brand = if (name.contains(" by ")) name.substringAfter(" by ") else name
        return brand.replace(Regex("_[A-Za-z0-9]+$"), "").trim().take(18).ifBlank { "гарнитура" }
    }

    /** Чем распознано — словами: «Google, по сети», «GigaAM-v3, по ходу речи», «Whisper medium, видеокарта». */
    fun engineLabel(engine: String): String {
        val e = engine.trim()
        return when {
            e.isEmpty() -> "—"
            e == "google" -> "Google"
            e == "google-net" -> "Google, по сети"
            e == "google-replay" -> "Google, заново из звука"
            e == "whisper-small" -> "Whisper small"
            e == "whisper-base" -> "Whisper base"
            else -> {
                // Движки компа: «gigaam cpu по ходу», «small cuda», «whisper-large-v3» (Groq).
                val name = when {
                    e.startsWith("gigaam") -> "GigaAM-v3"
                    e.contains("large-v3") -> "Groq large-v3"
                    e.startsWith("small") -> "Whisper small"
                    e.startsWith("medium") -> "Whisper medium"
                    else -> return e
                }
                buildString {
                    append(name)
                    if (" cuda" in e) append(", видеокарта")
                    if ("по ходу" in e) append(", по ходу речи")
                }
            }
        }
    }

    /** Цена чистки: «$0,011», «$0,02», «<$0,001»; ноль — null. */
    fun usd(v: Double): String? {
        if (v <= 0.0) return null
        if (v < 0.0005) return "<\$0,001"
        var s = String.format(Locale.US, "%.3f", v)
        if (s.endsWith("0")) s = s.dropLast(1)
        return "\$" + s.replace('.', ',')
    }

    /** Длина: «15 с», «3 мин 55 с», «1 ч 2 мин». */
    fun duration(ms: Long): String {
        val s = ((ms + 500) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s с"
            s < 3600 -> if (s % 60 == 0L) "${s / 60} мин" else "${s / 60} мин ${s % 60} с"
            else -> "${s / 3600} ч ${(s % 3600) / 60} мин"
        }
    }

    /** Статус диктовки компа словами; у телефона — null. */
    fun statusLabel(status: String?): String? = when (status) {
        "pasted" -> "вставлено в окно"
        "clipboard" -> "оставлено в буфере"
        "cancelled" -> "отменено"
        "empty" -> "ничего не разобрал"
        "error" -> "распознавание упало"
        else -> null
    }

    // ------------------------------------------------------------------ слова

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    private fun norm(s: String): String = s.lowercase(Locale.forLanguageTag("ru")).replace('ё', 'е')

    private fun words(s: String): Set<String> = WORD.findAll(norm(s)).map { it.value }.filter { it.length >= 2 }.toSet()

    /** Доля слов тейка, нашедшихся во входе чистки. */
    internal fun overlap(take: Set<String>, input: String): Double {
        if (take.isEmpty()) return 0.0
        val inp = words(input)
        return take.count { it in inp }.toDouble() / take.size
    }
}
