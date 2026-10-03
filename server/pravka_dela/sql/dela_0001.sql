-- Дела: справочник (crm) и дела (tasks). Правда о делах живёт здесь, все
-- клиенты — телефон, веб, бот, Claude, встречи — ходят через API службы.
--
-- Устройство, которое держит эта схема:
-- * Каждое изменение пишется в журнал crm.history триггером: мимо него не
--   записать, а без dela.actor запись не проходит вовсе.
-- * Каждая строка несёт rev (версия строки) и seq (номер изменения из одной
--   последовательности на всю базу): телефон забирает «всё после seq».
-- * Строки не удаляются: дело закрывают или отменяют, проект и человека
--   убирают в архив. Поэтому синку не нужны надгробия.
-- * Права — в самой базе (row-level security): служба ставит dela.user на
--   каждый запрос, и база не отдаёт чужое, даже если в запросе забыли условие.
--   Владелец объектов (роль архива pravka) RLS не подчиняется: им идут
--   миграции, ночная копия и виды для Claude в life.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE SCHEMA crm;
CREATE SCHEMA tasks;

COMMENT ON SCHEMA crm IS 'Справочник Дел и будущая CRM: пользователи, организации, люди, проекты-сделки, доступы, взаимодействия, журнал.';
COMMENT ON SCHEMA tasks IS 'Дела: задачи, комментарии, метки, предложения автоматики («Новое»).';

CREATE SEQUENCE crm.change_seq;

-- ── Контекст запроса ────────────────────────────────────────────────────

-- Кто смотрит: id пользователя или 'system' (импорт, напоминания, вход).
CREATE FUNCTION crm.me() RETURNS text LANGUAGE sql STABLE AS
$$ SELECT nullif(current_setting('dela.user', true), '') $$;

-- Кто меняет (в журнал): пользователь или служба ('svc:meetings').
CREATE FUNCTION crm.actor() RETURNS text LANGUAGE sql STABLE AS
$$ SELECT nullif(current_setting('dela.actor', true), '') $$;

-- Откуда пришло изменение: app, web, bot, mcp, meetings, userbot, import, system.
CREATE FUNCTION crm.via() RETURNS text LANGUAGE sql STABLE AS
$$ SELECT coalesce(nullif(current_setting('dela.via', true), ''), 'system') $$;

-- Сутки владельца. Все трое живут в Москве; пояс сервера и UTC тут ни при чём.
CREATE FUNCTION crm.today() RETURNS date LANGUAGE sql STABLE AS
$$ SELECT (now() AT TIME ZONE 'Europe/Moscow')::date $$;

-- ── Справочник ──────────────────────────────────────────────────────────

CREATE TABLE crm.users (
    id          text PRIMARY KEY CHECK (id ~ '^[a-z][a-z0-9_]{1,30}$'),
    name        text NOT NULL,
    person_id   uuid,
    telegram_id bigint UNIQUE,
    role        text NOT NULL DEFAULT 'member' CHECK (role IN ('owner', 'member')),
    settings    jsonb NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    rev         integer NOT NULL DEFAULT 1,
    seq         bigint NOT NULL DEFAULT 0
);
COMMENT ON TABLE crm.users IS 'Кто пользуется Делами: sasha (владелец), natasha, marianna.';
COMMENT ON COLUMN crm.users.settings IS 'Утро во сколько, какие напоминания, вид по умолчанию. У Марианны с завода напоминаний и просрочек нет.';

CREATE TABLE crm.orgs (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL CHECK (btrim(name) <> ''),
    aliases     text[] NOT NULL DEFAULT '{}',
    kind        text NOT NULL DEFAULT 'client' CHECK (kind IN ('client', 'partner', 'fund', 'bank', 'other')),
    owner_id    text NOT NULL REFERENCES crm.users,
    note        text,
    notion_id   text UNIQUE,
    archived_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    rev         integer NOT NULL DEFAULT 1,
    seq         bigint NOT NULL DEFAULT 0
);
COMMENT ON TABLE crm.orgs IS 'Организации: клиенты, партнёры, фонды, банки. У клиента может быть несколько проектов-сделок (Алфавит).';

