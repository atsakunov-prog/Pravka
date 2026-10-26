-- Дела, миграция 38 (26.10.2026): правда ленты Засечки — на сервере (docs/server-truth.md).
--
-- Владелец, увидев в вебе «Ушло телефону — возьмёт за 5 минут»: «основная база всё равно на сервере… переносим всё».
-- До сих пор лента жила в файле телефона, архив был её копией сутками, веб ставил телефону команды (dela_0016).
-- Теперь:
--   crm.zas_entries — лента: строка — кусок, те же поля, что у Entry телефона; время — мс эпохи, как у него, чтобы
--                     номера записей (тоже мс) и время сходились один к одному без перевода;
--   crm.zas_ops     — журнал операций над лентой (кто, откуда, что вышло); только дописывается;
--   crm.zas_state   — режим: phone — правда ещё на телефоне (до передачи всё как было), server — после передачи.
-- Движок — pravka_dela/ledger.py, операции — zas.py. Архив остаётся памятью: после каждой правки сервер сам кладёт
-- затронутые сутки в архив тем же видом zasechka.day (crm.zas_publish — роль службы архив не видит, правило 2), и всё,
-- что читает ленту (life.entries, поиск, «Сон», claude.ai, часы по клиентам), не меняется. Только владельцу: лента —
-- его (у другого члена семьи своя лента на своём телефоне, её это не касается).

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

CREATE TABLE crm.zas_state (
    owner_id        text PRIMARY KEY REFERENCES crm.users,
    mode            text NOT NULL DEFAULT 'phone' CHECK (mode IN ('phone', 'server')),
    handed_by       text CHECK (handed_by IS NULL OR length(handed_by) <= 80),
    handed_at       timestamptz,
    handed_entries  integer,
    clients         jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(clients) = 'array'),
    categories      jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(categories) = 'array'),
    last_id         bigint NOT NULL DEFAULT 0,
    lrev            bigint NOT NULL DEFAULT 0,
    published       jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(published) = 'object'),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE crm.zas_state IS 'Лента Засечки: где правда (phone — до передачи, server — после), когда и каким телефоном передана, справочник клиентов ленты, версия ленты lrev (телефон берёт свежее окно, когда она сменилась).';
COMMENT ON COLUMN crm.zas_state.categories IS 'Категории ленты на миг передачи — запас; правда о них — Свод zasechka.categories.';
COMMENT ON COLUMN crm.zas_state.published IS 'Сутки → отпечаток последней публикации в архив: те же сутки второй раз не кладутся.';

CREATE TABLE crm.zas_entries (
    owner_id    text NOT NULL REFERENCES crm.users,
    id          bigint NOT NULL CHECK (id > 0),
    start_ms    bigint NOT NULL CHECK (start_ms > 0),
    end_ms      bigint NOT NULL DEFAULT 0 CHECK (end_ms = 0 OR end_ms >= start_ms),
    raw         text NOT NULL DEFAULT '',
    title       text NOT NULL DEFAULT '',
    category    text NOT NULL DEFAULT '',
    client      text NOT NULL DEFAULT '',
    useful      smallint NOT NULL DEFAULT 0 CHECK (useful BETWEEN 0 AND 5),
    source      text NOT NULL DEFAULT 'voice' CHECK (length(source) <= 20),
    created_ms  bigint NOT NULL DEFAULT 0,
    pomodoros   integer NOT NULL DEFAULT 0,
    comment     text NOT NULL DEFAULT '',
    task        text NOT NULL DEFAULT '',
    project     text NOT NULL DEFAULT '',
    who         text NOT NULL DEFAULT '',  -- поле person записи телефона: с кем, словами; не ссылка на crm.people (слияние людей его не трогает)
    day         date NOT NULL,
    PRIMARY KEY (owner_id, id)
);
COMMENT ON TABLE crm.zas_entries IS 'Лента Засечки владельца — правда (с 26.10.2026). Кусок: start_ms…end_ms (мс эпохи; end_ms = 0 — идёт), day — московские сутки начала. Правила ленты — ledger.py, пишет только zas.py.';
CREATE INDEX zas_entries_start ON crm.zas_entries (owner_id, start_ms);
CREATE INDEX zas_entries_day ON crm.zas_entries (owner_id, day);

