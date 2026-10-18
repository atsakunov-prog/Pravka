-- Дела, миграция 36 (19.10.2026): Дела в виде Todoist — новый веб /t. Слово Саши 18.10: «скопировать и дизайн, и
-- фичи Todoist; из нашего оставить вопрос Claude, дерево дел и все связи с CRM».
--
-- Чего у дела не было, а в Todoist — ядро:
-- - priority — p1…p4 как их видит человек: 1 — срочно (красный), 4 — без приоритета. В API Todoist наоборот (4 = p1);
--   здесь — как в интерфейсе, чтобы в коде и коннекторе не путаться;
-- - deadline — к чему обязан успеть (срок due_date — когда собираюсь делать); только дата;
-- - due_rule + recur — повтор: как сказано («каждый пн», «каждые! 3 дня») и разобранное правило. Выполнение
--   повторяющегося дела не закрывает его, а переносит срок на следующий раз (store.recur_next) — одно правило для веба,
--   телефона, бота и Claude: старый клиент шлёт task.done, сервер переносит сам;
-- - done_count — сколько раз выполнено (у повторяющихся растёт, дело остаётся открытым);
-- - section_id — раздел проекта (tasks.sections). Раздел — в проекте дела; сменился проект — раздел снимается (store).
-- У проекта — цвет (палитра Todoist по имени: berry_red … taupe). Избранное, порядок проектов и меток, сохранённые
-- фильтры и вид (список, доска) — личные: в crm.users.settings.todo, без миграции.
--
-- Старые клиенты новых полей не шлют (телефон шлёт поле, только увидев флаг в синке) и не замечают: по умолчанию p4,
-- без раздела, без повтора.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

CREATE TABLE tasks.sections (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  uuid NOT NULL REFERENCES crm.projects ON DELETE CASCADE,
    name        text NOT NULL CHECK (btrim(name) <> '' AND length(name) <= 200),
    pos         double precision,
    collapsed   boolean NOT NULL DEFAULT false,
    archived    boolean NOT NULL DEFAULT false,
    created_by  text NOT NULL REFERENCES crm.users,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    rev         integer NOT NULL DEFAULT 1,
    seq         bigint NOT NULL DEFAULT 0
);
COMMENT ON TABLE tasks.sections IS 'Разделы проекта (как в Todoist): заголовки внутри проекта, порядок pos. Не удаляются — archived; дела архивного раздела остаются в проекте без раздела.';
CREATE INDEX sections_project ON tasks.sections (project_id);
CREATE INDEX sections_seq ON tasks.sections (seq);
CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON tasks.sections FOR EACH ROW EXECUTE FUNCTION crm.stamp();
CREATE TRIGGER journal AFTER INSERT OR UPDATE OR DELETE ON tasks.sections FOR EACH ROW EXECUTE FUNCTION crm.journal();
ALTER TABLE tasks.sections ENABLE ROW LEVEL SECURITY;
CREATE POLICY see ON tasks.sections FOR SELECT USING (crm.sees_project(crm.me(), project_id));
CREATE POLICY ins ON tasks.sections FOR INSERT
    WITH CHECK (crm.edits_project(crm.me(), project_id) AND crm.me() IN (created_by, 'system'));
CREATE POLICY upd ON tasks.sections FOR UPDATE
    USING (crm.edits_project(crm.me(), project_id)) WITH CHECK (crm.edits_project(crm.me(), project_id));

ALTER TABLE tasks.tasks
    ADD COLUMN priority   smallint NOT NULL DEFAULT 4 CHECK (priority BETWEEN 1 AND 4),
    ADD COLUMN deadline   date,
    ADD COLUMN due_rule   text CHECK (due_rule IS NULL OR length(due_rule) <= 150),
    ADD COLUMN recur      jsonb CHECK (recur IS NULL OR jsonb_typeof(recur) = 'object'),
    ADD COLUMN done_count integer NOT NULL DEFAULT 0,
    ADD COLUMN section_id uuid REFERENCES tasks.sections ON DELETE SET NULL;