CREATE TABLE crm.people (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name              text NOT NULL CHECK (btrim(name) <> ''),
    short             text,
    aliases           text[] NOT NULL DEFAULT '{}',
    org_id            uuid REFERENCES crm.orgs,
    role              text,
    phones            text[] NOT NULL DEFAULT '{}',
    emails            text[] NOT NULL DEFAULT '{}',
    telegram_id       bigint,
    telegram_username text,
    birth_day         smallint CHECK (birth_day BETWEEN 1 AND 31),
    birth_month       smallint CHECK (birth_month BETWEEN 1 AND 12),
    birth_year        smallint CHECK (birth_year BETWEEN 1900 AND 2100),
    source            text,
    seeks             text,
    offers            text,
    traits            text,
    cadence           text CHECK (cadence IN ('month', 'quarter', 'year', 'none')),
    hub               boolean NOT NULL DEFAULT false,
    user_id           text UNIQUE REFERENCES crm.users,
    owner_id          text NOT NULL REFERENCES crm.users,
    note              text,
    notion_id         text UNIQUE,
    archived_at       timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    rev               integer NOT NULL DEFAULT 1,
    seq               bigint NOT NULL DEFAULT 0,
    CHECK ((birth_day IS NULL) = (birth_month IS NULL))
);
COMMENT ON TABLE crm.people IS 'Люди: команда, семья, клиенты, контакты. Алиасы — для разбора наговора и сопоставления клиента в ленте.';
COMMENT ON COLUMN crm.people.short IS 'Как человек пишется в задачах: «Горецкий», «Наташа».';
COMMENT ON COLUMN crm.people.source IS 'Кто познакомил или откуда.';
COMMENT ON COLUMN crm.people.seeks IS 'Что человеку нужно — для мэтчмейкинга.';
COMMENT ON COLUMN crm.people.offers IS 'Что может дать.';
COMMENT ON COLUMN crm.people.traits IS 'Характер и стиль общения.';
COMMENT ON COLUMN crm.people.cadence IS 'Теплота: как часто видеться — month, quarter, year, none.';
COMMENT ON COLUMN crm.people.hub IS 'Через этого человека постоянно приходят темы.';

ALTER TABLE crm.users ADD FOREIGN KEY (person_id) REFERENCES crm.people;

CREATE TABLE crm.projects (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name          text NOT NULL CHECK (btrim(name) <> ''),
    aliases       text[] NOT NULL DEFAULT '{}',
    sphere        text NOT NULL CHECK (sphere IN ('work', 'home')),
    kind          text NOT NULL CHECK (kind IN ('client', 'internal', 'personal')),
    org_id        uuid REFERENCES crm.orgs,
    owner_id      text NOT NULL REFERENCES crm.users,
    money_default text NOT NULL DEFAULT 'none' CHECK (money_default IN ('paid', 'potential', 'none')),
    note          text,
    import_ref    text UNIQUE,
    archived_at   timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    rev           integer NOT NULL DEFAULT 1,
    seq           bigint NOT NULL DEFAULT 0,
    CHECK (kind <> 'client' OR sphere = 'work')
);
COMMENT ON TABLE crm.projects IS 'Проекты без подпроектов: клиент (как корневой проект Todoist), служебные (ЗФ, Люди), личные (Семья, Личное). Сделки клиента — в crm.deals.';
COMMENT ON COLUMN crm.projects.money_default IS 'paid — оплата согласована, potential — развитие бизнеса, none — остальное. Задачи наследуют, если у них пусто.';
COMMENT ON COLUMN crm.projects.import_ref IS 'id проекта в Todoist (todoist:<id>).';

CREATE TABLE crm.deals (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id     uuid NOT NULL REFERENCES crm.projects,
    name           text NOT NULL CHECK (btrim(name) <> ''),
    stage          text NOT NULL DEFAULT 'lead'
                   CHECK (stage IN ('lead', 'proposal', 'mandate', 'active', 'closing', 'archive')),
    deal_type      text,
    lead_person_id uuid REFERENCES crm.people,
    person_ids     uuid[] NOT NULL DEFAULT '{}',
    fee_kop        bigint CHECK (fee_kop >= 0),
    deadline       date,
    wheel          boolean NOT NULL DEFAULT false,
    ball           text,
    next_step      text,
    my_view        text,
    ideas          text,
    log            text,
    notion_id      text UNIQUE,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    rev            integer NOT NULL DEFAULT 1,
    seq            bigint NOT NULL DEFAULT 0
);
COMMENT ON TABLE crm.deals IS 'Сделки внутри проекта-клиента (бывшая база «Сделки» Notion): у Стеллара — фонды, buy-side M&A, банковский advisory.';
COMMENT ON COLUMN crm.deals.stage IS 'Воронка: lead (Лид), proposal (КП), mandate (Мандат), active (В работе), closing (Закрытие), archive (Архив).';
COMMENT ON COLUMN crm.deals.lead_person_id IS 'Ответственный в ЗФ.';
COMMENT ON COLUMN crm.deals.person_ids IS 'Участники со стороны клиента и партнёров.';
COMMENT ON COLUMN crm.deals.fee_kop IS 'Потенциальный fee ЗФ, копейки.';
COMMENT ON COLUMN crm.deals.wheel IS '«Колесо крутится?» — дело движется само.';
COMMENT ON COLUMN crm.deals.ball IS 'У кого мяч — текстом, как было в Notion. Живой мяч — у задач.';
COMMENT ON COLUMN crm.deals.next_step IS 'Следующий шаг текстом (из Notion). Живой следующий шаг — открытая задача с этой сделкой.';
COMMENT ON COLUMN crm.deals.my_view IS '«Статус как я вижу» — субъективная оценка Саши.';
COMMENT ON COLUMN crm.deals.log IS 'Хронология «[ДД.ММ] что произошло» из Notion. Новое — в crm.interactions.';
CREATE INDEX deals_project ON crm.deals (project_id);

CREATE TABLE crm.project_access (
    project_id uuid NOT NULL REFERENCES crm.projects ON DELETE CASCADE,
    user_id    text NOT NULL REFERENCES crm.users,
    role       text NOT NULL CHECK (role IN ('view', 'edit')),
    granted_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    rev        integer NOT NULL DEFAULT 1,
    seq        bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id, user_id)
);
COMMENT ON TABLE crm.project_access IS 'Кому ещё открыт проект. По умолчанию проект виден только владельцу. Приватность — на уровне проекта, не задачи.';