CREATE TABLE crm.zas_ops (
    n           bigserial PRIMARY KEY,
    owner_id    text NOT NULL REFERENCES crm.users,
    kind        text NOT NULL,
    args        jsonb NOT NULL DEFAULT '{}',
    at          timestamptz,
    device      text,
    via         text,
    result      jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE crm.zas_ops IS 'Журнал операций над лентой Засечки: что просили (kind, args), когда на устройстве (at), откуда (device, via), что вышло. Только дописывается — «куда делась моя запись» читается здесь.';
CREATE INDEX zas_ops_owner ON crm.zas_ops (owner_id, created_at);

CREATE FUNCTION crm.zas_ops_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Дела: журнал операций ленты только дописывается';
END $$;
CREATE TRIGGER append_only BEFORE UPDATE OR DELETE ON crm.zas_ops FOR EACH ROW EXECUTE FUNCTION crm.zas_ops_append_only();

CREATE FUNCTION crm.zas_rules() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM crm.users u WHERE u.id = NEW.owner_id AND u.role = 'owner') THEN
        RAISE EXCEPTION 'Дела: лента Засечки — только владельцу Дел, а % не владелец', NEW.owner_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER rules BEFORE INSERT OR UPDATE ON crm.zas_state FOR EACH ROW EXECUTE FUNCTION crm.zas_rules();
CREATE TRIGGER rules BEFORE INSERT OR UPDATE ON crm.zas_entries FOR EACH ROW EXECUTE FUNCTION crm.zas_rules();

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['crm.zas_state', 'crm.zas_entries', 'crm.zas_ops'] LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY own ON %s FOR ALL USING (crm.me() IN (owner_id, ''system'')) '
                       'WITH CHECK (crm.me() IN (owner_id, ''system''))', t);
    END LOOP;
END $$;

-- ── Публикация суток в архив ───────────────────────────────────────────────
-- Роль службы Дел архив не видит (правило 2: служба смотрит в интернет). Положить сутки ленты — одна функция,
-- SECURITY DEFINER, как crm.life_rows для чтения: только виды zasechka.day и zasechka.reference, только хозяину архива
-- (person ''), от устройства svc-zasechka. Делает то же, что ingest архива (_store_one + refresh_said): событие в
-- журнал, последняя версия — в core.records, указатель сказанного по записи. Тот же снимок второй раз — ничего.
-- Архива в базе нет (тестовая база Дел) — false.
CREATE FUNCTION crm.zas_publish(p_kind text, p_key text, p_data jsonb, p_del boolean DEFAULT false) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = crm, pg_temp AS $$
DECLARE
    h text;
    cur record;
    n bigint;
    eid text;
    ts timestamptz := clock_timestamp();
