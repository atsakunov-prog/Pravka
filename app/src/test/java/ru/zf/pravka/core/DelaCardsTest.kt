package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Задание 4 (docs/dela-phone-4.md, 06.10.2026): проекты под клиентом, люди
 * плашками и «откуда он», «Люди» по компаниям, Claude в карточке, общий поиск.
 * Образец — веб (`server/pravka_dela/static/app.js`: `clientDeals`,
 * `dealShort`, `leaveClient`, `joinClient`, `renderPeople`, `renderSearch`,
 * `pageScope`). Данные выдуманные: репозиторий публичный.
 */
class DelaCardsTest {

    private val today = "2026-10-06"
    private val nowIso = "2026-10-06T12:00:00+03:00"

    private val orgBeta = "11111111-1111-4111-8111-111111111111"
    private val orgGamma = "22222222-2222-4222-8222-222222222222"
    private val beta = "33333333-3333-4333-8333-333333333333"
    private val alfa = "44444444-4444-4444-8444-444444444444"
    private val zf = "55555555-5555-4555-8555-555555555555"
    private val dModel = "66666666-6666-4666-8666-666666666666"
    private val dFunds = "77777777-7777-4777-8777-777777777777"
    private val dOld = "88888888-8888-4888-8888-888888888888"
    private val ivan = "99999999-9999-4999-8999-999999999999"
    private val olga = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val petr = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val nata = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val dupe = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    private val gleb = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"

    private fun snap(): Dela.Snapshot {
        val projects = listOf(
            Dela.Project(beta, "Бета Групп", aliases = listOf("Бета"), kind = "client", orgId = orgBeta),
            Dela.Project(alfa, "Альфа", kind = "client"),
            Dela.Project(zf, "ЗФ", kind = "internal", sphere = "work"),
        )
        val deals = listOf(
            Dela.Deal(dModel, beta, "Бета Групп: модель", stage = "proposal", personIds = listOf(petr), teamIds = listOf(nata)),
            Dela.Deal(dFunds, beta, "Бета: фонды", stage = "active", probability = 70, expectedOn = "2026-10-20", sourcePersonId = ivan),
            Dela.Deal(dOld, beta, "Бета: старое", stage = "archive", outcome = "won"),
        )
        val people = listOf(
            Dela.Person(ivan, "Иван Петров", short = "Иван", orgId = orgBeta, role = "CFO"),
            Dela.Person(olga, "Ольга Смирнова", orgId = orgGamma, role = "юрист"),
            Dela.Person(petr, "Пётр Сидоров", role = "аналитик"),
            Dela.Person(nata, "Наталья Команда", userId = "member1"),
            Dela.Person(dupe, "Иван П.", orgId = orgBeta, mergedInto = ivan),
            Dela.Person(gleb, "Глеб Без Компании"),
        )
        val tasks = listOf(
            Dela.Task("t1", num = 1, title = "Прислать модель", projectId = beta, dealId = dModel, ownerId = "sasha", dueDate = "2026-10-01"),
            Dela.Task("t2", num = 2, title = "Созвон по фондам", projectId = beta, dealId = dFunds, ownerId = "sasha", labels = listOf("звонок")),
            Dela.Task("t3", num = 3, title = "Отчёт", projectId = zf, ownerId = "sasha", personId = ivan, status = Dela.DONE),
        )
        return Dela.derive(
            Dela.Snapshot(
                today = today,
                tasks = tasks.associateBy { it.id },
                projects = projects.associateBy { it.id },
                deals = deals.associateBy { it.id },
                people = people.associateBy { it.id },
                orgs = listOf(Dela.Org(orgBeta, "ООО Бета"), Dela.Org(orgGamma, "Гамма Право")).associateBy { it.id },
            )
        )
    }

