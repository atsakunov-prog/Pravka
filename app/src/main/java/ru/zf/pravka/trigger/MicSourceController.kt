package ru.zf.pravka.trigger

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.zf.pravka.R
import ru.zf.pravka.core.StackGeometry
import ru.zf.pravka.data.Settings
import ru.zf.pravka.provider.MicRouting
import ru.zf.pravka.ui.Haptics

/**
 * Значок между «П» и «З»: кто слушает диктовку — телефон или Bluetooth-гарнитура.
 *
 * Владелец (13.09.2026): «между правкой и засечкой будет иконка размером в
 * треть от них. Это кнопочка-переключалка между БТ и микрофоном телефона. Она
 * будет показывать, кто слушает, и если на неё нажимаешь, то переключается».
 * Заглушка на время, пока помощник с кнопки гарнитуры не готов; правило
 * «микрофон выбирает владелец, а не подключение» — в `docs/agreements.md`.
 *
 * Состояние одно на всё приложение — тумблер «микрофон телефона» в Общих
 * (`Settings.phoneMicOnlyFlow`): значок его показывает и переключает, ничего
 * своего не хранит. Телефон — серый, как ручки: это положение по умолчанию и
 * должно молчать. Гарнитура — синий: не-умолчание обязано быть видно, иначе
 * значок, забытый на гарнитуре, даёт ту самую «Правка не слышит» в машине.
 * Гарнитура выбрана, а среди входов её нет — значок бледнеет.
 *
 * Не кнопка режима: не таскается (едет за «П», как ручки), окно снимается из
 * WindowManager при складывании Fold и когда всё убрано в верхнюю ручку.
 */
class MicSourceController(
    private val service: PravkaAccessibilityService,
    private val scope: CoroutineScope,
    private val settings: Settings,
) {

    private companion object {
        /** Приглушённый «ink-soft», как у ручек: телефон — положение по умолчанию. */
        private val GREY = 0xFF6E6659.toInt()
        /** Гарнитура: свой синий, не чернильный «Д» и не янтарь пары. */
        private val HEADSET = 0xFF2B7FA3.toInt()
        private val PAPER = 0xFFF7F3EA.toInt()
        private const val ALPHA = 0.72f
        /** Гарнитура выбрана, а её не видно среди входов. */
        private const val ALPHA_ABSENT = 0.32f
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val audioManager = service.getSystemService(AudioManager::class.java)
    private val density = service.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()
    private val pad = dp(5)
    private val main = Handler(Looper.getMainLooper())

    private var frame: FrameLayout? = null
    private var icon: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var buttonSize = 0
    private var headset = false
    private var headsetPresent = false
    private var deviceCallback: AudioDeviceCallback? = null

    /** Любое касание значка — стопке отсчёт простоя заново (`touched()`). */
    var onTap: (() -> Unit)? = null

    /** Высота слота между «П» и «З» при кнопке [buttonSize]: кружок в треть и поля. */
    fun slotPx(buttonSize: Int): Int = StackGeometry.toggleSlot(buttonSize, pad)

    init {
        scope.launch {
            settings.phoneMicOnlyFlow.collect {
                headset = !it
                applyLook()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (frame != null) return
        val slot = slotPx(buttonSize.coerceAtLeast(dp(Settings.FAB_SIZE_DEFAULT)))
        val circle = StackGeometry.toggleSize(buttonSize.coerceAtLeast(dp(Settings.FAB_SIZE_DEFAULT)))
        val f = FrameLayout(service)
        val iv = ImageView(service).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(GREY)
            }
            elevation = dp(3).toFloat()
            imageTintList = ColorStateList.valueOf(PAPER)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        f.addView(iv, FrameLayout.LayoutParams(circle, circle, Gravity.CENTER))
        // Тап-зона — весь слот, а не кружок в 16 dp: в кружок такой величины
        // пальцем не попасть, а промах уходит в кнопку под ним.
        f.setOnClickListener { flip() }
        val p = WindowManager.LayoutParams(
            slot,
            slot,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        params = p
        frame = f
        icon = iv
        runCatching { windowManager.addView(f, p) }
        watchDevices()
        refreshPresence()
        applyLook()
    }

    /** Именно removeView, а не GONE: скрытое окно стоит столько же, сколько видимое. */
    fun hide() {
        frame?.let { runCatching { windowManager.removeView(it) } }
        frame = null
        icon = null
        params = null
        deviceCallback?.let { runCatching { audioManager.unregisterAudioDeviceCallback(it) } }
        deviceCallback = null
    }

    /** Перепись окон для журнала складывания. */
    fun windowCount(): Int = if (frame != null) 1 else 0

    /** Слот сразу под «П», по её оси; размер пересчитывается под текущую кнопку. */
    fun moveTo(buttonX: Int, buttonY: Int, buttonSize: Int) {
        val p = params ?: return
        val f = frame ?: return
        if (buttonSize != this.buttonSize && buttonSize > 0) {
            this.buttonSize = buttonSize
            val slot = slotPx(buttonSize)
            p.width = slot
            p.height = slot
            val circle = StackGeometry.toggleSize(buttonSize)
            icon?.layoutParams = FrameLayout.LayoutParams(circle, circle, Gravity.CENTER)
            applyLook()
        }
        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
        val w = bounds?.width() ?: 0
        val h = bounds?.height() ?: 0
        p.x = (buttonX + (buttonSize - p.width) / 2).coerceIn(0, (w - p.width).coerceAtLeast(0))
        p.y = (buttonY + buttonSize).coerceIn(0, (h - p.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(f, p) }
    }

    private fun flip() {
        val next = !headset
        // Значок отвечает сразу, не дожидаясь DataStore; коллектор в init
        // подтвердит то же значение.
        headset = next
        applyLook()
        scope.launch { settings.setPhoneMicOnly(!next) }
        Haptics.start(service)
        service.app.eventLog.add(
            if (next) "микрофон: Bluetooth-гарнитура" + (if (headsetPresent) "" else " (сейчас не подключена)")
            else "микрофон: телефон",
        )
        onTap?.invoke()
    }

    private fun applyLook() {
        val iv = icon ?: return
        iv.setImageResource(if (headset) R.drawable.ic_mic_headset else R.drawable.ic_mic_phone)
        (iv.background as? GradientDrawable)?.setColor(if (headset) HEADSET else GREY)
        val circle = iv.layoutParams?.width ?: 0
        val inset = (circle * 0.2f).toInt()
        iv.setPadding(inset, inset, inset, inset)
        frame?.alpha = if (headset && !headsetPresent) ALPHA_ABSENT else ALPHA
    }

    private fun refreshPresence() {
        headsetPresent = runCatching { MicRouting.headsetMic(audioManager) != null }.getOrDefault(false)
    }

    /** Подключилась или отвалилась гарнитура — значок бледнеет или оживает. Колбэк лёгкий. */
    private fun watchDevices() {
        if (deviceCallback != null) return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refreshPresence()
                applyLook()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refreshPresence()
                applyLook()
            }
        }
        deviceCallback = cb
        runCatching { audioManager.registerAudioDeviceCallback(cb, main) }
    }
}
