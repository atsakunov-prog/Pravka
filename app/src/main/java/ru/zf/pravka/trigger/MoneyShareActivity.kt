package ru.zf.pravka.trigger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.Haptics

// «Поделиться» → «Деньги: выписка». Недельный пакет владельца (23.09.2026):
// Тиньков, Альфа Марианны, чат «Плати по миру» — выделил файлы или текст и
// поделился; можно несколько файлов разом (SEND_MULTIPLE).
//
// Байты читаются ЗДЕСЬ, до finish(): право читать чужой URI живёт, пока жива
// принявшая его активность. Разбор и сверка — уже в фоне приложения; итог —
// тостом и вкладкой «Деньги».
class MoneyShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inputs = runCatching { readAll(intent) }.getOrElse { e ->
            Haptics.error(this)
            Feedback.toast(this, "Не прочитал файл: ${e.javaClass.simpleName}: ${e.message}", long = true)
            finish()
            return
        }
        if (inputs.isEmpty()) {
            Haptics.error(this)
            Feedback.toast(this, "Нечего загружать — ни файла, ни текста")
            finish()
            return
        }
        val app = application as PravkaApp
        Haptics.start(this)
        Feedback.toast(this, if (inputs.size > 1) "Загружаю ${inputs.size} выписки…" else "Загружаю выписку…")
        app.appScope.launch {
            val lines = app.moneyEngine.importFiles(inputs)
            val ctx = app.applicationContext
            Haptics.success(ctx)
            Feedback.toast(ctx, lines.joinToString("\n"), long = true)
        }
        startActivity(
            Intent(this, ru.zf.pravka.MainActivity::class.java)
                .putExtra(ru.zf.pravka.MainActivity.EXTRA_TAB, ru.zf.pravka.MainActivity.TAB_MONEY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    /** Байты каждого файла (CSV, .xlsx) или текст чата — как байты UTF-8. */
    private fun readAll(intent: Intent?): List<ByteArray> {
        intent ?: return emptyList()
        val out = mutableListOf<ByteArray>()
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
        }
        for (u in uris) {
            val bytes = contentResolver.openInputStream(u)?.use { it.readBytes() } ?: continue
            out.add(bytes)
        }
        // Текст чата — не файл, а EXTRA_TEXT.
        if (uris.isEmpty()) {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it.toByteArray()) }
        }
        return out
    }

}
