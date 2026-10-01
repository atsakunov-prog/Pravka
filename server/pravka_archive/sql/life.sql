-- Схема life — то, что видит Claude. Только виды поверх core.records: их
-- можно менять сколько угодно, данные от этого не меняются. migrate сносит
-- схему и строит заново, затем пересобирает указатель сказанного.
--
-- Правила, которые здесь держатся (docs/arkhiv.md):
--  * сутки — поле day, его решил телефон (или intervals: start_date_local);
--    из UTC сутки не выводятся никогда: в Notion так получалось 1376 и 1506
--    минут вместо 1440;
--  * местное время — из самой строки ISO с поясом (символы 12–16), а не
--    пересчётом в пояс сервера;
--  * что идёт в счёт, решает телефон (money.live, food.confirmed): виды
--    правил не придумывают;
--  * деньги — целые копейки.

DROP SCHEMA IF EXISTS life CASCADE;
DROP VIEW IF EXISTS core.said_source;
CREATE SCHEMA life;
COMMENT ON SCHEMA life IS 'Архив Правки для чтения: виды по режимам, сутки, всё сказанное.';

CREATE FUNCTION life.hm(iso text) RETURNS text
    LANGUAGE sql IMMUTABLE AS $$ SELECT substring(iso from 12 for 5) $$;
COMMENT ON FUNCTION life.hm(text) IS 'Местное время ЧЧ:ММ из строки ISO с поясом.';

-- ---------------------------------------------------------------- Засечка

CREATE VIEW life.categories AS
SELECT c->>'name'                AS name,
       (c->>'value')::int        AS value_per_hour,
       NULLIF(c->>'hint', '')    AS hint,
       (c->>'base_min')::int     AS base_min,
       (c->>'ord')::int          AS ord
FROM core.records r, jsonb_array_elements(r.data->'categories') c
WHERE r.kind = 'zasechka.reference' AND r.key = 'all' AND NOT r.deleted;
COMMENT ON VIEW life.categories IS 'Справочник категорий ленты: ценность часа (−10…+10), подсказка, что владелец к ней относит, базовое время в минутах.';

CREATE VIEW life.clients AS
SELECT c #>> '{}' AS name
FROM core.records r, jsonb_array_elements(r.data->'clients') c
WHERE r.kind = 'zasechka.reference' AND r.key = 'all' AND NOT r.deleted;
COMMENT ON VIEW life.clients IS 'Клиенты, которых знает лента.';

CREATE VIEW life.entries AS
SELECT (e->>'id')::bigint                    AS id,
       (r.data->>'day')::date                AS day,
       (e->>'start')::timestamptz            AS start_at,
       (e->>'end')::timestamptz              AS end_at,
       life.hm(e->>'start')                  AS start_local,
       life.hm(e->>'end')                    AS end_local,
       (e->>'minutes')::int                  AS minutes,
       e->>'title'                           AS title,
       e->>'category'                        AS category,
       NULLIF(e->>'client', '')              AS client,
       e->>'source'                          AS source,
       NULLIF(e->>'raw', '')                 AS raw,
       NULLIF(e->>'comment', '')             AS comment,
       COALESCE((e->>'pomodoros')::int, 0)   AS pomodoros,
       NULLIF((e->>'rating')::int, 0)        AS rating,
       (e->>'end') IS NULL                   AS open,
       c.value_per_hour,
       round((e->>'minutes')::numeric / 60 * c.value_per_hour, 1) AS points
FROM core.records r
CROSS JOIN LATERAL jsonb_array_elements(r.data->'entries') e
LEFT JOIN life.categories c ON lower(c.name) = lower(e->>'category')
WHERE r.kind = 'zasechka.day' AND NOT r.deleted;
COMMENT ON VIEW life.entries IS 'Лента Засечки: что владелец делал, кусками времени. Сумма minutes за полные сутки = 1440. source: voice/text — сказал или набрал, edit — правил руками, auto — телефон или часы (сон, тренировка), gap — заполнитель «не размечено», todoist — запуск из задачи, nfc, autopilot, calendar.';
COMMENT ON COLUMN life.entries.day IS 'Сутки владельца (по поясу телефона). Группировать и сравнивать дни — только по нему.';
COMMENT ON COLUMN life.entries.minutes IS 'Минуты суток куска сверх более ранних: нахлёст с предыдущим куском (дубль, секунды на стыке) второй раз не считается, поэтому сумма за полные сутки — 1440. Нахлёст виден по start/end; у дубля minutes = 0. Пусто у идущего дела.';
COMMENT ON COLUMN life.entries.raw IS 'Что владелец сказал дословно, с ошибками распознавания. Строки «КБЖУ: …» дописала Еда.';
COMMENT ON COLUMN life.entries.comment IS 'Слова владельца о деле, уже осознанные: что было внутри и чем кончилось. Весит больше надиктовки.';
COMMENT ON COLUMN life.entries.points IS 'Очки = часы × ценность часа категории (по нынешнему справочнику). Пусто, если категории нет в справочнике: справочник присылает телефон (zasechka.reference), своего на сервере нет — второй источник правды разошёлся бы с телефоном.';
COMMENT ON COLUMN life.entries.open IS 'Дело идёт сейчас: конца ещё нет.';

