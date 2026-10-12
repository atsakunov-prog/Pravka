-- Дела, миграция 10 (12.10.2026): встречи в базе — карточка с единым названием, расшифровка, поиск.
--
-- Владелец, 12.10.2026: «сначала нам надо по всем транскриптам встреч пройти и правильно их назвать… у встречи
-- должно быть единообразное название: клиент, основная тема, кто присутствовал, дата, duration… каждая встреча
-- разбивается в таблицы… в списке встреч сортировка по клиентам, по времени, по присутствующим». И: «точно надо
-- закидывать встречи в базу. Я вообще не знал, что этого нет… закинем вообще всё, что возможно… встречи
-- поправить на личные и рабочие — отдельным свойством».
--
-- До этого служба встреч (ZF-Meetings, своя SQLite на ПК) отдавала Делам только сводку встречи в хронологию
-- клиента (crm.interactions, source = meeting) и предложения дел. Сама встреча, её участники и расшифровка в
-- базе не жили: ни поиска по разговору, ни списка встреч, а терапия лежала в той же хронологии, что клиенты.
--
-- Три таблицы, а не одна:
-- • crm.meetings — карточка: факты от службы (когда, сколько, сводка) и разбор (клиент, тема, участники,
--   рабочая или личная, единое название). Журнал и версии — как у всех сущностей Дел: правка владельца видна.
-- • crm.meeting_lines — расшифровка репликами, у каждой свой русский поиск. Журнала нет: это сырьё службы,
--   повторная присылка заменяет реплики целиком (строки удаляются — сознательно, как точки «Где мы»). Расшифровка
--   в карточке потащила бы мегабайты в журнал на каждую правку названия, а один tsvector на всю встречу
--   упирается в предел 1 МБ.
-- • crm.meeting_raw — присланное целиком, «как есть» (владелец: «закинем всё, что возможно»): исходный текст и
--   вся запись службы. Из неё можно вывести заново всё, что сегодня не разобрано.
--
-- Видит только владелец встречи (RLS): в расшифровках терапия и разговоры, которые команде не нужны. Открыть
-- рабочие встречи команде — отдельным решением владельца, тогда политика станет как у хронологии (по проекту).

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

CREATE TABLE crm.meetings (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source        text NOT NULL DEFAULT 'meetings' CHECK (source ~ '^[a-z][a-z0-9_]{1,23}$'),
    ref           text NOT NULL CHECK (btrim(ref) <> ''),
    url           text,
    started_at    timestamptz NOT NULL,
    duration_min  integer CHECK (duration_min >= 0),
    kind          text NOT NULL DEFAULT 'meeting' CHECK (kind IN ('meeting', 'zoom', 'call', 'other')),
    source_title  text,
    summary       text,
    agreements    jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(agreements) = 'array'),
    speakers      text[] NOT NULL DEFAULT '{}',
    hint_project  text,
    content_md5   text,
    lines_n       integer NOT NULL DEFAULT 0,
    chars_n       integer NOT NULL DEFAULT 0,
    -- Разбор (meetings_auto.py) и правка владельца.
    sphere        text CHECK (sphere IN ('work', 'home')),
    project_id    uuid REFERENCES crm.projects,
    deal_id       uuid REFERENCES crm.deals,
    label         text,
    topic         text,
    title         text,
    person_ids    uuid[] NOT NULL DEFAULT '{}',
    guests        text[] NOT NULL DEFAULT '{}',
    locked        text[] NOT NULL DEFAULT '{}',
    named_at      timestamptz,
    named_by      text,
    question      text,
    archived_at   timestamptz,
    owner_id      text NOT NULL REFERENCES crm.users,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    rev           integer NOT NULL DEFAULT 1,
    seq           bigint NOT NULL DEFAULT 0,
    search        tsvector GENERATED ALWAYS AS (to_tsvector('russian',
                      coalesce(title, '') || ' ' || coalesce(label, '') || ' ' || coalesce(topic, '') || ' '
                      || coalesce(source_title, '') || ' ' || coalesce(summary, ''))) STORED,
    UNIQUE (source, ref),
    CHECK (deal_id IS NULL OR project_id IS NOT NULL),
    CHECK (locked <@ '{title,topic,project,sphere,people}'::text[])
);
COMMENT ON TABLE crm.meetings IS 'Встречи: факты службы встреч (когда, сколько, сводка) и разбор — клиент, тема, участники, рабочая или личная, единое название «Клиент: тема». Видит только владелец.';
COMMENT ON COLUMN crm.meetings.ref IS 'Номер встречи у службы (у ZF-Meetings — N из …/m/N): по нему повторная присылка обновляет ту же строку, а хронология и дела встречи находят её.';
COMMENT ON COLUMN crm.meetings.sphere IS 'work — рабочая, home — личная (терапия, семья, дети). Пусто — ещё не разобрана.';
COMMENT ON COLUMN crm.meetings.label IS 'Кто в названии: имя клиента из справочника; без клиента — компания или человек, «ЗФ» для своих, «Личное» для личной.';
COMMENT ON COLUMN crm.meetings.topic IS 'Основная тема в несколько слов, без клиента и даты.';
COMMENT ON COLUMN crm.meetings.title IS 'Единое название «label: topic». Владелец назвал сам — в locked есть title, разбор его не трогает.';
COMMENT ON COLUMN crm.meetings.person_ids IS 'Участники из справочника, кроме самого владельца.';
COMMENT ON COLUMN crm.meetings.guests IS 'Участники, которых нет в справочнике, — как их назвали в разговоре.';
COMMENT ON COLUMN crm.meetings.locked IS 'Что владелец поправил руками (title, topic, project, sphere, people): разбор это не переписывает.';
COMMENT ON COLUMN crm.meetings.question IS 'Разбор не уверен — вопрос владельцу словами («Это Додо или Тилтех?»).';
COMMENT ON COLUMN crm.meetings.content_md5 IS 'Отпечаток сводки и расшифровки: поменялись при новой присылке — встреча уходит на разбор заново.';

