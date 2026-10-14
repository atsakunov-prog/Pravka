package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.zf.pravka.core.SpendBook
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvBarRow
import ru.zf.pravka.ui.KvCallout
import ru.zf.pravka.ui.KvColumn
import ru.zf.pravka.ui.KvColumns
import ru.zf.pravka.ui.KvGap
import ru.zf.pravka.ui.KvGlyphs
import ru.zf.pravka.ui.KvKpi
import ru.zf.pravka.ui.KvKpiRow
import ru.zf.pravka.ui.KvListRow
import ru.zf.pravka.ui.KvNote
import ru.zf.pravka.ui.KvSeg
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.KvTabs
import ru.zf.pravka.ui.KvTone
import ru.zf.pravka.ui.Look
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.fadingScroll

// «Claude: траты» (14.10.2026). Владелец: «значок денег Claude (все запросы в Claude) на главный экран справа вверху…
// систематизировать лучше по задачам… раз в неделю сам Claude пускай предлагает варианты экономии (красная точка над
// значком)», и вдогонку: «сбор в одном месте, плюс там отдельно должны быть серверные траты». Экран читает вид spend
// сервера Дел — одну книгу трат телефона и служб компа — и рисует общий итог, блоки «Телефон» и «Сервер» (службы и
// задачи полосами, дороже — выше), модели, кэш и пачки, тренд по дням, кто приносит и что не учитывается; советы недели
// — первыми, и, раз они на экране, красная точка гаснет. Вторая вкладка — прежний экран «$» телефона (дороги, кэш).

/** Значок трат Claude в шапке «Сегодня»: доллар и красная метка непрочитанных советов недели. */
@Composable
internal fun SpendAction(badge: SpendBook.Badge?, dot: Boolean, onClick: () -> Unit) {
    val hint = SpendBook.hint(badge)
    if (!Look.v2) {
        Box {
            ru.zf.pravka.ui.HeaderIcon(Glyphs.Dollar, hint, onClick)
            if (dot) ru.zf.pravka.ui.NewsDotMark(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp))
        }
        return
    }
    // `.ib` веба, как HeaderIcon v2: 40, прямоугольник 8, значок 20 цветом `--icon`; метка — как у значка Ковчега
    // (прямоугольник 10 цветом «сбой» с каймой фона: в виде v2 кружков нет).
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(KvShape.Control))
            .clickable(role = Role.Button, onClickLabel = hint, onClick = onClick)
            .semantics { contentDescription = hint },
        contentAlignment = Alignment.Center,
    ) {
        Icon(KvGlyphs.byKey("usd") ?: Glyphs.Dollar, null, tint = Kv.icon, modifier = Modifier.size(20.dp))
        if (dot) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 5.dp, end = 5.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Kv.bad)
                    .border(1.5.dp, Kv.bg, RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
internal fun ClaudeSpendScreen(app: PravkaApp) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        KvTabs(listOf("Все траты", "Телефон подробно"), tab, { tab = it }, Modifier.padding(horizontal = 16.dp))
        if (tab == 1) CostScreen(app) else AllSpend(app)
    }
}

