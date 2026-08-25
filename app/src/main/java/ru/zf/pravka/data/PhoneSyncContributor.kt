package ru.zf.pravka.data

import kotlinx.coroutines.flow.first
import org.json.JSONObject

// Что телефон отдаёт в общую таблицу сверх словаря и правил: расшифровки,
// сводку расхода и правки промптов.
class PhoneSyncContributor(
    private val settings: Settings,
    private val transcriptLog: TranscriptionLog,
    private val stats: Stats,
    private val promptSync: PromptSyncSupport,
) : PravkaSync.Contributor {

    override suspend fun transcripts(since: Long): List<JSONObject> {
        val withText = settings.syncTranscriptTextFlow.first()
        // Журнал хранит время строкой ISO - её же кладём ключом в таблицу,
        // так что повторная отправка той же записи ничего не удвоит.
        return transcriptLog.readLast(200).map { e ->
            JSONObject().apply {
                put("ts", e.ts)
                put("device", DEVICE)
                put("engine", e.engine)
                put("audio_s", e.audioMs / 1000.0)
                put("transcribe_s", e.transcribeMs / 1000.0)
                put("chars", e.chars)
                if (withText) put("text", e.text)
            }
        }
    }

    override suspend fun statsRow(): JSONObject {
        val s = stats.snapshotFlow.first()
        return JSONObject().apply {
            put("device", DEVICE)
            put("updatedAt", System.currentTimeMillis())
            put("total", s.total)
            put("errors", s.errors)
            put("chars", s.charsProcessed)
            put("tokensIn", s.tokensIn)
            put("tokensOut", s.tokensOut)
            put("costTotalUsd", s.costTotalUsd)
            put("costTodayUsd", s.costTodayUsd)
        }
    }

    override suspend fun prompts(): List<JSONObject> = promptSync.export()

    override suspend fun applyPrompts(incoming: List<JSONObject>) = promptSync.apply(incoming)

    private companion object {
        const val DEVICE = "pixel"
    }
}
