package ru.zf.slushalka.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.zf.slushalka.MainActivity
import ru.zf.slushalka.R
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.ui.Covers
import ru.zf.slushalka.ui.formatLeft

/**
 * Виджет «Продолжить»: последняя книга на главном экране - обложка, название,
 * где остановился. Тап открывает её сразу на своём месте.
 *
 * Обновляет его само приложение ([refresh]): при уходе с экрана и при
 * открытии книги. По таймеру - незачем: место меняется, только пока
 * приложением пользуются, а будить телефон раз в полчаса ради строчки дорого.
 */
class ContinueWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as SlushalkaApp
        val pending = goAsync()
        app.scope.launch {
            try {
                render(app, manager, ids)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** С этим действием MainActivity открывает последнюю книгу, минуя полку. */
        const val ACTION_CONTINUE = "ru.zf.slushalka.CONTINUE"

        fun refresh(app: SlushalkaApp) {
            val manager = AppWidgetManager.getInstance(app)
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(app, ContinueWidget::class.java))
            }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            app.scope.launch { runCatching { render(app, manager, ids) } }
        }

        private suspend fun render(app: SlushalkaApp, manager: AppWidgetManager, ids: IntArray) {
            val prefs = app.settings.flow.first { it.loaded }
            val lastId = app.positions.lastBook()
            val book = app.library.books(prefs.libraryUris).firstOrNull { it.id == lastId }
            val views = RemoteViews(app.packageName, R.layout.widget_continue)

            val open = Intent(app, MainActivity::class.java)
                .setAction(ACTION_CONTINUE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    app, 0, open,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            // Микрофон - тот же вопрос голосом, что в шторке плеера.
            views.setOnClickPendingIntent(R.id.widget_mic, ru.zf.slushalka.player.Shade.askByVoice(app))

            if (book == null) {
                views.setTextViewText(R.id.widget_title, app.getString(R.string.app_name))
                views.setTextViewText(R.id.widget_line, "Открой книгу - она появится здесь")
                views.setViewVisibility(R.id.widget_progress, View.GONE)
                // Спрашивать не о чем, пока нет книги.
                views.setViewVisibility(R.id.widget_mic, View.GONE)
                manager.updateAppWidget(ids, views)
                return
            }

            val st = app.positions.get(book.id)
            val share = when {
                book.hasAudio && book.totalMs > 0 -> (st.absMs.toFloat() / book.totalMs).coerceIn(0f, 1f)
                else -> st.readShare
            }
            views.setTextViewText(R.id.widget_label, if (book.hasAudio) "ПРОДОЛЖИТЬ СЛУШАТЬ" else "ПРОДОЛЖИТЬ ЧИТАТЬ")
            views.setTextViewText(R.id.widget_title, book.title)
            views.setTextViewText(
                R.id.widget_line,
                when {
                    book.hasAudio && book.totalMs > 0 -> {
                        val left = (book.totalMs - st.absMs).coerceAtLeast(0)
                        "${(share * 100).toInt()}% · осталось ${formatLeft(left, st.speed.takeIf { it > 0 } ?: 1f)}"
                    }
                    st.readChar > 0 -> "стр. ${st.readChar / Settings.PAGE_CHARS + 1}" +
                        if (share > 0f) " · ${(share * 100).toInt()}%" else ""
                    else -> book.author
                },
            )
            views.setViewVisibility(R.id.widget_progress, View.VISIBLE)
            views.setViewVisibility(R.id.widget_mic, if (book.textDocId != null) View.VISIBLE else View.GONE)
            views.setProgressBar(R.id.widget_progress, 1000, (share * 1000).toInt(), false)

            val tree = app.state.treeOf(book)
            val cover = tree?.let { runCatching { Covers.load(app, it, book, app.texts) }.getOrNull() }
            if (cover != null) views.setImageViewBitmap(R.id.widget_cover, rounded(cover, 260))
            manager.updateAppWidget(ids, views)
        }

        /**
         * Обложка поменьше и со скруглёнными углами. Меньше - потому что
         * RemoteViews везут картинку через Binder, и полноразмерная упирается в
         * его предел; скругление - потому что обрезать ImageView по контуру
         * виджет на старых Android не умеет.
         */
        private fun rounded(src: Bitmap, maxSide: Int): Bitmap {
            val k = maxSide.toFloat() / maxOf(src.width, src.height)
            val w = (src.width * minOf(1f, k)).toInt().coerceAtLeast(1)
            val h = (src.height * minOf(1f, k)).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.BitmapShader(
                    scaled, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP,
                )
            }
            val r = w * 0.07f
            Canvas(out).drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, paint)
            return out
        }
    }
}
