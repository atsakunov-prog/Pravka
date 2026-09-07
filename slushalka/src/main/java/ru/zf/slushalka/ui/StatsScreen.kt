package ru.zf.slushalka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.data.Stats
import ru.zf.slushalka.library.Book

/**
 * Статистика: сколько, когда и как быстро.
 *
 * Всё считается из журнала подходов (`Journal`) на месте: сверху итоги дня,
 * недели и серия дней; дальше динамика по дням, неделям или месяцам; когда в
 * сутках и в неделе книга звучит чаще; темп чтения и слушания; и книги - к
 * каким возвращаешься, к каким нет, и когда при таком темпе кончится текущая.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(app: SlushalkaApp, onBack: () -> Unit) {
    val rev by app.journal.rev.collectAsState()
    val books by app.state.books.collectAsState()
    val current by app.state.current.collectAsState()
    val text by app.state.text.collectAsState()

    // Идущий подход растёт, пока экран открыт: пересчитываем раз в минуту, а
    // не на каждый тик плеера - цифрам на экране незачем дрожать.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            tick++
        }
    }
    val report by produceState<Stats.Report?>(initialValue = null, rev, tick) {
        value = withContext(Dispatchers.Default) { Stats.report(app.journal.all()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Статистика") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        val r = report
        if (r == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Считаю…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (r.sessions.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Пока пусто. Журнал начнёт заполняться с первого пуска записи или " +
                        "перелистнутой страницы: сколько слушал и читал, в какое время, как быстро " +
                        "и к каким книгам возвращаешься.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(48.dp))
                return@Column
            }

            Spacer(Modifier.height(8.dp))
            Headline(r)
            Spacer(Modifier.height(14.dp))
            DynamicsCard(r)
            Spacer(Modifier.height(14.dp))
            WhenCard(r)
            Spacer(Modifier.height(14.dp))
            PaceCard(r)
            Spacer(Modifier.height(14.dp))
            BooksCard(app, r, books, current, text?.length ?: 0)
            Spacer(Modifier.height(18.dp))
            Footer(app, r)
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ------------------------------------------------------------------ итоги

/** Три плашки: сегодня, неделя, серия. То, за чем заходят чаще всего. */
@Composable
private fun Headline(r: Stats.Report) {
    val today = r.todayTotals
    val week = r.last7
    val prev = r.prev7
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(
            title = "Сегодня",
            value = if (today.activeMs > 0) formatSpan(today.activeMs) else "—",
            note = buildString {
                append(parts(today).ifBlank { "пока ничего" })
                if (r.yesterday.activeMs > 0) append("\nвчера ").append(formatSpan(r.yesterday.activeMs))
            },
            modifier = Modifier.weight(1.15f),
        )
        Tile(
            title = "Неделя",
            value = if (week.activeMs > 0) formatSpan(week.activeMs) else "—",
            note = buildString {
                if (week.activeMs > 0) append("в день ~").append(formatSpan(week.activeMs / 7))
                when {
                    prev.activeMs == 0L && week.activeMs > 0 -> append("\nнеделей раньше — ничего")
                    prev.activeMs > 0 -> {
                        val pct = ((week.activeMs - prev.activeMs) * 100.0 / prev.activeMs).toInt()
                        append("\n")
                        append(
                            when {
                                pct > 5 -> "на $pct% больше прошлой"
                                pct < -5 -> "на ${-pct}% меньше прошлой"
                                else -> "как на прошлой"
                            }
                        )
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
        Tile(
            title = "Серия",
            value = if (r.streak > 0) "${r.streak} ${plural(r.streak, "день", "дня", "дней")}" else "—",
            note = if (r.bestStreak > 0) "рекорд ${r.bestStreak} ${plural(r.bestStreak, "день", "дня", "дней")}"
            else "день идёт в счёт от минуты",
            modifier = Modifier.weight(0.9f),
        )
    }
}

@Composable
private fun Tile(title: String, value: String, note: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp,
            )
        }
    }
}

