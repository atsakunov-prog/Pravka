package ru.zf.pravka

import android.content.pm.PackageManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.zf.pravka.core.PhoneRemote
import ru.zf.pravka.core.PhoneRemote.Item
import ru.zf.pravka.core.PhoneRemote.Opt
import ru.zf.pravka.core.PhoneRemote.Type
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.Models

/**
 * Настройка телефона, видимая с компьютера (страница «Телефон» в вебе Дел; описание —
 * docs/phone-settings-server.md). [flow] — значение, за которым телефон следит: поменяли на самом телефоне —
 * в журнал сервера и в спор «кто позже»; [now] — показ, который читается в миг снимка (разрешения, есть ли
 * ключ); [write] — ТОТ ЖЕ путь, что тумблер на экране (тот же сеттер `Settings`), null — с компьютера не правится.
 */
internal class Knob(
    val group: String,
    val item: Item,
    val flow: ((PravkaApp) -> Flow<Any?>)? = null,
    val now: (suspend (PravkaApp) -> Any?)? = null,
    val write: (suspend (PravkaApp, Any) -> Unit)? = null,
) {
    suspend fun read(app: PravkaApp): Any? = now?.invoke(app) ?: flow?.invoke(app)?.first()
}

/** Группа на странице «Телефон»: название, значок веба, почему целиком в руках, что стоит одной строкой. */
internal class PhoneGroup(
    val key: String,
    val title: String,
    val hint: String,
    val icon: String,
    val note: String = "",
    val summary: (Map<String, Any?>) -> String = { "" },
)

/**
 * Какие настройки телефон показывает компьютеру и какие даёт править (14.10.2026). Решение — по смыслу:
 *
 * - **правятся с компьютера** — поведение приложения, которое одинаково понятно и с телефона, и с компа:
 *   модели и усилия дорог, ночной разбор, «Сегодня» (сон, город, отметки, бюджет), Правка, Засечка
 *   (напоминания, звонки, телефон в ленте, тумблеры автопилота), Спорт, лента и intervals у Еды, пуши Денег,
 *   распознавание (путь, режим, подсказки, звук «говори»), проверка связи, тема, «Вид 4.0» и сервисы вкладкой
 *   браузера, суточная копия,
 *   обновления, лог запросов;
 * - **только показываются** — что привязано к железу или решается в руках: места Wi-Fi, машина по Bluetooth,
 *   метки NFC, календари телефона, кто слушает (телефон или гарнитура), движок Whisper (файлы модели на
 *   телефоне), плавающие кнопки и диск (подбирают на живом экране), служба доступности и разрешения Android,
 *   кто пользуется и режимы (смена переписывает промпты и может выключить сам синк), дорога Дел (сервер или
 *   Todoist — сменить значит оборвать канал правок), откуда ставить сборки (иначе правка с компа могла бы
 *   подсунуть чужую сборку), цели еды и веса (их правда — Свод `food.goals`, правит «Еда» на компе);
 * - **не уходят вовсе** — ключи и токены (Anthropic, intervals, Todoist, вход в Дела и архив, пароль облака
 *   семьи), SSID сетей, адрес машины, номера NFC-меток: вместо ключа — строка «ключ есть / нет».
 *
 * Новая настройка — новая строка здесь (тот же сеттер, что у её тумблера); веб нарисует её сам по схеме.
 */
internal object PhoneKnobs {

    private const val WHY_HANDS = "плавающие кнопки — только в руках: какие и где стоят, подбирают на живом экране"
    private const val WHY_KEYS = "ключи и токены не уезжают с телефона — видно только, есть ли они"
    private const val WHY_PERM = "разрешение выдаёт только сам телефон — в его настройках"
    private const val WHY_GOALS = "цели еды и веса — запись Свода food.goals: их правит «Еда» на компе, телефон возьмёт её по заданию docs/eda-server.md"

