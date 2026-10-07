# Правка 4.0 — план пересборки дизайна (этап 0)

Задача — `docs/design/redesign-4/TASK.md`, правила — `docs/design/redesign-4/DESIGN.md`
(он главный), вид — `docs/design/redesign-4/screens/`, точные числа —
`docs/design/redesign-4/mockups/*.html` (CSS, 1 px = 1 dp). Архив от владельца
разложен туда как есть, 07.10.2026.

Документ — итог разведки: что в коде есть, во что оно превращается, чего нет и
откуда это взять. Порядок этапов — как в задаче; после каждого этапа приложение
собирается (`compileDebugKotlin`), тесты зелёные, коммит маленький и по-русски.

## 1. Что трогать нельзя — и как это проверить

**Слой поверх приложений** — весь `trigger/` (кнопки, док, стопка, веер,
пилюля, плашки с «ОК», меню долгого нажатия, тикер, `BubbleSkin`), его ресурсы
(`res/drawable/ic_*glyph*.xml`, `ic_mode_*_btn.xml`, `ic_mic*.xml`,
`ic_transcripts.xml`, `ic_tile.xml`, строки `res/values`), а ещё общие с ним
`core/PillLook.kt`, `core/PillGeometry.kt`, `core/DiskLook.kt`,
`core/DiskGeometry.kt`, `core/ChainPhysics.kt`, `core/StackGeometry.kt`,
`core/Countdown.kt`, `ui/Feedback.kt`, `ui/Haptics.kt` — без единой правки.
Приёмка: `git diff pravka-до-редизайна -- app/src/main/java/ru/zf/pravka/trigger app/src/main/res/drawable/ic_*glyph* …` пуст.

Что из оверлея приложение **читает, не меняя**:

- векторы букв для монет — `ic_fab_glyph` (П), `ic_zfab_glyph` (З),
  `ic_razn_glyph` (Д), `ic_money_glyph` (₽). Т и Е в том же брусковом
  языке в проекте уже есть: `ic_body_glyph` (Т) и `ic_efab_glyph` (Е, буква
  выключенной с завода кнопки Еды) — их и берём, рисовать заново не нужно;
  контуры из макетов (`M5 5h14v3.3…`, `M6 5h12v3.3…`) остаются запасом;
- цвета кнопок (`FloatingButtonController.ACCENT` и соседи) — `ui/Glow.kt`
  уже читает их константами;
- `CalendarPilot.calendars/query` (companion, публичные) — для календаря на
  «Сегодня»; `CalendarPilot` сам не меняется.

`QuickActionActivity` (лист плитки быстрых настроек) зовёт `PravkaTheme` — к
оверлею из списка он не относится, и новые шрифты темы дойдут до него сами;
код его не трогаем.

**Договор с оверлеем, который экраны обязаны держать** (оверлей шлёт и зовёт
это строками): `MainActivity.EXTRA_TAB` и все `TAB_*`, `EXTRA_SETTINGS_GROUP`
(имена `SettingsGroup` «PRAVKA», «ZASECHKA», «DELA», «MONEY», «FOOD» не
переименовывать), `EXTRA_FOOD_ACTION` («photo», «barcode», «edit:<id>»),
`MainActivity.shown`; крючки службы, которые зовут вкладки:
`onZasechkaTap`, `onRaznoskaTap/onRaznoskaText`, `onFoodTap`,
`onMoneyTap/onMoneyText`, `listenForDelaReason`, `listenForMoneyTab`,
`showMoneyPlate`, `startRestFromTab`, `retryRecording`, `runLearnBatchNow`.
Настройки кнопок и диска (`ButtonsSettings`, `DiskSettings`) пишут ключи,
которыми живёт оверлей, — меняется только вид экранов, не сеттеры.
`ui/` оверлей не импортирует, кроме `Feedback`, `Haptics` и `PravkaTheme`
(последний — только `QuickActionActivity`).

