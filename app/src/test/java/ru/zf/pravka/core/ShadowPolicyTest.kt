package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Тень второй модели: окно первой и следующих ночей, слепые стороны, разбор
// ответа судьи и подсчёт — всё, что не ходит в сеть.
class ShadowPolicyTest {

    private val day = 86_400_000L

    @Test
    fun `большой кусок — только когда просят, иначе сутки от конца прошлого прогона`() {
        val now = 100 * day
        val first = ShadowPolicy.window(now, null, big = true)
        assertTrue(first.first)
        assertEquals(now - 30 * day, first.fromMs)
        assertEquals(ShadowPolicy.FIRST_CAP, first.cap)

        // Удачных прогонов ещё нет, но большой не просили (первый застрял) — сутки.
        val plain = ShadowPolicy.window(now, null, big = false)
        assertFalse(plain.first)
        assertEquals(now - day, plain.fromMs)
        assertEquals(ShadowPolicy.DAILY_CAP, plain.cap)

        val next = ShadowPolicy.window(now, now - day, big = false)
        assertEquals(now - day, next.fromMs)

        // Телефон лежал выключенным две недели — глубже недели не лезем.
        val late = ShadowPolicy.window(now, now - 14 * day, big = false)
        assertEquals(now - 7 * day, late.fromMs)
    }

    @Test
    fun `стороны раздаются детерминированно и не все одинаково`() {
        val ids = (1..40).map { "s-$it" }
        val flips = ids.map { ShadowPolicy.flip(it) }
        assertEquals(flips, ids.map { ShadowPolicy.flip(it) })
        assertTrue(flips.any { it } && flips.any { !it })
    }

    @Test
    fun `совпадение слово в слово не смотрит на пробелы и регистр`() {
        assertTrue(ShadowPolicy.same("Привет,  мир.", "привет, мир."))
        assertFalse(ShadowPolicy.same("Привет, мир.", "Привет мир."))
    }

    private fun take(id: String, flip: Boolean) = ShadowPolicy.Take(
        id = id, tsMs = 1L, input = "надиктовано", prepared = "надиктовано", day = "дневная",
        dayCostUsd = 0.01, flip = flip, shadow = "вторая",
    )

    @Test
    fun `буква судьи переводится в модель с учётом стороны`() {
        assertEquals("Опус 5", ShadowPolicy.resolve("A", flip = true, dayLabel = "Сонет 5", shadowLabel = "Опус 5"))
        assertEquals("Сонет 5", ShadowPolicy.resolve("A", flip = false, dayLabel = "Сонет 5", shadowLabel = "Опус 5"))
        assertEquals("Опус 5", ShadowPolicy.resolve("b", flip = false, dayLabel = "Сонет 5", shadowLabel = "Опус 5"))
        assertEquals("same", ShadowPolicy.resolve("same", flip = true, dayLabel = "С", shadowLabel = "О"))
        assertEquals("both_bad", ShadowPolicy.resolve("both_bad", flip = true, dayLabel = "С", shadowLabel = "О"))
        assertEquals("unjudged", ShadowPolicy.resolve("C", flip = true, dayLabel = "С", shadowLabel = "О"))
        assertEquals("tie", ShadowPolicy.judgedVerdict("same"))
    }

    @Test
    fun `пара для судьи ставит вторую модель на сторону A при flip`() {
        val flipped = ShadowPolicy.renderTake(take("s-1", flip = true))
        assertTrue(flipped.contains("<a>вторая</a>") && flipped.contains("<b>дневная</b>"))
        val plain = ShadowPolicy.renderTake(take("s-2", flip = false))
        assertTrue(plain.contains("<a>дневная</a>") && plain.contains("<b>вторая</b>"))
        assertTrue(plain.contains("id=\"s-2\""))
    }

