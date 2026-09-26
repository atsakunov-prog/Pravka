package ru.zf.pravka

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.PaceSeed
import ru.zf.pravka.core.PaceTune
import ru.zf.pravka.data.ModelChoice
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.Models
import ru.zf.pravka.data.PaceStore
import ru.zf.pravka.data.Settings
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.SummaryLine

// Группа «Модели» в настройках: какая модель и с каким усилием работает на
// каждой дороге в Claude. Дороги перечислены в ModelRoute — по режимам, с
// заводскими значениями, которые раньше были зашиты в код. Владелец: «сделай
// в настройках выбор моделей и выбор усилий, прямо отдельной графой».
//
// Чипы, а не выпадающие списки: варианта три и шесть, они видны разом и
// переключаются одним тапом — но только у раскрытой дороги; остальные
// свёрнуты в строку «Опус 5.5 · medium» (24.09.2026). Ряд чипов едет вбок,
// а не переносится: на узком внешнем экране Fold так не прыгает высота.

@Composable
internal fun ModelsSettings(app: PravkaApp) {
    // Область жизни приложения, а не композиции: тап по чипу и уход с экрана
    // не должны рвать запись в DataStore на полпути (см. AnthropicSettings).
    val scope = app.appScope
    val settings = app.settings
    // Раскрыта одна дорога за раз (24.09.2026): семнадцать дорог по девять
    // чипов — это 153 чипа на одном экране, и искать среди них свою было
    // труднее, чем выбирать. Теперь каждая дорога — строка-сводка.
    var open by remember { mutableStateOf<ModelRoute?>(null) }
    // Калибровка кнопкой меняет прямые — всё, что показывает секунды, перечитывается.
    var round by remember { mutableStateOf(0) }

    SecondsCard(app, round) { round++ }

    val byMode = ModelRoute.entries.groupBy { it.mode }
    byMode.entries.forEachIndexed { index, (mode, routes) ->
        PaperCard(
            label = "${mode.lowercase()} · ${routes.size}",
            info = if (index == 0) MODELS_INFO else null,
        ) {
            routes.forEachIndexed { i, route ->
                if (i > 0) RowRule()
                RouteRow(app, settings, scope, route, expanded = open == route, round = round) {
                    open = if (open == route) null else route
                }
            }
        }
    }
}

// ---- Секунды на кнопке (владелец, 26.09.2026: «выведи эту статистику в
// настройки/модели? И примеры. И не забудь про остальное: засечка, тело,
// дело, еда, деньги. Там тоже нужна оценка») ----
//
// У каждой дороги — сколько она ждёт ответа на короткой, средней и длинной
// фразе, откуда это число (свои замеры, та же дорога на другой модели через
// взаимные коэффициенты, заводская прикидка), промах и последние настоящие
// запросы «обещал — пришло». И что будет, если тапнуть другой чип: секунды
// соседних моделей и усилий — решать с открытыми глазами, как с ценой.

/** Короткая фраза, средняя (медиана у владельца — 134 знака) и длинная (90% короче). */
private val EXAMPLE_LENGTHS = listOf(40, 140, 600)
private const val TYPICAL_INDEX = 1

/** Дороги, что ходят батчем по ночам: окна и отсчёта на кнопке у них нет. */
private val BATCH_ROUTES = setOf(
    ModelRoute.NIGHT_REVIEW, ModelRoute.NIGHT_CHECK, ModelRoute.SHADOW_JUDGE,
    ModelRoute.PROMPT_TUNE, ModelRoute.PATTERNS_DUPES,
)

private val RU: Locale = Locale.forLanguageTag("ru")

private fun sec(ms: Number): String = String.format(RU, "%.1f", ms.toDouble() / 1000.0)

private fun examplesLine(v: PaceStore.View): String =
    v.examples.joinToString(" · ") { (chars, ms) -> "$chars зн. — ${sec(ms)}" } + " с"

private fun modelName(model: String): String =
    if (model == Settings.MODEL_OPUS_5) "Опус 5" else Models.label(model)

