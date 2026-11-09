package ru.zf.pravka.trigger

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * „Сегодня“, поменять текущее… всё то, что обычно на виджетах». 08.11.2026 —
 * «чтобы там были и кнопки, и ещё была лента»: выбран «Пульт», вширь — две колонки.
 *
 * Что где:
 *  - кружок «З» со значком идущего дела — тейк «З», как тап по кнопке на
 *    стекле; название и подпись — лента Засечки; справа минуты, как на «З»;
 *  - полоса суток: прожитое цветами категорий, «сейчас» — янтарной чертой;
 *  - категории плитками, частые сверху ([ZWidget.model] → `ZSwitch.order`):
 *    касание — «переключить на …», как в меню долгого нажатия «З»; идущая —
 *    янтарём, её касание открывает ленту;
 *  - последние куски ленты строками: начало, название, длительность;
 *  - ряд кнопок: сменить (тот же тейк), мысль к идущему делу, еда снимком, из
 *    галереи, текстом, «Сегодня».
 * Сколько всего влезает — [ZWidget.plan] по размеру виджета на экране. Экранов
 * бывает два (складной телефон: внешний узкий и внутренний почти квадратный),
 * поэтому лаунчеру уходит своя раскладка на каждый размер, и он сам
 * показывает нужную ([forOptions]). Ряд кнопок прижат к низу: ошибись расчёт —
 * обрежется лента, а не кнопки.
 * «Стоп» нет нарочно: лента непрерывна, а закрытое касанием дело через пять
 * минут становится «не размечено» — выход «сменить» честнее.
 *
 * Действия, которым нужна служба или экран, идут через трамплины: тейк, мысль
 * и переключение — `ZasechkaQuickActivity` (не экспортирована, ресивер виджета
 * чужих просьб не принимает), еда, лента и «Сегодня» — `MainActivity`.
 *
 * Минуты тикает само приложение ([watch]): раз в минуту ровно к смене числа,
 * и только пока на экране есть хоть один виджет. Лента или справочник
 * категорий поменялись — перерисовка сразу. Перерисовка — целиком
 * (`updateAppWidget`): частичная поверх чужого размера путает слои. Картинок
 * на кадр — значок шапки, значки категорий (одна картинка на значок) и полоса
 * суток высотой 10 dp: всё вместе меньше трёхсот килобайт. Раскладки разных
 * размеров делят одни и те же картинки ([Kit]) — в посылку они уходят один раз.
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
                val model = ZWidget.model(
                    app.zasechkaStore.all(), System.currentTimeMillis(),
                    app.zasechkaStore.categories().map { it.name },
                )
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
                    combine(app.zasechkaStore.entriesFlow, app.zasechkaStore.categoriesFlow) { e, c ->
                        ZWidget.model(e, System.currentTimeMillis(), c.map { it.name })
                    }.distinctUntilChanged(),
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
            val kit = Kit()
            for (id in ids) {
                manager.updateAppWidget(id, forOptions(app, model, manager.getAppWidgetOptions(id), food, kit))
            }
        }

        /**
         * Виджет под все его размеры. С Android 12 лаунчер отдаёт список размеров —
         * по одному на экран (складной телефон: внешний узкий и внутренний), и
         * на каждый уходит своя раскладка: какую показать, лаунчер решает сам.
         * Раньше бралась ширина-минимум и высота-максимум из общего разброса — на
         * складном максимум высоты пришёл с внешнего экрана, и на внутреннем низ с
         * кнопками уехал за край (снимок владельца, 09.11.2026).
         * До Android 12 списка нет: портрет — ширина минимальная, высота
         * максимальная; пустые опции — заводские 4×4.
         */
        internal fun forOptions(app: PravkaApp, model: ZWidget.Model, options: Bundle, food: Boolean, kit: Kit = Kit()): RemoteViews {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sizes = BundleCompat.getParcelableArrayList(options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                    .orEmpty().filter { it.width >= 1f && it.height >= 1f }.distinct().take(16)
                if (sizes.isNotEmpty()) {
                    return RemoteViews(
                        sizes.associateWith { s ->
                            val w = s.width.toInt()
                            views(app, model, ZWidget.plan(w, s.height.toInt()), food, w, kit)
                        },
                    )
                }
            }
            val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 320
            val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 440
            return views(app, model, ZWidget.plan(w, h), food, w, kit)
        }

        /**
         * Картинки кадра, общие для раскладок всех размеров: одна и та же картинка в
         * посылке лаунчеру — один раз (RemoteViews узнаёт её по самому объекту).
         */
        internal class Kit {
            private val bitmaps = HashMap<String, Bitmap>()

            fun get(key: String, make: () -> Bitmap): Bitmap = bitmaps.getOrPut(key, make)
        }

        /** Виджет одного размера; `internal` — для снимков (`app/src/shots`). */
        internal fun views(
            app: PravkaApp,
            model: ZWidget.Model,
            plan: ZWidget.Plan,
            food: Boolean,
            widthDp: Int,
            kit: Kit = Kit(),
        ): RemoteViews {
            val v = RemoteViews(app.packageName, if (plan.wide) R.layout.widget_zasechka_wide else R.layout.widget_zasechka)
            val now = model.now
            // Узкий виджет (две ячейки): справа минутам места нет — название
            // обрезалось до «Д…» (снимок 11.10.2026). Там минуты в самом
            // кружке, под значком, ровно как на кнопке «З».
            val inFace = now != null && plan.narrow
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
                val bmp = kit.get("head:$icon:$inFace:$px") {
                    if (inFace) ZFaceGlyphs.bitmap(icon, PAPER, px, scale = 0.52f * 0.82f, lift = 0.11f)
                    else ZFaceGlyphs.bitmap(icon, PAPER, px)
                }
                v.setImageViewBitmap(R.id.w_icon, bmp)
            } else {
                v.setImageViewResource(R.id.w_icon, ModeGlyphs.zasechka())
            }

            v.setViewVisibility(R.id.w_strip_box, if (plan.strip) View.VISIBLE else View.GONE)
            if (plan.strip) {
                val d = app.resources.displayMetrics.density
                // Вширь полоса — в правой колонке: половина без полей и промежутка.
                val stripDp = if (plan.wide) (widthDp - 26 - 14) / 2 else widthDp - 26
                val bmp = kit.get("strip:$stripDp") {
                    strip(app, model.day, System.currentTimeMillis(), (stripDp * d).toInt(), (10 * d).toInt())
                }
                v.setImageViewBitmap(R.id.w_strip, bmp)
            }
            cats(app, v, model, plan, kit)
            rows(app, v, model, plan)
            v.setViewVisibility(R.id.w_actions, if (plan.actions) View.VISIBLE else View.GONE)
            // Еда и «Сегодня» — когда ряд шире двух ячеек; еда — если режим жив.
            val foodVis = if (!plan.narrow && food) View.VISIBLE else View.GONE
            v.setViewVisibility(R.id.w_photo, foodVis)
            v.setViewVisibility(R.id.w_gallery, foodVis)
            v.setViewVisibility(R.id.w_text, foodVis)
            v.setViewVisibility(R.id.w_today, if (!plan.narrow) View.VISIBLE else View.GONE)

            val take = quick(app, RC_TAKE, ZasechkaQuickActivity.W_RECORD)
            v.setOnClickPendingIntent(R.id.w_face_box, take)
            v.setOnClickPendingIntent(R.id.w_say, take)
            v.setOnClickPendingIntent(R.id.w_text_box, main(app, RC_TAB, MainActivity.TAB_ZASECHKA))
            v.setOnClickPendingIntent(R.id.w_min_box, main(app, RC_TAB, MainActivity.TAB_ZASECHKA))
            v.setOnClickPendingIntent(R.id.w_strip_box, main(app, RC_TAB, MainActivity.TAB_ZASECHKA))
            v.setOnClickPendingIntent(R.id.w_thought, quick(app, RC_THOUGHT, ZasechkaQuickActivity.W_THOUGHT))
            // Еда — сразу камерой, выбором снимка или строкой кнопки «Е», без вкладки (22.10.2026).
            v.setOnClickPendingIntent(R.id.w_photo, food(app, RC_PHOTO, FoodQuickActivity.W_PHOTO))
            v.setOnClickPendingIntent(R.id.w_gallery, food(app, RC_GALLERY, FoodQuickActivity.W_GALLERY))
            v.setOnClickPendingIntent(R.id.w_text, food(app, RC_TEXT, FoodQuickActivity.W_TEXT))
            v.setOnClickPendingIntent(R.id.w_today, main(app, RC_TODAY, MainActivity.TAB_TODAY))
            return v
        }

        /**
         * Кнопки категорий рядами по [ZWidget.Plan.cols]: плитки (значок над названием), вширь — таблетки (значок
         * слева). Идущая — янтарём, её касание открывает ленту; недобитый ряд добит пустыми местами, чтобы кнопки
         * не растягивались. Вширь место категорий тянется до ряда кнопок внизу левой колонки — пустым не прячется.
         */
        private fun cats(app: PravkaApp, v: RemoteViews, model: ZWidget.Model, plan: ZWidget.Plan, kit: Kit) {
            v.removeAllViews(R.id.w_cats)
            val list = model.cats.take(plan.cats)
            v.setViewVisibility(R.id.w_cats, if (list.isEmpty() && !plan.wide) View.GONE else View.VISIBLE)
            if (list.isEmpty()) return
            val px = ((if (plan.wide) 16 else 18) * app.resources.displayMetrics.density).toInt()
            for ((r, line) in list.chunked(plan.cols).withIndex()) {
                val row = RemoteViews(app.packageName, R.layout.widget_z_line)
                for (i in 0 until plan.cols) {
                    val chip = RemoteViews(app.packageName, if (plan.wide) R.layout.widget_z_pill else R.layout.widget_z_tile)
                    val c = line.getOrNull(i)
                    if (c == null) {
                        chip.setViewVisibility(R.id.w_cat, View.INVISIBLE)
                    } else {
                        chip.setTextViewText(R.id.w_cat_text, c.name)
                        chip.setContentDescription(R.id.w_cat, if (c.current) "Идёт: ${c.name}" else "Переключить на ${c.name}")
                        val ic = c.icon
                        if (ic != null) {
                            // Одна картинка на значок: «Передвижение» и «Транспорт» с одной машинкой — один битмап в посылке.
                            chip.setImageViewBitmap(R.id.w_cat_icon, kit.get("cat:$ic:$px") { ZFaceGlyphs.bitmap(ic, PAPER, px, scale = 0.95f) })
                        } else {
                            chip.setViewVisibility(R.id.w_cat_icon, View.GONE)
                        }
                        if (c.current) chip.setInt(R.id.w_cat, "setBackgroundResource", R.drawable.widget_tile_now)
                        chip.setOnClickPendingIntent(
                            R.id.w_cat,
                            if (c.current) main(app, RC_TAB, MainActivity.TAB_ZASECHKA)
                            else switchTo(app, RC_CAT + r * plan.cols + i, c.name),
                        )
                    }
                    row.addView(R.id.w_line, chip)
                }
                v.addView(R.id.w_cats, row)
            }
        }

        /**
         * Лента сегодня строками, свежее сверху; идущее дело — в шапке, здесь его нет. Касание — лента Засечки.
         * Место ленты не прячется и пустым: оно забирает остаток высоты, и ряд кнопок стоит внизу всегда — и когда
         * строк нет, и когда их больше, чем влезло (тогда обрезается лента, а не кнопки).
         */
        private fun rows(app: PravkaApp, v: RemoteViews, model: ZWidget.Model, plan: ZWidget.Plan) {
            v.removeAllViews(R.id.w_rows)
            val list = model.day.filter { it.to != 0L }.take(plan.rows)
            for (s in list) {
                val r = RemoteViews(app.packageName, R.layout.widget_z_row)
                r.setInt(R.id.w_row_bar, "setColorFilter", tint(s))
                r.setTextViewText(R.id.w_row_time, ZWidget.hm(s.from))
                r.setTextViewText(R.id.w_row_title, s.title)
                if (s.gap) r.setTextColor(R.id.w_row_title, MUTED)
                r.setTextViewText(R.id.w_row_min, ZWidget.dur(s.to - s.from))
                r.setOnClickPendingIntent(R.id.w_row, main(app, RC_TAB, MainActivity.TAB_ZASECHKA))
                v.addView(R.id.w_rows, r)
            }
        }

        /**
         * Цвет куска ленты — светофор цены часа, как в самой ленте (`ui/Tokens.worthTint`, тёмная тема:
         * виджет всегда на чернилах); «не размечено» и без категории — серой чертой.
         */
        private fun tint(s: ZWidget.Seg): Int =
            if (s.gap || s.category.isBlank()) GAP
            else android.graphics.Color.HSVToColor(
                floatArrayOf(ru.zf.pravka.ui.worthHue(ru.zf.pravka.ui.categoryWorth(s.category)), 0.42f, 0.88f),
            )

        /**
         * Полоса суток картинкой [w]×[h]: от полуночи до полуночи, прожитое — цветами категорий, «не размечено» —
         * серым, впереди — пустая дорожка, «сейчас» — янтарная черта. Часы под ней — текстом в раскладке.
         */
        private fun strip(app: PravkaApp, day: List<ZWidget.Seg>, now: Long, w: Int, h: Int): android.graphics.Bitmap {
            val bmp = android.graphics.Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val d = app.resources.displayMetrics.density
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val midnight = ZWidget.midnight(now)
            val dayMs = 24 * 3_600_000f
            fun x(t: Long) = ((t - midnight) / dayMs * bmp.width).coerceIn(0f, bmp.width.toFloat())
            val track = android.graphics.RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            val r = 3 * d
            c.save()
            c.clipPath(android.graphics.Path().apply { addRoundRect(track, r, r, android.graphics.Path.Direction.CW) })
            p.color = TRACK
            c.drawRect(track, p)
            for (s in day) {
                p.color = tint(s)
                c.drawRect(x(s.from), 0f, x(if (s.to == 0L) now else s.to), track.bottom, p)
            }
            p.color = AMBER
            c.drawRect(x(now) - d, 0f, x(now) + d, track.bottom, p)
            c.restore()
            return bmp
        }

        private fun switchTo(context: Context, code: Int, category: String): PendingIntent =
            PendingIntent.getActivity(
                context,
                code,
                Intent(context, ZasechkaQuickActivity::class.java)
                    .putExtra(ZasechkaQuickActivity.EXTRA_WHAT, ZasechkaQuickActivity.W_SWITCH)
                    .putExtra(ZasechkaQuickActivity.EXTRA_CATEGORY, category)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

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
        // Кнопки категорий — 100 и дальше по месту на виджете: код у места свой, дополнение (какая категория)
        // переписывается при каждой перерисовке.
        private const val RC_CAT = 100

        private val PAPER = 0xFFF7F3EA.toInt()
        private val MUTED = 0xFFA8998A.toInt()
        private val GAP = 0xFF5A4E44.toInt()
        private val TRACK = 0xFF2C251F.toInt()
        private val AMBER = 0xFFFFA640.toInt()

        private fun quick(context: Context, code: Int, what: String): PendingIntent =
            PendingIntent.getActivity(
                context,
                code,
                Intent(context, ZasechkaQuickActivity::class.java)
                    .putExtra(ZasechkaQuickActivity.EXTRA_WHAT, what)
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