    @Test
    fun `ответ судьи разбирается вместе с изъянами`() {
        val j = ShadowPolicy.parseJudge(
            """Вот: {"summary": "Часто теряются запятые.", "verdicts": [
               {"id": "s-1", "better": "A", "why": "не потеряла «не»", "loser_flaws": ["negation", "Punctuation"]},
               {"id": "s-2", "better": "same", "why": "одно и то же"}]}"""
        )
        assertEquals("Часто теряются запятые.", j.summary)
        assertEquals(listOf("negation", "punctuation"), j.verdicts["s-1"]!!.flaws)
        assertEquals("same", j.verdicts["s-2"]!!.better)
    }

    @Test
    fun `подсчёт считает победы по моделям и изъяны проигравшего`() {
        val takes = listOf(
            take("s-1", true).copy(verdict = "Опус 5", flaws = listOf("punctuation", "grammar")),
            take("s-2", false).copy(verdict = "Опус 5", flaws = listOf("punctuation")),
            take("s-3", false).copy(verdict = "Сонет 5", flaws = listOf("extra")),
            take("s-4", false).copy(verdict = "same"),
            take("s-5", false).copy(verdict = "tie"),
            take("s-6", false).copy(verdict = "both_bad"),
            take("s-7", false).copy(failure = "нет ответа"),
        )
        val t = ShadowPolicy.tally(takes, "Сонет 5", "Опус 5")
        assertEquals(7, t.total)
        assertEquals(2, t.shadowBetter)
        assertEquals(1, t.dayBetter)
        assertEquals(1, t.same)
        assertEquals(1, t.tie)
        assertEquals(1, t.bothBad)
        assertEquals(1, t.failed)
        assertEquals(5, t.judged)
        // Изъяны Сонета — там, где выиграл Опус: пунктуация дважды, падежи один раз.
        assertEquals(listOf("punctuation" to 2, "grammar" to 1), t.dayFlaws.entries.map { it.key to it.value })
        assertEquals(mapOf("extra" to 1), t.shadowFlaws)
        val s = ShadowPolicy.summary(t, 0L, day, "Сонет 5", "Опус 5", "", "Fable 5.1", 0.10, 0.25, listOf("Заметка."), first = true)
        assertTrue(s, s.contains("лучше Опус 5 — 2, лучше Сонет 5 — 1"))
        assertTrue(s, s.contains("пунктуация и членение 2"))
        assertTrue(s, s.contains("в 2.5 раза дороже"))
        assertTrue(s, s.contains("Судья: Заметка."))
    }

    @Test
    fun `примеры чередуют победы сторон и не берут совпавшие`() {
        val takes = (1..10).map { take("s-$it", false).copy(verdict = if (it % 2 == 0) "Опус 5" else "Сонет 5") } +
            take("s-11", false).copy(verdict = "same") + take("s-12", false).copy(verdict = "both_bad")
        val ex = ShadowPolicy.examples(takes, "Сонет 5", "Опус 5", n = 5)
        assertEquals(listOf("Опус 5", "Сонет 5", "Опус 5", "Сонет 5", "Опус 5"), ex.map { it.verdict })
        val all = ShadowPolicy.examples(takes, "Сонет 5", "Опус 5", n = 20)
        assertEquals(11, all.size)
        assertTrue(all.none { it.verdict == "same" })
    }

    @Test
    fun `нарезка судье держит предел и не рвёт пару`() {
        val items = listOf("a" to 50, "b" to 40, "c" to 30, "d" to 100)
        val chunks = ShadowPolicy.chunks(items, 90) { it.second }
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d")), chunks.map { c -> c.map { it.first } })
    }

    @Test
    fun `диктовки прогона переживают JSON`() {
        val takes = listOf(take("s-1", true).copy(verdict = "Опус 5", why = "лучше", flaws = listOf("lost"), prose = true), take("s-2", false).copy(failure = "нет ответа"))
        val back = ShadowPolicy.takesFromJson(ShadowPolicy.takesToJson(takes))
        assertEquals(takes, back)
    }
}
