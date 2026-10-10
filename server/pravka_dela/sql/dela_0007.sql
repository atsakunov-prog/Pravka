-- Дела, миграция 7 (10.10.2026): три группы дел — «Ждут от меня», «Сам», «Проверить».
--
-- Владелец: «есть дела, которые ждут меня — люди что-то спросили и ждут, они мега важные, их я делаю
-- в первую очередь… вторая — что я сам придумал, жду от себя… третья — проверить, что мне люди должны
-- сделать… и чёткий список на каждый день». До этого «ждут от меня» не было вовсе: из 158 открытых дел
-- 113 лежали одним «моё», и «Гуркин ждёт звонка» стояло рядом с «купить магний» — он про это забыл.
-- Дайджест писал «Ждут тебя» в сводке, а дело заводил тем же «моё».
--
-- Группа дела выводится из двух полей, отдельного поля нет:
--   owed            — «Ждут от меня»: человек (person_id) ждёт от Саши ответа или дела;
--   ball = waiting  — «Проверить»: Саша ждёт от человека;
--   остальное       — «Сам» (mine и agenda: «попросить Наташу», «проговорить с Марианной»).
-- Флаг, а не четвёртое значение ball: телефон до своего задания показывает такие дела как «моё», а не
-- теряет их — его виды фильтруют по ball = 'mine'.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true);

ALTER TABLE tasks.tasks ADD COLUMN owed boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN tasks.tasks.owed IS '«Ждут от меня»: человек (person_id) ждёт от владельца дела ответа или действия. Только у mine и agenda; у waiting сбрасывается сам.';

-- «Ждут от меня» и «жду от человека» разом не бывает: дело ушло в «Проверить» — флаг гаснет сам,
-- какой бы клиент ни поменял мяч (телефон шлёт один ball, Claude — тоже).
CREATE FUNCTION tasks.owed_rules() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.ball = 'waiting' THEN
        NEW.owed := false;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER owed BEFORE INSERT OR UPDATE ON tasks.tasks FOR EACH ROW EXECUTE FUNCTION tasks.owed_rules();

-- Вид собран через t.*: новая колонка в нём появится только пересборкой (как в dela_0003). Права —
-- те же, что были: архив применяет миграции Дел раньше migrate Дел.
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
       CASE WHEN t.owed THEN 'owed' WHEN t.ball = 'waiting' THEN 'check' ELSE 'self' END AS grp
FROM tasks.tasks t
LEFT JOIN crm.projects p ON p.id = t.project_id
LEFT JOIN crm.deals d ON d.id = t.deal_id
LEFT JOIN crm.people pe ON pe.id = t.person_id
LEFT JOIN crm.people rq ON rq.id = t.requested_by;
COMMENT ON VIEW tasks.v_tasks IS 'Задача с наследованными деньгами, сферой проекта, именами людей и группой: owed — ждут от меня, self — сам, check — проверить.';

DO $$
DECLARE
    r text;
BEGIN
    FOR r IN SELECT grantee FROM v_tasks_grants LOOP
        EXECUTE format('GRANT SELECT ON tasks.v_tasks TO %I', r);
    END LOOP;
END $$;
