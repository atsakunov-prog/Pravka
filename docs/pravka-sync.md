# Один словарь на телефоне и на воркстанции

Общая таблица в твоём Google-аккаунте, за ней - маленькое веб-приложение Apps
Script. Тот же приём, что у Засечки (`docs/zasechka-sheets.md`): никакого
OAuth, секретом служит сам URL скрипта - не публикуй его.

Что ездит:

| Лист | Направление | Что там |
|---|---|---|
| `Словарь` | в обе стороны | записи словаря целиком |
| `Правила` | в обе стороны | выученные правила |
| `Промпты` | в обе стороны | только твои правки заводских текстов |
| `Расшифровки` | вверх | что распознано, с метриками |
| `Статистика` | вверх | сводка расхода по каждому устройству |

Правила слияния:

- **спор решает время правки**: чья запись новее, та и права;
- **удаление - надгробием**: удалённая запись едет с пометкой `deleted`, иначе
  второе устройство вернуло бы её обратно при следующей синхронизации. Через
  90 дней надгробия убираются сами;
- **счётчик срабатываний** берётся наибольший из двух: он косметический, а
  складывать приросты значило бы вести на каждом устройстве ещё и учёт
  отправленного;
- словарь и правила уезжают **целиком**, а не приростом: это десятки
  килобайт, зато нечему разъехаться после сбоя.

Когда: при запуске, дальше раз в 12 часов, через полминуты после правки
словаря руками и по кнопке «Синхронизировать».

## Настройка за пять минут

1. Создай таблицу, например «Правка — общее».
2. **Расширения → Apps Script**, вставь скрипт целиком, сохрани.
3. **Развернуть → Новое развёртывание → Веб-приложение**: выполнять **от моего
   имени**, доступ - **все** (URL содержит длинный случайный токен; именно
   поэтому его никому не показываем).
4. Скопируй URL вида `https://script.google.com/macros/s/…/exec`.
5. Вставь его на обоих устройствах: на телефоне - Настройки → «Общий словарь»,
   на воркстанции - Настройки → «Синхронизация». Нажми «Синхронизировать».

Первый обмен сводит два словаря в один: записи, совпадающие по паре
«слышится + режим», не удваиваются, а склеиваются.

## Скрипт

