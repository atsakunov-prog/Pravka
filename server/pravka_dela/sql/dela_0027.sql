-- Дела, миграция 27 (15.10.2026, аудит №8–11, место этапа 6): «Только тебе» — наблюдения только владельцу.
--
-- Разбор встреч и переписки (этап 6) будет замечать несостыковки, неуверенность, «кто за столом», оценки людей; владелец
-- и сам пишет такое о людях и клиентах. Это видно только ему: не команде, не в общей хронологии клиента, не в
-- коннекторе для чужих. Хронология не годится (наблюдение — не касание: двигало бы «последний контакт» и смешивалось с
-- хронологией клиента), поле карточки — тоже (у поля нет даты, источника и состояния, а пометки карточки — профиль,
-- который владелец ведёт сам).
--
-- crm.insights — одна строка — одно наблюдение: вид (kind — словарь этапа 6, без миграции), текст и цитата, о ком (люди,
-- клиент, сделка, компания, встреча), когда (at, секунда встречи), откуда (вручную: web, phone, mcp; службы: meeting,
-- digest), повтор находки службы (item_key — та же строка), состояние (new → seen / useful / dismissed / wrong / moved).
-- Видит и пишет только владелец Дел (RLS «своё» и правило строки), журнала нет — как у приватной хронологии и сводок
-- дайджеста (текст оценок не копится в crm.history); кто записал — в author. Встреча стала личной — её строки убраны;
-- личную встречу «Только тебе» в CRM не держит. Слияние людей переносит person_ids (crm.person_merge — заново).
--
-- Обратимо по смыслу: таблица новая, функция слияния — прежнее тело плюс одна строка; данные не трогаются.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

CREATE TABLE crm.insights (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    text NOT NULL REFERENCES crm.users,
    kind        text NOT NULL DEFAULT 'note' CHECK (kind ~ '^[a-z][a-z0-9_]{0,23}$'),
    text        text NOT NULL CHECK (btrim(text) <> '' AND length(text) <= 2000),
    quote       text CHECK (quote IS NULL OR length(quote) <= 1000),
    person_ids  uuid[] NOT NULL DEFAULT '{}',
    project_id  uuid REFERENCES crm.projects,
    deal_id     uuid REFERENCES crm.deals,
    org_id      uuid REFERENCES crm.orgs,
    meeting_id  uuid REFERENCES crm.meetings,
    at          timestamptz NOT NULL DEFAULT now(),
    at_sec      numeric(9, 1) CHECK (at_sec IS NULL OR at_sec >= 0),
    source      text NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'web', 'phone', 'mcp', 'meeting', 'digest')),
    source_ref  text,
    item_key    text CHECK (item_key IS NULL OR (btrim(item_key) <> '' AND length(item_key) <= 200)),
    state       text NOT NULL DEFAULT 'new' CHECK (state IN ('new', 'seen', 'useful', 'dismissed', 'wrong', 'moved')),
    author      text NOT NULL,
    deleted_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    rev         integer NOT NULL DEFAULT 1,
    seq         bigint NOT NULL DEFAULT 0,
    CHECK (deal_id IS NULL OR project_id IS NOT NULL),
    CHECK (cardinality(person_ids) > 0 OR project_id IS NOT NULL OR org_id IS NOT NULL OR meeting_id IS NOT NULL
           OR source_ref IS NOT NULL)
);
COMMENT ON TABLE crm.insights IS '«Только тебе» (аудит №8–11, место этапа 6): наблюдения только владельцу Дел — оценки людей, несостыковки, неуверенность, «кто за столом», что уточнить, личное о людях. Пишет владелец (веб, телефон, коннектор) и службы этапа 6 (разбор встречи, раздел дайджеста). Команде, синку, хронологии клиента и журналу не видны; людям не цитировать.';
COMMENT ON COLUMN crm.insights.kind IS 'Вид наблюдения: note — заметка владельца; остальные ключи — словарь этапа 6 (латиница, без миграции).';
COMMENT ON COLUMN crm.insights.at IS 'Когда это было: начало встречи, окно дайджеста, миг записи.';
COMMENT ON COLUMN crm.insights.at_sec IS 'Секунда от начала встречи (ссылка на место в расшифровке).';
COMMENT ON COLUMN crm.insights.item_key IS 'Ключ находки службы: повтор той же находки (source, source_ref, item_key) не плодит строк — обновляет живую, убранную не возвращает.';
COMMENT ON COLUMN crm.insights.state IS 'new — новое; seen — видел; useful — пригодилось; dismissed — мимо; wrong — неверно; moved — перенёс в дело или карточку.';
COMMENT ON COLUMN crm.insights.author IS 'Кто записал (crm.actor() на вставке): журнала у таблицы нет — видно здесь.';

