package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Правка одного слова руками должна уезжать в словарь без модели; всё, что
// сложнее одной замены, — нет: иначе словарь наполнится мусором из переписок.
class EditDiffTest {

    @Test
    fun `одно слово заменено — замена найдена и похожа`() {
        val s = EditDiff.singleSubstitution(
            "Созвонился со стаф джетом по отчёту, договорились до пятницы.",
            "Созвонился со Стаффджетом по отчёту, договорились до пятницы.",
        )
        // «стаф джетом» — два слова у нас, одно у владельца.
        assertEquals("стаф джетом", s!!.from)
        assertEquals("Стаффджетом", s.to)
        assertTrue(s.similar)
    }

    @Test
    fun `слово заменено на непохожее — правка смысла, не словарь`() {
        val s = EditDiff.singleSubstitution(
            "Надо сделать отчёт к вечеру и отправить.",
            "Надо выполнить отчёт к вечеру и отправить.",
        )
        assertEquals("сделать", s!!.from)
        assertEquals("выполнить", s.to)
        assertFalse(s.similar)
        // До 12.10.2026 такое уходило HINT «владелец предпочитает это слово».
        assertTrue(
            EditDiff.learn("Надо сделать отчёт к вечеру и отправить.", "Надо выполнить отчёт к вечеру и отправить.", emptyList(), emptyList())
                is EditDiff.Learn.Queue
        )
    }

    @Test
    fun `текст вокруг нашего — контекст, не правка`() {
        val s = EditDiff.singleSubstitution(
            "Встреча с Ивановым перенесена на четверг.",
            "Привет! Встреча с Ивановым перенесена на пятницу. Напиши, если не подходит.",
        )
        assertEquals("четверг", s!!.from)
        assertEquals("пятницу", s.to)
    }

    @Test
    fun `чужая голова с точкой отрезается от замены первого слова`() {
        val s = EditDiff.singleSubstitution(
            "Четверг — встреча с Ивановым и Петровым.",
            "Привет! Пятница — встреча с Ивановым и Петровым.",
        )
        assertEquals("Четверг", s!!.from)
        assertEquals("Пятница", s.to)
    }

    @Test
    fun `слово внутри двухсловной правки — одна замена`() {
        val s = EditDiff.singleSubstitution(
            "Созвон с Петей завтра в десять утра.",
            "Созвон с Пётром завтра в десять утра.",
        )
        assertEquals("Петей", s!!.from)
        assertEquals("Пётром", s.to)
    }

    @Test
    fun `две замены — не словарный случай`() {
        assertNull(
            EditDiff.singleSubstitution(
                "Созвон с Петей завтра в десять утра.",
                "Созвон с Пётром послезавтра в десять утра.",
            )
        )
    }

    @Test
    fun `только пунктуация и регистр — правки нет`() {
        assertNull(
            EditDiff.singleSubstitution(
                "созвон с петей завтра в десять",
                "Созвон с Петей завтра, в десять.",
            )
        )
    }

    @Test
    fun `вставленное слово без удаления — не замена`() {
        assertNull(
            EditDiff.singleSubstitution(
                "Созвон с Петей завтра в десять утра.",
                "Созвон с Петей завтра в десять утра точно.",
            )
        )
    }

    @Test
    fun `совсем другой текст — ничего`() {
        assertNull(EditDiff.singleSubstitution("Один текст про работу.", "Совсем иная фраза про бег."))
    }

    @Test
    fun `та же основа, другое окончание — форма слова, не ослышка`() {
        assertTrue(EditDiff.sameStem("Папа", "Пап"))
        assertTrue(EditDiff.sameStem("Рубрика", "Рубрик"))
        assertTrue(EditDiff.sameStem("следующее", "следующая"))
        assertTrue(EditDiff.sameStem("проговорился", "проговорил"))
        // Разошлись в середине или с начала — настоящая ослышка, HARD уместен.
        assertFalse(EditDiff.sameStem("Стафджет", "Стаффджет"))
        assertFalse(EditDiff.sameStem("lifans", "onlyfans"))
        // Склейка двух слов в одно — не форма.
        assertFalse(EditDiff.sameStem("стаф джетом", "Стаффджетом"))
        val s = EditDiff.singleSubstitution(
            "Папа приедет завтра к обеду.",
            "Пап приедет завтра к обеду.",
        )
        assertTrue(s!!.similar)
        assertTrue(s.inflection)
    }

    // ---- Куда правке дорога без модели (EditDiff.learn, 12.10.2026) ----
    //
    // Случай: за месяц захвата правок словарь получил подсказки «и» → «убирала»,
    // «э» → «мне», «его» → «надо», «вечер» → «день» — владелец правил смысл
    // сообщения, а диф видел одну замену. Короткие стреляли в каждой диктовке.

