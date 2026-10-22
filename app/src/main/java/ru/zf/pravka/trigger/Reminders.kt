package ru.zf.pravka.trigger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.R
import ru.zf.pravka.core.Dela
import ru.zf.pravka.core.ReminderPlan
import ru.zf.pravka.data.Profile
import ru.zf.pravka.data.ReminderShown

/**
 * Напоминания Дел уведомлением телефона (захват, шаг 0 петли, 15.10.2026; задание — docs/dela-phone-25.md, п. 4).
 *
 * Владелец: «напоминание… приходит уведомлением телефона (не только ботом в Telegram — там я почти не отвечаю), после
 * срабатывания — „Сделано / Через час / Завтра“ и исчезает». Что и когда — решает `core/ReminderPlan.kt` по копии Дел
 * с очередью поверх; здесь — Android: канал «Напоминания Дел», кнопки, будильник ([ReminderAlarms]), отметка показа
 * серверу (`task.reminded {via: phone}` — тогда бот молчит; без флага `remind_phone` у сервера не шлём).
 *
 * Канал — HIGH и не глушится «Засечкой молча»: это просьба самого владельца, а не вопрос автопилота. Всё — не на
 * главном потоке (правило Fold): зовут из корутин приложения, приёмника и тика службы.
 */
class Reminders(private val app: PravkaApp) {

    enum class Act { DONE, HOUR, TOMORROW }

    private val shown by lazy { ReminderShown(app) }
    private val mutex = Mutex()

    /** Как стоит будильник — словами для строки в настройках Дел («неточно — нет разрешения…»). */
    @Volatile var alarmNote: String = ""
        private set

    companion object {
        const val CHANNEL = "dela_remind"
        /** Напоминание, которое бот уже прислал в Telegram, — тихо, без всплытия ([ReminderPlan.afterBot]). */
        const val QUIET_CHANNEL = "dela_remind_quiet"
        /** Отметка показа не ушла (нет сети) — повторить через столько, а не ждать тика службы через 5 минут. */
        private const val POKE_MS = 45_000L
        /** Итог захвата, пришедший позже (фраза ждала связи или модели), — тихо, без звука. */
        const val CAPTURE_CHANNEL = "dela_capture"
        /** Идеи за 10 минут до встречи с этим клиентом или человеком. */
        const val MEET_CHANNEL = "dela_meet_ideas"
        private val ZONE: ZoneId get() = ZoneId.systemDefault()
        fun notifId(taskId: String): Int = ("dela-remind:$taskId").hashCode()
    }

    /**
     * Следить за копией Дел: поменялись напоминания (новое, переставили, закрыли) — показать пора и переставить
     * будильник. Подпись — только то, что звенит: синк раз в пять минут будильник не трогает.
     */
    @OptIn(FlowPreview::class)
    fun start() {
        app.appScope.launch(Dispatchers.Default) {
            runCatching { app.delaStore.load() }
            app.delaStore.view
                .map { s ->
                    ReminderPlan.candidates(s, me()).sortedBy { it.id }.joinToString("|") { it.id + "@" + it.remindAt + "@" + it.status } +
                        "#" + s.ideas.values.count { it.open } + "#" + s.remindPhoneOn
                }
                .distinctUntilChanged()
                .debounce(1_000L)
                .collect { runCatching { fire("копия") }.onFailure { e -> app.eventLog.add("напоминания: ${e.javaClass.simpleName}: ${e.message}") } }
        }
    }

    private fun me(): String = app.delaSync.link.value?.user?.takeIf { it.isNotBlank() } ?: app.delaStore.me

    private fun notificationsOn(): Boolean =
        runCatching { androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled() }.getOrDefault(false)

