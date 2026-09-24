package ru.zf.pravka

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Расчёты вкладки «Деньги» — не на главном потоке и с памятью (владелец,
// 24.09.2026: «секунды 3–4 она открывается… какой-то очень масштабный
// расчёт»). Журнал — тысячи записей; итоги, ДДС, баланс и счета считались
// прямо при отрисовке, и на каждое открытие — заново.

/**
 * Ключ по ССЫЛКЕ: сравнивать журнал из 6 000 записей поэлементно на каждой
 * перерисовке (так делал `remember(state)`) — само по себе тормоз. Новый
 * журнал — новый объект: стор пишет копию, старый не меняется.
 */
internal class Ref(val v: Any?) {
    override fun equals(other: Any?) = other is Ref && other.v === v
    override fun hashCode() = System.identityHashCode(v)
}

/** Последние посчитанные значения: вкладку закрыли и открыли без новых записей — цифры сразу. */
internal object MoneyCalcCache {
    private val map = object : LinkedHashMap<List<Any?>, Any?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, Any?>?) = size > 48
    }

    @Synchronized fun get(key: List<Any?>): Any? = map[key]

    @Synchronized fun put(key: List<Any?>, value: Any?) { map[key] = value }
}

/**
 * Значение, посчитанное на фоне: null — ещё считается (или впервые). [tag]
 * отличает расчёты с одинаковыми ключами.
 */
@Suppress("UNCHECKED_CAST")
@Composable
internal fun <T : Any> rememberCalc(tag: String, vararg keys: Any?, compute: () -> T): T? {
    val k = listOf(tag) + keys.toList()
    val cached = MoneyCalcCache.get(k) as T?
    return produceState(initialValue = cached, *keys) {
        if (value == null) value = withContext(Dispatchers.Default) { compute().also { MoneyCalcCache.put(k, it) } }
    }.value
}
