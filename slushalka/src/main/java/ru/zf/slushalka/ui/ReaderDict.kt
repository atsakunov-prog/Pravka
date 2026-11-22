package ru.zf.slushalka.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.zf.slushalka.SlushalkaApp

// Словарь (23.11.2026): «посмотреть слово» - то, что есть в любой читалке.
// Своего словаря в APK нет и не нужно: на телефоне и на Boox уже стоят
// словари и переводчики, они принимают слово стандартным «Обработать текст»
// (ACTION_PROCESS_TEXT) или старым API ColorDict, который понимают GoldenDict
// и почти все офлайн-словари. Последний выбранный - первым в списке и
// открывается одним нажатием.

/** Приложение, которое умеет показать слово. */
data class DictApp(val label: String, val component: ComponentName, val colorDict: Boolean)

/** Словари и переводчики на устройстве. Своё приложение - мимо. */
fun dictApps(context: Context): List<DictApp> {
    val pm = context.packageManager
    val own = context.packageName
    fun query(intent: Intent, colorDict: Boolean): List<DictApp> = runCatching {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            if (ai.packageName == own) return@mapNotNull null
            DictApp(ri.loadLabel(pm).toString(), ComponentName(ai.packageName, ai.name), colorDict)
        }
    }.getOrDefault(emptyList())
    val process = query(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), colorDict = false)
    val color = query(Intent(COLORDICT_SEARCH), colorDict = true)
    // Одно приложение двумя путями - один раз: «Обработать текст» понятнее.
    val seen = process.map { it.component.packageName }.toHashSet()
    val all = process + color.filter { it.component.packageName !in seen }
    val last = lastDict(context)
    return all.sortedWith(compareBy({ it.component.flattenToString() != last }, { it.label.lowercase() }))
}

/** Показать слово в приложении-словаре; true - ушло. */
fun openInDict(context: Context, app: DictApp, word: String): Boolean = runCatching {
    val intent = if (app.colorDict) {
        Intent(COLORDICT_SEARCH).putExtra("EXTRA_QUERY", word).putExtra("EXTRA_FULLSCREEN", false)
    } else {
        Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, word)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    }
    intent.component = app.component
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_LAST, app.component.flattenToString()).apply()
    true
}.getOrDefault(false)

private fun lastDict(context: Context): String? =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)

private const val COLORDICT_SEARCH = "colordict.intent.action.SEARCH"
private const val PREFS = "dictionary"
private const val KEY_LAST = "last"

/**
 * Лист «Словарь»: словари устройства, Claude («что значит здесь» - с учётом
 * книги, а не словарной статьёй) и Википедия.
 */
@Composable
fun DictionarySheet(
    app: SlushalkaApp,
    word: String,
    onAskClaude: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val apps = remember { dictApps(context) }
    val shown = word.replace('\n', ' ').trim().let { if (it.length > 60) it.take(60) + "…" else it }
    PaperSheet(app = app, onClose = onClose, icon = Glyphs.Translate, title = "Словарь", subtitle = "«$shown»") {
        Spacer(Modifier.height(8.dp))
        apps.forEach { d ->
            PaperRow(Glyphs.MenuBook, d.label, hint = if (d.colorDict) "словарь" else null) {
                if (openInDict(context, d, word)) onClose()
            }
        }
        if (apps.isEmpty()) {
            PaperNote(
                "Словарей на устройстве не нашлось. На Boox словарь ставится в «Приложениях»; " +
                    "подойдут и GoldenDict, и Google Переводчик.",
                Modifier.padding(vertical = 6.dp),
            )
        }
        PaperRow(Glyphs.AutoAwesome, "Спросить Claude", hint = "Что это значит здесь, в этой книге") {
            onClose()
            onAskClaude()
        }
        PaperRow(Glyphs.TravelExplore, "Википедия", hint = "Поиск в браузере") {
            runCatching {
                val url = "https://ru.wikipedia.org/w/index.php?search=" + Uri.encode(word.trim())
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            onClose()
        }
        Spacer(Modifier.height(8.dp))
    }
}