    /**
     * Канал «Напоминания Дел» не выключен в Android: иначе телефон «показывает» в пустоту, отмечает показ — и бот молчит
     * (проверка 16.10.2026). Канала ещё нет — он заведётся при первом показе, считаем включённым.
     */
    private fun channelOn(): Boolean = runCatching {
        app.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(true)

    /**
     * Показывает ли телефон напоминания: Дела на сервере и в профиле, сервер это умеет (флаг `remind_phone`: бот ждёт
     * отметку показа 10 минут), тумблер включён, уведомления разрешены и канал «Напоминания Дел» не выключен. Сервер
     * старый — всё по-старому: шлёт бот, телефон молчит (иначе напоминание пришло бы дважды).
     */
    suspend fun on(): Boolean {
        // Правка на диете (22.10.2026): телефон напоминаний не показывает — шлёт бот в Telegram сразу. syncSetting
        // один раз скажет это серверу («0»), fire снимет будильник.
        return false
    }

    /**
     * Показать, что пора (и пропущенное за сутки — «должно было в 12:00»), отметить показ серверу и поставить будильник
     * на следующее. Зовут: будильник, загрузка, смена копии, тик службы, приезд в место.
     */
    suspend fun fire(why: String): Unit = withContext(Dispatchers.IO) { mutex.withLock { fireLocked(why) } }

    private suspend fun fireLocked(why: String) {
        // Сначала — сказать серверу, показывает ли телефон (шлётся, только когда поменялось): раньше это делали лишь тик
        // службы доступности и тумблер, и без поднятой службы после обновления сервер не знал remind_phone — каждое
        // напоминание приходило дважды (проверка 16.10.2026).
        runCatching { syncSetting() }.onFailure { e -> app.eventLog.add("напоминания: настройка серверу — ${e.message}") }
        if (!on()) {
            ReminderAlarms.schedule(app, null)
            alarmNote = ""
            return
        }
        app.delaStore.load()
        val snap = app.delaStore.view.value
        val me = me()
        val now = System.currentTimeMillis()
        if (!shown.seeded()) {
            val past = ReminderPlan.seed(snap, me, now)
            shown.add(past)
            if (past.isNotEmpty()) app.eventLog.add("напоминания: первый запуск — прошедшие (${past.size}) не звеним задним числом")
        }
        val keys = shown.all()
        val due = ReminderPlan.due(snap, me, now, keys)
        for (d in due) show(d, now)
        if (due.isNotEmpty()) {
            shown.add(due.map { it.key })
            app.eventLog.add("напоминания ($why): показал ${due.size} — " + due.joinToString(", ") { "«${it.task.title.take(40)}»" + if (it.late) " (опоздал)" else "" })
            // Бот у такого владельца ждёт 10 минут отметки — отмечаем сразу, иначе придёт и в Telegram. Не ушло (нет
            // сети) — ещё раз через 45 с, не дожидаясь тика службы. Уже присланное ботом своим показом не отмечаем.
            val marks = due.filterNot { ReminderPlan.afterBot(it) }
            if (snap.remindPhoneOn && marks.isNotEmpty()) {
                app.delaStore.enqueue(marks.map { ReminderPlan.shownOp(it, now, ZONE) })
                val sent = runCatching { app.delaSync.pushNow(4_000L) }.getOrDefault(false)
                if (!sent) poke()
            }
        }
        // Перед встречей (ответ 5а: идеи «всплывают перед встречей»): за 10 минут до встречи, у клиента или человека
        // которой есть живые идеи, — тихое уведомление с ними. Календарь — те же смотримые календари, что у «Сегодня».
        val meets = meetings(snap, now)
        val meetDue = ReminderPlan.meetDue(meets, now, keys)
        for (m in meetDue) showMeet(m, now)
        if (meetDue.isNotEmpty()) shown.add(meetDue.map { it.key })
        val shownNow = keys + due.map { it.key } + meetDue.map { it.key }
        val next = listOfNotNull(ReminderPlan.next(snap, me, now, shownNow), ReminderPlan.meetNext(meets, now, shownNow)).minOrNull()
        alarmNote = ReminderAlarms.schedule(app, next)
    }

    /** Отметка показа не ушла сразу — две попытки через [POKE_MS] (очередь Дел повторяет её и сама, но тиком в 5 минут). */
    private fun poke() {
        app.appScope.launch(Dispatchers.IO) {
            repeat(2) {
                kotlinx.coroutines.delay(POKE_MS)
                if (runCatching { app.delaSync.pushNow(4_000L) }.getOrDefault(false)) return@launch
            }
        }
    }

    /** Встречи на сутки вперёд с идеями; нет идей, календаря или разрешения — пусто. */
    private suspend fun meetings(snap: Dela.Snapshot, now: Long): List<ReminderPlan.Meet> {
        if (!snap.ideasOn || snap.ideas.values.none { it.open }) return emptyList()
        val watched = app.settings.todayCalendarsFlow.first() ?: app.settings.autoCalendarsFlow.first()
        val events = runCatching { CalendarPilot.query(app, now - ReminderPlan.MEET_AHEAD_MS, now + 24 * 3_600_000L) }.getOrDefault(emptyList())
            .filter { ru.zf.pravka.core.CalendarRules.eligible(it, watched) }
        return ReminderPlan.meetings(events, snap)
    }

    private fun showMeet(m: ReminderPlan.Meet, now: Long) {
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(MEET_CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(MEET_CHANNEL, "Идеи перед встречей", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(
                app, ("meet" + m.key).hashCode(),
                Intent(app, ru.zf.pravka.MainActivity::class.java)
                    .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_TODAY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val body = ReminderPlan.meetBody(m)
            nm.notify(
                ("dela-meet:" + m.key).hashCode(),
                Notification.Builder(app, MEET_CHANNEL)
                    .setContentTitle(ReminderPlan.meetTitle(m, now))
                    .setContentText(body)
                    .setStyle(Notification.BigTextStyle().bigText(body))
                    .setSmallIcon(R.drawable.ic_tile)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
            app.eventLog.add("напоминания: перед встречей «${m.event.title.take(40)}» — идей ${m.ideas.size}")
        }.onFailure { e -> app.eventLog.add("напоминания: перед встречей не показалось — ${e.message}") }
    }

    /**
     * Кнопка напоминания — в уведомлении или строкой на «Сегодня»: «Сделано», «Через час», «Завтра» (9:00). Обычная
     * операция очереди: без связи ляжет и уйдёт сама, строка с «Сегодня» уходит сразу (очередь поверх копии).
     * Возвращает слова для тоста. [toast] — их и правда покажут тостом: тогда отказ сервера в плашке «Сервер ответил»
     * не повторяется (отладка 16.10.2026, запись 1); кнопка уведомления тоста не показывает — отказ остаётся в плашке.
     */
    suspend fun act(taskId: String, act: Act, from: String, toast: Boolean = true): String =
        withContext(Dispatchers.IO) { actIo(taskId, act, from, toast) }

    private suspend fun actIo(taskId: String, act: Act, from: String, toast: Boolean): String {
        cancel(taskId)
        app.delaStore.load()
        val t = app.delaStore.view.value.tasks[taskId] ?: return "Напоминания уже нет"
        val now = System.currentTimeMillis()
        val (op, said) = when (act) {
            Act.DONE -> ReminderPlan.doneOp(t) to "✓ ${t.title.take(40)}"
            Act.HOUR -> ReminderPlan.moveOp(t, ReminderPlan.hourLater(now, ZONE)) to "Напомню через час"
            Act.TOMORROW -> ReminderPlan.moveOp(t, ReminderPlan.tomorrowMorning(now, ZONE)) to "Напомню завтра в 9:00"
        }
        app.delaStore.enqueue(listOf(op))
        app.eventLog.add("напоминания: «${t.title.take(40)}» — ${act.name.lowercase()} ($from)")
        val sent = runCatching { app.delaSync.pushNow(4_000L) }.getOrDefault(false)
        runCatching { fire("кнопка") }
        val refused = if (toast) app.delaStore.tell(listOf(op)) else app.delaStore.noticesFor(listOf(op)).firstOrNull { it.error }
        return refused?.text ?: if (sent) said else "$said — уйдёт на сервер, когда будет связь"
    }

    /** Снять уведомление дела (кнопка нажата здесь или на «Сегодня»). */
    fun cancel(taskId: String) {
        runCatching { app.getSystemService(NotificationManager::class.java).cancel(notifId(taskId)) }
    }

    private fun show(d: ReminderPlan.Due, now: Long) {
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                // HIGH — всплывает поверх экрана: DEFAULT на Samsung — тихая строка в шторке (урок автопилота).
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Напоминания Дел", NotificationManager.IMPORTANCE_HIGH))
            }
            val quiet = ReminderPlan.afterBot(d)
            if (quiet && nm.getNotificationChannel(QUIET_CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(QUIET_CHANNEL, "Напоминания Дел — уже в Telegram", NotificationManager.IMPORTANCE_LOW))
            }
            val id = d.task.id
            fun action(label: String, what: String, code: Int): Notification.Action {
                val pi = PendingIntent.getBroadcast(
                    app, (id + what).hashCode() + code,
                    Intent(app, ReminderReceiver::class.java).setAction(what).putExtra(ReminderReceiver.EXTRA_ID, id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                return Notification.Action.Builder(null as android.graphics.drawable.Icon?, label, pi).build()
            }
            val open = PendingIntent.getActivity(
                app, ("open" + id).hashCode(),
                Intent(app, ru.zf.pravka.MainActivity::class.java)
                    .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_TODAY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val body = ReminderPlan.body(d, now, ZONE)
            val n = Notification.Builder(app, if (quiet) QUIET_CHANNEL else CHANNEL)
                .setContentTitle(ReminderPlan.title(d.task))
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_tile)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setWhen(d.atMs)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(action("Сделано", ReminderReceiver.ACTION_DONE, 1))
                .addAction(action("Через час", ReminderReceiver.ACTION_HOUR, 2))
                .addAction(action("Завтра", ReminderReceiver.ACTION_TOMORROW, 3))
                .build()
            nm.notify(notifId(id), n)
        }.onFailure { e -> app.eventLog.add("напоминания: уведомление «${d.task.title.take(40)}» не показалось — ${e.message}") }
    }

    /**
     * Итог захвата, пришедший позже (фраза ждала связи с Делами или модели), — тихим уведомлением: строка «куда легло»
     * не должна пропасть оттого, что пилюля уже ушла. Тап — «Сегодня».
     */
    fun captureNotice(text: String) {
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CAPTURE_CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CAPTURE_CHANNEL, "Дела: куда легло сказанное", NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(
                app, "capture-open".hashCode(),
                Intent(app, ru.zf.pravka.MainActivity::class.java)
                    .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_TODAY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(app, CAPTURE_CHANNEL)
                .setContentTitle("Дела")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_tile)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify(("dela-capture:" + text).hashCode(), n)
        }.onFailure { e -> app.eventLog.add("захват: уведомление с итогом не показалось — ${e.message}") }
    }

    /**
     * Сказать серверу, показывает ли телефон напоминания (`user.settings {remind_phone: {on}}`): включено — бот ждёт
     * 10 минут отметки показа, выключено (тумблер, уведомления или канал запрещены, Дела выключены) — бот шлёт сразу.
     * Только серверу с флагом `remind_phone`; шлётся, когда поменялось.
     */
    suspend fun syncSetting() {
        if (!app.delaOnServer()) return
        app.delaStore.load()
        if (!app.delaStore.view.value.remindPhoneOn) return
        val on = on()
        val want = if (on) "1" else "0"
        if (app.settings.remindPhoneSent() == want) return
        app.delaDo(listOf(ReminderPlan.settingsOp(on)))
        app.settings.setRemindPhoneSent(want)
        app.eventLog.add("напоминания: серверу — " + if (on) "показывает телефон, бот запасной" else "телефон не показывает, бот шлёт сразу")
    }

    /** Строка состояния для настроек Дел: где звенит и точно ли. */
    fun stateWords(snap: Dela.Snapshot, on: Boolean): String = when {
        !on -> "выключено — напоминания шлёт бот в Telegram"
        !snap.remindPhoneOn -> "сервер Дел ещё не обновлён — пока напоминает бот в Telegram, телефон начнёт сам после обновления"
        alarmNote.startsWith("неточно") -> "напоминания могут опоздать на минуты — разреши «Будильники и напоминания» в настройках Android"
        else -> "уведомлением на телефоне; Telegram — только если телефон не отметил показ за 10 минут"
    }
}