    @Test
    fun `проекты под клиентом — короткое имя, живые, открытые дела и просрочка`() {
        val s = snap()
        val b = s.projects.getValue(beta)
        assertEquals("модель", DelaCrm.dealShort("Бета Групп: модель", b))
        // Алиас тоже клиент: «Бета: фонды» под «Бета Групп» — «фонды».
        assertEquals("фонды", DelaCrm.dealShort("Бета: фонды", b))
        // Чужая приставка и имя без двоеточия не трогаются.
        assertEquals("Альфа: фонды", DelaCrm.dealShort("Альфа: фонды", b))
        assertEquals("модель", DelaCrm.dealShort("модель", b))
        assertEquals("Бета: x", DelaCrm.dealShort("Бета: x", null))
        val nav = DelaCrm.navDeals(s, beta, today)
        // Закрытый проект в меню не виден; живые — по ходу воронки (КП раньше «в работе»).
        assertEquals(listOf("модель", "фонды"), nav.map { it.short })
        assertEquals(1, nav[0].open)
        assertTrue(nav[0].late)
        assertFalse(nav[1].late)
        assertEquals(3, DelaCrm.clientDeals(s, beta).size)
        assertEquals(dOld, DelaCrm.clientDeals(s, beta).last().id)
    }

    @Test
    fun `люди клиента — из организации и из его сделок, без слитых дублей`() {
        val s = snap()
        assertEquals(listOf("Иван Петров", "Пётр Сидоров"), DelaCrm.clientPeople(s, beta).map { it.name })
        assertTrue(DelaCrm.clientPeople(s, alfa).isEmpty())
        // Слитый дубль — не живой и в справочнике выбора не виден.
        assertFalse(s.people.getValue(dupe).live)
        assertFalse(s.livePeople().any { it.id == dupe })
        val back = Dela.person(JSONObject(Dela.json(s.people.getValue(dupe)).toString()))!!
        assertEquals(ivan, back.mergedInto)
    }

    @Test
    fun `крестик у клиента — из организации и из людей сделок, «Вернуть» ставит как было`() {
        val s = snap()
        val ivanLeaves = DelaCrm.leaveClient(s, s.people.getValue(ivan), beta)!!
        assertEquals(listOf("person.set"), ivanLeaves.ops.map { it.getString("op") })
        assertTrue(ivanLeaves.ops[0].getJSONObject("set").isNull("org_id"))
        assertEquals(orgBeta, ivanLeaves.back[0].getJSONObject("set").getString("org_id"))
        assertEquals("Иван больше не в «Бета Групп»", ivanLeaves.said)
        // Пётр — не из организации, а в людях сделки: уходит из person_ids.
        val petrLeaves = DelaCrm.leaveClient(s, s.people.getValue(petr), beta)!!
        assertEquals(listOf("deal.set"), petrLeaves.ops.map { it.getString("op") })
        assertEquals(0, petrLeaves.ops[0].getJSONObject("set").getJSONArray("person_ids").length())
        assertEquals(petr, petrLeaves.back[0].getJSONObject("set").getJSONArray("person_ids").getString(0))
        assertTrue((ivanLeaves.ops + petrLeaves.ops + petrLeaves.back).all { Dela.isUuid(it.getString("op_id")) })
        // Сразу видно: поверх копии Пётр у клиента пропал, после «Вернуть» — снова здесь.
        val gone = Dela.overlay(s, petrLeaves.ops, "sasha", today, nowIso)
        assertEquals(listOf("Иван Петров"), DelaCrm.clientPeople(gone, beta).map { it.name })
        val again = Dela.overlay(s, petrLeaves.ops + petrLeaves.back, "sasha", today, nowIso)
        assertEquals(2, DelaCrm.clientPeople(again, beta).size)
        // Связан иначе — правки нет: поправить в карточке.
        assertNull(DelaCrm.leaveClient(s, s.people.getValue(olga), beta))
    }