-- Снятый доступ: телефону и вебу надо узнать, что видимое сузилось.
CREATE TABLE crm.access_revoked (
    user_id    text NOT NULL REFERENCES crm.users,
    project_id uuid NOT NULL,
    seq        bigint NOT NULL,
    at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX access_revoked_user ON crm.access_revoked (user_id, seq);

CREATE TABLE crm.interactions (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    at         timestamptz NOT NULL,
    kind       text NOT NULL CHECK (kind IN ('call', 'meeting', 'zoom', 'telegram', 'email', 'whatsapp', 'note', 'other')),
    summary    text NOT NULL CHECK (btrim(summary) <> ''),
    next_step  text,
    project_id uuid REFERENCES crm.projects,
    deal_id    uuid REFERENCES crm.deals,
    person_ids uuid[] NOT NULL DEFAULT '{}',
    source     text NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'meeting', 'digest', 'userbot', 'phone', 'raznoska', 'import')),
    source_ref text,
    owner_id   text NOT NULL REFERENCES crm.users,
    notion_id  text UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    rev        integer NOT NULL DEFAULT 1,
    seq        bigint NOT NULL DEFAULT 0
);
COMMENT ON TABLE crm.interactions IS 'Хронология: звонки, встречи, переписка, заметки («не дела» из Разноски). Бывший «Лог взаимодействий» Notion.';

-- ── Дела ────────────────────────────────────────────────────────────────

CREATE SEQUENCE tasks.task_num;

CREATE TABLE tasks.labels (
    name       text PRIMARY KEY CHECK (name ~ '^[^\s,#@]{1,40}$'),
    color      text,
    created_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE tasks.labels IS 'Только свободные контексты («звонок»). Люди, мяч, деньги и оценка — поля задачи, не метки.';

CREATE TABLE tasks.tasks (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    num           integer NOT NULL UNIQUE DEFAULT nextval('tasks.task_num'),
    title         text NOT NULL CHECK (btrim(title) <> ''),
    notes         text,
    project_id    uuid REFERENCES crm.projects,
    deal_id       uuid REFERENCES crm.deals,
    owner_id      text NOT NULL REFERENCES crm.users,
    ball          text NOT NULL DEFAULT 'mine' CHECK (ball IN ('mine', 'waiting', 'agenda')),
    person_id     uuid REFERENCES crm.people,
    waiting_since date,
    nudge_on      date,
    requested_by  uuid REFERENCES crm.people,
    due_date      date,
    due_time      time,
    estimate_min  integer CHECK (estimate_min > 0),
    money         text CHECK (money IN ('paid', 'potential', 'none')),
    want          boolean NOT NULL DEFAULT false,
    focus_on      date,
    labels        text[] NOT NULL DEFAULT '{}',
    status        text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'done', 'cancelled')),
    source        text NOT NULL DEFAULT 'manual'
                  CHECK (source IN ('manual', 'voice', 'web', 'bot', 'telegram', 'meeting', 'mcp', 'import')),
    source_ref    text,
    created_by    text NOT NULL REFERENCES crm.users,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    completed_at  timestamptz,
    import_ref    text UNIQUE,
    rev           integer NOT NULL DEFAULT 1,
    seq           bigint NOT NULL DEFAULT 0,
    search        tsvector GENERATED ALWAYS AS (
                      to_tsvector('russian', coalesce(title, '') || ' ' || coalesce(notes, ''))
                  ) STORED,
    CHECK (due_time IS NULL OR due_date IS NOT NULL),
    CHECK ((status = 'open') = (completed_at IS NULL))
);
COMMENT ON TABLE tasks.tasks IS 'Дела. Формат названия — «Кто: действие». Пустой проект — «Входящие» владельца.';
COMMENT ON COLUMN tasks.tasks.num IS 'Короткий номер «#57»: им дело называют голосом, в боте и в Claude.';
COMMENT ON COLUMN tasks.tasks.ball IS 'mine — моё; waiting — мяч у человека person_id; agenda — поднять при следующем контакте с person_id.';
COMMENT ON COLUMN tasks.tasks.waiting_since IS 'Ставится само при переходе в waiting.';
COMMENT ON COLUMN tasks.tasks.nudge_on IS 'Когда пора напомнить тому, у кого мяч.';
COMMENT ON COLUMN tasks.tasks.money IS 'Пусто — как у проекта (money_default).';
COMMENT ON COLUMN tasks.tasks.want IS '«Хочу сам» — не потому, что попросили.';
COMMENT ON COLUMN tasks.tasks.focus_on IS 'Дело в «Сейчас» на эти сутки; на следующие гаснет само.';
COMMENT ON COLUMN tasks.tasks.import_ref IS 'id задачи в Todoist (todoist:<id>).';

