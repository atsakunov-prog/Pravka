package ru.zf.pravka.core

import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Плашка «Сервер ответил» (отладка 16.10.2026, записи 1–2): отказ от позавчера провисел над «Новым» 41 час без времени,
 * гас только по «Понятно», а то, что уже сказано тостом после тапа, висело ещё и в плашке. Данные выдуманы.
 */
class DelaNoticesTest {

    private val msk = ZoneId.of("Europe/Moscow")
    private val h = 60L * 60 * 1000
    // 16.10.2026 08:59 МСК — утро записи; отказ — 14.10 15:25 МСК, на 41,5 часа раньше.
    private val now = java.time.ZonedDateTime.of(2026, 10, 16, 8, 59, 0, 0, msk).toInstant().toEpochMilli()
    private val old = java.time.ZonedDateTime.of(2026, 10, 14, 15, 25, 37, 0, msk).toInstant().toEpochMilli()

    private fun n(at: Long, text: String, error: Boolean = true, op: String = "op-$at", shown: Boolean = false) =
        DelaNotices.Notice(at, text, error, op, shown)

    @Test
    fun oldAndToldAreNotShown() {
        val list = listOf(
            n(now - 2 * 60_000, "Не прошло: «Да» на «Позвонить Ивану» — сеть"),
            n(now - 5 * 60_000, "сказано тостом", shown = true),
            n(now - 23 * h, "срок меняли и с другой стороны", error = false),
            n(old, "Не прошло: «Да» на «Завести таблицу заказов» — предложение уже разобрано или не твоё"),
        )
        val shown = DelaNotices.visible(list, now)
        // Отказ двухдневной давности больше не висит (запись 1), сказанное тостом — тоже; моложе суток — да.
        assertEquals(listOf(list[0], list[2]), shown)
        // Ровно сутки — уже нет.
        assertTrue(DelaNotices.visible(listOf(n(now - 24 * h, "сутки")), now).isEmpty())
        // Не больше пяти, свежие первыми — как лежат.
        val many = (1..8).map { n(now - it * 60_000L, "№$it") }
        assertEquals((1..5).map { "№$it" }, DelaNotices.visible(many, now).map { it.text })
    }

    @Test
    fun stampHasDateAndTime() {
        // Без даты вчерашний отказ читается как ответ на сегодняшний тап.
        assertEquals("14.10 15:25", DelaNotices.stamp(old, msk))
        assertEquals("16.10 08:59", DelaNotices.stamp(now, msk))
    }

    @Test
    fun refusedSaysWhatAndWhy() {
        assertEquals(
            "Не прошло: «Да» на «Завести таблицу заказов» — Уже решено раньше: принято 14.10 в 15:25 — повторять не нужно",
            DelaNotices.refused("«Да» на «Завести таблицу заказов»", "Уже решено раньше: принято 14.10 в 15:25 — повторять не нужно"),
        )
        assertEquals("Не прошло: закрыть «Позвонить Ивану» — без причины", DelaNotices.refused("закрыть «Позвонить Ивану»", "  "))
        // По началу записка «Что не так?» понимает, что сервер отказал, и оставляет текст в поле.
        assertTrue(DelaNotices.refused("x", "y").startsWith(DelaNotices.REFUSED))
    }

    @Test
    fun markShownTouchesOnlyTold() {
        val a = n(now - 60_000, "отказ", op = "op-a")
        val b = n(now - 60_000, "спор правок", error = false, op = "op-a")
        val c = n(now - 120_000, "отказ", op = "op-c")
        val next = DelaNotices.markShown(listOf(a, b, c), listOf(a))
        assertEquals(listOf(true, false, false), next.map { it.shown })
        assertEquals(listOf(b, c), DelaNotices.visible(next, now))
        // Нечего помечать — тот же список.
        assertEquals(listOf(a, b, c), DelaNotices.markShown(listOf(a, b, c), emptyList()))
    }

    @Test
    fun outboxKeepsShownAndReadsOldFile() {
        val list = listOf(n(now, "отказ", shown = true), n(old, "спор правок", error = false, op = ""))
        val back = DelaNotices.fromJson(JSONArray(DelaNotices.toJson(list).toString()))
        assertEquals(list, back)
        // Несказанное пишется без лишнего ключа — файл как был.
        assertFalse(DelaNotices.toJson(list).getJSONObject(1).has("shown"))
        // dela-outbox.json до 16.10.2026: заметки без `shown` читаются как не сказанные.
        val oldFile = JSONObject(
            """{"v":1,"me":"u1","ops":[],"notices":[{"at":$old,"text":"Сервер не принял принять предложение: нет","error":true,"op_id":"0ab"}]}""",
        )
        val read = DelaNotices.fromJson(oldFile.optJSONArray("notices"))
        assertEquals(listOf(DelaNotices.Notice(old, "Сервер не принял принять предложение: нет", true, "0ab", false)), read)
        // …и старше суток уже не видны: обновление само уберёт висящую плашку.
        assertTrue(DelaNotices.visible(read, now).isEmpty())
        assertTrue(DelaNotices.fromJson(null).isEmpty())
    }
}
