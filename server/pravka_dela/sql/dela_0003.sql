-- Дела, миграция 3 (06.10.2026): напоминания в Telegram.
--
-- Владелец: «хочу, чтобы шли напоминания через мой Ковчег-бот в Телеграме… „напомни мне в
-- 11:00“ или „напомни, когда я приеду домой“». Напоминание — момент (remind_at) или место
-- (remind_place: имя места автопилота Засечки). Место в момент превращает телефон — он один
-- знает, где владелец: на настоящем приезде ставит remind_at = миг приезда. Отправляет бот
-- Ковчега (служба встреч), забирая «что пора» у Дел; отправленное помечает reminded_at.
-- Контракт — server/contract/dela-remind.json, задание — docs/dela-server-remind.md.

ALTER TABLE tasks.tasks
    ADD COLUMN remind_at    timestamptz,
    ADD COLUMN remind_place text CHECK (btrim(remind_place) <> ''),
    ADD COLUMN reminded_at  timestamptz;
COMMENT ON COLUMN tasks.tasks.remind_at IS 'Когда напомнить в Telegram. В прошлом и не отправлено — уйдёт сразу (так телефон отмечает приезд).';
COMMENT ON COLUMN tasks.tasks.remind_place IS 'Напомнить по приезду: имя места автопилота Засечки («дом»). Приезд превращает его в remind_at — оба заполнены значит «место уже сработало».';
COMMENT ON COLUMN tasks.tasks.reminded_at IS 'Когда напоминание ушло в Telegram. Ставит только сервер (task.reminded от бота); новое remind_at или remind_place сбрасывает его само.';

-- Новое время или место — напоминание снова в силе: «Через час» из бота и новое время в
-- карточке взводят его заново. Закрытое дело полей не теряет: вернули в работу — напомнит,
-- если ещё не напоминало. Явная запись reminded_at (её делает только сервер) — как есть.
CREATE FUNCTION tasks.remind_rules() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.remind_at IS DISTINCT FROM OLD.remind_at OR NEW.remind_place IS DISTINCT FROM OLD.remind_place)
       AND NEW.reminded_at IS NOT DISTINCT FROM OLD.reminded_at THEN
        NEW.reminded_at := NULL;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER remind BEFORE UPDATE ON tasks.tasks FOR EACH ROW EXECUTE FUNCTION tasks.remind_rules();

-- Что пора отправить: бот спрашивает раз в 30 секунд.
CREATE INDEX tasks_remind ON tasks.tasks (remind_at)
    WHERE status = 'open' AND reminded_at IS NULL AND remind_at IS NOT NULL;

-- Вид собран через t.*: новые колонки в нём появятся только пересборкой. Права на него
-- раздаёт migrate Дел, но архив применяет миграции Дел раньше (life_views) — между ними
-- служба осталась бы без вида, поэтому прежние права переносятся здесь же.
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
       (t.focus_on = crm.today()) AS now_
FROM tasks.tasks t
LEFT JOIN crm.projects p ON p.id = t.project_id
LEFT JOIN crm.deals d ON d.id = t.deal_id
LEFT JOIN crm.people pe ON pe.id = t.person_id
LEFT JOIN crm.people rq ON rq.id = t.requested_by;
COMMENT ON VIEW tasks.v_tasks IS 'Задача с наследованными деньгами, сферой проекта и именами людей.';

DO $$
DECLARE
    r text;
BEGIN
    FOR r IN SELECT grantee FROM v_tasks_grants LOOP
        EXECUTE format('GRANT SELECT ON tasks.v_tasks TO %I', r);
    END LOOP;
END $$;