-- ---------------------------------------------------------------- Телефон

CREATE VIEW life.phone_days AS
SELECT (d->>'day')::date                       AS day,
       (d->>'screen_min')::int                 AS screen_min,
       (d->>'pickups')::int                    AS pickups,
       (d->>'glances')::int                    AS glances,
       (d->>'calls_min')::int                  AS calls_min,
       (d->>'calls')::int                      AS calls,
       COALESCE((d->>'backfilled')::boolean, false) AS backfilled
FROM core.records r CROSS JOIN LATERAL (SELECT r.data AS d) x
WHERE r.kind = 'phone.day' AND NOT r.deleted;
COMMENT ON VIEW life.phone_days IS 'Телефон по суткам: экран, подъёмы, отвлечения короче двух минут, звонки. Это статистика телефона, а не записи ленты: время с делами ленты не складывается.';

CREATE VIEW life.phone_apps AS
SELECT (r.data->>'day')::date AS day,
       a->>'pkg'              AS pkg,
       a->>'label'            AS app,
       (a->>'min')::numeric   AS minutes,
       (a->>'sessions')::int  AS sessions,
       (a->>'glances')::int   AS glances
FROM core.records r, jsonb_array_elements(r.data->'apps') a
WHERE r.kind = 'phone.day' AND NOT r.deleted;
COMMENT ON VIEW life.phone_apps IS 'Приложения по суткам: минуты на экране, сессии от 5 секунд, отвлечения.';

CREATE VIEW life.phone_sites AS
SELECT (r.data->>'day')::date AS day, s->>'domain' AS domain, (s->>'min')::numeric AS minutes
FROM core.records r, jsonb_array_elements(r.data->'sites') s
WHERE r.kind = 'phone.day' AND NOT r.deleted;
COMMENT ON VIEW life.phone_sites IS 'Сайты в Chrome по суткам, минуты.';

CREATE VIEW life.phone_calls AS
SELECT (r.data->>'day')::date AS day, c->>'who' AS who, (c->>'min')::numeric AS minutes
FROM core.records r, jsonb_array_elements(r.data->'callers') c
WHERE r.kind = 'phone.day' AND NOT r.deleted;
COMMENT ON VIEW life.phone_calls IS 'Звонки от минуты по собеседникам за сутки.';

-- ---------------------------------------------------------------- Еда

CREATE VIEW life.meals AS
SELECT r.key                                  AS id,
       (d->>'day')::date                      AS day,
       (d->>'at')::timestamptz                AS at,
       life.hm(d->>'at')                      AS time_local,
       d->>'kind'                             AS kind,
       d->>'source'                           AS source,
       COALESCE((d->>'confirmed')::boolean, false) AS confirmed,
       NULLIF(d->>'raw', '')                  AS raw,
       NULLIF(d->>'note', '')                 AS note,
       NULLIF(d->>'photo', '')                AS photo,
       (d->'totals'->>'kcal')::numeric        AS kcal,
       (d->'totals'->>'protein_g')::numeric   AS protein_g,
       (d->'totals'->>'fat_g')::numeric       AS fat_g,
       (d->'totals'->>'carbs_g')::numeric     AS carbs_g,
       (d->'totals'->>'fiber_g')::numeric     AS fiber_g,
       (d->'totals'->>'grams')::numeric       AS grams,
       jsonb_array_length(COALESCE(d->'items', '[]'::jsonb)) AS items,
       CASE WHEN d->'items' @> '[{"sureness": "наугад"}]' THEN 'наугад'
            WHEN d->'items' @> '[{"sureness": "примерно"}]' THEN 'примерно'
            WHEN d->'items' @> '[{"sureness": "точно"}]' THEN 'точно' END AS confidence,
       NULLIF(d->>'model', '')                AS model
FROM core.records r CROSS JOIN LATERAL (SELECT r.data AS d) x
WHERE r.kind = 'food.meal' AND NOT r.deleted;
COMMENT ON VIEW life.meals IS 'Приёмы пищи. В счёт идут только confirmed. kind: завтрак, обед, ужин, перекус, добавки (горсть таблеток без еды). confidence — самая слабая позиция приёма: «наугад» значит, что числа прикидочные.';

CREATE VIEW life.meal_items AS
SELECT r.key                                  AS meal_id,
       (r.data->>'day')::date                 AS day,
       COALESCE((r.data->>'confirmed')::boolean, false) AS confirmed,
       i.n                                    AS n,
       i.v->>'name'                           AS name,
       (i.v->>'grams')::numeric               AS grams,
       (i.v->>'kcal')::numeric                AS kcal,
       (i.v->>'protein_g')::numeric           AS protein_g,
       (i.v->>'fat_g')::numeric               AS fat_g,
       (i.v->>'carbs_g')::numeric             AS carbs_g,
       (i.v->>'fiber_g')::numeric             AS fiber_g,
       NULLIF(i.v->>'sureness', '')           AS sureness,
       COALESCE((i.v->>'pill')::boolean, false) AS pill,
       COALESCE(i.v->'micro', '{}'::jsonb)    AS micro
