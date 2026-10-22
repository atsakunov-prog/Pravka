package ru.zf.pravka.trigger

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.zf.pravka.MainActivity
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.R
import ru.zf.pravka.core.ZFace
import ru.zf.pravka.core.ZWidget
import ru.zf.pravka.data.Profile

/**
 * Виджет Засечки на главном экране (11.10.2026, решения — `core/ZWidget.kt`).
 * Владелец: «что идёт, кнопки записи еды (картинка, фото, текст), открыть
 * „Сегодня“, поменять текущее… всё то, что обычно на виджетах».
 *
 * Что где:
 *  - кружок «З» со значком идущего дела — тейк «З», как тап по кнопке на
 *    стекле; название и подпись — лента Засечки; справа минуты, как на «З»;
 *  - «ещё раз» — три недавних дела: касание начинает такое же дело сейчас,
 *    без голоса и без Claude (как метка NFC);
 *  - ряд кнопок: сменить (тот же тейк), мысль к идущему делу, еда снимком, из
 *    галереи, текстом, «Сегодня».
 * «Стоп» нет нарочно: лента непрерывна, а закрытое касанием дело через пять
 * минут становится «не размечено» — выход «сменить» честнее.
 *
 * Действия, которым нужна служба или экран, идут через трамплины: тейк, мысль
 * и «ещё раз» — `ZasechkaQuickActivity` (не экспортирована, ресивер виджета
 * чужих просьб не принимает), еда и «Сегодня» — `MainActivity`.
 *
 * Минуты тикает само приложение ([watch]): раз в минуту ровно к смене числа,
 * и только пока на экране есть хоть один виджет. Лента поменялась —
 * перерисовка сразу. Перерисовка — целиком (`updateAppWidget`): частичная
 * поверх чужого размера путает слои, а одна картинка значка в минуту — копейки.
 */
class ZasechkaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshAsync(context, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        refreshAsync(context, intArrayOf(id))
    }

    override fun onEnabled(context: Context) = poke()

    private fun refreshAsync(context: Context, ids: IntArray) {
        val app = context.applicationContext as? PravkaApp ?: return
        val pending = goAsync()
        app.appScope.launch(Dispatchers.Default) {
            try {
                val model = ZWidget.model(app.zasechkaStore.all(), System.currentTimeMillis())
                render(app, ids, model)
            } catch (e: Exception) {
                app.eventLog.add("виджет Засечки: не нарисовался — ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pending.finish()
            }
            // Виджет появился (или лаунчер попросил) — тиканье минут проснётся.
            poke()
        }
    }

    companion object {
        /** Толчок тиканью: виджет добавили или перерисовали снаружи. */
        private val wake = MutableStateFlow(0L)

        fun poke() {
            wake.value = SystemClock.elapsedRealtimeNanos()
        }

        /**
         * Зовёт `PravkaApp` на старте процесса. Модель ленты меняется — перерисовка
         * и новое выравнивание тика под начало нового дела (`collectLatest`
         * бросает прежний цикл). Виджетов нет — цикл сразу кончается и ждёт
         * толчка [poke]: тикать впустую незачем.
         */
        fun watch(app: PravkaApp) {
            app.appScope.launch(Dispatchers.Default) {
                combine(
                    app.zasechkaStore.entriesFlow
                        .map { ZWidget.model(it, System.currentTimeMillis()) }
                        .distinctUntilChanged(),
                    wake,
                ) { model, _ -> model }
                    .collectLatest { model ->
                        while (true) {
                            val ids = ids(app)
                            if (ids.isEmpty()) return@collectLatest
                            runCatching { render(app, ids, model) }
                                .onFailure { app.eventLog.add("виджет Засечки: ${it.javaClass.simpleName}: ${it.message}") }
                            val start = model.now?.start ?: return@collectLatest
                            delay(ZFace.nextTickMs(System.currentTimeMillis() - start))
                        }
                    }
            }
        }

        private fun ids(context: Context): IntArray = runCatching {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, ZasechkaWidget::class.java))
        }.getOrDefault(IntArray(0))

        private fun render(app: PravkaApp, ids: IntArray, model: ZWidget.Model) {
            val manager = AppWidgetManager.getInstance(app)
            val food = app.profileStore.has(Profile.Mode.FOOD)
            for (id in ids) {
                val o = manager.getAppWidgetOptions(id)
                // Портрет: ширина — минимальная, высота — максимальная (так их
                // отдаёт лаунчер); пустые опции — заводские 4×2.
                val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 250
                val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 180
                manager.updateAppWidget(id, views(app, model, ZWidget.size(w, h), ZWidget.wide(w), food))
            }
        }

        /** Виджет одного размера; `internal` — для снимков (`app/src/shots`). */
        internal fun views(
            app: PravkaApp,
            model: ZWidget.Model,
            size: ZWidget.Size,
            wide: Boolean,
            food: Boolean,
        ): RemoteViews {
            val v = RemoteViews(app.packageName, R.layout.widget_zasechka)
            val now = model.now
            // Узкий виджет (две ячейки): справа минутам места нет — название
            // обрезалось до «Д…» (снимок 11.10.2026). Там минуты в самом
            // кружке, под значком, ровно как на кнопке «З».
            val inFace = now != null && !wide
            if (now != null) {
                val label = ZFace.label(System.currentTimeMillis() - now.start)
                v.setTextViewText(R.id.w_title, now.title)
                v.setTextViewText(R.id.w_sub, now.sub)
                v.setTextViewText(R.id.w_min, label)
                v.setTextViewText(R.id.w_unit, ZWidget.unit(label))
                v.setTextViewText(R.id.w_face_min, label)
            } else {
                v.setTextViewText(R.id.w_title, app.getString(R.string.widget_z_idle_title))
                v.setTextViewText(R.id.w_sub, app.getString(R.string.widget_z_idle_sub))
            }
            v.setViewVisibility(R.id.w_min_box, if (now != null && !inFace) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.w_face_min, if (inFace) View.VISIBLE else View.GONE)
            val icon = now?.icon
            if (icon != null) {
                val px = (46 * app.resources.displayMetrics.density).toInt()
                // С минутами в кружке — доли кнопки «З»: значок ×0,82 и выше на 11%.
                val bmp = if (inFace) ZFaceGlyphs.bitmap(icon, PAPER, px, scale = 0.52f * 0.82f, lift = 0.11f)
                else ZFaceGlyphs.bitmap(icon, PAPER, px)
                v.setImageViewBitmap(R.id.w_icon, bmp)
            } else {
                v.setImageViewResource(R.id.w_icon, ModeGlyphs.zasechka())
            }

            // Ярусы по размеру: строка — только «что идёт», дальше кнопки,
            // в высоком — ещё и «ещё раз» (если есть что повторить).
            val again = model.again
            v.setViewVisibility(
                R.id.w_again,
                if (size == ZWidget.Size.FULL && again.isNotEmpty()) View.VISIBLE else View.GONE,
            )
            v.setViewVisibility(R.id.w_actions, if (size == ZWidget.Size.ROW) View.GONE else View.VISIBLE)
            val chips = intArrayOf(R.id.w_a1, R.id.w_a2, R.id.w_a3)
            for ((i, chip) in chips.withIndex()) {
                val a = again.getOrNull(i)
                if (a == null) {
                    v.setViewVisibility(chip, View.INVISIBLE)
                    continue
                }
                v.setViewVisibility(chip, View.VISIBLE)
                v.setTextViewText(chip, a.title)
                v.setOnClickPendingIntent(chip, quick(app, RC_AGAIN + i, ZasechkaQuickActivity.W_AGAIN, a.id))
            }
            // Еда и «Сегодня» — когда ряд шире двух ячеек; еда — если режим жив.
            val foodVis = if (wide && food) View.VISIBLE else View.GONE
            v.setViewVisibility(R.id.w_photo, foodVis)
            v.setViewVisibility(R.id.w_gallery, foodVis)
            v.setViewVisibility(R.id.w_text, foodVis)
            v.setViewVisibility(R.id.w_today, if (wide) View.VISIBLE else View.GONE)

            val take = quick(app, RC_TAKE, ZasechkaQuickActivity.W_RECORD)
            v.setOnClickPendingIntent(R.id.w_face_box, take)
            v.setOnClickPendingIntent(R.id.w_say, take)
            v.setOnClickPendingIntent(R.id.w_text_box, main(app, RC_TAB, MainActivity.TAB_ZASECHKA))
            v.setOnClickPendingIntent(R.id.w_min_box, main(app, RC_TAB, MainActivity.TAB_ZASECHKA))
            v.setOnClickPendingIntent(R.id.w_thought, quick(app, RC_THOUGHT, ZasechkaQuickActivity.W_THOUGHT))
            // Еда — сразу камерой, выбором снимка или строкой кнопки «Е», без вкладки (22.10.2026).
            v.setOnClickPendingIntent(R.id.w_photo, food(app, RC_PHOTO, FoodQuickActivity.W_PHOTO))
            v.setOnClickPendingIntent(R.id.w_gallery, food(app, RC_GALLERY, FoodQuickActivity.W_GALLERY))
            v.setOnClickPendingIntent(R.id.w_text, food(app, RC_TEXT, FoodQuickActivity.W_TEXT))
            v.setOnClickPendingIntent(R.id.w_today, main(app, RC_TODAY, MainActivity.TAB_TODAY))
            return v
        }

        // Свой код запроса на каждую кнопку: PendingIntent с одинаковым
        // интентом и кодом — один и тот же, и дополнения последней кнопки
        // переписали бы остальные («ещё раз» с чужим делом).
        private const val RC_TAKE = 1
        private const val RC_TAB = 2
        private const val RC_THOUGHT = 3
        private const val RC_PHOTO = 4
        private const val RC_GALLERY = 5
        private const val RC_TEXT = 6
        private const val RC_TODAY = 7
        private const val RC_AGAIN = 10

        private val PAPER = 0xFFF7F3EA.toInt()

        private fun quick(context: Context, code: Int, what: String, entry: Long = 0L): PendingIntent =
            PendingIntent.getActivity(
                context,
                code,
                Intent(context, ZasechkaQuickActivity::class.java)
                    .putExtra(ZasechkaQuickActivity.EXTRA_WHAT, what)
                    .putExtra(ZasechkaQuickActivity.EXTRA_ENTRY, entry)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun food(context: Context, code: Int, what: String): PendingIntent =
            PendingIntent.getActivity(
                context,
                code,
                Intent(context, FoodQuickActivity::class.java)
                    .putExtra(FoodQuickActivity.EXTRA_WHAT, what)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun main(context: Context, code: Int, tab: String, food: String = ""): PendingIntent =
            PendingIntent.getActivity(
                context,
                code,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TAB, tab)
                    .apply { if (food.isNotEmpty()) putExtra(MainActivity.EXTRA_FOOD_ACTION, food) }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
