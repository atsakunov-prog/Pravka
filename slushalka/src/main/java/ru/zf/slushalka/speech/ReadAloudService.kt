package ru.zf.slushalka.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import ru.zf.slushalka.R
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.player.Shade
import ru.zf.slushalka.ui.formatSpeed

/**
 * Служба озвучки. Держит чтение живым, когда экран погас и приложение
 * свёрнуто, и кладёт в шторку простое уведомление: пауза, абзац назад и
 * вперёд, стоп. Своя, а не PlaybackService: та построена вокруг ExoPlayer и
 * файлов, а здесь звук рождает движок синтеза, и ни таймлайна, ни файлов у него
 * нет. Уходит сама, как только озвучка выключена.
 */
class ReadAloudService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val readAloud get() = (application as SlushalkaApp).readAloud

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // В передний план - сразу: с Android 8 на это даётся пять секунд.
        show(readAloud.state.value)
        watch = scope.launch {
            readAloud.state.collect { s ->
                if (!s.active) {
                    stopSelf()
                    return@collect
                }
                show(s)
                holdCpu(s.speaking)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> readAloud.playPause()
            ACTION_BACK -> readAloud.skip(-1)
            ACTION_FORWARD -> readAloud.skip(+1)
            ACTION_STOP -> readAloud.stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
        holdCpu(false)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Движок синтеза сам процессор не держит; без замка на погашенном экране
     * чтение через несколько минут начинает заикаться и обрывается.
     */
    private fun holdCpu(on: Boolean) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (on) {
            if (wakeLock?.isHeld == true) return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "slushalka:readaloud").also {
                runCatching { it.acquire(6 * 60 * 60 * 1000L) }
            }
        } else {
            wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
            wakeLock = null
        }
    }

    private fun show(s: ReadAloud.State) {
        val n = build(s)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun build(s: ReadAloud.State): Notification {
        val line = listOf("Озвучка", formatSpeed(s.rate), s.chapter).filter { it.isNotBlank() }
            .joinToString(" · ")
        return NotificationCompat.Builder(this, Shade.CHANNEL)
            .setSmallIcon(R.drawable.ic_shade_book)
            .setContentTitle(s.title.ifBlank { "Слушалка" })
            .setContentText(line)
            .setContentIntent(Shade.openApp(this))
            .setOngoing(s.speaking)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(R.drawable.ic_shade_book, "Назад", action(ACTION_BACK))
            .addAction(R.drawable.ic_shade_book, if (s.speaking) "Пауза" else "Дальше", action(ACTION_TOGGLE))
            .addAction(R.drawable.ic_shade_book, "Вперёд", action(ACTION_FORWARD))
            .addAction(R.drawable.ic_shade_book, "Стоп", action(ACTION_STOP))
            .build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this,
        name.hashCode(),
        Intent(this, ReadAloudService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Канал тот же, что у плеера: две «книги в шторке» пользователю не нужны. */
    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(Shade.CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(Shade.CHANNEL, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val ACTION_TOGGLE = "ru.zf.slushalka.readaloud.TOGGLE"
        const val ACTION_BACK = "ru.zf.slushalka.readaloud.BACK"
        const val ACTION_FORWARD = "ru.zf.slushalka.readaloud.FORWARD"
        const val ACTION_STOP = "ru.zf.slushalka.readaloud.STOP"
        private const val NOTIFICATION_ID = 7
    }
}
