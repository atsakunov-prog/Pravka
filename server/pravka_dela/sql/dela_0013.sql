-- Дела, миграция 13 (13.10.2026): Круг жизни 7.2 — дерево целей.
--
-- Владелец принёс бриф 7.2 («единая логика… единое дерево жизни»): направлений девять, а не двенадцать; под каждым —
-- цели, которые он ставит сам, под целями — подцели (до четырёх уровней), проекты и дела. «Место дела определяется
-- его целью, а не тем, кто попросил». Цель — состояние («сын хорошо знает математику»), а не действие; цель с
-- ритмом («ежедневно 8 часов сна») — практика: повторяется и не закрывается. Пустая цель — сигнал, не мусор.
--
-- Цель — по-прежнему дело (как в 7.1: три цели лесенкой), но теперь с явной пометкой «узел дерева» (goal): узел
-- живёт в дереве и в цепочке «Зачем», а в списках дел («Сегодня», «Все дела», группы «Отбить / Запустить /
-- Мониторить») его нет — там только то, что делать. Раньше целью считалось любое дело с шагами, и пустая цель
-- переставала быть целью; восемьдесят узлов брифа без пометки легли бы в «Запустить».
--
-- Узлы и тексты целей видит только хозяин: пометка живёт в строке Круга (политика «только хозяину», dela_0009),
-- а сам узел — дело без проекта (crm.sees_task: такое видит только хозяин). Это ставит store._krug_write.
--
-- Проект (и клиент целиком) тоже вешается на цель: «Клиентские проекты вешаются на цель целиком». Поэтому у строки
-- Круга проекта теперь бывает «Зачем».
--
-- Направление дела — одно главное, остальные — метки (не больше двух): главное — то, в которое упирается цепочка
-- «Зачем» (корень дерева), в нём считается движение; свои направления дела, кроме главного, — его метки.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

ALTER TABLE tasks.krug
    ADD COLUMN goal boolean NOT NULL DEFAULT false,
    ADD COLUMN rhythm text CHECK (rhythm IS NULL OR length(rhythm) BETWEEN 1 AND 60),
    ADD COLUMN no_steps boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN tasks.krug.goal IS 'Узел дерева целей: цель-состояние. Узла нет в списках дел — только в дереве и в цепочке «Зачем».';
COMMENT ON COLUMN tasks.krug.rhythm IS 'Ритм практики словами («ежедневно», «2 раза в неделю»): цель с ритмом — практика, она повторяется и не закрывается.';
COMMENT ON COLUMN tasks.krug.no_steps IS 'Под этой целью дел и подцелей не заводят (ориентир, а не задача) — правило базы, а не клиента.';

-- У проекта теперь бывает «Зачем» (цель); узлом и практикой бывает только дело.
ALTER TABLE tasks.krug DROP CONSTRAINT krug_check;
ALTER TABLE tasks.krug ADD CONSTRAINT krug_task_only CHECK (kind = 'task' OR NOT (goal OR no_steps OR rhythm IS NOT NULL));
ALTER TABLE tasks.krug DROP CONSTRAINT krug_directions_check;
ALTER TABLE tasks.krug ADD CONSTRAINT krug_directions_check CHECK (cardinality(directions) <= 3);
COMMENT ON COLUMN tasks.krug.directions IS 'Свои направления — ключи из Свода krug.directions. У корневой цели — её направление; у остальных — метки (главное направление берётся у корня цепочки «Зачем»). Пусто — наследует.';
COMMENT ON COLUMN tasks.krug.why_id IS '«Зачем»: цель (дело-узел), к которой ведёт это дело, цель или проект. Только своё, без кругов.';

CREATE OR REPLACE FUNCTION tasks.krug_rules() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
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
    NEW.rhythm := nullif(btrim(NEW.rhythm), '');
    IF NEW.why_id IS NOT NULL THEN
        IF NOT EXISTS (SELECT 1 FROM tasks.tasks WHERE id = NEW.why_id AND owner_id = NEW.owner_id) THEN
            RAISE EXCEPTION 'Дела: «зачем» — только своё дело';
        END IF;
        IF EXISTS (SELECT 1 FROM tasks.krug WHERE id = NEW.why_id AND no_steps)
           AND (TG_OP = 'INSERT' OR NEW.why_id IS DISTINCT FROM OLD.why_id) THEN
            RAISE EXCEPTION 'Дела: под этой целью дел не заводят — она ориентир, а не задача';
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

