package ru.zf.pravka.trigger

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.DiskLook
import ru.zf.pravka.core.MicLevel
import ru.zf.pravka.core.PillGeometry
import ru.zf.pravka.core.PillLook

/**
 * Пилюля диктовки — бегущая строка в одежде Gemini, одна на пять кнопок.
 *
 * Владелец (26.09.2026), со снимками Gemini: «мне очень нравится… как там
 * внизу вылезает такая маленькая плашка… Она будет вылезать не рядом с
 * кнопкой, а я вот нажимаю, допустим, на правку, и вот эта штука так
 * всплывает. Причём над клавиатурой, если клавиатура включена, или внизу
 * экрана… всплывать снизу… наверху немножко так вниз делает… — оп — назад.
 * И дальше точно так же всё поведение, как в плашке».
 *
 * Поэтому поведение прежнее, до вызова: `showTicker` / `updateTicker` /
 * `hideTicker` у каждой кнопки теперь просто зовут сюда, у «З», «Д», «₽» и
 * «Е» тап по пилюле по-прежнему глушит микрофон и открывает набор, у «П» она
 * по-прежнему не ловит касаний. Новое — место, вид и движение:
 *
 *  - **место** — посередине над клавиатурой или над навигацией
 *    (`core/PillGeometry.kt`), кнопки на экране и «отмену» не накрывает; выбор
 *    «Снизу · У кнопки» в «Кнопках на экране» возвращает прежнее место;
 *  - **вид** — стекло цвета режима, уведённого в чернила, с градиентом к
 *    кружку голоса справа и свечением от него; слева знак режима — там, где у
 *    Gemini плюс (`core/PillLook.kt`);
 *  - **движение** — всплывает снизу на пружине с одним проскоком и уходит
 *    вниз.
 *
 * Клавиатуру служба не подсматривает: правило Fold — «за чужими окнами не
 * подглядывать, на оконные события не подписываться». Высоту клавиатуры
 * отдаёт метрика СВОЕГО окна (`WindowManager.currentWindowMetrics` —
 * «сырые» отступы экрана, клавиатура в них есть), одним вызовом в
 * WindowManager, и не на главном потоке службы: на показ и раз в
 * [POLL_MS], пока пилюля видна, — клавиатура может открыться посреди тейка.
 * Складывание идёт — не спрашиваем вовсе.
 *
 * Окно — ровно пилюля и небольшой запас над ней ([PillLook.ROOM_DP]): в
 * него помещается проскок пружины. На время всплытия и ухода окно
 * вытягивается вниз на глубину всплытия, после — ужимается обратно, не
 * двигая верх: пилюля внутри окна при этом не сдвигается ни на пиксель.
 * Окно, которое и едет, и растёт, система догоняет рывком — здесь оно только
 * меняет высоту, и только по прозрачному краю.
 */
