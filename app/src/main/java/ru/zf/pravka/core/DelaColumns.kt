package ru.zf.pravka.core

import kotlin.math.max
import kotlin.math.min

/**
 * Три колонки разложенных Дел (14.10.2026, владелец: «Давай сделаем так, чтобы все три колонки можно было
 * двигать… Я работаю со списками, средней колонкой, поэтому справа и слева должен иметь возможность эти колонки
 * убирать. Механика одинаковая»): боковина слева, список посередине, карточка дела справа. Боковину и карточку
 * прячут и тянут их язычки (`ColumnGrip`), список — главный и забирает всё, что они отдали.
 *
 * Здесь только счёт — сколько кому места и куда встаёт отпущенный язычок; рисует `DelaTab`. Числа — в dp.
 * Хранится в `dela-favs.json` рядом с прежним ключом боковины (`ui:side`): устройство помнит выбор, как и вид дерева.
 */
object DelaColumns {
    /** Спрятана ли боковина: «0» — спрятана (ключ 13.10.2026, прежний — читается как был). */
    const val SIDE_KEY = "ui:side"
    const val SIDE_W_KEY = "ui:sideW"
    const val PANE_KEY = "ui:pane"
    const val PANE_W_KEY = "ui:paneW"

    /** Боковина с завода — как на холсте («Боковина Дел»). */
    const val SIDE_DEF = 172f
    /** Уже — разделы и фильтры не читаются даже в две строки. */
    const val SIDE_MIN = 140f
    /** Уже — у карточки ломаются ряд действий и свойства строками. */
    const val PANE_MIN = 280f
    /** Списку — не меньше этой доли ширины: ради него колонки и двигают. */
    const val MID_SHARE = 0.4f
    /** Шаг «шире / уже» для чтения с экрана (действия язычка без пальца). */
    const val STEP = 24f

    /** Что помнит устройство. Ширина null — не тянули: боковина 172, карточка — половина того, что осталось. */
    data class State(val sideOn: Boolean = true, val sideW: Float? = null, val paneOn: Boolean = true, val paneW: Float? = null)

    /** Сколько колонке места сейчас: спрятанной — 0. */
    data class Fit(val side: Float, val pane: Float)

    fun read(prefs: Map<String, String>): State = State(
        sideOn = prefs[SIDE_KEY] != "0",
        sideW = prefs[SIDE_W_KEY]?.toFloatOrNull()?.takeIf { it > 0f },
        paneOn = prefs[PANE_KEY] != "0",
        paneW = prefs[PANE_W_KEY]?.toFloatOrNull()?.takeIf { it > 0f },
    )

    /** Для `DelaStore.setGroups`: одной записью файла. Ширину не тянули — ключа нет (заводская считается сама). */
    fun write(s: State): Map<String, String> = buildMap {
        put(SIDE_KEY, if (s.sideOn) "1" else "0")
        put(PANE_KEY, if (s.paneOn) "1" else "0")
        s.sideW?.let { put(SIDE_W_KEY, Math.round(it).toString()) }
        s.paneW?.let { put(PANE_W_KEY, Math.round(it).toString()) }
    }

    /** Сколько боковине и карточке вместе: остальное — списку. */
    private fun room(total: Float) = total * (1f - MID_SHARE)

    private fun sideWant(s: State) = max(SIDE_MIN, s.sideW ?: SIDE_DEF)

    /**
     * Ширины колонок при ширине экрана [total]. Тянул владелец — его ширина, но списку остаётся его доля: не
     * влезает — сначала уступает карточка (до своего минимума; боковину сузили — она снова шире), потом боковина.
     * Экран совсем узкий, а открыты обе — минимумы важнее доли списка: иначе колонку не прочесть.
     */
    fun fit(total: Float, s: State): Fit {
        val room = room(total)
        var side = if (s.sideOn) sideWant(s) else 0f
        var pane = if (s.paneOn) max(PANE_MIN, s.paneW ?: ((total - side) / 2f)) else 0f
        if (s.paneOn) pane = max(PANE_MIN, min(pane, room - side))
        if (s.sideOn) side = max(SIDE_MIN, min(side, room - pane))
        return Fit(side, pane)
    }

    /** Какой станет колонка, если её показать: ширина содержимого, пока колонка уезжает или выезжает. */
    fun openSide(total: Float, s: State): Float = fit(total, s.copy(sideOn = true)).side
    fun openPane(total: Float, s: State): Float = fit(total, s.copy(paneOn = true)).pane

    /** Дальше этого язычок не тянется: соседу — его минимум, списку — его доля. */
    fun maxSide(total: Float, paneOn: Boolean): Float = max(SIDE_MIN, room(total) - if (paneOn) PANE_MIN else 0f)
    fun maxPane(total: Float, sideOn: Boolean): Float = max(PANE_MIN, room(total) - if (sideOn) SIDE_MIN else 0f)

    /**
     * Тянут боковину ([raw] — ширина под пальцем): она — от нуля (уходит в док) до предела, карточка уступает
     * место, как в [fit], и снова шире, когда боковину сужают.
     */
    fun dragSide(total: Float, s: State, raw: Float): Fit {
        val side = raw.coerceIn(0f, maxSide(total, s.paneOn))
        val pane = if (s.paneOn) fit(total, s.copy(sideOn = true, sideW = max(side, SIDE_MIN))).pane else 0f
        return Fit(side, pane)
    }

    /** Тянут карточку: теперь уступает боковина (до своего минимума) — иначе карточку было бы не расширить. */
    fun dragPane(total: Float, s: State, raw: Float): Fit {
        val pane = raw.coerceIn(0f, maxPane(total, s.sideOn))
        val side = if (s.sideOn) max(SIDE_MIN, min(sideWant(s), room(total) - max(pane, PANE_MIN))) else 0f
        return Fit(side, pane)
    }

    /**
     * Язычок боковины отпустили на [raw]. Меньше половины минимума — она уходит в док, а ширина остаётся прежней
     * (выедет такой, какой была); иначе — новая ширина в пределах. Тянули спрятанную — она выехала.
     */
    fun dropSide(total: Float, s: State, raw: Float): State =
        if (raw < SIDE_MIN / 2f) s.copy(sideOn = false)
        else s.copy(sideOn = true, sideW = raw.coerceIn(SIDE_MIN, maxSide(total, s.paneOn)))

    /** То же у карточки; боковина, уступившая ей место, так и остаётся уже (как её видели, когда отпускали). */
    fun dropPane(total: Float, s: State, raw: Float): State {
        if (raw < PANE_MIN / 2f) return s.copy(paneOn = false)
        val pane = raw.coerceIn(PANE_MIN, maxPane(total, s.sideOn))
        val side = dragPane(total, s, pane).side
        val squeezed = s.sideOn && side < sideWant(s)
        return s.copy(paneOn = true, paneW = pane, sideW = if (squeezed) side else s.sideW)
    }
}
