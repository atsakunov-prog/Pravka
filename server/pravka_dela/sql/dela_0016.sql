-- Дела, миграция 16 (14.10.2026): веб-двойники экранов телефона «Правка 5.0» — «Сегодня», «Засечка», «Правка».
--
-- Владелец: телефон и веб — «две копии одного и того же»: на компьютере — та же Засечка, тот же день, те же
-- диктовки, одна база на сервере. Для этого вебу Дел нужны две вещи, которых у Дел не было.
--
-- 1. Чтение архива — узко. Роль службы Дел (dela_app) сознательно не видит ни core, ни life (правило 2 Дел): служба
--    смотрит в интернет. Своей роли с DSN, как у «Спорта» и «Денег» (они читают ролью pravka_reader всю схему life),
--    здесь нет — вместо неё одна функция crm.life_rows, как crm.time_* (правило 13): SECURITY DEFINER, отвечает только
--    владельцу Дел, и только из списка в своём теле — определённые колонки определённых видов life за даты. Так
--    у службы нет ни пароля второй роли, ни права на всю схему: взломанный веб прочтёт ровно то, что и так покажет
--    страница владельца, а не всю жизнь (сон, пульс, переписку журнала службы телефона). Тело — plpgsql и EXECUTE:
--    архив пересобирает life целиком на каждом своём migrate, ссылка в теле не должна её держать. Архива в базе нет
--    (старый сервер, тестовая база Дел без архива) — пусто; вызывать — только роли службы (PUBLIC снят, право даёт
--    db.grant_app).
--
-- 2. Команды Засечки. Правда ленты — телефон (ZasechkaStore, docs/agreements.md «Лента — незаменимые данные»), архив —
--    её копия: веб не пишет день в архив сам — следующая отправка телефона перезатёрла бы его правку, а его правка —
--    телефонную. Поэтому «Начать», «Стоп», «Поправить» из веба — команда в crm.zasechka_cmds: телефон забирает её
--    синком Дел (поле zasechka), берёт (take — одна команда одному устройству), применяет тем же путём, что кнопки и
--    голос, отмечает сделанной (done) и отправляет день в архив как обычно. Пока команда не взята, веб пишет «ждёт
--    телефона». Видит и пишет только владелец (роль owner): лента — его. Журнала (crm.history) нет — как у дайджеста:
--    названия дел ленты — личное, и копиться в общем журнале, который читают карточки, им незачем; сама строка
--    команды хранит всё (кто взял, когда, что вышло) и не удаляется (правило 8).

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

-- ── 1. Чтение архива ────────────────────────────────────────────────────────

-- Есть ли в базе архив (виды life) — страница говорит «нет доступа к архиву» словами, а не падает.
CREATE FUNCTION crm.life_ok() RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT crm.me() IS NOT DISTINCT FROM crm.owner_id()
       AND to_regclass('life.entries') IS NOT NULL AND to_regclass('life.dictations') IS NOT NULL
$$;

-- Строки архива для страниц-двойников: what — какой вид, d1…d2 — сутки (поле day, его решил телефон), не длиннее
-- 62 суток за раз. Каждая строка — jsonb с колонками из списка ниже (новая колонка — CREATE OR REPLACE в новой
-- миграции). Не владельцу — отказ (не пустота): прямой запрос команды должен упасть громко.
CREATE FUNCTION crm.life_rows(what text, d1 date, d2 date) RETURNS SETOF jsonb
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
    'Архив жизни для страниц-двойников веба Дел (Сегодня, Засечка, Правка): только владельцу, только список видов и колонок в теле, не длиннее 62 суток.';

-- Вызывать — только роли службы Дел (её имя знает db.grant_app); остальным право снято.
REVOKE ALL ON FUNCTION crm.life_rows(text, date, date) FROM PUBLIC;
REVOKE ALL ON FUNCTION crm.life_ok() FROM PUBLIC;

-- ── 2. Команды Засечки ──────────────────────────────────────────────────────

CREATE TABLE crm.zasechka_cmds (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    text NOT NULL REFERENCES crm.users,
    kind        text NOT NULL CHECK (kind IN ('start', 'stop', 'edit')),
    args        jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(args) = 'object'),
    said_at     timestamptz NOT NULL DEFAULT now(),
    via         text,
    status      text NOT NULL DEFAULT 'wait' CHECK (status IN ('wait', 'taken', 'done', 'refused', 'expired', 'cancelled')),
    taken_by    text CHECK (taken_by IS NULL OR (btrim(taken_by) <> '' AND length(taken_by) <= 80)),
    taken_at    timestamptz,
    done_at     timestamptz,
    note        text CHECK (note IS NULL OR length(note) <= 500),
    entry_id    bigint,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    rev         integer NOT NULL DEFAULT 1,
    seq         bigint NOT NULL DEFAULT 0,
    -- Взятая и сделанная — всегда чьим-то устройством: так видно, какой телефон её применил.
    CHECK (status NOT IN ('taken', 'done', 'refused') OR taken_by IS NOT NULL)
);
COMMENT ON TABLE crm.zasechka_cmds IS 'Команды Засечки из веба Дел телефону (14.10.2026): начать, остановить, поправить. Правда ленты — телефон: он берёт команду синком, применяет и шлёт день в архив. Только владельцу.';
COMMENT ON COLUMN crm.zasechka_cmds.args IS 'start: {title, category, client?, task_id?, project_id?}; stop: {entry_id} — запись, которую веб видел идущей; edit: {entry_id, day, was: {title, category, start, end}, set: {title?, category?, start?, end?}} — время «ЧЧ:ММ» местное, суток записи.';
COMMENT ON COLUMN crm.zasechka_cmds.said_at IS 'Миг нажатия в вебе — время дела: «Начать» в 10:00, взятое телефоном в 10:04, начинает дело с 10:00.';
COMMENT ON COLUMN crm.zasechka_cmds.status IS 'wait — ждёт телефона; taken — телефон взял (taken_by); done — сделано; refused — телефон не стал (note — почему); expired — не взята за 12 часов; cancelled — снята в вебе.';

CREATE INDEX zasechka_cmds_open ON crm.zasechka_cmds (owner_id, said_at) WHERE status IN ('wait', 'taken');

-- Только владельцу Дел: лента — его (как дайджест, dela_0014).
CREATE FUNCTION crm.zasechka_rules() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM crm.users u WHERE u.id = NEW.owner_id AND u.role = 'owner') THEN
        RAISE EXCEPTION 'Дела: Засечка — только владельцу Дел, а % не владелец', NEW.owner_id;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.kind <> OLD.kind OR NEW.args <> OLD.args OR NEW.said_at <> OLD.said_at
                             OR NEW.owner_id <> OLD.owner_id) THEN
        RAISE EXCEPTION 'Дела: команду Засечки не переписывают — сними её и поставь новую';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER rules BEFORE INSERT OR UPDATE ON crm.zasechka_cmds FOR EACH ROW EXECUTE FUNCTION crm.zasechka_rules();
CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON crm.zasechka_cmds FOR EACH ROW EXECUTE FUNCTION crm.stamp();

ALTER TABLE crm.zasechka_cmds ENABLE ROW LEVEL SECURITY;
CREATE POLICY own ON crm.zasechka_cmds FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));
