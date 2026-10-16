-- Дела, миграция 32 (15.10.2026): захват — «сказал → легло → вернётся» (шаг 0 петли этапа 4).
--
-- Владелец 15.10.2026: «Приоритет номер один… захват за три секунды, которому я доверяю. Сегодня я хотел „напомни в 12 в
-- Контуре отправить счёт“ и не знал, куда: в календарь нельзя — Засечка примет за встречу; в Дела — утонет среди
-- остального. Сказал Алисе». Договор: одна дверь («сказать», «Д», «З», строка веба) — одна операция capture.say;
-- раскладывает сервер (capture.py) по корзинам — напоминание, «Сейчас», «Сегодня», на день, «потом», заметка, идея,
-- сделано — и сразу говорит одной строкой, куда легло и когда вернётся.
--
-- Что здесь:
-- • tasks.tasks.kind — 'task' | 'remind'. Напоминание — вид дела, а не новая таблица: синк, очередь, журнал, RLS,
--   remind.due, кнопки бота и приезд телефона работают как есть; в списках дел его нет (store._list — kind = 'task').
-- • back_on / back_said / back_n — дело «потом»: день, когда оно вернётся строкой «Вернулось» на «Сегодня», как это было
--   сказано («на неделе») и сколько раз его откладывали само (ночью не тронутое уходит на понедельник).
-- • reminded_via — кто показал напоминание: бот в Telegram или уведомление телефона (с 15.10 бот — запасной канал).
-- • tasks.dictations.capture… — что захват сделал с наговоркой: правилом, ждёт модели, разложено моделью, сбой.
-- • crm.ideas — идеи у клиента, человека, сделки (ответ 5а: своя маленькая сущность). Идеи — мысли владельца: видит
--   только хозяин (как сводки дайджеста), команда не видит.
-- • crm.person_merge — переносит и crm.ideas.person_id (people.REFS); тело — от dela_0027 (вагон 1 поезда: и
--   «Только тебе»), а не от dela_0023, как было в ветке: иначе захват молча снял бы перенос наблюдений.
-- • tasks.v_tasks — пересобран от ПОСЛЕДНЕЙ сборки (dela_0030: pos через t.*, why_goal; правило 26 карты) — с kind,
--   back_on, back_said через t.*; steps_open — только дела (напоминание внутри не держит родителя открытым).
-- • tasks.nest_rise / nest_fall (dela_0030, правило 27) — только по делам: сделанное или вернувшееся напоминание
--   родителя не поднимает и срок ему не возвращает, открытое напоминание внутри подъёму не мешает.
--
-- Обратимо по смыслу: новые колонки с умолчаниями и новая таблица, лежащие строки не трогаются (старые дела — kind
-- 'task'). В ветке capture файл звался dela_0024c.sql (чтобы не столкнуться с шагом 1 петли); номер — следующий
-- свободный в сборке поезда 16.10.2026, после dela_0031 («Режим отладки»): 24–29 — «Приватность», 30 — дела внутри дела.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

-- ── Дело: вид, «потом», кто показал напоминание ─────────────────────────

ALTER TABLE tasks.tasks
    ADD COLUMN kind         text NOT NULL DEFAULT 'task' CHECK (kind IN ('task', 'remind')),
    ADD COLUMN back_on      date,
    ADD COLUMN back_said    text CHECK (back_said IS NULL OR (btrim(back_said) <> '' AND length(back_said) <= 40)),
    ADD COLUMN back_n       smallint NOT NULL DEFAULT 0 CHECK (back_n >= 0),
    ADD COLUMN reminded_via text CHECK (reminded_via IN ('bot', 'phone'));