## 2. Что есть сейчас (разведка)

**Навигация** — `MainActivity.kt`: `enum Tab` (семь режимов + служебные),
`Scaffold` с нижней панелью из семи кнопок (`ui/Nav.kt: ModeBottomBar`), на
развороте ≥ 600 dp — колонка слева (`ModeRail`). Поверх вкладки — стопка
`Page` (служебный экран, настройки режима, стоимость). Ссылки снаружи —
`EXTRA_TAB` (`pravka`, `zasechka`, `todoist`, `sport`, `food`, `money`,
`settings`, `prompts`), `EXTRA_SETTINGS_GROUP`, `EXTRA_FOOD_ACTION`;
стартовая вкладка — Засечка.

**Каркас вкладки** — `ui/Frame.kt`: `ModeFrame` (краска режима через
`ModeDecor.tint`, свет `ui/Glow.kt`), `TabHeader` (значок в плашке, название
с засечками, значки в стеклянной капсуле), `glyphPattern` (узор знаков на
плашках), `scrollFade`. Набор — `ui/Kit.kt` (кнопки, чипы, `Segments`,
`PaperRow`, `PaperToggle`, `PaperSheet`, `PaperAlert`, `PaperField`,
`VoiceInput` — пилюля «сказать режиму», `TopPill`, `ThinkingLine`, `DayNav`,
`PaperSlider`, `InfoButton`), `ui/Blocks.kt` (`PaperLabel`, `PaperCard`,
`bevel`, `GoalBar`, `MicroBar`), `ui/CardLook.kt` (`keyFace`, `modeGlass`,
`grain`), `ui/Charts.kt` (бублик, полосы, столбики, линия, `KpiTile`),
значки — `ui/Glyphs.kt` + `ui/GlyphsGemini.kt` (уже Material Symbols Rounded
вес 300, путями в коде). Тема — `ui/Theme.kt`: тёмная всегда, засечки —
системный `FontFamily.Serif`, остальное — системный sans.

**Вкладки** (все через `PaperCard`/`PaperLabel`, пилюля `VoiceInput` первой
строкой): `PravkaTab.kt`, `ZasechkaTab.kt` (+`ZasechkaTasksCard.kt`),
`DelaTab.kt` (+`DelaCrmUi.kt`, `DelaAskUi.kt`, запас — `TodoistTab.kt`),
`SportTab.kt`, `FoodTab.kt`, `MoneyTab.kt` (+`MoneyPanels/Cards/
CashflowPanels/Charts.kt`), `ReportTab.kt` (Общая статистика),
`ReviewsTab.kt`, `SettingsTab.kt` и служебные экраны внутри `MainActivity.kt`
(Обучение, Логи, Словарь, Промпты).

## 3. Набор: что есть → во что превращается (DESIGN §11)

Главный ход: **перешить детали набора на месте**, сохранив их имена и
параметры. `PaperCard` зовут из ~300 мест — новая плашка-стекло дойдёт до всех
вкладок одной правкой, и «содержимое вкладок сохраняется» выполняется само.
Новые детали — сначала в набор, потом на экран.

