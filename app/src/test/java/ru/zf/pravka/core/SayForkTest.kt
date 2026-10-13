package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.prompts.PromptsZasechka

// Развилка строки «сказать» на «Сегодня» (Правка 5.0, этап 2): семь дорог,
// запас словами, лента получает фразу как сказана.
class SayForkTest {

    @Test
    fun `ответ Сонета — семь дорог, у ленты фраза как сказана`() {
        val e = SayFork.fromModel("""{"kind":"entry","text":"","why":"новое занятие"}""", "обедаю последние полчаса")!!
        assertEquals(SayFork.Kind.ENTRY, e.kind)
        assertEquals("обедаю последние полчаса", e.text)
        val m = SayFork.fromModel("""Вот: {"kind": "money", "text": "такси 640", "why": "трата с суммой"}""", "заплатил за такси 640")!!
        assertEquals(SayFork.Kind.MONEY, m.kind)
        assertEquals("такси 640", m.text)
        assertEquals("трата с суммой", m.why)
        assertEquals(SayFork.Kind.BODY, SayFork.fromModel("""{"kind":"body","text":"подтянулся три раза"}""", "x")!!.kind)
        assertEquals(SayFork.Kind.ASK, SayFork.fromModel("""{"kind":"ask","text":"что у меня сегодня?"}""", "x")!!.kind)
        // Пустой текст у не-ленты — сказанное целиком.
        assertEquals("съел борщ", SayFork.fromModel("""{"kind":"food","text":""}""", "съел борщ")!!.text)
    }

    @Test
    fun `непрочитанный ответ — null, решают слова`() {
        assertNull(SayFork.fromModel("не знаю", "x"))
        assertNull(SayFork.fromModel("""{"kind":"weather"}""", "x"))
    }

    @Test
    fun `слова — вопрос по знаку, деньги по сумме, остальное как у развилки З`() {
        assertEquals(SayFork.Kind.ASK, SayFork.words("что у меня сегодня по делам?").kind)
        assertEquals(SayFork.Kind.MONEY, SayFork.words("такси 640 руб").kind)
        assertEquals(SayFork.Kind.MONEY, SayFork.words("перевёл Наташе 30 тысяч").kind)
        assertEquals(SayFork.Kind.MONEY, SayFork.words("продукты 3200 ₽").kind)
        assertEquals(SayFork.Kind.ENTRY, SayFork.words("еду к Илье").kind)
        assertEquals(SayFork.Kind.TASKS, SayFork.words("запиши дело купить молоко").kind)
    }

    @Test
    fun `промпт — семь дорог, время и фраза хвостом`() {
        val p = PromptsZasechka.SAY_FORK
        for (k in SayFork.Kind.entries) assertTrue(k.key, p.contains("\"${k.key}\""))
        // Всё выше {NOW} одинаково между фразами — под кэшем.
        assertTrue(p.indexOf("{NOW}") < p.indexOf("{INPUT}"))
        assertTrue(p.indexOf("{NOW}") > p.length / 2)
    }
}
