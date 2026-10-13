package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.prompts.PromptsRaznoska
import ru.zf.pravka.core.prompts.SharedPrompts
import ru.zf.pravka.provider.parseTasksDela

/** Место в дереве при разборе (docs/dela-phone-15.md): дерево в промпте, номер цели — серверу полем why_auto. */
class DelaPlaceTest {

    private val tree = "<дерево жизни>\nЗдоровье\n  #12 Еда\n</дерево жизни>"

    @Test
    fun `дерево встаёт вместо TREE — в голову промпта под кэшем`() {
        val full = PromptsRaznoska.TASKS_DELA
        assertTrue(SharedPrompts.raznoska.contains("{TREE}"))
        // askSplit режет голову по {TODAY}: дерево стабильно и должно быть в ней, как справочник.
        assertTrue(full.indexOf("{TREE}") < full.indexOf("{TODAY}"))
        val out = DelaPlace.withTree(full, tree)
        assertFalse(out.contains("{TREE}"))
        assertTrue(out.indexOf("#12 Еда") < out.indexOf("{TODAY}"))
    }

    @Test
    fun `правила без TREE — дерево перед строкой с датой, блока нет — так и сказано`() {
        val rules = "Разбери.\n{CATALOG}\n\nСегодня {TODAY}, сейчас {NOW}."
        assertEquals("Разбери.\n{CATALOG}\n\n$tree\n\nСегодня {TODAY}, сейчас {NOW}.", DelaPlace.withTree(rules, tree))
        assertEquals("a\n\n" + DelaPlace.NO_TREE, DelaPlace.withTree("a", ""))
        assertEquals("x " + DelaPlace.NO_TREE + " y", DelaPlace.withTree("x {TREE} y", "  "))
    }

    @Test
    fun `номер цели из ответа модели — как есть, без угадывания`() {
        val raw = """{"tasks": [
            {"title": "Купить протеин", "goal": 12},
            {"title": "Альфа: счёт", "goal": 0},
            {"title": "Старый ответ"},
            {"title": "Кривой", "goal": -3}
        ], "notes": []}"""
        val tasks = parseTasksDela(raw, Dela.Snapshot()).first
        assertEquals(listOf(12, 0, 0, 0), tasks.map { it.whyAuto })
    }

    @Test
    fun `why_auto — только серверу, который его знает, и только с номером`() {
        fun op() = Dela.createOp(Dela.Task(id = "t1", title = "Купить протеин"), "op1")
        assertEquals(12, DelaPlace.withPlace(op(), 12, true).getJSONObject("task").getInt("why_auto"))
        assertFalse(DelaPlace.withPlace(op(), 12, false).getJSONObject("task").has("why_auto"))
        assertFalse(DelaPlace.withPlace(op(), 0, true).getJSONObject("task").has("why_auto"))
        val snap = Dela.Snapshot(features = setOf(Dela.FEATURE_PLACE))
        assertTrue(snap.placeOn && !Dela.Snapshot().placeOn)
        assertEquals("task.create", DelaPlace.withPlace(op(), 12, true).getString("op"))
    }

    @Test
    fun `блок — из Свода`() {
        val was = Svod.current
        try {
            Svod.current = mapOf(DelaPlace.SVOD_KEY to Svod.Entry(key = DelaPlace.SVOD_KEY, body = "  $tree \n"))
            assertEquals(tree, DelaPlace.block())
            Svod.current = emptyMap()
            assertEquals("", DelaPlace.block())
        } finally {
            Svod.current = was
        }
    }
}
