package ru.zf.slushalka.ask

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.zf.slushalka.data.BookDir
import ru.zf.slushalka.data.RazborStore
import ru.zf.slushalka.data.ServerLibrary
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.text.BookText

/**
 * Разбор книги: читает готовый с сервера и заказывает новый.
 *
 * Считает разбор не телефон, а сервер библиотеки на домашнем ПК: Опус
 * получает книгу целиком, кладёт её в кэш и подряд решает задачи. Готовое
 * ложится в папку книги двумя файлами - [FILE] (это здесь) и справочником в
 * его обычном формате (его подхватывает GuideEngine, серверный побеждает
 * свой старый).
 *
 * Заказ - файл [ORDER] в папке книги на сервере. Сервер раз в двадцать секунд
 * ищет такие файлы, считает по одному и пишет в тот же файл статус:
 * «заказан» → «готовится» → «готово» или «ошибка». Поэтому заказать можно
 * только книгу домашней библиотеки ([canOrder]): сервер разбирает текст из
 * своей папки.
 */
class RazborEngine(
    private val settings: Settings,
    private val store: RazborStore,
    private val dir: BookDir,
    private val guide: GuideEngine,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, Razbor>>(emptyMap())
    /** Разборы книг, которые подходят к своему тексту; нет книги в карте - нет и листа «Разбор». */
    val states: StateFlow<Map<String, Razbor>> = _states

    private val _orders = MutableStateFlow<Map<String, Order>>(emptyMap())
    /** Заказы, какими их последний раз видели на сервере. */
    val orders: StateFlow<Map<String, Order>> = _orders

    /** Заказ разбора: кто, с какого устройства, когда, и что о нём пишет сервер. */
    data class Order(
        val status: String,
        val by: String,
        val device: String,
        val at: Long,
        val usd: Double = 0.0,
        val error: String = "",
    ) {
        /** Сервер его ещё считает - и заказ не брошен: второй не нужен. */
        val waiting: Boolean
            get() = (status == QUEUED || status == WORKING) && System.currentTimeMillis() - at < ORDER_FRESH_MS

        val done: Boolean get() = status == DONE
        val failed: Boolean get() = status == FAILED

        /** «Саша (Fold)» - имя дорожки и устройство, если они разные. */
        val who: String
            get() = by.ifBlank { "кто-то" } + if (device.isNotBlank() && device != by) " ($device)" else ""

        fun toJson(): JSONObject = JSONObject()
            .put("status", status)
            .put("by", by)
            .put("device", device)
            .put("at", at)

        companion object {
            fun parse(raw: String): Order? = runCatching {
                val o = JSONObject(raw)
                Order(
                    status = o.optString("status").trim(),
                    by = o.optString("by").trim(),
                    device = o.optString("device").trim(),
                    at = o.optLong("at"),
                    usd = o.optDouble("usd", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    error = o.optString("error").trim(),
                )
            }.getOrNull()
        }
    }

    fun of(bookId: String): Razbor? = _states.value[bookId]

    /**
     * Есть ли у книги разбор - не открывая её, для карточки на полке: его уже
     * читали (копия на телефоне) или сервер назвал файл в оглавлении.
     */
    fun known(book: Book, server: ServerLibrary.ServerBook?): Boolean =
        _states.value.containsKey(book.id) || store.has(book.id) || server?.other?.any { it.path == FILE } == true

    /** Книга домашней библиотеки: её папка есть на сервере, и разобрать её может он. */
    fun canOrder(book: Book): Boolean = dir.onServer(book)

    /**
     * Свериться с папкой книги: свежий разбор с сервера (он же ложится копией
     * на телефон), без сети - своя копия. Разбор к другому тексту не годится:
     * его главы и знаки не наши.
     */
    suspend fun sync(book: Book, text: BookText): Razbor? = withContext(Dispatchers.IO) {
        val raw = dir.read(book, FILE)
        val fresh = raw?.let { Razbor.parse(it) }?.takeIf { it.fit.fits(book, text) }
        if (fresh != null) {
            if (raw != store.load(book.id)) store.save(book.id, raw)
            return@withContext publish(book.id, fresh)
        }
        val cached = store.load(book.id)?.let { Razbor.parse(it) }?.takeIf { it.fit.fits(book, text) }
        if (cached != null) return@withContext publish(book.id, cached)
        _states.update { it - book.id }
        null
    }

    private fun publish(bookId: String, r: Razbor): Razbor {
        // Тот же разбор второй раз не публикуется: лист не перерисуется зря.
        val known = _states.value[bookId]
        if (known != null && known.created == r.created && known.fit == r.fit) return known
        _states.update { it + (bookId to r) }
        return r
    }

    /**
     * Сколько заплатит сервер - его же формулой: книга один раз пишется в кэш
     * (вход с надбавкой в четверть), восемь задач читают её из кэша, ответы -
     * по цене выхода. Около 1,9 $ за «Джорджа», 3 $ за том Акунина.
     */
    fun estimate(text: BookText): Double = estimateUsd(text.length, text.chapters.size)

    /** Заказ книги на сервере, каким он там лежит; null - заказа нет (или сети). */
    suspend fun orderOf(book: Book): Order? {
        val o = dir.readServer(book, ORDER)?.let { Order.parse(it) } ?: return null
        _orders.update { it + (book.id to o) }
        return o
    }

    /**
     * Заказать разбор. Сервер уже считает свежий заказ (свой, Марианны,
     * панели «Книги») - второй не кладётся, возвращается тот. После заказа
     * движок сам следит за статусом и забирает готовое.
     */
    suspend fun order(book: Book, text: BookText): Result<Order> {
        orderOf(book)?.takeIf { it.waiting }?.let { known ->
            watch(book, text)
            return Result.success(known)
        }
        val p = settings.now()
        val order = Order(QUEUED, p.profile.ifBlank { "без имени" }, p.device, System.currentTimeMillis())
        val ok = withContext(Dispatchers.IO) { dir.writeServer(book, ORDER, order.toJson().toString()) }
        if (!ok) return Result.failure(IllegalStateException("Заказ не лёг на сервер - нет связи с облаком?"))
        _orders.update { it + (book.id to order) }
        watch(book, text)
        return Result.success(order)
    }

    /** Открыли книгу: заказ ещё считается - следить за ним, чтобы готовое появилось само. */
    fun checkOrder(book: Book, text: BookText) {
        if (!canOrder(book)) return
        scope.launch { if (orderOf(book)?.waiting == true) watch(book, text) }
    }

    private val watching = ConcurrentHashMap.newKeySet<String>()

    /**
     * Следить за заказом раз в минуту, пока он считается: «готово» - перечитать
     * оба файла, разбор и справочник; «ошибка» - перестать. Дольше [WATCH_MS]
     * не следим: сервер обычно укладывается в десять минут, а если очередь
     * длинная - готовое заберёт следующее открытие книги.
     */
    private fun watch(book: Book, text: BookText) {
        if (!watching.add(book.id)) return
        scope.launch {
            try {
                val until = System.currentTimeMillis() + WATCH_MS
                while (System.currentTimeMillis() < until) {
                    delay(POLL_MS)
                    // Сети нет - не повод бросать: связь вернётся, а заказ на месте.
                    val o = orderOf(book) ?: continue
                    if (o.done) {
                        sync(book, text)
                        guide.sync(book, text)
                        break
                    }
                    if (!o.waiting) break
                }
            } finally {
                watching.remove(book.id)
            }
        }
    }

    companion object {
        /** Цена разбора по объёму текста и числу глав - см. [estimate]. */
        fun estimateUsd(chars: Int, chapters: Int): Double {
            val m = Models.OPUS
            val bookTokens = chars / 2.2
            val outTokens = 45_000.0 + 300.0 * chapters.coerceAtLeast(1)
            return (bookTokens * (m.input * 1.25 + TASKS * m.cacheRead) + outTokens * m.output) / 1_000_000.0
        }

        /** Разбор в папке книги - рядом со справочником. */
        const val FILE = "слушалка-разбор.json"

        /** Заказ разбора в папке книги на сервере; статус в него пишет сервер. */
        const val ORDER = "слушалка-разбор.заказ.json"

        const val QUEUED = "заказан"
        const val WORKING = "готовится"
        const val DONE = "готово"
        const val FAILED = "ошибка"

        /** Заказ моложе этого ждём; старше - брошен, можно заказывать заново. */
        const val ORDER_FRESH_MS = 6 * 3600_000L

        /** Задач у разборщика сервера: столько раз книга читается из кэша. */
        private const val TASKS = 8

        private const val POLL_MS = 60_000L
        private const val WATCH_MS = 45 * 60_000L
    }
}
