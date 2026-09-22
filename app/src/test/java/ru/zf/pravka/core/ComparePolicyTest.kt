package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ручное сравнение трёх моделей: слепые буквы, разбор судьи, подсчёт, сводка,
// примеры и хранение — всё, что не ходит в сеть.
class ComparePolicyTest {

    private fun item(id: String, sonnet: String, opus: String, low: String, best: String = "", worst: String = "", flaws: List<String> = emptyList()) =
        ComparePolicy.Item(
            id, 1_000L, "надиктовано $id",
            results = mapOf(
                "sonnet" to ComparePolicy.Result(sonnet, 0.01, 1_000),
                "opus" to ComparePolicy.Result(opus, 0.03, 3_000),
                "opus_low" to ComparePolicy.Result(low, 0.02, 2_000),
            ),
            best = best, worst = worst, why = "потому", flaws = flaws,
        )

    @Test
    fun `три плеча фиксированы — Сонет, Опус, Опус low`() {
        assertEquals(listOf("sonnet", "opus", "opus_low"), ComparePolicy.ARMS.map { it.key })
        assertEquals("low", ComparePolicy.arm("opus_low")!!.effort)
        assertEquals("", ComparePolicy.arm("opus")!!.effort)
        assertEquals(ComparePolicy.arm("opus")!!.model, ComparePolicy.arm("opus_low")!!.model)
    }

    @Test
    fun `буквы раздаются детерминированно, все шесть порядков в ходу, resolve обращает render`() {
        val ids = (1..60).map { "c-$it" }
        assertEquals(ids.map { ComparePolicy.perm(it) }, ids.map { ComparePolicy.perm(it) })
        assertEquals(6, ids.map { ComparePolicy.perm(it) }.distinct().size)
        for (id in ids.take(12)) {
            val it = item(id, "S", "O", "L")
            val rendered = ComparePolicy.render(it)
            assertTrue(rendered.contains("<d>надиктовано $id</d>"))
            // Под буквой стоит текст того плеча, которое resolve этой буквы и возвращает.
            for (letter in listOf("A", "B", "C")) {
                val key = ComparePolicy.resolve(letter, id)
                val text = it.results[key]!!.text
                assertTrue(rendered.contains("<${letter.lowercase()}>$text</${letter.lowercase()}>"))
            }
            assertEquals(setOf("sonnet", "opus", "opus_low"), listOf("A", "B", "C").map { ComparePolicy.resolve(it, id) }.toSet())
        }
        assertEquals("same", ComparePolicy.resolve(" same ", "c-1"))
        assertEquals("", ComparePolicy.resolve("D", "c-1"))
    }

    @Test
    fun `состояние диктовки — ждёт плеч, готова, совпала, судима`() {
        val empty = ComparePolicy.Item("c-1", 0L, "x")
        assertEquals(3, empty.pendingArms.size)
        assertFalse(empty.complete)
        val half = empty.copy(results = mapOf("sonnet" to ComparePolicy.Result("a")))
        assertEquals(listOf("opus", "opus_low"), half.pendingArms.map { it.key })
        val same = item("c-2", "Привет, мир.", "привет,  мир.", "Привет, мир. ")
        assertTrue(same.complete)
        assertTrue(same.allSame)
        assertFalse(same.judgeable)
        val diff = item("c-3", "Привет, мир.", "Привет мир.", "Привет, мир.")
        assertTrue(diff.judgeable)
        val failed = half.copy(results = half.results + ("opus" to ComparePolicy.Result(failure = "HTTP 400")))
        assertTrue(failed.failed)
        assertEquals(listOf("opus_low"), failed.pendingArms.map { it.key })
    }

    @Test
    fun `ответ судьи разбирается — изъяны в нижнем регистре, пустые id пропущены, обёртка терпится`() {
        val raw = """
            Вот ответ:
            ```json
            {"summary": "Чаще всего страдала пунктуация.",
             "verdicts": [
               {"id": "c-1", "best": "B", "worst": "A", "why": "A потерял «не»", "worst_flaws": ["Negation", " lost "]},
               {"id": "", "best": "A"},
               {"id": "c-2", "best": "same", "worst": "same", "why": "только запятые", "worst_flaws": []}
             ]}
            ```
        """.trimIndent()
        val j = ComparePolicy.parseJudge(raw)
        assertEquals("Чаще всего страдала пунктуация.", j.summary)
        assertEquals(setOf("c-1", "c-2"), j.verdicts.keys)
        assertEquals(listOf("negation", "lost"), j.verdicts["c-1"]!!.flaws)
        assertEquals("same", j.verdicts["c-2"]!!.best)
    }

