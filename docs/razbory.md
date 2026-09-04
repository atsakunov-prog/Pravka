# Разборы: паттерны, дайджесты, CSV и жизнь в Notion

> Часть спецификации Правки. Была разделом README; правится вместе с кодом режима. Карта репозитория — `CLAUDE.md`, короткий обзор — `README.md`.

## Файлы режима

Читатель всех сторов: ночной поиск паттернов батчем, «Запрос для чата», CSV всей жизни, синк ленты/еды/спорта/дней в базы Notion «Правка: разборы». Батч-дорога к Claude — `provider/ClaudeAnalysis.kt`, промпты — `core/prompts/PromptsAnalysis.kt`.

- `ItogiTab.kt`
- `core/AnalysisBuilder.kt`
- `core/AnalysisEngine.kt`
- `core/DigestBuilder.kt`
- `core/prompts/PromptsAnalysis.kt`
- `data/AnalysisStore.kt`
- `data/NotionLifeSync.kt`
- `provider/ClaudeAnalysis.kt`
- `trigger/ServiceAnalysis.kt`
- `docs/razbory-instruction.md`

---

Подробный текст про ночной поиск паттернов, вердикты владельца и «Запрос для чата» — в `docs/telo.md`, раздел «Паттерны: ночная охота за повторами» (исторически он вырос внутри вкладки Спорта). Синк всей жизни в Notion описан в настройках Тела (`docs/telo.md`, «Вся жизнь — в «Правка: разборы»») и в `docs/razbory-instruction.md`.

Правила, которых здесь держатся: МОДЕЛЬ НЕ СЧИТАЕТ — числа считает `core/AnalysisBuilder.kt`; ночью — батч за половину цены, руками — обычный запрос; вердикт владельца по паттерну весит больше уверенности модели; в Notion пишем только свои колонки.
