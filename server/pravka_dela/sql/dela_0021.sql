-- Дела, миграция 21 (15.10.2026, аудит №6, №43, №3): одна правда о встрече — её карточка в Делах.
--
-- До этого запись встречи в хронологии клиента (crm.interactions, source = meeting) писала служба встреч сама, один раз
-- при сверке: клиента выбирала голосованием, людей — словарём имён. Карточку встречи (клиент, сделка, участники, рабочая
-- или личная) независимо ведут разборщик Дел и правки владельца — и до хронологии они не доходили: у 3 записей из 26
-- клиент не тот, у 7 нет людей, у всех 27 нет сделки, хотя у 106 встреч на карточке она стоит (№3, №6). Название встречи
-- Дела переименовали у 196 из 199, а пачки «Нового» так и жили со старым (№43).
--
-- Теперь запись встречи ведёт сервер по карточке (crm.meeting_touch): заведена, поправлена, переименован клиент — запись
-- следом; стала личной, без клиента или двойником — убрана. Одна живая запись на встречу — уникальным индексом. Запись
-- убрал или поправил сам владелец — замок touch: сервер её больше не трогает (владелец сильнее автоматики, как другие
-- замки встречи). Пачки «Нового» встречи берут название Дел (crm.meeting_batches_retitle). Архив до 01.09 не догоняем и
-- не трогаем (правило 20). Записи по карточкам заводит разово команда «meetings touch» после установки, не миграция.
--
-- Обратимо по смыслу: прежние записи не удаляются (лишние живые дубли — deleted_at, их на 14.10 ноль), функции новые,
-- триггер клиента — прежние правила плюс два вызова.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

-- ── Замок touch: запись встречи — в руках владельца ────────────────────────────────

DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c FROM pg_constraint
    WHERE conrelid = 'crm.meetings'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%locked <@%';
    IF c IS NOT NULL THEN
        EXECUTE format('ALTER TABLE crm.meetings DROP CONSTRAINT %I', c);
    END IF;
END $$;
ALTER TABLE crm.meetings ADD CONSTRAINT meetings_locked_check
    CHECK (locked <@ '{title,topic,project,sphere,people,touch}'::text[]);
COMMENT ON COLUMN crm.meetings.locked IS 'Что владелец поправил руками (title, topic, project, sphere, people): разбор это не переписывает. touch — запись встречи в хронологии в руках владельца (убрал или поправил сам): сервер её не трогает и не возвращает (meeting.set unlock touch — снова по карточке).';

-- Решения человека о записи встречи, принятые до этой миграции, — тоже замок touch. Замка тогда не было: запись, которую
-- владелец убрал (deleted_at) или переписал своими словами, crm.meeting_touch завёл бы заново или затёр данными карточки
-- при первом же meeting.upsert, переразборе или переименовании клиента (проверка 15.10.2026). Человек — любая правка
-- записи в журнале не от службы (actor не «svc:…»): владелец, кто-то из команды, Claude от их имени. Номера таких встреч
-- печатает «meetings touch --dry» отдельной строкой («под замком»); вернуть встречу правилу — meeting.set unlock touch.
UPDATE crm.meetings m SET locked = array_append(m.locked, 'touch')
 WHERE NOT ('touch' = ANY (m.locked))
   AND EXISTS (SELECT 1 FROM crm.interactions i
                 JOIN crm.history h ON h.entity = 'crm.interactions' AND h.entity_id = i.id::text AND h.op = 'update'
                                   AND h.actor NOT LIKE 'svc:%'
                WHERE i.source = 'meeting' AND i.owner_id = m.owner_id
                  AND crm.meeting_ref(i.source_ref) = crm.meeting_ref(coalesce(m.url, '/m/' || m.ref)));

-- ── Одна живая запись на встречу ───────────────────────────────────────

-- Живые дубли записей одной встречи (на 14.10 их 0 — служба глушила их сама, _twin): остаётся первая, лишним — deleted_at.
UPDATE crm.interactions i SET deleted_at = now()
 WHERE i.source = 'meeting' AND i.deleted_at IS NULL AND crm.meeting_ref(i.source_ref) IS NOT NULL
   AND EXISTS (SELECT 1 FROM crm.interactions j
                WHERE j.source = 'meeting' AND j.deleted_at IS NULL AND j.owner_id = i.owner_id
                  AND crm.meeting_ref(j.source_ref) = crm.meeting_ref(i.source_ref)
                  AND (j.created_at, j.id::text) < (i.created_at, i.id::text));

CREATE UNIQUE INDEX interactions_meeting_live ON crm.interactions (owner_id, crm.meeting_ref(source_ref))
    WHERE source = 'meeting' AND deleted_at IS NULL;

