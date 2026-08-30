package ru.zf.slushalka.player

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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

    override fun onCreate() {
        super.onCreate()
        val app = application as SlushalkaApp
        setMediaNotificationProvider(ShadeNotificationProvider(this))
        val built = MediaSession.Builder(this, ShadePlayer(app.player, app.settings))
            .setSessionActivity(Shade.openApp(this))
            .build()
        session = built
        // Без этой строки шторка пуста, и никакая раскладка кнопок не поможет.
        // Собранная сессия сама по себе службе неизвестна: она попадает к
        // менеджеру уведомлений только через addSession. Обычно это делается
        // за нас - когда к службе подключается MediaController, - но здесь
        // экраны работают с плеером напрямую, контроллеров нет, и onGetSession
        // не вызывается никогда. Служба при этом живёт, звук идёт, а плеера в
        // шторке нет: ровно та поломка, которую невозможно увидеть в коде
        // уведомления, потому что уведомление просто не заказывают.
        addSession(built)
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