```javascript
// Правка: общий словарь, правила, промпты, расшифровки, статистика.
// Принимает POST c JSON и возвращает слитое состояние.

const SHEETS = {
  dict: 'Словарь',
  rules: 'Правила',
  prompts: 'Промпты',
  transcripts: 'Расшифровки',
  stats: 'Статистика',
};

const HEADERS = {
  dict: ['uid', 'from', 'to', 'mode', 'note', 'hits', 'enabled', 'createdAt', 'updatedAt', 'deleted'],
  rules: ['uid', 'text', 'enabled', 'created', 'updatedAt', 'deleted', 'before', 'after'],
  prompts: ['key', 'text', 'updatedAt'],
  transcripts: ['ts', 'device', 'engine', 'audio_s', 'transcribe_s', 'chars', 'text'],
  stats: ['device', 'updatedAt', 'total', 'errors', 'chars', 'tokensIn', 'tokensOut', 'costTotalUsd', 'costTodayUsd'],
};

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const body = JSON.parse(e.postData.contents);
    const out = {
      serverTime: Date.now(),
      dict: mergeRows('dict', body.dict || [], 'uid', mergeByUpdatedAt),
      rules: mergeRows('rules', body.rules || [], 'uid', mergeByUpdatedAt),
      prompts: mergeRows('prompts', body.prompts || [], 'key', mergeByUpdatedAt),
    };
    appendRows('transcripts', body.transcripts || [], 'ts', 'device');
    appendRows('stats', body.stats || [], 'device');
    return json(out);
  } catch (err) {
    return json({ error: true, message: String(err) });
  } finally {
    lock.releaseLock();
  }
}

function sheet(kind) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sh = ss.getSheetByName(SHEETS[kind]);
  if (!sh) {
    sh = ss.insertSheet(SHEETS[kind]);
    sh.appendRow(HEADERS[kind]);
    sh.setFrozenRows(1);
  }
  return sh;
}

// Новее - тот, у кого updatedAt больше. Счётчик срабатываний берём
// наибольший: он считается на обоих устройствах независимо.
function mergeByUpdatedAt(existing, incoming) {
  const winner = (Number(incoming.updatedAt) || 0) > (Number(existing.updatedAt) || 0)
    ? Object.assign({}, incoming) : Object.assign({}, existing);
  if (existing.hits !== undefined || incoming.hits !== undefined) {
    winner.hits = Math.max(Number(existing.hits) || 0, Number(incoming.hits) || 0);
  }
  return winner;
}

function mergeRows(kind, incoming, keyField, merge) {
  const sh = sheet(kind);
  const header = HEADERS[kind];
  const values = sh.getDataRange().getValues();
  const rows = {};
  const order = [];
  for (let i = 1; i < values.length; i++) {
    const row = objectFromRow(header, values[i]);
    const key = String(row[keyField] || '');
    if (!key) continue;
    if (!(key in rows)) order.push(key);
    rows[key] = row;
  }
  for (const item of incoming) {
    const key = String(item[keyField] || '');
    if (!key) continue;
    if (key in rows) {
      rows[key] = merge(rows[key], item);
    } else {
      rows[key] = item;
      order.push(key);
    }
  }
  const out = order.map(function (key) { return rows[key]; });
  const table = [header].concat(out.map(function (row) {
    return header.map(function (field) {
      const value = row[field];
      return value === undefined || value === null ? '' : value;
    });
  }));
  sh.clear();
  sh.getRange(1, 1, table.length, header.length).setValues(table);
  sh.setFrozenRows(1);
  return out;
}

// Только вверх: строки дописываются, повторы отсекаются по ключу.
function appendRows(kind, incoming, /* ...keyFields */) {
  if (!incoming.length) return;
  const keyFields = Array.prototype.slice.call(arguments, 2);
  const sh = sheet(kind);
  const header = HEADERS[kind];
  const values = sh.getDataRange().getValues();
  const seen = {};
  const rowByKey = {};
  for (let i = 1; i < values.length; i++) {
    const row = objectFromRow(header, values[i]);
    const key = keyFields.map(function (f) { return String(row[f] || ''); }).join('|');
    seen[key] = true;
    rowByKey[key] = i + 1;  // номер строки на листе
  }
  const fresh = [];
  for (const item of incoming) {
    const key = keyFields.map(function (f) { return String(item[f] || ''); }).join('|');
    const line = header.map(function (field) {
      const value = item[field];
      return value === undefined || value === null ? '' : value;
    });
    if (seen[key]) {
      // Сводка устройства обновляется на месте, а не плодит строки.
      sh.getRange(rowByKey[key], 1, 1, header.length).setValues([line]);
    } else {
      seen[key] = true;
      fresh.push(line);
    }
  }
  if (fresh.length) {
    sh.getRange(sh.getLastRow() + 1, 1, fresh.length, header.length).setValues(fresh);
  }
}

function objectFromRow(header, row) {
  const out = {};
  header.forEach(function (field, i) { out[field] = row[i]; });
  return out;
}

function json(payload) {
  return ContentService.createTextOutput(JSON.stringify(payload))
    .setMimeType(ContentService.MimeType.JSON);
}
```

## Если что-то пошло не так

- **«Таблица ответила 302»** - развёртывание сделано с доступом «только я».
  Переразверни с доступом «все».
- **Записи удвоились** - словари сводились по паре «слышится + режим», а у
  тебя два разных написания одного слова. Лишнее удали в приложении: удаление
  доедет надгробием и на второе устройство.
- **Удалённое слово вернулось** - значит, второе устройство не синхронизировалось
  дольше 90 дней и надгробие успело убраться. Удали ещё раз.
- **Текст расшифровок в таблице не нужен** - выключи «слать текст расшифровок»
  в настройках; метрики продолжат ездить.
