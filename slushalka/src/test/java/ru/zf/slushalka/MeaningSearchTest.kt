package ru.zf.slushalka

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.slushalka.ask.BookTalk
import ru.zf.slushalka.ask.Guide
import ru.zf.slushalka.ask.MeaningSearch
import ru.zf.slushalka.text.BookText
import ru.zf.slushalka.text.Chapter

/**
 * Поиск по смыслу и справочник со связями: модель отвечает словами, а место
 * в книге ищем сами - и ошибка тут не видна, пока не прыгнешь не туда.
 */
class MeaningSearchTest {

    private val plain = "Глава первая\nАнна вела внучку на балет. Лора молчала.\n" +
        "Глава вторая\nЛора сказала: «Мне жаль папу — он всё время думает о Лизл».\nКонец."

    private val text = BookText(
        plain,
        listOf(
            Chapter("первая", 0, plain.indexOf("Глава вторая")),
            Chapter("вторая", plain.indexOf("Глава вторая"), plain.length),
        ),
    )

    @Test
    fun `цитата находится несмотря на кавычки, тире, регистр и ё`() {
        val at = MeaningSearch.findQuote(plain, 0, plain.length, "мне жаль папу - он всё время ДУМАЕТ")
        assertEquals(plain.indexOf("Мне жаль"), at)
    }

    @Test
    fun `цитата не ищется за местом чтения`() {
        val cutoff = plain.indexOf("Глава вторая")
        assertNull(MeaningSearch.findQuote(plain, 0, cutoff, "Мне жаль папу"))
    }

    @Test
    fun `приметы - два слова рядом дают место, одно - нет`() {
        val at = MeaningSearch.locate(text, 2, "папу Лизл")
        assertNotNull(at)
        assertTrue(at!! >= text.chapters[1].start)
        assertNull(MeaningSearch.locate(text, 2, "Лизл"))
    }

    @Test
    fun `ответ модели с пояснением вокруг JSON разбирается`() {
        val hits = MeaningSearch.parseHits("Вот: {\"hits\": [{\"chapter\": 2, \"why\": \"про папу\", \"words\": \"папу Лизл\"}]} Всё.")
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].chapter)
        val topics = BookTalk.parseTopics("{\"topics\": [{\"title\": \"Лора\", \"opener\": \"Почему она жалеет отца?\"}]}")
        assertEquals("Лора", topics.single().title)
    }

    @Test
    fun `связь героя видна только с главы, где открывается`() {
        val g = Guide.fromJson(
            JSONObject(
                """{"characters": [{"name": "Лора", "chapter": 1, "role": "внучка",
                "links": [{"to": "Анна", "kind": "внучка", "chapter": 1},
                          {"to": "Лизл", "kind": "сестра", "chapter": 2}]}],
                "events": [{"chapter": 2, "when": "весна", "text": "разговор о папе"}]}"""
            )
        )
        val lora = g.characters.single()
        assertEquals(listOf("Анна"), lora.visibleAt(1)!!.links.map { it.to })
        assertEquals(2, lora.visibleAt(2)!!.links.size)
        assertEquals(1, g.events.size)
        // Круг через JSON: связи и хронология не теряются при записи в файл.
        val again = Guide.fromJson(g.toJson())
        assertEquals(2, again.characters.single().links.size)
        assertEquals("весна", again.events.single().whenText)
    }
}
