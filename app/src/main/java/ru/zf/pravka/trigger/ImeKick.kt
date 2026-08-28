package ru.zf.pravka.trigger

import android.app.Service
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/**
 * Поднять клавиатуру для поля внутри окна поверх всего.
 *
 * Владелец: «когда я кликаю по баблу, чтобы напечатать вместо наговора,
 * клавиатура появляется только если я второй раз туда кликаю. бесит».
 *
 * Причина не в фокусе поля, а в фокусе ОКНА. Оверлей службы доступности
 * получает фокус асинхронно, уже после addView, а showSoftInput в тот же кадр
 * (даже из view.post) зовётся раньше — и метод молча возвращает false, потому
 * что окно ещё не фокусное. Со второго тапа окно фокусно, и всё работает.
 *
 * Поэтому просить надо не один раз, а пока не согласятся: showSoftInput
 * возвращает Boolean, и это единственный честный признак успеха. Плюс
 * подписка на смену фокуса окна — обычно именно она срабатывает первой.
 */
internal object ImeKick {

    private const val ATTEMPTS = 12
    private const val STEP_MS = 60L

    fun raise(service: Service, edit: EditText) {
        val imm = service.getSystemService(InputMethodManager::class.java) ?: return
        val handler = Handler(Looper.getMainLooper())
        edit.isFocusable = true
        edit.isFocusableInTouchMode = true

        fun ask(): Boolean {
            if (!edit.isAttachedToWindow) return true  // окно закрыли — прекращаем
            if (!edit.hasFocus()) edit.requestFocus()
            return runCatching {
                imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            }.getOrDefault(false)
        }

        // Фокус окна — тот самый момент, которого не хватало.
        runCatching {
            edit.viewTreeObserver.addOnWindowFocusChangeListener(
                object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
                    override fun onWindowFocusChanged(hasFocus: Boolean) {
                        if (!hasFocus) return
                        ask()
                        runCatching {
                            edit.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                        }
                    }
                }
            )
        }

        var left = ATTEMPTS
        val retry = object : Runnable {
            override fun run() {
                if (ask()) return
                if (--left > 0) handler.postDelayed(this, STEP_MS)
            }
        }
        edit.post(retry)
    }
}
