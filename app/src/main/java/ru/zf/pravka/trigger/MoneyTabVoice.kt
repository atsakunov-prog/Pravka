package ru.zf.pravka.trigger

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Ход наговора, заказанного вкладкой «Деньги»: кто заказал (карточка
 * вопроса, поле «спросить Claude») и что уже слышно. Служба пишет, вкладка
 * читает — чтобы «слушаю…» и живой текст были видны и там, где кнопки «₽»
 * на экране нет.
 */
object MoneyTabVoice {
    data class Live(val owner: String, val text: String)

    val live = MutableStateFlow<Live?>(null)
}
