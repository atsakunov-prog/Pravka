package ru.zf.pravka.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

// Tactile feedback (spec section 8): distinct patterns for start, success, error.
object Haptics {

    private fun vibrator(context: Context): Vibrator {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        return manager.defaultVibrator
    }

    /** One short buzz - proofreading started. */
    fun start(context: Context) {
        vibrator(context).vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Two short buzzes - success. */
    fun success(context: Context) {
        vibrator(context).vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 35, 90, 35), -1)
        )
    }

    /**
     * Щелчок — диск кнопок прошёл очередную четверть под пальцем. Системный
     * EFFECT_TICK там, где он есть: он короче и суше любого одиночного
     * импульса; иначе десять миллисекунд.
     */
    fun tick(context: Context) {
        val v = vibrator(context)
        val effect = if (android.os.Build.VERSION.SDK_INT >= 29) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching { v.vibrate(effect) }
    }

    /** One long buzz - error or refusal. */
    fun error(context: Context) {
        vibrator(context).vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
