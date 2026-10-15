-- Дела, миграция 24 (15.10.2026): дела внутри дела — место среди соседей.
--
-- Владелец 15.10.2026: «до этого надо из расшифровки встречи набросать драфт… под делом должно быть „до этого“ /
-- „для этого“ / „поправить это дело“»; вечером — «про дела и последовательность надо делать вложенность одного в
-- другое и показывать тоже в дереве»; уточнение — «это не отдельная сущность, а обычные дела, вложенные в дело».
-- Вложенность уже есть: «Зачем» дела (tasks.krug.why_id, dela_0009) — другое дело; цепочку любой глубины и защиту от
-- петли держит tasks.krug_rules (dela_0013: «„зачем“ по кругу», «только своё дело»). Не хватало порядка «сначала /
-- потом» у дел одного родителя.
--
-- pos — место дела среди соседей (дел с тем же «Зачем»): меньше — раньше; пусто — после занумерованных, по времени
-- заведения. Общее поле дела, а не свойство вложенности: его видит всякий, кто видит дело (у команды без «Зачем» оно
-- просто ничего не значит), журнал пишет его как любое поле. Порядок правит клиент, перенумеровывая соседей 1, 2, 3…
--
-- Обратимо: колонка и пересборка вида; данных не трогает.

ALTER TABLE tasks.tasks ADD COLUMN pos integer;
COMMENT ON COLUMN tasks.tasks.pos IS 'Место среди соседей — дел с тем же «Зачем» (why_id в tasks.krug): меньше — раньше, пусто — после занумерованных по времени заведения (15.10.2026).';

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
       coalesce(k.no_steps, false) AS no_steps
FROM tasks.tasks t
LEFT JOIN crm.projects p ON p.id = t.project_id
LEFT JOIN crm.deals d ON d.id = t.deal_id
LEFT JOIN crm.people pe ON pe.id = t.person_id
LEFT JOIN crm.people rq ON rq.id = t.requested_by
LEFT JOIN tasks.krug k ON k.id = t.id AND k.kind = 'task'
LEFT JOIN tasks.tasks w ON w.id = k.why_id;
COMMENT ON VIEW tasks.v_tasks IS 'Задача с наследованными деньгами, сферой проекта, именами людей, группой (owed — ждут от меня, self — сам, check — проверить) и Кругом хозяину: directions — свои направления, dirs — [главное, метки…], why — «Зачем» (дело-цель или дело, внутри которого это дело), steps_open — открытых дел внутри, pos — место среди соседей, goal — узел дерева целей (в списках дел его нет), rhythm — ритм практики, no_steps — под целью дел не заводят.';

DO $$
DECLARE
    r text;
BEGIN
    FOR r IN SELECT grantee FROM v_tasks_grants LOOP
        EXECUTE format('GRANT SELECT ON tasks.v_tasks TO %I', r);
    END LOOP;
END $$;
