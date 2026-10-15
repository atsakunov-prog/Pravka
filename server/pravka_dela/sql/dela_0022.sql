-- Дела, миграция 22 (15.10.2026, аудит №2, №3, №7, №28): шаги и контакт сделки — одним правилом, упоминание — не контакт.
--
-- Следующий шаг сделки считался только по делам со сделкой (tasks.deal_id), а с 05.10 автоматика (встречи, Telegram) не
-- поставила сделку ни одному делу: из 23 живых сделок 8 горели «застывшими», у 3 — наверняка ложно (у клиента одна живая
-- сделка, и его открытые дела есть). Запись встречи ложилась в хронологию без сделки — карточка сделки не видела ни одной
-- из 106 своих встреч. «Последний контакт» сделки веб и коннектор считали по-разному (веб — и записи клиента без сделки,
-- коннектор — только сделки). Упоминание клиента в сводке Telegram («обсуждали Ромашку» в командном чате) считалось
-- контактом с ним: три клиента выглядели свежими только из-за разговоров о них.
--
-- Теперь:
-- - crm.only_deal(клиент) — его единственная живая сделка (или пусто). Пока сделка у клиента одна, его открытые дела без
--   сделки — её шаги, а записи хронологии без сделки — её хронология (не из Notion и не раньше её заведения);
-- - crm.touch_deal(клиент, люди, когда, имя) — «сделка касания», одно правило для записи, у которой сделку не назвали:
--   названа и есть у клиента — она; одна живая — она; иначе живая, где есть кто-то из людей, если такая одна; иначе пусто.
--   Её зовут interaction.add без ключа deal_id, запись встречи (crm.meeting_deal — карточка без сделки) и дайджест;
-- - crm.deal_records(сделка) — записи хронологии сделки по этому правилу, crm.deal_last_touch(сделка) — их последняя
--   дата без упоминаний: одна для веба, телефона и коннектора (life.deals);
-- - crm.interactions.mention — запись-упоминание (клиент или человек назван в теме сводки, а его чата в окне нет): в
--   хронологии видна, контактом не считается.
--
-- Обратимо по смыслу: колонка новая (у всех прежних записей — false, как было), функции новые, meeting_deal — прежнее
-- правило (сделка карточки) плюс «сделка касания», когда в карточке пусто. Флаг упоминания старым записям дайджеста
-- ставит разово команда digest-relink (после установки и своей копии базы), не миграция.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

-- ── Упоминание — не контакт (№28) ──────────────────────────────────────

ALTER TABLE crm.interactions ADD COLUMN mention boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN crm.interactions.mention IS 'Упоминание, а не контакт (аудит №28): клиент или человек назван в теме пункта сводки Telegram, а его чата (личного или группы клиента) в окне нет. В хронологии запись видна («упоминание»), но «последний контакт» клиента, сделки и человека она не двигает.';

-- ── Единственная живая сделка клиента (№2, №3, №7) ──────────────────────

-- SECURITY DEFINER: её зовут и виды life (роль Claude прав на crm не имеет); видимость — явно: клиента должен видеть тот,
-- кто спрашивает (в life — владелец Дел, как у остальных видов life). Сделки клиента видны все разом (политика сделок —
-- по клиенту), поэтому ответ у владельца и у команды один.
CREATE FUNCTION crm.only_deal(p_project uuid) RETURNS uuid LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT CASE WHEN count(*) = 1 THEN (array_agg(d.id))[1] END
      FROM crm.deals d
     WHERE d.project_id = p_project AND d.stage <> 'archive'
       AND crm.sees_project(coalesce(crm.me(), crm.owner_id()), p_project)
$$;
COMMENT ON FUNCTION crm.only_deal(uuid) IS 'Единственная живая (не архив) сделка клиента или NULL (аудит №2, 15.10.2026): пока она одна, открытые дела клиента без сделки — её шаги, записи хронологии клиента без сделки — её хронология.';

-- С какого дня сделка «была» для записей без сделки: заведённая в Делах — с дня заведения (по Москве), перенесённая из
-- Notion — без нижней границы (в Делах она с дня переноса, а жила раньше).
CREATE FUNCTION crm.deal_from(p_deal uuid) RETURNS date LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT CASE WHEN d.notion_id IS NULL THEN (d.created_at AT TIME ZONE 'Europe/Moscow')::date END
      FROM crm.deals d WHERE d.id = p_deal
$$;

-- ── Сделка касания (№3) ─────────────────────────────────────────────────

-- Запись хронологии без названной сделки: названа и есть у клиента — она (любая стадия: назвал человек); у клиента одна
-- живая — она; иначе живая сделка клиента, где есть кто-то из persons (ведёт, привёл, команда, люди клиента), если такая
-- одна; иначе NULL. Сделка, заведённая в Делах позже дня записи, её не берёт (у переноса из Notion границы нет).
-- SECURITY INVOKER: что видно — решает RLS того, кто пишет запись.
CREATE FUNCTION crm.touch_deal(p_project uuid, p_persons uuid[], p_at timestamptz, p_named text DEFAULT NULL)
RETURNS uuid LANGUAGE plpgsql STABLE SET search_path = crm, pg_temp AS $$
DECLARE
    v uuid;
    v_day date := (p_at AT TIME ZONE 'Europe/Moscow')::date;
