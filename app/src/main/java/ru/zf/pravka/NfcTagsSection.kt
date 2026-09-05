package ru.zf.pravka

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.zf.pravka.data.NfcTag

/**
 * Метки NFC: наклейка = засечка. Владелец: «давай добавим возможность
 * касания NFC-метки и программирования, что она делает. в настройках
 * отдельно. например, метка в туалете, на кухне, на велике, в машине».
 *
 * Программируется именно здесь, а не на метке: на наклейку уезжает только
 * идентификатор. Поменять категорию — тап в этом списке, наклейку трогать
 * не надо. Заодно это снимает вопрос размера: у дешёвых NTAG213 всего 144
 * байта, и «Быт: гигиена» по-русски их бы съело.
 */
@Composable
fun NfcTagsSection(app: PravkaApp) {
    val context = LocalContext.current
    val scope = app.appScope
    val tags by app.settings.nfcTagsFlow.collectAsState(initial = emptyList<NfcTag>())
    val categoryEntries by app.zasechkaStore.categoriesFlow.collectAsState()
    val categories = remember(categoryEntries) { categoryEntries.map { it.name } }

    var editing by remember { mutableStateOf<NfcTag?>(null) }
    var writing by remember { mutableStateOf<NfcTag?>(null) }

    val adapter = remember { runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull() }

    Text("Метки NFC", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Наклейка на стене — это засечка без телефона в руках. Приложил, зайдя " +
            "в туалет, приложил, выйдя. Работает и с погашенным экраном на " +
            "заблокированном телефоне. На саму метку уходит только номер: что " +
            "она делает, задаётся здесь и меняется без перезаписи наклейки.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    when {
        adapter == null -> Text(
            "В этом телефоне нет NFC — метки работать не будут.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        !adapter.isEnabled -> {
            Text(
                "NFC выключен — записать и прочитать метку не выйдет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_NFC_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("Включить NFC") }
        }
    }

    for (tag in tags) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    tag.name.ifBlank { "Без имени" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    buildString {
                        append(NfcTag.actLabel(tag.act))
                        append(" · «").append(tag.entryTitle()).append('»')
                        if (tag.category.isNotBlank()) append(" · ").append(tag.category)
                        append(if (tag.written > 0L) " · записана" else " · НЕ ЗАПИСАНА")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tag.written > 0L) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = { editing = tag }) { Text("Правка") }
            TextButton(onClick = { writing = tag }) {
                Text(if (tag.written > 0L) "Перезаписать" else "Записать")
            }
        }
    }

    OutlinedButton(onClick = {
        editing = NfcTag(
            id = NfcTag.newId(),
            name = "",
            act = NfcTag.ACT_TOGGLE,
            title = "",
            category = categories.firstOrNull().orEmpty(),
        )
    }) { Text("+ Новая метка") }

    Text(
        "Куда клеить, кроме туалета и кухни: на руль велосипеда и на держатель " +
            "в машине (поездка сама начинается и кончается), у входной двери " +
            "(ушёл / пришёл), на кофеварку, на дверь спальни (сон), на гантельную " +
            "стойку или коврик (тренировка), на рабочий монитор (сел за работу), " +
            "на обложку книги или на кресло для чтения, на детскую дверь (время с " +
            "Серёжей), на зарядку телефона у кровати (отбой), в прихожей на " +
            "ключницу. Метка хороша там, где дело начинается ФИЗИЧЕСКИ и всегда " +
            "в одном месте — тогда касание надёжнее памяти.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    editing?.let { tag ->
        TagDialog(
            tag = tag,
            categories = categories,
            onDismiss = { editing = null },
            onDelete = {
                scope.launch { app.settings.removeNfcTag(tag.id) }
                editing = null
            },
            onSave = { updated ->
                scope.launch { app.settings.saveNfcTag(updated) }
                editing = null
                // Новую метку сразу зовём приложить: заведённая, но не
                // записанная метка — самая бесполезная строчка в списке.
                if (updated.written == 0L) writing = updated
            },
        )
    }

    writing?.let { tag ->
        WriteDialog(
            tag = tag,
            onDone = { ok ->
                if (ok) {
                    scope.launch {
                        app.settings.saveNfcTag(tag.copy(written = System.currentTimeMillis()))
                    }
                }
                writing = null
            },
        )
    }
}

