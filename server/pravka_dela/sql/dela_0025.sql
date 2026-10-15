-- Дела, миграция 25 (15.10.2026, аудит №9, №48): встреча — команде факт, итог и договорённости — владельцу.
--
-- С dela_0021 запись встречи в хронологии клиента ведёт сервер по карточке встречи: «<название>: <итог>» и
-- договорённости. Запись общая — её видит команда клиента (политика see по проекту), а итог встречи — пересказ разговора
-- владельца. Тесты закрепляли «команда видит итог», а запись встречи мог править и убирать сотрудник с правом edit (замок
-- touch ему не ставится — он встречи не видит, и при следующей правке карточки запись «возвращалась»).
--
-- Теперь (решение по умолчанию — «только факт», переключатель «Настройки → Что видит команда → Встречи» у владельца):
-- • Свод privacy.team {"meetings": "fact" | "summary"} (нет записи или кривое — fact) — crm.team_sees_meetings(владелец);
-- • crm.meeting_text(встреча) — прежнее правило текста записи одной функцией (название Дел, «название: итог», договорённости);
-- • crm.meeting_touch — fact: в записи только название (команда видит факт: «Клиент: тема», когда, сколько, с кем,
--   сделка), договорённостей нет; summary — как было. Остальное тело — без изменений (dela_0021, dela_0022);
-- • crm.v_interactions (security_invoker) — хронология для показа: запись встречи владельцу — с итогом и договорённостями с
--   карточки встречи (встречи под RLS «только владелец» — команде подстановки нет), team_sees — что из этого видит команда
--   (none — приватная, fact — владелец видит больше, чем лежит в строке, all — то же), ссылка на веб встреч (source_ref
--   записи встречи) — только хозяину записи (№48: пароль веба встреч — только владельцу);
-- • приватность записи: снять нельзя, поставить («скрыть от команды») — хозяин записи;
-- • запись встречи и пункт дайджеста правит и убирает только хозяин (политика upd без ветки edit для них).
--
-- Обратимо по смыслу: функции новые или с прежним телом плюс режим, вид новый, политика — прежняя плюс условие; строки не
-- трогаются — записи переписывает разово privacy-fix (или переключатель) после своей копии базы.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

-- ── Переключатель владельца ─────────────────────────────────────────────

CREATE FUNCTION crm.team_sees_meetings(p_owner text) RETURNS text LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT coalesce((SELECT CASE WHEN s.value ->> 'meetings' IN ('fact', 'summary') THEN s.value ->> 'meetings' END
                       FROM crm.svod s
                      WHERE s.owner_id = p_owner AND s.key = 'privacy.team' AND jsonb_typeof(s.value) = 'object'), 'fact')
$$;
COMMENT ON FUNCTION crm.team_sees_meetings(text) IS 'Что команда видит из встреч владельца (Свод privacy.team, аудит №9): fact — только факт (название «Клиент: тема», когда, сколько, с кем, сделка), summary — и итог с договорённостями. Нет записи или кривое — fact. SECURITY DEFINER: Свод под RLS «только своё», а запись встречи пересобирают и триггеры от имени команды.';

-- ── Текст записи встречи одной функцией ─────────────────────────────────

CREATE FUNCTION crm.meeting_text(m crm.meetings, OUT title text, OUT summary text, OUT next_step text)
LANGUAGE sql STABLE AS $$
    SELECT t.title,
           left(CASE WHEN nullif(btrim(m.summary), '') IS NOT NULL THEN t.title || ': ' || btrim(m.summary) ELSE t.title END,
                1500),
           nullif(left(array_to_string(ARRAY(
               SELECT btrim(CASE jsonb_typeof(a) WHEN 'string' THEN a #>> '{}'
                                 ELSE coalesce(a ->> 'text', a ->> 'title', a ->> 'what') END)
                 FROM jsonb_array_elements(coalesce(m.agreements, '[]'::jsonb)) a), '; '), 600), '')
      FROM (SELECT coalesce(nullif(btrim(m.title), ''), nullif(btrim(m.label), ''), nullif(btrim(m.source_title), ''),
                            'Встреча') AS title) t
