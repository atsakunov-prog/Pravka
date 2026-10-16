-- Дела, миграция 35 (16.10.2026, этап 6 аудита; слово Саши «да. Делай» на раздел 10 prompts.docx): звонки во входе
-- разноски дайджеста.
--
-- Новый текст разноски сверяет «позвонить …» со звонками: блок ЗВОНКИ во входе (кто, день, минуты за окно и неделю до
-- него), а «Перезвонить Виталию» после звонка с Виталием закрывает ещё и код (svodka.call_closes службы дайджеста: в
-- прогоне модель сама закрыла такое дело в 6 окнах из 9). Звонки телефон кладёт в архив (life.phone_calls — по суткам и
-- собеседникам), а роль службы Дел архив не видит (правило 2): единственная дверь — crm.life_rows, и новый вид архива —
-- новая ветвь в её теле новой миграцией (правило 22), а не право на схему. Тело — от dela_0016 слово в слово плюс ветвь
-- calls; права прежние (CREATE OR REPLACE их не трогает, PUBLIC снят ниже ещё раз). Вид Дел — calls?since&until
-- (twins.py, флаг calls), только владельцу.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

CREATE OR REPLACE FUNCTION crm.life_rows(what text, d1 date, d2 date) RETURNS SETOF jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = crm, pg_temp AS $$
DECLARE
    q text;
BEGIN
    IF crm.me() IS DISTINCT FROM crm.owner_id() THEN
        RAISE EXCEPTION 'Дела: архив жизни — только владельцу' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF d1 IS NULL OR d2 IS NULL OR d2 < d1 OR d2 - d1 > 62 THEN
        RAISE EXCEPTION 'Дела: архив читается не больше чем за 62 суток (% … %)', d1, d2;
    END IF;
    IF to_regclass('life.entries') IS NULL THEN
        RETURN;
    END IF;
    q := CASE what
        -- Лента Засечки: местное время — из строки телефона (start_local), сутки — его же.
        WHEN 'entries' THEN
            'SELECT id, day, start_at, end_at, start_local, end_local, minutes, title, category, client, source, '
            'left(raw, 300) AS raw, comment, task_id, project_id, open, value_per_hour, points FROM life.entries '
            'WHERE day BETWEEN $1 AND $2 ORDER BY day, start_at, id'
        WHEN 'categories' THEN
            'SELECT name, value_per_hour, hint, base_min, ord FROM life.categories ORDER BY ord NULLS LAST, name'
        -- Еда: и неподтверждённое — на ленте оно «записать?», в счёт — только confirmed.
        WHEN 'meals' THEN
            'SELECT id, day, at, time_local, kind, confirmed, kcal, protein_g, fat_g, carbs_g, items, confidence '
            'FROM life.meals WHERE day BETWEEN $1 AND $2 ORDER BY at'
        WHEN 'workouts' THEN
            'SELECT id, day, start_at, start_local, type, name, minutes, km, hr, load FROM life.workouts '
            'WHERE day BETWEEN $1 AND $2 ORDER BY start_at'
        WHEN 'wellness' THEN
            'SELECT day, sleep_h, sleep_score, hrv, rhr, ctl, atl, tsb, steps FROM life.wellness '
            'WHERE day BETWEEN $1 AND $2 ORDER BY day'
        WHEN 'plan' THEN
            'SELECT id, day, category, type, name, planned_min, raw->>''start_date_local'' AS start FROM life.plan '
            'WHERE day BETWEEN $1 AND $2 AND category = ''WORKOUT'' ORDER BY 7, id'
        WHEN 'gtg' THEN
            'SELECT day, status, charged FROM life.gtg WHERE day BETWEEN $1 AND $2'
        -- Деньги: только то, что идёт в счёт (решает телефон); траты семьи считает страница: shelf = family, минус.
        WHEN 'money' THEN
            'SELECT id, owner, day, at, time_local, amount_kop, what, note, category_title, shelf, income '
            'FROM life.money_live WHERE day BETWEEN $1 AND $2 ORDER BY at'
        WHEN 'phone' THEN
            'SELECT day, screen_min, pickups, calls_min, calls FROM life.phone_days WHERE day BETWEEN $1 AND $2'
        -- Звонки по собеседникам за сутки (dela_0035, 16.10.2026): вход разноски дайджеста (ЗВОНКИ) — с кем Саша говорил.
        WHEN 'calls' THEN
            'SELECT day, who, minutes FROM life.phone_calls WHERE day BETWEEN $1 AND $2 ORDER BY day, who'
        -- Диктовки телефона и компа (экран «Правка»): тейк и чистка; склейку делает служба (twins.pair).
        WHEN 'dictations' THEN
            'SELECT id, day, at, time_local, engine, audio_ms, text, ok, error, mic, origin, status, final '
            'FROM life.dictations WHERE day BETWEEN $1 AND $2 ORDER BY at'
        WHEN 'cleanups' THEN
            'SELECT id, day, at, mode, model, input, output, latency_ms, cost_usd, error, origin, take_id, style '
            'FROM life.cleanups WHERE day BETWEEN $1 AND $2 ORDER BY at'
        -- Когда телефон последний раз присылал: лента в вебе свежа настолько, насколько свеж архив.
        WHEN 'freshness' THEN
            'SELECT source, last_ok, last_error_at FROM life.freshness WHERE source LIKE ''phone:%'' OR source = ''intervals'''
    END;
    IF q IS NULL THEN
        RAISE EXCEPTION 'Дела: такого чтения архива нет: %', what;
    END IF;
    BEGIN
        RETURN QUERY EXECUTE 'SELECT to_jsonb(x) FROM (' || q || ') x' USING d1, d2;
    EXCEPTION WHEN undefined_table OR undefined_column THEN
        -- Архив старше страниц (нет вида или колонки): словами, а не пустотой — иначе «Правка» молча пустая.
        RAISE EXCEPTION 'Дела: архив старый — нет вида или колонки для «%» (обнови архив установщиком)', what;
    END;
END $$;

COMMENT ON FUNCTION crm.life_rows(text, date, date) IS
    'Архив жизни для страниц-двойников веба Дел (Сегодня, Засечка, Правка) и звонков разноски дайджеста: только владельцу, только список видов и колонок в теле, не длиннее 62 суток.';

REVOKE ALL ON FUNCTION crm.life_rows(text, date, date) FROM PUBLIC;