FROM core.records r, jsonb_array_elements(r.data->'items') WITH ORDINALITY i(v, n)
WHERE r.kind = 'food.meal' AND NOT r.deleted;
COMMENT ON VIEW life.meal_items IS 'Позиции приёмов: числа на всю порцию. pill — таблетка: калорий нет, доза в micro. micro — вещества: ключ — id из life.norms.';

CREATE VIEW life.norms AS
SELECT s->>'id'                    AS substance,
       s->>'name'                  AS name,
       s->>'group'                 AS grp,
       s->>'unit'                  AS unit,
       (s->>'norm')::numeric       AS norm,
       (s->>'upper')::numeric      AS upper_limit,
       NULLIF(s->>'why', '')       AS why,
       NULLIF(s->>'where', '')     AS sources,
       (s->>'ord')::int            AS ord
FROM core.records r, jsonb_array_elements(r.data->'substances') s
WHERE r.kind = 'food.norms' AND r.key = 'all' AND NOT r.deleted;
COMMENT ON VIEW life.norms IS 'Справочник веществ: норма в день (мужчина 43 лет; у натрия это потолок, а не цель), верхний предел, зачем и где брать.';

CREATE VIEW life.micro AS
SELECT mi.meal_id, mi.day, mi.confirmed, mi.n AS item, mi.name AS item_name, mi.pill,
       m.key AS substance, m.value::numeric AS amount
FROM life.meal_items mi, jsonb_each_text(mi.micro) m;
COMMENT ON VIEW life.micro IS 'Вещества по позициям, длинным списком: substance — id из life.norms, amount — в единицах нормы.';

CREATE VIEW life.micro_days AS
SELECT m.day, m.substance, n.name, n.unit,
       round(sum(m.amount), 2)                                AS amount,
       round(sum(m.amount) FILTER (WHERE m.pill), 2)          AS from_pills,
       n.norm,
       round(100 * sum(m.amount) / NULLIF(n.norm, 0))         AS pct_of_norm,
       n.upper_limit
FROM life.micro m LEFT JOIN life.norms n USING (substance)
WHERE m.confirmed
GROUP BY m.day, m.substance, n.name, n.unit, n.norm, n.upper_limit;
COMMENT ON VIEW life.micro_days IS 'Вещества за сутки из подтверждённых приёмов: сколько, сколько из таблеток, процент нормы. Сутки без посчитанных веществ строкой не становятся: «не ел» и «не считали» — разное.';

-- ---------------------------------------------------------------- Силовые и зарядка

CREATE VIEW life.strength AS
SELECT r.key                                   AS id,
       (d->>'day')::date                       AS day,
       NULLIF(d->>'block', '')                 AS block,
       NULLIF(d->>'title', '')                 AS title,
       NULLIF((d->>'minutes')::int, 0)         AS minutes,
       NULLIF((d->>'feel')::int, 0)            AS feel,
       NULLIF((d->>'rpe')::int, 0)             AS rpe,
       COALESCE((d->>'done')::boolean, false)  AS done,
       NULLIF(d->>'note', '')                  AS note,
       NULLIF(d->>'icu_activity_id', '')       AS icu_activity_id,
       jsonb_array_length(COALESCE(d->'exercises', '[]'::jsonb)) AS exercises,
       jsonb_array_length(COALESCE(d->'checked', '[]'::jsonb))   AS checked
FROM core.records r CROSS JOIN LATERAL (SELECT r.data AS d) x
WHERE r.kind = 'strength.session' AND NOT r.deleted;
COMMENT ON VIEW life.strength IS 'Силовые сессии голосом или галочками. feel 1–5: 1 — отлично. checked — сколько пунктов схемы отмечено без чисел.';

CREATE VIEW life.strength_sets AS
SELECT r.key                                  AS session_id,
       (r.data->>'day')::date                 AS day,
       ex.v->>'exercise_id'                   AS exercise_id,
       ex.v->>'name'                          AS exercise,
       ex.v->>'unit'                          AS unit,
       s.n                                    AS n,
       (s.v->>'amount')::numeric              AS amount,
       NULLIF((s.v->>'weight_kg')::numeric, 0) AS weight_kg,
       NULLIF(s.v->>'note', '')               AS note
FROM core.records r,
     jsonb_array_elements(r.data->'exercises') ex(v),
     jsonb_array_elements(ex.v->'sets') WITH ORDINALITY s(v, n)
WHERE r.kind = 'strength.session' AND NOT r.deleted;
COMMENT ON VIEW life.strength_sets IS 'Подходы: amount — повторы или секунды/метры/минуты по unit (reps, sec, m, min), вес в кг.';

CREATE VIEW life.gtg AS
SELECT r.key::date                              AS day,
       d->>'status'                             AS status,
       COALESCE((d->>'charged')::boolean, false) AS charged,
       NULLIF((d->>'hang_s')::int, 0)           AS hang_s,
       NULLIF((d->>'negatives')::int, 0)        AS negatives,
       NULLIF((d->>'scapular')::int, 0)         AS scapular,
       NULLIF((d->>'pullups')::int, 0)          AS pullups,
       NULLIF(d->>'knee', '')                   AS knee,
       NULLIF((d->>'feel')::int, 0)             AS feel,
       NULLIF(d->>'note', '')                   AS note,
       COALESCE(d->'items', '[]'::jsonb)        AS items