    private val base = "Потому что новая бригада, и много до этого старая, убирала двор плохо."
    private val edit = "Потому что новая бригада убирала много, до этого старая убирала двор плохо."

    @Test
    fun `«и» → «убирала» — диф видит одну замену, но в словарь она не попадает`() {
        // Причина воспроизводится: правка смысла выглядит ровно одной заменой.
        val s = EditDiff.singleSubstitution(base, edit)
        assertEquals("и", s!!.from)
        assertEquals("убирала", s.to)
        assertFalse(s.similar)
        // В словарь — ни сразу, ни при повторе той же пары: только в очередь Опусу.
        val first = EditDiff.learn(base, edit, emptyList(), emptyList())
        assertTrue(first is EditDiff.Learn.Queue)
        assertEquals("и", (first as EditDiff.Learn.Queue).sub!!.from)
        val again = EditDiff.learn(base, edit, emptyList(), listOf(EditDiff.pairKey(s), EditDiff.pairKey(s)))
        assertTrue(again is EditDiff.Learn.Queue)
    }

    @Test
    fun `обращение вместо первого слова — не словарь`() {
        // Голова «Коль, привет!» отрезается как чужая — остаётся «Коля → А».
        val r = EditDiff.learn(
            "Коля, ты же отправил вчерашнюю презентацию?",
            "Коль, привет! А ты же отправил вчерашнюю презентацию?",
            emptyList(), emptyList(),
        )
        assertTrue(r is EditDiff.Learn.Queue)
    }

    @Test
    fun `ослышка ложится в словарь со второй такой правки`() {
        val b = "Созвонился со Стафджетом по отчёту, договорились до пятницы."
        val e = "Созвонился со Стаффджетом по отчёту, договорились до пятницы."
        val first = EditDiff.learn(b, e, emptyList(), listOf("другое→слово"))
        assertTrue(first is EditDiff.Learn.Once)
        val key = EditDiff.pairKey((first as EditDiff.Learn.Once).sub)
        val second = EditDiff.learn(b, e, emptyList(), listOf(key))
        assertTrue(second is EditDiff.Learn.Dict)
        assertEquals("Стафджетом", (second as EditDiff.Learn.Dict).sub.from)
    }

    @Test
    fun `похожая правка смысла впервые — ждёт повтора, а не HARD`() {
        val r = EditDiff.learn(
            "Мы на совете весь бюджет определили.",
            "Мы на совете весь бюджет распределили.",
            emptyList(), emptyList(),
        )
        assertTrue(r is EditDiff.Learn.Once)
    }

    @Test
    fun `короткое слово не учится даже повторённым`() {
        val b = "Положи дом на верхнюю полку."
        val e = "Положи том на верхнюю полку."
        val s = EditDiff.singleSubstitution(b, e)!!
        assertTrue(s.similar)
        val r = EditDiff.learn(b, e, emptyList(), listOf(EditDiff.pairKey(s)))
        assertTrue(r is EditDiff.Learn.Queue)
    }

    @Test
    fun `разбор застал набор — полуслово не словарь`() {
        val r = EditDiff.learn(
            "Пришли мне счёт за прошлый месяц, пожалуйста.",
            "Пришли мне сч за прошлый месяц, пожалуйста.",
            emptyList(), listOf("счёт→сч"),
        )
        assertTrue(r is EditDiff.Learn.Queue)
        assertTrue(EditDiff.halfTyped("проигрывает", "проигрываетзвук"))
        assertFalse(EditDiff.halfTyped("Стафджет", "Стаффджет"))
    }

    @Test
    fun `слово уже в словаре, даже выключенное, — не трогаем`() {
        val known = listOf(DictEntry(id = 7, from = "Стафджетом", to = "Стаффджетом", mode = DictMode.HARD, enabled = false, createdAt = 0))
        val r = EditDiff.learn(
            "Созвонился со Стафджетом по отчёту.",
            "Созвонился со Стаффджетом по отчёту.",
            known, emptyList(),
        )
        assertTrue(r is EditDiff.Learn.Known)
    }

    @Test
    fun `короткие и служебные слова`() {
        assertTrue(EditDiff.shortOrService("и"))
        assertTrue(EditDiff.shortOrService("его"))
        assertTrue(EditDiff.shortOrService("Когда"))
        assertTrue(EditDiff.shortOrService("1100"))
        assertFalse(EditDiff.shortOrService("стаф джет"))
        assertFalse(EditDiff.shortOrService("Стаффджет"))
    }
}
