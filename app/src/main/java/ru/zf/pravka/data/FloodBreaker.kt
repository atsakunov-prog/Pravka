package ru.zf.pravka.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Interceptor
import org.json.JSONObject
import ru.zf.pravka.R
import ru.zf.pravka.core.FloodGuard

/**
 * Рубильник защиты от флуда (13.10.2026, `core/FloodGuard.kt` — пороги и
 * почему такие). Стоит перехватчиком на общем http-клиенте, поэтому видит
 * КАЖДЫЙ POST на api.anthropic.com — дороги `ClaudeProvider`, батчи, поиск
 * словаря, — какой бы код его ни слал. Сработал — запросы к Claude стоят
 * ([FloodGuard.Blocked], ошибка ввода-вывода: что умеет ждать сеть, дождётся
 * включения), и владельцу вопрос: плашкой «П» и громким пушем «Защита от
 * флуда сработала… Остановить?». Галочка (и молчание) — стоять; крестик —
 * «всё ок»: включить, и час пороги втрое. Остановленное включается и в
 * настройках, группа «Модели».
 *
 * Сработавший рубильник переживает перезапуск процесса (`flood.json`):
 * петля, которая роняет приложение и поднимается заново, иначе начинала бы
 * каждый раз с чистого счёта.
 */
class FloodBreaker(
    private val context: Context,
    private val eventLog: EventLog,
    /** Имя дороги по-человечески: «Телефон в ленте», «Чистка диктовки». */
    private val routeName: (String) -> String,
    /** Спросить плашкой (зовётся с потока запроса — сам переложит на главный). */
    private val ask: (text: String, onStop: () -> Unit, onOk: () -> Unit) -> Unit = { _, _, _ -> },
) {

    companion object {
        private const val FILE_NAME = "flood.json"
        private const val CHANNEL = "pravka_flood"
        private const val NOTIF_ID = 0x0F100D
        const val WHAT_STOP = "flood_stop"
        const val WHAT_OK = "flood_ok"
    }

    private val hits = ArrayList<FloodGuard.Hit>()
    @Volatile private var loaded = false
    @Volatile private var relaxUntil = 0L

    /** Почему стоит (словами); null — запросы идут. */
    private val _stopped = MutableStateFlow<String?>(null)
    val stopped: StateFlow<String?> = _stopped

    private val file: File get() = File(DataRoot.dir(context), FILE_NAME)

    /** Перехватчик общего клиента: считает POST на Anthropic, сработавший — не пускает. */
    fun interceptor(): Interceptor = Interceptor { chain ->
        val req = chain.request()
        if (req.method == "POST" && req.url.host == "api.anthropic.com") {
            val route = req.tag(FloodGuard.Route::class.java)?.key
                ?: if (req.url.encodedPath.contains("batches")) "batch" else ""
            admit(route)?.let { throw FloodGuard.Blocked(it) }
        }
        chain.proceed(req)
    }

    /** Пропустить запрос [route] или назвать, почему нельзя. */
    @Synchronized
    private fun admit(route: String): String? {
        ensureLoaded()
        _stopped.value?.let { return blockedWords(it) }
        val now = System.currentTimeMillis()
        val kept = FloodGuard.prune(hits, now)
        hits.clear()
        hits.addAll(kept)
        val trip = FloodGuard.check(hits, route, now, relaxed = now < relaxUntil)
        hits.add(FloodGuard.Hit(now, route))
        if (trip == null) return null
        val why = FloodGuard.words(trip, routeName)
        _stopped.value = why
        persist(why)
        runCatching { eventLog.add("защита от флуда: сработала — $why; запросы к Claude остановлены") }
        runCatching { notifyTrip(why) }
        runCatching { ask("Защита от флуда сработала: $why. Остановить запросы к Claude?", { answer(stop = true) }, { answer(stop = false) }) }
        return blockedWords(why)
    }

    private fun blockedWords(why: String): String =
        "Запросы к Claude остановлены защитой от флуда ($why). Всё в порядке — крестик на плашке, " +
            "«Всё ок» в пуше или «Включить запросы» в Настройках → Модели."

    /**
     * Ответ владельца: [stop] — галочка «да, остановить» (стоять до включения
     * руками), иначе крестик «всё ок»: включить, и час пороги втрое.
     */
    @Synchronized
    fun answer(stop: Boolean) {
        ensureLoaded()
        cancelNotif()
        val why = _stopped.value ?: return
        if (stop) {
            runCatching { eventLog.add("защита от флуда: остановлено тобой — $why") }
            return
        }
        _stopped.value = null
        hits.clear()
        relaxUntil = System.currentTimeMillis() + FloodGuard.RELAX_MS
        persist(null)
        runCatching { eventLog.add("защита от флуда: «всё ок» — запросы идут, час пороги втрое") }
    }

    private fun notifyTrip(why: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Защита от флуда", NotificationManager.IMPORTANCE_HIGH))
        }
        fun act(label: String, what: String, code: Int): Notification.Action {
            val intent = Intent(context, ru.zf.pravka.trigger.AutoPilotActivity::class.java)
                .putExtra(ru.zf.pravka.trigger.AutoPilotActivity.EXTRA_WHAT, what)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Action.Builder(null, label, pi).build()
        }
        val text = "$why. Запросы к Claude остановлены. «Остановить» — так и оставить; «Всё ок» — включить."
        val n = Notification.Builder(context, CHANNEL)
            .setContentTitle("⛔ Защита от флуда сработала")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_tile)
            .setAutoCancel(true)
            .addAction(act("Остановить", WHAT_STOP, 0x0F1))
            .addAction(act("Всё ок", WHAT_OK, 0x0F2))
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private fun cancelNotif() {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
    }

    private fun ensureLoaded() {
        if (loaded) return
        StoreFiles.readOrQuarantine(file) { JSONObject(it) }?.let { o ->
            o.optString("stopped").takeIf { it.isNotBlank() }?.let { _stopped.value = it }
        }
        loaded = true
    }

    /** Прочитать состояние для настроек. */
    @Synchronized
    fun load() = ensureLoaded()

    private fun persist(why: String?) {
        val o = JSONObject().put("format", "pravka-flood").put("stopped", why.orEmpty()).put("at", System.currentTimeMillis())
        runCatching { StoreFiles.writeAtomic(file, o.toString()) }
    }
}
