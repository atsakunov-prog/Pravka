package ru.zf.pravka.trigger

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import kotlinx.coroutines.launch
import ru.zf.pravka.BuildConfig
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.data.PendingTakes
import ru.zf.pravka.ui.Feedback

/**
 * Еда снимком, из галереи или строкой — без вкладки «Еда» (22.10.2026, Правка на диете: владелец во вкладки не
 * заходит, а снимает тарелку кнопкой виджета). Невидимое окно: камера или выбор снимка, разбор и сразу в дневник
 * той же дорогой, что у вкладки (`FoodEngine.parse` → `confirm`), итог тостом. Нет связи с Claude — снимок ждёт в
 * очереди `PendingTakes` и станет приёмом своего времени. Строка — полем кнопки «Е», как «набрать текстом».
 *
 * Без noHistory, в отличие от прочих трамплинов: камера выходит на передний план, и окно с noHistory закрылось бы,
 * не дождавшись снимка.
 */
class FoodQuickActivity : ComponentActivity() {

    companion object {
        const val EXTRA_WHAT = "what"
        const val W_PHOTO = "photo"
        const val W_GALLERY = "gallery"
        const val W_TEXT = "text"
    }

    private var shot: File? = null

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = shot
        shot = null
        if (ok && file != null && file.length() > 0) eat(file) else runCatching { file?.delete() }
        finish()
    }

    private val gallery = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            val copied = runCatching { copyToCache(uri) }.getOrNull()
            if (copied == null) Feedback.toast(this, "Снимок не прочитался") else eat(copied)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Поворот или возврат после смерти процесса: заказ уже ушёл камере, второй раз не звать.
        if (savedInstanceState != null) {
            shot = savedInstanceState.getString("shot")?.let { File(it) }
            return
        }
        when (intent?.getStringExtra(EXTRA_WHAT) ?: W_PHOTO) {
            W_GALLERY -> gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            W_TEXT -> {
                val service = PravkaAccessibilityService.instance
                if (service?.eButton != null) service.openFoodTypeIn("")
                else Feedback.toast(this, "Строка еды живёт у кнопки «Е» — включи её в «Кнопках на экране»", long = true)
                finish()
            }
            else -> {
                val file = File(cacheDir, "eda-shot-${System.currentTimeMillis()}.jpg")
                shot = file
                val uri = androidx.core.content.FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".files", file)
                runCatching { camera.launch(uri) }.onFailure {
                    Feedback.toast(this, "Камера не открылась: ${it.message}", long = true)
                    finish()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        shot?.let { outState.putString("shot", it.absolutePath) }
    }

    private fun copyToCache(uri: Uri): File {
        val out = File(cacheDir, "eda-pick-${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: throw java.io.IOException("Снимок не открылся")
        return out
    }

    /** Разбор в области приложения: окно уже закрылось, а Сонет ещё считает. */
    private fun eat(photo: File) {
        val app = application as PravkaApp
        val at = System.currentTimeMillis()
        Feedback.toast(app, "Снимок принят — разбираю")
        app.appScope.launch {
            val parsed = runCatching { app.foodEngine.parse("", photo = photo, source = "photo") }
                .getOrElse { Result.failure(it) }
            val err = parsed.exceptionOrNull()
            if (err != null) {
                if (PendingTakes.isNetwork(err) &&
                    runCatching { app.pendingTakes.add(PendingTakes.Kind.FOOD, "", at, "photo", photo, error = err.message.orEmpty()) }.isSuccess
                ) {
                    app.eventLog.add("очередь: снимок еды ждёт связи (${err.message})")
                    Feedback.toast(app, "Нет связи с Claude — снимок запомнил, разберу сам, как появится", long = true)
                } else {
                    Feedback.toast(app, err.message ?: "Не разобрал снимок", long = true)
                }
                runCatching { photo.delete() }
                return@launch
            }
            // Движок положил свою копию рядом с дневником, кадр из кэша был только транспортом.
            runCatching { photo.delete() }
            val meal = runCatching { app.foodEngine.confirm(parsed.getOrThrow().meal.id).meal }.getOrNull()
            Feedback.toast(
                app,
                if (meal == null) "Разобрал, а в дневник не записал — приём ждёт подтверждения"
                else "Записал: " + (if (meal.supplement) "добавки" else "${meal.kcal} ккал") + " — ${meal.shortList}",
                long = true,
            )
        }
    }
}