@Composable
private fun AllSpend(app: PravkaApp) {
    var screen by remember { mutableStateOf<SpendBook.Screen?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var local by remember { mutableStateOf<Pair<Double, List<Pair<String, Double>>>?>(null) }
    var period by rememberSaveable { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        // Свои дни — на сервер до того, как спросить вид: иначе экран показал бы телефон на полчаса позади.
        runCatching { app.claudeSpend.push("экран трат") }
        val got = runCatching { app.claudeSpend.view() }
        screen = got.getOrNull()?.let(SpendBook::screen)
        error = got.exceptionOrNull()?.message.orEmpty()
        if (screen == null) local = runCatching { app.claudeSpend.localWeek() }.getOrNull()
        loading = false
    }
    // Советы на экране — значит, открыты: точка над значком гаснет (сервер — spend.seen).
    val tipsId = screen?.tipsId
    LaunchedEffect(tipsId) { if (tipsId != null && screen?.tipsSeen == false) scope.launch { app.claudeSpend.markSeen(tipsId) } }

    Column(
        Modifier
            .fillMaxSize()
            .fadingScroll()
            .padding(ScreenPad.Padding),
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        val s = screen
        if (s == null) {
            if (loading) {
                KvNote("Спрашиваю сервер Дел…")
            } else {
                KvCallout(
                    "Сервер Дел не ответил" + (if (error.isNotBlank()) " ($error)" else "") +
                        " — здесь только траты этого телефона. Службы компа и советы — когда сервер будет на связи.",
                    tone = KvTone.WARN,
                )
                local?.let { (usd, routes) -> LocalCard(usd, routes) }
            }
            return@Column
        }
        val key = SpendBook.PERIODS[period].first
        val p = s.periods.getValue(key)
        KvSeg(SpendBook.PERIODS.map { it.second }, period, { period = it })
        TotalCard(s, p, key)
        if (s.tips.isNotEmpty()) TipsCard(s)
        for (b in p.blocks) BlockCard(b, p.usd)
        ModelsCard(p)
        SavingsCard(p)
        TrendCard(s)
        SourcesCard(s)
    }
}

@Composable
private fun TotalCard(s: SpendBook.Screen, p: SpendBook.Period, key: String) {
    val phone = p.blocks.firstOrNull { it.key == "phone" }?.usd ?: 0.0
    val server = p.blocks.firstOrNull { it.key == "server" }?.usd ?: 0.0
    PaperCard(label = "Claude: всего", note = SpendBook.PERIODS.first { it.first == key }.second.lowercase()) {
        KvKpiRow {
            KvKpi(SpendBook.money(p.usd), Modifier.weight(1f), label = "всего · ${p.calls} выз.")
            KvKpi(SpendBook.money(phone), Modifier.weight(1f), label = "телефон")
            KvKpi(SpendBook.money(server), Modifier.weight(1f), label = "сервер")
        }
        if (key == "week") {
            KvGap()
            KvNote("Прошлые 7 дней — ${SpendBook.money(s.prevWeek)}")
        }
        if (p.inexactUsd >= 0.01) KvNote("Из них ${SpendBook.money(p.inexactUsd)} — без токенов (книга службы знает только $)")
    }
}

@Composable
private fun TipsCard(s: SpendBook.Screen) {
    val week = s.tipsWeek.takeIf { it.length == 10 }?.let { it.substring(8) + "." + it.substring(5, 7) }.orEmpty()
    PaperCard(label = "Советы Claude на неделю", note = if (week.isNotBlank()) "неделя с $week" else null) {
        if (s.tipsNote.isNotBlank()) {
            KvCallout(s.tipsNote, tone = KvTone.CLAUDE)
            KvGap()
        }
        s.tips.forEachIndexed { i, t ->
            // Где тратится — первым словом подписи (у строки списка слева место только под время: «телеф» не годится).
            val where = when (t.where) { "phone" -> "Телефон"; "server" -> "Сервер"; else -> null }
            KvListRow(
                title = t.title,
                sub = listOf(where, t.why, t.how.takeIf { it.isNotBlank() }?.let { "как: $it" }, t.risk.takeIf { it.isNotBlank() }?.let { "риск: $it" })
                    .filterNot { it.isNullOrBlank() }.joinToString(" · "),
                value = if (t.saveMonth > 0) "−${SpendBook.money(t.saveMonth)}/мес" else null,
                valueColor = Kv.ok,
                valueSmall = true,
                divider = i > 0,
                subLines = 6,
            )
        }
    }
}

@Composable
private fun BlockCard(b: SpendBook.Block, total: Double) {
    val share = if (total > 0) " · ${Math.round(100 * b.usd / total)}%" else ""
    PaperCard(label = b.title, note = SpendBook.money(b.usd) + share) {
        if (b.services.isEmpty()) {
            KvNote(if (b.key == "phone") "Телефон за этот период Claude не звал (или ещё не присылал)." else "Службы компа за этот период Claude не звали.")
            return@PaperCard
        }
        // У телефона одна служба — сразу задачи; у сервера — сначала службы, потом самые дорогие задачи.
        if (b.key != "phone") {
            val top = b.services.maxOf { it.usd }.coerceAtLeast(1e-9)
            for (l in b.services) KvBarRow(l.title, (l.usd / top).toFloat(), SpendBook.money(l.usd), labelWidth = 140.dp, valueWidth = 64.dp)
            KvGap()
            KvNote("По задачам")
        }
        val top = b.tasks.maxOfOrNull { it.usd }?.coerceAtLeast(1e-9) ?: 1.0
        for (l in b.tasks.take(TASKS_MAX)) {
            KvBarRow(l.title, (l.usd / top).toFloat(), SpendBook.money(l.usd), labelWidth = 170.dp, valueWidth = 64.dp)
        }
        if (b.tasks.size > TASKS_MAX) KvNote("и ещё ${b.tasks.size - TASKS_MAX} — на ${SpendBook.money(b.tasks.drop(TASKS_MAX).sumOf { it.usd })}")
    }
}

@Composable
private fun ModelsCard(p: SpendBook.Period) {
    if (p.models.isEmpty()) return
    PaperCard(label = "По моделям") {
        val top = p.models.maxOf { it.usd }.coerceAtLeast(1e-9)
        for (l in p.models) KvBarRow(l.title, (l.usd / top).toFloat(), SpendBook.money(l.usd), labelWidth = 140.dp, valueWidth = 64.dp)
    }
}

@Composable
private fun SavingsCard(p: SpendBook.Period) {
    PaperCard(
        label = "Кэш и пачки",
        info = "Кэш: те же токены по цене входа минус как вышло — запись дороже входа, чтение в 20 раз дешевле; минус — " +
            "кэш пишется, а не читается. Пачки (Message Batches) — половина цены: всё, что может подождать минуты.",
    ) {
        KvKpiRow {
            KvKpi(
                (if (p.cacheSaved < 0) "−" else "") + SpendBook.money(kotlin.math.abs(p.cacheSaved)), Modifier.weight(1f),
                label = "кэш · прочитано ${p.cacheRead / 1000} тыс.", color = if (p.cacheSaved < 0) Kv.warn else Kv.text,
            )
            KvKpi(SpendBook.money(p.batchSaved), Modifier.weight(1f), label = "пачки · ${p.batchCalls} из ${p.calls}")
        }
    }
}

@Composable
private fun TrendCard(s: SpendBook.Screen) {
    if (s.trend.isEmpty()) return
    val days = s.trend.takeLast(TREND_DAYS)
    PaperCard(label = "По дням", note = "${days.size} дней") {
        // Подпись — каждый пятый день и сегодня: тридцать чисел под тридцатью столбиками на сложенном не влезают.
        KvColumns(days.mapIndexed { i, (d, v) ->
            val label = if (d == s.today || (days.size - 1 - i) % 5 == 0) d.takeLast(2).trimStart('0') else ""
            KvColumn(v.toFloat(), label, accent = d == s.today)
        }, height = 96.dp)
        KvGap()
        val sum = days.sumOf { it.second }
        KvNote("За ${days.size} дней ${SpendBook.money(sum)} · в среднем ${SpendBook.money(sum / days.size)} в день")
    }
}

@Composable
private fun SourcesCard(s: SpendBook.Screen) {
    PaperCard(
        label = "Откуда числа",
        info = "Каждая строка книги — от одного источника: Дела пишут свои вызовы сами, телефон присылает итоги дней, " +
            "службы компа приносит сборщик «Дома». Аудит — docs/claude-spend.md.",
    ) {
        s.sources.forEachIndexed { i, l -> KvListRow(title = l.title, sub = l.sub, divider = i > 0) }
        if (s.gaps.isNotEmpty()) {
            KvGap()
            KvNote("Не учитывается:")
            for (g in s.gaps) KvNote("· $g")
        }
    }
}

@Composable
private fun LocalCard(usd: Double, routes: List<Pair<String, Double>>) {
    PaperCard(label = "Телефон, 7 дней", note = SpendBook.money(usd)) {
        if (routes.isEmpty()) {
            KvNote("Книга трат телефона пока пуста — она пишется с этой сборки.")
            return@PaperCard
        }
        val top = routes.maxOf { it.second }.coerceAtLeast(1e-9)
        for ((route, v) in routes) {
            val title = ru.zf.pravka.data.ModelRoute.entries.firstOrNull { it.key == route }?.let { "${it.mode} · ${it.title}" } ?: route
            KvBarRow(title, (v / top).toFloat(), SpendBook.money(v), labelWidth = 170.dp, valueWidth = 64.dp)
        }
    }
}

private const val TASKS_MAX = 10
private const val TREND_DAYS = 30
