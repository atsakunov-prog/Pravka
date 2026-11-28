package ru.zf.slushalka.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
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
import ru.zf.slushalka.ui.Covers
import ru.zf.slushalka.ui.formatSpeed

/**
 * Служба озвучки. Держит чтение живым, когда экран погас и приложение
 * свёрнуто, и показывает озвучку Android как обычное медиа (28.11.2026,
 * владелец: «чтобы я мог делать паузу на наушниках… чтобы Android воспринимал
 * как медиа, которое играет»): своя медиасессия - кнопки гарнитуры и
 * Bluetooth, часы, плеер в шторке и на экране блокировки с обложкой; вынули
 * наушники - пауза, как у любого плеера.
 *
 * Сессия платформенная, своя, а не PlaybackService: та построена вокруг
 * ExoPlayer и файлов, а здесь звук рождает движок синтеза или голос сервера,
 * и ни таймлайна, ни файлов у него нет. Две сессии одного приложения Android
 * различает по состоянию: кнопку гарнитуры получает та, что «играет». Уходит
 * сама, как только озвучка выключена.
 *
 * «Вперёд/Назад» - на абзац: так же, как кнопки в читалке.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ReadAloudService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var session: MediaSession

    /** Что уже отдано сессии и шторке: состояние меняется на каждой фразе, а пересобирать незачем. */
    private var shownKey: String? = null
    private var metaKey: String? = null
    private var art: Bitmap? = null
    private var artFor: String? = null

    private val readAloud get() = (application as SlushalkaApp).readAloud

    override fun onBind(intent: Intent?): IBinder? = null

    /** Кнопки гарнитуры, Bluetooth, часов и плеера в шторке. */
    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = readAloud.resume()
        override fun onPause() = readAloud.pause()
        override fun onStop() = readAloud.stop()
        override fun onSkipToNext() = readAloud.skip(+1)
        override fun onSkipToPrevious() = readAloud.skip(-1)
        override fun onFastForward() = readAloud.skip(+1)
        override fun onRewind() = readAloud.skip(-1)
        override fun onCustomAction(action: String, extras: Bundle?) {
            if (action == CUSTOM_STOP) readAloud.stop()
        }
    }

    /** Вынули наушники или отвалился Bluetooth - пауза, а не книга вслух на весь вагон. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && readAloud.state.value.speaking) {
                readAloud.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        session = MediaSession(this, "slushalka-readaloud").apply {
            setCallback(callback, Handler(Looper.getMainLooper()))
            setSessionActivity(Shade.openApp(this@ReadAloudService))
            isActive = true
        }
        ContextCompat.registerReceiver(
            this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
        runCatching { unregisterReceiver(noisy) }
        session.isActive = false
        session.release()
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
        session.setPlaybackState(playback(s))
        val mk = "${s.bookId}|${s.title}|${s.chapter}"
        if (mk != metaKey) {
            metaKey = mk
            session.setMetadata(metadata(s))
        }
        // Шторка - только когда в ней что-то меняется, а не на каждой фразе.
        val key = "${s.speaking}|${s.title}|${s.chapter}|${s.rate}|${s.bookId}"
        if (key == shownKey) return
        shownKey = key
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(s), type)
    }

    /**
     * Позиции нет: у синтеза нет таймлайна, есть только фраза в тексте. Без
     * неё плеер в шторке просто не рисует полосу - это честнее, чем выдумка.
     */
    private fun playback(s: ReadAloud.State): PlaybackState =
        PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
                    PlaybackState.ACTION_REWIND
            )
            // Готовится голос сервера - для гарнитуры это всё равно «играет»: нажатие ставит паузу, а не
            // запускает второе чтение поверх.
            .setState(
                if (s.speaking) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (s.speaking) s.rate else 0f,
            )
            // Плеер в шторке с Android 13 строит кнопки из сессии, а не из уведомления: «Стоп» - отсюда.
            .addCustomAction(
                PlaybackState.CustomAction.Builder(CUSTOM_STOP, "Стоп", icon(CommandButton.ICON_STOP)).build()
            )
            .build()

    private fun metadata(s: ReadAloud.State): MediaMetadata =
        MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, s.title.ifBlank { "Слушалка" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, s.chapter.ifBlank { "Озвучка" })
            .putString(MediaMetadata.METADATA_KEY_ALBUM, s.title)
            .apply { cover(s.bookId)?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
            .build()

    /** Обложка - та, что уже на экране (кэш читалки), иначе вынутая из книги; ужата: сессия ходит через binder. */
    private fun cover(bookId: String?): Bitmap? {
        if (bookId == null) return null
        if (artFor == bookId) return art
        val app = application as SlushalkaApp
        val big = Covers.cached(bookId) ?: runCatching {
            val f = app.texts.coverFile(bookId)
            if (!f.exists()) return@runCatching null
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })
        }.getOrNull()
        art = big?.let { shrink(it, 512) }
        artFor = bookId
        return art
    }

    private fun shrink(b: Bitmap, side: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= side) return b
        val k = side.toFloat() / m
        return Bitmap.createScaledBitmap(
            b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true,
        )
    }

    private fun build(s: ReadAloud.State): Notification {
        val line = listOf(formatSpeed(s.rate), s.chapter).filter { it.isNotBlank() }.joinToString(" · ")
        val toggle = if (s.speaking) {
            action(icon(CommandButton.ICON_PAUSE), "Пауза", ACTION_TOGGLE)
        } else {
            action(icon(CommandButton.ICON_PLAY), "Дальше", ACTION_TOGGLE)
        }
        return Notification.Builder(this, Shade.CHANNEL)
            .setSmallIcon(R.drawable.ic_shade_book)
            .setContentTitle(s.title.ifBlank { "Слушалка" })
            .setContentText(line)
            .apply { cover(s.bookId)?.let { setLargeIcon(it) } }
            .setContentIntent(Shade.openApp(this))
            .setOngoing(s.speaking)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(icon(CommandButton.ICON_PREVIOUS), "Назад", ACTION_BACK))
            .addAction(toggle)
            .addAction(action(icon(CommandButton.ICON_NEXT), "Вперёд", ACTION_FORWARD))
            .addAction(action(icon(CommandButton.ICON_STOP), "Стоп", ACTION_STOP))
            // Тот же вопрос голосом, что у плеера: книга замолкает, ответ вслух.
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_shade_mic), "Спросить", Shade.askByVoice(this),
                ).build()
            )
            .setStyle(
                Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun icon(constant: Int): Int = CommandButton.getIconResIdForIconConstant(constant)

    private fun action(iconRes: Int, title: String, name: String): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, iconRes), title, pending(name)).build()

    private fun pending(name: String): PendingIntent = PendingIntent.getService(
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
        private const val CUSTOM_STOP = "ru.zf.slushalka.readaloud.custom.STOP"
        private const val NOTIFICATION_ID = 7
    }
}