COMMENT ON COLUMN tasks.tasks.kind IS 'task — дело; remind — напоминание (захват 15.10.2026): «напомни в 12 …» без своего срока. В списках дел его нет — стоит на «Сегодня» в свой час и приходит уведомлением телефона (бот — запасной).';
COMMENT ON COLUMN tasks.tasks.back_on IS 'Дело «потом»: в этот день вернётся строкой «Вернулось» на «Сегодня». Не тронул за день — ночью уходит на ближайший понедельник (back_n + 1).';
COMMENT ON COLUMN tasks.tasks.back_said IS 'Как сказано «когда вернуться»: «на неделе», «потом», «в январе» — для строки «ты сказал …».';
COMMENT ON COLUMN tasks.tasks.back_n IS 'Сколько раз «Вернулось» уходило дальше само, не тронутым.';
COMMENT ON COLUMN tasks.tasks.reminded_via IS 'Кто показал напоминание: phone — уведомление телефона, bot — бот Ковчега в Telegram (запасной: шлёт, если телефон за 10 минут не отметил показ — remind.PHONE_GRACE_MIN).';

CREATE INDEX tasks_back ON tasks.tasks (owner_id, back_on) WHERE status = 'open' AND back_on IS NOT NULL;
CREATE INDEX tasks_kind_remind ON tasks.tasks (owner_id, remind_at) WHERE status = 'open' AND kind = 'remind';

-- Новое время или место — напоминание снова в силе (dela_0003); теперь заодно забываем, кто показывал прежнее.
CREATE OR REPLACE FUNCTION tasks.remind_rules() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.remind_at IS DISTINCT FROM OLD.remind_at OR NEW.remind_place IS DISTINCT FROM OLD.remind_place)
       AND NEW.reminded_at IS NOT DISTINCT FROM OLD.reminded_at THEN
        NEW.reminded_at := NULL;
        NEW.reminded_via := NULL;
    END IF;
    RETURN NEW;
END $$;

-- ── Наговорка: что с ней сделал захват ──────────────────────────────────

ALTER TABLE tasks.dictations
    ADD COLUMN capture        text CHECK (capture IN ('rule', 'pending', 'model', 'error')),
    ADD COLUMN capture_result jsonb CHECK (capture_result IS NULL OR jsonb_typeof(capture_result) = 'object'),
    ADD COLUMN capture_from   text CHECK (capture_from IS NULL OR length(capture_from) <= 20),
    ADD COLUMN capture_hint   text CHECK (capture_hint IS NULL OR length(capture_hint) <= 4000);
COMMENT ON COLUMN tasks.dictations.capture IS 'Захват (capture.py): rule — разложено правилом за миллисекунды; pending — ждёт модели на сервере; model — разложено моделью; error — не разложено (причина — в capture_result.error, текст — здесь же).';
COMMENT ON COLUMN tasks.dictations.capture_result IS 'Итог захвата: by, ms, head (одна строка), items [{bucket, line, chips, undo, refs}], error. Его отдаёт вид capture?ref и он же — повтор операции.';
COMMENT ON COLUMN tasks.dictations.capture_from IS 'Откуда фраза: say — строка «сказать», d — кнопка «Д», z — «З», web — строка веба, pc — Диктовка компа.';
COMMENT ON COLUMN tasks.dictations.capture_hint IS 'Подсказки словаря телефона (как он слышит имена) — модель видит их хвостом сообщения.';
CREATE INDEX dictations_capture_pending ON tasks.dictations (at) WHERE capture = 'pending';

-- ── Идеи ────────────────────────────────────────────────────────────────

CREATE TABLE crm.ideas (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    text NOT NULL REFERENCES crm.users,
    text        text NOT NULL CHECK (btrim(text) <> '' AND length(text) <= 2000),
    project_id  uuid REFERENCES crm.projects,
    person_id   uuid REFERENCES crm.people,
    deal_id     uuid REFERENCES crm.deals,
    status      text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'used', 'dropped')),
    task_id     uuid REFERENCES tasks.tasks,
    source      text NOT NULL DEFAULT 'manual' CHECK (source IN ('voice', 'web', 'meeting', 'mcp', 'manual')),
    source_ref  text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    done_at     timestamptz,
    rev         integer NOT NULL DEFAULT 1,
    seq         bigint NOT NULL DEFAULT 0,
    CHECK ((status = 'open') = (done_at IS NULL))
);
COMMENT ON TABLE crm.ideas IS 'Идеи владельца у клиента, человека, сделки (захват 15.10.2026, ответ 5а): всплывают перед встречей и в карточках. Только хозяину. open — живая; used — использована (или стала делом task_id); dropped — убрана.';
COMMENT ON COLUMN crm.ideas.task_id IS 'Идея стала делом («В дело»): это дело.';
COMMENT ON COLUMN crm.ideas.source_ref IS 'Откуда: capture:<ref> — наговорка захвата.';