CREATE UNIQUE INDEX insights_item ON crm.insights (owner_id, source, coalesce(source_ref, ''), item_key)
    WHERE item_key IS NOT NULL;
CREATE INDEX insights_people ON crm.insights USING gin (person_ids);
CREATE INDEX insights_project ON crm.insights (project_id) WHERE deleted_at IS NULL;
CREATE INDEX insights_deal ON crm.insights (deal_id) WHERE deleted_at IS NULL;
CREATE INDEX insights_org ON crm.insights (org_id) WHERE deleted_at IS NULL;
CREATE INDEX insights_meeting ON crm.insights (meeting_id) WHERE deleted_at IS NULL;

CREATE FUNCTION crm.insight_rules() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
DECLARE
    v_project uuid;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM crm.users u WHERE u.id = NEW.owner_id AND u.role = 'owner') THEN
        RAISE EXCEPTION 'Дела: «Только тебе» — только владельцу Дел, а % не владелец', NEW.owner_id;
    END IF;
    -- Журнала нет, а правило «без dela.actor не пишем» держал журнал — держим здесь; кто записал — author.
    IF crm.actor() IS NULL THEN
        RAISE EXCEPTION 'Дела: изменение без dela.actor — некому его подписать (crm.insights)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        NEW.author := crm.actor();
    ELSE
        NEW.author := OLD.author;
    END IF;
    IF NEW.deal_id IS NOT NULL THEN
        SELECT d.project_id INTO v_project FROM crm.deals d WHERE d.id = NEW.deal_id;
        IF NEW.project_id IS NULL THEN
            NEW.project_id := v_project;
        ELSIF NEW.project_id IS DISTINCT FROM v_project THEN
            RAISE EXCEPTION 'Дела: «Только тебе» — сделка другого клиента';
        END IF;
    END IF;
    IF NEW.meeting_id IS NOT NULL AND NEW.deleted_at IS NULL
       AND EXISTS (SELECT 1 FROM crm.meetings m WHERE m.id = NEW.meeting_id AND m.sphere = 'home') THEN
        RAISE EXCEPTION 'Дела: личная встреча — без «Только тебе» в CRM';
    END IF;
    NEW.person_ids := crm.ids_swap(NEW.person_ids, NULL, NULL);
    RETURN NEW;
END $$;
CREATE TRIGGER rules BEFORE INSERT OR UPDATE ON crm.insights FOR EACH ROW EXECUTE FUNCTION crm.insight_rules();
CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON crm.insights FOR EACH ROW EXECUTE FUNCTION crm.stamp();

ALTER TABLE crm.insights ENABLE ROW LEVEL SECURITY;
CREATE POLICY own ON crm.insights FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));

-- Встреча стала личной — её «Только тебе» убраны (этап 6, design-deals §6 п. 5): терапия и семья в CRM не живут.
CREATE FUNCTION crm.meeting_home_insights() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    UPDATE crm.insights SET deleted_at = now() WHERE meeting_id = NEW.id AND deleted_at IS NULL;
    RETURN NULL;
END $$;
CREATE TRIGGER home_insights AFTER UPDATE OF sphere ON crm.meetings
    FOR EACH ROW WHEN (NEW.sphere = 'home' AND OLD.sphere IS DISTINCT FROM 'home')
    EXECUTE FUNCTION crm.meeting_home_insights();

