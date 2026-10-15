-- Дела, миграция 30 (15.10.2026): дела внутри дела — место среди соседей и подъём родителя.
--
-- Владелец 15.10.2026: «до этого надо из расшифровки встречи набросать драфт… под делом должно быть „до этого“ /
-- „для этого“ / „поправить это дело“»; вечером — «про дела и последовательность надо делать вложенность одного в
-- другое и показывать тоже в дереве»; уточнение — «это не отдельная сущность, а обычные дела, вложенные в дело».
-- Вложенность уже есть: «Зачем» дела (tasks.krug.why_id, dela_0009) — другое дело; цепочку любой глубины и защиту от
-- петли держит tasks.krug_rules (dela_0013: «„зачем“ по кругу», «только своё дело»). Не хватало порядка «сначала /
-- потом» у дел одного родителя и подъёма родителя, когда всё внутри сделано.
--
-- pos — место дела среди соседей (дел с тем же «Зачем»): меньше — раньше; пусто — после занумерованных, по времени
-- заведения. Общее поле дела, а не свойство вложенности: его видит всякий, кто видит дело (у команды без «Зачем» оно
-- просто ничего не значит), журнал пишет его как любое поле. Порядок правит клиент, перенумеровывая соседей 1, 2, 3…
-- Только с единицы: ноль и минус веб считал бы настоящим местом («первым»), а телефон и дерево — «не задано», и порядок
-- на устройствах разошёлся бы (сервер отказывает словами раньше, CHECK — страховка).
--
-- Подъём родителя (правило 27): сделано последнее открытое дело внутри — родитель не закрывается, а поднимается на
-- сегодня. Делает это база (tasks.nest_rise), а не код под правами того, кто закрыл: «Зачем» видит только хозяин (Круг
-- личный), и когда последнее дело внутри закрывала Наташа (дело Саши в общем проекте), родитель не поднимался — ей
-- why_id не виден. «Вернуть» после «Сделано» (тост, «Вернуть» у Claude и у «Закрыто само», повторный тап на телефоне)
-- возвращает и срок родителя (tasks.nest_fall): какой он был до подъёма — в tasks.nest_raised; правил срок сам — память
-- гаснет (триггер nest_forget), и «Вернуть» его не трогает.
--
-- Обратимо: колонка, таблица памяти, функции и пересборка вида; уже лежащих данных не трогает.

ALTER TABLE tasks.tasks ADD COLUMN pos integer CONSTRAINT tasks_pos_positive CHECK (pos > 0);
COMMENT ON COLUMN tasks.tasks.pos IS 'Место среди соседей — дел с тем же «Зачем» (why_id в tasks.krug): 1, 2, 3… — раньше, пусто — после занумерованных по времени заведения (15.10.2026).';

-- Вид собран через t.*: новая колонка дела встаёт в него только пересборкой — с теми же правами (как в dela_0013).
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
COMMENT ON VIEW tasks.v_tasks IS 'Задача с наследованными деньгами, сферой проекта, именами людей, группой (owed — ждут от меня, self — сам, check — проверить) и Кругом хозяину: directions — свои направления, dirs — [главное, метки…], why — «Зачем» (дело-цель или дело, внутри которого это дело; why_goal — цель ли это), steps_open — открытых дел внутри, pos — место среди соседей, goal — узел дерева целей (в списках дел его нет), rhythm — ритм практики, no_steps — под целью дел не заводят.';

DO $$
DECLARE
    r text;
BEGIN
    FOR r IN SELECT grantee FROM v_tasks_grants LOOP
        EXECUTE format('GRANT SELECT ON tasks.v_tasks TO %I', r);
    END LOOP;
END $$;

