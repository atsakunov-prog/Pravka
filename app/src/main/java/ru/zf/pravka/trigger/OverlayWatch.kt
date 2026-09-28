package ru.zf.pravka.trigger

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.zf.pravka.core.OverlaySight

/**
 * Сторож «диск висит, а его не видно» (владелец, 28.09.2026, Android 17 на
 * Fold: «диск не перерисовывается, если сложить экран, и он пропадает… и
 * помогает только перезапустить службу»). Почему свои флаги тут бессильны и
 * откуда берётся правда — `core/OverlaySight.kt`.
 *
 * Когда смотрит: через шесть секунд после старта службы (заодно проверка
 * проверяет сама себя: видит ли она наши окна, когда они заведомо на экране),
 * через три секунды после каждого складывания и при разблокировке, если
 * после складывания ещё не смотрела. Складывание с Fold запирает экран, а на
 * замке проверка молчит: судить надо о том, что видит владелец, открыв
 * телефон.
 *
 * Что делает, если системе наших окон не видно: смотрит ещё раз через две
 * секунды (переход складывания мог ещё идти) и перезапускает службу — ровно
 * то, чем владелец лечил это руками; система поднимает службу сама, как после
 * переезда базы. Перевешивать окна бесполезно: в 697 перевешивание трижды
 * ничего не дало, а перезапуск вернул диск сразу. Перезапуск — только если
 * проверка в эту жизнь службы уже видела наши окна, ничего не пишется и не
 * ждёт ответа Claude, приложение не на экране и прошлый самоперезапуск был
 * больше трёх минут назад. Иначе — уведомление словами с дорогой в
 * настройки служб (правило 6: молчаливая механика читается как поломка).
 * После самоперезапуска — ещё и сверка, что диск расставился: в 697 новая
 * служба подняла кнопки, а место диска не прочиталось, и они стояли
 * столбиком у края до следующего складывания.
 *
 * Список окон система отдаёт службе только с флагом
 * `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`. Постоянно он не нужен и стоит системе
 * слежки за окнами, поэтому поднимается на один взгляд (меньше секунды) и
 * сразу опускается; на складывание — опускается немедленно. Это не подписка
 * на оконные события и не подглядывание за чужими окнами: события окон служба
 * не получает (их нет в её типах событий), в чужие процессы не ходит — только
 * одна выписка у системы, в которой ищутся свои квадраты.
 */
