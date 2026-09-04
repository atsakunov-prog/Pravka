package ru.zf.pravka.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zf.pravka.data.ExerciseBook
import ru.zf.pravka.data.PlanStore

// Разбор описаний событий intervals — на НАСТОЯЩИХ текстах из календаря
// владельца (план v3, сентябрь 2026) и настоящем справочнике из assets.
// Каждый случай здесь — то, что вкладка «Тело» раньше показывала неверно.
class PlanLineTest {

    private val book = ExerciseBook(null).apply {
        loadFromJson(File("src/main/assets/exercises.json").readText())
    }

    private fun day(
        name: String,
        description: String,
        type: String = "WeightTraining",
        tags: List<String> = emptyList(),
        time: String = "",
    ) = PlanStore.PlanDay(
        eventId = "1", date = "2026-09-04", name = name, type = type, minutes = 14, load = 0,
        description = description, time = time, tags = tags,
    )

    // ---- Зарядка · 6 пунктов (4.09): список по строкам + хвост после списка ----

    private val zaryadka6 = """
        1. Суставы сверху вниз ~3 мин (корпус: кошка-корова ×6)
        2. Осанка: chin tuck ×10 · скольжения по стене ×10 · грудь в проёме 30 сек
        3. Вис ×2 до предела, отдых 1 мин — секунды в заметку
        4. Подтягивания с резинкой ×3, с запасом
        5. Отжимания 2×6
        6. Подъёмы коленей на турнике ×10–12
        Минимум на плохое утро: 1 + 3 + шесть отжиманий. Сегодня первый день нового списка — скольжения по стене будут неловкими, это норма.
    """.trimIndent()

    @Test
    fun `zaryadka with six numbered lines parses all six and keeps the trailing note`() {
        val d = day("Зарядка · 6 пунктов", zaryadka6, tags = listOf("v3", "зарядка"), time = "07:30")
        assertTrue(d.charger)
        assertEquals(6, d.plannedLines().size)
        assertEquals("", d.noteBefore())
        assertTrue(d.noteAfter().startsWith("Минимум на плохое утро"))

        val lines = PlanLine.parseAll(d.plannedLines(), book, suffix = "-z")
        assertEquals(
            listOf(
                "sustavy-sverhu-vniz",
                "osanka-podborodok-nazad-skolzheniya-po-stene-gru",
                "vis-na-turnike",
                "podtyagivaniya-s-rezinkoy",
                "otzhimaniya",
                "podemy-koleney-na-turnike",
            ),
            lines.map { it.id },
        )
        assertEquals("~3 мин (корпус: кошка-корова ×6)", lines[0].dose)
        assertEquals("chin tuck ×10 · скольжения по стене ×10 · грудь в проёме 30 сек", lines[1].dose)
        assertEquals("×2 до предела", lines[2].dose)
        assertEquals("отдых 1 мин; секунды в заметку", lines[2].note)
        assertEquals("×3", lines[3].dose)
        assertEquals("с запасом", lines[3].note)
        assertEquals("2×6", lines[4].dose)
        assertEquals("×10–12", lines[5].dose)
        assertEquals("Вис на турнике", lines[2].canonical)
    }

    // ---- Зарядка · дача (12.09 и 13.09): список В ОДНУ СТРОКУ ----

    @Test
    fun `inline numbered list is a list, prefix and tail become notes`() {
        val d = day(
            "Зарядка · дача",
            "Дача. 1. Суставы. 2. Осанка: chin tuck ×10 · стена ×10 · проём 30 сек. " +
                "3. Тяга резинки к поясу 2×10–12. 4. Отжимания 2×6. 5. Скручивания ×15 с паузой. Потом прогулка.",
            tags = listOf("v3", "зарядка"),
        )
        assertEquals(
            listOf(
                "Суставы",
                "Осанка: chin tuck ×10 · стена ×10 · проём 30 сек",
                "Тяга резинки к поясу 2×10–12",
                "Отжимания 2×6",
                "Скручивания ×15 с паузой",
            ),
            d.plannedLines(),
        )
        assertEquals("Дача.", d.noteBefore())
        assertEquals("Потом прогулка.", d.noteAfter())

        val lines = PlanLine.parseAll(d.plannedLines(), book, suffix = "-z")
        assertEquals(
            listOf(
                "sustavy-sverhu-vniz",
                "osanka-podborodok-nazad-skolzheniya-po-stene-gru",
                "tyaga-rezinki-k-poyasu",
                "otzhimaniya",
                "skruchivaniya-s-pauzoy",
            ),
            lines.map { it.id },
        )
        assertEquals("2×10–12", lines[2].dose)
        assertEquals("×15 с паузой", lines[4].dose)
    }

