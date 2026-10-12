-- Дела, миграция 11 (13.10.2026): встречи, что уже в базе, — без вопросов о старом, тема без двоеточий,
-- «ЗФ» — проектом, участники, которых служба узнала по голосу.
--
-- Владелец, 13.10.2026: «догонять встречи не надо. Надо сделать только те, которые уже есть. Старые уже ушли в
-- прошлое». Архив служба встреч уже прислала (198 встреч, все названы); заново его не разбираем и запросов к
-- Claude не шлём — здесь только то, что выводится из уже лежащего, тем же правилом, что код (meetings.py) для
-- новых встреч. Каждая правка — в журнал (svc:migrate); поправленное владельцем (locked) не трогается.
-- Повтор безвреден: второй проход ничего не меняет.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

-- 1. Вопрос разбора — только о свежей встрече (meetings.QUESTION_DAYS = 7): все 36 вопросов архива были о
--    встречах старше недели («с кем был этот 5-минутный Zoom 22.06?») — у таких остаётся лучшая догадка.
UPDATE crm.meetings SET question = NULL
WHERE question IS NOT NULL AND started_at < now() - interval '7 days';

-- 2. Двоеточие в названии одно — после клиента: «Семья: урок математики: задачи» → «Семья: урок математики —
--    задачи» (meetings.clean_topic и title_of).
UPDATE crm.meetings m
SET topic = t.topic,
    title = CASE WHEN 'title' = ANY (m.locked) THEN m.title
                 ELSE coalesce(nullif(btrim(m.label), '') || ': ' || t.topic, nullif(btrim(m.label), ''), t.topic) END
FROM (SELECT id, nullif(btrim(regexp_replace(btrim(topic), '\s*:\s*', ' — ', 'g'), ' —'), '') AS topic
      FROM crm.meetings WHERE topic LIKE '%:%') t
WHERE t.id = m.id AND NOT ('topic' = ANY (m.locked));

-- 3. Подпись, совпавшая ровно с одним живым проектом, — это проект: «ЗФ» стоял в списке клиентов дважды —
--    служебным проектом и подписью без проекта.
UPDATE crm.meetings m
SET project_id = p.id,
    label = p.name,
    sphere = coalesce(m.sphere, CASE WHEN p.sphere = 'home' THEN 'home' ELSE 'work' END),
    title = CASE WHEN 'title' = ANY (m.locked) THEN m.title
                 ELSE coalesce(p.name || ': ' || nullif(btrim(m.topic), ''), p.name) END
FROM crm.projects p
WHERE m.project_id IS NULL AND m.label IS NOT NULL AND NOT ('project' = ANY (m.locked))
  AND p.archived_at IS NULL AND crm.named(crm.norm(m.label), p.name, p.aliases)
  AND (SELECT count(*) FROM crm.projects q
       WHERE q.archived_at IS NULL AND crm.named(crm.norm(m.label), q.name, q.aliases)) = 1;

-- 4. Участники, которых служба узнала по голосу и связала с карточкой (raw.speakers[].person_id), — в
--    участники встречи, кроме самого владельца; гость словами, который и есть такой человек, уходит из гостей.
--    Слитый дубль ведёт в живую карточку. Кривое или чужое в raw — мимо (только существующие люди).
WITH v AS (
    SELECT r.meeting_id,
           array_agg(DISTINCT coalesce(pe.merged_into, pe.id)) AS ids
    FROM crm.meeting_raw r
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(r.raw -> 'speakers') = 'array' THEN r.raw -> 'speakers' ELSE '[]'::jsonb END) AS e(x)
    JOIN crm.people pe ON jsonb_typeof(e.x) = 'object' AND pe.id::text = e.x ->> 'person_id'
    GROUP BY r.meeting_id
)
UPDATE crm.meetings m
SET person_ids = ARRAY(
        SELECT u FROM unnest(m.person_ids || v.ids) WITH ORDINALITY AS t(u, i)
        WHERE u IS DISTINCT FROM (SELECT us.person_id FROM crm.users us WHERE us.id = m.owner_id)
        GROUP BY u ORDER BY min(i)),
    guests = ARRAY(
        SELECT g FROM unnest(m.guests) WITH ORDINALITY AS t(g, i)
        WHERE NOT EXISTS (SELECT 1 FROM crm.people pe WHERE pe.id = ANY (v.ids)
                          AND crm.named(crm.norm(g), pe.name, pe.aliases, pe.short))
        ORDER BY i)
FROM v
WHERE v.meeting_id = m.id AND NOT ('people' = ANY (m.locked));