/** Вид запроса словами: «вопрос» у Тела — вопрос тренеру, «ответ» у Денег — ответ на карточку. */
private fun kindTitle(kind: String, photo: Boolean): String {
    val k = when (kind) {
        "" -> "разбор"
        "вопрос" -> "вопросы"
        "ответ" -> "ответ на карточку"
        else -> kind
    }
    return if (photo) "$k со снимком" else k
}

private fun sourceText(v: PaceStore.View): String {
    val miss = v.miss?.let { m ->
        val b = v.bias ?: 0.0
        val side = when {
            kotlin.math.abs(b) < 100.0 -> ""
            b > 0 -> ", ответ позже на ${sec(b)}"
            else -> ", ответ раньше на ${sec(-b)}"
        }
        " · мимо ±${sec(m)} с$side"
    }.orEmpty()
    val line = v.line?.let { l ->
        if (l.straight) "${sec(l.baseMs)} с + ${String.format(RU, "%.1f", l.msPerChar)} мс на знак"
        else "среднее ${sec(l.meanMs)} с"
    }.orEmpty()
    return when (v.source) {
        PaceStore.Source.OWN -> "своя прямая: $line · замеров ${v.own}$miss"
        PaceStore.Source.MIXED ->
            "своих замеров ${v.own} — пока вместе с " +
                (if (v.sibling.isNotBlank()) "прямой «${v.sibling}» через коэффициенты моделей" else "заводской прикидкой") +
                "; к дюжине останется только своё$miss"
        PaceStore.Source.SIBLING ->
            "своих замеров нет — прямая этой же дороги «${v.sibling}», пересчитанная коэффициентами моделей: $line"
        PaceStore.Source.FACTORY ->
            "своих замеров нет — заводская прикидка по журналу правок: $line"
    }
}

