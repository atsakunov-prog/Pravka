package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.ZasechkaStore

/**
 * Звонки режут дело (06.10.2026): с кем говорил — то и было; где звонок и так
 * часть дела — не режем. Владелец: «когда я говорю по телефону, то я не
 * работаю… а пришёл звонок по работе — я прямо вот работаю».
 */
class CallRulesTest {

    private val family = CallRules.familyList(CallRules.FAMILY_DEFAULT)
    private val people = listOf(
        CallRules.Person(listOf("Иван Петров", "Иван"), listOf("+7 (916) 123-45-67"), client = "Бета Групп", projectId = "p-beta"),
        CallRules.Person(listOf("Наташа Шепелина", "Наташа"), emptyList()),
    )

    private fun call(name: String, number: String = "") = CallRules.Call(0L, 10 * 60_000L, name, number, outgoing = false)

    @Test
    fun `семья — по имени контакта`() {
        val v = CallRules.verdict(call("Марианна"), family, people, null)
        assertEquals(CallRules.FAMILY, v.category)
        assertEquals("Звонок: Марианна", v.title)
        assertTrue(v.sure)
        assertEquals(CallRules.FAMILY, CallRules.verdict(call("Папа"), family, people, null).category)
        assertEquals(CallRules.FAMILY, CallRules.verdict(call("Марианна Цакунова"), family, people, null).category)
        assertEquals(CallRules.FAMILY, CallRules.verdict(call("Серёжа сын"), family, people, null).category)
        // «Мама» не цепляет «Маматова»: слово целиком или падеж, не любая приставка.
        assertEquals(CallRules.UNKNOWN, CallRules.verdict(call("Маматов Игорь"), family, emptyList(), null).category)
    }

    @Test
    fun `люди Дел — работа с их клиентом`() {
        // По номеру, хоть контакт записан иначе: «8 916…» и «+7 (916)…» — один номер.
        val byNumber = CallRules.verdict(call("Ваня Бета", "89161234567"), family, people, null)
        assertEquals(CallRules.WORK, byNumber.category)
        assertEquals("Бета Групп", byNumber.client)
        assertEquals("p-beta", byNumber.project)
        // По имени контакта целиком.
        val byName = CallRules.verdict(call("Наташа Шепелина"), family, people, null)
        assertEquals(CallRules.WORK, byName.category)
        assertEquals("", byName.client)
    }

    @Test
    fun `незнакомый — «Звонки» и вопрос, номер в названии`() {
        val v = CallRules.verdict(call("", "+79035550011"), family, people, null)
        assertEquals(CallRules.UNKNOWN, v.category)
        assertFalse(v.sure)
        assertEquals("Звонок: +7 903 555-00-11", v.title)
        assertEquals("Звонок: номер скрыт", CallRules.title(call("", "")))
        // Начало «Звонок» держит дедуп ленты: один разговор несколькими строками журнала.
        assertTrue(v.title.startsWith("Звонок"))
    }

    @Test
    fun `слово владельца сильнее всего`() {
        val learned = CallRules.Learned("Работа: текущая", "Альфа", "p-alfa")
        val v = CallRules.verdict(call("Папа"), family, people, learned)
        assertEquals("Работа: текущая", v.category)
        assertEquals("Альфа", v.client)
        assertEquals(CallRules.Why.LEARNED, v.why)
    }

    @Test
    fun `где звонок не режет дело`() {
        fun seen(title: String, category: String, source: String = "voice") = CallRules.Seen(title, category, source)
        assertEquals(CallRules.Skip.NONE, CallRules.skip(listOf(seen("Модель для Беты", "Работа: текущая"))))
        assertEquals(CallRules.Skip.NONE, CallRules.skip(listOf(seen("Сон", "Сон"))))
        assertEquals(CallRules.Skip.OWN_CALL, CallRules.skip(listOf(seen("Созвон с Ильёй", "Работа: текущая"))))
        assertEquals(CallRules.Skip.OWN_CALL, CallRules.skip(listOf(seen("Звонок маме", "Семья"))))
        assertEquals(CallRules.Skip.OWN_CALL, CallRules.skip(listOf(seen("Илья", CallRules.WORK))))
        assertEquals(CallRules.Skip.MEETING, CallRules.skip(listOf(seen("ПТИЦ (регулярный синк)", "Работа: текущая", CalendarRules.SOURCE))))
        assertEquals(CallRules.Skip.TRAVEL, CallRules.skip(listOf(seen("Поездка из «дом»", "Передвижение: транспорт"))))
        assertEquals(CallRules.Skip.SPORT, CallRules.skip(listOf(seen("Бег", "Спорт: бег"))))
        // Заполнитель и чужой авто-звонок — не «свой звонок».
        assertEquals(CallRules.Skip.NONE, CallRules.skip(listOf(seen("Не размечено", "Не размечено", "gap"))))
    }

    // ------------------------------------------------------------ «Убрать» в пуше

    private fun e(id: Long, start: Long, end: Long, title: String, category: String, source: String = "voice", raw: String = "") =
        ZasechkaStore.Entry(id, start, end, raw, title, category, "", 0, source, false, 0L)

    @Test
    fun `убрать звонок — дело сшивается обратно`() {
        val m = 60_000L
        val ribbon = listOf(
            e(1, 0, 30 * m, "Модель для Беты", "Работа: текущая", raw = "сажусь за модель"),
            e(2, 30 * m, 42 * m, "Звонок: Марианна", CallRules.FAMILY, source = "auto"),
            e(3, 42 * m, 0L, "Модель для Беты", "Работа: текущая"),
        )
        val (out, kept) = ZasechkaStore.dissolved(ribbon, 2)!!
        assertEquals(1, out.size)
        assertEquals(1L, kept!!.id)
        assertTrue("продолжение было открыто — дело снова идёт", kept.open)
        assertEquals("сажусь за модель", kept.raw)
    }

    @Test
    fun `убрать звонок, после которого было другое дело`() {
        val m = 60_000L
        val ribbon = listOf(
            e(1, 0, 30 * m, "Модель для Беты", "Работа: текущая"),
            e(2, 30 * m, 42 * m, "Звонок: Иван", CallRules.WORK, source = "auto"),
            e(3, 42 * m, 0L, "Обед", "Еда", raw = "обедаю"),
        )
        val (out, kept) = ZasechkaStore.dissolved(ribbon, 2)!!
        assertEquals(2, out.size)
        assertEquals(42 * m, kept!!.end)
        assertEquals("Обед", out.last().title)
        // Ручную запись «убрать» не трогает.
        assertEquals(null, ZasechkaStore.dissolved(ribbon, 1))
    }
}
