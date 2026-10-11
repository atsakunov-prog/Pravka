package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.ZFace.Icon
import ru.zf.pravka.data.ZasechkaStore

// Лицо «З» — идущее дело (docs/zasechka.md, «Кнопка «З»: идущее дело»):
// «кнопка засечки меняется на значок с тем делом, которое есть… и внизу
// под ним минуты, сколько уже это дело идёт».
class ZFaceTest {

    @Test
    fun `у каждой заводской категории свой значок, кроме Не размечено`() {
        val all = ZasechkaStore.DEFAULT_CATEGORIES.map { it.name } +
            ZasechkaStore.neutralCategories().map { it.name }
        for (name in all.distinct()) {
            if (name == "Не размечено") assertNull(name, ZFace.icon(name))
            else assertTrue("у «$name» нет значка", ZFace.icon(name) != null)
        }
    }

    @Test
    fun `раздел решает раньше уточнения`() {
        // «Передвижение: вело» — дорога на велосипеде, а не велотренировка, но
        // значок у них один; транспорт и спорт без уточнения — свои.
        assertEquals(Icon.BIKE, ZFace.icon("Спорт: вело"))
        assertEquals(Icon.BIKE, ZFace.icon("Передвижение: вело"))
        assertEquals(Icon.WALK, ZFace.icon("Передвижение: пешком"))
        assertEquals(Icon.TRANSPORT, ZFace.icon("Передвижение: транспорт"))
        assertEquals(Icon.STRENGTH, ZFace.icon("Спорт: силовая"))
        assertEquals(Icon.RUN, ZFace.icon("Спорт: бег"))
        assertEquals(Icon.SPORT, ZFace.icon("Спорт: прочее"))
    }

    @Test
    fun `работа — по уточнению`() {
        assertEquals(Icon.LEADS, ZFace.icon("Работа: привлечение"))
        assertEquals(Icon.WORK, ZFace.icon("Работа: текущая"))
        assertEquals(Icon.PLAN, ZFace.icon("Работа: планирование"))
        assertEquals(Icon.CALL, ZFace.icon("Работа: звонки"))
        assertEquals(Icon.CALL, ZFace.icon("работа — созвоны"))
        assertEquals("звонок без раздела — та же трубка", Icon.CALL, ZFace.icon("Звонки"))
    }

    @Test
    fun `категорию владелец правит сам — узнаём по словам`() {
        assertEquals(Icon.SLEEP, ZFace.icon("  сон "))
        assertEquals(Icon.STUDY, ZFace.icon("Учёба"))
        assertEquals(Icon.STUDY, ZFace.icon("учеба"))
        assertEquals(Icon.LOVE, ZFace.icon("Секс: с Марианной"))
        assertNull("незнакомая — своя «З»", ZFace.icon("Гитара"))
        assertNull("без категории — своя «З»", ZFace.icon(""))
    }

    @Test
    fun `дыра — без значка, но с минутами`() {
        val f = ZFace.of("Не размечено", "gap", 1_000L)
        assertNull(f.icon)
        assertEquals(1_000L, f.start)
        // Даже если заполнитель кто-то переименовал в «Потери» — это не дело.
        assertNull(ZFace.of("Потери", "gap", 0L).icon)
        assertEquals(Icon.LOSS, ZFace.of("Потери", "voice", 0L).icon)
    }

    @Test
    fun `минуты до часа, дальше часы с минутами, сутки — днями`() {
        assertEquals("0", ZFace.label(59_999))
        assertEquals("1", ZFace.label(60_000))
        assertEquals("59", ZFace.label(59 * 60_000L + 59_999))
        assertEquals("1:00", ZFace.label(60 * 60_000L))
        assertEquals("1:25", ZFace.label(85 * 60_000L))
        assertEquals("23:59", ZFace.label(24 * 60 * 60_000L - 1))
        assertEquals("1д", ZFace.label(24 * 60 * 60_000L))
        assertEquals("2д", ZFace.label(50 * 60 * 60_000L))
        assertEquals("начало в будущем — ноль, не минус", "0", ZFace.label(-5_000))
    }

    @Test
    fun `тик — к смене минуты, с запасом`() {
        assertEquals(60_000L + ZFace.TICK_SLACK_MS, ZFace.nextTickMs(0))
        assertEquals(1_000L + ZFace.TICK_SLACK_MS, ZFace.nextTickMs(59_000))
        assertEquals(30_000L + ZFace.TICK_SLACK_MS, ZFace.nextTickMs(5 * 60_000L + 30_000))
        // Через тик подпись уже другая.
        val e = 4 * 60_000L + 59_950
        assertEquals("4", ZFace.label(e))
        assertEquals("5", ZFace.label(e + ZFace.nextTickMs(e)))
    }
}
