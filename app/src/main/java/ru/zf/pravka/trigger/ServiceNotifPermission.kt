package ru.zf.pravka.trigger

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.zf.pravka.BuildConfig

// Уведомления после обновления. Владелец (08.09.2026): «когда обновляю
// Правку, разрешение на пуши, видимо, уходит. Можно, чтобы после обновления
// он сам вспоминал, что пуши надо снова включить?»
//
// По правилам Android грант переживает обновление того же пакета той же
// подписью, и откуда он уходит — пока не найдено. Поэтому этот файл делает
// две вещи. Первая — ПИШЕТ В ЖУРНАЛ при каждом подъёме службы: номер сборки,
// с какой обновились и разрешены ли уведомления; по этим строкам будет видно,
// в какой момент состояние слетает (а не «видимо»). Вторая — лечит: если
// уведомления выключены, служба один раз на сборку зовёт
// NotifPermissionActivity (системный диалог, а когда система его уже не
// покажет — экран настроек) и показывает записку на кнопке «З»: пушем об
// этом не скажешь по определению. Страховка — плашка сверху в MainActivity,
// пока уведомления выключены (см. там же).

/** Разрешены ли уведомления Правки прямо сейчас; сбой системы читаем как «да». */
internal fun PravkaAccessibilityService.notificationsEnabled(): Boolean =
    runCatching { getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
        .getOrDefault(true)

/**
 * Зовётся из `onServiceConnected`: служба поднимается после загрузки, после
 * обновления APK и после падения процесса — ровно те моменты, когда стоит
 * сверить, на месте ли уведомления.
 */
internal fun PravkaAccessibilityService.checkNotificationsAfterUpdate() {
    val prefs = getSharedPreferences(PravkaAccessibilityService.PREFS_INTERNAL, Context.MODE_PRIVATE)
    val build = BuildConfig.VERSION_CODE
    val seen = prefs.getInt(PravkaAccessibilityService.KEY_SEEN_BUILD, 0)
    val updated = seen != 0 && seen != build
    if (seen != build) prefs.edit().putInt(PravkaAccessibilityService.KEY_SEEN_BUILD, build).apply()
    val enabled = notificationsEnabled()
    app.eventLog.add(
        "служба: сборка $build" + (if (updated) " (обновление с $seen)" else "") +
            " · уведомления " + (if (enabled) "разрешены" else "ВЫКЛЮЧЕНЫ")
    )
    if (enabled) return
    // Один раз на сборку: после каждого обновления — заново, но не при каждом
    // перезапуске службы, иначе это допрос, а не напоминание.
    if (prefs.getInt(PravkaAccessibilityService.KEY_NOTIF_NUDGED_BUILD, 0) == build) return
    prefs.edit().putInt(PravkaAccessibilityService.KEY_NOTIF_NUDGED_BUILD, build).apply()
    val why = if (updated) "после обновления до $build" else "служба поднялась на сборке $build"
    app.eventLog.add("уведомления выключены — прошу заново ($why)")
    scope.launch {
        // Кнопки должны успеть встать: старт активити из фона система
        // разрешает, пока у приложения есть видимое окно, — это наши
        // плавающие кнопки. Тем же путём служба просит микрофон.
        delay(6_000)
        zButton?.showNote("🔕 Пуши выключены — разреши их Правке", ok = false, holdMs = 12_000)
        runCatching {
            startActivity(
                Intent(this@checkNotificationsAfterUpdate, NotifPermissionActivity::class.java)
                    .putExtra(NotifPermissionActivity.EXTRA_WHY, why)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { app.eventLog.add("уведомления: диалог не открылся — ${it.message}") }
    }
}