-- ── Слияние людей — и «Только тебе» (тело — dela_0023, плюс crm.insights.person_ids) ──────────

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

    UPDATE tasks.tasks SET person_id = p_into WHERE person_id = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE tasks.tasks SET requested_by = p_into WHERE requested_by = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.deals SET lead_person_id = p_into WHERE lead_person_id = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.deals SET source_person_id = p_into WHERE source_person_id = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.deals SET person_ids = crm.ids_swap(person_ids, p_dup, p_into) WHERE p_dup = ANY (person_ids);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.deals SET team_ids = crm.ids_swap(team_ids, p_dup, p_into) WHERE p_dup = ANY (team_ids);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.interactions SET person_ids = crm.ids_swap(person_ids, p_dup, p_into) WHERE p_dup = ANY (person_ids);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.meetings SET person_ids = crm.ids_swap(person_ids, p_dup, p_into) WHERE p_dup = ANY (person_ids);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    -- «Только тебе» (dela_0027): наблюдения о дубле — о живой.
    UPDATE crm.insights SET person_ids = crm.ids_swap(person_ids, p_dup, p_into) WHERE p_dup = ANY (person_ids);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
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
    UPDATE crm.svod SET
           value = (SELECT coalesce(jsonb_agg(x ORDER BY i), '[]'::jsonb)
                      FROM (SELECT CASE WHEN e = to_jsonb(p_dup::text) THEN to_jsonb(p_into::text) ELSE e END AS x,
                                   min(i) AS i
                              FROM jsonb_array_elements(value) WITH ORDINALITY AS t(e, i) GROUP BY 1) q),
           author = 'merge', reason = 'слияние людей: «' || v_dup.name || '» → «' || v_into.name || '»'
     WHERE key = 'people.family' AND jsonb_typeof(value) = 'array' AND value @> jsonb_build_array(p_dup::text);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.people SET merged_into = p_into WHERE merged_into = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    IF v_dup.user_id IS NOT NULL THEN
        UPDATE crm.people SET user_id = NULL WHERE id = p_dup;
        UPDATE crm.people SET user_id = v_dup.user_id WHERE id = p_into;
    END IF;
    IF EXISTS (SELECT 1 FROM crm.users WHERE person_id = p_dup) THEN
        PERFORM set_config('dela.user', 'system', true);
        UPDATE crm.users SET person_id = p_into WHERE person_id = p_dup;
        PERFORM set_config('dela.user', coalesce(v_raw, ''), true);
    END IF;
    UPDATE crm.people SET org_id = coalesce(org_id, v_dup.org_id), role = coalesce(role, v_dup.role),
           birth_day = coalesce(birth_day, v_dup.birth_day), birth_month = coalesce(birth_month, v_dup.birth_month),
           birth_year = coalesce(birth_year, v_dup.birth_year), note = coalesce(note, v_dup.note)
     WHERE id = p_into;
    UPDATE crm.people SET merged_into = p_into, archived_at = coalesce(archived_at, now()) WHERE id = p_dup;
    RETURN jsonb_build_object('dup', to_jsonb(v_dup), 'moved', v_n);
END $$;
COMMENT ON FUNCTION crm.person_merge(uuid, uuid) IS 'Слить дубль в живую карточку (аудит №23, dela_0023; dela_0027 — и «Только тебе»): только владелец Дел или system; все ссылки мимо RLS — дела (person_id, requested_by), сделки (ведёт, привёл, люди клиента, команда), хронология, участники встреч, «Только тебе» (crm.insights.person_ids), ждущие вопросы «Нового» (person_id и варианты), Свод people.family, прежние дубли дубля, учётка Дел; пустые поля живой — из дубля; дубль — archived_at и merged_into. Ответ: {dup — строка дубля до слияния, moved — сколько строк переехало}. Имена и номера дубля дописывает people.op_person_merge.';
