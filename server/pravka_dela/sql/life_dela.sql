-- Дела в схеме life — для Claude (роль pravka_reader, только чтение).
-- Пересоздаётся на каждом migrate архива (он сносит life целиком) и Дел.
-- Виды принадлежат владельцу базы и RLS не подчиняются, поэтому видимость
-- задана явно: только то, что видит владелец Дел. Приватные проекты Наташи
-- сюда не попадают.

CREATE OR REPLACE VIEW life.tasks AS
SELECT t.num, t.title, t.notes, t.status,
       p.name AS project, d.name AS deal, coalesce(p.sphere, 'inbox') AS sphere,
       t.owner_id AS owner, t.ball, pe.short AS person, t.waiting_since, t.nudge_on,
       rq.short AS requested_by, t.due_date, t.due_time, t.estimate_min,
       coalesce(t.money, p.money_default, 'none') AS money,
       t.want, (t.focus_on = crm.today()) AS now, t.labels, t.source, t.source_ref,
       t.created_at, t.updated_at, t.completed_at,
       (t.status = 'open' AND t.due_date < crm.today()) AS overdue,
       t.id, t.project_id, t.person_id,
       -- Напоминание в Telegram (dela_0003) — в конце: CREATE OR REPLACE дописывает колонки только в хвост.
       t.remind_at, t.remind_place, t.reminded_at
FROM tasks.tasks t
LEFT JOIN crm.projects p ON p.id = t.project_id
LEFT JOIN crm.deals d ON d.id = t.deal_id
LEFT JOIN crm.people pe ON pe.id = t.person_id
LEFT JOIN crm.people rq ON rq.id = t.requested_by
WHERE crm.sees_task(crm.owner_id(), t.project_id, t.owner_id);
COMMENT ON VIEW life.tasks IS 'Дела владельца (сервис «Дела»). num — короткий номер «#57». status: open, done, cancelled. ball: mine — моё, waiting — мяч у person, agenda — поднять при встрече с person. money — paid (оплата согласована), potential (развитие), none; пусто у задачи = как у проекта. project пусто — «Входящие». remind_at — когда напомнить в Telegram, remind_place — напомнить по приезду (место автопилота телефона), reminded_at — когда напоминание ушло. Менять дела — инструментами dela_*, не SQL.';

CREATE OR REPLACE VIEW life.projects AS
SELECT p.name, p.aliases, p.sphere, p.kind, o.name AS org, p.owner_id AS owner, p.money_default, p.note, p.archived_at,
       (SELECT count(*) FROM tasks.tasks t WHERE t.project_id = p.id AND t.status = 'open') AS open_tasks,
       (SELECT count(*) FROM crm.deals d WHERE d.project_id = p.id AND d.stage <> 'archive') AS live_deals,
       p.id, p.org_id,
       -- Статус клиента и папка (dela_0006) — в хвост: CREATE OR REPLACE дописывает только туда.
       p.status, p.folder_url, p.files
FROM crm.projects p
LEFT JOIN crm.orgs o ON o.id = p.org_id
WHERE crm.sees_project(crm.owner_id(), p.id);
COMMENT ON VIEW life.projects IS 'Проекты Дел: клиенты (как корневые проекты Todoist), служебные (ЗФ, Люди), личные. money_default — paid (оплата согласована), potential (развитие), none. note — описание клиента; status relations — поддержание отношений (не лид и не сделка). Сделки клиента — life.deals.';