    val GROUPS: List<PhoneGroup> = listOf(
        PhoneGroup("models", "Модели", "какая модель и с каким усилием на каждой дороге; ночной разбор", "claude") { v ->
            val own = ModelRoute.entries.count { r ->
                v["model.${r.key}"] != r.defaultModel || v["effort.${r.key}"] != r.defaultEffort
            }
            (if (own > 0) "своих: $own" else "заводские") + if (v["night.review"] == true) " · ночью разбор" else ""
        },
        PhoneGroup("voice", "Микрофон и распознавание", "путь Google, режим, подсказки, звук «говори»", "mic") { v ->
            listOfNotNull(v["voice.mic"] as? String, if (v["voice.network"] == "cloud") "облако Google" else "офлайн-пакет",
                if (v["voice.own_mic"] == true) "свой микрофон Правки" else null).joinToString(" · ")
        },
        PhoneGroup("zasechka", "Засечка", "напоминания, звонки, телефон в ленте, автопилот", "clock") { v ->
            if (v["z.quiet"] == true) "молча: лента пишется сама"
            else "дыра от ${v["z.gap"] ?: "—"} мин · день ${v["z.day_start"] ?: "—"}–${v["z.day_end"] ?: "—"}"
        },
        PhoneGroup("today", "Сегодня", "время сна, погода, отметки в ленте, бюджет месяца", "sun") { v ->
            listOfNotNull((v["today.bedtime"] as? Int)?.let { "сон " + PhoneRemote.hm(it) }, (v["today.city"] as? String)?.takeIf { it.isNotBlank() })
                .joinToString(" · ")
        },
        PhoneGroup("pravka", "Правка", "проза, контекст разговора, правила в промпте", "edit") { v ->
            listOfNotNull("проза".takeIf { v["pravka.prose"] == true }, "контекст".takeIf { v["pravka.convo"] == true },
                "правила в промпте".takeIf { v["pravka.rules"] == true }).joinToString(" · ").ifBlank { "чистка без дополнений" }
        },
        PhoneGroup("sport", "Спорт", "отдых между подходами, выгрузка, уведомление", "activity") { v ->
            "отдых ${v["sport.rest"] ?: "—"} с · ${v["sport.goal_weight"] ?: ""}".trimEnd(' ', '·')
        },
        PhoneGroup("food", "Еда", "куда уходит еда; цели — в «Еде» на компе", "food") { v -> (v["food.targets"] as? String).orEmpty() },
        PhoneGroup("money", "Деньги", "пуши банка", "wallet") { v ->
            if (v["money.push"] == true) "пуши банка ловятся" else "пуши банка не ловятся"
        },
        PhoneGroup("net", "Связь с облаками", "проверка Google и Claude", "globe") { v ->
            if (v["net.probe"] == true) "проверка раз в ${v["net.every"]} мин" else "проверка выключена"
        },
        PhoneGroup("look", "Вид приложения", "тема, «Вид 4.0», сервисы внутри или браузером", "monitor") { v ->
            if (v["look.v4"] == true) "«Вид 4.0»" else when (v["look.theme"]) { "light" -> "светлая"; "dark" -> "тёмная"; else -> "как в системе" }
        },
        PhoneGroup("data", "База данных", "копия раз в сутки, где лежит", "archive") { v ->
            listOfNotNull(v["data.where"] as? String, if (v["data.daily_backup"] == true) "копия ночью" else "без ночной копии").joinToString(" · ")
        },
        PhoneGroup("app", "Обновления и служба", "обновления, лог запросов", "refresh") { v ->
            listOfNotNull(v["app.service"] as? String, if (v["app.upd_auto"] == true) "обновляется сам" else "обновления руками").joinToString(" · ")
        },
        PhoneGroup("buttons", "Кнопки на экране", "какие кнопки, диск или стопка", "phone", note = WHY_HANDS) { v ->
            listOfNotNull(v["buttons.which"] as? String, v["buttons.disk"] as? String).joinToString(" · ")
        },
        PhoneGroup("links", "Подключения", "Anthropic, intervals.icu, Todoist, Дела, архив, облако", "link", note = WHY_KEYS) { v ->
            val on = listOf("link.anthropic", "link.intervals", "link.dela", "link.archive").count { k -> (v[k] as? String)?.let { it.startsWith("ключ есть") || it.startsWith("подключ") } == true }
            "подключено: $on из 4"
        },
        PhoneGroup("perms", "Разрешения Android", "микрофон, поверх окон, уведомления, звонки…", "shield", note = WHY_PERM) { v ->
            val no = v.filterKeys { it.startsWith("perm.") }.count { it.value == "нет" }
            if (no == 0) "всё выдано" else "не выдано: $no"
        },
        PhoneGroup("profile", "Кто пользуется", "имя и режимы", "user",
            note = "смена профиля переписывает промпты и может выключить сам синк Дел — только на телефоне") { v ->
            listOfNotNull(v["profile.who"] as? String, v["profile.modes"] as? String).joinToString(" · ")
        },
    )

    // ------------------------------------------------------------ как описывать

    private fun bool(g: String, key: String, title: String, hint: String = "", sec: String = "",
                     flow: (PravkaApp) -> Flow<Boolean>, write: suspend (PravkaApp, Boolean) -> Unit) =
        Knob(g, Item(key, title, Type.BOOL, hint = hint, sec = sec), flow = flow, write = { app, v -> write(app, v as Boolean) })