FROM core.records r CROSS JOIN LATERAL (SELECT r.data AS d) x
WHERE r.kind = 'strength.gtg' AND NOT r.deleted;
COMMENT ON VIEW life.gtg IS 'Зарядка по дням (GTG): статус выполнена / частично / пропущена, вис, негативы, лопаточные, подтягивания, светофор колена.';

-- ---------------------------------------------------------------- Сырые надиктовки режимов

CREATE VIEW life.takes AS
SELECT split_part(r.kind, '.', 1)            AS domain,
       r.key                                 AS id,
       (d->>'day')::date                     AS day,
       (d->>'at')::timestamptz               AS at,
       life.hm(d->>'at')                     AS time_local,
       d->>'text'                            AS text,
       NULLIF(d->>'kind', '')                AS take_kind,
       NULLIF(d->>'source', '')              AS source,
       NULLIF(d->>'consumed_by', '')         AS consumed_by,
       NULLIF(d->>'error', '')               AS error
FROM core.records r CROSS JOIN LATERAL (SELECT r.data AS d) x
WHERE r.kind IN ('strength.take', 'money.take') AND NOT r.deleted;
COMMENT ON VIEW life.takes IS 'Что владелец наговорил Силовым и Деньгам дословно, до разбора. consumed_by — во что фраза превратилась.';

-- ---------------------------------------------------------------- Деньги

CREATE VIEW life.money_categories AS
SELECT c->>'key'                              AS key,
       c->>'title'                            AS title,
       c->>'group'                            AS grp,
       c->>'shelf'                            AS shelf,
       COALESCE((c->>'income')::boolean, false) AS income
FROM core.records r, jsonb_array_elements(r.data->'categories') c
WHERE r.kind = 'money.reference' AND r.key = 'all' AND NOT r.deleted;
COMMENT ON VIEW life.money_categories IS 'Категории денег. shelf: family — траты семьи, zf — деньги ЗФ, service — не трата (между своими, наличные, займы, пополнения). income — доход.';

CREATE VIEW life.money AS
SELECT r.key                                      AS id,
       d->>'owner'                                AS owner,
       (d->>'day')::date                          AS day,
       (d->>'at')::timestamptz                    AS at,
       CASE WHEN COALESCE((d->>'time_known')::boolean, true) THEN life.hm(d->>'at') END AS time_local,
       (d->>'amount_kop')::bigint                 AS amount_kop,
       round((d->>'amount_kop')::numeric / 100, 2) AS amount_rub,
       COALESCE(NULLIF(d->>'currency', ''), 'RUB') AS currency,
       (d->>'orig_minor')::bigint                 AS orig_minor,
       NULLIF(d->>'rub_basis', '')                AS rub_basis,
       NULLIF(d->>'what', '')                     AS what,
       NULLIF(d->>'note', '')                     AS note,
       NULLIF(d->>'mcc', '')                      AS mcc,
       NULLIF(d->>'bank_category', '')            AS bank_category,
       NULLIF(d->>'account', '')                  AS account,
       NULLIF(d->>'category', '')                 AS category,
       mc.title                                   AS category_title,
       mc.shelf                                   AS shelf,
       COALESCE(mc.income, false)                 AS income,
       NULLIF(d->>'for_whom', '')                 AS for_whom,
       NULLIF(d->>'category_by', '')              AS category_by,
       d->>'source'                               AS source,
       COALESCE((d->>'draft')::boolean, false)    AS draft,
       COALESCE((d->>'dropped')::boolean, false)  AS dropped,
       COALESCE((d->>'live')::boolean, false)     AS live,
       NULLIF(d->>'match_id', '')                 AS match_id,
       NULLIF(d->>'replaced_by', '')              AS replaced_by,
       NULLIF(d->>'take_id', '')                  AS take_id
FROM core.records r
CROSS JOIN LATERAL (SELECT r.data AS d) x
LEFT JOIN life.money_categories mc ON mc.key = d->>'category'
WHERE r.kind = 'money.entry' AND NOT r.deleted;
COMMENT ON VIEW life.money IS 'Деньги семьи: каждая операция из голоса, выписки, пуша банка или руками. Дубли не сливаются, а связываются: в счёт идёт только live (решает телефон). amount_kop со знаком: минус — ушло. owner — чей счёт или надиктовка (sasha, marianna). category_by: owner сильнее rule и model.';
COMMENT ON COLUMN life.money.live IS 'Идёт в счёт: не черновик, не вычеркнута, не заменена выпиской, голос без пары с банком.';
COMMENT ON COLUMN life.money.shelf IS 'family — траты семьи, zf — ЗФ, service — не трата.';

