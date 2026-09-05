package ru.zf.pravka.trigger

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.data.NfcTag
import ru.zf.pravka.ui.Feedback

/**
 * Касание метки. Прозрачный трамплин: Android отдаёт метку только Activity,
 * поэтому она есть — но живёт доли секунды и ничего не рисует.
 *
 * Ради чего всё: засечка без телефона в руках. Зашёл в туалет — приложил,
 * вышел — приложил. Сел на велик — приложил. Ни разблокировки, ни диктовки,
 * ни экрана; метка работает и с погашенным экраном на заблокированном
 * телефоне (Android поднимает NDEF-приложение сам).
 *
 * Действие берётся ИЗ НАСТРОЕК по идентификатору с метки, а не с самой
 * метки: переклеивать наклейку, чтобы поменять категорию, — глупость.
 */
class NfcTapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        // Экран не нужен ни на кадр: работа доделается в фоне приложения.
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val app = application as? PravkaApp ?: return
        val id = tagId(intent)
        if (id.isBlank()) {
            Feedback.toast(app, "Метка не прочиталась")
            return
        }
        app.appScope.launch {
            val tag = runCatching { app.settings.nfcTagsFlow.first() }
                .getOrDefault(emptyList())
                .firstOrNull { it.id == id }
            if (tag == null) {
                // Метка записана, но из настроек её удалили. Не молчим:
                // иначе владелец будет прикладывать её и гадать.
                Feedback.toast(app, "Метка не заведена в настройках Засечки")
                app.eventLog.add("метка NFC $id — нет в настройках")
                return@launch
            }
            runCatching { act(app, tag) }
                .onFailure { Feedback.toast(app, "Метка «${tag.name}»: не вышло") }
            app.zasechkaSync.kickSoon(app.appScope)
        }
    }

    private suspend fun act(app: PravkaApp, tag: NfcTag) {
        val now = System.currentTimeMillis()
        val store = app.zasechkaStore
        val title = tag.entryTitle()
        when (tag.act) {
            NfcTag.ACT_STOP -> {
                val closed = store.closeOpen(now)
                Feedback.toast(
                    app,
                    if (closed == null) "Открытого дела нет"
                    else "⏹ «${closed.title}» — ${closed.durationMin(now)} м",
                )
                app.eventLog.add("метка «${tag.name}»: закрыто «${closed?.title ?: "—"}»")
            }
            NfcTag.ACT_START -> {
                store.startEntry(now, "", title, tag.category, "", 0, "voice")
                Feedback.toast(app, "⏱ $title — с ${hm(now)}")
                app.eventLog.add("метка «${tag.name}»: начато «$title»")
            }
            else -> toggle(app, tag, title, now)
        }
    }

    /**
     * Касание-переключатель. Второе касание той же метки закрывает дело — и,
     * если владелец не выключил, ВОЗВРАЩАЕТ то, что шло до него. Туалет,
     * кухня, звонок — это перерывы: после них человек возвращается к работе,
     * а не в пустоту, и лента не должна получать дыру «Не размечено».
     */
    private suspend fun toggle(app: PravkaApp, tag: NfcTag, title: String, now: Long) {
        val store = app.zasechkaStore
        val all = store.all()
        val open = all.lastOrNull { it.open }
        // «Своё» дело узнаём по названию ИЛИ по имени метки: владелец
        // переименовал метку в настройках, пока дело шло, — второе касание
        // всё равно должно закрыть его, а не открыть второе такое же.
        val mine = open != null && (
            open.title.equals(title, ignoreCase = true) ||
                open.title.equals(tag.name.trim(), ignoreCase = true)
            )
        if (open == null || !mine) {
            store.startEntry(now, "", title, tag.category, "", 0, "voice")
            Feedback.toast(app, "⏱ $title — с ${hm(now)}")
            app.eventLog.add("метка «${tag.name}»: начато «$title»")
            return
        }
        // Дело, которое эта же метка и прервала: оно кончилось ровно там,
        // где началось наше.
        val before = all.filter { !it.open && it.end <= open.start + 60_000L }
            .maxByOrNull { it.end }
        val closed = store.closeOpen(now)
        val back = before?.takeIf {
            tag.resume &&
                kotlin.math.abs(it.end - open.start) < 60_000L &&
                !it.title.equals(title, ignoreCase = true) &&
                !it.category.equals("Не размечено", ignoreCase = true)
        }
        if (back != null) {
            store.startEntry(now, "", back.title, back.category, back.client, back.useful, "voice")
            Feedback.toast(
                app,
                "⏹ «$title» ${closed?.durationMin(now) ?: 0} м · ↩︎ «${back.title}»",
            )
            app.eventLog.add("метка «${tag.name}»: закрыто «$title», вернулся к «${back.title}»")
        } else {
            Feedback.toast(app, "⏹ «$title» — ${closed?.durationMin(now) ?: 0} м")
            app.eventLog.add("метка «${tag.name}»: закрыто «$title»")
        }
    }

    /** Идентификатор с метки: наш MIME-рекорд, всё остальное игнорируем. */
    private fun tagId(intent: Intent?): String {
        val i = intent ?: return ""
        val raw: Array<out Any>? = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }
        val messages = raw?.filterIsInstance<NdefMessage>().orEmpty()
        for (m in messages) {
            val records = m.records ?: continue
            for (r in records) {
                if (r.tnf != NdefRecord.TNF_MIME_MEDIA) continue
                val mime = runCatching { r.toMimeType() }.getOrNull().orEmpty()
                if (!mime.equals(NfcTag.MIME, ignoreCase = true)) continue
                val id = NfcTag.idFromPayload(r.payload)
                if (id.isNotBlank()) return id
            }
        }
        return ""
    }

    private fun hm(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))
}