CREATE INDEX ideas_project ON crm.ideas (project_id) WHERE status = 'open' AND project_id IS NOT NULL;
CREATE INDEX ideas_person ON crm.ideas (person_id) WHERE status = 'open' AND person_id IS NOT NULL;
CREATE INDEX ideas_deal ON crm.ideas (deal_id) WHERE status = 'open' AND deal_id IS NOT NULL;
CREATE INDEX ideas_owner ON crm.ideas (owner_id, created_at DESC);

-- Закрыта или убрана — когда; снова живая — без даты. Сделка — только своего клиента (как у дела).
CREATE FUNCTION crm.idea_rules() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status <> 'open' AND NEW.done_at IS NULL THEN
        NEW.done_at := now();
    ELSIF NEW.status = 'open' THEN
        NEW.done_at := NULL;
    END IF;
    IF NEW.deal_id IS NOT NULL THEN
        IF NEW.project_id IS NULL THEN
            NEW.project_id := (SELECT d.project_id FROM crm.deals d WHERE d.id = NEW.deal_id);
        ELSIF NOT EXISTS (SELECT 1 FROM crm.deals d WHERE d.id = NEW.deal_id AND d.project_id = NEW.project_id) THEN
            RAISE EXCEPTION 'Дела: сделка идеи должна быть из её клиента';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rules BEFORE INSERT OR UPDATE ON crm.ideas FOR EACH ROW EXECUTE FUNCTION crm.idea_rules();
CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON crm.ideas FOR EACH ROW EXECUTE FUNCTION crm.stamp();
CREATE TRIGGER journal AFTER INSERT OR UPDATE OR DELETE ON crm.ideas FOR EACH ROW EXECUTE FUNCTION crm.journal();

ALTER TABLE crm.ideas ENABLE ROW LEVEL SECURITY;
-- Только своё: идея — мысль владельца о клиенте, команде она не видна (как сводки дайджеста и наговорки).
CREATE POLICY own ON crm.ideas FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));

-- ── Вид дела: новые колонки — пересборкой (вид на t.*), права — переносом (как в dela_0003, 0013) ──
-- Тело — от последней сборки tasks.v_tasks (dela_0030, дела внутри дела: why_goal в хвосте, pos — через t.*): в ветке
-- захват пересобирал вид от dela_0013 и молча снял бы why_goal, а с ним «Зачем — цель или дело» у веба, телефона и
-- коннектора (поезд 16.10.2026, вагоны 2 и 4). Следующая пересборка — тоже от последней (правило 26 карты Дел).

CREATE TEMP TABLE v_tasks_grants ON COMMIT DROP AS
    SELECT DISTINCT grantee FROM information_schema.role_table_grants
    WHERE table_schema = 'tasks' AND table_name = 'v_tasks' AND privilege_type = 'SELECT' AND grantee <> current_user;

DROP VIEW tasks.v_tasks;
CREATE VIEW tasks.v_tasks WITH (security_invoker = true) AS
SELECT t.*,
       coalesce(t.money, p.money_default, 'none') AS money_eff,
       coalesce(p.sphere, 'inbox') AS sphere,
       p.name AS project_name,
       p.kind AS project_kind,
       d.name AS deal_name,
       pe.short AS person_short,
       pe.name AS person_name,
       rq.short AS requested_by_short,
       (t.status = 'open' AND t.due_date < crm.today()) AS overdue,
       (t.focus_on = crm.today()) AS now_,
       CASE WHEN t.owed THEN 'owed' WHEN t.ball = 'waiting' THEN 'check' ELSE 'self' END AS grp,
       -- Круг (dela_0009, 0013): только хозяину дела — у остальных строки Круга не видно, поля пустые.
       coalesce(k.directions, '{}'::text[]) AS directions,
       k.why_id,
       k.seq AS krug_seq,
       CASE WHEN t.owner_id = crm.me() THEN tasks.krug_dirs(t.id) ELSE '{}'::text[] END AS dirs,
       w.num AS why_num,
       w.title AS why_title,
       -- Открытых дел внутри: напоминание (kind remind) — не дело, внутри его не считаем (захват, dela_0032).
       (SELECT count(*) FROM tasks.krug c JOIN tasks.tasks ct ON ct.id = c.id
        WHERE c.why_id = t.id AND ct.status = 'open' AND ct.kind = 'task')::integer AS steps_open,
       coalesce(k.goal, false) AS goal,
       k.rhythm,
       coalesce(k.no_steps, false) AS no_steps,
       -- «Зачем» — цель дерева (true) или обычное дело, внутри которого это дело (false); без «Зачем» — false.
       coalesce(wk.goal, false) AS why_goal