-- ── Запись встречи по её карточке ──────────────────────────────────────

-- Граница «архив не догоняем» (правило 20): встречи раньше её запись не получают и свою не теряют.
CREATE FUNCTION crm.meeting_touch_from() RETURNS timestamptz LANGUAGE sql IMMUTABLE AS
$$ SELECT timestamptz '2026-09-01 00:00:00+03' $$;

-- Сделка записи: сделка карточки встречи (№3). Пакет «сделка касания» (dela-3) заменит её правилом crm.touch_deal.
CREATE FUNCTION crm.meeting_deal(m_id uuid) RETURNS uuid LANGUAGE sql STABLE AS
$$ SELECT deal_id FROM crm.meetings WHERE id = m_id $$;

-- Двойник — другая живая встреча того же владельца, чей интервал «начало + длительность» перекрывает эту не меньше чем
-- на половину более короткой (Zoom и Plaud одной встречи). Не «тот же клиент ±3 ч», как у службы: так вторая настоящая
-- встреча дня с тем же клиентом пропадала бы. Сама встреча может быть и в архиве: убрали её — запись переходит к живому
-- двойнику.
CREATE FUNCTION crm.meeting_twins(m_id uuid) RETURNS SETOF uuid LANGUAGE sql STABLE AS $$
    SELECT o.id FROM crm.meetings m JOIN crm.meetings o
      ON o.owner_id = m.owner_id AND o.id <> m.id AND o.archived_at IS NULL
     AND coalesce(m.duration_min, 0) > 0 AND coalesce(o.duration_min, 0) > 0
     AND least(m.started_at + make_interval(mins => m.duration_min), o.started_at + make_interval(mins => o.duration_min))
         - greatest(m.started_at, o.started_at)
         >= make_interval(mins => least(m.duration_min, o.duration_min)) / 2
     WHERE m.id = m_id
$$;

-- Запись встречи по карточке: заводит, правит или убирает живую запись (source = meeting, ключ — номер встречи).
-- Подходит — с 01.09, живая, рабочая, клиент — клиент, у самого полного из двойников (больше текста, при равенстве —
-- меньший id). Новую запись заводит только разобранной встрече (разбор хоть раз лёг — named_by) или той, чью сферу
-- поставил владелец (замок sphere): до разбора «рабочая» и клиент — подсказка службы (её голосование давало «клиент не тот» у 3 записей из 26),
-- а запись общая — команда увидела бы личную встречу раньше, чем разбор её погасит (инцидент 09.10). Живую запись
-- правит и до разбора: переразбор новой расшифровки её не гасит. Замок touch — запись в руках владельца (убрал или
-- поправил сам): сервер её не трогает — ни правит, ни убирает, ни заводит заново; замок любого из двойников — замок
-- всей группы (иначе запись вернулась бы через вторую половину, ставшую полнее); кроме личной встречи — её запись
-- гаснет всегда (точка №9). Ответ — что сделано словом (для «meetings touch --dry»): заведена, обновлена, убрана,
-- убрана у двойника, под замком, ждёт разбора, архив, нечего.
CREATE FUNCTION crm.meeting_touch(m_id uuid) RETURNS text LANGUAGE plpgsql SET search_path = crm, pg_temp AS $$
DECLARE
    m crm.meetings;
    w crm.meetings;
    x crm.meetings;
    grp uuid[];
    held boolean;
    src text;
    rec crm.interactions;
    ok boolean;
    twin_gone boolean := false;
    v_kind text;
    v_title text;
    v_summary text;
    v_next text;
    v_people uuid[];
    v_deal uuid;
    v_private boolean;
