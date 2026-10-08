package ru.zf.pravka.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Задание 9 (docs/dela-phone-9.md) — карточки клиента и проекта как в вебе:
 * хронология вперемешку, итог встречи коротко, «+ человек» поиском, проекты
 * человека со стороной, новые поля едут синком, разговор с Claude помнит реплики.
 * Данные выдуманные.
 */
class DelaCardsWebTest {

    private val today = "2026-10-09"

    @Test
    fun `хронология — записи, счета и оплаты вперемешку, свежие сверху`() {
        val call = DelaCrm.Interaction("i1", "2026-10-08T15:00:00+03:00", "call", "Созвон: ждут модель")
        val meet = DelaCrm.Interaction("i2", "2026-10-03T11:00:00+03:00", "meeting", "Бета: модель: договорились о сценариях")
        val pay = Dela.Payment("p1", "d1", amountKop = 10_000_000, invoicedOn = "2026-10-05", paidOn = "2026-10-09")
        val gone = Dela.Payment("p2", "d1", amountKop = 5_000_000, invoicedOn = "2026-10-07", cancelledAt = "2026-10-08T10:00:00+03:00")
        val rows = DelaCrm.timelineRows(listOf(meet, call), listOf(pay, gone))
        assertEquals(listOf("оплата", "i1", "счёт", "i2"), rows.map { it.item?.id ?: if (it.paid) "оплата" else "счёт" })
    }

    @Test
    fun `итог встречи — тема до последнего двоеточия в начале, договорённости списком`() {
        assertEquals("Бета: модель" to "договорились о двух сценариях", DelaCrm.meetingParts("Бета: модель: договорились о двух сценариях"))
        // Двоеточие после первой точки — уже не тема.
        assertEquals(null to "Созвонились. Итог: ждут модель", DelaCrm.meetingParts("Созвонились. Итог: ждут модель"))
        assertEquals(listOf("Иван пришлёт выгрузку", "мы — модель к пятнице", "созвон в среду"),
            DelaCrm.steps("→ Иван пришлёт выгрузку; мы — модель к пятнице.\n• созвон в среду;"))
        assertEquals("https://disk.example.ru/a", DelaCrm.withScheme("disk.example.ru/a"))
        assertEquals("http://x.ru", DelaCrm.withScheme(" http://x.ru "))
    }

    private fun snap(): Dela.Snapshot {
        val people = listOf(
            Dela.Person("pe-ivan", "Иван Петров", short = "Иван", orgId = "o-beta", role = "финдиректор"),
            Dela.Person("pe-anna", "Анна Белова", short = "Анна", orgId = "o-beta", role = "казначей"),
            Dela.Person("pe-lena", "Елена Орлова", short = "Лена", userId = "lena"),
            Dela.Person("pe-petr", "Пётр Ершов", short = "Пётр", role = "юрист"),
        ).associateBy { it.id }
        val deals = listOf(
            Dela.Deal("d1", "p-beta", "Бета Групп: финмодель", stage = "active", leadPersonId = "pe-lena", teamIds = listOf("pe-petr"), personIds = listOf("pe-ivan")),
            Dela.Deal("d2", "p-beta", "Бета Групп: рефинансирование", stage = "archive", outcome = "lost", sourcePersonId = "pe-ivan"),
        ).associateBy { it.id }
        return Dela.Snapshot(
            people = people, deals = deals,
            projects = mapOf("p-beta" to Dela.Project("p-beta", "Бета Групп", orgId = "o-beta")),
            orgs = mapOf("o-beta" to Dela.Org("o-beta", "Бета Групп")),
        )
    }

    @Test
    fun `+ человек — сразу свои, набрал — среди всех по имени, компании, должности`() {
        val s = snap()
        val base = listOf(s.people.getValue("pe-lena"), s.people.getValue("pe-petr"))
        assertEquals(listOf("pe-petr"), DelaCrm.searchPeople(s, "", base, exclude = setOf("pe-lena")).map { it.id })
        // «бета» — компания: оба из Беты; «каз» — кусок должности.
        assertEquals(setOf("pe-ivan", "pe-anna"), DelaCrm.searchPeople(s, "бета", base, emptySet()).map { it.id }.toSet())
        assertEquals(listOf("pe-anna"), DelaCrm.searchPeople(s, "казн", base, emptySet()).map { it.id })
        // Каждое слово должно найтись.
        assertEquals(listOf("pe-ivan"), DelaCrm.searchPeople(s, "иван фин", base, emptySet()).map { it.id })
        assertTrue(DelaCrm.searchPeople(s, "иван юрист", base, emptySet()).isEmpty())
        assertEquals(listOf("pe-lena", "pe-petr"), DelaCrm.teamPeople(s).map { it.id })
    }

    @Test
    fun `проекты человека — со стороной, живые сверху, команда клиента — ведущие и команды живых`() {
        val s = snap()
        assertEquals(listOf("Бета Групп: финмодель" to "от клиента", "Бета Групп: рефинансирование" to "привёл"),
            DelaCrm.personDeals(s, "pe-ivan").map { it.first.name to it.second })
        assertEquals(listOf("ведёт"), DelaCrm.personDeals(s, "pe-lena").map { it.second })
        assertEquals(listOf("pe-lena", "pe-petr"), DelaCrm.clientTeam(s.deals.values.toList()))
    }