-- Колонки сделок поменялись (dela_0002) — поэтому DROP, как у work_time.
DROP VIEW IF EXISTS life.deals;
CREATE VIEW life.deals AS
SELECT d.name, p.name AS project, d.stage, d.outcome, d.lost_reason, d.closed_on, d.deal_type, lp.short AS lead,
       ARRAY(SELECT pe.short FROM crm.people pe WHERE pe.id = ANY (d.team_ids)) AS team,
       ARRAY(SELECT coalesce(pe.short, pe.name) FROM crm.people pe WHERE pe.id = ANY (d.person_ids)) AS people,
       sp.name AS source_person,
       d.fee_kind, round(d.fee_kop / 100.0) AS fee_rub, round(d.retainer_kop / 100.0) AS retainer_rub, d.success_pct,
       coalesce(d.probability, CASE d.stage WHEN 'lead' THEN 10 WHEN 'proposal' THEN 30 WHEN 'archive' THEN NULL ELSE 90 END) AS probability,
       d.expected_on, d.deadline,
       (SELECT round(sum(pm.amount_kop) / 100.0) FROM crm.payments pm WHERE pm.deal_id = d.id AND pm.paid_on IS NOT NULL AND pm.cancelled_at IS NULL) AS paid_rub,
       (SELECT round(sum(pm.amount_kop) / 100.0) FROM crm.payments pm WHERE pm.deal_id = d.id AND pm.paid_on IS NULL AND pm.cancelled_at IS NULL) AS unpaid_rub,
       crm.stage_since(d.id) AS stage_since,
       (SELECT max(i.at) FROM crm.interactions i WHERE i.deal_id = d.id AND i.deleted_at IS NULL) AS last_touch,
       d.wheel, d.ball, d.next_step, d.my_view, d.ideas, d.log,
       (SELECT count(*) FROM tasks.tasks t WHERE t.deal_id = d.id AND t.status = 'open') AS open_tasks,
       d.updated_at, d.id, d.project_id, d.description, d.folder_url, d.files
FROM crm.deals d
JOIN crm.projects p ON p.id = d.project_id
LEFT JOIN crm.people lp ON lp.id = d.lead_person_id
LEFT JOIN crm.people sp ON sp.id = d.source_person_id
WHERE crm.sees_project(crm.owner_id(), d.project_id);
COMMENT ON VIEW life.deals IS 'Сделки CRM (бывшие «Сделки» Notion). stage: lead, proposal (КП), mandate, active, closing, archive; у архива outcome: won (сделали), lost (проиграли), paused (заморожено). fee_rub — гонорар ЗФ всего, retainer_rub — в месяц, success_pct — процент успеха; probability — вероятность (пустая по стадии подставлена). paid_rub / unpaid_rub — из оплат (life.payments). lead и team — команда ЗФ, people — люди клиента. description — что за проект словами; folder_url и files — папка и документы ссылками. next_step, ball, log — тексты из Notion до 04.10.2026; живой следующий шаг — открытые дела сделки (life.tasks.deal).';

CREATE OR REPLACE VIEW life.payments AS
SELECT d.name AS deal, p.name AS project, pm.kind, pm.title, round(pm.amount_kop / 100.0) AS amount_rub,
       pm.due_on, pm.invoiced_on, pm.paid_on, (pm.cancelled_at IS NOT NULL) AS cancelled, pm.note,
       pm.id, pm.deal_id, d.project_id, pm.sent_to, pm.sent_via
FROM crm.payments pm
JOIN crm.deals d ON d.id = pm.deal_id
JOIN crm.projects p ON p.id = d.project_id
WHERE crm.sees_project(crm.owner_id(), d.project_id);
COMMENT ON VIEW life.payments IS 'Оплаты по сделкам ЗФ: due_on — когда ждём, invoiced_on — счёт выставлен (sent_to — кому, sent_via — как), paid_on — деньги пришли; cancelled — не будет. Деньги фирмы, не семьи (семья — life.money_live).';

CREATE OR REPLACE VIEW life.people AS
SELECT pe.name, pe.short, pe.aliases, o.name AS org, pe.role, pe.phones, pe.emails, pe.telegram_username,
       CASE WHEN pe.birth_day IS NOT NULL THEN lpad(pe.birth_day::text, 2, '0') || '.' || lpad(pe.birth_month::text, 2, '0')
            || coalesce('.' || pe.birth_year, '') END AS birthday,
       pe.source, pe.seeks, pe.offers, pe.traits, pe.cadence, pe.hub, pe.user_id, pe.note, pe.archived_at,
       (SELECT max(i.at) FROM crm.interactions i WHERE pe.id = ANY (i.person_ids) AND i.deleted_at IS NULL) AS last_contact,
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

