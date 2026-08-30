package ru.zf.slushalka.player

import android.os.Bundle
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import ru.zf.slushalka.SlushalkaApp

/**
 * Служба воспроизведения. Она не «фоновый сервис ради галочки»: именно она
 * держит книгу живой, когда экран погашен и приложение свёрнуто, и именно из
 * неё берутся картинка на экране блокировки, кнопки перемотки в шторке и
 * реакция на кнопку гарнитуры.
 *
 * Свайп приложения из недавних книгу НЕ останавливает - в наушниках это
 * выглядело бы как поломка. Служба уходит сама, когда воспроизведение стоит.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    /** Шаг перемотки, уже отданный шторке: чтобы не пересобирать её впустую. */
    private var appliedSkip = 0

    private val callback = object : MediaSession.Callback {

        /**
         * Без этого кнопки перемотки в шторке не нажимаются: контроллер
         * уведомления - такой же контроллер, и своих команд он не знает,
         * пока их ему не выдадут.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult = MediaSession.ConnectionResult.accept(
            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(Shade.command(Shade.BACK))
                .add(Shade.command(Shade.FORWARD))
                .build(),
            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
        )

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val holder = (application as SlushalkaApp).player
            val sec = Shade.skipSeconds(this@PlaybackService)
            val code = when (customCommand.customAction) {
                Shade.BACK -> { holder.skip(-sec); SessionResult.RESULT_SUCCESS }
                Shade.FORWARD -> { holder.skip(sec); SessionResult.RESULT_SUCCESS }
                else -> SessionResult.RESULT_ERROR_NOT_SUPPORTED
            }
            return Futures.immediateFuture(SessionResult(code))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as SlushalkaApp
        setMediaNotificationProvider(ShadeNotificationProvider(this))
        appliedSkip = Shade.skipSeconds(this)
        session = MediaSession.Builder(this, app.player.player)
            .setSessionActivity(Shade.openApp(this))
            .setCustomLayout(Shade.layout(appliedSkip))
            .setCallback(callback)
            .build()
    }

    /**
     * Шаг перемотки меняют в настройках на ходу. Раскладка пересобирается
     * ровно тогда, когда он и правда изменился: setCustomLayout сам просит
     * перерисовать уведомление, и без этой проверки они бы гоняли друг друга
     * по кругу.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val sec = Shade.skipSeconds(this)
        if (sec != appliedSkip) {
            appliedSkip = sec
            session.setCustomLayout(Shade.layout(sec))
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            (application as SlushalkaApp).player.saveNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        (application as SlushalkaApp).player.saveNow()
        session?.release()
        session = null
        super.onDestroy()
    }
}