ALTER TABLE tasks.tasks ADD CONSTRAINT tasks_recur_pair CHECK ((recur IS NULL) = (due_rule IS NULL));
ALTER TABLE tasks.tasks ADD CONSTRAINT tasks_recur_due CHECK (recur IS NULL OR due_date IS NOT NULL);
CREATE INDEX tasks_section ON tasks.tasks (section_id) WHERE section_id IS NOT NULL;
COMMENT ON COLUMN tasks.tasks.priority IS 'p1…p4 как в интерфейсе Todoist: 1 — срочно, 4 — без приоритета.';
COMMENT ON COLUMN tasks.tasks.deadline IS 'Дедлайн — к чему обязан успеть (срок — когда собираюсь делать). Только дата.';
COMMENT ON COLUMN tasks.tasks.due_rule IS 'Повтор словами, как сказано: «каждый пн», «каждые! 3 дня» («!» — от дня выполнения).';
COMMENT ON COLUMN tasks.tasks.recur IS 'Повтор разобранным правилом: {every: day|workday|week|month|year, n, days?, monthday?, from: due|done, until?}. Выполнение переносит срок (store.recur_next).';
COMMENT ON COLUMN tasks.tasks.done_count IS 'Сколько раз выполнено: у повторяющегося растёт, а дело остаётся открытым.';
COMMENT ON COLUMN tasks.tasks.section_id IS 'Раздел проекта (tasks.sections). Сменился проект — раздел снимается.';

ALTER TABLE crm.projects ADD COLUMN color text CHECK (color IS NULL OR color ~ '^[a-z_]{3,20}$');
COMMENT ON COLUMN crm.projects.color IS 'Цвет проекта по имени палитры Todoist (berry_red, red, orange, …, taupe); пусто — серый (charcoal).';

-- Вид дела — тело от последней сборки (dela_0032, правило 26 карты Дел) плюс имя раздела: t.* разворачивается при
-- создании, новых колонок старый вид не видит. Права — переносом, как в dela_0003, 0007, 0032.
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
       coalesce(wk.goal, false) AS why_goal,
       s.name AS section_name
FROM tasks.tasks t
LEFT JOIN crm.projects p ON p.id = t.project_id
LEFT JOIN crm.deals d ON d.id = t.deal_id
LEFT JOIN crm.people pe ON pe.id = t.person_id
LEFT JOIN crm.people rq ON rq.id = t.requested_by
LEFT JOIN tasks.krug k ON k.id = t.id AND k.kind = 'task'
LEFT JOIN tasks.tasks w ON w.id = k.why_id
LEFT JOIN tasks.krug wk ON wk.id = k.why_id AND wk.kind = 'task'
LEFT JOIN tasks.sections s ON s.id = t.section_id;
COMMENT ON VIEW tasks.v_tasks IS 'Задача с наследованными деньгами, сферой проекта, именами людей, группой (owed — ждут от меня, self — сам, check — проверить) и Кругом хозяину: directions — свои направления, dirs — [главное, метки…], why — «Зачем» (дело-цель или дело, внутри которого это дело; why_goal — цель ли это), steps_open — открытых дел внутри (напоминания не в счёт), pos — место среди соседей, goal — узел дерева целей (в списках дел его нет), rhythm — ритм практики, no_steps — под целью дел не заводят. kind — task или remind (напоминание: в списках дел его нет), back_on — день возврата дела «потом», back_said — как сказано. Вид Todoist (dela_0036): priority p1…p4, deadline, due_rule и recur — повтор, done_count, section_id и section_name.';

DO $$
DECLARE
    r text;
BEGIN
    FOR r IN SELECT grantee FROM v_tasks_grants LOOP
        EXECUTE format('GRANT SELECT ON tasks.v_tasks TO %I', r);
    END LOOP;
END $$;
