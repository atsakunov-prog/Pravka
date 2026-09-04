package ru.zf.pravka.trigger
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.io.File
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.R
import ru.zf.pravka.core.ProofreadEngine
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.UndoStack
import ru.zf.pravka.data.Settings
import ru.zf.pravka.provider.GoogleSpeechSession
import ru.zf.pravka.target.AccessibilityTarget
import ru.zf.pravka.target.effectiveText
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Haptics

// Разборы в службе: ночной тик поиска паттернов. Расширение PravkaAccessibilityService.

/**
 * Итоги: забрать готовый батч и, если ночь пришла, отправить новый.
 * Разбор приезжает уведомлением — иначе он тихо лежал бы во вкладке,
 * которую владелец открывает раз в неделю.
 */
internal suspend fun PravkaAccessibilityService.analysisTick() {
    val ready = app.analysisEngine.tick() ?: return
    runCatching {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val channelId = "pravka-itogi"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId, "Итоги: разбор готов",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
        val open = android.app.PendingIntent.getActivity(
            this, 73,
            android.content.Intent(this, ru.zf.pravka.MainActivity::class.java)
                .putExtra(
                    ru.zf.pravka.MainActivity.EXTRA_TAB,
                    ru.zf.pravka.MainActivity.TAB_ITOGI,
                )
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val preview = ready.text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .take(200)
        val notif = android.app.Notification.Builder(this, channelId)
            .setContentTitle("Разбор готов: " + ready.title())
            .setContentText(preview)
            .setStyle(android.app.Notification.BigTextStyle().bigText(preview))
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(("itogi" + ready.id).hashCode(), notif)
    }
}
