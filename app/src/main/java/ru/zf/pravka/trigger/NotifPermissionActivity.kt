package ru.zf.pravka.trigger

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.ui.Feedback

/**
 * Невидимый одноразовый трамплин за разрешением на уведомления — по образцу
 * [MicPermissionActivity]: спросить разрешение умеет только Activity, а
 * замечает, что уведомления выключены, служба доступности (после обновления,
 * см. `ServiceNotifPermission.kt`). Сказать об этом пушем нельзя по
 * определению, поэтому служба зовёт это окно, и на экране появляется
 * системный диалог.
 *
 * Система показывает диалог не больше двух раз, потом отказывает мгновенно;
 * тогда открываем экран уведомлений Правки в настройках — единственное место,
 * где разрешение ещё можно вернуть. Отказ, после которого система готова
 * спросить ещё раз, — слово владельца: только записка, без настроек.
 */
class NotifPermissionActivity : ComponentActivity() {

    companion object {
        /** Зачем спросили — для журнала. */
        const val EXTRA_WHY = "why"
    }

    private val request = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val app = application as PravkaApp
        when {
            granted -> {
                app.eventLog.add("уведомления: разрешены заново (${why()})")
                Feedback.toast(this, "Уведомления Правки снова разрешены")
            }
            Build.VERSION.SDK_INT >= 33 &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                // Диалог система уже не покажет — только руками в настройках.
                app.eventLog.add("уведомления: система диалог не показывает (${why()}) — открываю настройки")
                openSystemSettings()
            }
            else -> app.eventLog.add("уведомления: владелец отказал в диалоге (${why()})")
        }
        finish()
    }

    private fun why(): String = intent?.getStringExtra(EXTRA_WHY).orEmpty().ifBlank { "проверка" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.areNotificationsEnabled()) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            request.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // До Android 13 разрешения нет — только тумблер в настройках.
            openSystemSettings()
            finish()
        }
    }

    private fun openSystemSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Feedback.toast(this, "Включи «Разрешить уведомления» для Правки", long = true)
        }.onFailure {
            Feedback.toast(this, "Не открыл настройки уведомлений: ${it.message}", long = true)
        }
    }
}
