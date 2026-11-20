package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Замер одного тейка «П»: как быстро слова появлялись у Google и у компа, как ехал звук домой и сколько
 * ждали итог (20.11.2026). Владелец: «ты прямо будешь смотреть, как быстро расшифровывается у меня всё…
 * сколько он ушёл с телефона, сколько он пришёл… реально прямо вот сравнение сделать».
 *
 * Все времена — мс от тапа «П» (часы телефона, `elapsedRealtime`), и только на телефоне: часы компа с
 * ними не сравнить, поэтому дорогу считаем по полному кругу «кусок ушёл → ответ пришёл» (`rtt`), а из
 * ответа вычитаем, сколько комп держал запрос (`srv`): остаток — сеть туда и обратно.
 *
 * Сравнение строк — по словам: «k-е слово» у Google появилось в такой-то миг, у компа — в такой-то;
 * разница по всем k — на сколько одна строка быстрее другой. Слова у двух движков не одни и те же, но
 * идут в одном темпе речи, и по сотне слов медиана честная. Ещё у компа точно известно, до какой
 * секунды звука досчитана строка, — отставание строки от речи (`pc_lag`).
 *
 * Итог — JSON в «Расшифровки» и архив (`timing` у `pravka.take`, вид `life.dictations.timing`).
 * Без Android, под тестом (`TakeTimingTest`).
 */
class TakeTiming(private val startAt: Long) {

    private val gWords = ArrayList<Long>()
    private val pcWords = ArrayList<Long>()
    private val pcLag = ArrayList<Long>()
    private val rtt = ArrayList<Long>()
    private val srv = ArrayList<Long>()
    private val pass = ArrayList<Long>()
    private var retries = 0
    private var stopAt = 0L
    private var googleFinalAt = 0L
    private var pcFinalAt = 0L
    private var waitMs = -1L
    private var finalPcServerMs = -1L

    private fun rel(at: Long) = (at - startAt).coerceAtLeast(0L)

    /** Строка Google сейчас такая — новые слова получили время [at]. */
    @Synchronized fun google(at: Long, text: String) = grow(gWords, at, PcAsr.words(text))

    /** Строка компа пришла в [at]: слов [words], досчитана до звука, который прозвучал в [audioAt]. */
    @Synchronized fun pc(at: Long, words: Int, audioAt: Long, passMs: Long) {
        grow(pcWords, at, words)
        if (audioAt > 0L) pcLag += (at - audioAt).coerceAtLeast(0L)
        if (passMs >= 0) pass += passMs
    }

    /** Кусок звука: полный круг [rttMs], из них комп держал [serverMs]. */
    @Synchronized fun chunk(rttMs: Long, serverMs: Long) {
        rtt += rttMs
        if (serverMs >= 0) srv += serverMs
    }

    @Synchronized fun retry() { retries++ }
    @Synchronized fun stop(at: Long) { if (stopAt == 0L) stopAt = at }
    @Synchronized fun googleFinal(at: Long) { googleFinalAt = at }

    /** Итог компа пришёл в [at] (ждали после Google [waited] мс; сам счёт на компе — [serverMs]). */
    @Synchronized fun pcFinal(at: Long, waited: Long, serverMs: Long) {
        pcFinalAt = at
        waitMs = waited
        finalPcServerMs = serverMs
    }

    @Synchronized fun waited(ms: Long) { waitMs = ms }

    private fun grow(list: ArrayList<Long>, at: Long, words: Int) {
        while (list.size < words && list.size < MAX_WORDS) list += rel(at)
    }

    /** Итог замера: [line] — чья строка выбрана, [inserted] — чей текст вставлен, [mux] — сколько чья строка была в пилюле. */
    @Synchronized fun summary(line: String, inserted: String, mux: LineMux?): JSONObject = JSONObject().apply {
        put("v", 1)
        put("line", line)
        put("final", inserted)
        gWords.firstOrNull()?.let { put("g_first", it) }
        pcWords.firstOrNull()?.let { put("pc_first", it) }
        val n = minOf(gWords.size, pcWords.size)
        if (n > 0) {
            val d = (0 until n).map { pcWords[it] - gWords[it] }
            put("word_lag_med", median(d))
            put("word_lag_p90", pct(d, 90))
        }
        if (pcLag.isNotEmpty()) {
            put("pc_lag_med", median(pcLag))
            put("pc_lag_p90", pct(pcLag, 90))
        }
        if (rtt.isNotEmpty()) {
            put("chunks", rtt.size)
            put("rtt_med", median(rtt))
            put("rtt_p90", pct(rtt, 90))
            put("rtt_max", rtt.max())
        }
        if (srv.isNotEmpty()) {
            put("srv_med", median(srv))
            if (srv.size == rtt.size) put("net_med", median(rtt.zip(srv) { a, b -> a - b }))
        }
        if (pass.isNotEmpty()) put("pass_med", median(pass))
        if (retries > 0) put("retries", retries)
        if (stopAt > 0L) {
            put("stop", rel(stopAt))
            if (googleFinalAt > 0L) put("stop_g", googleFinalAt - stopAt)
            if (pcFinalAt > 0L) put("stop_pc", pcFinalAt - stopAt)
        }
        if (waitMs >= 0) put("wait", waitMs)
        if (finalPcServerMs >= 0) put("pc_final_srv", finalPcServerMs)
        mux?.let {
            put("pc_shown_ms", it.pcShownMs)
            put("fallback_ms", if (line == PcAsr.FINAL_PC) it.googleShownMs else 0L)
            if (it.fallbacks > 0) put("fallbacks", it.fallbacks)
        }
        put("g_words", JSONArray(gWords))
        put("pc_words", JSONArray(pcWords))
    }

    companion object {
        /** Слов в рядах не больше — пятиминутный тейк, дальше ряды не растут (сводка всё равно честная). */
        const val MAX_WORDS = 800

        fun median(xs: List<Long>): Long = xs.sorted()[xs.size / 2]

        fun pct(xs: List<Long>, p: Int): Long {
            val s = xs.sorted()
            return s[((s.size - 1) * p / 100.0).let { Math.round(it).toInt() }.coerceIn(0, s.size - 1)]
        }
    }
}