FROM tasks.tasks t
LEFT JOIN crm.projects p ON p.id = t.project_id
LEFT JOIN crm.deals d ON d.id = t.deal_id
LEFT JOIN crm.people pe ON pe.id = t.person_id
LEFT JOIN crm.people rq ON rq.id = t.requested_by
LEFT JOIN tasks.krug k ON k.id = t.id AND k.kind = 'task'
LEFT JOIN tasks.tasks w ON w.id = k.why_id
LEFT JOIN tasks.krug wk ON wk.id = k.why_id AND wk.kind = 'task';
COMMENT ON VIEW tasks.v_tasks IS 'Задача с наследованными деньгами, сферой проекта, именами людей, группой (owed — ждут от меня, self — сам, check — проверить) и Кругом хозяину: directions — свои направления, dirs — [главное, метки…], why — «Зачем» (дело-цель или дело, внутри которого это дело; why_goal — цель ли это), steps_open — открытых дел внутри (напоминания не в счёт), pos — место среди соседей, goal — узел дерева целей (в списках дел его нет), rhythm — ритм практики, no_steps — под целью дел не заводят. kind — task или remind (напоминание: в списках дел его нет), back_on — день возврата дела «потом», back_said — как сказано.';

DO $$
DECLARE
    r text;
BEGIN
    FOR r IN SELECT grantee FROM v_tasks_grants LOOP
        EXECUTE format('GRANT SELECT ON tasks.v_tasks TO %I', r);
    END LOOP;
END $$;

-- ── Слияние людей переносит и идеи (people.REFS; тело — как в dela_0027, плюс crm.ideas) ──

