package ru.zf.pravka

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import ru.zf.pravka.trigger.MoneyTabVoice
import ru.zf.pravka.ui.glass
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.core.MoneyCategories
import ru.zf.pravka.core.MoneyEngine
import ru.zf.pravka.core.MoneyEntry
import ru.zf.pravka.core.MoneyFormat
import ru.zf.pravka.core.MoneyMerchants
import ru.zf.pravka.core.MoneyStats
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.trigger.onMoneyTap
import ru.zf.pravka.trigger.onMoneyText
import ru.zf.pravka.trigger.showMoneyPlate
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.Segments
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.SummaryLine
import ru.zf.pravka.ui.fadingScroll

// Вкладка «Деньги» (23.09.2026). Сверху — «Личное · ЗФ»: от них зависит вся
// вкладка. Под ними три части чипами (24.09.2026, второе издание): вкладка
// выросла до пятнадцати плашек подряд, и то, что смотришь каждый день, тонуло
// среди того, что открываешь раз в неделю.
//   «Сводка»    — спросить Claude, наговорить траты, период, ДДС и баланс,
//                 счета, цифры, куда ушло, по дням, полгода, регулярные,
//                 паттерны;
//   «Разобрать» — то, что ждёт владельца: вопросы сверки, траты без «ОК» и
//                 выписки (файл, буфер, «Поделиться»); на чипе — сколько ждёт;
//   «Журнал»    — записи периода и справочник получателей.
// Всё тяжёлое — в `MoneyEngine`, здесь только показать и передать ответ.
// Книга «Деньги» (.xlsx) выгружается значком в шапке вкладки.
//
// Справочник — два слоя: заводской в assets и свои правила текстом на
// экране; свои перебивают заводские (см. `core/MoneyRules.kt`).

/** Выбор категории: список по группам, как владелец читает бюджет. */
@Composable
internal fun CategoryPicker(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PaperButton(
            if (current.isBlank()) "Категория…" else MoneyCategories.title(current),
            onClick = { open = true },
            icon = Glyphs.Tag,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            var lastGroup = ""
            for (c in MoneyCategories.ALL) {
                if (c.group != lastGroup) {
                    lastGroup = c.group
                    DropdownMenuItem(
                        text = { Text(c.group.uppercase(Locale.forLanguageTag("ru")), style = MaterialTheme.typography.labelSmall) },
                        onClick = {},
                        enabled = false,
                    )
                }
                DropdownMenuItem(text = { Text(c.title) }, onClick = { open = false; onPick(c.key) })
            }
        }
    }
}

/** Настройки Денег — группа за шестерёнкой в шапке вкладки. */
@Composable
internal fun MoneySettings(app: PravkaApp) {
    // Кнопка «₽» — в «Кнопках на экране» рядом с остальными (24.09.2026);
    // модели — в «Моделях», промпты — во вкладке «Промпты».
    PaperCard(label = "пуши банка") { PushSettings(app) }
    PushSamplesCard(app)
}

/**
 * Сбор образцов денежных уведомлений (25.09.2026): разборщик пушей нового
 * банка пишется по настоящим текстам. Включил, неделю пожил, «Поделиться
 * образцами» — и файл уходит тому, кто пишет разбор. Нужен тот же доступ к
 * уведомлениям, что и пушам банка.
 */
@Composable
private fun PushSamplesCard(app: PravkaApp) {
    val context = LocalContext.current
    val on by app.settings.mPushSamplesFlow.collectAsState(initial = false)
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(on) {
        while (true) {
            count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.pushSamples.count() }
            kotlinx.coroutines.delay(3_000)
        }
    }
    PaperCard(
        label = "образцы пушей",
        info = "Пока включено, каждое уведомление, похожее на денежное (есть сумма в рублях или оно " +
            "от банка), записывается целиком в файл базы. Нужно, чтобы научить Правку пушам нового " +
            "банка — Альфы, МКБ, внесения через банкомат: разбор пишется по настоящим текстам. " +
            "Собрал — «Поделиться образцами», и выключи: чужие уведомления копятся, только пока их " +
            "собирают. Хранятся последние 400.",
    ) {
        ru.zf.pravka.ui.PaperToggle(
            title = "Собирать образцы",
            checked = on,
            onCheckedChange = { v -> app.appScope.launch { app.settings.setMPushSamples(v) } },
            hint = if (count > 0) "собрано: $count" else "пока пусто",
        )
        if (count > 0) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                ru.zf.pravka.ui.PaperButton("Поделиться образцами", icon = ru.zf.pravka.ui.Glyphs.Share, onClick = {
                    val f = app.pushSamples.fileForShare() ?: return@PaperButton
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(
                                ru.zf.pravka.data.shareFileIntent(context, f, "text/plain"),
                                "Образцы пушей",
                            )
                        )
                    }
                })
                ru.zf.pravka.ui.PaperTextButton("Очистить", onClick = { app.pushSamples.clear(); count = 0 })
            }
        }
    }
}


/**
 * Пуши банка: тумблер, есть ли «Доступ к уведомлениям» и что поймано.
 * Отозванный доступ — не «пушей просто нет», а строка словами и кнопка
 * (железное правило 6: молчаливая механика читается как поломка).
 */
