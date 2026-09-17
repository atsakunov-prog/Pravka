package ru.zf.pravka.data

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.Prompts

/**
 * «Файл для компа» — всё, что нужно утилите `tools/pravka_comp.py`, чтобы
 * причёсывать текст на компьютере ровно как кнопка «П»: действующие промпты
 * Правки, словарь целиком, блок принятых правил в том виде, в каком он едет в
 * запрос, и выбор моделей владельца по дорогам Правки.
 *
 * Почему файл, а не синхронизация: телефон — хозяин словаря и правил (обучение
 * живёт здесь), комп только читает. Словарь меняется раз в несколько дней,
 * и «нажал кнопку — скинул себе в Telegram — утилита подобрала свежий из
 * Загрузок» дешевле любого канала наружу и не заводит нового секрета.
 *
 * Формат читает `tools/pravka_comp_core.py` (parse_bundle): поле `format`
 * защищает от подсовывания чужого JSON (например, экспорта одного словаря).
 * Новое поле — сюда и в parse_bundle; старые не переименовывать.
 */
object CompBundle {

    const val FORMAT = "pravka-comp"
    const val VERSION = 1

    suspend fun build(app: PravkaApp): JSONObject {
        val promptStore = app.promptStore
        // Директивы переделки (короче/длиннее/отшлифовать) на телефоне не
        // редактируются — они и здесь заводские; стили и проза — действующие.
        val prompts = JSONObject().apply {
            put("clean", promptStore.effective(ProofreadMode.CLEAN))
            put("business", promptStore.effective(PromptStore.PromptId.BUSINESS))
            put("soften", promptStore.effective(PromptStore.PromptId.SOFTEN))
            put("prose", promptStore.effective(PromptStore.PromptId.PROSE))
            put("shorter", Prompts.REDO_SHORTER)
            put("longer", Prompts.REDO_LONGER)
            put("polish", Prompts.REDO_POLISH)
        }
        // Дороги Правки: модель и усилие — настройка владельца, не константа
        // (правило 7), поэтому комп получает именно его выбор.
        val routes = JSONObject().apply {
            for (route in listOf(ModelRoute.PRAVKA, ModelRoute.PRAVKA_STRONG)) {
                val choice = app.settings.modelChoice(route)
                put(route.key, JSONObject().put("model", choice.model).put("effort", choice.effort))
            }
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("routes", routes)
            put("prompts", prompts)
            // Готовый блок, байт в байт как в запросе чистки: бюджет на 2000
            // знаков и нумерация считаются здесь, а не переписываются на Python.
            put("rulesBlock", app.rulesStore.enabledBlock())
            put("rulesInProse", app.settings.rulesInProseFlow.first())
            put("dictionary", JSONObject(app.dictionaryStore.exportJson()))
        }
    }

    /**
     * Собирает файл в кэш и открывает системное «Поделиться». Возвращает
     * строку для тоста: что именно уехало, чтобы пустой словарь был виден сразу.
     */
    suspend fun share(context: Context, app: PravkaApp): String {
        val json = build(app)
        val name = "pravka-comp-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".json"
        val file = File(context.cacheDir, name)
        withContext(Dispatchers.IO) { file.writeText(json.toString(2)) }
        val intent = shareFileIntent(context, file, "application/json")
        withContext(Dispatchers.Main) {
            context.startActivity(Intent.createChooser(intent, "Файл для компа"))
        }
        val entries = json.getJSONObject("dictionary").getJSONArray("entries").length()
        val rules = json.getString("rulesBlock").length
        return "Файл для компа: словарь $entries, правила $rules зн., чистка на " +
            json.getJSONObject("routes").getJSONObject(ModelRoute.PRAVKA.key).getString("model")
    }
}