| Сейчас | Станет (§) | Как |
|---|---|---|
| `PravkaTheme` (системные шрифты) | §5, §14 | Literata (переменный, `opsz` на стиль) + Golos Text в `res/font`; `PravkaType` в `LocalPravkaType`, слоты M3 заполнены ближайшими; `tnum` везде |
| `ModeDecor.tint` (своя пятёрка цветов) | `ModeColors` §4.2, `LocalMode` | `key/ink/tint/label/value/meta/eventText/ramp`; в режиме `primary = tint` (см. §8 плана), `primaryContainer = key` |
| `ModeGlowLayer` (4 пятна, соседи ±14°) | свет §4.5 | три пятна по макету каждого режима; «Сегодня» — крем + янтарь; дрейф 17/23/29 с; ярче +0.15 пока Claude думает; сжатая шапка — ×0.65 |
| `PaperCard` (модное стекло + узор знаков) | **GlassSurface** §8 | ink@.84–.86 + налёт key .22→.10 + блик + рамка 1 dp + верхняя линия + тень 0/14/30; **узор знаков снимается** (запрет «знаки режимов на фоне»), радиус 22 |
| `PaperLabel` | **SectionHeader** §11.7 | overline 11.5 прописными +0.06em в label режима, справа число в meta |
| `TabHeader` + капсула | **ModeHeader** §11.2 | «‹» · монета 34 · titleM Literata 24 · второй тон под названием («Сонет · high ⌄») · значки без капсулы |
| — | **DayHeader**, **ZPill**, **MiniStat**, **WeatherRow** | новые, для «Сегодня» |
| `VoiceInput` + `TopPill` (пилюля сверху) | **SayBar** §11.4 | внизу экрана; стекло, клавиша режима (на «Сегодня» — кремовая); состояния: пусто, текст→стрелка, слушаю, Claude думает (заливка + «Причёсываю · ещё 6 с»), итог, вопрос, ошибка |
| `keyFace` | **Key**, **CreamKey** §8 | круг с двумя радиальными бликами, внутренние линии, тень 0/6/14 |
| — | **Coin** | монета с буквой режима (векторы оверлея), 20/24/30/34 |
| `PaperButton(primary)` | **PrimaryKey** | капсула-клавиша key режима справа внизу |
| `PaperButton` контуром | — | капсула с рамкой tint@.30, текст label |
| `PaperChip`, `Segments`, `ChipRow` | **Segmented**, **Chip** §11.7 | выбранный — маленькая клавиша-капсула в key, остальные — текст label с рамкой tint@.18; числа в сегментах |
| `PaperToggle` | **Toggle** | трек ink + рамка tint@.3; вкл — бегунок-клавиша key, выкл — кремовый контур @.5 |
| `PaperSlider` | **Slider** | трек 4 dp tint@.18, активная часть tint, бегунок — клавиша 20 |
| `PaperSheet` | **BottomSheet** | стекло режима, верх 28, ручка 32×4 крем @.3, заголовок titleS |
| `IconAction` | **IconLabel** | значок в круге 48 (tint@.14, рамка tint@.28), подпись 12.5 |
| `InfoButton` | **InfoButton** | «i» 20 в кольце, лист с пояснением |
| `ThinkingLine` | ClaudeThinking §4.4 | заливка слева направо tint@.25 + слова и секунды |
| `DayNav` | **DayNavigator** | «‹ Сегодня / понедельник, 5 октября ›», «›» сегодня — `textDisabled` |
| `KpiTile` | **StatTile** | подпись · значение · ▲/▼ дельта в акцентном тексте, «хуже» приглушённее |
| `DonutChart`, `StackedColumns`, `LineChart`, `DayStripChart` | Charts §11.7 | ступени шкалы режима, без сетки, одна базовая линия |
| — | **ModeTile** | плашка режима на «Сегодня»: значок 16 + подпись, цифра valueL, полоса 4 |
| — | **Timeline**: TimeColumn, Rail, EntryRow, CurrentRow, EventChip, PendingCard, NowLine, FreeGap, PlannedRow, TaskGroupHeader, TaskRow, SleepRow, NowJump | §11.5 |
| — | **Dial**, **TimePlate**, **StackedBar**, **LegendItem**, **ZasechkaEntryRow**, **CategoryRow** | §11.6 |
| — | **SuggestionCard**, **CoachNote**, **ExerciseRow**, **AskChips**, **TaskRow (Дела)** | §11.7 |
| — | **EmptyState**, **LoadingLine**, **ErrorPlate**, **OfflineChip** | §11.7, §12.13 |

