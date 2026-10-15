package ru.zf.pravka.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Один точный будильник на ближайшее напоминание Дел (захват, 15.10.2026, `core/ReminderPlan.kt`). Без
 * `AlarmManager` точность была бы «пока жива служба, раз в пять минут» — «напомни в 12» звенело бы в 12:04. Будильник
 * ставится при смене копии Дел, старте приложения, загрузке телефона, после показа и кнопок; звонит —
 * `ReminderReceiver` (действие [ACTION_FIRE]), тот показывает и ставит следующий.
 *
 * Точный будильник — разрешение `USE_EXACT_ALARM` (Android 13+, выдаётся при установке) или `SCHEDULE_EXACT_ALARM`
 * (Android 12, выдаёт владелец). Нельзя точно — неточный (минуты) и честная строка в настройках Дел ([exact]):
 * молчаливая механика читается как поломка (правило 6 карты).
 */
object ReminderAlarms {

    const val ACTION_FIRE = "ru.zf.pravka.dela.REMIND_FIRE"

    private const val CODE_FIRE = 0x52_4D_01

    /** Точные будильники разрешены (до Android 12 — всегда). */
    fun exact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    private fun firePending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, CODE_FIRE,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Будильник на [atMs]; null — снять (напоминаний впереди нет). Один на всё: новый заменяет прежний (тот же
     * PendingIntent). Возвращает словами, что поставлено, — для журнала.
     */
    fun schedule(context: Context, atMs: Long?): String {
        val am = context.getSystemService(AlarmManager::class.java) ?: return "нет AlarmManager"
        val pi = firePending(context)
        if (atMs == null) {
            runCatching { am.cancel(pi) }
            return "впереди напоминаний нет"
        }
        if (exact(context)) {
            val ok = runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi) }.isSuccess
            if (ok) return "точно"
        }
        // Нельзя точно (не выдано «Будильники и напоминания») — неточный: минуты опоздания лучше тишины.
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi) }
        return "неточно — нет разрешения на точные будильники"
    }
}
