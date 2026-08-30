package ru.zf.slushalka.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import ru.zf.slushalka.MainActivity
import ru.zf.slushalka.R
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Settings

/**
 * Плеер в шторке. Слушают книгу, как правило, не глядя в приложение: телефон
 * в кармане, наушники в ушах. Значит всё нужное должно быть в одном движении
 * сверху вниз - пауза, перемотка на те же секунды, что в приложении, и вход
 * внутрь одним нажатием.
 */
object Shade {

    const val CHANNEL = "slushalka-playback"

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

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
    fun button(icon: Int, playerCommand: Int, name: String): CommandButton =
        CommandButton.Builder(icon)
            .setIconResId(CommandButton.getIconResIdForIconConstant(icon))
            .setPlayerCommand(playerCommand)
            .setDisplayName(name)
            .build()
}

/**
 * Плеер для сессии. Нужен ровно затем, чтобы перемотка из шторки, с гарнитуры
 * и из автомагнитолы шла на тот же шаг, что кнопки в приложении: у ExoPlayer
 * шаг задаётся один раз при сборке, а в настройках его меняют на ходу.
 *
 * Обычными командами плеера, а не своими: свои команды надо выдавать каждому
 * контроллеру отдельно, и уведомление собирается вокруг них заметно более
 * хрупко - а здесь всё делается тем же COMMAND_SEEK_BACK, что понимают и часы,
 * и магнитола.
 */
class ShadePlayer(
    private val holder: PlayerHolder,
    private val settings: Settings,
) : ForwardingPlayer(holder.player) {

    private val step: Int get() = settings.now().skipSec

    override fun seekBack() = holder.skip(-step)

    override fun seekForward() = holder.skip(step)

    override fun getSeekBackIncrement(): Long = step * 1000L

    override fun getSeekForwardIncrement(): Long = step * 1000L
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
        // Своя раскладка не стоит пустой шторки: если она почему-то не
        // соберётся, пусть будет стандартный плеер media3, а не ничего.
        return runCatching {
            val sec = Shade.skipSeconds(context)
            ImmutableList.of(
                Shade.button(Shade.backIcon(sec), Player.COMMAND_SEEK_BACK, "Назад $sec с"),
                Shade.button(
                    if (showPauseButton) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY,
                    Player.COMMAND_PLAY_PAUSE,
                    if (showPauseButton) "Пауза" else "Слушать",
                ),
                Shade.button(Shade.forwardIcon(sec), Player.COMMAND_SEEK_FORWARD, "Вперёд $sec с"),
            )
        }.getOrElse {
            super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
        }
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
        val indices = super.addNotificationActions(session, mediaButtons, builder, actionFactory)
        runCatching {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    IconCompat.createWithResource(context, R.drawable.ic_shade_open),
                    "Открыть Слушалку",
                    Shade.openApp(context),
                ).build()
            )
        }
        if (mediaButtons.isEmpty()) return indices
        // Свёрнутое уведомление показывает три кнопки: перемотка, пауза,
        // перемотка. «Открыть» остаётся в развёрнутом - туда и так ведёт
        // нажатие на само уведомление.
        return IntArray(minOf(3, mediaButtons.size)) { it }
    }
}