    @Test
    fun `inline list without tail keeps the last item clean`() {
        val d = day(
            "Зарядка · 6 пунктов",
            "1. Суставы ~3 мин. 2. Осанка: chin tuck ×10 · стена ×10 · проём 30 сек. 3. Вис ×2 — секунды в заметку. " +
                "4. Резинка ×3. 5. Отжимания 2×6 (стало легко — 2×8). 6. Колени ×10–12.",
            tags = listOf("v3", "зарядка"),
        )
        assertEquals(6, d.plannedLines().size)
        assertEquals("", d.noteAfter())
        val lines = PlanLine.parseAll(d.plannedLines(), book, suffix = "-z")
        assertEquals("sustavy-sverhu-vniz", lines[0].id)
        assertEquals("~3 мин", lines[0].dose)
        assertEquals("vis-na-turnike", lines[2].id)
        assertEquals("×2", lines[2].dose)
        assertEquals("секунды в заметку", lines[2].note)
        // «Резинка ×3» в зарядке — подтягивания с резинкой, не тяга к поясу.
        assertEquals("podtyagivaniya-s-rezinkoy", lines[3].id)
        // Тире внутри скобок строку не режет.
        assertEquals("otzhimaniya", lines[4].id)
        assertEquals("2×6 (стало легко — 2×8)", lines[4].dose)
        assertEquals("podemy-koleney-na-turnike", lines[5].id)
    }

    // ---- Гиря №0: комментарий до списка, пояснения через «: », хвост после ----

    private val girya0 = """
        Знакомство с гирёй — не раньше чем через 40 мин после подъёма, после зарядки и завтрака. Сначала по одному видео на каждое движение (запросы в карточках Notion), потом с гирёй в руках:

        1. 10 воздушных приседаний.
        2. Гоблет-присед 2×6: гиря за рога у груди, локти вниз, 3 сек вниз, пятки в пол, внизу локти касаются коленей.
        3. Тяга в наклоне одной рукой 2×6 на руку: свободная рука на стул, спина прямая, тяни локтем к поясу, лопатку к позвоночнику, медленно вниз.
        4. Румынская тяга двумя руками 2×8: колени чуть согнуты и такими остаются, таз назад (закрываешь ягодицами дверцу машины), гиря скользит вдоль ног, спина прямая, взгляд в пол на 2 м вперёд.

        Отдых минута. Задача дня — не устать, а понять, как гиря лежит в руках и где шарнир. Ощущения — в заметку активности.
    """.trimIndent()

    @Test
    fun `kettlebell session splits name, dose and explanation`() {
        val d = day(
            "Гиря №0 — знакомство: гоблет 2×6 · тяга 2×6 на руку · румынская 2×8",
            girya0, tags = listOf("v3", "гиря"), time = "09:00",
        )
        assertFalse(d.charger)
        assertEquals("Гиря №0", d.shortName)
        assertTrue(d.noteBefore().startsWith("Знакомство с гирёй"))
        assertTrue(d.noteAfter().startsWith("Отдых минута."))
        val lines = PlanLine.parseAll(d.plannedLines(), book)
        assertEquals(4, lines.size)
        assertEquals("prisedaniya-s-pauzoy-vozdushnye", lines[0].id)
        assertEquals("×10", lines[0].dose)
        assertEquals("goblet-prised-s-girey", lines[1].id)
        assertEquals("2×6", lines[1].dose)
        assertTrue(lines[1].note.startsWith("гиря за рога у груди"))
        assertEquals("tyaga-giri-v-naklone-odnoy-rukoy", lines[2].id)
        assertEquals("2×6 на руку", lines[2].dose)
        assertEquals("rumynskaya-tyaga-rdl-s-girey", lines[3].id)
        assertEquals("2×8", lines[3].dose)
    }

    // ---- Турник + пресс №0: это СИЛОВАЯ, не зарядка ----

    private val turnik0 = """
        Сразу после гири, спина тёплая. Знакомство — половинный объём.
        1. Подтягивания с резинкой 3×4, обратный хват (легче), отдых 90 сек. Из полного виса, начинай с лопаток, подбородок над перекладиной, медленно вниз.
        2. Лопаточные подтягивания 2×5: вис на прямых руках, не сгибая локтей потяни лопатки вниз-назад, корпус приподнимется на 5–10 см. Негативов нет, пока вис <30 сек.
        3. Подъёмы коленей на турнике 2×10 медленно.
        4. Dead bug 2×10, поясница прижата.
        Повторы и секунды виса — в заметку.
    """.trimIndent()