    @Test
    fun `новые поля — описание, папка, файлы, статус клиента, кому ушёл счёт — синком и в кэш`() {
        val d = Dela.deal(JSONObject("{\"id\": \"d1\", \"project_id\": \"p\", \"name\": \"X\", \"description\": \"Модель на 2027\", " +
            "\"folder_url\": \"https://disk.example.ru/x\", \"files\": [{\"kind\": \"nda\", \"title\": \"NDA\", \"url\": \"https://disk.example.ru/nda\", \"at\": \"2026-10-01\"}, {\"kind\": \"act\"}]}"))!!
        assertEquals("Модель на 2027", d.description)
        assertEquals(listOf(Dela.FileRef("nda", "NDA", "https://disk.example.ru/nda", "2026-10-01")), d.files)
        assertEquals(d, Dela.deal(Dela.json(d)))
        val p = Dela.project(JSONObject("{\"id\": \"p\", \"name\": \"Бета\", \"status\": \"relations\"}"))!!
        assertEquals("relations", p.status)
        assertEquals(p, Dela.project(Dela.json(p)))
        // Старый сервер полей не знает — пусто, не «null».
        val old = Dela.deal(JSONObject("{\"id\": \"d2\", \"project_id\": \"p\", \"name\": \"Y\", \"description\": null}"))!!
        assertEquals("", old.description)
        assertTrue(old.files.isEmpty())
        val pay = Dela.payment(JSONObject("{\"id\": \"pay\", \"deal_id\": \"d1\", \"amount_kop\": 100, \"sent_to\": \"бухгалтерия\", \"sent_via\": \"почтой\"}"))!!
        assertEquals("бухгалтерия" to "почтой", pay.sentTo to pay.sentVia)
        val inv = DelaCrm.invoiceOp("pay", today, " Иван ", "")
        assertEquals("Иван", inv.getJSONObject("set").getString("sent_to"))
        assertTrue(inv.getJSONObject("set").isNull("sent_via"))
        // Правка сделки видна сразу, до ответа.
        val s = snap()
        val after = Dela.applyDeal(s.deals.getValue("d1"), JSONObject().put("description", "Суть").put("lead_person_id", "pe-petr"), today)
        assertEquals("Суть" to "pe-petr", after.description to after.leadPersonId)
    }

    @Test
    fun `разговор — память реплик, история не больше восьми, принятое возвращается`() {
        val s = Dela.Snapshot()
        val id = Dela.newId()
        val r = DelaAsk.parse(JSONObject("{\"route\": \"edit\", \"reply\": \"Поставил. Какой срок?\", " +
            "\"decided\": [{\"n\": 1, \"decision\": \"accept\", \"kind\": \"create\", \"id\": \"$id\", \"title\": \"Позвонить в банк\", \"created\": true}," +
            "{\"n\": 2, \"decision\": \"reject\", \"kind\": \"create\", \"reason\": \"дубль\", \"what\": \"КП\"}]}"))
        assertTrue(r.did)
        assertTrue(r.undoable)
        assertEquals("П1 заведено: Позвонить в банк", DelaAsk.decidedWord(r.decided[0]))
        assertEquals("П2 отклонено: КП", DelaAsk.decidedWord(r.decided[1]))
        // Вернуть принятое: заведённое — отменой; отклонённое не возвращается.
        assertEquals(listOf("task.cancel" to id), DelaAsk.undoOps(r).map { it.getString("op") to it.getString("id") })
        val memo = DelaAsk.memo(DelaAsk.Turn("П1 поставь, П2 не надо", r, undone = true), s)
        assertEquals("П1 заведено; П2 отклонено — Саша вернул всё это как было", memo.getString("done"))
        val turns = (1..10).map { DelaAsk.Turn("реплика $it", r) }
        val h = DelaAsk.withHistory(JSONObject().put("title", "Новое"), turns, s).getJSONArray("history")
        assertEquals(DelaAsk.TALK_KEEP, h.length())
        assertEquals("реплика 3", h.getJSONObject(0).getString("said"))
        // Первая реплика — без истории: старый сервер и так её не знает.
        assertFalse(DelaAsk.withHistory(JSONObject(), emptyList(), s).has("history"))
        // Ничего не сделал и не ответил — команда снова в поле.
        val empty = DelaAsk.parse(JSONObject("{\"route\": \"edit\", \"reply\": \"\"}"))
        assertFalse(empty.did)
        assertNull(empty.changed.firstOrNull())
    }

    @Test
    fun `клиенты — порядок сервера, фильтр поддержания отношений`() {
        val v = DelaCrm.clients(JSONObject("{\"clients\": [" +
            "{\"id\": \"c2\", \"name\": \"Орион\", \"last_task_at\": \"2026-10-08\"}," +
            "{\"id\": \"c1\", \"name\": \"Альфа\", \"status\": \"relations\", \"last_task_at\": \"2026-09-01\"}]}"))
        assertEquals(listOf("c2", "c1"), v.find("").map { it.id })
        assertEquals(listOf("c1"), v.find("", relationsOnly = true).map { it.id })
        assertTrue(v.clients[1].relations)
    }
}
