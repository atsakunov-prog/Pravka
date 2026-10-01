# Архив Правки — сервис на домашнем компе

Вся жизнь из Правки и intervals.icu в одной базе PostgreSQL. Claude в
claude.ai читает её через MCP. Как устроено и почему — `docs/arkhiv.md`,
там же контракт событий. Подготовка машины — `SETUP-PC.md`, часть А.

## Что внутри

Один процесс на Python (`python -m pravka_archive serve`), один порт
(`PRAVKA_LISTEN`, с завода 8090). Роутер публикует его по HTTPS на 8443.

| Путь | Кто ходит | Вход |
|---|---|---|
| `/mcp` | Claude из claude.ai | OAuth: страница `/login`, пароль `PRAVKA_OWNER_PASSWORD` |
| `/ingest` | телефон | `Authorization: Bearer PRAVKA_INGEST_TOKEN` |
| `/.well-known/…`, `/register`, `/authorize`, `/token`, `/revoke` | Claude при подключении | — |
| `/health` | проверка | — |

В том же процессе работает **сборщик intervals**:

- каждые 10 минут — свежее;
- раз в сутки после 04:00 — 60 дней назад;
- при первом запуске — вся история.

Ему нужны `ICU_ATHLETE_ID` и `ICU_API_KEY`. Без них он спит, остальное работает.

База устроена так:

- **`core`** — журнал событий `core.events`, текущее состояние `core.records`,
  указатель сказанного, токены. Меняется только новыми файлами
  `sql/core_NNNN.sql`.
- **`life`** — виды для Claude. Строится заново на каждом `migrate`.

Claude читает ролью `pravka_reader`: только `life` и только чтение.

## Развёртывание

На Windows, после части А из `SETUP-PC.md`: Postgres стоит, роли заведены,
`D:\PravkaArchive\secrets\server.env` заполнен.

```powershell
cd D:\PravkaArchive\repo\server
py -3.12 -m venv .venv
.venv\Scripts\python -m pip install --upgrade pip
.venv\Scripts\python -m pip install -r requirements.txt
.venv\Scripts\python -m pravka_archive migrate
.venv\Scripts\python -m pravka_archive check
```

`check` проверяет базу двумя ролями, папки, ключ intervals и адрес снаружи.
Адрес снаружи будет «НЕТ», пока сервис не запущен. Остальное должно быть «ОК».

**Пробный запуск руками:** `.venv\Scripts\python -m pravka_archive serve`.
Пока он работает, ещё раз `check` в другом окне: теперь и адрес снаружи
должен быть «ОК». Если там страница роутера, приложение в KeenDNS смотрит не
туда.

**Служба — задача планировщика от SYSTEM** (по образцу задачи rclone):

```powershell
$py  = "D:\PravkaArchive\repo\server\.venv\Scripts\python.exe"
$act = New-ScheduledTaskAction -Execute $py -Argument "-m pravka_archive serve" -WorkingDirectory "D:\PravkaArchive\repo\server"
$trg = New-ScheduledTaskTrigger -AtStartup
$set = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
       -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
Register-ScheduledTask -TaskName "Pravka Archive" -Action $act -Trigger $trg -Settings $set -User "SYSTEM" -RunLevel Highest
Start-ScheduledTask -TaskName "Pravka Archive"
```

Журнал службы — `PRAVKA_LOGS\archive.log`.

**Первая выгрузка intervals** (вся история) пойдёт сама при первом запуске.
Она может идти от минут до часа: по каждой тренировке тянутся карточка,
потоки и исходный файл. Посмотреть руками:
`.venv\Scripts\python -m pravka_archive pull full`.

**Подключить в claude.ai:**

1. Settings → Connectors → Add custom connector: имя «Правка», адрес
   `PRAVKA_PUBLIC_URL/mcp`.
2. Connect. Откроется страница «Архив Правки», туда вводится пароль
   `PRAVKA_OWNER_PASSWORD`.
3. В чате включить коннектор в меню инструментов и спросить, например:
   «что у меня было 7 сентября?»

Коннектор, подключённый в claude.ai, работает и в мобильном приложении.

**Телефон.** `.venv\Scripts\python -m pravka_archive pair` печатает в консоли
QR с адресом и токеном. Сканер для него появится в Правке отдельной сборкой
(Настройки → Подключения → Архив).

## Обновление

```powershell
cd D:\PravkaArchive\repo
git pull
cd server
.venv\Scripts\python -m pip install -r requirements.txt
.venv\Scripts\python -m pravka_archive migrate
Stop-ScheduledTask -TaskName "Pravka Archive"; Start-ScheduledTask -TaskName "Pravka Archive"
```

## Если что-то не так

- **`check`** говорит словами, что не так.
- **Журнал** — `archive.log`.
- **Свежесть источников** (кто и когда последний раз прислал данные) —
  вид `life.freshness`, её же Claude видит в `schema`.
- **Выгнать Claude отовсюду:** сменить `PRAVKA_OWNER_PASSWORD`, выполнить
  `DELETE FROM core.oauth_tokens;` владельцем базы и перезапустить задачу.
  После этого надо подключиться заново.
- **Сменить токен телефона:** новый `PRAVKA_INGEST_TOKEN`, перезапуск,
  `pair` и сканировать заново.

## Тесты

Нужен PostgreSQL с суперпользователем. Тесты заводят свою базу и роли, а в
конце их сносят.

```powershell
.venv\Scripts\python -m pip install -r requirements-dev.txt
$env:PRAVKA_TEST_DSN = "postgresql://postgres:<пароль>@127.0.0.1:5432/postgres"
.venv\Scripts\python -m pytest
```

Контракт с телефоном — `contract/batch.json`: пример каждого вида события.
Тесты сервера читают его; тесты телефона будут проверять, что телефон
порождает ту же форму.
