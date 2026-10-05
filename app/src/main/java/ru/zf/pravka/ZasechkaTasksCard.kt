package ru.zf.pravka

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.launch
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.ZasechkaTasks
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.RowRule

/**
 * Плашка «дела» во вкладке Засечки (05.10.2026; владелец: «должен быть
 * список дел из моих дел, и рядом с каждым — значок Play, когда я просто
 * начинаю заниматься этим делом… и можно ещё сделать какой-то комментарий»).
 * Что в списке — `core/ZasechkaTasks.shortlist`: идущее, «сейчас», начатое за
 * неделю, на сегодня и просроченное. ▶ — запись ленты из дела (та же дорога,
 * что во вкладке «Дела»), у идущего — «идёт N мин», заметка и стоп; заметка
 * ложится комментарием к записи и уезжает в само дело. Тап по строке —
 * карточка дела. Дел не на своём сервере или нечего показать — плашки нет.
 */
@Composable
internal fun ZasechkaTasksCard(
    app: PravkaApp,
    entries: List<ZasechkaStore.Entry>,
    now: Long,
    onComment: (ZasechkaStore.Entry) -> Unit,
    onOpen: (Dela.Task) -> Unit,
) {
    val delaOn by app.delaServer.collectAsState()
    // Режим «Дела» выключен в профиле — его вкладки нет, нет и плашки.
    val profile by app.profileStore.flow.collectAsState()
    if (!delaOn || profile?.has(ru.zf.pravka.data.Profile.Mode.DELA) == false) return
    val snap by app.delaStore.view.collectAsState()
    val link by app.delaSync.link.collectAsState()
    val me = link?.user ?: app.delaStore.me
    val today = LocalDate.now().toString()
    val list = remember(snap, entries, me, today) { ZasechkaTasks.shortlist(snap, me, today, entries, now) }
    if (list.isEmpty()) return
    val spent = remember(entries, now) { ZasechkaTasks.spent(entries, now) }
    val runningEntry = entries.lastOrNull { it.open }
    val runningTask = runningEntry?.task.orEmpty()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var starting by remember { mutableStateOf("") }
    val shown = if (expanded) list else list.take(COLLAPSED)
    PaperCard(
        label = "дела",
        info = "▶ — начать дело в ленте: запись знает, какое это дело, и его время копится " +
            "в «в ленте». У идущего — заметка (ляжет комментарием к записи и в само дело) " +
            "и стоп. Здесь — идущее, отмеченное на сегодня, начатое за неделю, на сегодня " +
            "и просроченное; тап по строке — карточка дела.",
        trailing = if (list.size > COLLAPSED) {
            {
                PaperTextButton(
                    if (expanded) "свернуть" else "ещё ${list.size - COLLAPSED}",
                    onClick = { expanded = !expanded },
                )
            }
        } else null,
    ) {
        shown.forEachIndexed { i, t ->
            if (i > 0) RowRule()
            val live = t.id == runningTask && runningEntry != null
            TaskStartRow(
                task = t,
                spentMs = spent[t.id] ?: 0L,
                liveMs = if (live) runningEntry!!.durationMs(now) else -1L,
                starting = starting == t.id,
                onOpen = { onOpen(t) },
                onStart = {
                    if (starting.isBlank()) {
                        starting = t.id
                        app.appScope.launch {
                            val entry = runCatching { app.zasechkaEngine.startTask(t) }.getOrNull()
                            Feedback.toast(app, if (entry != null) "⏱ ${entry.title}" else "Не смог записать дело")
                            starting = ""
                        }
                    }
                },
                onStop = { app.appScope.launch { app.zasechkaEngine.closeOpen() } },
                onComment = { runningEntry?.let(onComment) },
            )
        }
    }
}

private const val COLLAPSED = 3

/**
 * Строка дела в Засечке: название, номер, проект и время в ленте; справа ▶,
 * а у идущего — заметка и стоп. Одно действие — один значок.
 */
@Composable
private fun TaskStartRow(
    task: Dela.Task,
    spentMs: Long,
    /** Сколько идёт сейчас; −1 — дело не идёт. */
    liveMs: Long,
    starting: Boolean,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onComment: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val live = liveMs >= 0L
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                color = if (live) c.primary else c.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                add(task.numLabel)
                if (task.projectName.isNotBlank()) add(task.projectName)
                if (live) add("идёт " + ZasechkaTasks.label(liveMs))
                if (spentMs > 0L) add("в ленте " + ZasechkaTasks.label(spentMs))
            }.joinToString(" · ")
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = if (live) c.primary else c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            starting -> Text("…", style = MaterialTheme.typography.bodyMedium, color = c.primary)
            live -> {
                Icon(
                    Glyphs.Note,
                    contentDescription = "заметка к делу",
                    tint = c.primary,
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onComment).padding(4.dp),
                )
                Icon(
                    Glyphs.Stop,
                    contentDescription = "остановить",
                    tint = c.error,
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onStop).padding(4.dp),
                )
            }
            else -> Icon(
                Glyphs.Play,
                contentDescription = "начать в ленте",
                tint = c.primary,
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onStart).padding(4.dp),
            )
        }
    }
}
