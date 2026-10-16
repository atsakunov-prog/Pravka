package ru.zf.pravka.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.zf.pravka.PravkaApp

/**
 * Будильник напоминаний и кнопки уведомления «Сделано · Через час · Завтра» (захват, 15.10.2026). Закрыт снаружи
 * (`exported=false`): сюда ходят только свои PendingIntent. Работа — не на главном потоке (правило Fold): `goAsync`
 * и корутина приложения, у системы на неё десять секунд.
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DONE = "ru.zf.pravka.dela.REMIND_DONE"
        const val ACTION_HOUR = "ru.zf.pravka.dela.REMIND_HOUR"
        const val ACTION_TOMORROW = "ru.zf.pravka.dela.REMIND_TOMORROW"
        const val EXTRA_ID = "id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PravkaApp ?: return
        val action = intent.action.orEmpty()
        val pending = goAsync()
        app.appScope.launch(Dispatchers.IO) {
            try {
                when (action) {
                    ReminderAlarms.ACTION_FIRE -> app.reminders.fire("будильник")
                    ACTION_DONE -> app.reminders.act(intent.getStringExtra(EXTRA_ID).orEmpty(), Reminders.Act.DONE, "уведомление", toast = false)
                    ACTION_HOUR -> app.reminders.act(intent.getStringExtra(EXTRA_ID).orEmpty(), Reminders.Act.HOUR, "уведомление", toast = false)
                    ACTION_TOMORROW -> app.reminders.act(intent.getStringExtra(EXTRA_ID).orEmpty(), Reminders.Act.TOMORROW, "уведомление", toast = false)
                }
            } catch (e: Exception) {
                runCatching { app.eventLog.add("напоминания: $action упало — ${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Загрузка телефона и обновление приложения: будильники системы после них пусты. Догнать пропущенное (≤ суток, с
 * «должно было в 12:00») и поставить следующий. Действия — только системные защищённые: чужое приложение их не пошлёт.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action.orEmpty()
        // Только после разблокировки (BOOT_COMPLETED): база лежит в памяти, закрытой до первого ввода кода.
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as? PravkaApp ?: return
        val pending = goAsync()
        app.appScope.launch(Dispatchers.IO) {
            try {
                app.reminders.fire(if (action == Intent.ACTION_MY_PACKAGE_REPLACED) "обновление" else "загрузка")
            } catch (e: Exception) {
                runCatching { app.eventLog.add("напоминания: после загрузки упало — ${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                pending.finish()
            }
        }
    }
}