BEGIN
    IF crm.me() IS DISTINCT FROM crm.owner_id() AND crm.me() <> 'system' THEN
        RAISE EXCEPTION 'Дела: ленту в архив кладёт только владелец' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF p_kind NOT IN ('zasechka.day', 'zasechka.reference') THEN
        RAISE EXCEPTION 'Дела: в архив отсюда — только лента, а не %', p_kind;
    END IF;
    IF to_regclass('core.records') IS NULL OR to_regclass('core.events') IS NULL THEN
        RETURN false;
    END IF;
    h := CASE WHEN p_del THEN 'del' ELSE encode(sha256(convert_to(p_data::text, 'UTF8')), 'hex') END;
    EXECUTE 'SELECT hash, deleted FROM core.records WHERE person = '''' AND kind = $1 AND key = $2 FOR UPDATE'
        INTO cur USING p_kind, p_key;
    IF cur IS NOT NULL AND ((p_del AND cur.deleted) OR (NOT p_del AND NOT cur.deleted AND cur.hash = h)) THEN
        RETURN false;
    END IF;
    n := (extract(epoch FROM ts) * 1000000)::bigint;
    eid := 'svc-zasechka:' || n;
    EXECUTE 'INSERT INTO core.events (eid, device, person, seq, kind, key, op, at, schema, app, data) '
            'VALUES ($1, ''svc-zasechka'', '''', $2, $3, $4, $5, $6, 1, ''dela'', $7) ON CONFLICT (eid) DO NOTHING'
        USING eid, n, p_kind, p_key, CASE WHEN p_del THEN 'del' ELSE 'put' END, ts,
              CASE WHEN p_del THEN NULL ELSE p_data END;
    IF p_del THEN
        EXECUTE 'INSERT INTO core.records (person, kind, key, deleted, data, hash, at, seq, eid, device, updated_at) '
                'VALUES ('''', $1, $2, true, ''{}''::jsonb, ''del'', $3, $4, $5, ''svc-zasechka'', now()) '
                'ON CONFLICT (person, kind, key) DO UPDATE SET deleted = true, hash = ''del'', at = EXCLUDED.at, '
                'seq = EXCLUDED.seq, eid = EXCLUDED.eid, device = EXCLUDED.device, updated_at = now()'
            USING p_kind, p_key, ts, n, eid;
    ELSE
        EXECUTE 'INSERT INTO core.records (person, kind, key, deleted, data, hash, at, seq, eid, device, updated_at) '
                'VALUES ('''', $1, $2, false, $3, $4, $5, $6, $7, ''svc-zasechka'', now()) '
                'ON CONFLICT (person, kind, key) DO UPDATE SET deleted = false, data = EXCLUDED.data, '
                'hash = EXCLUDED.hash, at = EXCLUDED.at, seq = EXCLUDED.seq, eid = EXCLUDED.eid, '
                'device = EXCLUDED.device, updated_at = now()'
            USING p_kind, p_key, p_data, h, ts, n, eid;
    END IF;
    IF to_regclass('core.said_index') IS NOT NULL AND to_regclass('core.said_source') IS NOT NULL THEN
        EXECUTE 'DELETE FROM core.said_index WHERE person = '''' AND kind = $1 AND key = $2' USING p_kind, p_key;
        EXECUTE 'INSERT INTO core.said_index (person, kind, key, part, domain, day, at, time_local, ref, text) '
                'SELECT person, kind, key, part, domain, day, at, time_local, ref, text FROM core.said_source '
                'WHERE person = '''' AND kind = $1 AND key = $2' USING p_kind, p_key;
    END IF;
    IF to_regclass('core.sources') IS NOT NULL THEN
        EXECUTE 'INSERT INTO core.sources (source, last_ok) VALUES (''svc:zasechka'', now()) '
                'ON CONFLICT (source) DO UPDATE SET last_ok = now()';
    END IF;
    RETURN true;
END $$;
COMMENT ON FUNCTION crm.zas_publish(text, text, jsonb, boolean) IS
    'Сутки ленты Засечки (и её справочник) — в архив от svc-zasechka: только владельцу, только zasechka.day и zasechka.reference.';
REVOKE ALL ON FUNCTION crm.zas_publish(text, text, jsonb, boolean) FROM PUBLIC;

-- Передана ли лента владельца серверу. Спрашивает архив перед тем, как принять ленту от телефона: после передачи
-- телефон её больше не шлёт, а пачка старой сборки затёрла бы опубликованные сервером сутки.
CREATE FUNCTION crm.zas_handed() RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM crm.zas_state s WHERE s.owner_id = crm.owner_id() AND s.mode = 'server')
$$;
COMMENT ON FUNCTION crm.zas_handed() IS 'Лента Засечки владельца уже на сервере (mode = server): архив не принимает её от телефона.';