-- Направления дела: [главное, метки…]. Цепочка вверх — «Зачем» дела; у дела без «Зачем» — его проект (строка Круга
-- проекта хозяина), а от проекта — его «Зачем». Главное — первое направление самого верхнего звена, у которого
-- направления есть (корень дерева); метки — свои направления дела, кроме главного (не больше двух). Только строки
-- Круга хозяина дела: дело Наташи в проекте Саши направлений Саши не получает. SECURITY DEFINER — как функции
-- видимости: её зовут и виды life; кому показывать, решает вызывающий (v_tasks — хозяину дела).
CREATE OR REPLACE FUNCTION tasks.krug_dirs(t uuid) RETURNS text[] LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = tasks, crm, pg_temp AS $$
    WITH RECURSIVE me AS (SELECT owner_id FROM tasks.tasks WHERE id = t),
    up AS (
        SELECT 'task'::text AS kind, x.id, x.project_id, coalesce(k.directions, '{}'::text[]) AS directions, k.why_id, 0 AS depth
        FROM tasks.tasks x LEFT JOIN tasks.krug k ON k.id = x.id AND k.owner_id = x.owner_id
        WHERE x.id = t
        UNION ALL
        SELECT n.kind, n.id, n.project_id, n.directions, n.why_id, up.depth + 1
        FROM up CROSS JOIN LATERAL (
            SELECT 'task'::text, x.id, x.project_id, coalesce(k.directions, '{}'::text[]), k.why_id
            FROM tasks.tasks x LEFT JOIN tasks.krug k ON k.id = x.id AND k.owner_id = x.owner_id
            WHERE up.why_id IS NOT NULL AND x.id = up.why_id
            UNION ALL
            SELECT 'project'::text, kp.id, NULL::uuid, kp.directions, kp.why_id
            FROM tasks.krug kp
            WHERE up.why_id IS NULL AND up.kind = 'task' AND kp.id = up.project_id AND kp.kind = 'project'
              AND kp.owner_id = (SELECT owner_id FROM me)
        ) AS n(kind, id, project_id, directions, why_id)
        WHERE up.depth < 50
    ),
    main AS (SELECT directions[1] AS d FROM up WHERE cardinality(directions) > 0 ORDER BY depth DESC LIMIT 1)
    SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM main) THEN '{}'::text[]
           ELSE ARRAY[(SELECT d FROM main)] || ARRAY(
               SELECT x FROM unnest((SELECT directions FROM up WHERE depth = 0)) WITH ORDINALITY AS u(x, i)
               WHERE x <> (SELECT d FROM main) ORDER BY i LIMIT 2)
           END
$$;
COMMENT ON FUNCTION tasks.krug_dirs(uuid) IS 'Направления дела: [главное, метки…]. Главное — у корня цепочки «Зачем» (через проект, если у дела «Зачем» нет), метки — свои направления дела.';

-- Вид собран через t.*: новые колонки — в хвост, пересборкой с теми же правами (как в dela_0009).
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
       (SELECT count(*) FROM tasks.krug c JOIN tasks.tasks ct ON ct.id = c.id
        WHERE c.why_id = t.id AND ct.status = 'open')::integer AS steps_open,
       coalesce(k.goal, false) AS goal,
       k.rhythm,
       coalesce(k.no_steps, false) AS no_steps
FROM tasks.tasks t
LEFT JOIN crm.projects p ON p.id = t.project_id
LEFT JOIN crm.deals d ON d.id = t.deal_id
LEFT JOIN crm.people pe ON pe.id = t.person_id
LEFT JOIN crm.people rq ON rq.id = t.requested_by
LEFT JOIN tasks.krug k ON k.id = t.id AND k.kind = 'task'
LEFT JOIN tasks.tasks w ON w.id = k.why_id;
COMMENT ON VIEW tasks.v_tasks IS 'Задача с наследованными деньгами, сферой проекта, именами людей, группой (owed — ждут от меня, self — сам, check — проверить) и Кругом хозяину: directions — свои направления, dirs — [главное, метки…], why — «Зачем», steps_open — открытых шагов к этому делу, goal — узел дерева целей (в списках дел его нет), rhythm — ритм практики, no_steps — под целью дел не заводят.';

DO $$
DECLARE
    r text;
BEGIN
    FOR r IN SELECT grantee FROM v_tasks_grants LOOP
        EXECUTE format('GRANT SELECT ON tasks.v_tasks TO %I', r);
    END LOOP;
END $$;
