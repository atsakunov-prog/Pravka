package ru.zf.pravka.trigger

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import ru.zf.pravka.data.Profile

/**
 * Входы режима снаружи приложения: пункт «Поделиться» Денег, «Разноска» в
 * меню выделения текста, касание метки NFC Засечки (25.09.2026).
 *
 * Выключенный в профиле режим не должен торчать в чужих меню: у Серёжи без
 * Денег «Поделиться → Правка: Деньги» — пункт, который ведёт в никуда. Поэтому
 * компонент выключается в системе целиком (система сама убирает его из меню),
 * а не проверяет режим уже после запуска. Пуши банка слушает служба — её
 * компонент не трогаем: выданный доступ к уведомлениям привязан к нему, и
 * выключение сбросило бы его; режим она проверяет сама.
 */
internal object ModeEntries {

    private val ENTRIES = mapOf(
        "ru.zf.pravka.trigger.NfcTapActivity" to Profile.Mode.ZASECHKA,
        "ru.zf.pravka.trigger.ProcessTasksActivity" to Profile.Mode.DELA,
        "ru.zf.pravka.trigger.MoneyShareActivity" to Profile.Mode.MONEY,
    )

    /** Привести компоненты к профилю. Системные вызовы — звать не на главном потоке. */
    fun sync(context: Context, profile: Profile?) {
        val pm = context.packageManager
        for ((cls, mode) in ENTRIES) {
            val want = profile?.has(mode) ?: true
            val state = if (want) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val cn = ComponentName(context.packageName, cls)
            runCatching {
                if (pm.getComponentEnabledSetting(cn) != state) {
                    pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
                }
            }
        }
    }
}