CREATE VIEW life.money_live AS
SELECT * FROM life.money WHERE live;
COMMENT ON VIEW life.money_live IS 'Только то, что идёт в счёт. Траты семьи — shelf = family и amount_kop < 0 и не income.';

CREATE VIEW life.money_rules AS
SELECT x->>'pattern' AS pattern, x->>'category' AS category, NULLIF(x->>'for_whom', '') AS for_whom,
       (x->>'sign')::int AS sign, NULLIF(x->>'owner', '') AS owner, NULLIF(x->>'comment', '') AS comment,
       NULLIF(x->>'source', '') AS bank, NULLIF(x->>'mcc', '') AS mcc, NULLIF((x->>'amount_kop')::bigint, 0) AS amount_kop
FROM core.records r, jsonb_array_elements(r.data->'rules') x
WHERE r.kind = 'money.reference' AND r.key = 'all' AND NOT r.deleted;
COMMENT ON VIEW life.money_rules IS 'Справочник получателей владельца: шаблон — категория.';

CREATE VIEW life.money_balances AS
SELECT x->>'account' AS account, (x->>'at')::timestamptz AS at, (x->>'kop')::bigint AS kop, x->>'source' AS source
FROM core.records r, jsonb_array_elements(r.data->'anchors') x
WHERE r.kind = 'money.reference' AND r.key = 'all' AND NOT r.deleted;
COMMENT ON VIEW life.money_balances IS 'Вписанные остатки счетов на момент.';

CREATE VIEW life.bank_pushes AS
SELECT r.key AS key, (d->>'at')::timestamptz AS at, (d->>'day')::date AS day, d->>'pkg' AS pkg,
       d->>'title' AS title, d->>'text' AS text, NULLIF(d->>'result', '') AS result
FROM core.records r CROSS JOIN LATERAL (SELECT r.data AS d) x
WHERE r.kind = 'money.push' AND NOT r.deleted;
COMMENT ON VIEW life.bank_pushes IS 'Сырые уведомления банков, из которых выведены операции.';

-- ---------------------------------------------------------------- Правка

CREATE VIEW life.dictations AS
SELECT r.key                              AS id,
       (d->>'day')::date                  AS day,
       (d->>'at')::timestamptz            AS at,
       life.hm(d->>'at')                  AS time_local,
       NULLIF(d->>'engine', '')           AS engine,
       (d->>'audio_ms')::int              AS audio_ms,
       d->>'text'                         AS text,
       COALESCE((d->>'ok')::boolean, true) AS ok,
       NULLIF(d->>'error', '')            AS error,
       NULLIF(d->>'audio', '')            AS audio,
       NULLIF(d->>'mic', '')              AS mic
FROM core.records r CROSS JOIN LATERAL (SELECT r.data AS d) x
WHERE r.kind = 'pravka.take' AND NOT r.deleted;
COMMENT ON VIEW life.dictations IS 'Каждая диктовка Правки: распознанный текст до чистки. Это всё, что владелец наговаривал в любые поля: сообщения людям, заметки, письма.';

CREATE VIEW life.cleanups AS
SELECT r.key                       AS id,
       (d->>'day')::date           AS day,
       (d->>'at')::timestamptz     AS at,
       NULLIF(d->>'mode', '')      AS mode,
       NULLIF(d->>'model', '')     AS model,
       d->>'input'                 AS input,
       d->>'output'                AS output,
       (d->>'latency_ms')::int     AS latency_ms,
       (d->>'cost_usd')::numeric   AS cost_usd,
       NULLIF(d->>'error', '')     AS error
FROM core.records r CROSS JOIN LATERAL (SELECT r.data AS d) x
WHERE r.kind = 'pravka.clean' AND NOT r.deleted;
COMMENT ON VIEW life.cleanups IS 'Чистка диктовки моделью: что пришло (input) и что модель вернула (output).';

CREATE VIEW life.corrections AS
SELECT r.key AS id, (d->>'day')::date AS day, (d->>'at')::timestamptz AS at,
       d->>'said' AS said, d->>'model' AS model_text, d->>'final' AS final, NULLIF(d->>'app', '') AS app,
       NULLIF(d->>'result', '') AS result
FROM core.records r CROSS JOIN LATERAL (SELECT r.data AS d) x
WHERE r.kind = 'pravka.correction' AND NOT r.deleted;
COMMENT ON VIEW life.corrections IS 'Правки владельца после модели: надиктовано — что вернула модель — что осталось в поле.';

-- ---------------------------------------------------------------- Спорт: intervals как есть