$$;
COMMENT ON FUNCTION crm.meeting_text(crm.meetings) IS 'Текст записи встречи по карточке (dela_0021, аудит №9): title — название Дел (иначе подпись, название службы, «Встреча»), summary — «<название>: <итог>» до 1500 знаков, next_step — договорённости через «; » до 600.';

-- ── Запись встречи: факт команде (тело — dela_0021, режим — здесь) ───────────

CREATE OR REPLACE FUNCTION crm.meeting_touch(m_id uuid) RETURNS text LANGUAGE plpgsql SET search_path = crm, pg_temp AS $$
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
    v_text record;
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
    -- Замок touch у любого из группы (кроме личной встречи) — запись всей группы в руках владельца.
    held := EXISTS (SELECT 1 FROM crm.meetings WHERE id = ANY (grp) AND 'touch' = ANY (locked)
                                                 AND sphere IS DISTINCT FROM 'home');
    FOR x IN SELECT * FROM crm.meetings WHERE id = ANY (grp) AND id <> w.id LOOP
        CONTINUE WHEN held AND x.sphere IS DISTINCT FROM 'home';
        UPDATE crm.interactions i SET deleted_at = now()
         WHERE i.source = 'meeting' AND i.deleted_at IS NULL AND i.owner_id = x.owner_id
           AND crm.meeting_ref(i.source_ref) = crm.meeting_ref(coalesce(x.url, '/m/' || x.ref));
        twin_gone := twin_gone OR FOUND;
    END LOOP;
    src := coalesce(w.url, '/m/' || w.ref);
    IF crm.meeting_ref(src) IS NULL THEN
        RETURN CASE WHEN twin_gone THEN 'убрана у двойника' ELSE 'нечего' END;
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
    IF rec.id IS NULL AND NOT ('sphere' = ANY (w.locked))
       AND (w.named_by IS NULL OR w.named_by LIKE 'error%') THEN
        RETURN CASE WHEN twin_gone THEN 'убрана у двойника' ELSE 'ждёт разбора' END;
    END IF;
    v_kind := CASE w.kind WHEN 'zoom' THEN 'zoom' WHEN 'call' THEN 'call' ELSE 'meeting' END;
    SELECT * INTO v_text FROM crm.meeting_text(w);
    -- Что команда видит из встречи (аудит №9): fact — только название Дел («Клиент: тема»), договорённостей нет; итог и
    -- договорённости владелец видит с карточки встречи (crm.v_interactions). summary — «название: итог» и договорённости.
    IF crm.team_sees_meetings(w.owner_id) = 'summary' THEN
        v_summary := v_text.summary;
        v_next := v_text.next_step;
    ELSE
        v_summary := left(v_text.title, 1500);
        v_next := NULL;
    END IF;
    v_people := ARRAY(SELECT u.pid FROM unnest(w.person_ids) WITH ORDINALITY AS u(pid, i)
                       WHERE NOT EXISTS (SELECT 1 FROM crm.people pe WHERE pe.id = u.pid AND pe.user_id IS NOT NULL)
                         AND NOT EXISTS (SELECT 1 FROM crm.users us WHERE us.person_id = u.pid)
                       ORDER BY u.i);
    v_deal := crm.meeting_deal(w.id);
    -- Точка для режима «команда не видит встреч вовсе» (аудит №9, §8 контракта): здесь стало бы true.
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
COMMENT ON FUNCTION crm.meeting_touch(uuid) IS 'Запись встречи в хронологии по её карточке (аудит №6, №9): с 01.09, рабочая, клиент — клиент, у полного из двойников — заводит (только разобранной встрече или со сферой от владельца) или правит; иначе живую запись убирает. Текст — по переключателю владельца (crm.team_sees_meetings): fact — название Дел без договорённостей, summary — «название: итог» и договорённости. Замок touch у любого из двойников — запись группы в руках владельца (кроме личной встречи). Ответ — что сделано.';

-- Встреча по номеру записи (вид ниже и interaction.add старой службы).
CREATE INDEX meetings_owner_ref ON crm.meetings (owner_id, crm.meeting_ref(coalesce(url, '/m/' || ref)));