/** Сверху экрана: модели между собой, калибровка, выгрузка. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecondsCard(app: PravkaApp, round: Int, onTuned: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    PaperCard(
        label = "секунды на кнопке",
        info = "Сколько ждать ответа, считается по своей истории отдельно на каждую " +
            "тройку «дорога + модель + усилие»: основание плюс цена знака сказанного, " +
            "сверху — добавка, если кэш промпта остыл. Учится на каждом удачном ответе, " +
            "а раз в сутки ночью калибровка прогоняет всю историю двенадцатью способами " +
            "счёта и оставляет лучший. Сменил модель или усилие — дорога начинает с " +
            "прямой себя же на прошлой модели, пересчитанной коэффициентами ниже. «Мимо ±» " +
            "— насколько отсчёт промахивался на последних двух десятках запросов; «позже» " +
            "— ответ приходил после нуля. У каждой дороги ниже — её секунды и примеры.",
    ) {
        Text("Модели между собой — по журналу правок, 1862 чистки:", style = MaterialTheme.typography.bodyMedium)
        val rows = listOf(
            Triple(Settings.MODEL_SONNET, "high", "размышлений нет"),
            Triple(Settings.MODEL_OPUS_5, "high", "усилие менялось"),
            Triple(Settings.MODEL_OPUS, "medium", "думает"),
            Triple(Settings.MODEL_FABLE, "high", "прикидка, своих замеров нет"),
        )
        for ((model, effort, note) in rows) {
            val g = PaceSeed.line(model, effort)
            val ex = EXAMPLE_LENGTHS.joinToString(" · ") { "$it зн. — ${sec(g.baseMs + g.msPerChar * it)}" }
            Text("· ${modelName(model)} $effort ($note): $ex с", style = MaterialTheme.typography.bodySmall)
        }
        PaperHint(
            "Старт у всех около 1,9 с — сеть и первый токен. Дальше генерация: Сонет ≈98 токенов " +
                "в секунду, Опус 5.5 ≈115 — он пишет быстрее, но думает: ≈160 токенов до текста и " +
                "около токена на знак. Поэтому Опус 5.5 медленнее Сонета не в одно число, а в 1,8 " +
                "раза на короткой фразе, в 2 на средней и в 2,5 на длинной; переносятся два " +
                "коэффициента — на основание и на знак. Остальные усилия — множителями от снятого."
        )
        val samples by produceState(0, round) {
            value = withContext(Dispatchers.IO) { app.paceStore.logSize() }
        }
        val (tunedAt, tuned) = remember(round) { app.paceStore.lastTune() }
        if (tunedAt > 0L) {
            val stamp = remember { java.text.SimpleDateFormat("d MMMM, HH:mm", RU) }
            PaperHint("Калибровка — ${stamp.format(java.util.Date(tunedAt))}: ${tuned.firstOrNull().orEmpty().substringAfter("калибровка · ")}")
        } else {
            PaperHint("Калибровки ещё не было: первая пройдёт сама в ближайший тик службы, дальше — раз в сутки ночью.")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperTextButton(
                if (busy) "Считаю…" else "Пересчитать сейчас",
                icon = Glyphs.Refresh,
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val lines = withContext(Dispatchers.IO) {
                            runCatching { app.paceStore.tuneNow(app.historyLog) }
                                .getOrElse { e -> listOf("не посчиталось: ${e.message}") }
                        }
                        runCatching { app.nightLog.add(lines.firstOrNull().orEmpty() + " (кнопкой)") }
                        busy = false
                        onTuned()
                    }
                },
            )
            if (samples > 0) {
                PaperTextButton(
                    "Выгрузить замеры · $samples",
                    icon = Glyphs.Export,
                    onClick = {
                        scope.launch {
                            val intent = withContext(Dispatchers.IO) { runCatching { app.paceStore.shareCsvIntent() } }
                            intent.onSuccess { i ->
                                context.startActivity(
                                    android.content.Intent.createChooser(i, "Замеры Claude")
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.onFailure { e -> ru.zf.pravka.ui.Feedback.toast(context, "Не собралась: ${e.message}") }
                        }
                    },
                )
            }
        }
    }
}

/** Секунды раскрытой дороги: примеры, откуда, промах, последние запросы, соседние чипы. */
@Composable
private fun SecondsBlock(app: PravkaApp, route: ModelRoute, choice: ModelChoice, round: Int) {
    if (route in BATCH_ROUTES) {
        PaperHint("Идёт батчем по ночам — окна и отсчёта на кнопке нет, секунды не считаются.")
        return
    }
    val pace = app.paceStore
    val v = remember(choice, round) {
        pace.view(route.key, "", false, choice.model, choice.effort, EXAMPLE_LENGTHS)
    }
    Text("Секунды: ${examplesLine(v)}", style = MaterialTheme.typography.bodyMedium)
    PaperHint(sourceText(v))
    if (v.coldExtra >= 100.0) PaperHint("Кэш остыл (дорога молчала больше часа) — ещё +${sec(v.coldExtra)} с.")
    if (!v.tune.factory) PaperHint("Калибровка выбрала: ${PaceTune.describe(v.tune)}.")
    // Что будет, если тапнуть другой чип: та же дорога, другая модель или усилие.
    val typical = EXAMPLE_LENGTHS[TYPICAL_INDEX]
    val byModel = remember(choice, round) {
        Models.ALL.joinToString(" · ") { m ->
            val ms = pace.view(route.key, "", false, m, choice.effort, listOf(typical)).examples.first().second
            "${Models.label(m)} ${sec(ms)}"
        }
    }
    val byEffort = remember(choice, round) {
        Models.EFFORTS.filter { it.isNotBlank() }.joinToString(" · ") { e ->
            val ms = pace.view(route.key, "", false, choice.model, e, listOf(typical)).examples.first().second
            "$e ${sec(ms)}"
        }
    }
    PaperHint("На $typical знаках другой моделью: $byModel с.")
    PaperHint("…или другим усилием у ${Models.label(choice.model)}: $byEffort с.")
    val recent by produceState(emptyList<PaceStore.Sample>(), choice, round) {
        value = withContext(Dispatchers.IO) {
            runCatching { pace.recent(route.key, "", false, choice.model, choice.effort) }.getOrDefault(emptyList())
        }
    }
    if (recent.isNotEmpty()) {
        PaperHint(
            "Последние: " + recent.joinToString(" · ") {
                "${it.chars} зн. — обещал ${sec(it.expected)}, пришло ${sec(it.actual)}"
            } + " с."
        )
    }
    // Другие виды той же дороги: вопросы тренеру, ответ на карточку, снимок.
    val kinds = remember(round) { pace.kindsOf(route.key).filter { it != ("" to false) } }
    for ((kind, photo) in kinds) {
        val kv = remember(choice, round, kind, photo) {
            pace.view(route.key, kind, photo, choice.model, choice.effort, EXAMPLE_LENGTHS)
        }
        PaperHint("${kindTitle(kind, photo).replaceFirstChar { it.uppercase() }}: ${examplesLine(kv)} · " + sourceText(kv))
    }
}

