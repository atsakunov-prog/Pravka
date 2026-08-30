package ru.zf.slushalka.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import ru.zf.slushalka.MainActivity
import ru.zf.slushalka.R
import ru.zf.slushalka.SlushalkaApp

/**
 * Плеер в шторке. Слушают книгу, как правило, не глядя в приложение: телефон
 * в кармане, наушники в ушах. Значит всё нужное должно быть в одном движении
 * сверху вниз - пауза, перемотка на те же секунды, что в приложении, и вход
 * внутрь одним нажатием.
 */
object Shade {

    const val BACK = "ru.zf.slushalka.SHADE_BACK"
    const val FORWARD = "ru.zf.slushalka.SHADE_FORWARD"
    const val CHANNEL = "slushalka-playback"

    fun command(action: String): SessionCommand = SessionCommand(action, Bundle.EMPTY)

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Кнопки перемотки шторки берут шаг из настроек, а не из встроенных в
     * ExoPlayer пятнадцати секунд: иначе в приложении «10», а в шторке «15»,
     * и это замечаешь ровно в тот момент, когда переспрашиваешь фразу.
     */
    fun skipSeconds(context: Context): Int =
        (context.applicationContext as SlushalkaApp).settings.now().skipSec

    private fun nearest(sec: Int) = when {
        sec <= 7 -> 5
        sec <= 12 -> 10
        sec <= 22 -> 15
        else -> 30
    }

    fun backIcon(sec: Int): Int = when (nearest(sec)) {
        5 -> CommandButton.ICON_SKIP_BACK_5
        10 -> CommandButton.ICON_SKIP_BACK_10
        15 -> CommandButton.ICON_SKIP_BACK_15
        else -> CommandButton.ICON_SKIP_BACK_30
    }

    fun forwardIcon(sec: Int): Int = when (nearest(sec)) {
        5 -> CommandButton.ICON_SKIP_FORWARD_5
        10 -> CommandButton.ICON_SKIP_FORWARD_10
        15 -> CommandButton.ICON_SKIP_FORWARD_15
        else -> CommandButton.ICON_SKIP_FORWARD_30
    }

    /**
     * Кнопка с картинкой. setIconResId обязателен: уведомление берёт из кнопки
     * именно iconResId, а сам по себе он из ICON_-константы не выводится - и
     * кнопка уезжает в шторку пустой, без значка.
     */
    fun button(icon: Int, action: String, name: String): CommandButton =
        CommandButton.Builder(icon)
            .setIconResId(CommandButton.getIconResIdForIconConstant(icon))
            .setSessionCommand(command(action))
            .setDisplayName(name)
            .build()

    fun playPause(pausing: Boolean): CommandButton {
        val icon = if (pausing) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY
        return CommandButton.Builder(icon)
            .setIconResId(CommandButton.getIconResIdForIconConstant(icon))
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setDisplayName(if (pausing) "Пауза" else "Слушать")
            .build()
    }

    /** Раскладка для системных потребителей: Bluetooth, часы, автомагнитола. */
    fun layout(sec: Int): List<CommandButton> = listOf(
        button(backIcon(sec), BACK, "Назад $sec с"),
        button(forwardIcon(sec), FORWARD, "Вперёд $sec с"),
    )
}

/**
 * Своя раскладка уведомления вместо стандартной «предыдущий / пауза /
 * следующий». У аудиокниги нет треков, между которыми прыгают: перелистывать
 * файлы книги кнопкой в шторке бессмысленно и опасно - одно случайное нажатие
 * уносит на час вперёд. Поэтому по краям от паузы стоит перемотка на секунды.
 *
 * Всё остальное - канал, передний план, загрузка обложки - остаётся от
 * media3: это ровно та часть, которую больно писать заново и легко сломать.
 */
class ShadeNotificationProvider(private val context: Context) : DefaultMediaNotificationProvider(
    context,
    NotificationIdProvider { DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID },
    Shade.CHANNEL,
    R.string.playback_channel,
) {

    init {
        setSmallIcon(R.drawable.ic_shade_book)
    }

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        customLayout: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        val sec = Shade.skipSeconds(context)
        return ImmutableList.of(
            Shade.button(Shade.backIcon(sec), Shade.BACK, "Назад $sec с"),
            Shade.playPause(showPauseButton),
            Shade.button(Shade.forwardIcon(sec), Shade.FORWARD, "Вперёд $sec с"),
        )
    }

    /**
     * Четвёртой кнопкой - вход в приложение. Намерение ведёт прямо в activity,
     * а не через службу: запуск экрана из фоновой службы Android с десятой
     * версии молча проглатывает.
     */
    override fun addNotificationActions(
        session: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray {
        super.addNotificationActions(session, mediaButtons, builder, actionFactory)
        builder.addAction(
            NotificationCompat.Action.Builder(
                IconCompat.createWithResource(context, R.drawable.ic_shade_open),
                "Открыть Слушалку",
                Shade.openApp(context),
            ).build()
        )
        // Свёрнутое уведомление показывает три кнопки: перемотка, пауза,
        // перемотка. «Открыть» остаётся в развёрнутом - туда и так ведёт
        // нажатие на само уведомление.
        return IntArray(minOf(3, mediaButtons.size)) { it }
    }
}