    @Test
    fun `подсчёт — совпавшие, равноценные, без вердикта, сбои, счёт лучших и худших, деньги и скорость`() {
        val items = listOf(
            item("c-1", "одно", "одно", "одно"),                                  // совпали
            item("c-2", "а", "б", "в", best = "opus", worst = "sonnet", flaws = listOf("negation", "lost")),
            item("c-3", "а", "б", "в", best = "opus", worst = "opus_low", flaws = listOf("punctuation")),
            item("c-4", "а", "б", "в", best = "sonnet", worst = "opus_low", flaws = listOf("punctuation")),
            item("c-5", "а", "б", "в", best = "same", worst = "same"),           // равноценно
            item("c-6", "а", "б", "в"),                                          // судья не ответил
            item("c-7", "а", "б", "в").let { it.copy(results = it.results + ("opus" to ComparePolicy.Result(failure = "400"))) },
        )
        val t = ComparePolicy.tally(items)
        assertEquals(7, t.total)
        assertEquals(1, t.same)
        assertEquals(1, t.tie)
        assertEquals(1, t.unjudged)
        assertEquals(1, t.failed)
        assertEquals(2, t.best["opus"])
        assertEquals(1, t.best["sonnet"])
        assertEquals(0, t.best["opus_low"])
        assertEquals(2, t.worst["opus_low"])
        assertEquals(1, t.worst["sonnet"])
        assertEquals(2, t.flaws["opus_low"]!!["punctuation"])
        assertEquals(1, t.flaws["sonnet"]!!["negation"])
        // Деньги: семь диктовок по плечу, у сбойного Опуса цена нуль; скорость без сбоев.
        assertEquals(0.07, t.cost["sonnet"]!!, 1e-9)
        assertEquals(0.18, t.cost["opus"]!!, 1e-9)
        assertEquals(1_000L, t.avgMs["sonnet"])
        assertEquals(3_000L, t.avgMs["opus"])
    }

    @Test
    fun `сводка называет плечи, счёт, скорость, деньги и изъяны худших`() {
        val items = listOf(
            item("c-1", "одно", "одно", "одно"),
            item("c-2", "а", "б", "в", best = "opus", worst = "sonnet", flaws = listOf("negation")),
            item("c-3", "а", "б", "в", best = "opus", worst = "opus_low", flaws = listOf("punctuation")),
        )
        val s = ComparePolicy.summary(ComparePolicy.tally(items), 0L, 86_400_000L, "Fable 5.1", 0.12, listOf("Пунктуация хромала."))
        assertTrue(s, s.contains("3 диктовок, судья Fable 5.1"))
        assertTrue(s, s.contains("Все три совпали слово в слово: 1."))
        assertTrue(s, s.contains("Лучше всех: Опус 5.5 — 2, Сонет 5 — 0, Опус 5.5 low — 0."))
        assertTrue(s, s.contains("Хуже всех: Сонет 5 — 1, Опус 5.5 low — 1, Опус 5.5 — 0."))
        assertTrue(s, s.contains("Сонет 5 — 1.0 с, Опус 5.5 — 3.0 с, Опус 5.5 low — 2.0 с"))
        assertTrue(s, s.contains("Сонет 5 — $0.03, Опус 5.5 — $0.09, Опус 5.5 low — $0.06; судья — $0.12"))
        assertTrue(s, s.contains("Изъяны Сонет 5, где он худший: ${ShadowPolicy.flawLabel("negation")} 1."))
        assertTrue(s, s.contains("Судья: Пунктуация хромала."))
    }

    @Test
    fun `примеры — по кругу плеч-победителей, совпавшие и равноценные не в счёт`() {
        val items = listOf(
            item("c-1", "одно", "одно", "одно"),
            item("c-2", "а", "б", "в", best = "opus", worst = "sonnet"),
            item("c-3", "а", "б", "в", best = "opus", worst = "sonnet"),
            item("c-4", "а", "б", "в", best = "sonnet", worst = "opus"),
            item("c-5", "а", "б", "в", best = "same", worst = "same"),
            item("c-6", "а", "б", "в", best = "opus_low", worst = "opus"),
        )
        // Круг идёт в порядке плеч: Сонет, Опус, Опус low — потом второй победитель Опуса.
        assertEquals(listOf("c-4", "c-2", "c-6", "c-3"), ComparePolicy.examples(items).map { it.id })
        assertEquals(listOf("c-4", "c-2"), ComparePolicy.examples(items, 2).map { it.id })
    }

    @Test
    fun `хранение — туда и обратно без потерь, тексты плеч примера в порядке плеч`() {
        val items = listOf(
            ComparePolicy.Item("c-1", 5L, "вход", prose = true),
            item("c-2", "а", "б", "в", best = "opus", worst = "sonnet", flaws = listOf("lost")),
        )
        assertEquals(items, ComparePolicy.fromJson(ComparePolicy.toJson(items)))
        assertEquals(emptyList<ComparePolicy.Item>(), ComparePolicy.fromJson("мусор"))
        val texts = ComparePolicy.armTexts("""{"Опус 5.5 low":"в","Сонет 5":"а","Опус 5.5":""}""")
        assertEquals(listOf("Сонет 5" to "а", "Опус 5.5 low" to "в"), texts)
        assertEquals(emptyList<Pair<String, String>>(), ComparePolicy.armTexts("не json"))
    }
}
