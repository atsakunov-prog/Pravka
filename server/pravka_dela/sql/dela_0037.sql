-- Дела, миграция 37 (19.10.2026): книга выполнений — статистика по событиям выполнения, как у Todoist.
--
-- До неё очки, серия, движение Круга и «последнее по направлению» считались по нынешнему статусу дела (status = 'done',
-- completed_at). Так терялись выполнения, после которых дело снова открыто:
-- - повторяющееся дело при выполнении остаётся открытым (dela_0036) — его выполнения очков не давали вовсе;
-- - повтор, как у Todoist, снова открывает выполненные дела внутри себя (слово Саши 19.10: «давай как тудуист все
--   делать») — их выполнение стёрлось бы из статистики задним числом.
-- У Todoist статистика из журнала: выполнение остаётся выполнением. Здесь — tasks.done_log, строку пишет база:
-- - дело стало сделанным — строка 'done' (срок, «Сейчас», «ждут от тебя» — на момент выполнения, для «обещанного»);
-- - повтор выполнен (done_count вырос) — строка 'recur' (срок и «Сейчас» — прежние, до переноса);
-- - «Вернуть» (сделанное снова открыто) — последняя строка 'done' уходит: очки снимаются, как и раньше;
-- - «Отменить» выполнение повтора (done_count уменьшили) — уходит последняя 'recur';
-- - сброс дел внутри повтора открывает их с dela.keep_done = on — книга не трогается, выполнение остаётся;
-- - «Отменить» выполнение повтора отмечает их снова сделанными тоже с dela.keep_done = on (task.set restore) — без
--   второй строки: выполнение одно, оно и было.
-- Пишет только триггер (SECURITY DEFINER), читает хозяин дела и system.
-- Прошлое переносится: сделанные дела — по completed_at, выполнения повторов — из журнала crm.history.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

CREATE TABLE tasks.done_log (
    id          bigserial PRIMARY KEY,
    task_id     uuid NOT NULL REFERENCES tasks.tasks ON DELETE CASCADE,
    owner_id    text NOT NULL REFERENCES crm.users,
    at          timestamptz NOT NULL,
    kind        text NOT NULL CHECK (kind IN ('done', 'recur')),
    task_kind   text NOT NULL DEFAULT 'task',  -- вид дела: task или remind (закрытое напоминание — движение Круга, не очки)
    due_date    date,
    focus_on    date,
    owed        boolean NOT NULL DEFAULT false,
    project_id  uuid,
    source      text
);
COMMENT ON TABLE tasks.done_log IS 'Книга выполнений (как журнал Todoist): строка на каждое выполнение дела — и обычного (done), и повтора (recur). Статистика, серия, движение Круга — отсюда. Пишет только триггер done_log на tasks.tasks.';
CREATE INDEX done_log_owner_at ON tasks.done_log (owner_id, at);
CREATE INDEX done_log_task ON tasks.done_log (task_id, at);
ALTER TABLE tasks.done_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY see ON tasks.done_log FOR SELECT USING (crm.me() IN (owner_id, 'system'));

CREATE FUNCTION tasks.done_log_track() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = tasks, crm, pg_temp AS $$
BEGIN
    IF coalesce(current_setting('dela.keep_done', true), '') = 'on' AND TG_OP = 'UPDATE' AND NEW.status IS DISTINCT FROM OLD.status THEN
        NULL;  -- сброс дел внутри повтора или его отмена: выполнение то же, книга не трогается
    ELSIF NEW.status = 'done' AND (TG_OP = 'INSERT' OR OLD.status <> 'done') THEN
        INSERT INTO tasks.done_log (task_id, owner_id, at, kind, task_kind, due_date, focus_on, owed, project_id, source)
        VALUES (NEW.id, NEW.owner_id, coalesce(NEW.completed_at, now()), 'done', NEW.kind, NEW.due_date, NEW.focus_on, NEW.owed,
                NEW.project_id, NEW.source);
    ELSIF TG_OP = 'UPDATE' AND OLD.status = 'done' AND NEW.status <> 'done' THEN
        DELETE FROM tasks.done_log WHERE id = (SELECT id FROM tasks.done_log WHERE task_id = NEW.id AND kind = 'done'
                                               ORDER BY at DESC, id DESC LIMIT 1);
    ELSIF TG_OP = 'UPDATE' AND NEW.status = 'done' AND NEW.completed_at IS DISTINCT FROM OLD.completed_at THEN
        -- время выполнения поправили (перенос, починка) — книга за ним
        UPDATE tasks.done_log SET at = NEW.completed_at WHERE id = (SELECT id FROM tasks.done_log WHERE task_id = NEW.id
                                                                     AND kind = 'done' ORDER BY at DESC, id DESC LIMIT 1);
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.done_count > OLD.done_count THEN
        INSERT INTO tasks.done_log (task_id, owner_id, at, kind, task_kind, due_date, focus_on, owed, project_id, source)
        VALUES (NEW.id, NEW.owner_id, now(), 'recur', NEW.kind, OLD.due_date, OLD.focus_on, OLD.owed, NEW.project_id, NEW.source);
    ELSIF TG_OP = 'UPDATE' AND NEW.done_count < OLD.done_count THEN
        DELETE FROM tasks.done_log WHERE id IN (SELECT id FROM tasks.done_log WHERE task_id = NEW.id AND kind = 'recur'
                                                ORDER BY at DESC, id DESC LIMIT OLD.done_count - NEW.done_count);
    END IF;
    RETURN NULL;
END $$;
COMMENT ON FUNCTION tasks.done_log_track() IS 'Книга выполнений: сделано — строка, «Вернуть» — минус последняя (сброс дел внутри повтора и его отмена — dela.keep_done = on — книгу не трогают), выполнение повтора — строка recur, отмена — минус последняя recur.';
CREATE TRIGGER done_log AFTER INSERT OR UPDATE OF status, done_count, completed_at ON tasks.tasks
    FOR EACH ROW EXECUTE FUNCTION tasks.done_log_track();

-- Прошлое: сделанные дела — по completed_at; выполнения повторов (до этой миграции очков не давали) — из журнала.
INSERT INTO tasks.done_log (task_id, owner_id, at, kind, task_kind, due_date, focus_on, owed, project_id, source)
SELECT id, owner_id, completed_at, 'done', kind, due_date, focus_on, owed, project_id, source
FROM tasks.tasks WHERE status = 'done' AND completed_at IS NOT NULL;

INSERT INTO tasks.done_log (task_id, owner_id, at, kind, task_kind, due_date, focus_on, owed, project_id, source)
SELECT t.id, t.owner_id, h.at, 'recur', t.kind, (h.before ->> 'due_date')::date, (h.before ->> 'focus_on')::date, t.owed,
       t.project_id, t.source
FROM crm.history h JOIN tasks.tasks t ON t.id::text = h.entity_id
WHERE h.entity = 'tasks.tasks' AND h.op = 'update'
  AND (h.after ->> 'done_count')::int > coalesce((h.before ->> 'done_count')::int, 0);