private const val MODELS_INFO =
    "Каждая дорога в Claude — своя модель и своё усилие. Заводские значения " +
        "действуют со следующего запроса, без пересборки. Усилие — глубина " +
        "размышлений: «по умолчанию» значит не передавать параметр (API берёт " +
        "medium у Опуса 5.5 и high у остальных), low быстрее и дешевле, xhigh и " +
        "max — для трудных случаев. Сонет до high включительно отвечает без " +
        "размышлений — правка это механика; xhigh и max включают их. Опус 5.5 и " +
        "Fable 5.1 думают всегда, Fable в два с половиной раза дороже Опуса и " +
        "оба умеют отказываться от текста — тогда придёт ошибка «модель отказалась». " +
        "Своё — краской, заводское — серым."

@Composable
private fun RouteRow(
    app: PravkaApp,
    settings: Settings,
    scope: CoroutineScope,
    route: ModelRoute,
    expanded: Boolean,
    round: Int,
    onToggle: () -> Unit,
) {
    // Flow создаётся один раз на дорогу: новый экземпляр на каждой
    // перекомпозиции переподписывал бы DataStore при каждом тапе.
    val flow = remember(route) { settings.modelChoiceFlow(route) }
    val choice by flow.collectAsState(initial = ModelChoice.defaultOf(route))
    val changed = !choice.isDefaultFor(route)
    // В свёрнутой строке — средняя фраза: сколько эта дорога ждёт обычно.
    val typical = remember(choice, round) {
        if (route in BATCH_ROUTES) ""
        else " · ~" + sec(
            app.paceStore.view(route.key, "", false, choice.model, choice.effort, listOf(EXAMPLE_LENGTHS[TYPICAL_INDEX]))
                .examples.first().second
        ) + " с"
    }

    SummaryLine(
        title = route.title,
        summary = Models.label(choice.model) + " · " + Models.effortLabel(choice.effort) + typical,
        expanded = expanded,
        onToggle = onToggle,
        changed = changed,
    ) {
        PaperHint(route.hint)
        ChipRow {
            for (model in Models.ALL) {
                PaperChip(
                    Models.label(model),
                    selected = choice.model == model,
                    onClick = { scope.launch { settings.setModel(route, model) } },
                )
            }
        }
        ChipRow {
            for (effort in Models.EFFORTS) {
                PaperChip(
                    Models.effortLabel(effort),
                    selected = choice.effort == effort,
                    onClick = { scope.launch { settings.setEffort(route, effort) } },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperHint(Models.priceLabel(choice.model))
            Spacer(Modifier.weight(1f))
            if (changed) {
                PaperTextButton(
                    "заводское: ${Models.label(route.defaultModel)} · ${Models.effortLabel(route.defaultEffort)}",
                    icon = Glyphs.Undo,
                    onClick = { scope.launch { settings.resetModelChoice(route) } },
                )
            }
        }
        SecondsBlock(app, route, choice, round)
    }
}