    @Test
    fun `«+ человек» — в организацию клиента, у клиента без неё — заводим её`() {
        val s = snap()
        val join = DelaCrm.joinClient(s, beta, s.people.getValue(olga))!!
        assertEquals(listOf("person.set"), join.ops.map { it.getString("op") })
        assertEquals(orgBeta, join.ops[0].getJSONObject("set").getString("org_id"))
        assertEquals(orgGamma, join.back[0].getJSONObject("set").getString("org_id"))
        assertEquals("Ольга Смирнова — теперь из «Бета Групп»", join.said)
        // Уже оттуда — правки нет.
        assertNull(DelaCrm.joinClient(s, beta, s.people.getValue(ivan)))
        // Альфа без организации: сначала организация с её именем и проект на неё, потом новый человек.
        val fresh = DelaCrm.joinClient(s, alfa, null, name = " Анна Новая ", newOrgId = "o-new", newPersonId = "p-new")!!
        assertEquals(listOf("org.create", "project.set", "person.create"), fresh.ops.map { it.getString("op") })
        assertEquals("Альфа", fresh.ops[0].getJSONObject("data").getString("name"))
        assertEquals("o-new", fresh.ops[1].getJSONObject("set").getString("org_id"))
        val data = fresh.ops[2].getJSONObject("data")
        assertEquals("Анна Новая", data.getString("name"))
        assertEquals("o-new", data.getString("org_id"))
        assertEquals("p-new", data.getString("id"))
        // Видно до ответа сервера: проект узнал организацию, человек — у клиента.
        val v = Dela.overlay(s, fresh.ops, "sasha", today, nowIso)
        assertEquals("o-new", v.projects.getValue(alfa).orgId)
        assertEquals(listOf("Анна Новая"), DelaCrm.clientPeople(v, alfa).map { it.name })
        assertEquals(DelaCrm.OrgLabel("Альфа", alfa), DelaCrm.orgLabel(v, "o-new"))
        assertNull(DelaCrm.joinClient(s, alfa, null, name = "  "))
    }

    @Test
    fun `откуда он и должность — правка сразу поверх копии`() {
        val s = snap()
        assertEquals(DelaCrm.OrgLabel("Бета Групп", beta), DelaCrm.orgLabel(s, orgBeta))
        assertEquals(DelaCrm.OrgLabel("Гамма Право"), DelaCrm.orgLabel(s, orgGamma))
        assertNull(DelaCrm.orgLabel(s, ""))
        // Клиенты по имени проекта, потом другие компании; организация клиента второй раз не встаёт.
        assertEquals(listOf("Бета Групп" to true, "Гамма Право" to false), DelaCrm.orgChoices(s).map { it.name to it.client })
        val op = DelaCrm.personSetOp(olga, JSONObject().put("org_id", orgBeta).put("role", "партнёр"))
        val v = Dela.overlay(s, listOf(op), "sasha", today, nowIso)
        assertEquals(orgBeta, v.people.getValue(olga).orgId)
        assertEquals("партнёр", v.people.getValue(olga).role)
        assertTrue(v.people.getValue(olga).local)
        assertEquals("человек «Ольга Смирнова»", Dela.describe(op, s))
    }

    @Test
    fun `люди по компаниям — команда, организации, без компании, поиск по должности и компании`() {
        val s = snap()
        val g = DelaCrm.peopleByCompany(s, "")
        assertEquals(listOf("Команда", "Бета Групп", "Гамма Право", "Без компании"), g.map { it.title })
        assertEquals(beta, g[1].clientId)
        assertEquals("", g[2].clientId)
        assertEquals(listOf("Глеб Без Компании", "Пётр Сидоров"), g[3].people.map { it.name })
        // Слитого дубля нет нигде.
        assertFalse(g.flatMap { it.people }.any { it.id == dupe })
        assertEquals(listOf("Ольга Смирнова"), DelaCrm.peopleByCompany(s, "юрист").flatMap { it.people }.map { it.name })
        assertEquals(listOf("Иван Петров"), DelaCrm.peopleByCompany(s, "бета cfo").flatMap { it.people }.map { it.name })
        assertTrue(DelaCrm.peopleByCompany(s, "нет такого").isEmpty())
    }