BEGIN
    SELECT * INTO m FROM crm.meetings WHERE id = m_id;
    IF NOT FOUND THEN
        RETURN 'не видна';
    END IF;
    IF m.started_at < crm.meeting_touch_from() THEN
        RETURN 'архив';
    END IF;
    -- Группа двойников и кто в ней ведёт запись: самая полная живая, при равенстве — меньший id.
    grp := ARRAY(SELECT m_id UNION SELECT crm.meeting_twins(m_id));
    SELECT * INTO w FROM crm.meetings WHERE id = ANY (grp) AND archived_at IS NULL
     ORDER BY chars_n DESC, id::text LIMIT 1;
    IF NOT FOUND THEN
        w := m;
    END IF;
    -- Замок touch у любого из группы (кроме личной встречи) — запись всей группы в руках владельца: он убрал запись
    -- половины Zoom, а вторая половина (Plaud) стала полнее и повела — без этого запись вернулась бы через неё.
    held := EXISTS (SELECT 1 FROM crm.meetings WHERE id = ANY (grp) AND 'touch' = ANY (locked)
                                                 AND sphere IS DISTINCT FROM 'home');
    -- Остальные из группы записи не держат; под замком группы чужие записи не трогаем — кроме личной встречи.
    FOR x IN SELECT * FROM crm.meetings WHERE id = ANY (grp) AND id <> w.id LOOP
        CONTINUE WHEN held AND x.sphere IS DISTINCT FROM 'home';
        UPDATE crm.interactions i SET deleted_at = now()
         WHERE i.source = 'meeting' AND i.deleted_at IS NULL AND i.owner_id = x.owner_id
           AND crm.meeting_ref(i.source_ref) = crm.meeting_ref(coalesce(x.url, '/m/' || x.ref));
        twin_gone := twin_gone OR FOUND;
    END LOOP;
    src := coalesce(w.url, '/m/' || w.ref);
    IF crm.meeting_ref(src) IS NULL THEN
        RETURN CASE WHEN twin_gone THEN 'убрана у двойника' ELSE 'нечего' END;  -- без номера записи не по чему держаться
    END IF;
    IF held AND w.sphere IS DISTINCT FROM 'home' THEN
        RETURN CASE WHEN twin_gone THEN 'убрана у двойника' ELSE 'под замком' END;
    END IF;
    SELECT * INTO rec FROM crm.interactions i
     WHERE i.source = 'meeting' AND i.deleted_at IS NULL AND i.owner_id = w.owner_id
       AND crm.meeting_ref(i.source_ref) = crm.meeting_ref(src) FOR UPDATE;
    ok := w.archived_at IS NULL AND w.sphere = 'work' AND w.started_at >= crm.meeting_touch_from()
          AND EXISTS (SELECT 1 FROM crm.projects p WHERE p.id = w.project_id AND p.kind = 'client');
    IF NOT ok THEN
        IF rec.id IS NOT NULL THEN
            UPDATE crm.interactions SET deleted_at = now() WHERE id = rec.id;
            RETURN 'убрана';
        END IF;
        RETURN CASE WHEN twin_gone THEN 'убрана у двойника' ELSE 'нечего' END;
    END IF;
    -- Новую запись — только разобранной встрече или со сферой от владельца: до разбора «рабочая» и клиент — подсказка
    -- службы, а запись видит команда клиента (09.10 так в хронологию клиента ушла личная встреча). Разобрана — разбор
    -- хоть раз лёг (named_by — модель; «error: …» — разбор не вышел, а named_at стоит): новая расшифровка сбрасывает
    -- named_at, но сфера и клиент остаются от разбора — подсказка службы их не перебивает. Живую запись — правим.
    IF rec.id IS NULL AND NOT ('sphere' = ANY (w.locked))
       AND (w.named_by IS NULL OR w.named_by LIKE 'error%') THEN
        RETURN CASE WHEN twin_gone THEN 'убрана у двойника' ELSE 'ждёт разбора' END;
    END IF;
    v_kind := CASE w.kind WHEN 'zoom' THEN 'zoom' WHEN 'call' THEN 'call' ELSE 'meeting' END;
    v_title := coalesce(nullif(btrim(w.title), ''), nullif(btrim(w.label), ''), nullif(btrim(w.source_title), ''), 'Встреча');
    v_summary := left(CASE WHEN nullif(btrim(w.summary), '') IS NOT NULL THEN v_title || ': ' || btrim(w.summary)
                           ELSE v_title END, 1500);
    v_next := nullif(left(array_to_string(ARRAY(
                  SELECT btrim(CASE jsonb_typeof(a) WHEN 'string' THEN a #>> '{}'
                                    ELSE coalesce(a ->> 'text', a ->> 'title', a ->> 'what') END)
                    FROM jsonb_array_elements(w.agreements) a), '; '), 600), '');
    -- Люди клиента: участники карточки без команды (у кого есть учётка Дел) и без самого владельца.
    v_people := ARRAY(SELECT u.pid FROM unnest(w.person_ids) WITH ORDINALITY AS u(pid, i)
                       WHERE NOT EXISTS (SELECT 1 FROM crm.people pe WHERE pe.id = u.pid AND pe.user_id IS NOT NULL)
                         AND NOT EXISTS (SELECT 1 FROM crm.users us WHERE us.person_id = u.pid)
                       ORDER BY u.i);
    v_deal := crm.meeting_deal(w.id);
    -- Точка для №9 (пакет «Приватность»): что команда видит из встречи, решается здесь, одной строкой. Сегодня запись
    -- общая — как у записи службы встреч до 15.10; личная встреча (sphere = home) записи не получает вовсе (выше).
    v_private := false;
    IF rec.id IS NULL THEN
        INSERT INTO crm.interactions (at, kind, summary, next_step, project_id, deal_id, person_ids, source, source_ref,
                                      duration_min, owner_id, private)
        VALUES (w.started_at, v_kind, v_summary, v_next, w.project_id, v_deal, v_people, 'meeting', src,
                nullif(w.duration_min, 0), w.owner_id, v_private);
        RETURN 'заведена';
    END IF;
    UPDATE crm.interactions SET at = w.started_at, kind = v_kind, summary = v_summary, next_step = v_next,
           project_id = w.project_id, deal_id = v_deal, person_ids = v_people, duration_min = nullif(w.duration_min, 0)
     WHERE id = rec.id
       AND (at, kind, summary, next_step, project_id, deal_id, person_ids, duration_min)
           IS DISTINCT FROM (w.started_at, v_kind, v_summary, v_next, w.project_id, v_deal, v_people, nullif(w.duration_min, 0));
    IF FOUND THEN
        RETURN 'обновлена';
    END IF;
    RETURN CASE WHEN twin_gone THEN 'убрана у двойника' ELSE 'нечего' END;
