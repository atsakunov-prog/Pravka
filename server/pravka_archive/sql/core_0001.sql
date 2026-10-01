-- Ядро архива: журнал событий, текущее состояние записей, указатель
-- сказанного, вход Claude и свежесть источников.
--
-- Журнал (core.events) только дописывается и есть сам архив. Всё остальное
-- выводится из него и может быть пересобрано: состояние (core.records)
-- держит последнюю версию каждой записи, виды схемы life читают его.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Всё, что пришло: с телефона и из intervals. Не удаляется и не правится.
CREATE TABLE core.events (
    eid         text PRIMARY KEY,           -- «<устройство>:<seq>»; повтор не удваивает
    device      text NOT NULL,              -- sasha-3f9a2c · intervals
    seq         bigint NOT NULL,            -- растёт у устройства: порядок внутри него
    kind        text NOT NULL,              -- zasechka.day, food.meal, icu.activity, …
    key         text NOT NULL,              -- кто это внутри вида: день, номер записи
    op          text NOT NULL CHECK (op IN ('put', 'del')),
    at          timestamptz NOT NULL,       -- когда событие родилось у источника
    received_at timestamptz NOT NULL DEFAULT now(),
    schema      int NOT NULL,               -- версия контракта
    app         text,                       -- версия приложения или сборщика
    data        jsonb                       -- запись целиком (у del — пусто)
);
CREATE INDEX events_record ON core.events (kind, key, at);
CREATE INDEX events_received ON core.events (received_at);

-- Последняя версия каждой записи. Удалённая остаётся строкой с deleted = true
-- и последними известными данными: история не теряется, виды её не видят.
CREATE TABLE core.records (
    kind       text NOT NULL,
    key        text NOT NULL,
    deleted    boolean NOT NULL DEFAULT false,
    data       jsonb NOT NULL,
    hash       text NOT NULL,               -- sha256 канонического JSON: тот же снимок не плодит событий
    at         timestamptz NOT NULL,
    seq        bigint NOT NULL,
    eid        text NOT NULL,
    device     text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, key)
);
CREATE INDEX records_live ON core.records (kind) WHERE NOT deleted;

-- Всё сказанное и написанное владельцем, кусками, с русским поиском по
-- словоформам и поиском по подстроке (опечатки распознавания). Кэш вида
-- core.said_source: пересобирается по записи при каждом её изменении.
CREATE TABLE core.said_index (
    kind   text NOT NULL,
    key    text NOT NULL,
    part   text NOT NULL,                   -- raw:<id>, comment:<id>, text, question, …
    domain text NOT NULL,                   -- засечка, еда, силовые, деньги, правка, тренер, …
    day    date,
    at     timestamptz,
    time_local text,                        -- ЧЧ:ММ по поясу источника
    ref    text,                            -- номер записи, по которому её найти в видах
    text   text NOT NULL,
    tsv    tsvector GENERATED ALWAYS AS (to_tsvector('russian', text)) STORED,
    -- Слова как есть, без стеммера: имена русский стеммер режет по-разному
    -- («Марианной» → «мариа», «Марианна» → «мариан»), и поиск по началу
    -- слова (марианн:*) их ловит. ё приравнено к е.
    tsv_words tsvector GENERATED ALWAYS AS (to_tsvector('simple', replace(lower(text), 'ё', 'е'))) STORED
);
CREATE INDEX said_tsv ON core.said_index USING gin (tsv);
CREATE INDEX said_words ON core.said_index USING gin (tsv_words);
CREATE INDEX said_trgm ON core.said_index USING gin (text gin_trgm_ops);
CREATE INDEX said_record ON core.said_index (kind, key);
CREATE INDEX said_day ON core.said_index (day);

-- Вход Claude по OAuth: клиенты (регистрируются сами) и выданные токены.
-- Токены хранятся хешами: утечка таблицы не даёт входа.
CREATE TABLE core.oauth_clients (
    client_id  text PRIMARY KEY,
    info       jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE core.oauth_tokens (
    token_hash text PRIMARY KEY,
    kind       text NOT NULL CHECK (kind IN ('access', 'refresh')),
    client_id  text NOT NULL,
    scopes     text[] NOT NULL,
    resource   text,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Свежесть: когда источник последний раз прислал или отдал данные и чем
-- кончилась последняя попытка. Молчаливая механика читается как поломка.
CREATE TABLE core.sources (
    source        text PRIMARY KEY,         -- phone:<устройство> · intervals
    last_ok       timestamptz,
    last_error    text,
    last_error_at timestamptz,
    note          jsonb
);

-- Что сборщик intervals уже видел: хеш карточки из списка (изменилась —
-- тянуть подробную заново), забраны ли потоки и исходный файл.
CREATE TABLE core.icu_seen (
    activity_id  text PRIMARY KEY,
    list_hash    text NOT NULL,
    streams_done boolean NOT NULL DEFAULT false,
    file_done    boolean NOT NULL DEFAULT false,
    updated_at   timestamptz NOT NULL DEFAULT now()
);