CREATE INDEX tasks_open ON tasks.tasks (owner_id, status) WHERE status = 'open';
CREATE INDEX tasks_project ON tasks.tasks (project_id);
CREATE INDEX tasks_person ON tasks.tasks (person_id) WHERE person_id IS NOT NULL;
CREATE INDEX tasks_seq ON tasks.tasks (seq);
CREATE INDEX tasks_search ON tasks.tasks USING gin (search);
CREATE INDEX tasks_title_trgm ON tasks.tasks USING gin (lower(title) gin_trgm_ops);

CREATE TABLE tasks.comments (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id    uuid NOT NULL REFERENCES tasks.tasks,
    author_id  text NOT NULL REFERENCES crm.users,
    text       text NOT NULL CHECK (btrim(text) <> ''),
    deleted_at timestamptz,
    import_ref text UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    rev        integer NOT NULL DEFAULT 1,
    seq        bigint NOT NULL DEFAULT 0
);
CREATE INDEX comments_task ON tasks.comments (task_id);

CREATE TABLE tasks.suggestions (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    for_user       text NOT NULL REFERENCES crm.users,
    kind           text NOT NULL CHECK (kind IN ('create', 'close', 'update', 'assign')),
    task_id        uuid REFERENCES tasks.tasks,
    payload        jsonb NOT NULL DEFAULT '{}',
    source         text NOT NULL CHECK (source IN ('meeting', 'telegram', 'userbot', 'bot', 'import', 'phone', 'mcp', 'user')),
    source_ref     text,
    quote          text,
    batch_ref      text,
    batch_title    text,
    dup_of         uuid[] NOT NULL DEFAULT '{}',
    status         text NOT NULL DEFAULT 'pending'
                   CHECK (status IN ('pending', 'accepted', 'edited', 'rejected', 'expired')),
    reason         text,
    decided_by     text REFERENCES crm.users,
    decided_at     timestamptz,
    result_task_id uuid REFERENCES tasks.tasks,
    result         jsonb,
    expires_at     timestamptz,
    created_by     text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    rev            integer NOT NULL DEFAULT 1,
    seq            bigint NOT NULL DEFAULT 0,
    CHECK (kind = 'create' OR task_id IS NOT NULL),
    CHECK ((status = 'pending') = (decided_at IS NULL))
);
COMMENT ON TABLE tasks.suggestions IS '«Новое»: что предложила автоматика (встречи, Telegram, боты) или человек другому человеку. Пока не принято — задачи нет. Отклонённое и поправленное остаётся с причиной: материал для промптов.';
COMMENT ON COLUMN tasks.suggestions.kind IS 'create — новое дело; close — предложить закрыть («отправил»); update — поправить; assign — предложить дело пользователю (Марианне — только с её согласия).';
COMMENT ON COLUMN tasks.suggestions.batch_ref IS 'Встреча или прогон юзербота: предложения одной встречи принимают пачкой.';
COMMENT ON COLUMN tasks.suggestions.result IS 'Что сохранено на деле. Разница с payload — поправка владельца.';
CREATE INDEX suggestions_pending ON tasks.suggestions (for_user, status) WHERE status = 'pending';

-- ── Вход и служебное (только в контексте system) ───────────────────────

CREATE TABLE crm.tokens (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      text NOT NULL REFERENCES crm.users,
    kind         text NOT NULL CHECK (kind IN ('device', 'service')),
    name         text NOT NULL,
    token_hash   text NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz,
    revoked_at   timestamptz
);
COMMENT ON TABLE crm.tokens IS 'Токены телефонов и служб. Хранится только sha256.';

CREATE TABLE crm.sessions (
    token_hash   text PRIMARY KEY,
    user_id      text NOT NULL REFERENCES crm.users,
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    last_seen_at timestamptz,
    user_agent   text
);

CREATE TABLE crm.login_requests (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    poll_hash    text NOT NULL UNIQUE,
    code         text NOT NULL,
    user_id      text REFERENCES crm.users,
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    confirmed_at timestamptz,
    used_at      timestamptz
);
COMMENT ON TABLE crm.login_requests IS 'Вход в веб через бота: сайт ждёт, бот у человека в Telegram подтверждает.';

CREATE TABLE crm.passkeys (
    credential_id text PRIMARY KEY,
    user_id       text NOT NULL REFERENCES crm.users,
    public_key    bytea NOT NULL,
    sign_count    bigint NOT NULL DEFAULT 0,
    name          text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_used_at  timestamptz
);

CREATE TABLE crm.ops_seen (
    op_id   uuid PRIMARY KEY,
    user_id text NOT NULL,
    at      timestamptz NOT NULL DEFAULT now(),
    result  jsonb NOT NULL
);
COMMENT ON TABLE crm.ops_seen IS 'Операции офлайн-очереди, уже применённые: повтор того же op_id отдаёт прежний ответ. Чистится через 30 дней.';

