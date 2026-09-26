package ru.zf.pravka.trigger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import ru.zf.pravka.R
import ru.zf.pravka.ui.Feedback

/**
 * Кнопка гарнитуры. Прозрачный трамплин, как у метки NFC: команду «голосовой
 * помощник» Android отдаёт только Activity (`ACTION_VOICE_COMMAND`), — но
 * живёт она доли секунды и ничего не рисует.
 *
 * Откуда команда. Гарнитура по долгому нажатию (у Shokz OpenComm2 2025 —
 * Mute две секунды вне звонка; у машины — кнопка голоса на руле) шлёт по
 * Bluetooth «включи распознавание», стек телефона открывает на неё
 * приложение голосовых команд. Правка объявила себя таким приложением; в
 * первый раз система спросит «чем открыть» — «Правка, всегда». Спрашивает она
 * только на разблокированном экране, поэтому первый раз — не с локскрина.
 *
 * Решение — в службе и сразу, в onCreate: пока окно трамплина не встало,
 * фокус ещё у поля, где стоял курсор, и «с полем или без» решается
 * по-настоящему (`ServiceHeadset.kt`).
 *
 * Назначение (26.09.2026; владелец после первой сборки: «ничего не работает.
 * Не реагирует телефон на кнопку. Хотя раньше запускал gemini»). Как только
 * Правка объявила себя приложением голосовых команд, Android сбросил прежнее
 * «всегда Gemini» и на нажатие гарнитуры должен спросить, чем открыть, — а на
 * заблокированном экране спросить не может, и снаружи это «ничего». Поэтому
 * спросить можно заранее и в руках: «Назначить Правку» в настройках шлёт ту же
 * команду с [EXTRA_SETUP], система показывает выбор, и трамплин на такой
 * вызов тейк не заводит — только подтверждает.
 */
class HeadsetButtonActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle()
        // Экран не нужен ни на кадр: тейк живёт в службе.
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle()
        finish()
    }

    private fun handle() {
        val app = application as? ru.zf.pravka.PravkaApp
        val setup = intent?.getBooleanExtra(EXTRA_SETUP, false) == true
        // Первой строкой и до службы: «дошло ли нажатие до Правки вообще» —
        // главный вопрос, когда кнопка «не реагирует».
        app?.eventLog?.add(if (setup) "гарнитура: назначение — команда голоса дошла до Правки" else "гарнитура: команда голоса пришла")
        if (setup) {
            Feedback.toast(this, "Готово: кнопка гарнитуры — за Правкой")
            return
        }
        val service = PravkaAccessibilityService.instance
        if (service == null) {
            Feedback.toast(this, getString(R.string.toast_no_service))
            return
        }
        service.onHeadsetButton()
    }

    /** Кто сейчас отвечает на кнопку гарнитуры — для строки в настройках. */
    sealed class Answer {
        data object Pravka : Answer()
        /** Несколько приложений и ни одно не выбрано — система спросит при нажатии. */
        data object Ask : Answer()
        data class Other(val packageName: String, val label: String) : Answer()
        data object Nobody : Answer()
    }

    companion object {

        /** Вызов из настроек «Назначить Правку»: тейк не заводить, только подтвердить. */
        const val EXTRA_SETUP = "ru.zf.pravka.HEADSET_SETUP"

        /**
         * Показать системный выбор «чем открыть» для команды голоса — в руках и
         * на разблокированном экране, где система его показать может. Правка —
         * «Всегда», и дальше нажатие гарнитуры идёт к ней и с замка.
         */
        fun askAssign(context: Context): Boolean = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VOICE_COMMAND)
                    .putExtra(EXTRA_SETUP, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess

        /**
         * Чем гарнитура связана с телефоном. Команда помощника (AT+BVRA) ходит
         * ТОЛЬКО по профилю звонков (HFP): гарнитура, подключённая одной
         * музыкой, или отдавшая звонки адаптеру Loop120 у компьютера, жмёт
         * кнопку в пустоту. 26.09.2026 назначение до Правки дошло, а ни одно
         * нажатие — нет: рвётся раньше Правки, и это первое, что видно здесь.
         *
         * [calls] / [music]: true — подключена, false — нет, null — не узнать
         * (нет разрешения «Устройства поблизости»).
         */
        data class Link(val calls: Boolean?, val music: Boolean?, val callNames: List<String>)

        @android.annotation.SuppressLint("MissingPermission") // проверяем разрешение сами
        fun link(context: Context): Link {
            val allowed = android.os.Build.VERSION.SDK_INT < 31 ||
                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            val adapter = runCatching {
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            }.getOrNull()
            fun state(profile: Int): Boolean? = if (!allowed || adapter == null) null else runCatching {
                adapter.getProfileConnectionState(profile) == android.bluetooth.BluetoothProfile.STATE_CONNECTED
            }.getOrNull()
            // Имя — у системы звука: устройство связи Bluetooth есть ровно тогда,
            // когда гарнитура подключена по звонкам. Разрешения не требует.
            val names = runCatching {
                val am = context.getSystemService(android.media.AudioManager::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    am.availableCommunicationDevices
                        .filter { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                        .map { it.productName.toString() }
                } else {
                    am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                        .filter { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                        .map { it.productName.toString() }
                }
            }.getOrDefault(emptyList()).distinct()
            return Link(
                calls = state(android.bluetooth.BluetoothProfile.HEADSET) ?: if (names.isNotEmpty()) true else null,
                music = state(android.bluetooth.BluetoothProfile.A2DP),
                callNames = names,
            )
        }

        /** Приложение, объявившее команду голоса, и его приоритет у системы. */
        data class Handler(val packageName: String, val label: String, val priority: Int)

        /**
         * Все, кто принимает команду голоса, — для диагностики на экране
         * (26.09.2026: назначили Правку, а кнопка всё равно молчит). Приоритет
         * фильтра честно учитывается только у системных приложений, и если у
         * Google он выше, выбор «Всегда» его не перебьёт: система отдаст
         * команду ему, не спрашивая. Это видно здесь, а не гадается.
         */
        fun handlers(context: Context): List<Handler> = runCatching {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_VOICE_COMMAND), PackageManager.MATCH_DEFAULT_ONLY)
                .map {
                    val pkg = it.activityInfo?.packageName.orEmpty()
                    Handler(pkg, it.loadLabel(pm)?.toString().orEmpty().ifBlank { pkg }, it.priority)
                }
                .sortedByDescending { it.priority }
        }.getOrDefault(emptyList())

        /**
         * Молчаливая механика читается как поломка (правило 6): если «всегда»
         * когда-то выбрали за Google, Правка кнопку гарнитуры не получит
         * никогда, и снаружи это ровно «не работает».
         */
        fun whoAnswers(context: Context): Answer = runCatching {
            val pm = context.packageManager
            val ri = pm.resolveActivity(Intent(Intent.ACTION_VOICE_COMMAND), PackageManager.MATCH_DEFAULT_ONLY)
                ?: return@runCatching Answer.Nobody
            val pkg = ri.activityInfo?.packageName.orEmpty()
            when {
                pkg == context.packageName -> Answer.Pravka
                // Выбор «чем открыть» — системный: android или отдельный модуль.
                pkg == "android" || pkg.contains("intentresolver") || pkg.contains("resolver") -> Answer.Ask
                else -> Answer.Other(pkg, ri.loadLabel(pm)?.toString().orEmpty().ifBlank { pkg })
            }
        }.getOrDefault(Answer.Nobody)
    }
}
