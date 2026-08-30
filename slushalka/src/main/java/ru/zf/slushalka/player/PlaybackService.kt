package ru.zf.slushalka.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ru.zf.slushalka.MainActivity
import ru.zf.slushalka.SlushalkaApp

/**
 * Служба воспроизведения. Она не «фоновый сервис ради галочки»: именно она
 * держит книгу живой, когда экран погашен и приложение свёрнуто, и именно из
 * неё берутся обложка на экране блокировки, кнопки ±15 секунд в шторке и
 * реакция на кнопку гарнитуры.
 *
 * Свайп приложения из недавних книгу НЕ останавливает - в наушниках это
 * выглядело бы как поломка. Служба уходит сама, когда воспроизведение стоит.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val holder = (application as SlushalkaApp).player
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, holder.player)
            .setSessionActivity(open)
            .build()
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