BEGIN
    IF p_project IS NULL THEN
        RETURN NULL;
    END IF;
    IF crm.norm(p_named) IS NOT NULL THEN
        SELECT d.id INTO v FROM crm.deals d
         WHERE d.project_id = p_project AND crm.norm(d.name) = crm.norm(p_named)
         ORDER BY d.stage = 'archive', d.created_at DESC LIMIT 1;
        IF FOUND THEN
            RETURN v;
        END IF;
    END IF;
    v := crm.only_deal(p_project);
    IF v IS NOT NULL THEN
        RETURN CASE WHEN v_day IS NULL OR crm.deal_from(v) IS NULL OR crm.deal_from(v) <= v_day THEN v END;
    END IF;
    IF coalesce(cardinality(p_persons), 0) = 0 THEN
        RETURN NULL;
    END IF;
    SELECT CASE WHEN count(*) = 1 THEN (array_agg(d.id))[1] END INTO v FROM crm.deals d
     WHERE d.project_id = p_project AND d.stage <> 'archive'
       AND (d.lead_person_id = ANY (p_persons) OR d.source_person_id = ANY (p_persons)
            OR d.team_ids && p_persons OR d.person_ids && p_persons)
       AND (v_day IS NULL OR d.notion_id IS NOT NULL OR (d.created_at AT TIME ZONE 'Europe/Moscow')::date <= v_day);
    RETURN v;
END $$;
COMMENT ON FUNCTION crm.touch_deal(uuid, uuid[], timestamptz, text) IS 'Сделка касания (аудит №3, 15.10.2026): у записи без названной сделки — названная у клиента, иначе его единственная живая, иначе единственная живая с кем-то из людей записи, иначе NULL; сделка, заведённая в Делах позже дня записи, не берёт её. Зовут interaction.add без ключа deal_id, crm.meeting_deal и дайджест (digest_crm.apply).';

-- Сделка записи встречи (dela_0021): сделка карточки, а в карточке пусто — сделка касания по людям клиента встречи (без
-- команды и владельца — то же правило, что люди записи в crm.meeting_touch).
CREATE OR REPLACE FUNCTION crm.meeting_deal(m_id uuid) RETURNS uuid LANGUAGE sql STABLE AS $$
    SELECT coalesce(m.deal_id, crm.touch_deal(m.project_id, ARRAY(
               SELECT u.pid FROM unnest(m.person_ids) AS u(pid)
                WHERE NOT EXISTS (SELECT 1 FROM crm.people pe WHERE pe.id = u.pid AND pe.user_id IS NOT NULL)
                  AND NOT EXISTS (SELECT 1 FROM crm.users us WHERE us.person_id = u.pid)), m.started_at))
      FROM crm.meetings m WHERE m.id = m_id
$$;
COMMENT ON FUNCTION crm.meeting_deal(uuid) IS 'Сделка записи встречи в хронологии: сделка карточки встречи, пусто — сделка касания (crm.touch_deal) по людям клиента встречи (аудит №3).';

-- ── Хронология и последний контакт сделки (№3, №7) ─────────────────────

-- Записи хронологии сделки: её собственные и — пока сделка у клиента одна живая — записи клиента без сделки, кроме
-- перенесённых из Notion и раньше дня её заведения (у переноса из Notion — без нижней границы). Видимость — та же, что у
-- политики see записей (crm.interactions): своё — всегда, чужое — не приватное в видимом клиенте; в life (dela.user не
-- задан) — как видит владелец Дел. SECURITY DEFINER — ради life.deals: роль Claude прав на crm не имеет.
CREATE FUNCTION crm.deal_records(p_deal uuid) RETURNS SETOF uuid LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    WITH v AS (SELECT coalesce(crm.me(), crm.owner_id()) AS u),
    d AS (SELECT x.id, x.project_id, crm.deal_from(x.id) AS since, crm.only_deal(x.project_id) = x.id AS solo
            FROM crm.deals x, v WHERE x.id = p_deal AND crm.sees_project(v.u, x.project_id))
    SELECT i.id FROM d, v, crm.interactions i
     WHERE i.deleted_at IS NULL
       AND (i.deal_id = d.id
            OR (d.solo AND i.deal_id IS NULL AND i.project_id = d.project_id AND i.source NOT IN ('notion', 'import')
                AND (d.since IS NULL OR (i.at AT TIME ZONE 'Europe/Moscow')::date >= d.since)))
       AND (v.u IN (i.owner_id, 'system')
            OR (NOT i.private AND i.project_id IS NOT NULL AND crm.sees_project(v.u, i.project_id)))
$$;
COMMENT ON FUNCTION crm.deal_records(uuid) IS 'Записи хронологии сделки (аудит №3, 15.10.2026): её собственные и, пока у клиента одна живая сделка, записи клиента без сделки (не Notion, не раньше дня заведения сделки). Видимость — как у политики see; без dela.user — как видит владелец Дел.';

CREATE FUNCTION crm.deal_last_touch(p_deal uuid) RETURNS timestamptz LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT max(i.at) FROM crm.interactions i WHERE i.id IN (SELECT crm.deal_records(p_deal)) AND NOT i.mention
$$;
COMMENT ON FUNCTION crm.deal_last_touch(uuid) IS 'Последний контакт сделки (аудит №7, 15.10.2026): самая свежая запись её хронологии (crm.deal_records) без упоминаний. Одна функция для веба, телефона (DEALS_SQL) и коннектора (life.deals): приватную запись дайджеста команда не видит и здесь.';