CREATE TABLE crm.state (
    key        text PRIMARY KEY,
    value      jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE crm.state IS 'Состояние служебных заданий: зеркало Todoist, напоминания, ночная копия.';

-- ── Журнал ──────────────────────────────────────────────────────────────

CREATE TABLE crm.history (
    id        bigserial PRIMARY KEY,
    at        timestamptz NOT NULL DEFAULT now(),
    actor     text NOT NULL,
    via       text NOT NULL,
    entity    text NOT NULL,
    entity_id text NOT NULL,
    op        text NOT NULL CHECK (op IN ('insert', 'update', 'delete')),
    before    jsonb,
    after     jsonb
);
COMMENT ON TABLE crm.history IS 'Журнал всех изменений справочника и дел: кто, когда, откуда, что было и что стало. У правки — только изменившиеся поля. Пишет триггер.';
CREATE INDEX history_entity ON crm.history (entity, entity_id, at);
CREATE INDEX history_at ON crm.history (at);

-- Версия и номер изменения. SECURITY DEFINER: последовательность принадлежит
-- владельцу, роли службы к ней доступ не нужен.
CREATE FUNCTION crm.stamp() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF to_jsonb(NEW) - '{rev,seq,updated_at,search}'::text[] = to_jsonb(OLD) - '{rev,seq,updated_at,search}'::text[] THEN
            RETURN NEW;  -- ничего не поменялось: версию не трогаем
        END IF;
        NEW.rev := OLD.rev + 1;
    ELSE
        NEW.rev := 1;
    END IF;
    NEW.updated_at := now();
    NEW.seq := nextval('crm.change_seq');
    RETURN NEW;
END $$;

-- Правила дела, которые не должны зависеть от клиента.
CREATE FUNCTION tasks.task_rules() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Чьё дело — тот должен видеть его проект, иначе дело пропадёт у хозяина.
    IF NEW.project_id IS NOT NULL AND NOT crm.sees_project(NEW.owner_id, NEW.project_id) THEN
        RAISE EXCEPTION 'Дела: % не видит проект этого дела — сначала открыть ему проект', NEW.owner_id;
    END IF;
    -- Кто просил согласия (Марианна), тому дело ставится только предложением:
    -- принимает он сам, и тогда владелец меняется уже от его имени.
    IF (TG_OP = 'INSERT' OR NEW.owner_id IS DISTINCT FROM OLD.owner_id)
       AND NEW.owner_id IS DISTINCT FROM crm.me() AND crm.me() IS DISTINCT FROM 'system'
       AND (SELECT u.settings ->> 'assign' FROM crm.users u WHERE u.id = NEW.owner_id) = 'ask' THEN
        RAISE EXCEPTION 'Дела: дело на % ставится только с согласия — через предложение', NEW.owner_id;
    END IF;
    IF NEW.deal_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM crm.deals d WHERE d.id = NEW.deal_id AND d.project_id IS NOT DISTINCT FROM NEW.project_id) THEN
        RAISE EXCEPTION 'Дела: сделка дела должна быть из его проекта';
    END IF;
    IF NEW.ball = 'waiting' AND NEW.waiting_since IS NULL
       AND (TG_OP = 'INSERT' OR OLD.ball <> 'waiting') THEN
        NEW.waiting_since := crm.today();
    END IF;
    IF NEW.ball <> 'waiting' THEN
        NEW.waiting_since := NULL;
    END IF;
    IF NEW.status <> 'open' AND NEW.completed_at IS NULL THEN
        NEW.completed_at := now();
    ELSIF NEW.status = 'open' THEN
        NEW.completed_at := NULL;
    END IF;
    NEW.labels := ARRAY(SELECT DISTINCT unnest(NEW.labels) ORDER BY 1);
    RETURN NEW;
END $$;

-- Журнал. Аргументы триггера — ключевые колонки, если у таблицы нет id.
CREATE FUNCTION crm.journal() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
DECLARE
    a text := crm.actor();
    noise text[] := '{rev,seq,updated_at,search}';
    o jsonb;
    n jsonb;
    b jsonb := '{}';
    c jsonb := '{}';
    k text;
    key_id text;
BEGIN
    IF a IS NULL THEN
        RAISE EXCEPTION 'Дела: изменение без dela.actor — некому записать в журнал (%.%)', TG_TABLE_SCHEMA, TG_TABLE_NAME;
    END IF;
    o := CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) - noise END;
    n := CASE WHEN TG_OP <> 'DELETE' THEN to_jsonb(NEW) - noise END;
    IF TG_OP = 'INSERT' THEN
        n := jsonb_strip_nulls(n);  -- при создании пустые поля журналу не нужны
    END IF;
    IF TG_NARGS = 0 THEN
        key_id := coalesce(n, o) ->> 'id';
    ELSE
        SELECT string_agg(coalesce(n, o) ->> arg, ':') INTO key_id FROM unnest(TG_ARGV) AS arg;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        FOR k IN SELECT jsonb_object_keys(n) LOOP
            IF n -> k IS DISTINCT FROM o -> k THEN
                b := b || jsonb_build_object(k, o -> k);
                c := c || jsonb_build_object(k, n -> k);
            END IF;
        END LOOP;
        IF c = '{}' THEN
            RETURN NULL;
        END IF;
        o := b;
        n := c;
    END IF;
    INSERT INTO crm.history (actor, via, entity, entity_id, op, before, after)
    VALUES (a, crm.via(), TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME, key_id, lower(TG_OP), o, n);
    RETURN NULL;
END $$;

-- Снятый доступ — в список, чтобы синк отдал «пересобери всё».
CREATE FUNCTION crm.access_gone() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    INSERT INTO crm.access_revoked (user_id, project_id, seq)
    VALUES (OLD.user_id, OLD.project_id, nextval('crm.change_seq'));
    RETURN NULL;
