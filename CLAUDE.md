# Слушалка — карта для сессии

Плеер аудиокниг с читалкой и вопросами по книге: Kotlin, Compose, Media3, один
APK `ru.zf.slushalka`. Живёт в ветке **`slushalka`** репозитория Pravka; с
Правкой (ветка `pravka`) общие только `keystore/pravka.jks` и ветка
`apk-builds`. Код и документация — по-русски, комментарии объясняют ПОЧЕМУ.
Спецификация — `README.md`; здесь только карта.

## Где что

- `slushalka/src/main/java/ru/zf/slushalka/`
  - `SlushalkaApp.kt` — сервис-локатор; `MainActivity.kt` — экраны.
  - `player/` — Media3, служба воспроизведения, шторка, куски аудио.
  - `library/` — сканер папки SAF, книги, естественный порядок файлов.
  - `text/` — fb2/epub, текст книги, привязка «секунда ↔ знак»
    (`Alignment.kt`, `Locator.kt`), извлечение текста.
  - `ask/` — Claude: `ClaudeClient.kt` (транспорт, цены), `AskEngine.kt`
    (вопрос по книге, пересказ), `Prompts.kt`, голосовой ввод, озвучка.
  - `data/` — `Settings.kt` (DataStore снимком `Prefs`, в том числе модели и
    усилие для вопроса и пересказа), позиции и их синк, разметка, `Updater.kt`
    (самообновление из `apk-builds`).
  - `ui/` — экраны: библиотека, плеер, читалка, вопрос, пересказ, настройки.

## Сборка и проверка

```bash
./gradlew :slushalka:compileReleaseKotlin -PbuildNumber=999   # компиляция
./gradlew :slushalka:assembleRelease -PbuildNumber=999         # APK
```

В облачной сессии SDK ставится в `/opt/android-sdk`, путь — в
`local.properties` (`sdk.dir=…`, файл не в git). Перед пушем компиляция
обязательна.

## Ветка и доставка

- **Линия одна — `slushalka`.** Новая работа — своей веткой от неё, потом
  fast-forward `slushalka` и удалить рабочую ветку. PR не открывать без явной
  просьбы. Коммиты: тема по-русски, дальше причины.
- CI (`.github/workflows/build-apk.yml`) собирает релизный APK на каждый пуш,
  а публикует в `apk-builds` **только из `slushalka`**: `slushalka.apk`,
  `slushalka-build-info.txt` (+ `slushalka-debug.apk` для старых копий). Файлы
  Правки рядом (`pravka-debug.apk`, `build-info.txt`) не трогать: публикация
  забирает их из ветки и кладёт обратно, пуш — с `--force-with-lease` и
  повтором.
- Приложение читает `slushalka-build-info.txt`; файл без строки `slushalka=` —
  не его сборка, и перед установкой проверяется пакет внутри APK.
- Подпись — только `keystore/pravka.jks`. Другой ключ сломает установку
  поверх и стёр бы позиции и разметку.
- Модели и усилие для вопроса и пересказа — настройки владельца
  (`Settings.Prefs.askModel/recapModel`, раздел «Модели»), заводские — Опус 5
  и Сонет 5; в запросе `thinking` не передаётся (Fable отвергает «disabled»).