    private fun int(g: String, key: String, title: String, min: Int, max: Int, step: Int = 1, unit: String = "", hint: String = "",
                    sec: String = "", type: Type = Type.INT, flow: (PravkaApp) -> Flow<Int>, write: suspend (PravkaApp, Int) -> Unit) =
        Knob(g, Item(key, title, type, hint = hint, min = min, max = max, step = step, unit = unit, sec = sec),
            flow = flow, write = { app, v -> write(app, (v as Number).toInt()) })

    private fun choice(g: String, key: String, title: String, options: List<Opt>, hint: String = "", sec: String = "", row: String = "",
                       flow: (PravkaApp) -> Flow<String>, write: suspend (PravkaApp, String) -> Unit) =
        Knob(g, Item(key, title, Type.CHOICE, hint = hint, options = options, sec = sec, row = row),
            flow = flow, write = { app, v -> write(app, v as String) })

    private fun info(g: String, key: String, title: String, why: String, hint: String = "", sec: String = "",
                     read: suspend (PravkaApp) -> String) =
        Knob(g, Item(key, title, Type.INFO, hint = hint, edit = false, why = why, sec = sec), now = read)

    private fun opts(vararg p: Pair<String, String>) = p.map { Opt(it.first, it.second) }

    private fun granted(app: PravkaApp, perm: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(app, perm) == PackageManager.PERMISSION_GRANTED

    private fun yes(b: Boolean) = if (b) "есть" else "нет"

    // ------------------------------------------------------------ сами настройки

    /** Режим, ради которого группа существует (как `SettingsGroup.modes`): выключен в профиле — группы в схеме нет. */
    fun modeOf(group: String): ru.zf.pravka.data.Profile.Mode? = when (group) {
        "zasechka" -> ru.zf.pravka.data.Profile.Mode.ZASECHKA
        "sport" -> ru.zf.pravka.data.Profile.Mode.SPORT
        "food" -> ru.zf.pravka.data.Profile.Mode.FOOD
        "money" -> ru.zf.pravka.data.Profile.Mode.MONEY
        else -> null
    }

    /**
     * Все настройки по порядку групп. [owner] — телефон владельца: проза есть только у него (docs/data.md,
     * профиль); [has] — включён ли режим в профиле: настройки выключенного режима ни на что не действуют, и
     * на странице их нет — как на экране «Телефон».
     */
    fun all(owner: Boolean, has: (ru.zf.pravka.data.Profile.Mode) -> Boolean = { true }): List<Knob> =
        everything(owner).filter { k -> modeOf(k.group)?.let(has) ?: true }

    private fun everything(owner: Boolean): List<Knob> = buildList {
        // Модели: каждая дорога — модель и усилие одной строкой (row), разделы — по режимам дорог.
        for (r in ModelRoute.entries) {
            add(choice("models", "model.${r.key}", r.title, Models.ALL.map { Opt(it, Models.label(it)) },
                hint = r.hint.take(300), sec = r.mode, row = r.key,
                flow = { app -> app.settings.modelChoiceFlow(r).map { it.model } },
                write = { app, v -> app.settings.setModel(r, v) }))
            add(choice("models", "effort.${r.key}", "Усилие", Models.EFFORTS.map { Opt(it, Models.effortLabel(it)) }, sec = r.mode, row = r.key,
                flow = { app -> app.settings.modelChoiceFlow(r).map { it.effort } },
                write = { app, v -> app.settings.setEffort(r, v) }))
        }
        val night = "Ночной разбор"
        add(bool("models", "night.review", "Разбирать диктовки ночью", "в ночь на пятницу за неделю; пачкой — вдвое дешевле", night,
            { it.settings.nightReviewEnabledFlow }) { app, v -> app.settings.setNightReviewEnabled(v) })
        add(bool("models", "night.daily", "И каждую ночь за сутки", "", night, { it.settings.nightDailyEnabledFlow }) { app, v -> app.settings.setNightDailyEnabled(v) })
        add(bool("models", "night.tune", "Недельная правка промпта", "в ночь на субботу", night, { it.settings.promptTuneEnabledFlow }) { app, v -> app.settings.setPromptTuneEnabled(v) })
        add(int("models", "night.hour", "Час ночного разбора", 0, 23, unit = "ч", sec = night, flow = { it.settings.nightReviewHourFlow }) { app, v -> app.settings.setNightReviewHour(v) })
        add(int("models", "night.budget", "Потолок трат автоматов за сутки", 1, 50, unit = "$", sec = night,
            hint = "выше — ночные автоматы сами не стартуют; кнопкой — можно", flow = { it.settings.nightBudgetUsdFlow }) { app, v -> app.settings.setNightBudgetUsd(v) })

        // Микрофон и распознавание.
        add(info("voice", "voice.mic", "Кто слушает", "микрофон по умолчанию — только в руках: телефон или гарнитуру выбирают ухом, рядом с ней") { app ->
            if (app.settings.phoneMicOnlyFlow.first()) "телефон" else "гарнитура"
        })
        add(info("voice", "voice.engine", "Движок", "Whisper — файлы модели на самом телефоне: выбирать его там, где видно, скачаны ли они") { app ->
            when (app.settings.speechEngine()) {
                ru.zf.pravka.data.Settings.SPEECH_WHISPER_SMALL -> "Whisper small"
                ru.zf.pravka.data.Settings.SPEECH_WHISPER_BASE -> "Whisper base"
                else -> "Google"
            }
        })
        add(choice("voice", "voice.network", "Путь Google", opts("cloud" to "Облако", "offline" to "Офлайн-пакет"),
            hint = "облако чётче на именах и терминах; без сети телефон сам падает на пакет",
            flow = { app -> app.settings.speechNetworkFlow.map { if (it) "cloud" else "offline" } }) { app, v -> app.settings.setSpeechNetwork(v == "cloud") })
        add(choice("voice", "voice.mode", "Режим", opts("continuous" to "Непрерывный", "segmented" to "Посегментный"),
            flow = { app -> app.settings.speechSegmentedFlow.map { if (it) "continuous" else "segmented" } }) { app, v -> app.settings.setSpeechSegmented(v == "continuous") })
        add(bool("voice", "voice.biasing", "Подсказывать слова словаря", flow = { it.settings.speechBiasingFlow }) { app, v -> app.settings.setSpeechBiasing(v) })
        add(bool("voice", "voice.formatting", "Пунктуация распознавателя", flow = { it.settings.speechFormattingFlow }) { app, v -> app.settings.setSpeechFormatting(v) })
        add(bool("voice", "voice.own_mic", "Микрофон держит Правка", "выключено — распознаватель слушает сам, по-старому",
            flow = { it.settings.speechOwnMicFlow }) { app, v -> app.settings.setSpeechOwnMic(v) })
        add(choice("voice", "voice.chime", "Звук «говори»", opts("headset" to "В наушниках", "always" to "Всегда", "off" to "Выкл"),
            flow = { app -> app.settings.readyChimeFlow.map { it.key } }) { app, v -> app.settings.setReadyChime(ru.zf.pravka.core.ReadyChime.Mode.fromKey(v)) })
        add(info("voice", "voice.chime_delay", "Когда звенеть «говори»", "подбирается на слух — ползунком на телефоне") { app ->
            "после пилюли %.1f с · после переключения %.1f с".format(java.util.Locale.forLanguageTag("ru"),
                app.settings.chimeAfterStartFlow.first() / 1000.0, app.settings.chimeAfterSwitchFlow.first() / 1000.0)
        })

        // Засечка.
        val remind = "Напоминания"
        add(bool("zasechka", "z.quiet", "Засечка молча", "лента пишется сама — без напоминаний и вопросов", remind,
            { it.zQuietFlow }) { app, v -> app.settings.setZQuiet(v) })
        add(int("zasechka", "z.gap", "Дыра во времени", 0, 120, 15, "мин", "через сколько без дела напомнить; 0 — не напоминать", remind,
            flow = { it.settings.zGapMinFlow }) { app, v -> app.settings.setZGapMin(v) })
        add(int("zasechka", "z.day_start", "День начинается", 0, 12, unit = "ч", sec = remind, flow = { it.settings.zDayStartFlow }) { app, v -> app.settings.setZDayStart(v) })
        add(int("zasechka", "z.day_end", "День кончается", 12, 24, unit = "ч", sec = remind, flow = { it.settings.zDayEndFlow }) { app, v -> app.settings.setZDayEnd(v) })
        add(bool("zasechka", "z.checkins", "Спрашивать «всё ещё …?»", sec = remind, flow = { it.settings.zCheckinsFlow }) { app, v -> app.settings.setZCheckins(v) })
        val calls = "Звонки и телефон"
        add(bool("zasechka", "z.calls_cut", "Звонок режет дело — с категорией по собеседнику", sec = calls,
            flow = { it.settings.zCallsCutFlow }) { app, v -> app.settings.setZCallsCut(v) })
        add(Knob("zasechka", Item("z.call_family", "Кто — семья для звонков", Type.TEXT, hint = "имена контактов через запятую", maxLen = 200, sec = calls),
            flow = { it.settings.zCallFamilyFlow }, write = { app, v -> app.settings.setZCallFamily(v as String) }))
        add(bool("zasechka", "z.phone_cut", "Долгое сидение в телефоне режет дело", sec = calls,
            flow = { it.zPhoneSitFlow }) { app, v -> app.settings.setZPhoneCut(v) })
        add(choice("zasechka", "z.phone_min", "С какой минуты — уже не «быстро ответил»",
            ru.zf.pravka.core.PhoneSit.MIN_CHOICES.map { Opt(it.toString(), "$it мин") }, sec = calls,
            flow = { app -> app.settings.zPhoneMinFlow.map { it.toString() } }) { app, v -> app.settings.setZPhoneMin(v.toInt()) })
        add(bool("zasechka", "z.phone_text", "Читать текст экрана — о чём переписка, что за ролик", sec = calls,
            flow = { it.settings.zPhoneTextFlow }) { app, v -> app.settings.setZPhoneText(v) })
        val auto = "Автопилот"
        add(bool("zasechka", "auto.plates", "Вопросы автопилота — плашкой сверху", sec = auto, flow = { it.settings.autoPlatesFlow }) { app, v -> app.settings.setAutoPlates(v) })
        add(bool("zasechka", "auto.arrive", "Приезд в место закрывает передвижение", sec = auto, flow = { it.settings.autoArriveFlow }) { app, v -> app.settings.setAutoArrive(v) })
        add(bool("zasechka", "auto.leave_ask", "Спрашивать при отъезде из места", sec = auto, flow = { it.settings.autoLeaveAskFlow }) { app, v -> app.settings.setAutoLeaveAsk(v) })
        add(bool("zasechka", "auto.walk", "Двадцать минут в движении после отъезда — пешком", sec = auto, flow = { it.settings.autoWalkStartFlow }) { app, v -> app.settings.setAutoWalkStart(v) })
        add(bool("zasechka", "auto.car_start", "Машина подключилась — сразу «Поездка на машине»", sec = auto, flow = { it.settings.autoCarStartFlow }) { app, v -> app.settings.setAutoCarStart(v) })
        add(bool("zasechka", "auto.car_ask", "…или хотя бы спросить «сел в машину?»", sec = auto, flow = { it.settings.autoCarAskFlow }) { app, v -> app.settings.setAutoCarAsk(v) })
        add(bool("zasechka", "auto.still_ask", "«Всё ещё …?», когда телефон задвигался", sec = auto, flow = { it.settings.autoStillAskFlow }) { app, v -> app.settings.setAutoStillAsk(v) })
        add(bool("zasechka", "auto.calendar", "Встречи из календаря — в ленту сами", sec = auto, flow = { it.settings.autoCalOnFlow }) { app, v -> app.settings.setAutoCalOn(v) })
        add(bool("zasechka", "auto.sleep", "Сон сам: зарядка и полчаса без движения", sec = auto, flow = { it.settings.autoBedtimeFlow }) { app, v -> app.settings.setAutoBedtime(v) })
        add(info("zasechka", "auto.wake", "Дело по подъёму", "дело с категорией ленты правится на телефоне — там видны категории", sec = auto) { app ->
            app.settings.autoWakeDealFlow.first()?.let { "${it.title} [${it.category}]" } ?: "нет"
        })
        add(info("zasechka", "auto.places", "Места по Wi-Fi", "место — это сеть Wi-Fi рядом с телефоном: назвать его можно только на телефоне", sec = auto) { app ->
            app.settings.autoPlacesFlow.first().values.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ").ifBlank { "не названы" }
        })
        add(info("zasechka", "auto.car", "Машина по Bluetooth", "машина узнаётся по Bluetooth самого телефона", sec = auto) { app ->
            app.settings.autoCarBtFlow.first().ifBlank { "не задана" }
        })
        add(info("zasechka", "auto.nfc", "Метки NFC", "метка — наклейка, её прикладывают к самому телефону", sec = auto) { app ->
            val tags = app.settings.nfcTagsFlow.first()
            if (tags.isEmpty()) "нет" else "${tags.size}: " + tags.joinToString(", ") { it.name }
        })

        // «Сегодня».
        add(int("today", "today.bedtime", "Время сна", 21 * 60, 26 * 60, 15, hint = "отсюда «до сна» и «дела не влезают на N м»", type = Type.TIME,
            flow = { it.settings.todayBedtimeFlow }) { app, v -> app.settings.setTodayBedtime(v) })
        add(Knob("today", Item("today.city", "Город погоды", Type.TEXT, hint = "пусто — ряда погоды нет", maxLen = 60),
            flow = { it.settings.weatherCityFlow }, write = { app, v -> app.settings.setWeatherCity(v as String) }))
        add(Knob("today", Item("today.marks", "Отметки в ленте", Type.SET, hint = "монета режима у записи Засечки",
            options = opts("sport" to "Спорт", "food" to "Еда", "money" to "Деньги", "dela" to "Дела")),
            flow = { app -> app.settings.todayMarksFlow.map { it.toList() } },
            write = { app, v -> app.settings.setTodayMarks((v as List<*>).map { it.toString() }.toSet()) }))
        add(bool("today", "today.state", "Сон, HRV и форма под днём", flow = { it.settings.todayStateFlow }) { app, v -> app.settings.setTodayState(v) })
        add(int("today", "today.budget", "Бюджет месяца", 0, 99_999_999, unit = "₽", hint = "на личные траты; 0 — полосы на плашке Денег нет",
            flow = { app -> app.settings.moneyMonthBudgetFlow.map { it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } }) { app, v -> app.settings.setMoneyMonthBudget(v.toLong()) })
        add(info("today", "today.calendars", "Календари в хронике", "список календарей есть только у телефона") { app ->
            when (val c = app.settings.todayCalendarsFlow.first()) {
                null -> "как у автопилота"
                else -> c.sorted().joinToString(", ").ifBlank { "ни одного" }
            }
        })

        // Правка.
        if (owner) add(bool("pravka", "pravka.prose", "Художественная проза", "бережёт ритм, инверсии, повторы",
            flow = { it.settings.proseModeFlow }) { app, v -> app.settings.setProseMode(v) })
        add(bool("pravka", "pravka.convo", "Контекст разговора", "недавние сообщения в том же приложении", flow = { it.settings.convoContextFlow }) { app, v -> app.settings.setConvoContext(v) })
        add(bool("pravka", "pravka.rules", "Постоянные правила в промпте", flow = { it.settings.rulesInPromptFlow }) { app, v -> app.settings.setRulesInPrompt(v) })
        add(bool("pravka", "pravka.rules_prose", "Правила обучения в прозе", "действует, когда включены проза и правила",
            flow = { it.settings.rulesInProseFlow }) { app, v -> app.settings.setRulesInProse(v) })

        // Спорт.
        add(int("sport", "sport.rest", "Отдых между подходами", 30, 240, 5, "с", flow = { it.settings.restSecFlow }) { app, v -> app.settings.setRestSec(v) })
        add(int("sport", "sport.days", "Глубина выгрузки", 30, 400, 10, "дн.", "столько дней тренировок держит телефон",
            flow = { it.settings.sportDaysFlow }) { app, v -> app.settings.setSportDays(v) })
        add(bool("sport", "sport.notify", "Тренировка приехала — уведомление", flow = { it.settings.sportNotifyFlow }) { app, v -> app.settings.setSportNotify(v) })
        add(info("sport", "sport.goal_weight", "Цель веса", WHY_GOALS) { app -> "${app.settings.goalWeightFlow.first()} кг" })

        // Еда.
        add(info("food", "food.targets", "Цели на день", WHY_GOALS) { app ->
            val t = app.settings.foodTargets()
            "%,d ккал · Б %d · Ж %d · У %d".format(java.util.Locale.forLanguageTag("ru"), t.kcal, t.protein, t.fat, t.carbs)
        })
        add(bool("food", "food.ribbon", "Приписывать к ленте", flow = { it.settings.foodToRibbonFlow }) { app, v -> app.settings.setFoodToRibbon(v) })
        add(bool("food", "food.icu", "Писать в intervals.icu", "КБЖУ дня — в wellness", flow = { it.settings.foodToIcuFlow }) { app, v -> app.settings.setFoodToIcu(v) })

        // Деньги.
        add(bool("money", "money.push", "Ловить пуши банка", "Т-Банк, Альфа, МКБ, «Плати по миру»", flow = { it.settings.mPushFlow }) { app, v -> app.settings.setMPush(v) })
        add(bool("money", "money.push_tbank", "Пуши Т-Банка на этом телефоне", "обычно их ловит телефон владельца: карта на его счёте",
            flow = { app -> app.settings.mPushTbankFlow.map { ru.zf.pravka.core.BankPush.catchTbank(it, app.profileStore.owner) } }) { app, v -> app.settings.setMPushTbank(v) })
        add(bool("money", "money.samples", "Собирать образцы пушей", "для разборщиков новых банков; собрал — выключи",
            flow = { it.settings.mPushSamplesFlow }) { app, v -> app.settings.setMPushSamples(v) })
        add(info("money", "money.access", "Доступ к уведомлениям", WHY_PERM) { app ->
            yes(androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName))
        })

        // Связь с облаками.
        add(bool("net", "net.probe", "Проверять связь с облаками", "Интернет, Google, Claude — журнал и неделя полосами",
            flow = { it.settings.netProbeFlow }) { app, v -> app.settings.setNetProbe(v) })
        add(choice("net", "net.every", "Как часто", ru.zf.pravka.core.NetProbe.INTERVALS_MIN.map { Opt(it.toString(), "раз в $it мин") },
            hint = "ночью — раз в полчаса", flow = { app -> app.settings.netProbeIntervalFlow.map { it.toString() } }) { app, v -> app.settings.setNetProbeInterval(v.toInt()) })

        // Вид приложения.
        add(choice("look", "look.theme", "Тема", opts("system" to "Как в системе", "light" to "Светлая", "dark" to "Тёмная"),
            flow = { it.settings.lookThemeFlow }) { app, v -> app.settings.setLookTheme(v) })
        add(bool("look", "look.v4", "«Вид 4.0»", "стекло и свечение — на время переезда", flow = { it.settings.lookV4Flow }) { app, v -> app.settings.setLookV4(v) })
        add(bool("look", "look.services_in_browser", "Сервисы Ковчега — вкладкой браузера", "запасной путь, если внутри приложения страница сервиса не работает",
            flow = { it.settings.servicesInBrowserFlow }) { app, v -> app.settings.setServicesInBrowser(v) })

        // База данных.
        add(bool("data", "data.daily_backup", "Копия базы раз в сутки", "ночью, в Documents/Pravka-backups и в облако семьи",
            flow = { it.settings.dailyBackupFlow }) { app, v -> app.settings.setDailyBackup(v) })
        add(info("data", "data.where", "Где лежит база", "переезд базы — только на телефоне: файлы и доступ к ним там") { _ ->
            when (ru.zf.pravka.data.DataRoot.where.value) {
                ru.zf.pravka.data.DataRoot.Where.PRIVATE -> "в памяти приложения"
                ru.zf.pravka.data.DataRoot.Where.FOLDER -> "в папке Documents/Pravka"
                ru.zf.pravka.data.DataRoot.Where.FOLDER_NO_ACCESS -> "в папке — нет доступа"
            }
        })

        // Обновления и служба.
        add(bool("app", "app.upd_auto", "Обновляться самому", "раз в сутки проверить ветку и поставить новую сборку",
            flow = { it.settings.updAutoFlow }) { app, v -> app.settings.setUpdAuto(v) })
        add(bool("app", "app.upd_mobile", "Качать сборку и по мобильной сети", flow = { it.settings.updMobileFlow }) { app, v -> app.settings.setUpdMobile(v) })
        add(bool("app", "app.debug_log", "Лог запросов к Claude", "каждый запрос целиком — в отдельный журнал", flow = { it.settings.debugLogFlow }) { app, v -> app.settings.setDebugLog(v) })
        add(info("app", "app.service", "Служба доступности", "службу включают только на самом телефоне — в его настройках") { _ ->
            if (ru.zf.pravka.trigger.PravkaAccessibilityService.instance != null) "работает" else "выключена"
        })
        add(info("app", "app.build", "Сборка", "откуда ставить сборки, правится только на телефоне: правка с компьютера могла бы подсунуть чужую сборку") { app ->
            val branch = app.settings.updBranchFlow.first().ifBlank { BuildConfig.BUILD_BRANCH }
            "${BuildConfig.VERSION_NAME} · ветка $branch"
        })

        // Кнопки на экране — только показ.
        add(info("buttons", "buttons.which", "Кнопки", WHY_HANDS) { app ->
            val s = app.settings
            val p = app.profileStore
            listOfNotNull(
                "П",
                "З".takeIf { s.zEnabledFlow.first() && p.has(ru.zf.pravka.data.Profile.Mode.ZASECHKA) },
                "Д".takeIf { s.rEnabledFlow.first() && p.has(ru.zf.pravka.data.Profile.Mode.DELA) },
                "₽".takeIf { s.mEnabledFlow.first() && p.has(ru.zf.pravka.data.Profile.Mode.MONEY) },
                "Е".takeIf { s.tEnabledFlow.first() && p.has(ru.zf.pravka.data.Profile.Mode.FOOD) },
            ).joinToString(" ")
        })
        add(info("buttons", "buttons.disk", "Как стоят", WHY_HANDS) { app -> if (app.settings.diskModeFlow.first()) "диском" else "стопкой" })
        add(info("buttons", "buttons.size", "Размер и прозрачность", WHY_HANDS) { app ->
            "${app.settings.fabSizeFlow.first()} dp · ${(app.settings.fabAlphaFlow.first() * 100).toInt()} %"
        })

        // Подключения — только «есть / нет»: ключи и токены не уезжают.
        add(info("links", "link.anthropic", "Anthropic", WHY_KEYS) { app -> if (app.settings.apiKeyFlow.first().isNotBlank()) "ключ есть" else "ключа нет" })
        add(info("links", "link.intervals", "intervals.icu", WHY_KEYS) { app ->
            if (app.settings.icuAthleteFlow.first().isNotBlank() && app.settings.icuKeyFlow.first().isNotBlank()) "ключ есть" else "не задано"
        })
        add(info("links", "link.todoist", "Todoist", WHY_KEYS) { app -> if (app.settings.todoistTokenFlow.first().isNotBlank()) "ключ есть" else "не задано" })
        add(info("links", "link.dela", "Сервер Дел", "дорогу Дел (сервер или Todoist) меняют на телефоне: её смена оборвала бы сам канал правок") { app ->
            val on = app.delaSync.link.value != null
            val server = app.delaOnServer()
            when {
                on && server -> "подключено"
                on -> "подключено · дела идут через Todoist"
                else -> "не подключено"
            }
        })
        add(info("links", "link.archive", "Архив на компе", WHY_KEYS) { app -> if (app.archiveSync.link.value != null) "подключён" else "не подключён" })
        add(info("links", "link.cloud", "Облако семьи", WHY_KEYS) { app -> if (app.homeServer.saved.value != null) "подключено" else "не задано" })

        // Разрешения Android — только показ.
        add(info("perms", "perm.mic", "Микрофон", WHY_PERM) { app -> yes(granted(app, android.Manifest.permission.RECORD_AUDIO)) })
        add(info("perms", "perm.overlay", "Поверх других окон", WHY_PERM) { app -> yes(android.provider.Settings.canDrawOverlays(app)) })
        add(info("perms", "perm.notify", "Уведомления", WHY_PERM) { app -> yes(androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled()) })
        add(info("perms", "perm.usage", "Статистика использования", WHY_PERM) { app -> yes(ru.zf.pravka.data.PhoneSweeper.hasUsageAccess(app)) })
        add(info("perms", "perm.calls", "Журнал звонков", WHY_PERM) { app -> yes(ru.zf.pravka.data.PhoneSweeper.hasCallLogAccess(app)) })
        add(info("perms", "perm.calendar", "Календарь", WHY_PERM) { app -> yes(ru.zf.pravka.trigger.CalendarPilot.hasPermission(app)) })
        add(info("perms", "perm.location", "Местоположение", WHY_PERM) { app -> yes(granted(app, android.Manifest.permission.ACCESS_FINE_LOCATION)) })
        add(info("perms", "perm.files", "Все файлы", WHY_PERM, hint = "для базы в папке Documents/Pravka") { _ ->
            yes(android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager())
        })

        // Кто пользуется — только показ.
        add(info("profile", "profile.who", "Кто", "смена профиля переписывает промпты — только на телефоне") { app ->
            app.profileStore.current?.name ?: "не выбран"
        })
        add(info("profile", "profile.modes", "Режимы", "выключенный режим перестаёт и синхронизироваться — только на телефоне") { app ->
            val p = app.profileStore.current
            listOf("Правка") .plus(ru.zf.pravka.data.Profile.Mode.entries.filter { p?.has(it) ?: true }.map { it.title }).joinToString(", ")
        })
    }

    /** Схема для сервера: группы по порядку [GROUPS], пустые не уходят. */
    fun groups(knobs: List<Knob>): List<PhoneRemote.Group> = GROUPS.mapNotNull { g ->
        val items = knobs.filter { it.group == g.key }.map { it.item }
        if (items.isEmpty()) null else PhoneRemote.Group(g.key, g.title, g.hint, g.icon, items, g.note)
    }

    /** Что стоит в группе одной строкой — подпись группы на странице. Сбой счёта — пусто, а не падение снимка. */
    fun status(values: Map<String, Any?>): Map<String, String> =
        GROUPS.associate { g -> g.key to runCatching { g.summary(values) }.getOrDefault("") }.filterValues { it.isNotBlank() }
}