/** «слушал 52 мин · читал 20 мин · 14 стр.» - только то, что было. */
private fun parts(t: Stats.Totals): String = buildList {
    if (t.listenMs > 0) {
        add(
            "слушал ${formatSpan(t.listenMs)}" +
                // На скорости выше единицы записи проходит больше, чем времени.
                if (t.coveredMs > t.listenMs + 5 * 60_000L) " (${formatSpan(t.coveredMs)} записи)" else ""
        )
    }
    if (t.readMs > 0) add("читал ${formatSpan(t.readMs)}")
    if (t.aloudMs > 0) add("озвучка ${formatSpan(t.aloudMs)}")
    if (t.chars >= Settings.PAGE_CHARS / 2) add("${fmtPages(t.pages)} стр.")
}.joinToString(" · ")

// --------------------------------------------------------------- динамика

private enum class Span(val label: String) { DAYS("14 дней"), WEEKS("12 недель"), MONTHS("12 месяцев") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DynamicsCard(r: Stats.Report) {
    var span by remember { mutableStateOf(Span.DAYS) }
    val buckets = remember(r, span) {
        when (span) {
            Span.DAYS -> r.lastDays(14)
            Span.WEEKS -> r.lastWeeks(12)
            Span.MONTHS -> r.lastMonths(12)
        }
    }
    Section("По дням") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Span.entries.forEach { s ->
                FilterChip(selected = span == s, onClick = { span = s }, label = { Text(s.label) })
            }
        }
        Spacer(Modifier.height(10.dp))
        Bars(
            buckets = buckets,
            labelEvery = if (span == Span.WEEKS) 2 else 1,
            describe = { i, b ->
                val what = when (span) {
                    Span.DAYS -> formatDay(r.today.minusDays((buckets.lastIndex - i).toLong()))
                    Span.WEEKS -> "неделя с ${b.label}"
                    Span.MONTHS -> b.label
                }
                if (b.totals.activeMs == 0L) "$what: ничего" else "$what: ${parts(b.totals)}"
            },
        )
        Spacer(Modifier.height(10.dp))
        val m = r.last30
        Note(
            "Тап по столбику показывает, что в нём. " +
            if (m.activeMs == 0L) "За месяц пока ничего."
            else "За 30 дней: ${formatSpan(m.activeMs)} — ${parts(m)}." + run {
                val p = r.prev30
                if (p.activeMs > 0) {
                    val pct = ((m.activeMs - p.activeMs) * 100.0 / p.activeMs).toInt()
                    when {
                        pct > 5 -> " Это на $pct% больше, чем месяцем раньше."
                        pct < -5 -> " Это на ${-pct}% меньше, чем месяцем раньше."
                        else -> " Как и месяцем раньше."
                    }
                } else ""
            }
        )
    }
}

// ------------------------------------------------------------------- когда

