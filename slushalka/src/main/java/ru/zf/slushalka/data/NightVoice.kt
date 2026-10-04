package ru.zf.slushalka.data

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import ru.zf.slushalka.library.Book

/**
 * Озвучка книги нейросетью на сервере библиотеки (служба ZF-Night).
 *
 * Днём Claude готовит текст к чтению вслух, ночью его читает рассказчик на
 * видеокарте домашнего ПК, каждый кусок переслушивает whisper. Ночь - пока
 * владелец спит (по «Сну» в Засечке, иначе 02:30–07:00), книга часов на шесть
 * звука - примерно одна ночь, заказы идут по очереди.
 *
 * Заказ - файл [ORDER] в папке книги на сервере; дальше файл ведёт сервер,
 * телефон только читает статус. Главы ложатся в папку книги разом, в самом
 * конце, вместе с точной разметкой «текст ↔ звук», и через минуту появляются
 * в `index.json`: для приложения это обычная аудиокнига, недоделанную оно не
 * увидит. Признак машинной озвучки - [MARK] в папке книги (в оглавлении он
 * в `other`); скачается живая запись - сервер уберёт его вместе с машинными
 * главами, и пометка пропадёт сама.
 */
class NightVoice(
    private val settings: Settings,
    private val dir: BookDir,
    private val server: ServerLibrary,
) {

    /** Заказ озвучки, каким его видно в файле. */
    data class Order(
        val status: String,
        val by: String,
        val device: String,
        val at: Long,
        /** Озвучено кусков из [total]; у «ждёт ночи» после первой ночи тоже не ноль. */
        val done: Int = 0,
        val total: Int = 0,
        /** Часов звука - оценка сервера. */
        val hours: Double = 0.0,
        val error: String = "",
    ) {
        private val finished: Boolean get() = status == DONE || status == FAILED

        /**
         * Сервер ведёт заказ: моложе шести часов и не кончился. «Ждёт ночи»
         * длится до суток, поэтому живой заказ сервер освежает раз в час.
         */
        val alive: Boolean get() = !finished && System.currentTimeMillis() - at < FRESH_MS

        /** Служба молчит дольше шести часов: можно заказать заново, сервер продолжит с того же куска. */
        val silent: Boolean get() = !finished && !alive

        val doneOk: Boolean get() = status == DONE
        val failed: Boolean get() = status == FAILED

        val percent: Int? get() = if (total > 0) (done * 100 / total).coerceIn(0, 100) else null

        /** Что показать вместо кнопки - по статусу. */
        val line: String
            get() = when (status) {
                QUEUED -> "Заказано"
                EDITING -> "Готовлю текст"
                NIGHT -> if (done > 0 && percent != null) "Озвучено $percent%, продолжу ночью" else "Озвучу ночью"
                VOICING -> "Озвучивается" + (percent?.let { ", $it%" } ?: "")
                DONE -> "Озвучено"
                FAILED -> "Не вышло" + if (error.isNotBlank()) ": $error" else ""
                else -> status
            }

        /** «Саша (Fold)» - имя дорожки и устройство, если они разные. */
        val who: String
            get() = by.ifBlank { "кто-то" } + if (device.isNotBlank() && device != by) " ($device)" else ""

        companion object {
            /**
             * null - не JSON или это проверка самого сервера на N кусках (`limit`):
             * в библиотеку она не попадает, и для приложения такого заказа нет.
             */
            fun parse(raw: String): Order? = runCatching {
                val o = JSONObject(raw)
                if (o.has("limit") && !o.isNull("limit")) return null
                val progress = o.optJSONObject("progress")
                Order(
                    status = o.optString("status").trim(),
                    by = o.optString("by").trim(),
                    device = o.optString("device").trim(),
                    at = o.optLong("at"),
                    done = progress?.optInt("done") ?: 0,
                    total = progress?.optInt("total") ?: 0,
                    hours = o.optDouble("hours", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    error = o.optString("error").trim(),
                )
            }.getOrNull()
        }
    }

    /** Прикидка до заказа: часов звука, ночей и сколько стоит правка текста Claude. */
    data class Estimate(val hours: Double, val nights: Int, val usd: Double) {
        /** Подпись под кнопкой - словами сервера. */
        val caption: String
            get() {
                val h = String.format(RU, "%.1f", hours)
                val usdText = String.format(RU, "%.1f", usd)
                return if (nights <= 1) {
                    "Озвучит нейросетью ночью: ~$h ч звука, 1 ночь, правка текста ~$usdText $. " +
                        "Утром книга сама появится с аудио."
                } else {
                    "Озвучит нейросетью за $nights ${nightsWord(nights)}: ~$h ч звука, правка текста ~$usdText $. " +
                        "Сама появится, когда будет готова."
                }
            }
    }

    private val _orders = MutableStateFlow<Map<String, Order>>(emptyMap())
    /** Заказы по папкам книг, какими их последний раз видели на сервере. */
    val orders: StateFlow<Map<String, Order>> = _orders

    fun orderFor(book: Book): Order? = _orders.value[ServerLibrary.folderKey(book.folderName)]

    private fun entry(book: Book): ServerLibrary.ServerBook? = server.index.value?.byFolder(book.folderName)

    /**
     * Можно ли заказать: книга домашней библиотеки, у сервера есть текст и нет
     * звука, и своего звука нет на телефоне. Где звук уже есть - живой или
     * машинный, - сервер откажет: заменить плохую запись машинной пока нельзя.
     */
    fun canOrder(book: Book): Boolean {
        if (!settings.now().cloudReady || book.hasAudio) return false
        val sb = entry(book) ?: return false
        return sb.text.isNotEmpty() && sb.audio.isEmpty()
    }

    /** Озвучено нейросетью: в папке книги на сервере лежит [MARK]. */
    fun isMachine(book: Book): Boolean = entry(book)?.machineVoiced == true

    /** Заказ книги на сервере; null - заказа нет, нет сети или это проверка сервера. */
    suspend fun orderOf(book: Book): Order? {
        val key = ServerLibrary.folderKey(book.folderName)
        val o = dir.readServer(book, ORDER)?.let { Order.parse(it) }
        _orders.update { if (o == null) it - key else it + (key to o) }
        return o
    }

    /** Заказать (или заказать заново поверх замолчавшего и неудачного): полным объектом. */
    suspend fun order(book: Book): Result<Order> {
        val p = settings.now()
        val now = System.currentTimeMillis()
        val order = Order(QUEUED, p.profile.ifBlank { "без имени" }, p.device, now)
        val body = JSONObject()
            .put("status", order.status)
            .put("by", order.by)
            .put("device", order.device)
            .put("at", now)
            .put("ordered", now)
            .toString()
        if (!dir.writeServer(book, ORDER, body)) {
            return Result.failure(IllegalStateException("Заказ не лёг на сервер - нет связи с облаком?"))
        }
        _orders.update { it + (ServerLibrary.folderKey(book.folderName) to order) }
        return Result.success(order)
    }

    companion object {
        /** Заказ озвучки в папке книги, рядом с заказом разбора. */
        const val ORDER = "слушалка-озвучка.заказ.json"

        /** Признак машинной озвучки в папке книги. */
        const val MARK = ServerLibrary.MACHINE_MARK

        const val QUEUED = "заказан"
        const val EDITING = "правка"
        const val NIGHT = "ждёт ночи"
        const val VOICING = "озвучивается"
        const val DONE = "готово"
        const val FAILED = "ошибка"

        /** Заказ моложе этого сервер ведёт - то же правило, что у разбора. */
        const val FRESH_MS = 6 * 3600_000L

        private val RU: Locale = Locale.forLanguageTag("ru")

        /**
         * Формулы сервера: звук - знаков / 13 в секунду; ночь вмещает около
         * шести часов звука, с запасом в десятую; платится только правка
         * текста Claude пакетом. «Этюд в багровых тонах» - 4,6 ч, 1 ночь, ~0,7 $.
         */
        fun estimate(chars: Int): Estimate {
            val hours = chars / 13.0 / 3600.0
            val nights = maxOf(1, kotlin.math.ceil(hours * 1.1 / 6.0).toInt())
            return Estimate(hours, nights, chars * 0.0000032)
        }

        private fun nightsWord(n: Int): String = when {
            n % 10 == 1 && n % 100 != 11 -> "ночь"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "ночи"
            else -> "ночей"
        }
    }
}