CREATE OR REPLACE FUNCTION crm.person_merge(p_dup uuid, p_into uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = crm, pg_temp AS $$
DECLARE
    v_me   text := crm.me();
    v_raw  text := current_setting('dela.user', true);
    v_dup  crm.people;
    v_into crm.people;
    v_n    integer := 0;
    v_k    integer;
    v_s    record;
    v_p    jsonb;
BEGIN
    -- Склеивает людей только владелец (закон 3): слияние трогает дела, сделки и встречи всех, а отмены у него нет.
    IF v_me IS DISTINCT FROM 'system' AND v_me IS DISTINCT FROM crm.owner_id() THEN
        RAISE EXCEPTION 'Дела: склеивает людей только владелец Дел — слияние переносит дела, сделки и встречи всех';
    END IF;
    IF p_dup IS NULL OR p_into IS NULL OR p_dup = p_into THEN
        RAISE EXCEPTION 'Дела: слияние — нужны две разные карточки: дубль и живая';
    END IF;
    SELECT * INTO v_dup FROM crm.people WHERE id = p_dup FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Дела: слияние — нет такого человека (дубль)';
    END IF;
    SELECT * INTO v_into FROM crm.people WHERE id = p_into FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Дела: слияние — нет такого человека (живая карточка)';
    END IF;
    IF v_dup.merged_into IS NOT NULL THEN
        RAISE EXCEPTION 'Дела: «%» уже слит в другую карточку — второй раз его не сливаем', v_dup.name;
    END IF;
    IF v_into.merged_into IS NOT NULL OR v_into.archived_at IS NOT NULL THEN
        RAISE EXCEPTION 'Дела: «%» сама в архиве — сливать в неё нельзя', v_into.name;
    END IF;
    IF v_dup.user_id IS NOT NULL AND v_into.user_id IS NOT NULL THEN
        RAISE EXCEPTION 'Дела: «%» и «%» — оба пользователи Дел, такое решает владелец руками', v_dup.name, v_into.name;
    END IF;

    -- Дела: о ком и кто просил.
    UPDATE tasks.tasks SET person_id = p_into WHERE person_id = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE tasks.tasks SET requested_by = p_into WHERE requested_by = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    -- Сделки: ведёт, привёл, люди клиента, команда (по team_ids сотрудник видит клиента — crm.on_client).
    UPDATE crm.deals SET lead_person_id = p_into WHERE lead_person_id = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.deals SET source_person_id = p_into WHERE source_person_id = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.deals SET person_ids = crm.ids_swap(person_ids, p_dup, p_into) WHERE p_dup = ANY (person_ids);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.deals SET team_ids = crm.ids_swap(team_ids, p_dup, p_into) WHERE p_dup = ANY (team_ids);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    -- Хронология (и приватные записи дайджеста — они тоже его) и участники встреч.
    UPDATE crm.interactions SET person_ids = crm.ids_swap(person_ids, p_dup, p_into) WHERE p_dup = ANY (person_ids);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.meetings SET person_ids = crm.ids_swap(person_ids, p_dup, p_into) WHERE p_dup = ANY (person_ids);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    -- «Только тебе» (dela_0027): наблюдения о дубле — о живой.
    UPDATE crm.insights SET person_ids = crm.ids_swap(person_ids, p_dup, p_into) WHERE p_dup = ANY (person_ids);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    -- Идеи о человеке (dela_0032): «идея: Ивану предложить…» живёт у живой карточки.
    UPDATE crm.ideas SET person_id = p_into WHERE person_id = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    -- Ждущие вопросы «Нового»: «это он?» (payload.person_id) и варианты ответа кнопками (options[].person_id). Решённые
    -- не трогаем — их result — история.
    FOR v_s IN
        SELECT s.id, s.payload FROM tasks.suggestions s
         WHERE s.status = 'pending'
           AND (s.payload ->> 'person_id' = p_dup::text
                OR (jsonb_typeof(s.payload -> 'options') = 'array'
                    AND EXISTS (SELECT 1 FROM jsonb_array_elements(s.payload -> 'options') AS e(o)
                                 WHERE o ->> 'person_id' = p_dup::text)))
    LOOP
        v_p := v_s.payload;
        IF v_p ->> 'person_id' = p_dup::text THEN
            v_p := jsonb_set(v_p, '{person_id}', to_jsonb(p_into::text));
        END IF;
        IF jsonb_typeof(v_p -> 'options') = 'array' THEN
            v_p := jsonb_set(v_p, '{options}', (
                SELECT coalesce(jsonb_agg(CASE WHEN o ->> 'person_id' = p_dup::text
                                               THEN jsonb_set(o, '{person_id}', to_jsonb(p_into::text)) ELSE o END
                                          ORDER BY i), '[]'::jsonb)
                  FROM jsonb_array_elements(v_p -> 'options') WITH ORDINALITY AS e(o, i)));
        END IF;
        UPDATE tasks.suggestions SET payload = v_p WHERE id = v_s.id;
        v_n := v_n + 1;
    END LOOP;
    -- Семья в Своде (people.family — массив id: «Семья» звонков на телефоне и дайджест).
    UPDATE crm.svod SET
           value = (SELECT coalesce(jsonb_agg(x ORDER BY i), '[]'::jsonb)
                      FROM (SELECT CASE WHEN e = to_jsonb(p_dup::text) THEN to_jsonb(p_into::text) ELSE e END AS x,
                                   min(i) AS i
                              FROM jsonb_array_elements(value) WITH ORDINALITY AS t(e, i) GROUP BY 1) q),
           author = 'merge', reason = 'слияние людей: «' || v_dup.name || '» → «' || v_into.name || '»'
     WHERE key = 'people.family' AND jsonb_typeof(value) = 'array' AND value @> jsonb_build_array(p_dup::text);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    -- Прежние дубли этого дубля — сразу на живую: встреча или голос со старым id найдут живого за один шаг.
    UPDATE crm.people SET merged_into = p_into WHERE merged_into = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    -- Учётка Дел дубля — живой: user_id уникален (сначала снять с дубля), а crm.users.person_id переставляет только
    -- system (crm.users_guard) — на один запрос, потом прежний пользователь.
    IF v_dup.user_id IS NOT NULL THEN
        UPDATE crm.people SET user_id = NULL WHERE id = p_dup;
        UPDATE crm.people SET user_id = v_dup.user_id WHERE id = p_into;
    END IF;
    IF EXISTS (SELECT 1 FROM crm.users WHERE person_id = p_dup) THEN
        PERFORM set_config('dela.user', 'system', true);
        UPDATE crm.users SET person_id = p_into WHERE person_id = p_dup;
        PERFORM set_config('dela.user', coalesce(v_raw, ''), true);
    END IF;
    -- Пустое у живой — из дубля (компания, должность, день рождения, заметка). День и месяц пустуют только вместе.
    UPDATE crm.people SET org_id = coalesce(org_id, v_dup.org_id), role = coalesce(role, v_dup.role),
           birth_day = coalesce(birth_day, v_dup.birth_day), birth_month = coalesce(birth_month, v_dup.birth_month),
           birth_year = coalesce(birth_year, v_dup.birth_year), note = coalesce(note, v_dup.note)
     WHERE id = p_into;
    UPDATE crm.people SET merged_into = p_into, archived_at = coalesce(archived_at, now()) WHERE id = p_dup;
    RETURN jsonb_build_object('dup', to_jsonb(v_dup), 'moved', v_n);
END $$;
COMMENT ON FUNCTION crm.person_merge(uuid, uuid) IS 'Слить дубль в живую карточку (аудит №23, dela_0023; dela_0027 — и «Только тебе»; dela_0032 — и идеи): только владелец Дел или system; все ссылки мимо RLS — дела (person_id, requested_by), сделки (ведёт, привёл, люди клиента, команда), хронология, участники встреч, «Только тебе» (crm.insights.person_ids), идеи (crm.ideas.person_id), ждущие вопросы «Нового» (person_id и варианты), Свод people.family, прежние дубли дубля, учётка Дел; пустые поля живой — из дубля; дубль — archived_at и merged_into. Ответ: {dup — строка дубля до слияния, moved — сколько строк переехало}. Имена и номера дубля дописывает people.op_person_merge.';

-- ── Подъём родителя — только по делам (dela_0030, правило 27; поезд 16.10.2026, вагоны 2 и 4) ──
-- Напоминание (kind remind) — не дело: «Сделано» у уведомления телефона или кнопка бота не поднимают родителя, «Вернуть»
-- напоминания не возвращает ему срок, а открытое напоминание внутри не держит родителя «внутри ещё открыто» вечно (в
-- dela_0030 открытых детей искали по tasks.krug.kind = 'task' — это вид строки Круга, а не дела). Вкладывать напоминание
-- в дело сервер и так не даёт (store._krug_own); здесь — сама база, что бы ни лежало в данных. Права — прежние (CREATE OR
-- REPLACE их не трогает: PUBLIC снят в dela_0030, роли службы — db.APP_FUNCTIONS).
CREATE OR REPLACE FUNCTION tasks.nest_rise(child uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path = tasks, crm, pg_temp AS $$
DECLARE
    pid uuid;
    p tasks.tasks;
    d date := crm.today();
    moved boolean;
BEGIN
    SELECT k.why_id INTO pid FROM tasks.krug k JOIN tasks.tasks c ON c.id = k.id
    WHERE k.id = child AND k.kind = 'task' AND c.status = 'done' AND c.kind = 'task';
    IF pid IS NULL OR EXISTS (SELECT 1 FROM tasks.krug WHERE id = pid AND goal) THEN
        RETURN NULL;
    END IF;
    SELECT * INTO p FROM tasks.tasks WHERE id = pid FOR UPDATE;
    IF p.id IS NULL OR p.status <> 'open' THEN
        RETURN NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM tasks.krug k JOIN tasks.tasks c ON c.id = k.id
               WHERE k.why_id = pid AND k.kind = 'task' AND c.status = 'open' AND c.kind = 'task' AND NOT k.goal) THEN
        RETURN NULL;
    END IF;
    moved := p.due_date IS NULL OR p.due_date > d;
    IF moved THEN
        UPDATE tasks.tasks SET due_date = d WHERE id = pid;  -- триггер nest_forget гасит прежнюю память
        INSERT INTO tasks.nest_raised (parent_id, was, rose_to) VALUES (pid, p.due_date, d)
        ON CONFLICT (parent_id) DO UPDATE SET was = EXCLUDED.was, rose_to = EXCLUDED.rose_to, at = now();
    END IF;
    RETURN jsonb_build_object('id', p.id, 'num', p.num, 'title', p.title, 'owner_id', p.owner_id, 'moved', moved,
                              'due_date', CASE WHEN moved THEN d ELSE p.due_date END);
END $$;
COMMENT ON FUNCTION tasks.nest_rise(uuid) IS 'Сделано последнее открытое дело внутри — родитель поднимается на сегодня (правило 27). Считаются только дела: напоминание (kind remind) не поднимает и не держит (dela_0032). Ответ: id, num, title, owner_id, moved, due_date родителя; иначе NULL.';

CREATE OR REPLACE FUNCTION tasks.nest_fall(child uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path = tasks, crm, pg_temp AS $$
DECLARE
    pid uuid;
    m tasks.nest_raised;
    p tasks.tasks;
BEGIN
    SELECT k.why_id INTO pid FROM tasks.krug k JOIN tasks.tasks c ON c.id = k.id
    WHERE k.id = child AND k.kind = 'task' AND c.status = 'open' AND c.kind = 'task';
    IF pid IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT * INTO m FROM tasks.nest_raised WHERE parent_id = pid FOR UPDATE;
    IF m.parent_id IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT * INTO p FROM tasks.tasks WHERE id = pid FOR UPDATE;
    IF p.status <> 'open' OR p.due_date IS DISTINCT FROM m.rose_to THEN
        RETURN NULL;
    END IF;
    UPDATE tasks.tasks SET due_date = m.was WHERE id = pid;  -- триггер nest_forget гасит память
    RETURN jsonb_build_object('id', p.id, 'num', p.num, 'title', p.title, 'owner_id', p.owner_id, 'due_date', m.was);
END $$;
COMMENT ON FUNCTION tasks.nest_fall(uuid) IS '«Вернуть» дела внутри: подъём родителя отменяется — срок, какой был до него, если его с тех пор не правили (правило 27). Только дела: вернувшееся напоминание срок не возвращает (dela_0032). Ответ: id, num, title, owner_id, due_date родителя; иначе NULL.';

-- ── «Какой Иван?» — вопрос захвата в «Подскажи» (проверка 16.10.2026) ─────────
-- Тёзки в сказанном («Иван сказал, …», «позвонил Ивану» при двух Иванах): запись ложится без человека, а кнопка под
-- строкой живёт 12 секунд пилюли — не нажал, и заметки нет ни в одной карточке. Теперь ещё и вопрос kind capture:
-- payload.ask, options — люди (person_id), pick, capture {ref, item}; ответ — тот же capture.move person (store).
-- Дела у вопроса может не быть (заметка) — как у create и person.
ALTER TABLE tasks.suggestions DROP CONSTRAINT suggestions_kind_check;
ALTER TABLE tasks.suggestions ADD CONSTRAINT suggestions_kind_check
    CHECK (kind IN ('create', 'close', 'update', 'assign', 'person', 'capture'));
ALTER TABLE tasks.suggestions DROP CONSTRAINT suggestions_task_check;
ALTER TABLE tasks.suggestions ADD CONSTRAINT suggestions_task_check
    CHECK (kind IN ('create', 'person', 'capture') OR task_id IS NOT NULL);
COMMENT ON COLUMN tasks.suggestions.kind IS 'create — новое дело; close — предложить закрыть («отправил»); update — поправить; assign — предложить дело пользователю (Марианне — только с её согласия); person — новый человек из Telegram или телефонной книжки (dela_0019); capture — «какой Иван?» захвата (dela_0032): тёзки в сказанном, ответ кнопкой человека (payload.capture — какая запись).';
