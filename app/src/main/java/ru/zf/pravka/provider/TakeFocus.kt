package ru.zf.pravka.provider

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Музыка на паузу, пока идёт тейк (владелец, 30.09.2026: «когда я нажимаю на
 * кнопку микрофона, то всё ещё играет музыка в наушниках»).
 *
 * Системный фокус звука «на время, целиком» (`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`,
 * назначение «помощник») — ровно так делает Ассистент: плеер, потерявший
 * фокус на время, встаёт на паузу, а получив его обратно, играет дальше сам.
 * Держит его тейк любого режима и любого движка; звуки «говори» и «принял»
 * играет сама Правка, фокус им не мешает.
 *
 * Отпускаем не на самом стопе, а после «принял» ([STOP_RELEASE_MS]): музыка,
 * вернувшаяся поверх щелчка, его заглушила бы. Тейки кончаются десятком
 * дорог (стоп, отмена, ошибка движка, десять минут без слов), поэтому, кроме
 * стопа и конца сессии, фокус снимает и тик службы ([tick]), если тейка уже
 * нет, — музыка не останется на паузе навсегда.
 *
 * Всё — на главном потоке: запрос фокуса лёгкий, а один поток снимает гонки
 * между «держать» нового тейка и «отпустить» прошлого.
 */
object TakeFocus {

    private val main = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null
    private var appContext: Context? = null
    private var log: (String) -> Unit = {}

    /** Когда тик впервые увидел держащий фокус без тейка (0 — не видел). */
    private var idleSinceMs = 0L

    /** Когда назначено «отпустить» (elapsedRealtime; 0 — не назначено). */
    private var releaseAtMs = 0L

    private val letGoNow = Runnable { abandon("тейк кончился") }

    /** Взять фокус на тейк: музыка встаёт на паузу. Второй раз подряд — ничего, только снимает отложенное «отпустить». */
    fun hold(context: Context, log: (String) -> Unit = {}) = onMain {
        main.removeCallbacks(letGoNow)
        releaseAtMs = 0L
        idleSinceMs = 0L
        if (request != null) return@onMain
        val app = context.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@onMain
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            // Отняли на время (звонок) — тейк идёт своим ходом; вернут — ничего не делаем.
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) {
                    request = null
                    log("фокус звука забрали насовсем — музыка может заиграть")
                }
            }, main)
            .build()
        val granted = runCatching { am.requestAudioFocus(req) }.getOrElse {
            log("фокус звука: ${it.javaClass.simpleName}: ${it.message}")
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            request = req
            appContext = app
            this.log = log
            log("музыка на паузе: фокус звука у тейка")
        } else {
            log("фокус звука не дали — музыка может играть дальше")
        }
    }

    /**
     * Отпустить через [delayMs]: музыка вернётся. Уже назначенное позже не
     * приближает — конец сессии сразу за стопом не должен вернуть музыку
     * поверх «принял». Новый [hold] до срока отменяет.
     */
    fun release(delayMs: Long = 0L) = onMain {
        if (request == null) return@onMain
        val at = SystemClock.elapsedRealtime() + delayMs
        if (releaseAtMs != 0L && releaseAtMs >= at) return@onMain
        main.removeCallbacks(letGoNow)
        releaseAtMs = at
        if (delayMs <= 0L) abandon("тейк кончился") else main.postDelayed(letGoNow, delayMs)
    }

    /**
     * Тик службы (раз в две секунды): фокус держится, а тейка нет уже
     * [IDLE_RELEASE_MS] — отпустить. Страховка на случай дороги, которая
     * кончила тейк мимо [release].
     */
    fun tick(takeRunning: Boolean) = onMain {
        if (request == null || takeRunning) {
            idleSinceMs = 0L
            return@onMain
        }
        val now = SystemClock.elapsedRealtime()
        if (idleSinceMs == 0L) {
            idleSinceMs = now
            return@onMain
        }
        if (now - idleSinceMs >= IDLE_RELEASE_MS) abandon("тейка нет, а фокус держался — отпускаю")
    }

    private fun abandon(why: String) {
        main.removeCallbacks(letGoNow)
        releaseAtMs = 0L
        val req = request ?: return
        request = null
        idleSinceMs = 0L
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching { am?.abandonAudioFocusRequest(req) }
        log("музыка вернулась: фокус звука отпущен ($why)")
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /**
     * Через сколько после стопа отпускать: хвост записи (300 мс), «принял» с
     * паузой после кнопки гарнитуры (250 мс) и сам щелчок (~0,5 с) — с запасом.
     */
    const val STOP_RELEASE_MS = 1_300L

    /** Тейк кончился сам (ошибка, тишина) — музыку вернуть почти сразу. */
    const val END_RELEASE_MS = 400L

    /** Сколько тик терпит фокус без тейка. */
    private const val IDLE_RELEASE_MS = 3_000L
}
