# Локальный Whisper для Правки

Модель, загруженная в видеопамять один раз, и OpenAI-совместимый эндпоинт
поверх неё. Отсюда берёт расшифровки десктопная Правка; сюда же можно кидать
часовые встречи.

Почему не «запустить `whisper` из командной строки»: при каждом запуске модель
заново читается в GPU. Для встречи это терпимо, для диктовки на десять секунд
— нет. Плюс движок здесь `faster-whisper` (CTranslate2) вместо референсного
PyTorch: веса те же, исполнение обычно в 3–5 раз быстрее и вдвое экономнее по
видеопамяти.

## Установка (Windows 11, PowerShell)

```powershell
cd scripts\whisper
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\pip.exe install -r requirements.txt
```

Первый запуск скачает веса с Hugging Face (turbo — около 1.6 ГБ) в
`%USERPROFILE%\.cache\huggingface`.

```powershell
.\run.ps1                                        # запуск в окне, видно лог
Invoke-RestMethod http://127.0.0.1:8178/health   # в другом окне
```

Автозапуск при входе в систему:

```powershell
.\install-service.ps1            # поставить
.\install-service.ps1 -Remove    # убрать
```

## Настройки

Всё в `whisper.env` рядом. Главное:

| Ключ | Смысл |
|---|---|
| `PRAVKA_WHISPER_MODEL` | модель по умолчанию; `large-v3-turbo` для диктовки |
| `PRAVKA_WHISPER_PRELOAD` | что загрузить сразу при старте (через запятую) |
| `PRAVKA_WHISPER_COMPUTE` | `float16` — быстрее, `int8_float16` — вдвое меньше VRAM |
| `PRAVKA_WHISPER_MAX_LOADED` | сколько моделей держать в памяти одновременно |
| `PRAVKA_WHISPER_HOST` | `127.0.0.1` — только этот компьютер. См. «Доступ с телефона» |

Модель выбирается в каждом запросе: диктовка шлёт `large-v3-turbo`, встречу
можно разобрать точной `large-v3` — вторая подгрузится по первому обращению
и останется в памяти.

## Как пользоваться руками

```powershell
# короткая запись
curl.exe -F file=@take.wav -F model=large-v3-turbo http://127.0.0.1:8178/v1/audio/transcriptions

# часовая встреча точной моделью, ответ текстом
curl.exe -F file=@meeting.m4a -F model=large-v3 -F response_format=text `
         http://127.0.0.1:8178/v1/audio/transcriptions
```

Параметр `prompt` — подсказка распознавателю: Правка кладёт туда слова из
своего словаря, и фамилии с терминами распознаются правильно сразу, без
последующей починки.

## Если не заводится

- **`Could not load library cudnn_ops64_9.dll`** — CTranslate2 не нашёл cuDNN.
  Он ставится вместе с зависимостями (`nvidia-cudnn-cu12`); если ставил
  зависимости без них, добавь:
  `.\.venv\Scripts\pip.exe install nvidia-cublas-cu12 nvidia-cudnn-cu12`.
- **Не хватает видеопамяти** — поставь `PRAVKA_WHISPER_COMPUTE=int8_float16`
  и `PRAVKA_WHISPER_MAX_LOADED=1`.
- **Нет GPU под рукой** — `PRAVKA_WHISPER_DEVICE=cpu` и
  `PRAVKA_WHISPER_COMPUTE=int8`. Медленно, но работает.
- **Модель `large-v3-turbo` не найдена** — старый `faster-whisper`. Обнови
  (`pip install -U faster-whisper`) или впиши в `PRAVKA_WHISPER_MODEL` полный
  идентификатор репозитория, например
  `mobiuslabsgmbh/faster-whisper-large-v3-turbo`.

## Доступ с телефона (по желанию)

Сервер слушает только `127.0.0.1`. Если захочется, чтобы телефон дома тоже
распознавал этой моделью вместо своей `small`, поставь
`PRAVKA_WHISPER_HOST=0.0.0.0` — и **только вместе** с ограничением доступа:
или правило брандмауэра на домашнюю сеть, или Tailscale. Открывать порт в
интернет нельзя: эндпоинт без пароля.