CREATE VIEW life.workouts AS
SELECT d->>'id'                                              AS id,
       left(d->>'start_date_local', 10)::date                AS day,
       (d->>'start_date')::timestamptz                       AS start_at,
       substring(d->>'start_date_local' from 12 for 5)       AS start_local,
       d->>'type'                                            AS type,
       d->>'name'                                            AS name,
       round((d->>'elapsed_time')::numeric / 60, 1)          AS minutes,
       round((d->>'moving_time')::numeric / 60, 1)           AS moving_min,
       round((d->>'distance')::numeric / 1000, 2)            AS km,
       CASE WHEN (d->>'average_speed')::numeric > 0 THEN round(1000 / (d->>'average_speed')::numeric) END AS pace_s_km,
       CASE WHEN (d->>'gap')::numeric > 0 THEN round(1000 / (d->>'gap')::numeric) END AS gap_s_km,
       (d->>'average_heartrate')::numeric                    AS hr,
       (d->>'max_heartrate')::numeric                        AS hr_max,
       (d->>'icu_average_watts')::numeric                    AS watts,
       (d->>'icu_weighted_avg_watts')::numeric               AS np,
       CASE WHEN d->>'type' IN ('Run', 'TrailRun', 'VirtualRun', 'Walk', 'Hike')
                 AND (d->>'average_cadence')::numeric BETWEEN 1 AND 130
            THEN (d->>'average_cadence')::numeric * 2
            ELSE (d->>'average_cadence')::numeric END        AS cadence,
       (d->>'total_elevation_gain')::numeric                 AS elev_m,
       (d->>'icu_training_load')::numeric                    AS load,
       (d->>'icu_intensity')::numeric                        AS intensity,
       (d->>'decoupling')::numeric                           AS decoupling,
       (d->>'icu_efficiency_factor')::numeric                AS ef,
       (d->>'calories')::numeric                             AS kcal,
       (d->>'feel')::int                                     AS feel,
       (d->>'icu_rpe')::int                                  AS rpe,
       d->'icu_hr_zone_times'                                AS hr_zone_s,
       NULLIF(d->>'description', '')                         AS description,
       'https://intervals.icu/activities/' || (d->>'id')     AS url,
       d                                                     AS raw
FROM core.records r CROSS JOIN LATERAL (SELECT r.data AS d) x
WHERE r.kind = 'icu.activity' AND NOT r.deleted;
COMMENT ON VIEW life.workouts IS 'Тренировки из intervals.icu как они там лежат. pace — с/км, cadence у бега и ходьбы — шаги в минуту, у вело — обороты. feel 1–5: 1 — отлично, rpe 1–10. raw — вся карточка intervals: любое поле, которого нет колонкой, — raw->>''поле''.';

CREATE VIEW life.workout_streams AS
SELECT r.key AS workout_id, s->>'type' AS type, s->'data' AS data
FROM core.records r, jsonb_array_elements(r.data->'streams') s
WHERE r.kind = 'icu.streams' AND NOT r.deleted;
COMMENT ON VIEW life.workout_streams IS 'Посекундные потоки тренировки: type — time, heartrate, watts, cadence, velocity_smooth, altitude, distance, latlng…; data — массив значений. Разворачивать через jsonb_array_elements_text(data) WITH ORDINALITY.';

CREATE VIEW life.wellness AS
SELECT r.key::date                                AS day,
       (d->>'restingHR')::numeric                 AS rhr,
       (d->>'hrv')::numeric                       AS hrv,
       round((d->>'sleepSecs')::numeric / 3600, 2) AS sleep_h,
       (d->>'sleepScore')::numeric                AS sleep_score,
       (d->>'sleepQuality')::int                  AS sleep_quality,
       (d->>'steps')::int                         AS steps,
       (d->>'weight')::numeric                    AS weight_kg,
       (d->>'bodyFat')::numeric                   AS body_fat_pct,
       (d->>'vo2max')::numeric                    AS vo2max,
       (d->>'ctl')::numeric                       AS ctl,
       (d->>'atl')::numeric                       AS atl,
       (d->>'ctl')::numeric - (d->>'atl')::numeric AS tsb,
       (d->>'readiness')::numeric                 AS readiness,
       (d->>'kcalConsumed')::numeric              AS kcal_eaten,
       (d->>'protein')::numeric                   AS protein_eaten_g,
       (d->>'soreness')::int                      AS soreness,
       (d->>'fatigue')::int                       AS fatigue,
       (d->>'stress')::int                        AS stress,
       (d->>'mood')::int                          AS mood,
       NULLIF(d->>'comments', '')                 AS comment,
       d                                          AS raw
FROM core.records r CROSS JOIN LATERAL (SELECT r.data AS d) x
WHERE r.kind = 'icu.wellness' AND NOT r.deleted
  AND r.key <= to_char(now(), 'YYYY-MM-DD');
COMMENT ON VIEW life.wellness IS 'Сутки здоровья из intervals.icu (часы и весы): пульс покоя, HRV, сон, шаги, вес, CTL/ATL/TSB. kcal_eaten — это наш же итог еды, ушедший туда из Правки. Завтрашний прогноз CTL/ATL строкой не становится. raw — всё, что прислал intervals.';

CREATE VIEW life.plan AS
SELECT d->>'id'                                         AS id,
       left(d->>'start_date_local', 10)::date           AS day,
       d->>'category'                                   AS category,
       NULLIF(d->>'type', '')                           AS type,
       d->>'name'                                       AS name,
       NULLIF(d->>'description', '')                    AS description,
       round((d->>'moving_time')::numeric / 60)         AS planned_min,
       (d->>'icu_training_load')::numeric               AS planned_load,
       d                                                AS raw