CREATE INDEX meetings_started ON crm.meetings (started_at DESC);
CREATE INDEX meetings_project ON crm.meetings (project_id) WHERE project_id IS NOT NULL;
CREATE INDEX meetings_people ON crm.meetings USING gin (person_ids);
CREATE INDEX meetings_unnamed ON crm.meetings (started_at) WHERE named_at IS NULL AND archived_at IS NULL;
CREATE INDEX meetings_search ON crm.meetings USING gin (search);

CREATE TABLE crm.meeting_lines (
    meeting_id  uuid NOT NULL REFERENCES crm.meetings ON DELETE CASCADE,
    n           integer NOT NULL CHECK (n >= 0),
    start_sec   numeric(9, 1),
    end_sec     numeric(9, 1),
    speaker     text,
    text        text NOT NULL,
    owner_id    text NOT NULL REFERENCES crm.users,
    tsv         tsvector GENERATED ALWAYS AS (to_tsvector('russian', text)) STORED,
    PRIMARY KEY (meeting_id, n)
);
COMMENT ON TABLE crm.meeting_lines IS 'Расшифровка встречи репликами: кто, когда от начала, что сказал. Поиск по словоформам — tsv.';
CREATE INDEX meeting_lines_tsv ON crm.meeting_lines USING gin (tsv);

CREATE TABLE crm.meeting_raw (
    meeting_id  uuid PRIMARY KEY REFERENCES crm.meetings ON DELETE CASCADE,
    transcript  text,
    raw         jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(raw) = 'object'),
    owner_id    text NOT NULL REFERENCES crm.users,
    received_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE crm.meeting_raw IS 'Присланное службой встреч целиком: исходная расшифровка и вся её запись о встрече — сырьё, из которого разбор выводится заново.';

-- Правила карточки — в базе: служба встреч, веб, Claude и разборщик пишут одинаково.
CREATE FUNCTION crm.meeting_rules() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    IF NEW.project_id IS NOT NULL AND NOT crm.sees_project(NEW.owner_id, NEW.project_id) THEN
        RAISE EXCEPTION 'Дела: встреча — % не видит этот проект', NEW.owner_id;
    END IF;
    IF NEW.deal_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM crm.deals d WHERE d.id = NEW.deal_id AND d.project_id = NEW.project_id) THEN
        RAISE EXCEPTION 'Дела: сделка встречи должна быть из её проекта';
    END IF;
    NEW.person_ids := ARRAY(SELECT x FROM unnest(NEW.person_ids) WITH ORDINALITY AS u(x, i) GROUP BY x ORDER BY min(i));
    NEW.locked := ARRAY(SELECT DISTINCT unnest(NEW.locked) ORDER BY 1);
    RETURN NEW;
END $$;

CREATE TRIGGER rules BEFORE INSERT OR UPDATE ON crm.meetings FOR EACH ROW EXECUTE FUNCTION crm.meeting_rules();
CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON crm.meetings FOR EACH ROW EXECUTE FUNCTION crm.stamp();
CREATE TRIGGER journal AFTER INSERT OR UPDATE OR DELETE ON crm.meetings FOR EACH ROW EXECUTE FUNCTION crm.journal();

ALTER TABLE crm.meetings ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.meeting_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.meeting_raw ENABLE ROW LEVEL SECURITY;
CREATE POLICY own ON crm.meetings FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));
CREATE POLICY own ON crm.meeting_lines FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));
CREATE POLICY own ON crm.meeting_raw FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));

-- Номер встречи из ссылки службы (…/m/215): хронология и дела встречи помнят ссылку, а хозяин адреса службы
-- менялся (znakomiy.netcraze.pro:8443 → kovcheg.am) — сравнивать можно только номер.
CREATE FUNCTION crm.meeting_ref(u text) RETURNS text LANGUAGE sql IMMUTABLE AS
$$ SELECT substring(u FROM '/m/([0-9]+)/?$') $$;
COMMENT ON FUNCTION crm.meeting_ref(text) IS 'Номер встречи службы из ссылки …/m/N — для связи хронологии и дел со встречей.';
