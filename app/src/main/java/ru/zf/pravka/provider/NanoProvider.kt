package ru.zf.pravka.provider

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.internal.GenAiUtils
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.ProofreadMode
import ru.zf.pravka.core.ProofreadProvider
import ru.zf.pravka.core.ProofreadResult
import ru.zf.pravka.core.Prompts
import ru.zf.pravka.core.TextChunker
import ru.zf.pravka.data.PromptStore

// Gemini Nano on-device via ML Kit Prompt API (spec 6.2). Experimental:
// Russian is not officially validated, so every in/out pair lands in the
// history file for quality review. Long text is split into sentence chunks,
// each chunk is corrected separately and the partially-corrected text is
// pushed back into the field as chunks complete (owner's request).
class NanoProvider(
    private val context: Context,
    private val promptStore: PromptStore,
) : ProofreadProvider {

    override val id = "nano"

    class NanoException(message: String) : Exception(message)

    private val model by lazy { Generation.getClient() }
    private var warmedUp = false

    override suspend fun isAvailable(): Boolean =
        runCatching { model.checkStatus() == FeatureStatus.AVAILABLE }.getOrDefault(false)

    /** Human-readable status line for the settings screen. */
    suspend fun statusText(): String = withContext(Dispatchers.IO) {
        if (!runCatching { GenAiUtils.isAiCoreCompatible(context) }.getOrDefault(true)) {
            return@withContext "Устройство не поддерживает AICore"
        }
        runCatching {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> "Модель готова"
                FeatureStatus.DOWNLOADABLE -> "Модель не скачана — нажми «Скачать»"
                FeatureStatus.DOWNLOADING -> "Модель скачивается…"
                else -> "Недоступна на этом устройстве"
            }
        }.getOrElse { humanize(it) }
    }

    suspend fun download(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            model.download().collect { /* progress ignored, status screen polls */ }
        }
    }

    override suspend fun proofread(
        input: String,
        mode: ProofreadMode,
        dictBlock: String,
        onPartial: (suspend (String) -> Unit)?,
    ): Result<ProofreadResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (mode != ProofreadMode.CLEAN) {
                throw NanoException("Gemini Nano работает только в режиме чистки.")
            }
            when (val status = model.checkStatus()) {
                FeatureStatus.AVAILABLE -> Unit
                FeatureStatus.DOWNLOADABLE -> {
                    throw NanoException("Модель Nano не скачана. Открой настройки Правки и нажми «Скачать модель».")
                }
                FeatureStatus.DOWNLOADING ->
                    throw NanoException("Модель Nano ещё скачивается, попробуй позже.")
                else ->
                    throw NanoException("Gemini Nano недоступна на этом устройстве (статус $status).")
            }
            if (!warmedUp) {
                runCatching { model.warmup() }
                warmedUp = true
            }

            val template = promptStore.effective(ProofreadMode.CLEAN, forNano = true)
            val started = System.currentTimeMillis()
            val chunks = TextChunker.chunk(input)
            val corrected = StringBuilder()

            for ((index, chunk) in chunks.withIndex()) {
                val body = chunk.trimEnd()
                val tail = chunk.substring(body.length)
                if (body.isBlank()) {
                    corrected.append(chunk)
                    continue
                }
                val parts = Prompts.assemble(template, dictBlock)
                val prompt = parts.beforeInput + body + parts.afterInput
                val reply = generate(prompt).trim()
                // Spec 6.2: a chunk that grew by more than 1.5x is considered
                // corrupted - keep the original chunk untouched.
                val safe = if (reply.isEmpty() || reply.length > body.length * 3 / 2) body else reply
                corrected.append(safe).append(tail)
                // Progressive replacement: corrected prefix + raw remainder.
                if (onPartial != null && index < chunks.size - 1) {
                    val remaining = chunks.subList(index + 1, chunks.size).joinToString("")
                    onPartial(corrected.toString() + remaining)
                }
            }

            val text = corrected.toString()
            ProofreadResult(
                text = text,
                providerId = id,
                latencyMs = System.currentTimeMillis() - started,
                changed = text.trim() != input.trim(),
                appliedDictEntries = emptyList(),
                modelId = "gemini-nano",
                inputTokens = 0,
                outputTokens = 0,
                costUsd = 0.0,
            )
        }
    }

    private suspend fun generate(prompt: String): String {
        // Spec 6.2: predictability over creativity.
        val request = generateContentRequest(TextPart(prompt)) {
            temperature = 0.1f
            topK = 1
            candidateCount = 1
            seed = 20260727
            maxOutputTokens = 256
        }
        val response = try {
            model.generateContent(request)
        } catch (e: GenAiException) {
            throw NanoException(humanize(e))
        }
        return response.candidates.firstOrNull()?.text
            ?: throw NanoException("Nano вернула пустой ответ")
    }

    // AICore errors look scary; translate the typical ones (spec 6.2).
    private fun humanize(e: Throwable): String = when {
        e is GenAiException && e.errorCode == 601 ->
            "AICore не привязался (601), обычно лечится перезагрузкой телефона."
        e is GenAiException && e.errorCode == 606 ->
            "AICore ещё не докачал конфигурацию (606) — подожди немного и перезагрузи телефон."
        e is GenAiException -> "Ошибка AICore ${e.errorCode}: ${e.message}"
        else -> e.message ?: "Ошибка Nano"
    }
}