**Уходят (§11.8):** `ModeBottomBar` и `ModeRail` (`ui/Nav.kt`), пилюля
«сказать режиму» первой строкой (`TopPill`), капсула значков в шапке,
`RainbowScoreBar` (полоса баланса −5…+10 — её заменяют циферблат и
StackedBar), узор знаков `glyphPattern`.

**Витрина** — `ui/Showcase.kt`: все детали во всех состояниях по каждому
режиму, вход «Ещё → Служебное → Витрина» только при `BuildConfig.DEBUG`; у
каждой детали — `@Preview`. Для `@Preview` нужен `ui-tooling-preview` — это
AndroidX Compose (не сторонняя библиотека), он только аннотации.

## 4. Новые файлы

| Файл | Что |
|---|---|
| `res/font/literata.ttf`, `res/font/golos_text.ttf` + `OFL-*.txt` в `docs/design/redesign-4/fonts/` | шрифты (OFL), переменные |
| `ui/Tokens.kt` | основа §4.1, `ModeColors`, `LocalMode`, шкалы, группы категорий Засечки §4.3 |
| `ui/Type.kt` | семейства, `PravkaType`, `LocalPravkaType` |
| `core/Fmt.kt` | тысячи U+202F, минус U+2212, «1 ч 40 м», даты, округление будущего до 5 м — под JVM-тестами |
| `ui/Surfaces.kt` | Glass, Key, CreamKey, Coin, тени через `BlurMaskFilter`, штриховка |
| `ui/Headers.kt` | ModeHeader, DayHeader, ZPill, MiniStat, WeatherRow, Avatar |
| `ui/Tiles.kt` | ModeTile, StatTile |
| `ui/SayBar.kt` | SayBar и его состояния |
| `ui/Timeline.kt` | строки хроники |
| `ui/Dial.kt` | циферблат, плашка времени, полоса, легенда, строки Засечки, итоги |
| `ui/Parts.kt` | SectionHeader, Segmented, IconLabel, EmptyState, LoadingLine, ErrorPlate, OfflineChip, SuggestionCard, CoachNote, ExerciseRow, AskChips, NowJump |
| `ui/Layout.kt` | правило разворота §13 (`twoPane()` = ширина ≥ 600 и высота ≥ 600), колонки |
| `ui/Showcase.kt` | Витрина |
| `core/DayAssembler.kt` | сборка хроники `DayItem` — чистая функция под тестами |
| `data/WeatherStore.kt` | Open-Meteo через `HttpURLConnection`, геокодинг города один раз, кэш 1 ч |
| `TodayScreen.kt` | экран «Сегодня» (сложенный и разворот), лист записи |

**SayBar по вкладкам** — те же действия, что у нынешней пилюли, только внизу:

| Вкладка | Отправить | Голос | Кнопки |
|---|---|---|---|
| Сегодня | разбор Засечки (`zasechkaEngine.record`) — главная лента дня | `onZasechkaTap` | камера → Еда «photo» |
| Засечка | `zasechkaEngine.record(text, "text")` | `onZasechkaTap` | «стоп» = `closeOpen()` |
| Правка | `engine.proofread(…, CLEAN)` | — (кружок = отправить) | вставить из буфера |
| Дела | `onRaznoskaText` | `onRaznoskaTap` | — |
| Спорт | `bodyEngine.hear(text, "text")` | — | — |
| Еда | `foodEngine.parse(text, "text")` | `onFoodTap` | камера, галерея, штрихкод |
| Деньги | вопрос Claude (`AskCard`) на «Сводке», трата голосом — `onMoneyText` | `onMoneyTap` | — |

У Спорта и Денег в глубине вкладок остаются свои поля («спросить про
тренировки», ответ на вопрос карточкой) — это не пилюля режима, они остаются
на месте в новом виде.

## 5. Навигация (этап 3)

- `Tab.TODAY` — стартовая, если интент не просит другого. Нижней панели и
  колонки нет.