-- ── Хронология для показа ───────────────────────────────────────────────

CREATE VIEW crm.v_interactions WITH (security_invoker = true) AS
SELECT i.id, i.at, i.kind,
       CASE WHEN mt.title IS NOT NULL THEN mt.summary ELSE i.summary END AS summary,
       CASE WHEN mt.title IS NOT NULL THEN mt.next_step ELSE i.next_step END AS next_step,
       i.project_id, i.deal_id, i.person_ids, i.source,
       CASE WHEN i.source = 'meeting' AND crm.me() IS DISTINCT FROM i.owner_id AND crm.me() IS DISTINCT FROM 'system'
            THEN NULL ELSE i.source_ref END AS source_ref,
       i.owner_id, i.notion_id, i.created_at, i.updated_at, i.rev, i.seq, i.duration_min, i.deleted_at, i.private,
       i.mention,
       CASE WHEN i.private THEN 'none'
            WHEN mt.title IS NOT NULL AND (mt.summary IS DISTINCT FROM i.summary OR mt.next_step IS DISTINCT FROM i.next_step)
            THEN 'fact'
            ELSE 'all' END AS team_sees
FROM crm.interactions i
LEFT JOIN LATERAL (
    SELECT t.title, t.summary, t.next_step
      FROM crm.meetings m CROSS JOIN LATERAL crm.meeting_text(m) t
     WHERE i.source = 'meeting' AND m.owner_id = i.owner_id
       AND crm.meeting_ref(coalesce(m.url, '/m/' || m.ref)) = crm.meeting_ref(i.source_ref)
       AND m.started_at >= crm.meeting_touch_from() AND NOT ('touch' = ANY (m.locked))
     ORDER BY m.chars_n DESC, m.id::text LIMIT 1) mt ON true;
COMMENT ON VIEW crm.v_interactions IS 'Хронология для показа (аудит №9, №48): все колонки crm.interactions; у записи встречи владельцу — итог и договорённости с карточки встречи (crm.meeting_text; встречи под RLS «только владелец» — команде подстановки нет, ей — что лежит в строке: при «только факт» — название), кроме записи под замком touch (её текст — слово владельца) и архива до 01.09; source_ref записи встречи (ссылка на веб встреч) — только хозяину записи; team_sees — что команда видит из этой записи: none (приватная), fact (владелец видит больше, чем лежит в строке), all. Новый читатель хронологии для людей — этот вид; даты «последнего контакта» — по crm.interactions.';

-- ── Приватность записи: снять нельзя, поставить — хозяин ─────────────────

CREATE OR REPLACE FUNCTION crm.interaction_private() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.private AND NOT NEW.private THEN
        RAISE EXCEPTION 'Дела: приватность записи хронологии не снимается — запись из Telegram и скрытую от команды не открывают задним числом';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.private AND NOT OLD.private
       AND crm.me() IS DISTINCT FROM 'system' AND crm.me() IS DISTINCT FROM NEW.owner_id THEN
        RAISE EXCEPTION 'Дела: скрыть запись хронологии от команды может только её хозяин';
    END IF;
    -- У приватной записи журнала нет, а правило «без dela.actor не пишем» держал журнал — держим здесь.
    IF NEW.private AND crm.actor() IS NULL THEN
        RAISE EXCEPTION 'Дела: изменение без dela.actor — некому его подписать (crm.interactions)';
    END IF;
    RETURN NEW;
END $$;

-- Запись встречи и пункт дайджеста правит и убирает только хозяин записи (Саша): сотрудник с edit видит запись встречи,
-- но её не трогает — иначе при следующей правке карточки она «возвращалась» бы (замок touch ему не поставить).
DROP POLICY upd ON crm.interactions;
CREATE POLICY upd ON crm.interactions FOR UPDATE
    USING (crm.me() IN (owner_id, 'system')
           OR (NOT private AND source NOT IN ('meeting', 'digest') AND project_id IS NOT NULL
               AND crm.edits_project(crm.me(), project_id)));