DROP VIEW IF EXISTS life.interactions;
CREATE VIEW life.interactions AS
SELECT i.at, (i.at AT TIME ZONE 'Europe/Moscow')::date AS day, i.kind, i.summary, i.next_step, i.duration_min,
       p.name AS project, d.name AS deal,
       ARRAY(SELECT coalesce(pe.short, pe.name) FROM crm.people pe WHERE pe.id = ANY (i.person_ids)) AS people,
       i.source, i.source_ref, i.id, i.project_id
FROM crm.interactions i
LEFT JOIN crm.projects p ON p.id = i.project_id
LEFT JOIN crm.deals d ON d.id = i.deal_id
WHERE i.deleted_at IS NULL
  AND (i.owner_id = crm.owner_id() OR (i.project_id IS NOT NULL AND crm.sees_project(crm.owner_id(), i.project_id)));
COMMENT ON VIEW life.interactions IS 'Хронология контактов: звонки, встречи, переписка, заметки (бывший «Лог взаимодействий» Notion).';

CREATE OR REPLACE VIEW life.suggestions AS
SELECT s.kind, s.status, s.payload ->> 'title' AS title, s.payload, s.source, s.source_ref, s.quote,
       s.batch_title, s.reason, s.created_at, s.decided_at, s.expires_at, s.id
FROM tasks.suggestions s
WHERE s.for_user = crm.owner_id();
COMMENT ON VIEW life.suggestions IS '«Новое» в Делах: что предложила автоматика. status pending — ждёт владельца; rejected — с причиной.';

-- Связь записи с делом ставит телефон (поля task и project записи, 03.10.2026):
-- она сильнее сопоставления текста. Порядок: проект записи → проект её дела →
-- клиент текстом по алиасам. id сравниваются текстом: строка записи пришла с
-- телефона, и кривой id не должен ронять вид целиком. Колонки поменялись —
-- поэтому DROP: Дела мигрируют этот файл и поверх старого вида.
-- Личное время (семья, еда, сон, спорт…) с одним лишь клиентом текстом — не работа на клиента
-- (09.10.2026): телефон переносит клиента в следующие записи, и «Время с семьёй» на 4 часа
-- легло в часы и хронологию клиента. Такие записи сюда не идут; звонок с человеком, запись
-- из дела или с проектом — идут, как раньше.
DROP VIEW IF EXISTS life.work_time;
CREATE VIEW life.work_time AS
SELECT e.day, e.start_local, e.end_local, e.minutes, e.title, e.category, e.client,
       p.name AS project, o.name AS org, coalesce(pe.short, pe.name) AS person,
       p.id AS project_id, coalesce(p.org_id, m.org_id, pe.org_id) AS org_id, pe.id AS person_id,
       t.num AS task_num, t.id AS task_id
FROM life.entries e
LEFT JOIN LATERAL crm.match_name(e.client) m ON true
LEFT JOIN tasks.tasks t ON t.id::text = e.task_id
LEFT JOIN crm.projects p ON p.id::text = coalesce(e.project_id, t.project_id::text, m.project_id::text)
-- Человек записи (звонок — с кем, 06.10.2026) сильнее человека из текста клиента; слитый дубль
-- ведёт в живую карточку.
LEFT JOIN crm.people pe0 ON pe0.id::text = coalesce(e.person_id, m.person_id::text)
LEFT JOIN crm.people pe ON pe.id = coalesce(pe0.merged_into, pe0.id)
LEFT JOIN crm.orgs o ON o.id = coalesce(p.org_id, m.org_id, pe.org_id)
WHERE (e.client IS NOT NULL OR e.project_id IS NOT NULL OR e.task_id IS NOT NULL OR e.person_id IS NOT NULL)
  AND NOT (coalesce(e.category, '') ~* '^(Семья|Еда|Быт|Отдых|Сон|Спорт|Секс|Чтение|Потери|Не размечено)'
           AND e.project_id IS NULL AND e.task_id IS NULL AND e.person_id IS NULL);
COMMENT ON VIEW life.work_time IS 'Лента с клиентом, сопоставленным со справочником Дел: проект, организация, человек, дело. Запись, начатая из дела, несёт его проект сама (task_num — «#57»); остальные — по алиасам клиента. Часы на клиента = sum(minutes)/60 по project или org; с человеком — по person_id (звонки несут его сами).';