internal class OverlayWatch(private val service: PravkaAccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private fun log(line: String) = service.app.eventLog.add(line)

    /** Взгляд в эту жизнь службы уже видел наши окна — его «не вижу» можно верить. */
    private var trusted = false

    /**
     * На чистом старте взгляд наших окон не нашёл — он слеп (или система
     * перестала называть оверлеи), и дальше его «не вижу» ничего не значит.
     */
    private var blind = false

    /**
     * Окна повешены при открытом телефоне, и с тех пор не складывали: сейчас
     * они на экране заведомо, так что взгляд проверяет сам себя.
     */
    private var cleanStart = false

    /** После складывания ещё не смотрели (или смотрели и не увидели) — разблокировка посмотрит. */
    private var dirty = false

    /** Последний раз окна вешались на замке или при погасшем экране — в журнал к итогу. */
    private var hungLocked = false

    /** Проверка, отложенная до разблокировки (на замке не судим), — её повод. */
    private var waiting: String? = null

    private var laterTries = 0
    private var unknownTries = 0
    private var told = false

    /** Метка прошлого самоперезапуска (из настроек, в памяти — читать её на главном потоке нельзя). */
    private var lastRestartAt = 0L

    private var planned: Runnable? = null
    private var looking: Runnable? = null
    private var flagRaised = false
    private var receiver: BroadcastReceiver? = null

    fun start() {
        service.scope.launch {
            val at = runCatching { service.app.settings.overlayRestartAt() }.getOrDefault(0L)
            lastRestartAt = at
            if (at > 0 && System.currentTimeMillis() - at < RESTARTED_MS) {
                log("окна: служба поднята после самоперезапуска — смотрю, вернулся ли диск")
            }
        }
        val rec = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) onUnlocked()
            }
        }
        receiver = rec
        // Окна службы вешаются в эти же мгновения: на замке или при погасшем
        // экране старт не «чистый» — такие окна и есть подозреваемые.
        cleanStart = !locked() && interactive()
        hungLocked = !cleanStart
        runCatching {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= 33) {
                service.registerReceiver(rec, filter, Context.RECEIVER_EXPORTED)
            } else {
                service.registerReceiver(rec, filter)
            }
        }
        plan(START_CHECK_MS) { check(START_REASON) }
    }

    fun stop() {
        planned?.let { handler.removeCallbacks(it) }
        planned = null
        dropLook()
        receiver?.let { runCatching { service.unregisterReceiver(it) } }
        receiver = null
    }

    /** Складывание началось: ни взгляда посреди него, ни флага окон — система и так занята. */
    fun onFoldStart() {
        planned?.let { handler.removeCallbacks(it) }
        planned = null
        dropLook()
    }

    /** Отчёт после складывания прошёл — посмотреть, когда переход точно кончился. */
    fun afterFold() {
        plan(FOLD_CHECK_MS) { check("после складывания") }
    }

    /** `configSettled`: окна повешены заново — после складывания на них надо посмотреть. */
    fun onSettled() {
        dirty = true
        cleanStart = false
        hungLocked = locked() || !interactive()
        waiting = null
        laterTries = 0
        unknownTries = 0
    }

    private fun onUnlocked() {
        // Смотрим, только если есть на что: после складывания, отложенное
        // с замка и ещё не проверенный старт. Остальные разблокировки — мимо:
        // флаг окон на каждую из них не нужен.
        val reason = waiting ?: when {
            dirty -> "после разблокировки"
            !trusted && !blind -> START_REASON
            else -> return
        }
        waiting = null
        // Замок уходит анимацией — смотреть, когда она кончилась.
        plan(UNLOCK_CHECK_MS) { check(reason) }
    }

    /**
     * Посмотреть, видит ли система наши окна, и решить, что дальше
     * (`OverlaySight.next`). [confirmed] — это второй взгляд после «не вижу».
     */
    fun check(reason: String, confirmed: Boolean = false) {
        planned?.let { handler.removeCallbacks(it) }
        planned = null
        if (service.folding || looking != null) return
        if (!interactive() || locked()) {
            // На замке не судим: дождёмся, пока владелец откроет телефон.
            // Подтверждение при этом начинается заново: окна видны или нет —
            // решит свежий взгляд после разблокировки.
            waiting = reason
            return
        }
        look { listed, overlays ->
            if (service.folding) return@look
            val ours = service.overlayBoxes()
            val (w, h) = service.screenSize()
            val look = OverlaySight.Look(
                expected = OverlaySight.expected(ours, w, h),
                seen = OverlaySight.seen(ours, overlays, w, h, slack = SLACK_PX),
                listed = listed,
            )
            if (look.seen > 0) trusted = true
            val clean = cleanStart
            val step = OverlaySight.next(
                look = look,
                cleanStart = clean,
                blind = blind,
                trusted = trusted,
                idle = service.overlayIdle(),
                sinceRestartMs = System.currentTimeMillis() - lastRestartAt,
                laterTries = laterTries,
                confirmed = confirmed,
            )
            val where = if (hungLocked) "$reason; окна вешались на замке" else reason
            val census = if (look.seen == 0 && look.expected > 0) {
                "; окон в списке системы $listed, оверлеев ${overlays.size}"
            } else ""
            val head = "окна: система видит ${look.seen} из ${look.expected} ($where)$census"
            // Несостоявшийся взгляд старт «чистым» оставляет: повтор судит так же.
            if (step != OverlaySight.Step.UNKNOWN) cleanStart = false
            when (step) {
                OverlaySight.Step.FINE -> {
                    dirty = false
                    laterTries = 0
                    // Сдавался, а окна вернулись (следующее складывание, ручной
                    // перевес) — уведомление о пропаже больше не правда.
                    if (told) {
                        told = false
                        runCatching { service.getSystemService(android.app.NotificationManager::class.java).cancel(NOTIF_ID) }
                    }
                    // Молчать о здоровом: в журнал — только старт, складывание
                    // и то, что было после «не вижу».
                    if (reason != QUIET_REASON) log(head + service.diskWords())
                    // Окна видны, а диск не расставлен (после самоперезапуска
                    // место не прочиталось) — расставить сейчас, не ждать
                    // минутного сторожа.
                    service.healDisk()
                    // Первый взгляд после самоперезапуска: диск — заново, как
                    // после складывания, что бы ни сломалось на подъёме (697:
                    // окна вернулись, а кнопки встали столбиком у края), и
                    // через полторы секунды — где что стоит, в журнал.
                    if (reason == START_REASON && System.currentTimeMillis() - lastRestartAt < RESTARTED_MS) {
                        service.relayoutDisk()
                        handler.postDelayed({
                            runCatching { log("после самоперезапуска: ${service.windowsReport()}") }
                        }, REPORT_AFTER_MS)
                    }
                }
                OverlaySight.Step.UNKNOWN -> {
                    log("$head — система не назвала ни одного окна, смотрю ещё раз")
                    if (unknownTries++ < 1) plan(UNKNOWN_RETRY_MS) { check(reason, confirmed) }
                }
                OverlaySight.Step.CONFIRM -> {
                    log("$head — смотрю ещё раз, потом перезапуск службы")
                    plan(CONFIRM_MS) { check("подтверждение", confirmed = true) }
                }
                OverlaySight.Step.LATER -> {
                    if (laterTries == 0) log("$head — перезапущу службу, когда кончится работа")
                    laterTries++
                    plan(LATER_MS) { check(QUIET_REASON, confirmed = true) }
                }
                OverlaySight.Step.RESTART -> {
                    log("$head — не видно дважды подряд, перезапускаю службу (так диск возвращался руками)")
                    restart()
                }
                OverlaySight.Step.BLIND -> {
                    if (clean) {
                        blind = true
                        log("$head — окна только что повешены и заведомо видны, значит, проверка их не различает; дальше ей не верю")
                    } else {
                        log("$head — проверка слепа (старт не нашёл окон), не трогаю")
                    }
                    dirty = false
                }
                OverlaySight.Step.GIVE_UP -> {
                    val why = when {
                        !trusted -> "проверка ещё ни разу не видела наших окон, перезапуску по ней не верю"
                        System.currentTimeMillis() - lastRestartAt < OverlaySight.RESTART_GAP_MS ->
                            "самоперезапуск уже был меньше трёх минут назад"
                        else -> "работа так и не кончилась"
                    }
                    log("$head — не трогаю: $why; перезапусти службу руками")
                    tellOwner()
                }
            }
        }
    }

    /**
     * Один взгляд: поднять флаг окон, дать системе собрать список, выписать
     * оверлеи и опустить флаг — всё на главном потоке, как и остальная
     * работа службы с окнами.
     */
    private fun look(then: (listed: Int, overlays: List<OverlaySight.Box>) -> Unit) {
        val raised = raiseFlag()
        val r = Runnable {
            looking = null
            val windows = runCatching { service.windows ?: emptyList() }.getOrDefault(emptyList())
            val overlays = windows
                .filter { runCatching { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.getOrDefault(false) }
                .mapNotNull { w ->
                    runCatching {
                        val b = Rect()
                        w.getBoundsInScreen(b)
                        OverlaySight.Box(b.left, b.top, b.right, b.bottom)
                    }.getOrNull()
                }
            if (raised) lowerFlag()
            then(windows.size, overlays)
        }
        looking = r
        handler.postDelayed(r, if (raised) LOOK_WAIT_MS else 0L)
    }

    private fun dropLook() {
        looking?.let { handler.removeCallbacks(it) }
        looking = null
        if (flagRaised) lowerFlag()
    }

    /** Поднять флаг окон, если его не было. Правда — если поднимали мы (опускать тоже нам). */
    private fun raiseFlag(): Boolean = runCatching {
        val info = service.serviceInfo ?: return@runCatching false
        val f = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        if (info.flags and f != 0) return@runCatching false
        info.flags = info.flags or f
        service.serviceInfo = info
        flagRaised = true
        true
    }.getOrDefault(false)

    private fun lowerFlag() {
        flagRaised = false
        runCatching {
            val info = service.serviceInfo ?: return@runCatching
            val f = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            if (info.flags and f == 0) return@runCatching
            info.flags = info.flags and f.inv()
            service.serviceInfo = info
        }
    }

    /**
     * Перезапуск процесса: метка — в настройки (сторож «раз в полчаса»
     * переживает сам перезапуск), очередь записи — дописать, потом выход.
     * Службу доступности система поднимет сама, как после переезда базы
     * (`DataRoot.restart`, минутный тик службы).
     */
    private fun restart() {
        val now = System.currentTimeMillis()
        lastRestartAt = now
        service.scope.launch(Dispatchers.IO) {
            runCatching { service.app.settings.setOverlayRestartAt(now) }
            ru.zf.pravka.data.DiskWriter.drain(3_000L)
            // Полсекунды — файловой системе папки базы: в 697 новая служба
            // через 0,4 с после подъёма поймала «close failed: EIO», похоже,
            // на файле, который старая записала за миг до выхода.
            kotlinx.coroutines.delay(EXIT_SETTLE_MS)
            Runtime.getRuntime().exit(0)
        }
    }

    /** Сдался — сказать словами и дать дорогу к перезапуску (раз за жизнь службы). */
    private fun tellOwner() {
        if (told) return
        told = true
        runCatching {
            val nm = service.getSystemService(android.app.NotificationManager::class.java)
            val channelId = "pravka-overlay"
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId, "Кнопки на экране",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
            val open = android.app.PendingIntent.getActivity(
                service, NOTIF_ID,
                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notif = android.app.Notification.Builder(service, channelId)
                .setContentTitle("Правка: система прячет диск")
                .setContentText("Кнопки висят, но их не видно. Перезапусти службу — диск вернётся.")
                .setStyle(
                    android.app.Notification.BigTextStyle().bigText(
                        "После складывания система не показывает окна диска, хотя они на месте, а " +
                            "сама служба перезапуститься сейчас не может. Выключи и включи службу " +
                            "Правки в «Специальных возможностях» — тап открывает этот экран.",
                    )
                )
                .setSmallIcon(ru.zf.pravka.R.drawable.ic_tile)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, notif)
        }
    }

    private fun plan(delayMs: Long, block: () -> Unit) {
        planned?.let { handler.removeCallbacks(it) }
        val r = Runnable { planned = null; block() }
        planned = r
        handler.postDelayed(r, delayMs)
    }

    private fun locked(): Boolean =
        runCatching { service.keyguardManager?.isKeyguardLocked == true }.getOrDefault(false)

    private fun interactive(): Boolean =
        runCatching { service.getSystemService(PowerManager::class.java)?.isInteractive != false }.getOrDefault(true)

    private companion object {
        /** Через сколько после старта службы — диск к этому времени расставлен. */
        const val START_CHECK_MS = 6_000L

        /** Разблокировка: анимация замка кончилась. */
        const val UNLOCK_CHECK_MS = 900L

        /** Сколько дать системе собрать список окон после подъёма флага. */
        const val LOOK_WAIT_MS = 1_000L

        /** После складывания — когда переход точно кончился (отчёт уже через 1,5 с после раскладки). */
        const val FOLD_CHECK_MS = 1_500L

        /** Второй взгляд после «не вижу» — перед перезапуском. */
        const val CONFIRM_MS = 2_000L

        /** Пауза между последней записью и выходом процесса при самоперезапуске. */
        const val EXIT_SETTLE_MS = 500L

        const val UNKNOWN_RETRY_MS = 2_000L
        const val LATER_MS = 30_000L

        /** Размер окна у системы и у нас совпадает до пикселя; допуск — на округление. */
        const val SLACK_PX = 4

        /** Повтор ожидания работы — в журнал не пишется: одна строка на «жду». */
        const val QUIET_REASON = "ожидание"

        const val START_REASON = "старт"

        /** Старт службы считается самоперезапуском, если метка свежее двух минут. */
        const val RESTARTED_MS = 2 * 60_000L

        /** Строка «где что стоит» после перерасстановки — когда кнопки доехали. */
        const val REPORT_AFTER_MS = 1_500L

        const val NOTIF_ID = 48
    }
}