@Composable
private fun WhenCard(r: Stats.Report) {
    val hours = remember(r) {
        r.hours.mapIndexed { h, t -> Stats.Bucket(if (h % 6 == 0) "$h" else "", t, false) }
    }
    val weekdays = remember(r) {
        val names = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
        r.weekdays.mapIndexed { i, t -> Stats.Bucket(names[i], t, i == r.today.dayOfWeek.value - 1) }
    }
    Section("Когда") {
        Text("По часам", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Bars(
            buckets = hours,
            height = 90.dp,
            labelEvery = 1,
            describe = { h, b ->
                "с $h:00 до ${(h + 1) % 24}:00: " + if (b.totals.activeMs == 0L) "ничего" else formatSpan(b.totals.activeMs)
            },
        )
        Spacer(Modifier.height(6.dp))
        Note(whenPhrase(r))
        Spacer(Modifier.height(12.dp))
        Text("По дням недели", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Bars(
            buckets = weekdays,
            height = 80.dp,
            labelEvery = 1,
            describe = { _, b -> "${b.label}: " + if (b.totals.activeMs == 0L) "ничего" else formatSpan(b.totals.activeMs) },
        )
        Spacer(Modifier.height(6.dp))
        Note(weekPhrase(r))
    }
}

/** «Чаще всего — вечером, около 22:00; на вечер приходится 61% времени». */
private fun whenPhrase(r: Stats.Report): String {
    val peak = r.peakHour ?: return "Часы прояснятся, когда наберётся полчаса."
    val total = r.hours.sumOf { it.activeMs }.coerceAtLeast(1)
    fun share(range: List<Int>) = (range.sumOf { r.hours[it].activeMs } * 100 / total).toInt()
    val partsOfDay = listOf(
        "утро" to (5..11).toList(),
        "день" to (12..17).toList(),
        "вечер" to (18..22).toList(),
        "ночь" to (listOf(23) + (0..4).toList()),
    ).map { (name, hs) -> name to share(hs) }.sortedByDescending { it.second }
    val (top, topShare) = partsOfDay.first()
    val second = partsOfDay[1]
    val when_ = when (top) {
        "утро" -> "утром"
        "день" -> "днём"
        "вечер" -> "вечером"
        else -> "ночью"
    }
    // Винительный падеж у «утро», «день», «вечер», «ночь» совпадает с именительным.
    return "Чаще всего — $when_, около $peak:00: на $top приходится $topShare% времени" +
        (if (second.second >= 15) ", на ${second.first} — ${second.second}%" else "") + "."
}

private fun weekPhrase(r: Stats.Report): String {
    val total = r.weekdays.sumOf { it.activeMs }
    if (total < 30 * 60_000L) return "Дни недели прояснятся, когда наберётся полчаса."
    val weekend = (r.weekdays[5].activeMs + r.weekdays[6].activeMs) * 100 / total
    val names = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    val best = r.weekdays.indices.maxByOrNull { r.weekdays[it].activeMs } ?: 0
    return "Самый книжный день — ${names[best]}. На выходные приходится $weekend% времени" +
        when {
            weekend >= 45 -> " — читаешь в основном по выходным."
            weekend <= 20 -> " — книга живёт в будних днях."
            else -> "."
        }
}

// -------------------------------------------------------------------- темп

@Composable
private fun PaceCard(r: Stats.Report) {
    Section("Темп") {
        val cpm = r.readCpm
        val rate = r.listenRate
        val lines = buildList {
            if (cpm != null) {
                val perPage = (Settings.PAGE_CHARS / cpm * 60).toLong()
                add(
                    "Читаю ${fmtInt(cpm.toInt())} зн./мин — ${fmtPages(cpm * 60 / Settings.PAGE_CHARS)} стр. в час, " +
                        "страница за ${formatClockShort(perPage)}."
                )
            } else if (r.all.readMs > 0) {
                add("Темп чтения глазами прояснится после десяти минут с книгой.")
            }
            if (rate != null) {
                add(
                    "Слушаю в среднем на ${formatSpeed(rate.toFloat())}: час записи за " +
                        "${formatSpan((3600_000 / rate).toLong())}."
                )
            }
            val aloudCpm = r.last30.let { if (it.aloudMs >= 10 * 60_000L) (it.chars - it.readChars) / (it.aloudMs / 60_000.0) else null }
            if (aloudCpm != null && aloudCpm > 0) {
                add("Озвучка идёт ${fmtInt(aloudCpm.toInt())} зн./мин — ${fmtPages(aloudCpm * 60 / Settings.PAGE_CHARS)} стр. в час.")
            }
            if (r.medianSessionMs > 0) {
                val longest = r.longest
                add(
                    "Обычный подход — ${formatSpan(r.medianSessionMs)}" +
                        (if (longest != null && longest.activeMs > r.medianSessionMs)
                            ", самый долгий — ${formatSpan(longest.activeMs)} (${formatDay(longest.startAt)})" else "") + "."
                )
            }
            val since = if (r.sinceAt > 0) Stats.readingDay(r.sinceAt, r.zone) else r.today
            val totalDays = ChronoUnit.DAYS.between(since, r.today).toInt() + 1
            add(
                "${r.sessions.size} ${plural(r.sessions.size, "подход", "подхода", "подходов")}, " +
                    "с книгой ${r.activeDays} ${plural(r.activeDays, "день", "дня", "дней")} из $totalDays."
            )
        }
        lines.forEachIndexed { i, line ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ------------------------------------------------------------------- книги

@Composable
private fun BooksCard(
    app: SlushalkaApp,
    r: Stats.Report,
    books: List<Book>,
    current: Book?,
    currentTextLength: Int,
) {
    val byId = remember(books) { books.associateBy { it.id } }
    Section("Книги") {
        r.books.forEachIndexed { i, bs ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val book = byId[bs.bookId]
            val st = app.positions.get(bs.bookId)
            val title = book?.title ?: bs.bookId.substringAfterLast('/')
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val t = bs.totals
            // Время в наушниках до появления журнала плеер считал сам - оно
            // лежит в позиции книги; показываем большее из двух.
            val listenedAll = maxOf(st.listenedMs, t.listenMs)
            val line = buildList {
                add(formatSpan(t.activeMs))
                add("${bs.sessions} ${plural(bs.sessions, "подход", "подхода", "подходов")}")
                add(
                    if (bs.spanDays > bs.days) "${bs.days} ${plural(bs.days, "день", "дня", "дней")} из ${bs.spanDays}"
                    else "${bs.days} ${plural(bs.days, "день", "дня", "дней")}"
                )
                if (t.chars >= Settings.PAGE_CHARS) add("${fmtPages(t.pages)} стр.")
                if (listenedAll > t.listenMs + 5 * 60_000L) add("всего в наушниках ${formatSpan(listenedAll)}")
            }
            Text(
                line.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val tail = buildList {
                add("последний раз ${formatAgo(bs.lastAt)}")
                bs.usualHour?.let { add("обычно около $it:00") }
                if (st.finished) add("дослушано")
                else if (book != null && book.hasAudio && book.totalMs > 0 && st.absMs > 0) {
                    add("${(st.absMs * 100 / book.totalMs).toInt()}%")
                }
            }
            Text(
                tail.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Прогноз - для книг, которые ещё идут и к которым возвращались за месяц.
            if (book != null && !st.finished && bs.last30.activeMs > 0) {
                forecastLine(r, book, st.absMs, st.readChar, current?.id == book.id, currentTextLength)?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** «Осталось 6 ч 10 м записи — при таком темпе ещё 9 дней, к 16 сентября». */
private fun forecastLine(
    r: Stats.Report,
    book: Book,
    absMs: Long,
    readChar: Int,
    isCurrent: Boolean,
    textLength: Int,
): String? {
    val recent = r.window(0, 14)
    if (book.hasAudio) {
        if (book.totalMs <= 0) return null
        val left = (book.totalMs - absMs).coerceAtLeast(0)
        if (left < 60_000) return null
        val f = Stats.forecastAudio(
            leftMs = left,
            rate = r.listenRate,
            fallbackSpeed = 1f,
            dailyListenMs = recent.listenMs / 14,
            today = r.today,
        )
        return "Осталось ${formatSpan(left)} записи, это ${formatSpan(f.realLeftMs)} у наушников" +
            (f.days?.let { d -> " — при таком темпе ещё $d ${plural(d, "день", "дня", "дней")}, к ${formatDay(f.date!!)}" } ?: "") + "."
    }
    // Книга без записи: длина текста известна только у открытой книги.
    if (!isCurrent || textLength <= 0 || readChar < 0) return null
    val leftChars = (textLength - readChar).toLong().coerceAtLeast(0)
    if (leftChars < Settings.PAGE_CHARS) return null
    val f = Stats.forecastText(leftChars, r.readCpm, recent.readMs / 14, r.today) ?: return null
    return "Осталось ${fmtPages(leftChars / Settings.PAGE_CHARS.toDouble())} стр., это ${formatSpan(f.realLeftMs)} чтения" +
        (f.days?.let { d -> " — при таком темпе ещё $d ${plural(d, "день", "дня", "дней")}, к ${formatDay(f.date!!)}" } ?: "") + "."
}

// ------------------------------------------------------------------ подвал

@Composable
private fun Footer(app: SlushalkaApp, r: Stats.Report) {
    val asks = app.askLog.count()
    val lines = buildList {
        if (asks > 0) add("Вопросов по книгам: $asks · потрачено %.2f $".format(Locale.US, app.askLog.totalUsd()))
        if (r.sinceAt > 0) add("Журнал ведётся с ${formatDay(r.sinceAt)}. Читательские сутки начинаются в четыре утра: то, что слушаешь после полуночи, - ещё вечер.")
    }
    lines.forEach {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
    }
}

// ----------------------------------------------------------------- рисунок

/**
 * Столбики: слушание тёмным снизу, чтение и озвучка светлым сверху. Тап по
 * столбику показывает, что в нём, - подписи под каждым не поместились бы.
 */
@Composable
private fun Bars(
    buckets: List<Stats.Bucket>,
    height: androidx.compose.ui.unit.Dp = 120.dp,
    labelEvery: Int,
    describe: (index: Int, Stats.Bucket) -> String,
) {
    val scheme = MaterialTheme.colorScheme
    var picked by remember(buckets) { mutableStateOf<Int?>(null) }
    val max = remember(buckets) { buckets.maxOfOrNull { it.totals.activeMs }?.coerceAtLeast(60_000L) ?: 60_000L }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Legend(scheme.primary, "слушал")
            Spacer(Modifier.width(10.dp))
            Legend(scheme.tertiary, "читал")
            Spacer(Modifier.weight(1f))
            Text(
                picked?.let { i -> buckets.getOrNull(i)?.let { describe(i, it) } } ?: "верх шкалы: ${formatSpan(max)}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(buckets) {
                    detectTapGestures { off ->
                        val slot = size.width.toFloat() / buckets.size
                        val i = (off.x / slot).toInt().coerceIn(0, buckets.lastIndex)
                        picked = if (picked == i) null else i
                    }
                },
        ) {
            val slot = size.width / buckets.size
            val barW = slot * 0.62f
            val base = size.height - 1f
            drawLine(
                color = scheme.outlineVariant,
                start = Offset(0f, base),
                end = Offset(size.width, base),
                strokeWidth = 1f,
            )
            buckets.forEachIndexed { i, b ->
                val listen = b.totals.listenMs.toFloat() / max * (size.height - 4f)
                val text = b.totals.textMs.toFloat() / max * (size.height - 4f)
                val x = i * slot + (slot - barW) / 2f
                val dim = when {
                    picked == null -> if (b.current) 1f else 0.78f
                    picked == i -> 1f
                    else -> 0.45f
                }
                if (listen > 0f) {
                    drawRoundRect(
                        color = scheme.primary.copy(alpha = dim),
                        topLeft = Offset(x, base - listen),
                        size = Size(barW, listen),
                        cornerRadius = CornerRadius(2f, 2f),
                    )
                }
                if (text > 0f) {
                    drawRoundRect(
                        color = scheme.tertiary.copy(alpha = dim),
                        topLeft = Offset(x, base - listen - text),
                        size = Size(barW, text),
                        cornerRadius = CornerRadius(2f, 2f),
                    )
                }
                if (listen <= 0f && text <= 0f) {
                    // Пустой день - точка на базе, чтобы столбики не «прыгали» по ширине.
                    drawRect(
                        color = scheme.outlineVariant.copy(alpha = 0.6f),
                        topLeft = Offset(x, base - 2f),
                        size = Size(barW, 2f),
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth()) {
            buckets.forEachIndexed { i, b ->
                Text(
                    if (i % labelEvery == 0) b.label else "",
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    color = if (b.current) scheme.onSurface else scheme.onSurfaceVariant,
                    fontWeight = if (b.current) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ----------------------------------------------------------------- мелочи

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 1, 2-4, 5+: «1 день», «2 дня», «5 дней». */
fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

private fun fmtInt(n: Int): String = String.format(Locale.forLanguageTag("ru"), "%,d", n)

/** Страницы: до десяти - с половинками, дальше целыми. */
private fun fmtPages(p: Double): String =
    if (p < 10) "%.1f".format(p).replace('.', ',').removeSuffix(",0") else p.toInt().toString()

/** «1 мин 25 с» - для коротких промежутков, где минуты без секунд слишком грубы. */
private fun formatClockShort(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return when {
        m > 0 && s > 0 -> "$m мин $s с"
        m > 0 -> "$m мин"
        else -> "$s с"
    }
}

private val DAY_FMT = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))

private fun formatDay(d: LocalDate): String = DAY_FMT.format(d)

private fun formatDay(at: Long): String =
    DAY_FMT.format(java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate())