@Composable
private fun TagDialog(
    tag: NfcTag,
    categories: List<String>,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (NfcTag) -> Unit,
) {
    var name by remember(tag.id) { mutableStateOf(tag.name) }
    var title by remember(tag.id) { mutableStateOf(tag.title) }
    var category by remember(tag.id) { mutableStateOf(tag.category) }
    var act by remember(tag.id) { mutableStateOf(tag.act) }
    var resume by remember(tag.id) { mutableStateOf(tag.resume) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Метка") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Где висит: «Туалет», «Велик»") },
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название в ленте (пусто — как выше)") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Text("Что делает касание", style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (a in listOf(NfcTag.ACT_TOGGLE, NfcTag.ACT_START, NfcTag.ACT_STOP)) {
                        FilterChip(
                            selected = act == a,
                            onClick = { act = a },
                            label = { Text(NfcTag.actLabel(a)) },
                        )
                    }
                }
                if (act != NfcTag.ACT_STOP) {
                    Spacer(Modifier.height(8.dp))
                    Text("Категория", style = MaterialTheme.typography.labelMedium)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (c in categories) {
                            FilterChip(
                                selected = category == c,
                                onClick = { category = c },
                                label = { Text(c) },
                            )
                        }
                    }
                }
                if (act == NfcTag.ACT_TOGGLE) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = resume, onCheckedChange = { resume = it })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Закрыв, вернуться к прошлому делу",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Туалет и кухня — это перерывы: без возврата в ленте " +
                            "останется дыра «Не размечено».",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    tag.copy(
                        name = name.trim(),
                        title = title.trim(),
                        category = category.trim(),
                        act = act,
                        resume = resume,
                    )
                )
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Удалить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/**
 * Запись на наклейку. Режим читателя, а не «форграунд-диспетч»: он даёт
 * метку колбэком, не поднимает системный звук и, главное, перехватывает её
 * до того, как Android откроет уже записанную метку (иначе перезапись
 * запускала бы засечку вместо правки).
 */
@Composable
private fun WriteDialog(tag: NfcTag, onDone: (Boolean) -> Unit) {
    val activity = LocalContext.current.findActivity()
    var status by remember { mutableStateOf("Поднеси метку к задней стороне телефона…") }
    var ok by remember { mutableStateOf(false) }

    DisposableEffect(tag.id, activity) {
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(activity) }.getOrNull()
        if (activity == null || adapter == null) {
            status = "NFC недоступен"
            onDispose { }
        } else {
            val cb = NfcAdapter.ReaderCallback { discovered ->
                val result = writeTag(discovered, tag.id)
                activity.runOnUiThread {
                    status = result.second
                    ok = result.first
                }
            }
            runCatching {
                adapter.enableReaderMode(
                    activity, cb,
                    // Без SKIP_NDEF_CHECK намеренно: именно эта проверка и
                    // заводит у метки технологию Ndef, без неё записывать
                    // было бы нечем.
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                    null,
                )
            }
            onDispose { runCatching { adapter.disableReaderMode(activity) } }
        }
    }

    AlertDialog(
        onDismissRequest = { onDone(ok) },
        title = { Text("Записать «${tag.name.ifBlank { "метку" }}»") },
        text = {
            Column {
                Text(status)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Метка держится у телефона секунду. Подойдёт любая пустая " +
                        "NDEF-наклейка (NTAG213 и крупнее): на неё уходит меньше " +
                        "восьмидесяти байт.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onDone(ok) }) { Text("Готово") } },
    )
}

/** Compose отдаёт контекст темы, а режиму читателя нужна именно Activity. */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Сообщение метки: наш MIME-рекорд плюс AAR — чтобы её открыла именно Правка. */
private fun ndefMessage(id: String): NdefMessage = NdefMessage(
    arrayOf(
        NdefRecord.createMime(NfcTag.MIME, NfcTag.payload(id)),
        NdefRecord.createApplicationRecord("ru.zf.pravka"),
    )
)

/** true + текст, если записалось. */
private fun writeTag(discovered: Tag, id: String): Pair<Boolean, String> {
    val msg = ndefMessage(id)
    val size = msg.toByteArray().size
    val ndef = Ndef.get(discovered)
    if (ndef != null) {
        return try {
            ndef.connect()
            when {
                !ndef.isWritable -> false to "Метка защищена от записи"
                ndef.maxSize < size -> false to "Метка мала: нужно $size Б, есть ${ndef.maxSize} Б"
                else -> {
                    ndef.writeNdefMessage(msg)
                    true to "Записано ✓ Приложи ещё раз — проверить."
                }
            }
        } catch (e: Throwable) {
            false to "Не записалось: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            runCatching { ndef.close() }
        }
    }
    val fmt = NdefFormatable.get(discovered)
        ?: return false to "Такую метку записывать не умею"
    return try {
        fmt.connect()
        fmt.format(msg)
        true to "Записано ✓ Приложи ещё раз — проверить."
    } catch (e: Throwable) {
        false to "Не отформатировалось: ${e.message ?: e.javaClass.simpleName}"
    } finally {
        runCatching { fmt.close() }
    }
}
