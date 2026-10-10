package ru.zf.pravka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.zf.pravka.core.DayAssembler
import ru.zf.pravka.core.DayAssembler.DayItem
import ru.zf.pravka.core.Fmt

// Витрина набора Правки 4.0 (TASK этап 2): все детали во всех состояниях для
// каждого режима — один экран, чтобы сверить материал с `screens/` глазами, а
// не по одному экрану режима. Вход — «Ещё → Служебное → Витрина», только в
// debug-сборке. Данные выдуманы и живут только здесь.

private val SHOW_MODES = listOf(
    ModeDecor.TODAY to "Сегодня",
    ModeDecor.ZASECHKA to "Засечка",
    ModeDecor.DELA to "Дела",
    ModeDecor.SPORT to "Спорт",
    ModeDecor.FOOD to "Еда",
    ModeDecor.MONEY to "Деньги",
    ModeDecor.PRAVKA to "Правка",
)

/** Экран «Витрина»: переключатель режима сверху и все детали ниже. */
@Composable
fun ShowcaseScreen(onBack: () -> Unit) {
    var pick by remember { mutableIntStateOf(0) }
    val decor = SHOW_MODES[pick].first
    ModeFrame(decor) {
        Column(Modifier.fillMaxSize()) {
            TabHeader(title = "Витрина", onBack = onBack, titleExtra = SHOW_MODES[pick].second)
            Segmented(SHOW_MODES.map { it.second }, pick, { pick = it }, Modifier.padding(horizontal = 16.dp))
            Column(
                Modifier.fillMaxSize().fadingScroll().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShowSurfaces(decor)
                ShowHeaders(decor)
                ShowTiles()
                ShowSayBar(decor)
                ShowTimeline()
                ShowZasechka()
                ShowParts()
                SayBarSpace()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShowSurfaces(decor: ModeDecor) {
    SectionHeader("Монеты и клавиши", trailing = "20 · 24 · 30 · 34")
    PaperCard {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (d in listOf(ModeDecor.PRAVKA, ModeDecor.ZASECHKA, ModeDecor.DELA, ModeDecor.SPORT, ModeDecor.FOOD, ModeDecor.MONEY)) {
                for (s in listOf(20, 24, 30, 34)) Coin(d, s.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Key(Glyphs.Mic, "голос", {}, size = 44.dp)
            Key(Glyphs.Mic, "голос", {}, size = 50.dp)
            Key(Glyphs.Check, "принять", {}, size = 52.dp)
            Key(Glyphs.Mic, "голос", {}, size = 56.dp, enabled = false)
            Key(Glyphs.Mic, "голос", {}, size = 50.dp, cream = true)
        }
    }
}

@Composable
private fun ShowHeaders(decor: ModeDecor) {
    SectionHeader("Шапки")
    ModeHeader("Дела", ModeDecor.DELA, onBack = {}, subtitle = "Все сферы", onSubtitle = {}, actions = {
        HeaderIcon(Glyphs.Stats, "статистика", {})
        HeaderIcon(Glyphs.Dollar, "стоимость", {})
        HeaderIcon(Glyphs.Gear, "настройки", {})
    })
    DayHeader("5 октября · сегодня", "Понедельник", "+84", {}, {}, "С", {})
    WeatherRow(
        listOf(
            WeatherCell(Glyphs.Cloud, "утро", "+4°"),
            WeatherCell(Glyphs.Sunny, "день", "+10°"),
            WeatherCell(Glyphs.Rainy, "вечер", "+7°"),
            WeatherCell(Glyphs.Umbrella, "дождь 17–21", "3 мм · 80 %"),
        ),
    )
    DayHeaderCompact("пн, 5 окт", "+7° · дождь с 17", minis = {
        MiniStat("+84", Modes.Zasechka, "Засечка", {})
        MiniStat("0/5", Modes.Dela, "Дела", {})
        MiniStat("1/2", Modes.Sport, "Спорт", {})
        MiniStat("1${Fmt.THIN}545", Modes.Food, "Еда", {}, dim = true)
    })
}

@Composable
private fun ShowTiles() {
    SectionHeader("Плашки режимов", trailing = "обычное · пусто · считаю · нет связи")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeTile(ModeDecor.DELA, Glyphs.Delo, "Дела", "0 из 5", {}, Modifier.weight(1f), progress = 0f)
        ModeTile(ModeDecor.SPORT, Glyphs.Sport, "Спорт", "1 из 2", {}, Modifier.weight(1f), progress = 0.5f)
        ModeTile(ModeDecor.FOOD, Glyphs.Food, "Еда", Fmt.num(1545), {}, Modifier.weight(1f), progress = 0.7f)
        ModeTile(ModeDecor.MONEY, Glyphs.Money, "Деньги", Fmt.num(-3577), {}, Modifier.weight(1f), progress = 0.5f)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeTile(ModeDecor.DELA, Glyphs.Delo, "Дела", "", {}, Modifier.weight(1f), state = TileState.EMPTY)
        ModeTile(ModeDecor.SPORT, Glyphs.Sport, "Спорт", "", {}, Modifier.weight(1f), state = TileState.LOADING)
        ModeTile(ModeDecor.FOOD, Glyphs.Food, "Еда", Fmt.num(1545), {}, Modifier.weight(1f), state = TileState.OFFLINE)
        ModeTile(ModeDecor.MONEY, Glyphs.Money, "Деньги", Fmt.num(-1_074_898), {}, Modifier.weight(1f))
    }
}

@Composable
private fun ShowSayBar(decor: ModeDecor) {
    SectionHeader("Строка «сказать»", trailing = "пусто · текст · слушаю · думает · итог · вопрос · ошибка")
    var text by remember { mutableStateOf("") }
    SayBar(text, { text = it }, "Саша, что у тебя?", {}, onMic = {}, cream = decor == ModeDecor.TODAY, trailing = { SayIcon(Glyphs.Camera, "фото", {}) })
    SayBar("Обед в кафе у дома", {}, "", {}, onMic = {})
    SayBar("", {}, "Саша, говори дела", {}, onMic = {}, listening = true)
    SayBar("", {}, "", {}, onMic = {}, busy = true, busyLabel = "Причёсываю")
    SayBar("", {}, "", {}, onMic = {}, result = SayResult("Записал в ленту · 2") {})
    SayBar("", {}, "", {}, onMic = {}, question = SayQuestion("Обед с 13:25 — всё ещё обедаешь?", {}, {}))
    SayBar("", {}, "Спроси про деньги", {}, onMic = {}, error = "HTTP 529: overloaded_error — Anthropic перегружен, повтори через минуту")
    ModeFrame(ModeDecor.ZASECHKA) {
        SayBar(
            "", {}, "Чем занят дальше?", {}, onMic = {},
            above = { SayAbove("Ужин с семьёй · с 18:30", "21 м") },
            leading = { StopKey({}) },
        )
    }
    ModeFrame(ModeDecor.FOOD) {
        SayBar("", {}, "Саша, что съел?", {}, onMic = {}, trailing = {
            SayIcon(Glyphs.Camera, "камера", {})
            SayIcon(Glyphs.Image, "галерея", {})
            SayIcon(Glyphs.Barcode, "штрихкод", {})
        })
    }
}

/** Выдуманный день для хроники Витрины — пример макета `screens/01`. */
private fun sampleDay(): DayAssembler.Result {
    val day = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    fun at(h: Int, m: Int) = day + (h * 60 + m) * 60_000L
    return DayAssembler.assemble(
        DayAssembler.Input(
            dayStart = day,
            now = at(18, 51),
            entries = listOf(
                DayAssembler.EntryIn(1, day - 20 * 60_000L, day, "Сон", "Сон", 0),
                DayAssembler.EntryIn(2, day, at(7, 5), "Сон", "Сон", 0),
                DayAssembler.EntryIn(3, at(9, 50), at(10, 40), "Созвон: бюджет на IV квартал", "Работа: звонки", 7, "Бета Групп", 5, "Сценарий без кредита — к четвергу"),
                DayAssembler.EntryIn(4, at(10, 40), at(10, 55), "Залип в ленте", "Потери", -5),
                DayAssembler.EntryIn(5, at(17, 45), at(18, 30), "Уроки со старшим", "Семья", 6, useful = 4),
                DayAssembler.EntryIn(6, at(18, 30), 0L, "Ужин с семьёй", "Еда", 1),
            ),
            marks = listOf(
                DayAssembler.MarkIn(at(0, 30), DayAssembler.Source.SPORT, "Готовность: можно тяжёлое · HRV 64"),
                DayAssembler.MarkIn(at(10, 41), DayAssembler.Source.DELA, "Из встречи: Бета — сценарий без кредита · #61"),
                DayAssembler.MarkIn(at(18, 0), DayAssembler.Source.MONEY, "${Fmt.num(-2337)} ₽ · канцелярия к школе"),
            ),
            pending = listOf(DayAssembler.PendingIn(at(18, 40), DayAssembler.Source.FOOD, "Паста, салат · ≈ 720 ккал", "m")),
            calendar = listOf(DayAssembler.CalendarIn(at(20, 0), at(20, 30), "Родительское собрание 3 «Б»", "Zoom")),
            workouts = listOf(DayAssembler.WorkoutIn(at(20, 30), 45, "Силовая А — гиря 16 кг: ноги и задняя цепь", "можно тяжёлое")),
            tasks = listOf(
                DayAssembler.TaskIn("61", "Бета: финмодель — сценарий без кредита", "#61 · Бета Групп", 45),
                DayAssembler.TaskIn("62", "Бета: пересчитать ковенанты", "до 7 окт", 30),
                DayAssembler.TaskIn("55", "Орион: КП на управленческий учёт", "#55 · Орион Логистик", 90),
            ),
        ),
    )
}

@Composable
private fun ShowTimeline() {
    SectionHeader("Хроника", trailing = "все виды строк")
    val r = remember { sampleDay() }
    ModeFrame(ModeDecor.TODAY) {
        Column(Modifier.fillMaxWidth().padding(start = 0.dp, end = 0.dp)) {
            TimelineItems(r.items, now = r.items.filterIsInstance<DayItem.Now>().firstOrNull()?.at ?: 0L)
        }
    }
}

@Composable
private fun ShowZasechka() {
    SectionHeader("Засечка", trailing = "циферблат · плашка времени · лента · итоги")
    ModeFrame(ModeDecor.ZASECHKA) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Dial(
                    sectors = listOf(
                        DialSector(1, 0f, 425f, 0, ZGroup.SLEEP.fill, false),
                        DialSector(2, 425f, 450f, 8, ZGroup.SPORT.fill, false),
                        DialSector(3, 450f, 495f, 6, ZGroup.FAMILY.fill, false),
                        DialSector(4, 590f, 640f, 7, ZGroup.WORK.fill, false),
                        DialSector(5, 640f, 655f, -5, ZGroup.LOSS.fill, false, hatched = true),
                        DialSector(6, 655f, 760f, 10, ZGroup.WORK.fill, false),
                        DialSector(7, 1110f, 1131f, 1, ZGroup.LIFE.fill, true),
                    ),
                    nowMin = 1131f,
                    score = "+84",
                    compare = { DialCompare(13, "пн, 28 сент") },
                    place = "3-й из 28 дней",
                )
            }
            TimePlate(
                big = "11 ч 46 м", sub = "с подъёма в 07:05 · сон 7 ч 25 м", right = "4 ч 40 м", rightSub = "до сна в 23:30",
                parts = listOf(BarPart(15f, ZGroup.LOSS), BarPart(385f, ZGroup.WORK), BarPart(70f, ZGroup.SPORT), BarPart(90f, ZGroup.FAMILY), BarPart(146f, ZGroup.LIFE), BarPart(280f, ZGroup.AHEAD)),
                legend = listOf(
                    Triple(ZGroup.WORK, "работа", "6 ч 25 м"), Triple(ZGroup.SPORT, "спорт", "1 ч 10 м"),
                    Triple(ZGroup.FAMILY, "семья", "1 ч 30 м"), Triple(ZGroup.LIFE, "быт", "2 ч 26 м"),
                    Triple(ZGroup.LOSS, "потери", "15 м"), Triple(ZGroup.AHEAD, "впереди", "4 ч 40 м"),
                ),
                nav = { DayNav("Сегодня", {}, null, subtitle = "понедельник, 5 октября") },
            )
            PaperCard(label = "итоги") {
                CategoryRow("Работа: текущая", 10, 105, "15 %", 18, 1f)
                CategoryRow("Потери", -5, 15, "2 %", -1, 0.14f)
                Text("ещё 6 без записей", style = LocalPravkaType.current.meta, color = LocalMode.current.meta)
            }
        }
    }
}

@Composable
private fun ShowParts() {
    SectionHeader("Детали", trailing = "сегменты · клавиши · состояния")
    var seg by remember { mutableIntStateOf(0) }
    Segmented(listOf("Утро 5", "Предстоящее 12", "Новое 4", "Жду 6"), seg, { seg = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostKey("Начать в Засечке", {}, icon = Glyphs.Play)
        PrimaryKey("Сделано", {}, icon = Glyphs.Check)
    }
    PaperCard(label = "переключатели") {
        var on by remember { mutableStateOf(true) }
        PaperToggle("Отметки еды на «Сегодня»", on, { on = it }, info = "Пояснение живёт за «i».")
        PaperToggle("Выключено", false, {})
        var v by remember { mutableFloatStateOf(0.4f) }
        PaperSlider("Время сна", "23:30", v, { v = it }, {}, 0f..1f)
    }
    PaperCard {
        Row { IconLabel(Glyphs.Edit, "Поправить", {}); IconLabel(Glyphs.Note, "Заметка", {}); IconLabel(Glyphs.Delete, "Удалить", {}) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("HRV", "64 мс", Modifier.weight(1f), delta = "▲ 6 к норме")
        StatTile("Сон", "7 ч 25 м", Modifier.weight(1f), delta = "▲ 20 м")
        StatTile("Пульс покоя", "52", Modifier.weight(1f), delta = "▼ 2", worse = true)
    }
    CoachNote("Темп 3-1-1, отдых 90 с. В последнем подходе оставь 1–2 повтора в запасе.")
    PaperCard { ExerciseRow("Гоблет-присед", "прошлый раз 4×6 @16 кг · ▲ +2 повтора", "4×8 @16") }
    AskChips(listOf("Куда ушло больше обычного?", "Хватит до зарплаты?"), {})
    EmptyState("Записей за этот день нет", icon = Glyphs.Calendar)
    LoadingLine()
    ThinkingLine("Причёсываю")
    ErrorPlate("HTTP 401: invalid x-api-key — ключ Anthropic не подошёл", onRetry = {})
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OfflineChip(); InfoDot("Пояснение", "Что это и откуда.") }
    PaperCard { DayNav("Сегодня", {}, null, subtitle = "понедельник, 5 октября") }
}

// ---------------------------------------------------------------------------
// @Preview — каждая группа деталей отдельно (411 dp, сложенный телефон)
// ---------------------------------------------------------------------------

@Composable
private fun PreviewBox(decor: ModeDecor = ModeDecor.TODAY, content: @Composable () -> Unit) {
    PravkaTheme {
        ModeFrame(decor) {
            Column(Modifier.background(Ink.Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@Preview(widthDp = 411, heightDp = 900, backgroundColor = 0xFF100F0D, showBackground = true)
@Composable
private fun PreviewShowcase() = PravkaTheme { ShowcaseScreen {} }

@Preview(widthDp = 411, backgroundColor = 0xFF100F0D, showBackground = true)
@Composable
private fun PreviewSurfaces() = PreviewBox(ModeDecor.DELA) { ShowSurfaces(ModeDecor.DELA) }

@Preview(widthDp = 411, backgroundColor = 0xFF100F0D, showBackground = true)
@Composable
private fun PreviewHeaders() = PreviewBox { ShowHeaders(ModeDecor.TODAY) }

@Preview(widthDp = 411, backgroundColor = 0xFF100F0D, showBackground = true)
@Composable
private fun PreviewTiles() = PreviewBox { ShowTiles() }

@Preview(widthDp = 411, backgroundColor = 0xFF100F0D, showBackground = true)
@Composable
private fun PreviewSayBar() = PreviewBox { ShowSayBar(ModeDecor.TODAY) }

@Preview(widthDp = 411, heightDp = 1400, backgroundColor = 0xFF100F0D, showBackground = true)
@Composable
private fun PreviewTimeline() = PreviewBox { ShowTimeline() }

@Preview(widthDp = 411, heightDp = 1200, backgroundColor = 0xFF100F0D, showBackground = true)
@Composable
private fun PreviewZasechka() = PreviewBox(ModeDecor.ZASECHKA) { ShowZasechka() }

@Preview(widthDp = 411, heightDp = 1400, backgroundColor = 0xFF100F0D, showBackground = true)
@Composable
private fun PreviewParts() = PreviewBox(ModeDecor.SPORT) { ShowParts() }
