package ru.zf.pravka

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.NetProbe
import ru.zf.pravka.data.NetProbeStore
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.MicroLow
import ru.zf.pravka.ui.MicroMid
import ru.zf.pravka.ui.MicroOk
import ru.zf.pravka.ui.MicroOver
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.RowRule

// Связь с облаками (01.10.2026; владелец: «пинг каждые 5 минут… пинговал два
// сервера: тот, который расшифровывает, и тот, который принимает… отдельный
// лог, есть доступ или нет… нарисуем, когда есть доступ и когда нет… мне надо
// где-то неделю это проверять»). Как проверяем — `provider/NetProber.kt`,
// что считаем доступом — `core/NetProbe.kt`.

@Composable
internal fun NetProbeSettings(app: PravkaApp) {
    val settings = app.settings
    val scope = app.appScope
    val context = LocalContext.current
    val on by settings.netProbeFlow.collectAsState(initial = true)
    val minutes by settings.netProbeIntervalFlow.collectAsState(initial = NetProbe.INTERVAL_DEFAULT_MIN)
    val last by app.netProbeStore.last.collectAsState()
    val version by app.netProbeStore.version.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var netNow by remember { mutableStateOf("") }
    LaunchedEffect(version) { netNow = withContext(Dispatchers.IO) { app.netProber.netLabel() } }

    val weekFrom = remember(version) { dayStart(System.currentTimeMillis()) - 6 * DAY_MS }
    val week by produceState(emptyList<NetProbeStore.Probe>() to emptyList<NetProbeStore.Live>(), version) {
        value = withContext(Dispatchers.IO) {
            app.netProbeStore.warm()
            app.netProbeStore.read(weekFrom)
        }
    }
    val journal by produceState(emptyList<String>(), version) {
        // Строки журнала ложатся чуть позже замера (тот же поток диска) — подождать их.
        delay(400)
        value = withContext(Dispatchers.IO) { app.netLog.readLast(80).reversed() }
    }

    PaperCard(
        label = "проверка",
        info = "Раз в выбранный срок Правка стучится к серверам тем же путём, каким ходит сама, — через " +
            "ту же сеть и тот же VPN: Интернет (пустой ответ Google, как проверяет связь сам Android), " +
            "Google (дверь облачного распознавания), Claude (список моделей — бесплатный запрос; 403 — " +
            "Anthropic не пускает отсюда) и облако семьи, если задано. Каждый раз — свежим соединением: " +
            "тёплое прятало бы как раз рукопожатие через VPN. Проверка идёт, пока жива служба; телефон " +
            "спит — проверок реже, на картинке это пустые места, а не «нет доступа».",
    ) {
        PaperToggle(
            title = "Проверять связь с облаками",
            checked = on,
            onCheckedChange = { v -> scope.launch { settings.setNetProbe(v) } },
            hint = if (on) "раз в $minutes мин · журнал и картинка ниже" else "выключено — картинка стоит",
        )
        ChipRow {
            for (m in NetProbe.INTERVALS_MIN) {
                PaperChip(
                    if (m == 1) "Каждую минуту" else "Раз в $m мин",
                    selected = m == minutes,
                    onClick = { scope.launch { settings.setNetProbeInterval(m) } },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperButton(
                if (checking) "Проверяю…" else "Проверить сейчас",
                icon = Glyphs.Refresh,
                primary = true,
                enabled = !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        try {
                            app.netProber.probe("вручную")
                        } finally {
                            checking = false
                        }
                    }
                },
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Сеть: $netNow",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val targets = NetProbe.Target.entries.filter { t ->
        t != NetProbe.Target.CLOUD || week.first.any { it.hits.containsKey(t) }
    }
    val todayFrom = dayStart(System.currentTimeMillis())

    PaperCard(label = "сейчас") {
        val p = last
        if (p == null) {
            PaperHint("Ещё не проверял — «Проверить сейчас» или подожди тика службы.")
        } else {
            Text(
                "Проверено в ${hm(p.at)} · ${p.net}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            targets.forEachIndexed { i, t ->
                if (i > 0) RowRule()
                val h = p.hits[t]
                val today = NetProbe.share(week.first.filter { it.at >= todayFrom }.map { NetProbe.mark(it.hits[t]) })
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(markColor(NetProbe.mark(h))))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when {
                                h == null -> "не проверялся"
                                h.ok -> "есть · ${h.ms} мс" + if (h.why != "есть") " · ${h.why}" else ""
                                else -> "нет · ${h.why}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (h?.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "сегодня ${NetProbe.percent(today)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    PaperCard(
        label = "неделя",
        info = "Каждая строка — сутки, слева направо от полуночи до полуночи. Зелёное — доступ есть, " +
            "жёлтое — есть, но ответ дольше трёх секунд, красное — нет доступа, пусто — проверок не было " +
            "(служба спала или проверка выключена). Оранжевые насечки — сбои в деле: тейк ушёл с молчащего " +
            "облака на офлайн-пакет, запрос к Claude не достучался. Справа — доля проверок с доступом.",
    ) {
        val holdMs = 2L * minutes * 60_000L + 60_000L
        targets.forEachIndexed { i, t ->
            if (i > 0) Spacer(Modifier.height(14.dp))
            val all = NetProbe.share(week.first.map { NetProbe.mark(it.hits[t]) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "${NetProbe.percent(all)} за 7 дней",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            val points = week.first.map { it.at to NetProbe.mark(it.hits[t]) }.filter { it.second != NetProbe.Mark.NONE }
            val lives = week.second.filter { it.target == t }.map { it.at }
            for (d in 0 until 7) {
                val from = todayFrom - d * DAY_MS
                val to = from + DAY_MS
                val dayShare = NetProbe.share(points.filter { it.first in from until to }.map { it.second })
                DayBar(
                    label = dayLabel(from),
                    segments = NetProbe.segments(points, from, to, holdMs),
                    lives = lives.filter { it in from until to },
                    from = from,
                    now = if (d == 0) System.currentTimeMillis() else 0L,
                    percent = NetProbe.percent(dayShare),
                )
            }
            HourAxis()
        }
    }

    PaperCard(
        label = "журнал",
        trailing = {
            PaperTextButton("Поделиться", icon = Glyphs.Share, onClick = {
                runCatching {
                    context.startActivity(android.content.Intent.createChooser(app.netLog.shareIntent(), "Журнал связи"))
                }
            })
        },
    ) {
        if (journal.isEmpty()) {
            PaperHint("Пусто: строка на каждую проверку и отдельная — на каждую перемену «есть ↔ нет».")
        } else {
            for (line in journal) {
                val bad = line.contains("НЕТ") || line.contains("в деле")
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (line.contains("НЕТ ДОСТУПА") || line.contains("снова есть")) FontWeight.SemiBold else null,
                    color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

/** Одна строка недели: день, полоса суток, доля. */
@Composable
private fun DayBar(
    label: String,
    segments: List<NetProbe.Segment>,
    lives: List<Long>,
    from: Long,
    now: Long,
    percent: String,
) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val cursor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(LABEL_W),
        )
        Canvas(Modifier.weight(1f).height(12.dp)) {
            val w = size.width
            val h = size.height
            val r = CornerRadius(h / 3f, h / 3f)
            drawRoundRect(track, cornerRadius = r)
            fun x(ms: Long) = ((ms - from).toFloat() / DAY_MS).coerceIn(0f, 1f) * w
            for (s in segments) {
                val a = x(s.fromMs)
                val b = maxOf(x(s.toMs), a + 1.5f)
                drawRoundRect(markColor(s.mark), topLeft = Offset(a, 0f), size = Size(b - a, h), cornerRadius = CornerRadius(2f, 2f))
            }
            for (hour in intArrayOf(6, 12, 18)) {
                val gx = w * hour / 24f
                drawLine(grid, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
            }
            for (t in lives) {
                val lx = x(t)
                drawLine(MicroOver, Offset(lx, -2f), Offset(lx, h + 2f), strokeWidth = 3f)
            }
            if (now > 0L) {
                val cx = x(now)
                drawLine(cursor, Offset(cx, -2f), Offset(cx, h + 2f), strokeWidth = 2f)
            }
        }
        Text(
            percent,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(PERCENT_W).padding(start = 6.dp),
        )
    }
}

/** Подписи часов под полосами: 0 · 6 · 12 · 18 · 24. */
@Composable
private fun HourAxis() {
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(LABEL_W))
        Row(Modifier.weight(1f)) {
            val style = MaterialTheme.typography.labelSmall
            val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            Text("0", style = style, color = color)
            Spacer(Modifier.weight(1f))
            Text("6", style = style, color = color)
            Spacer(Modifier.weight(1f))
            Text("12", style = style, color = color)
            Spacer(Modifier.weight(1f))
            Text("18", style = style, color = color)
            Spacer(Modifier.weight(1f))
            Text("24", style = style, color = color)
        }
        Spacer(Modifier.width(PERCENT_W))
    }
}

private fun markColor(m: NetProbe.Mark): Color = when (m) {
    NetProbe.Mark.OK -> MicroOk
    NetProbe.Mark.SLOW -> MicroMid
    NetProbe.Mark.FAIL -> MicroLow
    NetProbe.Mark.NONE -> Color(0x33888888)
}

private val LABEL_W = 44.dp
private val PERCENT_W = 46.dp
private const val DAY_MS = 86_400_000L

private fun dayStart(ms: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun dayLabel(ms: Long): String = SimpleDateFormat("EE d", Locale.forLanguageTag("ru")).format(Date(ms))

private fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