END $$;

COMMENT ON FUNCTION crm.meeting_touch(uuid) IS 'Запись встречи в хронологии по её карточке (аудит №6, 15.10.2026): с 01.09, рабочая, клиент — клиент, у полного из двойников — заводит (только разобранной встрече или со сферой от владельца) или правит (название Дел и итог, договорённости, клиент, сделка, люди без команды); иначе живую запись убирает. Замок touch у любого из двойников — запись группы в руках владельца (кроме личной встречи). Ответ — что сделано.';

-- ── Пачки «Нового» встречи — названием Дел (№43) ─────────────────────────

CREATE FUNCTION crm.meeting_batches_retitle(m_id uuid) RETURNS integer LANGUAGE sql AS $$
    WITH m AS (
        SELECT ref, owner_id, btrim(title) || ' · ' || to_char(started_at AT TIME ZONE 'Europe/Moscow', 'DD.MM') AS t
          FROM crm.meetings WHERE id = m_id AND nullif(btrim(title), '') IS NOT NULL),
    upd AS (
        UPDATE tasks.suggestions s SET batch_title = m.t FROM m
         WHERE s.batch_ref = 'meeting:' || m.ref AND s.for_user = m.owner_id AND s.batch_title IS DISTINCT FROM m.t
        RETURNING 1)
    SELECT count(*)::integer FROM upd
$$;
COMMENT ON FUNCTION crm.meeting_batches_retitle(uuid) IS 'Пачка «Нового» встречи (batch_ref meeting:<ref>, любого статуса) берёт название Дел: «Клиент: тема · дд.мм» (аудит №43).';

-- ── Клиент переименован, заведён или стал клиентом — записи и пачки его встреч следом ──

CREATE OR REPLACE FUNCTION crm.project_meetings() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
DECLARE
    mid uuid;
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
    -- Название и клиент встречи поменялись — пачки «Нового» и запись в хронологии следом (аудит №43, №6). Только когда
    -- было что: имя, другие имена (пристегнулись встречи), архив и вид (стал клиентом).
    IF TG_OP = 'INSERT' OR NEW.name IS DISTINCT FROM OLD.name OR NEW.aliases IS DISTINCT FROM OLD.aliases
       OR NEW.archived_at IS DISTINCT FROM OLD.archived_at OR NEW.kind IS DISTINCT FROM OLD.kind THEN
        FOR mid IN SELECT m.id FROM crm.meetings m WHERE m.project_id = NEW.id LOOP
            PERFORM crm.meeting_batches_retitle(mid);
            PERFORM crm.meeting_touch(mid);
        END LOOP;
    END IF;
    RETURN NULL;
END $$;

DROP TRIGGER meetings ON crm.projects;
CREATE TRIGGER meetings AFTER INSERT OR UPDATE OF name, aliases, archived_at, kind ON crm.projects
    FOR EACH ROW EXECUTE FUNCTION crm.project_meetings();

COMMENT ON FUNCTION crm.project_meetings() IS 'Проект переименован — его встречи берут новое имя (label и название без замка; правленое руками — только начало «Старое:»); заведён, переименован, получил другое имя — пристёгивает встречи без клиента с его подписью; затем пачки «Нового» его встреч берут название Дел, а записи в хронологии — клиента и вид (dela_0021).';