- Режим открывается поверх «Сегодня»: `tab` — режим, «назад» (системный и
  «‹» в шапке) из режима → «Сегодня». Стопка `Page` остаётся как есть и
  снимается первой.
- Входы: плашки режимов, «+84» → Засечка, тап по отметке → режим (с
  прокруткой к записи, где режим это умеет), «С» → Ещё (страница стопкой),
  плавающие кнопки — те же `EXTRA_TAB` (константы не меняются: их шлёт
  оверлей).
- Правка как режим — из «Ещё» (первая строка) и из меню «П».
- Переходы — fade-through 250 мс (`AnimatedContent`).

## 6. «Сегодня»: источники данных

| Что | Есть? | Откуда |
|---|---|---|
| Записи Засечки, категории, очки | есть | `zasechkaStore.entriesFlow/categoriesFlow`, очки = `DayReport.pointsOf(category.value, ms)`; полночь уже режется в сторе (`splitMidnightLocked`), полную ночь склеивает `DayReport.sleepBlocks`; подъём — `DayReport.rhythm` |
| Текущая запись | есть | `end == 0L`; открытая «не размечено» — тоже «идёт», но без чипа категории |
| Балл дня | есть | `DayReport.balance` (накопленное текущей входит), округление в конце |
| Сравнения балла (к тому же дню недели, место среди 28) | есть кусками | `DayReport.median/rank/Window.shifted` — сборка как в `ReportTab`, вынести в общий расчёт |
| Еда: приёмы со временем, ккал | есть | `foodStore.mealsOn(date)` (`Meal.ts`), ждущие — `foodStore.pending()` |
| Цель ккал | есть | `settings.foodKcalFlow` |
| Деньги за день со временем | есть, без дневного API | фильтр `moneyStore.stateFlow` по `MoneyStats.dayOf(ts)`, `live()`, `expense`; `timeKnown=false` — отметка без времени (в конец дня, не в ленту — в плашку) |
| Дневной лимит денег | **нет** | новая настройка «месячный бюджет»; лимит = бюджет / дней в месяце; без неё полоса не рисуется |
| Спорт: сделанные | есть | `sportStore.workoutsFlow` по `dayKey(start)`; км, темп, пульс |
| Спорт: план на сегодня | есть | `planStore.mainOf(date)` (`time` «07:30» или пусто, `minutes`); нет времени — тренировка встаёт в очередь как фиксированное событие после «сейчас» не ставится, а показывается строкой «Спорт · план» без времени перед делами |
| Готовность | есть | `trafficLight.today()` (`tone`, `headline`, числа HRV/сон/форма) |
| Зарядка «N из M» | есть, считается в UI | вынести подсчёт из `ZaryadkaChecklist` в общий |
| Дела на сегодня: порядок, минуты | есть | `DelaViews.morning(...)` — порядок списка «На сегодня»; `Task.estimateMin`, `dueTime` (фиксированное время), `focusOn` |
| События дел со временем | есть частично | «из встречи / из почты / Telegram» — `Suggestion.createdAt` + `source`; «сделано» — `Task.completedAt`; история дела — только по запросу (`delaSync.card`), в ленту не идёт |
| Календарь | есть | `CalendarPilot.query/calendars`, `READ_CALENDAR` уже в манифесте; выбор календарей — `settings.autoCalendarsFlow` (тот же, что у автопилота) + новая отдельная настройка «календари в ленте» |
| Погода | **нет** | `data/WeatherStore.kt`: Open-Meteo, город из настроек → координаты geocoding API один раз, прогноз почасовой, кэш 1 ч в `DataRoot.dir`, без сети и без города — ряд скрыт |
| Время сна | **нет** | новая настройка, с завода 23:30 |
| Экран телефона | есть | `phoneStore.daysFlow` + `PhoneDaySummary.of` |

