package ru.zf.pravka

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.Svod
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.RowRule

/**
 * «Настройки → Голос и Claude → Свод» (06.10.2026, docs/svod-phone.md, 1.7):
 * что телефон знает с сервера. Список ключей — версия, кто и когда записал,
 * «с сервера» или «ждёт отправки». Текст здесь не правится: промпты правят
 * автоматы, а правда — на сервере. Одна кнопка — «Взять с сервера заново».
 */
@Composable
internal fun SvodSettings(app: PravkaApp) {
    val v by app.svodStore.view.collectAsState()
    val link by app.delaSync.link.collectAsState()
    val dela by app.delaStore.view.collectAsState()
    val on = Svod.FEATURE in dela.features
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")) }

    PaperCard(
        label = "свод · одна правда на сервере",
        info = "Промпты, словарь, правила, выбор моделей, справочники и числа «Денег» живут на сервере Дел. " +
            "Телефон держит их копию и работает по ней и без сети; что поменял телефон (обучение, тюнер, " +
            "ответ на вопрос сверки), уходит на сервер и только оттуда возвращается.",
    ) {
        PaperHint(
            when {
                link == null -> "Дела не подключены — Свод живёт на их сервере. Телефон работает на своих текстах."
                !on -> "Сервер Дел ещё не умеет Свод — телефон работает на своих текстах."
                v.seededAt == 0L -> "Сервер умеет Свод; первое знакомство — со следующим синком Дел."
                else -> "Своё отдано ${fmt.format(Date(v.seededAt))}" +
                    (if (v.fetchedAt > 0) " · весь Свод взят ${fmt.format(Date(v.fetchedAt))}" else "") +
                    " · записей ${v.entries.size}" +
                    (if (v.pending.isNotEmpty()) " · ждёт отправки ${v.pending.size}" else "")
            }
        )
        Spacer(Modifier.height(8.dp))
        val pendingKeys = v.pending.map { it.key }.toSet()
        val keys = (v.entries.map { it.key } + pendingKeys + v.dirty).distinct().sorted()
        for (key in keys) {
            val e = v.entries.firstOrNull { it.key == key }
            val state = when {
                key in pendingKeys -> "ждёт отправки"
                key in v.dirty -> "своё не отдано"
                e != null -> "с сервера"
                else -> ""
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(key, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    PaperHint(
                        listOfNotNull(
                            e?.let { "версия ${it.rev}" },
                            e?.author?.takeIf { it.isNotBlank() },
                            e?.updatedAt?.takeIf { it.isNotBlank() }?.let { it.take(16).replace('T', ' ') },
                        ).joinToString(" · ").ifBlank { "ещё нет на сервере" }
                    )
                }
                Text(
                    state,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state == "с сервера") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                )
            }
            RowRule()
        }
        Spacer(Modifier.height(8.dp))
        if (note.isNotBlank()) PaperHint(note)
        Row {
            Spacer(Modifier.weight(1f))
            PaperButton(
                if (busy) "беру…" else "Взять с сервера заново",
                icon = Glyphs.Undo,
                enabled = !busy && link != null && on,
                onClick = {
                    busy = true
                    app.appScope.launch {
                        note = app.delaSync.refetchSvod().fold(
                            { "Взял весь Свод с сервера: своё неотправленное снято, сторы — по серверу" },
                            { "Не вышло: ${it.message}" },
                        )
                        busy = false
                    }
                },
            )
        }
    }
}