END $$;

CREATE TRIGGER rules BEFORE INSERT OR UPDATE ON tasks.tasks FOR EACH ROW EXECUTE FUNCTION tasks.task_rules();

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['crm.users', 'crm.orgs', 'crm.people', 'crm.projects', 'crm.deals', 'crm.interactions',
                             'tasks.tasks', 'tasks.comments', 'tasks.suggestions'] LOOP
        EXECUTE format('CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON %s FOR EACH ROW EXECUTE FUNCTION crm.stamp()', t);
        EXECUTE format('CREATE TRIGGER journal AFTER INSERT OR UPDATE OR DELETE ON %s FOR EACH ROW EXECUTE FUNCTION crm.journal()', t);
    END LOOP;
END $$;

CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON crm.project_access FOR EACH ROW EXECUTE FUNCTION crm.stamp();
CREATE TRIGGER journal AFTER INSERT OR UPDATE OR DELETE ON crm.project_access
    FOR EACH ROW EXECUTE FUNCTION crm.journal('project_id', 'user_id');
CREATE TRIGGER gone AFTER DELETE ON crm.project_access FOR EACH ROW EXECUTE FUNCTION crm.access_gone();
CREATE TRIGGER journal AFTER INSERT OR UPDATE OR DELETE ON tasks.labels
    FOR EACH ROW EXECUTE FUNCTION crm.journal('name');

-- ── Видимость ───────────────────────────────────────────────────────────
-- Функции видимости — SECURITY DEFINER: смотрят в таблицы мимо RLS, иначе
-- политика задач, спросив о проекте, упёрлась бы в политику проектов.

