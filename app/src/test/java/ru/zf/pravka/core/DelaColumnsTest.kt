package ru.zf.pravka.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.core.DelaColumns.State

/** Три колонки разложенных Дел (14.10.2026): кому сколько места и куда встаёт отпущенный язычок. */
class DelaColumnsTest {

    /** Внутренний экран Fold в снимках — 791 dp. */
    private val w = 791f
    private val room = w * (1f - DelaColumns.MID_SHARE)
    private val eps = 0.01f

    @Test
    fun `с завода — боковина 172, карточка — сколько оставляет доля списка, список не меньше 40 процентов`() {
        val f = DelaColumns.fit(w, State())
        assertEquals(172f, f.side, eps)
        // Половина остатка (309,5) больше, чем позволяет доля списка, — карточка уступает.
        assertEquals(room - 172f, f.pane, eps)
        assertTrue(w - f.side - f.pane >= w * DelaColumns.MID_SHARE - eps)
    }

    @Test
    fun `спрятанная колонка — ноль, список и карточка делят место как раньше`() {
        val noSide = DelaColumns.fit(w, State(sideOn = false))
        assertEquals(0f, noSide.side, eps)
        assertEquals(w / 2f, noSide.pane, eps)
        val noPane = DelaColumns.fit(w, State(paneOn = false))
        assertEquals(172f, noPane.side, eps)
        assertEquals(0f, noPane.pane, eps)
        val none = DelaColumns.fit(w, State(sideOn = false, paneOn = false))
        assertEquals(0f, none.side + none.pane, eps)
    }

    @Test
    fun `помнит устройство — тем же файлом, прежний ключ боковины читается как был`() {
        assertEquals(State(), DelaColumns.read(emptyMap()))
        assertFalse(DelaColumns.read(mapOf("ui:side" to "0")).sideOn)
        val s = State(sideOn = false, sideW = 200.4f, paneOn = true, paneW = 300f)
        val back = DelaColumns.read(DelaColumns.write(s))
        assertFalse(back.sideOn)
        assertEquals(200f, back.sideW!!, eps)
        assertEquals(300f, back.paneW!!, eps)
        // Не тянули — ключа ширины нет: заводская считается сама и на другом экране.
        assertFalse(DelaColumns.write(State()).containsKey(DelaColumns.SIDE_W_KEY))
        assertNull(DelaColumns.read(mapOf("ui:sideW" to "мусор")).sideW)
    }

    @Test
    fun `боковину тянут шире — карточка уступает до своего минимума, дальше язычок не идёт`() {
        val s = State()
        val live = DelaColumns.dragSide(w, s, 250f)
        assertEquals(DelaColumns.maxSide(w, paneOn = true), live.side, eps)
        assertEquals(DelaColumns.PANE_MIN, live.pane, eps)
        val after = DelaColumns.dropSide(w, s, 250f)
        assertTrue(after.sideOn)
        val f = DelaColumns.fit(w, after)
        assertEquals(room - DelaColumns.PANE_MIN, f.side, eps)
        assertEquals(DelaColumns.PANE_MIN, f.pane, eps)
        // Сузили боковину обратно — карточка снова прежняя: её ширину никто не трогал.
        val back = DelaColumns.fit(w, DelaColumns.dropSide(w, after, 172f))
        assertEquals(room - 172f, back.pane, eps)
    }

    @Test
    fun `карточку тянут шире — уступает боковина и остаётся такой, какой её видели`() {
        val s = State()
        val after = DelaColumns.dropPane(w, s, 400f)
        val f = DelaColumns.fit(w, after)
        assertEquals(DelaColumns.maxPane(w, sideOn = true), f.pane, eps)
        assertEquals(DelaColumns.SIDE_MIN, f.side, eps)
        assertEquals(DelaColumns.SIDE_MIN, after.sideW!!, eps)
        // Что видно под пальцем — то и встаёт.
        val live = DelaColumns.dragPane(w, s, 400f)
        assertEquals(f.pane, live.pane, eps)
        assertEquals(f.side, live.side, eps)
    }

    @Test
    fun `карточку сузили — боковину не трогаем`() {
        val after = DelaColumns.dropPane(w, State(sideW = 180f), 290f)
        assertEquals(180f, after.sideW!!, eps)
        assertEquals(290f, after.paneW!!, eps)
        val f = DelaColumns.fit(w, after)
        assertEquals(180f, f.side, eps)
        assertEquals(290f, f.pane, eps)
    }

    @Test
    fun `отпустил меньше половины минимума — колонка в доке, ширина прежняя`() {
        val s = State(sideW = 190f, paneW = 290f)
        val side = DelaColumns.dropSide(w, s, DelaColumns.SIDE_MIN / 2f - 1f)
        assertFalse(side.sideOn)
        assertEquals(190f, side.sideW!!, eps)
        val pane = DelaColumns.dropPane(w, s, 100f)
        assertFalse(pane.paneOn)
        assertEquals(290f, pane.paneW!!, eps)
        // Чуть больше половины — колонка остаётся, но не уже минимума.
        assertEquals(DelaColumns.SIDE_MIN, DelaColumns.dropSide(w, s, DelaColumns.SIDE_MIN / 2f + 1f).sideW!!, eps)
        // Под пальцем ширина идёт до нуля — видно, что колонка уходит.
        assertEquals(0f, DelaColumns.dragSide(w, s, -30f).side, eps)
    }

    @Test
    fun `спрятанную тянут из дока — выезжает той ширины, до какой дотянули`() {
        val s = State(sideOn = false, paneOn = false)
        val side = DelaColumns.dropSide(w, s, 220f)
        assertTrue(side.sideOn)
        assertEquals(220f, side.sideW!!, eps)
        val pane = DelaColumns.dropPane(w, s, 360f)
        assertTrue(pane.paneOn)
        assertEquals(360f, pane.paneW!!, eps)
        // Без соседа колонке можно всю долю, что не списка.
        assertEquals(room, DelaColumns.maxPane(w, sideOn = false), eps)
    }

    @Test
    fun `открыть колонку обратно — та же ширина, но список не меньше своей доли`() {
        // Карточку растянули при спрятанной боковине; боковину показали — карточка уступает, не список.
        val s = State(sideOn = false, paneW = 470f)
        val f = DelaColumns.fit(w, s.copy(sideOn = true))
        assertEquals(172f, f.side, eps)
        assertEquals(room - 172f, f.pane, eps)
        assertEquals(f.side, DelaColumns.openSide(w, s), eps)
        assertEquals(f.pane, DelaColumns.fit(w, s.copy(sideOn = true)).pane, eps)
    }

    @Test
    fun `узкий экран — минимумы важнее доли списка`() {
        val f = DelaColumns.fit(600f, State(sideW = 300f, paneW = 400f))
        assertEquals(DelaColumns.SIDE_MIN, f.side, eps)
        assertEquals(DelaColumns.PANE_MIN, f.pane, eps)
    }
}
