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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.IconBadge
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.SheetAction

/**
 * Пояснение раздела — за «i» у плашки «метки nfc» в настройках Засечки
 * (24.09.2026): раньше здесь стояли строка под заголовком, своя «i» и абзац
 * «куда клеить» в конце списка — теперь на плашке только метки.
 */
internal val NFC_INFO =
    "Засечка без телефона в руках: приложил — дело началось или кончилось. " +
        "Наклейка на стене: приложил, зайдя " +
        "в туалет, приложил, выйдя. Работает и с погашенным экраном на " +
        "заблокированном телефоне. На саму метку уходит только номер: что " +
        "она делает, задаётся здесь и меняется без перезаписи наклейки.\n\n" +
        "Куда клеить, кроме туалета и кухни: на руль велосипеда и на держатель " +
        "в машине (поездка сама начинается и кончается), у входной двери " +
        "(ушёл / пришёл), на кофеварку, на дверь спальни (сон), на гантельную " +
        "стойку или коврик (тренировка), на рабочий монитор (сел за работу), " +
        "на обложку книги или на кресло для чтения, на детскую дверь (время с " +
        "Серёжей), на зарядку телефона у кровати (отбой), в прихожей на " +
        "ключницу. Метка хороша там, где дело начинается ФИЗИЧЕСКИ и всегда " +
        "в одном месте — тогда касание надёжнее памяти."

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

    // Заголовок «метки nfc» и пояснение (NFC_INFO) — у плашки снаружи.
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
            Spacer(Modifier.height(6.dp))
            PaperButton("Включить NFC", icon = Glyphs.Nfc, onClick = {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_NFC_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            })
            Spacer(Modifier.height(6.dp))
        }
    }

    // Строка метки: имя, что делает и записана ли; справа «править» и
    // «записать» значками (24.09.2026) — две кнопки словами оставляли имени
    // метки полстроки.
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
            GlyphButton(Glyphs.Edit, "править метку", onClick = { editing = tag }, size = 36.dp)
            GlyphButton(
                Glyphs.Nfc,
                if (tag.written > 0L) "перезаписать метку" else "записать метку",
                onClick = { writing = tag },
                // Незаписанная — краской режима: это единственное, что с ней
                // осталось сделать.
                tint = if (tag.written > 0L) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                size = 36.dp,
            )
        }
    }

    Spacer(Modifier.height(6.dp))
    PaperButton("Новая метка", icon = Glyphs.Plus, onClick = {
        editing = NfcTag(
            id = NfcTag.newId(),
            name = "",
            act = NfcTag.ACT_TOGGLE,
            title = "",
            category = categories.firstOrNull().orEmpty(),
        )
    })

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

/**
 * Метка: где висит, что делает касание, категория. Лист набора (24.09.2026):
 * «Удалить» — корзиной слева, «Отмену» заменили крестик и свайп.
 */
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

    PaperAlert(
        onDismiss = onDismiss,
        title = "Метка",
        icon = Glyphs.Nfc,
        subtitle = tag.name.ifBlank { null },
        confirm = SheetAction("Сохранить") {
            onSave(
                tag.copy(
                    name = name.trim(),
                    title = title.trim(),
                    category = category.trim(),
                    act = act,
                    resume = resume,
                )
            )
        },
        destructive = SheetAction("удалить метку", onClick = onDelete),
    ) {
        PaperField(value = name, onValueChange = { name = it }, label = "Где висит: «Туалет», «Велик»")
        PaperField(value = title, onValueChange = { title = it }, label = "Название в ленте (пусто — как выше)")
        Text("Что делает касание", style = MaterialTheme.typography.labelMedium)
        ChipRow {
            for (a in listOf(NfcTag.ACT_TOGGLE, NfcTag.ACT_START, NfcTag.ACT_STOP)) {
                PaperChip(NfcTag.actLabel(a), selected = act == a, onClick = { act = a })
            }
        }
        if (act != NfcTag.ACT_STOP) {
            Text("Категория", style = MaterialTheme.typography.labelMedium)
            ChipRow {
                for (c in categories) {
                    PaperChip(c, selected = category == c, onClick = { category = c })
                }
            }
        }
        if (act == NfcTag.ACT_TOGGLE) {
            PaperToggle(
                title = "Закрыв, вернуться к прошлому делу",
                checked = resume,
                onCheckedChange = { resume = it },
                info = "Туалет и кухня — это перерывы: без возврата в ленте " +
                    "останется дыра «Не размечено».",
            )
        }
    }
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

    // Лист набора (24.09.2026). Закрыть — крестиком, свайпом или «Готово»:
    // все три отдают, записалась ли метка, как прежде onDismissRequest.
    PaperAlert(
        onDismiss = { onDone(ok) },
        title = "Записать «${tag.name.ifBlank { "метку" }}»",
        icon = Glyphs.Nfc,
        confirm = SheetAction("Готово") { onDone(ok) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (ok) Glyphs.Check else Glyphs.Nfc, size = 34.dp)
            Spacer(Modifier.width(12.dp))
            Text(status, style = MaterialTheme.typography.bodyLarge)
        }
        PaperHint(
            "Метка держится у телефона секунду. Подойдёт любая пустая " +
                "NDEF-наклейка (NTAG213 и крупнее): на неё уходит меньше " +
                "восьмидесяти байт."
        )
    }
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