    @Test
    fun `люди сделки и команда — крестик и плюс видны сразу`() {
        val s = snap()
        val op = DelaCrm.dealPeopleOp(dModel, "team_ids", listOf(nata, ivan, ivan))
        assertEquals(2, op.getJSONObject("set").getJSONArray("team_ids").length())
        val v = Dela.overlay(s, listOf(op, DelaCrm.dealPeopleOp(dModel, "person_ids", emptyList())), "sasha", today, nowIso)
        assertEquals(listOf(nata, ivan), v.deals.getValue(dModel).teamIds)
        assertTrue(v.deals.getValue(dModel).personIds.isEmpty())
        // И в карточке сделки из кэша вида.
        val view = DelaCrm.Deal(dModel, beta, "Бета Групп", "Бета Групп: модель", "proposal", personIds = listOf(petr), teamIds = listOf(nata))
        val o = DelaCrm.overlayDeal(view, listOf(op), today)
        assertEquals(listOf(nata, ivan), o.teamIds)
        assertEquals(listOf(petr), o.personIds)
        // Поля «что за сделка» — из синка и обратно.
        val d = s.deals.getValue(dFunds)
        assertEquals(70, d.probability)
        val back = Dela.deal(JSONObject(Dela.json(d).toString()))!!
        assertEquals(d, back)
        val fromView = DelaCrm.deal(JSONObject().put("id", dFunds).put("probability", 70).put("expected_on", "2026-10-20").put("source_person_id", ivan))!!
        assertEquals(70, fromView.probability)
        assertEquals(ivan, fromView.sourcePersonId)
        assertNull(DelaCrm.deal(JSONObject().put("id", dFunds).put("probability", JSONObject.NULL))!!.probability)
    }

    @Test
    fun `общий поиск — клиенты, проекты клиентов, люди и дела, каждое слово`() {
        val s = snap()
        val f = DelaViews.searchAll(s, "бета", showDone = false)
        assertEquals(listOf("Бета Групп"), f.projects.map { it.name })
        // Проекты клиента — живые первыми, закрытый в конце.
        assertEquals(listOf("Бета Групп: модель", "Бета: фонды", "Бета: старое"), f.deals.map { it.name })
        // Иван — по компании «Бета Групп».
        assertEquals(listOf("Иван Петров"), f.people.map { it.name })
        assertEquals(setOf("t1", "t2"), f.tasks.map { it.id }.toSet())
        // Дело — и по имени сделки, и по метке; закрытое — только со «Сделанными».
        assertEquals(listOf("t2"), DelaViews.searchAll(s, "фонды звонок", showDone = false).tasks.map { it.id })
        assertTrue(DelaViews.searchAll(s, "отчет", showDone = false).tasks.isEmpty())
        assertEquals(listOf("t3"), DelaViews.searchAll(s, "отчет иван", showDone = true).tasks.map { it.id })
        assertEquals(0, DelaViews.searchAll(s, "  ", showDone = true).total)
        assertEquals(listOf("Ольга Смирнова"), DelaViews.searchAll(s, "гамма юр", showDone = false).people.map { it.name })
    }

    @Test
    fun `Claude в карточке — scope card сервера`() {
        val c = DelaAsk.clientScope("Клиент: Бета Групп", listOf("t1", dModel), beta)
        assertEquals("client", c.getString("card"))
        assertEquals(beta, c.getString("project_id"))
        // Не uuid в task_ids сервер отверг бы весь запрос — их там нет.
        assertEquals(1, c.getJSONArray("task_ids").length())
        val d = DelaAsk.dealScope("Сделка: Бета: фонды", emptyList(), dFunds, beta)
        assertEquals("deal", d.getString("card"))
        assertEquals(dFunds, d.getString("deal_id"))
        assertEquals(beta, d.getString("project_id"))
        val p = DelaAsk.personScope("Человек: Иван Петров", emptyList(), ivan)
        assertEquals("person", p.getString("card"))
        assertEquals(ivan, p.getString("person_id"))
        // Без карточки поля нет; незнакомое слово — тоже.
        assertFalse(DelaAsk.scope("Сейчас", emptyList()).has("card"))
        assertFalse(DelaAsk.scope("x", emptyList(), card = "project").has("card"))
        assertEquals(JSONArray().length(), DelaAsk.scope("x", emptyList()).getJSONArray("task_ids").length())
    }
}
