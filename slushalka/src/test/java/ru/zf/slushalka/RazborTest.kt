package ru.zf.slushalka

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.slushalka.ask.GuideState
import ru.zf.slushalka.ask.Lens
import ru.zf.slushalka.ask.Razbor
import ru.zf.slushalka.ask.RazborEngine
import ru.zf.slushalka.ask.RazborRef
import ru.zf.slushalka.text.BookText
import ru.zf.slushalka.text.Chapter

/**
 * Разбор книги с сервера: файл кладёт чужая программа, и ошибка в разборе
 * формата видна только живьём - пустым листом или ссылкой не туда.
 */
class RazborTest {

    // Формат - из задачи сервера (04.10.2026), с линзами истории и детской разом.
    private val raw = """
        {
          "версия": 2, "книга": "Джордж", "text": "george.fb2", "chars": 270389, "главы": 33,
          "created": 1791100000000, "model": "claude-opus-5-5", "usd": 1.58,
          "card": {"genre": "научная сказка", "lens": "детская", "age": "8+", "series": "Джордж", "era": "наши дни",
                   "about": "Мальчик и компьютер, который открывает дверь в космос."},
          "dictionary": [{"word": "Гартман", "spoken": "Гартм+ан", "kind": "имя", "note": ""}],
          "chapters": [
            {"c": 1, "title": "Свинья", "summary": "Фредди сбегает.", "before": "", "pos": 0},
            {"c": 2, "title": "Соседи", "summary": "Анни и Эрик.", "before": "Джордж потерял поросёнка.", "pos": 37000}
          ],
          "events": [{"c": 2, "pos": 37609, "q": "…", "when": "весна", "text": "что случилось"}],
          "summary15": {"text": "Первый абзац.\nВторой абзац.", "audio": "_Озвучка/Джордж/разбор/Джордж — за 15 минут.mp3"},
          "ideas": [{"title": "Наука - это дверь", "idea": "…", "why": "…", "question": "…",
                     "links": [{"c": 3, "pos": 21000, "q": "цитата"}]}],
          "lenses": {
            "finance": [{"c": 10, "pos": 1, "q": "…", "topic": "Пошлины", "text": "что произошло", "lesson": "урок"}],
            "myths": [{"c": 2, "pos": 1, "common": "принято", "author": "у автора"}],
            "world": [{"when": "1618", "event": "тем временем"}],
            "rulers": [{"name": "Михаил", "years": "1613–1645", "relation": "первый"}],
            "science": [{"c": 7, "pos": 1, "q": "…", "claim": "Чёрная дыра", "reality": "…", "verdict": "частично"}],
            "print": [{"object": "Модель ракеты", "why": "…"}],
            "kids": {"questions": [{"c": 1, "pos": 0, "question": "Зачем?"}],
                     "words": [{"c": 2, "pos": 0, "word": "кордебалет", "explanation": "…"}],
                     "scary": [{"c": 9, "pos": 0, "what": "падение"}]},
            "astrology": [{"c": 1, "text": "незнакомая линза"}]
          }
        }
    """.trimIndent()

    @Test
    fun `разбор сервера читается целиком`() {
        val r = Razbor.parse(raw)
        assertNotNull(r)
        r!!
        assertEquals(2, r.version)
        assertEquals(1.58, r.usd, 0.001)
        assertEquals("детская", r.card.lens)
        assertEquals(2, r.chapters.size)
        assertEquals("Джордж потерял поросёнка.", r.chapter(2)!!.before)
        assertEquals("_Озвучка/Джордж/разбор/Джордж — за 15 минут.mp3", r.audio)
        assertEquals(RazborRef(3, 21000, "цитата"), r.ideas.single().links.single())
        assertEquals(270389, r.fit.chars)
        assertEquals(33, r.fit.chapters)
    }

    @Test
    fun `линзы - в своём порядке, незнакомые пропускаются`() {
        val r = Razbor.parse(raw)!!
        assertEquals(
            listOf("finance", "myths", "world", "rulers", "science", "print", "kids.questions", "kids.words", Lens.SCARY),
            r.lenses.map { it.key },
        )
        val rulers = r.lenses.first { it.key == "rulers" }.items.single()
        assertEquals("Михаил (1613–1645)", rulers.title)
        assertNull("правители ни к какому месту не привязаны", rulers.ref)
        val science = r.lenses.first { it.key == "science" }.items.single()
        assertEquals("частично", science.verdict)
        assertEquals(7, science.ref!!.chapter)
        assertTrue(r.lenses.first { it.key == Lens.SCARY }.warnsAhead)
    }

    @Test
    fun `без линз и идей разбор не ломается, пустой - не разбор`() {
        val r = Razbor.parse("""{"версия": 2, "card": {"about": "о книге"}, "chars": 10, "главы": 1}""")
        assertNotNull(r)
        assertTrue(r!!.lenses.isEmpty())
        assertTrue(r.ideas.isEmpty())
        assertEquals("", r.audio)
        assertNull(Razbor.parse("""{"версия": 2}"""))
        assertNull(Razbor.parse("не json"))
    }

    @Test
    fun `ссылка мимо своей главы ведёт к началу главы`() {
        val plain = "Глава первая\nНачало.\nГлава вторая\nЛора сказала важное.\n"
        val second = plain.indexOf("Глава вторая")
        val text = BookText(plain, listOf(Chapter("первая", 0, second), Chapter("вторая", second, plain.length)))
        val inside = plain.indexOf("Лора")
        assertEquals(inside, RazborRef(2, inside, "Лора сказала").at(text))
        assertEquals(second, RazborRef(2, 3, "").at(text))
        // Главы такой нет - знак как есть, но в пределах текста.
        assertEquals(plain.length - 1, RazborRef(9, 100_000, "").at(text))
    }

    @Test
    fun `прикидка цены - формулой сервера`() {
        // Сервер называет ~1,9 $ для «Джорджа» (270 389 знаков, 33 главы).
        assertEquals(1.91, RazborEngine.estimateUsd(270_389, 33), 0.02)
    }

    @Test
    fun `заказ - свежий ждём, старый и с ошибкой нет`() {
        val now = System.currentTimeMillis()
        assertTrue(RazborEngine.Order(RazborEngine.WORKING, "Саша", "Fold", now - 3600_000L).waiting)
        assertFalse(RazborEngine.Order(RazborEngine.QUEUED, "Саша", "Fold", now - 7 * 3600_000L).waiting)
        val failed = RazborEngine.Order.parse("""{"status": "ошибка", "by": "Саша", "at": $now, "error": "нет текста"}""")!!
        assertTrue(failed.failed)
        assertFalse(failed.waiting)
        assertEquals("нет текста", failed.error)
        assertEquals("Марианна (Boox)", RazborEngine.Order("готово", "Марианна", "Boox", now).who)
    }

    @Test
    fun `справочник разбора сервера узнаётся по подписи`() {
        val o = JSONObject().put("status", "READY").put("by", "сервер: разбор книги").put("batch", "").put("разбор", 2)
        assertTrue(GuideState.fromJson(o).fromServer)
        assertFalse(GuideState.fromJson(o.put("by", "Саша")).fromServer)
    }
}