@Composable
private fun PushSettings(app: PravkaApp) {
    val context = LocalContext.current
    val on by app.settings.mPushFlow.collectAsState(initial = true)
    val state by app.moneyStore.stateFlow.collectAsState()
    var granted by remember { mutableStateOf(pushAccess(context)) }
    // Доступ выдают в системных настройках и возвращаются «назад» — проверяем
    // по кругу, пока группа открыта: это чтение одной строки настроек.
    LaunchedEffect(Unit) {
        while (true) {
            granted = pushAccess(context)
            kotlinx.coroutines.delay(1500)
        }
    }
    ru.zf.pravka.ui.PaperToggle(
        title = "Ловить пуши банка",
        checked = on,
        onCheckedChange = { v -> app.appScope.launch { app.settings.setMPush(v) } },
        hint = "Т-Банк, Альфа, МКБ (и их SMS), «Плати по миру» в Телеграме",
        info = "Трата ложится в журнал сразу, как пришёл пуш, а выписка своего банка потом её заменяет. " +
            "Альфу и МКБ Правка пока читает осторожно: сомнительный пуш записью не становится, но его " +
            "текст сохраняется — по нему потом научится их точному виду.",
    )
    if (!on) return
    // Т-Банк на этом телефоне (08.10.2026). С завода — только у владельца: карта
    // Марианны на счёте Саши, её пуши ловит его телефон, и второй пуш той же
    // операции не склеился бы с выпиской — трата посчиталась бы дважды.
    val tbankSet by app.settings.mPushTbankFlow.collectAsState(initial = null)
    val ownerPhone = app.profileStore.owner
    ru.zf.pravka.ui.PaperToggle(
        title = "Пуши Т-Банка",
        checked = ru.zf.pravka.core.BankPush.catchTbank(tbankSet, ownerPhone),
        onCheckedChange = { v -> app.appScope.launch { app.settings.setMPushTbank(v) } },
        hint = if (ownerPhone) "свой счёт Т-Банка на этом телефоне" else "обычно их ловит телефон Саши: карта на его счёте",
        info = "Пуш Т-Банка заменяет строка выписки того же хозяина. Выписку Т-Банка грузит Саша, и карта " +
            "Марианны — на его счёте: пуш, пойманный на её телефоне, лёг бы второй записью и с выпиской не " +
            "склеился бы. Выключено — пуши Т-Банка здесь не читаются и не хранятся.",
    )
    Spacer(Modifier.height(6.dp))
    if (!granted) {
        PaperHint(
            "Нет доступа к уведомлениям — пуши не ловятся. Если Android не даёт включить: " +
                "«О приложении» → ⋮ → «Разрешить ограниченные настройки», как было со службой доступности.",
            color = MaterialTheme.colorScheme.error,
        )
        ru.zf.pravka.ui.PaperButton("Открыть доступ", icon = ru.zf.pravka.ui.Glyphs.Bell, primary = true, onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        })
        return
    }
    val pushes = state.pushes
    val money = pushes.count { it.result == ru.zf.pravka.data.MoneyStore.MONEY }
    val replaced = state.entries.count { it.source == MoneyEntry.Source.PUSH && it.replacedBy.isNotEmpty() }
    PaperHint(
        if (pushes.isEmpty()) "Доступ есть. Пока ничего не поймано — первый пуш банка появится здесь."
        else "Поймано ${pushes.size}, из них операций $money; выпиской уже заменено $replaced."
    )
    // Тап по пойманному — его сырьё целиком (25.09.2026: «пополнение ничего
    // не отметило» — видно, что именно сохранилось, а не гадать).
    var raw by remember { mutableStateOf<ru.zf.pravka.data.MoneyStore.Push?>(null) }
    for (p in pushes.takeLast(6).reversed()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 3.dp)
                .clickable { raw = p },
        ) {
            Text(
                stamp(p.ts) + "  " + p.title.ifBlank { p.text.lineSequence().firstOrNull().orEmpty().ifBlank { p.pkg.substringAfterLast('.') } },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PaperHint(p.result)
        }
    }
    // Переразбор вручную — тот же, что проходит шаг переразбора истории после
    // правки разбора: из сырья заново, решения владельца не трогает.
    var reparsing by remember { mutableStateOf(false) }
    Spacer(Modifier.height(6.dp))
    ru.zf.pravka.ui.PaperTextButton(
        if (reparsing) "Переразбираю…" else "Переразобрать пойманные пуши",
        icon = ru.zf.pravka.ui.Glyphs.Refresh,
        enabled = !reparsing,
        onClick = {
            reparsing = true
            app.appScope.launch {
                val text = runCatching { app.moneyEngine.reparsePushes() }.fold(
                    { o -> "Пуши: просмотрено ${o.looked}, поправлено ${o.changed}" + if (o.added > 0) ", добавлено ${o.added}" else "" },
                    { e -> "Не переразобрал: ${e.message ?: e.javaClass.simpleName}" },
                )
                app.eventLog.add("деньги: $text")
                ru.zf.pravka.ui.Feedback.toast(context, text)
                reparsing = false
            }
        },
    )
    raw?.let { p ->
        ru.zf.pravka.ui.PaperAlert(
            onDismiss = { raw = null },
            title = "Пойманный пуш",
            icon = ru.zf.pravka.ui.Glyphs.Bell,
            subtitle = stamp(p.ts) + " · " + p.result,
        ) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    "Приложение: ${p.pkg}\nЗаголовок: " + p.title.ifBlank { "(пусто)" } + "\n\n" + p.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            ru.zf.pravka.core.BankPush.invisibles(p.title + p.text).takeIf { it.isNotBlank() }?.let {
                PaperHint("Невидимые символы: $it")
            }
        }
    }
}

private fun pushAccess(context: android.content.Context): Boolean =
    androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun stamp(ts: Long, withTime: Boolean = true): String =
    SimpleDateFormat(if (withTime) "d MMM, HH:mm" else "d MMM", Locale.forLanguageTag("ru")).format(Date(ts))