CREATE FUNCTION crm.sees_project(u text, p uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT u = 'system' OR EXISTS (
        SELECT 1 FROM crm.projects pr
        WHERE pr.id = p AND (pr.owner_id = u OR EXISTS (
            SELECT 1 FROM crm.project_access a WHERE a.project_id = p AND a.user_id = u)))
$$;

CREATE FUNCTION crm.edits_project(u text, p uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT u = 'system' OR EXISTS (
        SELECT 1 FROM crm.projects pr
        WHERE pr.id = p AND (pr.owner_id = u OR EXISTS (
            SELECT 1 FROM crm.project_access a WHERE a.project_id = p AND a.user_id = u AND a.role = 'edit')))
$$;

-- Задачу видно: «Входящие» — владельцу, остальное — кто видит проект.
CREATE FUNCTION crm.sees_task(u text, project uuid, owner text) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT u = 'system'
        OR (project IS NULL AND owner = u)
        OR (project IS NOT NULL AND crm.sees_project(u, project))
$$;

CREATE FUNCTION crm.edits_task(u text, project uuid, owner text) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT u = 'system'
        OR (project IS NULL AND owner = u)
        OR (project IS NOT NULL AND crm.edits_project(u, project))
$$;

CREATE FUNCTION crm.sees_task_id(u text, t uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM tasks.tasks x WHERE x.id = t AND crm.sees_task(u, x.project_id, x.owner_id))
$$;

-- Человек связан с тем, что видит u: делом, проектом, или он — пользователь Дел.
-- Владельца и самого человека политика проверяет по строке: функция смотрит
-- только в другие таблицы (новую строку своей таблицы она бы не увидела).
CREATE FUNCTION crm.person_linked(u text, p uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM tasks.tasks t
        WHERE (t.person_id = p OR t.requested_by = p) AND crm.sees_task(u, t.project_id, t.owner_id))
    OR EXISTS (
        SELECT 1 FROM crm.deals d WHERE (d.lead_person_id = p OR p = ANY (d.person_ids)) AND crm.sees_project(u, d.project_id))
    OR EXISTS (
        SELECT 1 FROM crm.users us WHERE us.person_id = p)
$$;

CREATE FUNCTION crm.sees_person(u text, p uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT u = 'system'
        OR EXISTS (SELECT 1 FROM crm.people pe WHERE pe.id = p AND u IN (pe.owner_id, pe.user_id))
        OR crm.person_linked(u, p)
$$;

-- Доступ к проекту по строке доступа (без чтения самого проекта).
CREATE FUNCTION crm.has_access(u text, p uuid, edit boolean) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM crm.project_access a
                   WHERE a.project_id = p AND a.user_id = u AND (NOT edit OR a.role = 'edit'))
$$;

CREATE FUNCTION crm.org_linked(u text, o uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM crm.projects pr WHERE pr.org_id = o AND crm.sees_project(u, pr.id))
$$;

CREATE FUNCTION crm.sees_org(u text, o uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT u = 'system' OR EXISTS (SELECT 1 FROM crm.orgs og WHERE og.id = o AND og.owner_id = u)
        OR EXISTS (SELECT 1 FROM crm.projects pr WHERE pr.org_id = o AND crm.sees_project(u, pr.id))
$$;

-- ── Row-level security для роли службы ─────────────────────────────────

ALTER TABLE crm.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.orgs ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.people ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.deals ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.project_access ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.access_revoked ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.interactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks.labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks.tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks.comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks.suggestions ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.login_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.passkeys ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.ops_seen ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.state ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.history ENABLE ROW LEVEL SECURITY;

CREATE POLICY see ON crm.users FOR SELECT USING (crm.me() IS NOT NULL);
CREATE POLICY own ON crm.users FOR UPDATE USING (crm.me() IN (id, 'system'));
CREATE POLICY sys ON crm.users FOR INSERT WITH CHECK (crm.me() = 'system');

CREATE POLICY see ON crm.orgs FOR SELECT USING (crm.me() IN (owner_id, 'system') OR crm.org_linked(crm.me(), id));
CREATE POLICY ins ON crm.orgs FOR INSERT WITH CHECK (crm.me() IN (owner_id, 'system'));
CREATE POLICY upd ON crm.orgs FOR UPDATE USING (crm.me() IN (owner_id, 'system'));

CREATE POLICY see ON crm.people FOR SELECT
    USING (crm.me() IN (owner_id, user_id, 'system') OR crm.person_linked(crm.me(), id));
CREATE POLICY ins ON crm.people FOR INSERT WITH CHECK (crm.me() IN (owner_id, 'system'));
CREATE POLICY upd ON crm.people FOR UPDATE USING (crm.me() IN (owner_id, user_id, 'system'));

CREATE POLICY see ON crm.projects FOR SELECT
    USING (crm.me() IN (owner_id, 'system') OR crm.has_access(crm.me(), id, false));
CREATE POLICY ins ON crm.projects FOR INSERT WITH CHECK (crm.me() IN (owner_id, 'system'));
CREATE POLICY upd ON crm.projects FOR UPDATE
    USING (crm.me() IN (owner_id, 'system') OR crm.has_access(crm.me(), id, true))
    WITH CHECK (crm.me() IN (owner_id, 'system') OR crm.has_access(crm.me(), id, true));

CREATE POLICY see ON crm.deals FOR SELECT USING (crm.sees_project(crm.me(), project_id));
CREATE POLICY ins ON crm.deals FOR INSERT WITH CHECK (crm.edits_project(crm.me(), project_id));
CREATE POLICY upd ON crm.deals FOR UPDATE USING (crm.edits_project(crm.me(), project_id))
    WITH CHECK (crm.edits_project(crm.me(), project_id));

CREATE POLICY see ON crm.project_access FOR SELECT
    USING (crm.me() IN (user_id, 'system') OR EXISTS (
        SELECT 1 FROM crm.projects p WHERE p.id = project_id AND p.owner_id = crm.me()));
CREATE POLICY grant_ ON crm.project_access FOR ALL
    USING (crm.me() = 'system' OR EXISTS (
        SELECT 1 FROM crm.projects p WHERE p.id = project_id AND p.owner_id = crm.me()))
    WITH CHECK (crm.me() = 'system' OR EXISTS (
        SELECT 1 FROM crm.projects p WHERE p.id = project_id AND p.owner_id = crm.me()));

CREATE POLICY see ON crm.access_revoked FOR SELECT USING (crm.me() IN (user_id, 'system'));

CREATE POLICY see ON crm.interactions FOR SELECT
    USING (crm.me() IN (owner_id, 'system') OR (project_id IS NOT NULL AND crm.sees_project(crm.me(), project_id)));
CREATE POLICY ins ON crm.interactions FOR INSERT
    WITH CHECK (crm.me() IN (owner_id, 'system') AND (project_id IS NULL OR crm.sees_project(crm.me(), project_id)));
CREATE POLICY upd ON crm.interactions FOR UPDATE USING (crm.me() IN (owner_id, 'system'));

CREATE POLICY see ON tasks.labels FOR SELECT USING (crm.me() IS NOT NULL);
CREATE POLICY ins ON tasks.labels FOR INSERT WITH CHECK (crm.me() IS NOT NULL);

CREATE POLICY see ON tasks.tasks FOR SELECT USING (crm.sees_task(crm.me(), project_id, owner_id));
CREATE POLICY ins ON tasks.tasks FOR INSERT
    WITH CHECK (crm.edits_task(crm.me(), project_id, owner_id) AND crm.me() IN (created_by, 'system'));
CREATE POLICY upd ON tasks.tasks FOR UPDATE
    USING (crm.edits_task(crm.me(), project_id, owner_id))
    WITH CHECK (crm.edits_task(crm.me(), project_id, owner_id));

CREATE POLICY see ON tasks.comments FOR SELECT USING (crm.sees_task_id(crm.me(), task_id));
CREATE POLICY ins ON tasks.comments FOR INSERT
    WITH CHECK (crm.me() IN (author_id, 'system') AND crm.sees_task_id(crm.me(), task_id));
CREATE POLICY upd ON tasks.comments FOR UPDATE USING (crm.me() IN (author_id, 'system'));

-- Автор видит своё предложение: принял ли его тот, кому оно адресовано.
CREATE POLICY see ON tasks.suggestions FOR SELECT USING (crm.me() IN (for_user, created_by, 'system'));
CREATE POLICY ins ON tasks.suggestions FOR INSERT WITH CHECK (crm.me() IS NOT NULL);
CREATE POLICY upd ON tasks.suggestions FOR UPDATE USING (crm.me() IN (for_user, 'system'));

CREATE POLICY sys ON crm.tokens FOR ALL USING (crm.me() = 'system') WITH CHECK (crm.me() = 'system');
CREATE POLICY sys ON crm.sessions FOR ALL USING (crm.me() = 'system') WITH CHECK (crm.me() = 'system');
CREATE POLICY sys ON crm.login_requests FOR ALL USING (crm.me() = 'system') WITH CHECK (crm.me() = 'system');
CREATE POLICY sys ON crm.passkeys FOR ALL USING (crm.me() = 'system') WITH CHECK (crm.me() = 'system');
CREATE POLICY own ON crm.ops_seen FOR ALL USING (crm.me() IN (user_id, 'system')) WITH CHECK (crm.me() IN (user_id, 'system'));
CREATE POLICY sys ON crm.state FOR ALL USING (crm.me() = 'system') WITH CHECK (crm.me() = 'system');
CREATE POLICY sys ON crm.history FOR SELECT USING (crm.me() = 'system');

-- ── Виды для службы (RLS работает: security_invoker) ────────────────────

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
       (t.focus_on = crm.today()) AS now_
FROM tasks.tasks t
LEFT JOIN crm.projects p ON p.id = t.project_id
LEFT JOIN crm.deals d ON d.id = t.deal_id
LEFT JOIN crm.people pe ON pe.id = t.person_id
LEFT JOIN crm.people rq ON rq.id = t.requested_by;
COMMENT ON VIEW tasks.v_tasks IS 'Задача с наследованными деньгами, сферой проекта и именами людей.';

-- Сопоставление свободного текста (клиент в ленте, проект в наговоре) со справочником.
CREATE FUNCTION crm.norm(s text) RETURNS text LANGUAGE sql IMMUTABLE AS
$$ SELECT nullif(regexp_replace(lower(replace(btrim(s), 'ё', 'е')), '\s+', ' ', 'g'), '') $$;

CREATE FUNCTION crm.named(n text, name text, aliases text[], extra text DEFAULT NULL) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
    SELECT n IS NOT NULL AND (n = crm.norm(name) OR n = crm.norm(extra)
        OR n = ANY (ARRAY(SELECT crm.norm(a) FROM unnest(aliases) a)))
$$;

-- Что за клиент записан текстом: проект, организация, человек. Человек тянет
-- свою организацию, организация с единственным живым проектом — этот проект.
-- Двусмысленное (два проекта на одно имя) остаётся пустым: решает владелец.
CREATE FUNCTION crm.match_name(s text, OUT project_id uuid, OUT org_id uuid, OUT person_id uuid)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = crm, pg_temp AS $$
DECLARE
    n text := crm.norm(s);
    ids uuid[];
BEGIN
    IF n IS NULL THEN
        RETURN;
    END IF;
    ids := ARRAY(SELECT p.id FROM crm.projects p WHERE p.archived_at IS NULL AND crm.named(n, p.name, p.aliases));
    IF cardinality(ids) = 1 THEN
        project_id := ids[1];
        SELECT p.org_id INTO org_id FROM crm.projects p WHERE p.id = project_id;
        RETURN;
    END IF;
    ids := ARRAY(SELECT pe.id FROM crm.people pe WHERE pe.archived_at IS NULL AND crm.named(n, pe.name, pe.aliases, pe.short));
    IF cardinality(ids) = 1 THEN
        person_id := ids[1];
        SELECT pe.org_id INTO org_id FROM crm.people pe WHERE pe.id = person_id;
    ELSE
        ids := ARRAY(SELECT o.id FROM crm.orgs o WHERE o.archived_at IS NULL AND crm.named(n, o.name, o.aliases));
        IF cardinality(ids) = 1 THEN
            org_id := ids[1];
        END IF;
    END IF;
    IF org_id IS NOT NULL THEN
        ids := ARRAY(SELECT p.id FROM crm.projects p WHERE p.org_id = match_name.org_id AND p.archived_at IS NULL);
        IF cardinality(ids) = 1 THEN
            project_id := ids[1];
        END IF;
    END IF;
END $$;

-- Владелец Дел (тот же человек, чей архив).
CREATE FUNCTION crm.owner_id() RETURNS text LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$ SELECT id FROM crm.users WHERE role = 'owner' ORDER BY id LIMIT 1 $$;

-- Часы проекта из ленты Засечки (архив владельца): только суммы по суткам,
-- без текстов. Лента — одного человека, поэтому и ответ — только ему.
-- plpgsql и EXECUTE: схему life архив пересобирает целиком, ссылка в теле
-- не должна держать её и не должна падать, если архива в базе нет.
CREATE FUNCTION crm.project_minutes(p uuid) RETURNS TABLE (day date, minutes bigint)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = crm, pg_temp AS $$
BEGIN
    IF crm.me() IS DISTINCT FROM crm.owner_id() OR to_regclass('life.work_time') IS NULL THEN
        RETURN;
    END IF;
    RETURN QUERY EXECUTE
        'SELECT day, sum(minutes)::bigint FROM life.work_time WHERE project_id = $1 GROUP BY day ORDER BY day'
        USING p;
END $$;
