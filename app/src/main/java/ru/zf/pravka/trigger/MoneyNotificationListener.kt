package ru.zf.pravka.trigger

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.core.BankPush

// Пуши Т-Банка и чата «Плати по миру» → журнал Денег (владелец, 23.09.2026:
// «чтобы правка ловила пуши от Тинькова и вносила их»).
//
// Отдельная служба, НЕ служба доступности: у той можно подписаться на
// «уведомление появилось», но это ровно то, чего после Fold не делаем —
// лишние события и подглядывание за чужими окнами. Эта живёт своей жизнью,
// стопки кнопок не касается, и доступ к ней владелец выдаёт и отзывает сам
// («Доступ к уведомлениям»).
//
// Здесь только достать текст: чужие приложения отсекаются сразу по пакету,
// разбор и запись — в `MoneyEngine.onPush` на фоновом потоке. Главный поток
// службы ничего не считает.
class MoneyNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }

    /**
     * Подключились (включили доступ, перезапуск процесса): пройти по тому,
     * что уже висит в шторке, — пуши, пришедшие, пока службы не было, не
     * теряются. Повторы отсекает отпечаток пуша в сторе.
     */
    override fun onListenerConnected() {
        runCatching { activeNotifications }.getOrNull()?.forEach { handle(it) }
    }

    /**
     * Образцы денежных уведомлений (Настройки → Деньги, тумблер «Собирать
     * образцы»): для разборщиков пушей новых банков — Альфы и МКБ у Марианны,
     * внесения через банкомат у Т-Банка. Решение «похоже ли на деньги» — до
     * корутины: шторка шлёт десятки уведомлений, и тумблер читается только
     * ради денежных.
     */
    private fun sample(pkg: String, title: String, extras: android.os.Bundle, n: Notification, ts: Long) {
        val app = application as? PravkaApp ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        if (!app.pushSamples.wanted(pkg, title, text + " " + big)) return
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val lines = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages
                ?.mapNotNull { it.text?.toString() }
        }.getOrNull().orEmpty()
        app.appScope.launch {
            if (runCatching { app.settings.mPushSamplesFlow.first() }.getOrDefault(false)) {
                app.pushSamples.add(pkg, title, text, big, sub, lines, ts)
            }
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val n = sbn.notification ?: return
        // Сводка группы («13 уведомлений Т-Банка») — не операция: сами пуши придут отдельно.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        sample(pkg, title, extras, n, sbn.postTime)
        if (BankPush.from(pkg, title) == BankPush.From.OTHER) return

        // Телеграм кладёт непрочитанные сообщения чата списком (MessagingStyle):
        // каждое — со своим временем. Т-Банк — один текст, полный — в BIG_TEXT
        // (в свёрнутом виде шторка обрезает «…счет карты *1519…»).
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val items: List<Pair<String, Long>> = style?.messages?.mapNotNull { m ->
            m.text?.toString()?.takeIf { it.isNotBlank() }?.let { it to (if (m.timestamp > 0) m.timestamp else sbn.postTime) }
        }?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(
                (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                    ?.toString()?.takeIf { it.isNotBlank() }?.let { it to sbn.postTime }
            )
        if (items.isEmpty()) return
        val chatTitle = style?.conversationTitle?.toString()?.takeIf { it.isNotBlank() } ?: title

        val app = application as? PravkaApp ?: return
        // Деньги выключены в профиле — пуши банка не читаются вовсе.
        if (!app.profileStore.has(ru.zf.pravka.data.Profile.Mode.MONEY)) return
        app.appScope.launch {
            if (!app.settings.mPushFlow.first()) return@launch
            for ((text, ts) in items) {
                val result = runCatching { app.moneyEngine.onPush(pkg, chatTitle, text, ts) }
                    .getOrElse { e -> "ошибка: ${e.message ?: e.javaClass.simpleName}" }
                if (result.startsWith("ошибка")) app.eventLog.add("деньги: пуш $pkg — $result")
            }
        }
    }
}