FROM core.records r CROSS JOIN LATERAL (SELECT r.data AS d) x
WHERE r.kind = 'icu.event' AND NOT r.deleted;
COMMENT ON VIEW life.plan IS 'Календарь intervals: запланированные тренировки (category WORKOUT), заметки (NOTE) и прочее.';

CREATE VIEW life.coach AS
SELECT r.key AS id, (d->>'day')::date AS day, (d->>'at')::timestamptz AS at,
       d->>'question' AS question, d->>'answer' AS answer, (d->>'cost_usd')::numeric AS cost_usd, NULLIF(d->>'error', '') AS error
FROM core.records r CROSS JOIN LATERAL (SELECT r.data AS d) x
WHERE r.kind = 'sport.talk' AND NOT r.deleted;
COMMENT ON VIEW life.coach IS 'Разговоры с тренером в приложении: вопрос владельца и ответ модели.';

-- ---------------------------------------------------------------- Сказанное: один поиск на всё

CREATE VIEW core.said_source AS
SELECT r.kind, r.key, 'raw:' || (e->>'id') AS part, 'засечка' AS domain,
       (r.data->>'day')::date AS day, (e->>'start')::timestamptz AS at, life.hm(e->>'start') AS time_local,
       't' || (e->>'id') AS ref, split_part(e->>'raw', E'\nКБЖУ:', 1) AS text
FROM core.records r, jsonb_array_elements(r.data->'entries') e
WHERE r.kind = 'zasechka.day' AND NOT r.deleted AND COALESCE(e->>'raw', '') <> ''
UNION ALL
SELECT r.kind, r.key, 'comment:' || (e->>'id'), 'засечка', (r.data->>'day')::date, (e->>'start')::timestamptz,
       life.hm(e->>'start'), 't' || (e->>'id'), e->>'comment'
FROM core.records r, jsonb_array_elements(r.data->'entries') e
WHERE r.kind = 'zasechka.day' AND NOT r.deleted AND COALESCE(e->>'comment', '') <> ''
UNION ALL
SELECT r.kind, r.key, 'raw', 'еда', (r.data->>'day')::date, (r.data->>'at')::timestamptz, life.hm(r.data->>'at'),
       r.key, r.data->>'raw'
FROM core.records r
WHERE r.kind = 'food.meal' AND NOT r.deleted AND COALESCE(r.data->>'raw', '') <> ''
UNION ALL
SELECT r.kind, r.key, 'text', CASE r.kind WHEN 'money.take' THEN 'деньги' ELSE 'силовые' END,
       (r.data->>'day')::date, (r.data->>'at')::timestamptz, life.hm(r.data->>'at'), r.key, r.data->>'text'
FROM core.records r
WHERE r.kind IN ('strength.take', 'money.take') AND NOT r.deleted AND COALESCE(r.data->>'text', '') <> ''
UNION ALL
SELECT r.kind, r.key, 'note', 'силовые', COALESCE((r.data->>'day')::date, CASE WHEN r.kind = 'strength.gtg' THEN r.key::date END),
       NULL::timestamptz, NULL, r.key, r.data->>'note'
FROM core.records r
WHERE r.kind IN ('strength.session', 'strength.gtg') AND NOT r.deleted AND COALESCE(r.data->>'note', '') <> ''
UNION ALL
SELECT r.kind, r.key, 'text', 'правка', (r.data->>'day')::date, (r.data->>'at')::timestamptz, life.hm(r.data->>'at'),
       r.key, r.data->>'text'
FROM core.records r
WHERE r.kind = 'pravka.take' AND NOT r.deleted AND COALESCE(r.data->>'text', '') <> ''
UNION ALL
SELECT r.kind, r.key, 'question', 'тренер', (r.data->>'day')::date, (r.data->>'at')::timestamptz, life.hm(r.data->>'at'),
       r.key, r.data->>'question'
FROM core.records r
WHERE r.kind = 'sport.talk' AND NOT r.deleted AND COALESCE(r.data->>'question', '') <> ''
UNION ALL
SELECT r.kind, r.key, 'answer', 'тренер', (r.data->>'day')::date, (r.data->>'at')::timestamptz, life.hm(r.data->>'at'),
       r.key, r.data->>'answer'
FROM core.records r
WHERE r.kind = 'sport.talk' AND NOT r.deleted AND COALESCE(r.data->>'answer', '') <> ''
UNION ALL
SELECT r.kind, r.key, 'comment', 'форма', r.key::date, NULL, NULL, r.key, r.data->>'comments'
FROM core.records r
WHERE r.kind = 'icu.wellness' AND NOT r.deleted AND COALESCE(r.data->>'comments', '') <> ''
UNION ALL
SELECT r.kind, r.key, 'description', 'тренировки', left(r.data->>'start_date_local', 10)::date,
       (r.data->>'start_date')::timestamptz, substring(r.data->>'start_date_local' from 12 for 5), r.key, r.data->>'description'
FROM core.records r
WHERE r.kind = 'icu.activity' AND NOT r.deleted AND COALESCE(r.data->>'description', '') <> '';