-- ── Подъём родителя и его «Вернуть» ────────────────────────────────────
-- Память подъёма: какой срок был у родителя до него. Строка одна на родителя; правка срока родителя кем угодно и чем
-- угодно (руками, Claude, «Перенести», напоминание) её гасит — тогда «Вернуть» срок не трогает. Служебная память, а не
-- дело: журнала нет (сам срок родителя журнал пишет как любую правку), строки удаляются, роли службы прав нет — только
-- функциям ниже (RLS включена, политик нет).
CREATE TABLE tasks.nest_raised (
    parent_id uuid PRIMARY KEY REFERENCES tasks.tasks ON DELETE CASCADE,
    was       date,
    rose_to   date NOT NULL,
    at        timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE tasks.nest_raised IS 'Подъём родителя (правило 27): срок родителя до подъёма (was; пусто — был без срока) и на какой день поднят (rose_to). «Вернуть» дела внутри возвращает was, если срок с тех пор не правили. Пишут только tasks.nest_rise / nest_fall.';
ALTER TABLE tasks.nest_raised ENABLE ROW LEVEL SECURITY;

-- Сделано дело [child]: если оно — последнее открытое внутри обычного дела, родитель поднимается — без срока или со
-- сроком позже — на сегодня (просроченный и сегодняшний — как были). Родитель — открытое дело, не цель (у цели свои
-- правила: она живёт в дереве). SECURITY DEFINER: родителя и его дела внутри смотрим мимо RLS Круга — подъём для всех,
-- кто закрыл, а что дело было внутри чего, знает только база (ответ — вызывающему коду, он и решает, кому сказать).
-- Журнал правки родителя пишет того, кто закрыл (dela.actor сессии). Ответ — что поднялось, иначе NULL.
CREATE FUNCTION tasks.nest_rise(child uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path = tasks, crm, pg_temp AS $$
DECLARE
    pid uuid;
    p tasks.tasks;
    d date := crm.today();
    moved boolean;
BEGIN
    SELECT k.why_id INTO pid FROM tasks.krug k JOIN tasks.tasks c ON c.id = k.id
    WHERE k.id = child AND k.kind = 'task' AND c.status = 'done';
    IF pid IS NULL OR EXISTS (SELECT 1 FROM tasks.krug WHERE id = pid AND goal) THEN
        RETURN NULL;
    END IF;
    SELECT * INTO p FROM tasks.tasks WHERE id = pid FOR UPDATE;
    IF p.id IS NULL OR p.status <> 'open' THEN
        RETURN NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM tasks.krug k JOIN tasks.tasks c ON c.id = k.id
               WHERE k.why_id = pid AND k.kind = 'task' AND c.status = 'open' AND NOT k.goal) THEN
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
COMMENT ON FUNCTION tasks.nest_rise(uuid) IS 'Сделано последнее открытое дело внутри — родитель поднимается на сегодня (правило 27). Ответ: id, num, title, owner_id, moved, due_date родителя; иначе NULL.';

-- Дело [child] снова открыто («Вернуть» после «Сделано», повторный тап, «Вернуть» у «Закрыто само»): у родителя
-- опять есть открытое внутри — подъём отменяется, если срок родителя с тех пор никто не правил (память цела). Ответ —
-- родитель и его вернувшийся срок, иначе NULL.
CREATE FUNCTION tasks.nest_fall(child uuid) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER
SET search_path = tasks, crm, pg_temp AS $$
DECLARE
    pid uuid;
    m tasks.nest_raised;
    p tasks.tasks;
BEGIN
    SELECT k.why_id INTO pid FROM tasks.krug k JOIN tasks.tasks c ON c.id = k.id
    WHERE k.id = child AND k.kind = 'task' AND c.status = 'open';
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
COMMENT ON FUNCTION tasks.nest_fall(uuid) IS '«Вернуть» дела внутри: подъём родителя отменяется — срок, какой был до него, если его с тех пор не правили (правило 27). Ответ: id, num, title, owner_id, due_date родителя; иначе NULL.';

-- Срок дела поменялся — память подъёма этого дела больше не про него: «Вернуть» срок не тронет.
CREATE FUNCTION tasks.nest_forget() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = tasks, pg_temp AS $$
BEGIN
    DELETE FROM tasks.nest_raised WHERE parent_id = NEW.id;
    RETURN NULL;
END $$;
CREATE TRIGGER nest_forget AFTER UPDATE OF due_date ON tasks.tasks FOR EACH ROW
    WHEN (OLD.due_date IS DISTINCT FROM NEW.due_date) EXECUTE FUNCTION tasks.nest_forget();

-- Роли службы — по имени (db.APP_FUNCTIONS), остальным — нет: функции пишут мимо RLS.
REVOKE ALL ON FUNCTION tasks.nest_rise(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION tasks.nest_fall(uuid) FROM PUBLIC;
