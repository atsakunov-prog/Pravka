# Засечка → Google Sheets: настройка за 5 минут

Телефон — источник истины (`zasechka.json` в данных приложения), таблица —
зеркало для глаз и формул. Приложение отправляет закрытые записи маленькому
веб-приложению Apps Script, привязанному к твоей таблице; строки обновляются
по `id`, так что правка записи в приложении обновит и строку в таблице.
Никакого OAuth: секретом служит сам URL скрипта — не публикуй его.

## Шаги

1. Создай (или открой) Google-таблицу, в которой хочешь видеть записи.
2. Меню **Расширения → Apps Script**.
3. Сотри заготовку и вставь скрипт целиком (ниже). Сохрани (Ctrl+S).
4. **Развернуть → Новое развёртывание → Тип: Веб-приложение**:
   - «Выполнять от имени» — **От моего имени**;
   - «У кого есть доступ» — **Все**. (Да, «все»: URL содержит длинный
     случайный токен, подобрать его нельзя. Именно поэтому URL никому
     не показываем.)
5. Нажми «Развернуть», разреши доступ своему аккаунту, скопируй
   **URL веб-приложения** (`https://script.google.com/macros/s/…/exec`).
6. В Правке: вкладка **Засечка → Настройки Засечки → Google Sheets**,
   вставь URL, «Сохранить», затем «Синхронизировать» — в таблице появится
   лист «Засечки» с колонками и строками.

После этого синхронизация автоматическая: каждая закрытая запись улетает в
таблицу через несколько секунд; если сети не было — при следующем удобном
случае или по кнопке «Синхронизировать».

## Скрипт

```javascript
// Засечка -> Google Sheets. Принимает POST c JSON {entries:[...]} и
// обновляет/добавляет строки по id на листе SHEET_NAME.
const SHEET_NAME = 'Засечки';
const HEADER = ['id', 'Дата', 'Начало', 'Конец', 'Минуты',
                'Дело', 'Категория', 'Клиент', 'Полезность', 'Надиктовано'];

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    const body = JSON.parse(e.postData.contents);
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    let sh = ss.getSheetByName(SHEET_NAME);
    if (!sh) {
      sh = ss.insertSheet(SHEET_NAME);
      sh.appendRow(HEADER);
      sh.setFrozenRows(1);
    }
    // Индекс id -> номер строки, чтобы правки обновляли, а не дублировали.
    const lastRow = sh.getLastRow();
    const index = {};
    if (lastRow > 1) {
      const ids = sh.getRange(2, 1, lastRow - 1, 1).getValues();
      for (let i = 0; i < ids.length; i++) {
        const v = String(ids[i][0]);
        if (v) index[v] = i + 2;
      }
    }
    let upserted = 0;
    (body.entries || []).forEach(function (en) {
      const row = [String(en.id), en.date, en.start, en.end, en.minutes,
                   en.title, en.category, en.client,
                   en.useful == null ? '' : en.useful, en.raw];
      const at = index[String(en.id)];
      if (at) {
        sh.getRange(at, 1, 1, row.length).setValues([row]);
      } else {
        sh.appendRow(row);
      }
      upserted++;
    });
    return ContentService
      .createTextOutput(JSON.stringify({ ok: true, upserted: upserted }))
      .setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return ContentService
      .createTextOutput(JSON.stringify({ ok: false, error: String(err) }))
      .setMimeType(ContentService.MimeType.JSON);
  } finally {
    lock.releaseLock();
  }
}
```

## Если что-то не так

- **«таблица ответила не по формату»** — почти всегда неверный доступ в
  развёртывании: должно быть «Выполнять от моего имени» + «Все». После
  правок скрипта делай **Новое развёртывание** (URL меняется!) или обновляй
  существующее через «Управление развёртываниями».
- **Строки не появляются** — проверь, что вставлен URL, оканчивающийся на
  `/exec` (не `/dev`).
- Дубликаты по одной записи невозможны: строки ищутся по `id` (первая
  колонка). Колонку `id` можно скрыть, но не удаляй её.