class DictationPill(
    private val service: PravkaAccessibilityService,
    private val scope: CoroutineScope,
    private val accent: Int,
    @param:DrawableRes private val glyph: Int,
    /** «З», «Д», «₽», «Е» — да (тап — набор вместо голоса); «П» — нет. */
    private val touchable: Boolean,
    private val textSizeSp: Float,
    /** Размер экрана у кнопки-хозяйки: у неё он уже закэширован. */
    private val screen: () -> Pair<Int, Int>,
    /** Кнопка-хозяйка на экране — для места «У кнопки». */
    private val owner: () -> PillGeometry.Box?,
    /** Своё, что накрывать нельзя, помимо кнопок: «отмена» этой записи. */
    private val ownObstacles: () -> List<PillGeometry.Box> = { emptyList() },
) {

    companion object {
        private val PAPER = 0xFFF7F3EA.toInt()
        /** Как часто спрашивать клавиатуру, пока пилюля видна. */
        private const val POLL_MS = 400L
        /** Меньше этого пилюля за клавиатурой не переезжает, dp. */
        private const val MOVE_DP = 3

        // Клавиатура и навигация — общие на все пилюли: последнее, что
        // узнали. Новая пилюля встаёт по ним сразу, пока не пришёл свежий ответ.
        @Volatile private var lastIme = 0
        @Volatile private var lastNav = 0
        /** Что последним записали в журнал: пишем только перемену, а не каждый тейк. */
        private var loggedIme = -1
    }

    /** Тап по пилюле — только у [touchable]. */
    var onTap: (() -> Unit)? = null

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    private var root: FrameLayout? = null
    private var body: LinearLayout? = null
    private var ticker: MarqueeTickerView? = null
    private var orb: VoiceOrb? = null
    private var skin: PillSkin? = null
    private var params: WindowManager.LayoutParams? = null

    /** Пилюля должна быть на экране (окна может ещё не быть — ждём клавиатуру). */
    private var visible = false
    /** Номер показа: ответ про клавиатуру для старого показа — выбросить. */
    private var gen = 0
    private var animator: ValueAnimator? = null
    /** Идёт всплытие или уход: окну нужен запас снизу. */
    private var travelling = false
    private var pollJob: Job? = null
    /** Текст, пришедший раньше окна: окно ждёт ответа про клавиатуру. */
    private var pendingText: String? = null
    private var lastText = ""
    private var lastAt = 0L
    private var level = 0f
    /** Где стоит этот показ: снизу или у кнопки — решено на показе. */
    private var atBottom = true

    val windowCount: Int get() = if (root != null) 1 else 0

    // ---- Показ, текст, уход ----

    fun show() {
        val already = visible
        visible = true
        lastText = ""
        lastAt = 0L
        pendingText = null
        if (root != null) {
            // Каждый показ — с чистой строки, как было у прежней плашки.
            ticker?.reset()
            // Висит и так (идёт всплытие — пусть доигрывает) — всё.
            if (already) return
            // Окно ещё висит, но уходит после hide(). Конец тейка и начало
            // стрима чистки идут вплотную (hide → show): вернуть на место без
            // второго всплытия, иначе пилюля подпрыгивала бы между ними.
            stopTravel()
            body?.translationY = 0f
            body?.alpha = 1f
            rest()
            place()
            startPolling()
            return
        }
        // Окна ещё нет, но оно уже в пути — ждёт ответа про клавиатуру.
        if (already) return
        level = 0f
        atBottom = service.cachedTickerBottom
        if (!atBottom) {
            create()
            place()
            fadeIn()
            return
        }
        val g = ++gen
        scope.launch {
            val changed = refreshInsets()
            if (g != gen || !visible || root != null) return@launch
            if (changed) logInsets()
            create()
            enter()
            startPolling()
        }
    }

    fun update(text: String, force: Boolean = false) {
        if (text == lastText) return
        val now = SystemClock.uptimeMillis()
        // [force] — для подсказки службы («секунду…», «говори»): она одна за
        // тейк, и проглотить её потолком частоты значит соврать о том, слышит
        // движок или ещё нет. Остальное — не чаще раза в 60 мс: строка всё
        // равно сглаживает движение сама.
        if (!force && now - lastAt < 60) return
        lastAt = now
        lastText = text
        val tv = ticker
        if (tv == null) {
            if (visible) pendingText = text
            return
        }
        tv.setTickerText(text)
    }

    /** Громкость 0..1 — волна в кружке. Сглаживание то же, что у пульса кнопки. */
    fun setLevel(value: Float) {
        level = MicLevel.smooth(level, value)
        orb?.setLevel(level)
    }

    /** Запись кончилась: волна — снова значок. */
    fun rest() {
        level = 0f
        orb?.setLevel(0f)
    }

    /** Кнопку тащат, экран повернули, ширину или плотность поменяли. */
    fun reposition() {
        if (!visible || root == null) return
        skin?.setDensity(service.cachedTickerDensity)
        place()
    }

    fun hide() {
        if (!visible) return
        visible = false
        gen++
        stopPolling()
        pendingText = null
        val b = body
        if (root == null || b == null) return
        stopTravel()
        if (!atBottom) {
            travel(PillLook.EXIT_MS, AccelerateInterpolator()) { f -> b.alpha = 1f - f }
            return
        }
        // Уход вниз — по вытянутому окну: иначе низ пилюли срезался бы краем.
        travelling = true
        place()
        val from = b.translationY
        val to = dp(PillLook.RISE_DP) * 0.7f
        travel(PillLook.EXIT_MS, AccelerateInterpolator()) { f ->
            b.translationY = from + (to - from) * f
            b.alpha = 1f - f
        }
    }

    fun destroy() {
        visible = false
        gen++
        stopPolling()
        drop()
    }

    // ---- Окно ----

    private fun create() {
        val pillH = dp(PillLook.HEIGHT_DP)
        val orbSize = pillH - dp(14)
        val orbEnd = dp(7)
        val s = PillSkin(accent, orbCentreFromRight = orbEnd + orbSize / 2f).apply {
            setDensity(service.cachedTickerDensity)
        }
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = s
            // Дети режутся по контуру пилюли: и текст, и свечение, и кружок.
            clipToOutline = true
        }
        val mark = ImageView(service).apply {
            setImageResource(glyph)
            setColorFilter(PAPER)
            alpha = 0.82f
        }
        row.addView(mark, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(18) })
        val tv = MarqueeTickerView(service, textColor = PAPER, textSizeSp = textSizeSp)
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(4)
        })
        val o = VoiceOrb(service, PillLook.orb(accent)).apply { setLevel(level) }
        row.addView(o, LinearLayout.LayoutParams(orbSize, orbSize).apply {
            marginStart = dp(4)
            marginEnd = orbEnd
        })
        if (touchable) row.setOnClickListener { onTap?.invoke() }
        val frame = FrameLayout(service)
        frame.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, pillH).apply {
            gravity = Gravity.TOP
            topMargin = room()
        })
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        // Снизу окно на время всплытия вытянуто за край экрана — без этого
        // флага WindowManager прижал бы его обратно, и пилюля подпрыгнула бы.
        if (atBottom) flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val p = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        root = frame
        body = row
        ticker = tv
        orb = o
        skin = s
        params = p
        pendingText?.let { tv.setTickerText(it) }
        pendingText = null
        spotFor()?.let { layout(p, it) }
        runCatching { windowManager.addView(frame, p) }
    }

    private fun room(): Int = if (atBottom) dp(PillLook.ROOM_DP) else 0

    /** Место на экране по нынешним кнопкам, клавиатуре и настройкам. */
    private fun spotFor(): PillGeometry.Spot? {
        val (w, h) = screen()
        val pillH = dp(PillLook.HEIGHT_DP)
        val want = dp(service.cachedTickerWidthDp)
        if (!atBottom) {
            val b = owner() ?: return null
            return PillGeometry.beside(w, h, b, want, pillH, gap = dp(8), margin = dp(24))
        }
        val floor = PillGeometry.floor(h, lastIme, lastNav, dp(PillLook.GAP_DP))
        return PillGeometry.bottom(
            w, floor, want, pillH,
            side = dp(PillLook.SIDE_DP),
            gap = dp(PillLook.GAP_DP),
            minW = dp(PillLook.MIN_WIDTH_DP),
            obstacles = service.pillObstacles() + ownObstacles(),
        )
    }

    /** Параметры окна под место: запас сверху, пилюля, и на ходу — глубина всплытия снизу. */
    private fun layout(p: WindowManager.LayoutParams, spot: PillGeometry.Spot): Boolean {
        val room = room()
        val rise = if (atBottom && travelling) dp(PillLook.RISE_DP) else 0
        val x = spot.x
        val y = spot.y - room
        val width = spot.width
        val height = room + dp(PillLook.HEIGHT_DP) + rise
        if (p.x == x && p.y == y && p.width == width && p.height == height) return false
        p.x = x
        p.y = y
        p.width = width
        p.height = height
        return true
    }

    private fun place() {
        val r = root ?: return
        val p = params ?: return
        val spot = spotFor() ?: return
        if (layout(p, spot)) runCatching { windowManager.updateViewLayout(r, p) }
    }

    // ---- Движение ----

    /** Всплытие снизу: пружина с одним проскоком вверх («оп — назад»). */
    private fun enter() {
        val b = body ?: return
        val rise = dp(PillLook.RISE_DP).toFloat()
        b.translationY = rise
        b.alpha = 0f
        travelling = true
        place()
        travel(PillLook.ENTER_MS, LinearInterpolator()) { t ->
            b.translationY = rise * (1f - PillLook.spring(t))
            b.alpha = PillLook.enterAlpha(t)
        }
    }

    /** У кнопки — прежнее проявление на месте. */
    private fun fadeIn() {
        val b = body ?: return
        b.alpha = 0f
        travel(180L, LinearInterpolator()) { f -> b.alpha = f }
    }

    /**
     * Один аниматор на всё движение. ValueAnimator, а не `view.animate()`:
     * тот на только что прикреплённом окне не стартует, пока окно не прошло
     * компоновку (урок «кнопка правки пропала» в `FloatingButtonController`).
     */
    private fun travel(ms: Long, interp: android.animation.TimeInterpolator, step: (Float) -> Unit) {
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = interp
        }
        a.addUpdateListener { step(it.animatedFraction) }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Отменённый — не наш: его место уже занял следующий.
                if (animator !== a) return
                animator = null
                step(1f)
                travelling = false
                if (visible) {
                    body?.translationY = 0f
                    body?.alpha = 1f
                    place()  // ужать окно обратно — только низ, верх на месте
                } else {
                    drop()
                }
            }
        })
        animator = a
        a.start()
    }

    private fun stopTravel() {
        val a = animator ?: return
        animator = null
        a.cancel()
        travelling = false
    }

    private fun drop() {
        stopTravel()
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        body = null
        ticker = null
        orb = null
        skin = null
        params = null
        lastText = ""
        lastAt = 0L
    }

    // ---- Клавиатура ----

    /**
     * Спросить отступы экрана: клавиатура и навигация снизу. Не на главном
     * потоке — это вызов в WindowManager, а главный поток службы занят
     * кнопками. true — что-то поменялось.
     */
    private suspend fun refreshInsets(): Boolean = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext false
        runCatching {
            val insets = windowManager.currentWindowMetrics.windowInsets
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val changed = ime != lastIme || nav != lastNav
            lastIme = ime
            lastNav = nav
            changed
        }.getOrDefault(false)
    }

    private fun logInsets() {
        val ime = (lastIme / density).toInt()
        if (ime == loggedIme) return
        loggedIme = ime
        service.logEvent(
            if (ime > 0) "пилюля: над клавиатурой ${ime} dp" else "пилюля: клавиатуры нет, над навигацией"
        )
    }

    private fun startPolling() {
        stopPolling()
        if (!atBottom) return
        pollJob = scope.launch {
            while (isActive && visible) {
                delay(POLL_MS)
                if (service.folding || !visible) continue
                val before = params?.y
                if (!refreshInsets() || !visible) continue
                logInsets()
                val spot = spotFor() ?: continue
                val now = before ?: continue
                // Клавиатура открылась или закрылась посреди тейка: пилюля
                // всплывает заново уже на новом полу — тем же движением.
                if (abs(spot.y - room() - now) >= dp(MOVE_DP) && animator == null) enter() else place()
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ---- Кружок голоса: круг цвета режима и волна из трёх полосок ----

    private class VoiceOrb(context: Context, color: Int) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = PAPER
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private var level = 0f

        fun setLevel(value: Float) {
            if (abs(value - level) < 0.01f) return
            level = value
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val r = minOf(w, h) / 2f
            // Блик сверху, как у кнопок на стекле: кружок — клавиша, а не пятно.
            sheen.shader = RadialGradient(
                w / 2f, h / 2f - r * 0.6f, r * 1.2f,
                intArrayOf(DiskLook.white(0.22f), DiskLook.white(0.06f), DiskLook.white(0f)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            bar.strokeWidth = r * 0.17f
        }

        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) / 2f
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, sheen)
            val bars = PillLook.bars(level)
            val maxH = r * 0.9f
            val step = r * 0.36f
            for (i in 0..2) {
                val x = cx + (i - 1) * step
                val half = maxH * bars[i] / 2f
                canvas.drawLine(x, cy - half, x, cy + half, bar)
            }
        }
    }

    // ---- Стекло пилюли ----

    /**
     * Заливка слева направо (почти чернила → цвет режима гуще), свечение от
     * кружка голоса, блик по верхней трети и светлая кромка. Плотность
     * гасит заливку сильно, свечение и кромку — слабо: на прозрачном стекле
     * форму держит край, а цвет режима — свечение.
     */
    private class PillSkin(private val accent: Int, private val orbCentreFromRight: Float) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val face = RectF()
        private var density = PillLook.DENSITY_DEFAULT

        fun setDensity(value: Float) {
            if (value == density && fill.shader != null) return
            density = value
            build(bounds)
            invalidateSelf()
        }

        override fun onBoundsChange(bounds: Rect) = build(bounds)

        private fun build(b: Rect) {
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val d = density.coerceIn(0f, 1f)
            fill.shader = LinearGradient(
                b.left.toFloat(), 0f, b.right.toFloat(), 0f,
                DiskLook.withAlpha(PillLook.bodyStart(accent), d),
                DiskLook.withAlpha(PillLook.bodyEnd(accent), d),
                Shader.TileMode.CLAMP,
            )
            val g = PillLook.glowAlpha(d)
            glow.shader = RadialGradient(
                b.right - orbCentreFromRight, b.exactCenterY(), h * 1.9f,
                intArrayOf(
                    DiskLook.withAlpha(accent, g),
                    DiskLook.withAlpha(accent, g * 0.35f),
                    DiskLook.withAlpha(accent, 0f),
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            val top = b.top.toFloat()
            sheen.shader = LinearGradient(
                0f, top, 0f, top + h * 0.45f,
                DiskLook.white(PillLook.sheenAlpha(d)), DiskLook.white(0f),
                Shader.TileMode.CLAMP,
            )
            rim.strokeWidth = (h * 0.018f).coerceAtLeast(1f)
            val rimA = PillLook.rimAlpha(d)
            rim.shader = LinearGradient(
                0f, top, 0f, b.bottom.toFloat(),
                DiskLook.white(rimA), DiskLook.white(rimA * 0.35f),
                Shader.TileMode.CLAMP,
            )
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            face.set(b)
            val r = face.height() / 2f
            canvas.drawRoundRect(face, r, r, fill)
            canvas.drawRoundRect(face, r, r, glow)
            canvas.drawRoundRect(face, r, r, sheen)
            val inset = rim.strokeWidth / 2f
            face.inset(inset, inset)
            canvas.drawRoundRect(face, r - inset, r - inset, rim)
        }

        override fun getOutline(outline: Outline) {
            outline.setRoundRect(bounds, bounds.height() / 2f)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
