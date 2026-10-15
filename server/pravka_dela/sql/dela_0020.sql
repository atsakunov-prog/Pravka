-- Дела, миграция 20 (15.10.2026, аудит, №1): своё имя человека сильнее чужого другого имени.
--
-- С 08.10 короткое имя сотрудника стало ещё и другим именем (aliases) второй карточки — из сверки контактов. crm.match_name
-- сравнивал имя, короткое имя и другие имена на равных и требовал ровно одного совпадения: «Ваня» совпал с двумя, и
-- почти все вопросы «Подскажи» с 09.10 были из-за этого, а дела после «Да» оставались без человека.
--
-- Теперь люди — ярусами: сначала точное имя или короткое имя карточки (одна — она; две и больше — никого, к другим
-- именам не идём: тёзки по-настоящему двусмысленны), потом другое имя. Проекты и компании — как было. Тот же разбор
-- зовёт life.entries (клиент ленты Засечки, life_dela.sql) — там станет только однозначнее.
-- Обратимо по смыслу: прежнее тело — в dela_0001.sql; данные не трогаются.

CREATE OR REPLACE FUNCTION crm.match_name(s text, OUT project_id uuid, OUT org_id uuid, OUT person_id uuid)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = crm, pg_temp AS $$
DECLARE
    n text := crm.norm(s);
    ids uuid[];
BEGIN
    IF n IS NULL THEN
        RETURN;
    END IF;
    ids := ARRAY(SELECT p.id FROM crm.projects p WHERE p.archived_at IS NULL AND crm.named(n, p.name, p.aliases));
    IF cardinality(ids) = 1 THEN
        project_id := ids[1];
        SELECT p.org_id INTO org_id FROM crm.projects p WHERE p.id = project_id;
        RETURN;
    END IF;
    -- Ярус 1: своё имя или короткое имя живой карточки.
    ids := ARRAY(SELECT pe.id FROM crm.people pe
                 WHERE pe.archived_at IS NULL AND pe.merged_into IS NULL
                   AND (n = crm.norm(pe.name) OR n = crm.norm(pe.short)));
    -- Ярус 2: другое имя — только если своим так не зовут никого.
    IF cardinality(ids) = 0 THEN
        ids := ARRAY(SELECT pe.id FROM crm.people pe
                     WHERE pe.archived_at IS NULL AND pe.merged_into IS NULL
                       AND n = ANY (ARRAY(SELECT crm.norm(a) FROM unnest(pe.aliases) a)));
    END IF;
    IF cardinality(ids) = 1 THEN
        person_id := ids[1];
        SELECT pe.org_id INTO org_id FROM crm.people pe WHERE pe.id = person_id;
    ELSE
        ids := ARRAY(SELECT o.id FROM crm.orgs o WHERE o.archived_at IS NULL AND crm.named(n, o.name, o.aliases));
        IF cardinality(ids) = 1 THEN
            org_id := ids[1];
        END IF;
    END IF;
    IF org_id IS NOT NULL THEN
        ids := ARRAY(SELECT p.id FROM crm.projects p WHERE p.org_id = match_name.org_id AND p.archived_at IS NULL);
        IF cardinality(ids) = 1 THEN
            project_id := ids[1];
        END IF;
    END IF;
END $$;

COMMENT ON FUNCTION crm.match_name(text) IS 'Что за клиент записан текстом: проект (имя или другое имя), человек ярусами (своё имя или короткое имя, потом другое имя; двое на ярусе — никого), компания. Человек тянет свою компанию, компания с единственным живым проектом — этот проект. dela_0020 (15.10.2026).';