CREATE VIEW life.said AS
SELECT domain, day, time_local, at, ref, part, text, tsv, tsv_words
FROM core.said_index;
COMMENT ON VIEW life.said IS 'Всё сказанное и написанное владельцем во всех режимах — для поиска. Проще всего — инструментом search. Руками: tsv @@ websearch_to_tsquery(''russian'', …) — словоформы обычных слов; имена стеммер режет по-разному, для них tsv_words @@ to_tsquery(''simple'', ''марианн:*'') — начало слова (ё как е); text ILIKE ''%…%'' — подстрока. domain: засечка, еда, силовые, деньги, правка, тренер, форма, тренировки. ref — номер записи в своём виде (у ленты — t<id>).';

-- ---------------------------------------------------------------- Сутки целиком

CREATE VIEW life.days AS
WITH d AS (
    SELECT day FROM life.entries
    UNION SELECT day FROM life.meals WHERE confirmed
    UNION SELECT day FROM life.wellness
    UNION SELECT day FROM life.workouts
    UNION SELECT day FROM life.money_live
    UNION SELECT day FROM life.phone_days
    UNION SELECT day FROM life.strength
    UNION SELECT day FROM life.gtg
)
SELECT d.day,
       (ARRAY['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс'])[extract(isodow FROM d.day)::int] AS dow,
       e.ribbon_min, e.points, e.minutes_by_category,
       m.meals, m.kcal, m.protein_g, m.fat_g, m.carbs_g, m.fiber_g,
       w.workouts, w.workout_min, w.km, w.load,
       h.sleep_h, h.hrv, h.rhr, h.weight_kg, h.ctl, h.atl, h.tsb, h.steps,
       s.strength_done, g.gtg_status,
       mo.family_spent_rub, mo.operations,
       p.screen_min, p.pickups
FROM d
LEFT JOIN LATERAL (
    SELECT sum(minutes) AS ribbon_min, round(sum(points), 1) AS points,
           (SELECT jsonb_object_agg(category, mins)
              FROM (SELECT category, sum(minutes) AS mins FROM life.entries e2
                     WHERE e2.day = d.day GROUP BY category) c) AS minutes_by_category
    FROM life.entries e1 WHERE e1.day = d.day
) e ON true
LEFT JOIN LATERAL (
    SELECT count(*) AS meals, round(sum(kcal)) AS kcal, round(sum(protein_g)) AS protein_g,
           round(sum(fat_g)) AS fat_g, round(sum(carbs_g)) AS carbs_g, round(sum(fiber_g)) AS fiber_g
    FROM life.meals WHERE day = d.day AND confirmed
) m ON true
LEFT JOIN LATERAL (
    SELECT count(*) AS workouts, round(sum(minutes)) AS workout_min, round(sum(km), 1) AS km, round(sum(load)) AS load
    FROM life.workouts WHERE day = d.day
) w ON true
LEFT JOIN life.wellness h ON h.day = d.day
LEFT JOIN LATERAL (SELECT bool_or(done) AS strength_done FROM life.strength WHERE day = d.day) s ON true
LEFT JOIN LATERAL (SELECT status AS gtg_status FROM life.gtg WHERE day = d.day) g ON true
LEFT JOIN LATERAL (
    SELECT round(-sum(amount_kop) FILTER (WHERE amount_kop < 0 AND shelf = 'family' AND NOT income)::numeric / 100) AS family_spent_rub,
           count(*) AS operations
    FROM life.money_live WHERE day = d.day
) mo ON true
LEFT JOIN life.phone_days p ON p.day = d.day;
COMMENT ON VIEW life.days IS 'Одна строка на сутки: лента (минуты, очки, минуты по категориям), еда (подтверждённое), тренировки, сон и форма, силовые и зарядка, траты семьи в рублях, экран. Полные сутки ленты — ribbon_min = 1440.';

-- ---------------------------------------------------------------- Свежесть

CREATE VIEW life.freshness AS
SELECT source, last_ok, last_error, last_error_at, note
FROM core.sources;
COMMENT ON VIEW life.freshness IS 'Когда каждый источник (phone:<устройство>, intervals) последний раз прислал данные и чем кончилась последняя ошибка. Старше часа у телефона — он, может быть, без сети; ответ про сегодня тогда неполный.';

-- ---------------------------------------------------------------- Всё остальное и история

CREATE VIEW life.raw AS
SELECT kind, key, data, updated_at FROM core.records WHERE NOT deleted;
COMMENT ON VIEW life.raw IS 'Любая запись архива как она пришла (JSON): то, для чего ещё нет отдельного вида (icu.athlete — пороги и зоны, icu.file — исходные файлы тренировок, …), читается здесь: data->>''поле''.';

CREATE VIEW life.history AS
SELECT kind, key, op, at, device, data FROM core.events;
COMMENT ON VIEW life.history IS 'Журнал изменений: каждая версия каждой записи. Как было до правки — здесь: WHERE kind = ''…'' AND key = ''…'' ORDER BY at. at у телефона — когда он заметил изменение (до 10 секунд после правки), а не время самой записи; всё, что было до подключения архива (01.10.2026), пришло одной заливкой с одним at. Время дела, приёма, траты — в полях записи.';
