package ru.zf.pravka.core

/**
 * Чья строка в пилюле «П»: компа (GigaAM по ходу речи) или Google (20.11.2026). Владелец: «у меня же в
 * компьютере всё прямо показывается по моментам, когда я диктую. И это с Гигой… Давай тоже с
 * переключением сделаем».
 *
 * Правило: при выборе «Компьютер» показываем строку компа, пока она свежая (приходила не дольше
 * [STALE_MS] назад). Перестала приходить (дорога, метро) — строка Google, пока комп не оживёт. В начале
 * тейка первые слова у Google раньше (комп считает с секунды звука и ещё везёт по сети) — даём компу
 * [FIRST_WAIT_MS] от первого слова Google, а потом уже Google; так строка не прыгает «Google → комп» на
 * каждом тейке. Сколько времени показывали чью строку — для замера и для сторожа: строка компа
 * застряла ([stuck]) два тейка подряд — выбор сам на Google, как у итога (`PcAsr.Breaker`).
 *
 * Без Android: время — снаружи, под тестом (`LineMuxTest`).
 */
class LineMux(val pcMode: Boolean) {

    enum class Source { NONE, PC, GOOGLE }

    var source = Source.NONE
        private set
    private var google = ""
    // -1 — ещё не было (0 — законный миг на часах тестов)
    private var googleFirstAt = -1L
    private var pc = ""
    private var pcAt = -1L
    private var shownAt = -1L
    /** Сколько мс в пилюле была строка компа и Google (с первого показанного слова). */
    var pcShownMs = 0L
        private set
    var googleShownMs = 0L
        private set
    /** Сколько раз строка уходила с компа на Google посреди тейка. */
    var fallbacks = 0
        private set

    /** Новая строка Google; ответ — что показать (null — не трогать пилюлю). */
    fun onGoogle(text: String, now: Long): String? {
        google = text
        if (text.isNotBlank() && googleFirstAt < 0L) googleFirstAt = now
        if (!pcMode) return show(Source.GOOGLE, text, now)
        if (pcFresh(now)) return null
        if (text.isBlank()) return null
        // Комп ещё ни разу не ответил: ждём его немного после первого слова Google.
        if (pcAt < 0L && now - googleFirstAt < FIRST_WAIT_MS) return null
        return show(Source.GOOGLE, text, now)
    }

    /** Новая строка компа; ответ — что показать. */
    fun onPc(text: String, now: Long): String? {
        if (!pcMode) return null
        pc = text
        pcAt = now
        if (text.isBlank()) return null
        return show(Source.PC, text, now)
    }

    /** Время, которое строка провела у своего хозяина, — до [now] (конец тейка). */
    fun close(now: Long) {
        account(now)
        shownAt = now
    }

    /** Строка компа застряла в этом тейке: ни разу не показалась, хотя Google говорил, или ушла на Google больше чем на 30 %. */
    fun stuck(): Boolean {
        if (!pcMode) return false
        val total = pcShownMs + googleShownMs
        if (pcAt < 0L) return googleFirstAt >= 0L && googleShownMs >= FIRST_WAIT_MS
        return total >= 3_000L && googleShownMs * 10 > total * 3
    }

    private fun pcFresh(now: Long) = pcAt >= 0L && pc.isNotBlank() && now - pcAt <= STALE_MS

    private fun show(to: Source, text: String, now: Long): String {
        if (to != source) {
            account(now)
            if (source == Source.PC && to == Source.GOOGLE) fallbacks++
            source = to
            shownAt = now
        }
        return text
    }

    private fun account(now: Long) {
        if (shownAt < 0L) return
        val d = (now - shownAt).coerceAtLeast(0L)
        when (source) {
            Source.PC -> pcShownMs += d
            Source.GOOGLE -> googleShownMs += d
            Source.NONE -> Unit
        }
        shownAt = now
    }

    companion object {
        /** Строка компа старше — считаем, что комп отстал: показываем Google. */
        const val STALE_MS = 2_000L
        /** Сколько ждать первых слов компа после первого слова Google. */
        const val FIRST_WAIT_MS = 2_000L
    }
}