    @Test
    fun `pull-up bar session is strength, not the morning charger`() {
        val d = day(
            "Турник + пресс №0 — резинка 3×4 · лопаточные 2×5 · колени 2×10 · жук 2×10",
            turnik0, tags = listOf("v3", "турник"), time = "09:20",
        )
        assertFalse("«Турник + пресс» — силовая со своим блоком, не зарядка", d.charger)
        assertEquals("Турник", d.block)
        assertEquals("Турник + пресс №0", d.shortName)
        val lines = PlanLine.parseAll(d.plannedLines(), book)
        assertEquals(
            listOf("podtyagivaniya-s-rezinkoy", "lopatochnye-podtyagivaniya", "podemy-koleney-na-turnike", "dead-bug-zhuk"),
            lines.map { it.id },
        )
        assertEquals("3×4", lines[0].dose)
        assertTrue(lines[0].note.startsWith("обратный хват (легче), отдых 90 сек"))
        assertEquals("2×10 медленно", lines[2].dose)
        assertEquals("2×10", lines[3].dose)
        assertEquals("поясница прижата", lines[3].note)
        assertEquals("Повторы и секунды виса — в заметку.", d.noteAfter())
    }

    @Test
    fun `charger is recognised by tag or by the word in the name`() {
        assertTrue(day("Зарядка · дача", "", tags = listOf("v3", "зарядка")).charger)
        assertTrue(day("Утренняя зарядка", "").charger)
        assertFalse(day("Гиря №1 — уроки", "").charger)
        assertFalse(day("Турник/GTG", "").charger)
        assertFalse(day("Зарядка", "", type = "Run").charger)
    }

    // ---- Структура Garmin — не текст плана ----

    @Test
    fun `garmin structure lines never reach notes or items`() {
        val d = day(
            "LTHR-тест — 30 мин",
            "Протокол Фрила: 30 минут ровным гоночным усилием.\n\n" +
                "1. Условие запуска: спина молчала 4–5 дней подряд — на беге и в быту\n" +
                "2. Нагрудный ремень — обязателен — оптика на таком пульсе врёт\n\n" +
                "Строки ниже с пометкой intensity= — машинная структура для Garmin, не чек-лист.\n\n" +
                "Warmup\n- 10m Z1 HR intensity=warmup\n\nMain Set 1x\n- 30m 100% LTHR intensity=interval\n\nCooldown\n- 5m Z1 HR intensity=cooldown",
            type = "Run",
        )
        assertEquals(2, d.plannedLines().size)
        assertEquals("Протокол Фрила: 30 минут ровным гоночным усилием.", d.noteBefore())
        assertEquals("", d.noteAfter())
        val cue = PlanLine.parse(0, d.plannedLines()[0], book)
        assertEquals("Условие запуска", cue.name)
        assertEquals("", cue.dose)
        assertTrue(cue.note.startsWith("спина молчала"))
    }

    // ---- Прежний формат «Название — доза — пояснение» и сегмент с дозой ----

    @Test
    fun `legacy dash format and dose-in-second-segment both resolve`() {
        val legacy = PlanLine.parse(0, "Гоблет-присед — 3×8 — легко", book)
        assertEquals("goblet-prised-s-girey", legacy.id)
        assertEquals("3×8", legacy.dose)
        assertEquals("легко", legacy.note)

        val swapped = PlanLine.parse(2, "Вместо виса и резинки — тяга резинки к поясу 2×10–12", book)
        assertEquals("tyaga-rezinki-k-poyasu", swapped.id)
        assertEquals("2×10–12", swapped.dose)
        assertEquals("Вместо виса и резинки", swapped.note)

        val free = PlanLine.parse(3, "Bird dog 2×6 на сторону", book)
        assertTrue(free.id.startsWith("task-3-"))
        assertEquals("Bird dog", free.name)
        assertEquals("2×6 на сторону", free.dose)
    }

    @Test
    fun `slug matches the python generator`() {
        assertEquals("dead-bug-zhuk", ExerciseBook.slug("Dead bug («жук»)"))
        assertEquals("y-t-w-lezha-na-zhivote", ExerciseBook.slug("Y-T-W лёжа на животе"))
        assertEquals("ruki-superset-sgibaniya-giri-uzkie-otzhimaniya", ExerciseBook.slug("Руки-суперсет: сгибания гири + узкие отжимания"))
        assertEquals("osanka-podborodok-nazad-skolzheniya-po-stene-gru", ExerciseBook.slug("Осанка: подбородок назад · скольжения по стене · грудь в проёме"))
        for (e in book.all) assertEquals(e.name, e.id, ExerciseBook.slug(e.name))
        assertNotNull(book.byId("sustavy-sverhu-vniz"))
    }
}
