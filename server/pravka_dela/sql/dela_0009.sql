-- Дела, миграция 9 (12.10.2026): Круг жизни — направления дел и «Зачем».
--
-- Владелец (бриф «Циферблат», 12.10.2026, и разговор того же вечера): у каждого дела — одно или несколько
-- направлений жизни, которые оно двигает («просто направление и Круг жизни, всё»). И «Зачем»: «Распечатать
-- столбики примеров — зачем? Уверенно считает до 20; а это — хорошо знает математику; а это —
-- поступить в сильную школу. То есть многоуровневые планы». Цель — обычное дело, у которого есть шаги; её
-- название и есть результат. У сделок «зачем» уже есть — сама сделка: дела в ней с ней и так связаны.
--
-- Почему отдельная таблица, а не колонки дела. Направления и «Зачем» видит только хозяин: Наташа и Марианна
-- видят его дела в общих проектах, но не метки Круга. Колонка в tasks.tasks уехала бы им синком
-- (tasks.v_tasks собран через t.*), выгрузками и журналом карточки (api.task_card читает журнал
-- tasks.tasks). Здесь строка Круга — своя, под своей политикой: чужую строку база просто не покажет.
-- Справочник направлений (названия, формулы, «входит / не входит») — запись Свода krug.directions: он
-- личный, в публичный репозиторий не идёт и телефону едет обычным синком.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

CREATE TABLE tasks.krug (
    id          uuid PRIMARY KEY,
    kind        text NOT NULL CHECK (kind IN ('task', 'project')),
    owner_id    text NOT NULL REFERENCES crm.users,
    directions  text[] NOT NULL DEFAULT '{}' CHECK (cardinality(directions) <= 4),
    why_id      uuid REFERENCES tasks.tasks,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    rev         integer NOT NULL DEFAULT 1,
    seq         bigint NOT NULL DEFAULT 0,
    CHECK (kind = 'task' OR why_id IS NULL),
    CHECK (why_id IS DISTINCT FROM id)
);
COMMENT ON TABLE tasks.krug IS 'Круг жизни: направления дела или проекта и «Зачем» дела. Видит и меняет только хозяин дела (проекта).';
COMMENT ON COLUMN tasks.krug.id IS 'id дела (kind task) или проекта (kind project).';
COMMENT ON COLUMN tasks.krug.directions IS 'Свои направления — ключи из Свода krug.directions. Пусто — наследует: от «Зачем» вверх по цепочке, потом от проекта.';
COMMENT ON COLUMN tasks.krug.why_id IS '«Зачем»: дело-цель, шагом к которой это дело является. Только своё, без кругов.';
CREATE INDEX krug_why ON tasks.krug (why_id) WHERE why_id IS NOT NULL;

-- Правила строки Круга — в базе, а не у клиента: телефон, веб, Claude и разборщик пишут одинаково.
-- SECURITY DEFINER: проверка смотрит на дело и цепочку «Зачем» мимо политик.
CREATE FUNCTION tasks.krug_rules() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = tasks, crm, pg_temp AS $$
DECLARE
    own text;
    cur uuid;
    n integer := 0;
BEGIN
    IF NEW.kind = 'task' THEN
        SELECT owner_id INTO own FROM tasks.tasks WHERE id = NEW.id;
    ELSE
        SELECT owner_id INTO own FROM crm.projects WHERE id = NEW.id;
    END IF;
    IF own IS NULL THEN
        RAISE EXCEPTION 'Дела: Круг — нет такого %', CASE NEW.kind WHEN 'task' THEN 'дела' ELSE 'проекта' END;
    END IF;
    IF own IS DISTINCT FROM NEW.owner_id THEN
        RAISE EXCEPTION 'Дела: направления и «зачем» ставит только хозяин';
    END IF;
    IF EXISTS (SELECT 1 FROM unnest(NEW.directions) x WHERE x !~ '^[a-z][a-z0-9_]{1,23}$') THEN
        RAISE EXCEPTION 'Дела: направление — ключ латиницей (health, work…)';
    END IF;
    -- Одно направление дважды — одно; порядок — как дали.
    NEW.directions := ARRAY(SELECT x FROM unnest(NEW.directions) WITH ORDINALITY AS u(x, i) GROUP BY x ORDER BY min(i));
    IF NEW.why_id IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM tasks.tasks WHERE id = NEW.why_id AND owner_id = NEW.owner_id) THEN
            RAISE EXCEPTION 'Дела: «зачем» — только своё дело';
        END IF;
        cur := NEW.why_id;
        WHILE cur IS NOT NULL LOOP
            IF cur = NEW.id THEN
                RAISE EXCEPTION 'Дела: «зачем» по кругу — дело не может быть шагом к самому себе';
            END IF;
            n := n + 1;
            IF n > 50 THEN
                RAISE EXCEPTION 'Дела: цепочка «зачем» длиннее 50';
            END IF;
            SELECT k.why_id INTO cur FROM tasks.krug k WHERE k.id = cur;
        END LOOP;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER rules BEFORE INSERT OR UPDATE ON tasks.krug FOR EACH ROW EXECUTE FUNCTION tasks.krug_rules();
CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON tasks.krug FOR EACH ROW EXECUTE FUNCTION crm.stamp();
CREATE TRIGGER journal AFTER INSERT OR UPDATE OR DELETE ON tasks.krug FOR EACH ROW EXECUTE FUNCTION crm.journal();

ALTER TABLE tasks.krug ENABLE ROW LEVEL SECURITY;
CREATE POLICY own ON tasks.krug FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));

-- Направления дела с наследованием: свои; нет — первые непустые вверх по «Зачем»; нет и там — проекта дела
-- (или проекта ближайшей цели). Только строки Круга хозяина дела: проект клиента размечен Сашей, а дело в нём
-- у Наташи — её дело направления от Саши не получает. SECURITY DEFINER — как функции видимости: её зовут и
-- виды life (роль чтения таблиц Дел не видит); кому показывать, решает вызывающий (v_tasks — хозяину дела).
CREATE FUNCTION tasks.krug_dirs(t uuid) RETURNS text[] LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = tasks, crm, pg_temp AS $$
    WITH RECURSIVE me AS (SELECT owner_id FROM tasks.tasks WHERE id = t),
    up AS (
        SELECT x.id, x.project_id, k.directions, k.why_id, 0 AS depth
        FROM tasks.tasks x LEFT JOIN tasks.krug k ON k.id = x.id AND k.owner_id = x.owner_id
        WHERE x.id = t
        UNION ALL
        SELECT x.id, x.project_id, k.directions, k.why_id, up.depth + 1
        FROM up JOIN tasks.tasks x ON x.id = up.why_id LEFT JOIN tasks.krug k ON k.id = x.id AND k.owner_id = x.owner_id
        WHERE up.depth < 50
    )
    SELECT coalesce(
        (SELECT directions FROM up WHERE cardinality(directions) > 0 ORDER BY depth LIMIT 1),
        (SELECT kp.directions FROM up JOIN tasks.krug kp ON kp.id = up.project_id
         WHERE kp.owner_id = (SELECT owner_id FROM me) AND cardinality(kp.directions) > 0 ORDER BY up.depth LIMIT 1),
        '{}'::text[])
$$;
COMMENT ON FUNCTION tasks.krug_dirs(uuid) IS 'Направления дела с наследованием: свои, потом «Зачем» вверх по цепочке, потом проект.';

-- Разборщик Круга (krug_auto.py) спрашивает в «Подскажи», когда не уверен в направлении: свой источник.
ALTER TABLE tasks.suggestions DROP CONSTRAINT suggestions_source_check;
ALTER TABLE tasks.suggestions ADD CONSTRAINT suggestions_source_check
    CHECK (source IN ('meeting', 'telegram', 'userbot', 'bot', 'import', 'phone', 'mcp', 'user', 'krug'));

-- Вид собран через t.*: новые колонки — в хвост, пересборкой с теми же правами (как в dela_0003 и 0007).
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
       -- Круг (dela_0009): только хозяину дела — у остальных строки Круга не видно, поля пустые.
       coalesce(k.directions, '{}'::text[]) AS directions,
       k.why_id,
       k.seq AS krug_seq,
       CASE WHEN t.owner_id = crm.me() THEN tasks.krug_dirs(t.id) ELSE '{}'::text[] END AS dirs,
       w.num AS why_num,
       w.title AS why_title,
       (SELECT count(*) FROM tasks.krug c JOIN tasks.tasks ct ON ct.id = c.id
        WHERE c.why_id = t.id AND ct.status = 'open')::integer AS steps_open
FROM tasks.tasks t
LEFT JOIN crm.projects p ON p.id = t.project_id
LEFT JOIN crm.deals d ON d.id = t.deal_id
LEFT JOIN crm.people pe ON pe.id = t.person_id
LEFT JOIN crm.people rq ON rq.id = t.requested_by
LEFT JOIN tasks.krug k ON k.id = t.id AND k.kind = 'task'
LEFT JOIN tasks.tasks w ON w.id = k.why_id;
COMMENT ON VIEW tasks.v_tasks IS 'Задача с наследованными деньгами, сферой проекта, именами людей, группой (owed — ждут от меня, self — сам, check — проверить) и Кругом хозяину: directions — свои направления, dirs — с наследованием, why — «Зачем», steps_open — открытых шагов к этому делу.';

DO $$
DECLARE
    r text;
BEGIN
    FOR r IN SELECT grantee FROM v_tasks_grants LOOP
        EXECUTE format('GRANT SELECT ON tasks.v_tasks TO %I', r);
    END LOOP;
END $$;
