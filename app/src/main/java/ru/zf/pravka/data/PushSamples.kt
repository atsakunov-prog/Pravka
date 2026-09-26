package ru.zf.pravka.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Образцы денежных уведомлений — для разборщиков пушей новых банков
 * (25.09.2026). Разбор пушей пишется кодом под тестами на НАСТОЯЩИХ текстах,
 * как был написан Т-Банк; у Марианны Альфа и МКБ, их текстов в коде нет.
 * Пока тумблер включён, каждое уведомление, похожее на денежное (есть сумма
 * в рублях или оно от банка), ложится сюда целиком: пакет, заголовок, текст,
 * развёрнутый текст, строки переписки. Файл отдаётся кнопкой «Поделиться
 * образцами» — из него и пишутся тесты.
 *
 * Только по тумблеру и только на своём телефоне: это чужие уведомления, и
 * копятся они, лишь пока их сознательно собирают. Потолок — последние
 * [KEEP] образцов, повторы одного и того же уведомления отсекаются.
 */
internal class PushSamples(private val context: Context) {

    private val file: File get() = File(DataRoot.dir(context), FILE)

    /** Отпечатки последних образцов: шторка переотправляет одно и то же при каждом обновлении. */
    private val recent = ArrayDeque<Int>()

    /** Похоже ли уведомление на денежное. Чистая функция — под тестом. */
    fun wanted(pkg: String, title: String, text: String): Boolean = looksLikeMoney(pkg, title, text)

    fun add(pkg: String, title: String, text: String, big: String, sub: String, lines: List<String>, ts: Long) {
        val print = (pkg + "\u0000" + title + "\u0000" + text + "\u0000" + big).hashCode()
        synchronized(recent) {
            if (print in recent) return
            recent.addLast(print)
            while (recent.size > 50) recent.removeFirst()
        }
        val line = JSONObject()
            .put("ts", ts)
            .put("pkg", pkg)
            .put("title", title)
            .put("text", text)
            .put("big", big)
            .put("sub", sub)
            .put("lines", JSONArray(lines))
            .toString()
        DiskWriter.post {
            val f = file
            f.appendText(line + "\n")
            // Потолок: вдвое перерос — оставить последние KEEP строк.
            if (f.length() > MAX_BYTES) {
                val keep = f.readLines().takeLast(KEEP)
                StoreFiles.writeAtomic(f, keep.joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun count(): Int = runCatching { file.takeIf { it.exists() }?.useLines { it.count() } ?: 0 }.getOrDefault(0)

    fun fileForShare(): File? = file.takeIf { it.exists() && it.length() > 0 }

    fun clear() {
        DiskWriter.post { runCatching { file.delete() } }
    }

    companion object {
        const val FILE = "money-push-samples.jsonl"
        private const val KEEP = 400
        private const val MAX_BYTES = 1_000_000L

        // Банки и платёжные приложения — по пакету; остальное — по сумме в тексте.
        private val BANKISH = listOf(
            "bank", "alfa", "mkb", "tinkoff", "tbank", "sber", "vtb", "raif", "gazprom",
            "otkritie", "pochta", "rshb", "sovcom", "ozon.bank", "yoomoney", "qiwi",
        )
        private val AMOUNT = Regex("""\d[\d   .,]*\s*(₽|руб|RUB|р\.)""", RegexOption.IGNORE_CASE)

        fun looksLikeMoney(pkg: String, title: String, text: String): Boolean {
            val p = pkg.lowercase()
            if (BANKISH.any { p.contains(it) }) return true
            return AMOUNT.containsMatchIn(title) || AMOUNT.containsMatchIn(text)
        }
    }
}
