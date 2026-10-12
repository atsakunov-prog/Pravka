-- Дела, миграция 12 (13.10.2026): клиент переименован — встречи следом.
--
-- Владелец, 13.10.2026: «если я например решу переименовать клиента… надо как-то сделать так, чтобы это и в
-- истории встреч тоже переименовалось». Дела, сделки и хронология показывают клиента ссылкой на проект — у них
-- новое имя появляется само. А название встречи «Клиент: тема» и её label — копии имени на миг разбора
-- (dela_0010): переименованный клиент остался бы в списке встреч под старым именем. Правило — в базе, а не в
-- коде одной точки входа: проект переименовывают веб, телефон, Claude и импорт.
--
-- Три правила:
-- • старое имя проекта ложится в его другие имена (aliases): так проект узнаётся и по нему — в расшифровках,
--   у разборщика встреч, в сверке дел и в «кто это». Не ложится, если так зовут другой живой проект;
-- • встречи проекта берут новое имя: label — всегда, название — если владелец его не правил сам; правленое
--   руками «Старое: …» меняет только начало;
-- • встреча без клиента, чья подпись (label) по имени или другому имени называет ровно один живой проект, — его
--   встреча: проект завели, переименовали или дописали ему имя — такие встречи пристёгиваются сами (разбор
--   подписывает встречу «Сигма Пэй», пока такого клиента нет в справочнике). Владелец, сказавший «без клиента»
--   (замок project), этим не переспоривается. Только имя проекта: человек или компания в подписи — не повод.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

-- То же правило, что meetings.title_of в коде: «Клиент: тема»; нет темы — клиент; нет клиента — тема.
CREATE FUNCTION crm.meeting_title(label text, topic text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN nullif(btrim(label), '') IS NOT NULL AND nullif(btrim(topic), '') IS NOT NULL
                THEN btrim(label) || ': ' || btrim(topic)
                ELSE coalesce(nullif(btrim(label), ''), nullif(btrim(topic), '')) END
$$;

-- Название, которое владелец написал сам: «Старое: …» → «Новое: …», остальное как есть.
CREATE FUNCTION crm.meeting_retitle(title text, old text, new text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN title IS NOT NULL AND crm.norm(old) IS NOT NULL
                     AND crm.norm(left(title, length(old))) = crm.norm(old) AND substr(title, length(old) + 1, 1) = ':'
                THEN new || substr(title, length(old) + 1)
                ELSE title END
$$;

-- Ровно один живой проект с таким именем или другим именем — его id, иначе пусто.
CREATE FUNCTION crm.project_named(s text) RETURNS uuid LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT CASE WHEN count(*) = 1 THEN min(p.id::text)::uuid END FROM crm.projects p
     WHERE p.archived_at IS NULL AND crm.norm(s) IS NOT NULL AND crm.named(crm.norm(s), p.name, p.aliases) IS TRUE
$$;

-- Новое имя из других имён уходит: переименовали обратно — «Новое» не остаётся другим именем самого себя.
CREATE FUNCTION crm.project_old_name() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    NEW.aliases := ARRAY(SELECT a FROM unnest(NEW.aliases) a WHERE crm.norm(a) IS DISTINCT FROM crm.norm(NEW.name));
    IF crm.norm(NEW.name) IS DISTINCT FROM crm.norm(OLD.name)
       AND NOT EXISTS (SELECT 1 FROM unnest(NEW.aliases) a WHERE crm.norm(a) = crm.norm(OLD.name))
       AND NOT EXISTS (SELECT 1 FROM crm.projects p WHERE p.id <> NEW.id AND p.archived_at IS NULL
                       AND crm.named(crm.norm(OLD.name), p.name, p.aliases) IS TRUE) THEN
        NEW.aliases := array_append(NEW.aliases, OLD.name);
    END IF;
    RETURN NEW;
END $$;

-- Пристегнуть к проекту встречи без клиента, чья подпись называет ровно его. Вопрос разбора уходит:
-- он почти всегда о клиенте («Сигма Пэй нет в справочнике — завести?»), и ответ — сам заведённый клиент.
CREATE FUNCTION crm.meetings_claim(p_id uuid) RETURNS integer LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
DECLARE
    pname text;
    n integer;
BEGIN
    SELECT name INTO pname FROM crm.projects WHERE id = p_id AND archived_at IS NULL;
    IF pname IS NULL THEN
        RETURN 0;
    END IF;
    UPDATE crm.meetings m SET project_id = p_id, label = pname, question = NULL,
           title = CASE WHEN 'title' = ANY (m.locked) THEN crm.meeting_retitle(m.title, m.label, pname)
                        ELSE crm.meeting_title(pname, m.topic) END
     WHERE m.project_id IS NULL AND NOT ('project' = ANY (m.locked)) AND crm.norm(m.label) IS NOT NULL
       AND crm.project_named(m.label) = p_id AND crm.sees_project(m.owner_id, p_id);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END $$;

CREATE FUNCTION crm.project_meetings() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.name IS DISTINCT FROM OLD.name THEN
        -- label встречи — имя её проекта; руками владелец мог написать другое — тогда его не трогаем.
        UPDATE crm.meetings m SET
               label = CASE WHEN crm.norm(m.label) IS NULL OR crm.norm(m.label) = crm.norm(OLD.name)
                            THEN NEW.name ELSE m.label END,
               title = CASE WHEN 'title' = ANY (m.locked) THEN crm.meeting_retitle(m.title, OLD.name, NEW.name)
                            ELSE crm.meeting_title(CASE WHEN crm.norm(m.label) IS NULL OR crm.norm(m.label) = crm.norm(OLD.name)
                                                        THEN NEW.name ELSE m.label END, m.topic) END
         WHERE m.project_id = NEW.id AND crm.sees_project(m.owner_id, NEW.id);
    END IF;
    PERFORM crm.meetings_claim(NEW.id);
    RETURN NULL;
END $$;

CREATE TRIGGER old_name BEFORE UPDATE OF name ON crm.projects
    FOR EACH ROW EXECUTE FUNCTION crm.project_old_name();
CREATE TRIGGER meetings AFTER INSERT OR UPDATE OF name, aliases, archived_at ON crm.projects
    FOR EACH ROW EXECUTE FUNCTION crm.project_meetings();

COMMENT ON FUNCTION crm.project_named(text) IS 'Ровно один живой проект с этим именем или другим именем — его id; ноль или два — пусто.';
COMMENT ON FUNCTION crm.meetings_claim(uuid) IS 'Встречи без клиента, чья подпись называет ровно этот проект, — к нему (кроме замка project).';
COMMENT ON FUNCTION crm.project_meetings() IS 'Проект переименован — его встречи берут новое имя (label и название без замка; правленое руками — только начало «Старое:»); заведён, переименован, получил другое имя — пристёгивает встречи без клиента с его подписью.';
COMMENT ON FUNCTION crm.project_old_name() IS 'Старое имя переименованного проекта — в его aliases, если так не зовут другой живой проект.';

-- Встречи, которые разбор уже подписал именем существующего проекта, пристегнула dela_0011 (шаг 3, то же
-- правило); здесь — только правила на будущее.
