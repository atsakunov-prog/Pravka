package ru.zf.pravka.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Человек — одна карточка (docs/svod-phone.md, часть 3): звонок спрашивает
 * сервер «кто это», запись ленты несёт id человека, телефон учит сервер номеру
 * и имени контакта (`person.add`), разговор с человеком клиента — в
 * хронологию CRM. Имена и номера выдуманные (из контракта svod.json).
 */
class CallWhoTest {

    private val sokolov = CallRules.Person(
        names = listOf("Евгений Соколов", "Евгений Соколов VP банка"), phones = emptyList(),
        client = "Ромашка-банк", projectId = "p-romashka", id = "c2f8",
    )
    private val ivanov = CallRules.Person(names = listOf("Евгений Иванов"), phones = listOf("+7 999 000-11-22"), id = "e1a0")
    private val people = listOf(sokolov, ivanov)

    private fun call(name: String, number: String = "+7 916 123-45-67", min: Int = 12) =
        CallRules.Call(1_000_000L, 1_000_000L + min * 60_000L, name, number, outgoing = false)

    private fun contractWho(): JSONObject {
        val f = listOf(File("../server/contract/svod.json"), File("server/contract/svod.json")).first { it.isFile }
        return JSONObject(f.readText()).getJSONObject("people").getJSONObject("who").getJSONObject("answer")
    }

    @Test
    fun `ответ «кто это» из контракта`() {
        val w = CallRules.parseWho(contractWho())
        assertTrue(w.sure)
        assertEquals("c2f8…", w.best?.id)
        assertEquals("Евгений Соколов", w.best?.name)
        assertEquals(listOf("c2f8…"), w.candidates.map { it.id })
        val none = CallRules.parseWho(JSONObject().put("best", JSONObject.NULL).put("sure", true).put("candidates", JSONArray()))
        assertNull(none.best)
        assertFalse("без лучшего уверенности нет", none.sure)
    }

    @Test
    fun `звонок — who уверен — человек в записи и person add`() {
        val who = CallRules.Who(CallRules.Candidate("c2f8", "Евгений Соколов", 1.0), true, listOf(CallRules.Candidate("c2f8", "Евгений Соколов", 1.0)))
        val v = CallRules.verdict(call("Женя Соколов"), emptyList(), people, null, who)
        assertEquals(CallRules.WORK, v.category)
        assertEquals("c2f8", v.personId)
        assertEquals("Ромашка-банк", v.client)
        assertEquals("p-romashka", v.project)
        assertTrue(v.sure)
        // Номера и «Жени Соколова» у карточки нет — телефон учит сервер.
        val op = CallRules.learnOp(sokolov, call("Женя Соколов"))!!
        assertEquals("person.add", op.getString("op"))
        assertEquals("c2f8", op.getString("id"))
        assertTrue(Dela.isUuid(op.getString("op_id")))
        val add = op.getJSONObject("add")
        assertEquals("Женя Соколов", add.getJSONArray("aliases").getString(0))
        assertEquals("+7 916 123-45-67", add.getJSONArray("phones").getString(0))
        // Тот же звонок второй раз — тот же op_id: в очередь не встанет дважды.
        assertEquals(op.getString("op_id"), CallRules.learnOp(sokolov, call("Женя Соколов"))!!.getString("op_id"))
        // Всё уже знает — учить нечему.
        val knows = sokolov.copy(names = sokolov.names + "Женя Соколов", phones = listOf("8 (916) 123-45-67"))
        assertNull(CallRules.learnOp(knows, call("женя соколов")))
    }

    @Test
    fun `не уверен — вопрос с кандидатами, человека в записи нет`() {
        val who = CallRules.Who(
            CallRules.Candidate("c2f8", "Евгений Соколов", 0.6), false,
            listOf(CallRules.Candidate("c2f8", "Евгений Соколов", 0.6), CallRules.Candidate("e1a0", "Евгений Иванов", 0.6), CallRules.Candidate("gone", "Архивный", 0.5)),
        )
        val v = CallRules.verdict(call("Женя", "+7 905 000-00-00"), emptyList(), people, null, who)
        assertEquals(CallRules.UNKNOWN, v.category)
        assertEquals("", v.personId)
        assertFalse(v.sure)
        assertEquals("кандидаты — только живые люди синка", listOf("c2f8", "e1a0"), v.candidates.map { it.id })
    }

    @Test
    fun `без ответа сервера — прежнее точное сопоставление`() {
        val v = CallRules.verdict(call("Женя Соколов", "+7 999 000-11-22"), emptyList(), people, null, who = null)
        assertEquals("e1a0", v.personId)
        assertEquals("контакт «Женя Соколов» и карточка «Евгений Соколов» — без сервера разные", CallRules.UNKNOWN,
            CallRules.verdict(call("Женя Соколов", ""), emptyList(), people, null, null).category)
    }

    @Test
    fun `человек семьи по карточке — «Семья», с id`() {
        val who = CallRules.Who(CallRules.Candidate("e1a0", "Евгений Иванов", 1.0), true, emptyList())
        val v = CallRules.verdict(call("Женя"), emptyList(), people, null, who, familyIds = setOf("e1a0"))
        assertEquals(CallRules.FAMILY, v.category)
        assertEquals("e1a0", v.personId)
    }

    @Test
    fun `звонок от двух минут с человеком клиента — в хронологию, без дублей`() {
        val op = CallRules.interactionOp(call("Женя Соколов"), "Звонок: Женя Соколов", "c2f8", "p-romashka", "2026-10-06T12:00:00+03:00")!!
        assertEquals("interaction.add", op.getString("op"))
        val d = op.getJSONObject("data")
        assertEquals("call", d.getString("kind"))
        assertEquals("phone", d.getString("source"))
        assertEquals("p-romashka", d.getString("project_id"))
        assertEquals("c2f8", d.getJSONArray("person_ids").getString(0))
        assertEquals(12, d.getInt("duration_min"))
        assertEquals(op.getString("op_id"), CallRules.interactionOp(call("Женя Соколов"), "Звонок", "c2f8", "p-romashka", "x")!!.getString("op_id"))
        assertNull("короче двух минут — не разговор", CallRules.interactionOp(call("Женя", min = 1), "З", "c2f8", "p", "x"))
        assertNull("без клиента — не CRM", CallRules.interactionOp(call("Женя"), "З", "c2f8", "", "x"))
        assertNotNull(CallRules.interactionOp(call("Женя", min = 2), "З", "c2f8", "p", "x"))
    }
}
