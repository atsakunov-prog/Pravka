-- Дела в схеме life — для Claude (роль pravka_reader, только чтение).
-- Пересоздаётся на каждом migrate архива (он сносит life целиком) и Дел.
-- Виды принадлежат владельцу базы и RLS не подчиняются, поэтому видимость
-- задана явно: только то, что видит владелец Дел. Приватные проекты Наташи
-- сюда не попадают.

CREATE OR REPLACE VIEW life.tasks AS
SELECT t.num, t.title, t.notes, t.status,
       p.name AS project, coalesce(p.sphere, 'inbox') AS sphere,
       t.owner_id AS owner, t.ball, pe.short AS person, t.waiting_since, t.nudge_on,
       rq.short AS requested_by, t.due_date, t.due_time, t.estimate_min,
       coalesce(t.money, p.money_default, 'none') AS money,
       t.want, (t.focus_on = crm.today()) AS now, t.labels, t.source, t.source_ref,
       t.created_at, t.updated_at, t.completed_at,
       (t.status = 'open' AND t.due_date < crm.today()) AS overdue,
       t.id, t.project_id, t.person_id
FROM tasks.tasks t
LEFT JOIN crm.projects p ON p.id = t.project_id
LEFT JOIN crm.people pe ON pe.id = t.person_id
LEFT JOIN crm.people rq ON rq.id = t.requested_by
WHERE crm.sees_task(crm.owner_id(), t.project_id, t.owner_id);
COMMENT ON VIEW life.tasks IS 'Дела владельца (сервис «Дела»). num — короткий номер «#57». status: open, done, cancelled. ball: mine — моё, waiting — мяч у person, agenda — поднять при встрече с person. money — paid (оплата согласована), potential (развитие), none; пусто у задачи = как у проекта. project пусто — «Входящие». Менять дела — инструментами dela_*, не SQL.';

CREATE OR REPLACE VIEW life.projects AS
SELECT p.name, p.aliases, p.sphere, p.kind, o.name AS org, p.owner_id AS owner, p.money_default,
       p.stage, p.deal_type, lp.short AS lead, round(p.fee_kop / 100.0) AS fee_rub, p.deadline,
       p.wheel, p.my_view, p.ideas, p.note, p.archived_at,
       (SELECT count(*) FROM tasks.tasks t WHERE t.project_id = p.id AND t.status = 'open') AS open_tasks,
       p.id, p.org_id
FROM crm.projects p
LEFT JOIN crm.orgs o ON o.id = p.org_id
LEFT JOIN crm.people lp ON lp.id = p.lead_person_id
WHERE crm.sees_project(crm.owner_id(), p.id);
COMMENT ON VIEW life.projects IS 'Проекты и сделки (бывшие «Сделки» Notion). stage: lead, proposal (КП), mandate, active, closing, archive. fee_rub — потенциальный fee ЗФ. wheel — «колесо крутится».';

CREATE OR REPLACE VIEW life.people AS
SELECT pe.name, pe.short, pe.aliases, o.name AS org, pe.role, pe.phones, pe.emails, pe.telegram_username,
       CASE WHEN pe.birth_day IS NOT NULL THEN lpad(pe.birth_day::text, 2, '0') || '.' || lpad(pe.birth_month::text, 2, '0')
            || coalesce('.' || pe.birth_year, '') END AS birthday,
       pe.source, pe.seeks, pe.offers, pe.traits, pe.cadence, pe.hub, pe.user_id, pe.note, pe.archived_at,
       (SELECT max(i.at) FROM crm.interactions i WHERE pe.id = ANY (i.person_ids)) AS last_contact,
       pe.id, pe.org_id
FROM crm.people pe
LEFT JOIN crm.orgs o ON o.id = pe.org_id
WHERE crm.sees_person(crm.owner_id(), pe.id);
COMMENT ON VIEW life.people IS 'Люди: команда, семья, клиенты, контакты (бывшие «Контакты» Notion). cadence — как часто видеться (month, quarter, year, none); last_contact — последнее взаимодействие.';

CREATE OR REPLACE VIEW life.orgs AS
SELECT o.name, o.aliases, o.kind, o.note, o.archived_at, o.id
FROM crm.orgs o
WHERE crm.sees_org(crm.owner_id(), o.id);
COMMENT ON VIEW life.orgs IS 'Организации: клиенты, партнёры, фонды, банки.';

CREATE OR REPLACE VIEW life.interactions AS
SELECT i.at, (i.at AT TIME ZONE 'Europe/Moscow')::date AS day, i.kind, i.summary, i.next_step,
       p.name AS project,
       ARRAY(SELECT pe.short FROM crm.people pe WHERE pe.id = ANY (i.person_ids)) AS people,
       i.source, i.source_ref, i.id, i.project_id
FROM crm.interactions i
LEFT JOIN crm.projects p ON p.id = i.project_id
WHERE i.owner_id = crm.owner_id() OR (i.project_id IS NOT NULL AND crm.sees_project(crm.owner_id(), i.project_id));
COMMENT ON VIEW life.interactions IS 'Хронология контактов: звонки, встречи, переписка, заметки (бывший «Лог взаимодействий» Notion).';

CREATE OR REPLACE VIEW life.suggestions AS
SELECT s.kind, s.status, s.payload ->> 'title' AS title, s.payload, s.source, s.source_ref, s.quote,
       s.batch_title, s.reason, s.created_at, s.decided_at, s.expires_at, s.id
FROM tasks.suggestions s
WHERE s.for_user = crm.owner_id();
COMMENT ON VIEW life.suggestions IS '«Новое» в Делах: что предложила автоматика. status pending — ждёт владельца; rejected — с причиной.';

CREATE OR REPLACE VIEW life.work_time AS
SELECT e.day, e.start_local, e.end_local, e.minutes, e.title, e.category, e.client,
       p.name AS project, o.name AS org, pe.short AS person,
       m.project_id, m.org_id, m.person_id
FROM life.entries e
LEFT JOIN LATERAL crm.match_name(e.client) m ON true
LEFT JOIN crm.projects p ON p.id = m.project_id
LEFT JOIN crm.orgs o ON o.id = m.org_id
LEFT JOIN crm.people pe ON pe.id = m.person_id
WHERE e.client IS NOT NULL;
COMMENT ON VIEW life.work_time IS 'Лента с клиентом, сопоставленным со справочником Дел: проект, организация, человек. Часы на клиента = sum(minutes)/60 по project или org.';