**Сборка хроники** — `core/DayAssembler.kt`, чистая функция (вход — простые
модели, не сторы), под JVM-тестами по примеру из DESIGN §11.5 (силовая до
21:15 → очередь с 21:30; 45+30+20+10+40 → конец 23:55 → «дела не влезают на
25 м»). Отметки других режимов прикрепляются к записи Засечки, в чей интервал
попало их время; после «сейчас» — свободные окна, календарь, планы
тренировок, очередь дел, сон.

**Просьба владельца (07.10):** «в засечке надо будет добавить дела
следующие, а не только текущие». Решение: будущее «Сегодня» показывает очередь
дел по оценке минут до сна, а в Засечке под «Сейчас» — та же очередь «Дальше»
(ближайшие дела с временем старта), строки с ▶.

## 7. Разворот (этап 8)

Правило §13: две колонки при ширине ≥ 600 **и** высоте ≥ 600 dp, считаем по
`LocalConfiguration`, не по `WindowWidthSizeClass`. Слева обзор (x 20, 366),
справа список/лента/деталь (x 410, 365), SayBar внизу правой колонки. Деталь
(лист записи, карточка дела, операция) — в правой колонке; исключение —
«Сегодня»: лист снизу шириной правой колонки.

## 8. Решения и расхождения (заранее)

- **`primary = tint`, а не key.** §14 просит `primary = mode.key`, но старые
  экраны красят `primary` текст и значки (подписи, ссылки, `TextButton`):
  тёмно-синий `#2A5D82` на ночи не читается. `primaryContainer = key`, новые
  детали берут цвета из `LocalMode` напрямую. Запишу в отчёт.
- **Т и Е** — существующие векторы `ic_body_glyph`/`ic_efab_glyph` (тот же
  брусковый язык), а не контуры из макета.
- **Значки** — продолжение `ui/GlyphsGemini.kt` (Material Symbols Rounded 300
  путями в коде, как уже заведено), а не `res/drawable/ic_*.xml`: DESIGN
  разрешает оставить свои векторы, а почерк тот же.
- **`@Preview`** требует `androidx.compose.ui:ui-tooling-preview` (AndroidX).
- **Кнопка «стоп»** на SayBar Засечки закрывает текущую запись тем же
  `zasechkaEngine.closeOpen()`, что и прежняя кнопка.

## 9. Вопросы владельцу (решаю сам по умолчанию, отмечу в отчёте)

1. Город погоды — с завода «Москва» (иначе ряд пуст до первой настройки).
2. Календари в ленте — с завода те же, что выбраны для автопилота встреч.
3. Месячный бюджет — с завода не задан: полоса на плашке Деньги не рисуется.
4. Правка без нижней кнопки — вход из «Ещё» и меню «П»; если нужна плашка
   на «Сегодня» — скажи.

## 10. Этапы и проверка

0. План (этот файл) + макеты в `docs/design/redesign-4/`.
1. Тема и токены, шрифты, форматтеры (+ тесты `FmtTest`).
2. Набор деталей и Витрина; перешить `PaperCard`, `PaperLabel`, `TabHeader`,
   `VoiceInput`, переключатели, листы.
3. Навигация.
4. «Сегодня» на сложенном (+ `DayAssemblerTest`, погода, настройки).
5. Засечка: циферблат, плашка времени, лента, итоги, телефон, SayBar со стоп.
6. Режимы: Дела (Утро, Новое и соседние), Спорт, Еда, Деньги, Правка.
7. Ещё, Настройки (новые пункты), листы, Общая статистика.
8. Разворот.
9. Состояния §12.13.
10. Снимки 411×900 и 791×820 (Robolectric, `app/src/shots`) в
    `docs/redesign-4-shots/`, отчёт `docs/redesign-4-report.md`, документы
    режимов.

Проверка на каждом шаге: `./gradlew compileDebugKotlin testDebugUnitTest
-PbuildNumber=999`, дифф `trigger/` пуст.
