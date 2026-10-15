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
       t.remind_at, t.remind_place, t.reminded_at,
       -- Группа дела (dela_0007): owed — отбить (ждут от меня), self — запустить, check — мониторить.
       t.owed, CASE WHEN t.owed THEN 'owed' WHEN t.ball = 'waiting' THEN 'check' ELSE 'self' END AS grp,
       -- Круг жизни (dela_0009): направления с наследованием и «Зачем» — номер и название цели. Только у дел
       -- владельца: строки Круга — его.
       CASE WHEN t.owner_id = crm.owner_id() THEN tasks.krug_dirs(t.id) END AS directions,
       w.num AS why_num, w.title AS why,
       -- Дерево целей (dela_0013): узел дерева (цель-состояние, в списках дел его нет) и ритм практики.
       coalesce(k.goal, false) AS goal, k.rhythm,
       -- Дела внутри дела (dela_0030): «Зачем» — цель дерева (why_goal) или обычное дело, внутри которого это дело;
       -- pos — место среди соседей (дел с тем же «Зачем»).
       coalesce(wk.goal, false) AS why_goal, t.pos,
       -- Захват (dela_0032): kind — task или remind (напоминание, в списках дел его нет); back_on — когда вернётся дело «потом».
       t.kind, t.back_on
FROM tasks.tasks t
LEFT JOIN crm.projects p ON p.id = t.project_id
LEFT JOIN crm.deals d ON d.id = t.deal_id
LEFT JOIN crm.people pe ON pe.id = t.person_id
LEFT JOIN crm.people rq ON rq.id = t.requested_by
LEFT JOIN tasks.krug k ON k.id = t.id AND k.kind = 'task' AND k.owner_id = crm.owner_id()
LEFT JOIN tasks.tasks w ON w.id = k.why_id
LEFT JOIN tasks.krug wk ON wk.id = k.why_id AND wk.kind = 'task'
WHERE crm.sees_task(crm.owner_id(), t.project_id, t.owner_id);
COMMENT ON VIEW life.tasks IS 'Дела владельца (сервис «Дела»). num — короткий номер «#57». status: open, done, cancelled. grp — группа дела, как Саша их видит: owed — «Отбить» (ждут от меня: person ждёт от Саши ответа или дела; первым делом), self — «Запустить» (сам придумал: mine и agenda), check — «Мониторить» (ждёт от person, ball waiting). ball: mine — моё, waiting — мяч у person, agenda — поднять при встрече с person; owed — флаг «ждут от меня». money — paid (оплата согласована), potential (развитие), none; пусто у задачи = как у проекта. project пусто — «Входящие». remind_at — когда напомнить в Telegram, remind_place — напомнить по приезду (место автопилота телефона), reminded_at — когда напоминание ушло. directions — направления Круга жизни (ключи Свода krug.directions: health, work…) с наследованием от «Зачем» и проекта; why_num и why — «Зачем»: цель, к которой ведёт это дело (why_goal true), или обычное дело, внутри которого оно лежит (why_goal false: дела внутри дела — обычные дела, pos — место среди соседей, 1 — первым); directions[1] — главное направление, у корня цепочки; остальные — метки. goal — узел дерева целей Круга жизни (цель-состояние, а не дело); rhythm — ритм практики (цель, которая повторяется). kind — task (дело) или remind (напоминание «напомни в 12 …»: не дело, своего срока нет — remind_at); back_on — день, когда дело «потом» вернётся на «Сегодня». Менять дела — инструментами dela_* (вложить — dela_change inside), не SQL.';

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
       -- Последний контакт и шаги сделки — тем же правилом, что веб и телефон (аудит №2, №7, dela_0022): её записи и дела
       -- плюс, пока сделка у клиента одна живая, записи и открытые дела клиента без сделки; упоминания — не контакт.
       crm.deal_last_touch(d.id) AS last_touch,
       d.wheel, d.ball, d.next_step, d.my_view, d.ideas, d.log,
       (SELECT count(*) FROM tasks.tasks t
         WHERE t.status = 'open' AND t.kind = 'task'
           AND (t.deal_id = d.id
                OR (t.deal_id IS NULL AND t.project_id = d.project_id AND crm.only_deal(d.project_id) = d.id
                    AND NOT EXISTS (SELECT 1 FROM tasks.krug k WHERE k.id = t.id AND k.kind = 'task' AND k.goal)))) AS open_tasks,
       d.updated_at, d.id, d.project_id, d.description, d.folder_url, d.files
FROM crm.deals d
JOIN crm.projects p ON p.id = d.project_id
LEFT JOIN crm.people lp ON lp.id = d.lead_person_id
LEFT JOIN crm.people sp ON sp.id = d.source_person_id
WHERE crm.sees_project(crm.owner_id(), d.project_id);
COMMENT ON VIEW life.deals IS 'Сделки CRM (бывшие «Сделки» Notion). stage: lead, proposal (КП), mandate, active, closing, archive; у архива outcome: won (сделали), lost (проиграли), paused (заморожено). fee_rub — гонорар ЗФ всего, retainer_rub — в месяц, success_pct — процент успеха; probability — вероятность (пустая по стадии подставлена). paid_rub / unpaid_rub — из оплат (life.payments). lead и team — команда ЗФ, people — люди клиента. description — что за проект словами; folder_url и files — папка и документы ссылками. next_step, ball, log — тексты из Notion до 04.10.2026; живой следующий шаг — открытые дела сделки (life.tasks.deal), а пока сделка у клиента одна живая — и открытые дела клиента без сделки (их считает open_tasks). last_touch — последний контакт: самая свежая запись хронологии сделки (и записи клиента без сделки, пока сделка одна; не из Notion), упоминания не в счёт.';

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
       -- Упоминание в сводке Telegram (mention, dela_0022) — не контакт; чужие приватные записи — не в счёт.
       (SELECT max(i.at) FROM crm.interactions i WHERE pe.id = ANY (i.person_ids) AND i.deleted_at IS NULL AND NOT i.mention
           AND (i.owner_id = crm.owner_id()
                OR (NOT i.private AND i.project_id IS NOT NULL AND crm.sees_project(crm.owner_id(), i.project_id)))) AS last_contact,
       pe.id, pe.org_id
FROM crm.people pe
LEFT JOIN crm.orgs o ON o.id = pe.org_id
WHERE crm.sees_person(crm.owner_id(), pe.id);
COMMENT ON VIEW life.people IS 'Люди: команда, семья, клиенты, контакты (бывшие «Контакты» Notion). cadence — как часто видеться (month, quarter, year, none); last_contact — последнее взаимодействие (упоминание в сводке Telegram — не взаимодействие).';

CREATE OR REPLACE VIEW life.orgs AS
SELECT o.name, o.aliases, o.kind, o.note, o.archived_at, o.id
FROM crm.orgs o
WHERE crm.sees_org(crm.owner_id(), o.id);
COMMENT ON VIEW life.orgs IS 'Организации: клиенты, партнёры, фонды, банки.';

DROP VIEW IF EXISTS life.interactions;
-- Запись встречи (dela_0025, аудит №9): команде — факт, владельцу и Claude — итог и договорённости с карточки встречи
-- (то же правило, что crm.v_interactions; вид от владельца базы — RLS не действует, поэтому встречи владельца — явно).
-- team_sees — что из записи видит команда: none (приватная), fact (тут больше, чем в строке), all.
CREATE VIEW life.interactions AS
SELECT i.at, (i.at AT TIME ZONE 'Europe/Moscow')::date AS day, i.kind,
       CASE WHEN mt.title IS NOT NULL THEN mt.summary ELSE i.summary END AS summary,
       CASE WHEN mt.title IS NOT NULL THEN mt.next_step ELSE i.next_step END AS next_step, i.duration_min,
       p.name AS project, d.name AS deal,
       ARRAY(SELECT coalesce(pe.short, pe.name) FROM crm.people pe WHERE pe.id = ANY (i.person_ids)) AS people,
       i.source, i.source_ref, i.id, i.project_id,
       -- Дайджест Telegram (dela_0018): пункт сводки у человека и клиента — source digest, ссылка на сводку — до «#».
       i.private, CASE WHEN i.source = 'digest' THEN split_part(i.source_ref, '#', 1) END AS digest_ref,
       -- Упоминание (dela_0022): клиент или человек назван в теме сводки, а его чата в окне нет — не контакт.
       i.mention,
       CASE WHEN i.private THEN 'none'
            WHEN mt.title IS NOT NULL AND (mt.summary IS DISTINCT FROM i.summary OR mt.next_step IS DISTINCT FROM i.next_step)
            THEN 'fact' ELSE 'all' END AS team_sees
FROM crm.interactions i
LEFT JOIN crm.projects p ON p.id = i.project_id
LEFT JOIN crm.deals d ON d.id = i.deal_id
LEFT JOIN LATERAL (
    SELECT t.title, t.summary, t.next_step
      FROM crm.meetings m CROSS JOIN LATERAL crm.meeting_text(m) t
     WHERE i.source = 'meeting' AND i.owner_id = crm.owner_id() AND m.owner_id = i.owner_id
       AND crm.meeting_ref(coalesce(m.url, '/m/' || m.ref)) = crm.meeting_ref(i.source_ref)
       AND m.started_at >= crm.meeting_touch_from() AND NOT ('touch' = ANY (m.locked))
     ORDER BY m.chars_n DESC, m.id::text LIMIT 1) mt ON true
WHERE i.deleted_at IS NULL
  AND (i.owner_id = crm.owner_id()
       OR (NOT i.private AND i.project_id IS NOT NULL AND crm.sees_project(crm.owner_id(), i.project_id)));
COMMENT ON VIEW life.interactions IS 'Хронология контактов: звонки, встречи, переписка, заметки (бывший «Лог взаимодействий» Notion); source digest — пункты дайджеста Telegram (digest_ref — сводка, целиком — инструмент digest); mention — упоминание (клиента или человека назвали в теме сводки, а его чата в окне не было): в хронологии есть, контактом не считается. У записи встречи summary и next_step — итог и договорённости с карточки встречи (их видит только Саша); team_sees — что из записи видит команда: none — ничего (приватная: только Саше), fact — только название встречи, all — то же, что здесь. Приватное и то, чего команда не видит, людям не цитировать.';

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

-- Встречи (dela_0010, 12.10.2026): карточка с единым названием и расшифровка репликами. Владелец Дел видит свои
-- встречи целиком — и рабочие, и личные (sphere home: терапия, семья); Claude отличает их по sphere.
DROP VIEW IF EXISTS life.meeting_lines;
DROP VIEW IF EXISTS life.meetings;
CREATE VIEW life.meetings AS
SELECT m.started_at, (m.started_at AT TIME ZONE 'Europe/Moscow')::date AS day, m.duration_min, m.kind, m.sphere,
       m.title, coalesce(p.name, m.label) AS client, m.topic, d.name AS deal,
       ARRAY(SELECT coalesce(pe.short, pe.name) FROM unnest(m.person_ids) WITH ORDINALITY AS u(pid, i)
             JOIN crm.people pe ON pe.id = u.pid ORDER BY u.i) AS people,
       m.guests, m.summary, m.agreements, m.source_title, m.question, m.named_at IS NOT NULL AS named,
       m.lines_n, m.url, m.ref, m.id, m.project_id, m.person_ids, m.archived_at
FROM crm.meetings m
LEFT JOIN crm.projects p ON p.id = m.project_id
LEFT JOIN crm.deals d ON d.id = m.deal_id
WHERE m.owner_id = crm.owner_id();
COMMENT ON VIEW life.meetings IS 'Встречи владельца (служба встреч ZF-Meetings): единое название title = «Клиент: тема», client — клиент из справочника или кто был (ЗФ — свои, Личное — личное), topic — тема, people — участники из справочника кроме владельца, guests — кого в справочнике нет. sphere: work — рабочая, home — личная (терапия, семья, дети); пусто — ещё не разобрана. summary и agreements — сводка службы. Расшифровка — life.meeting_lines. archived_at — владелец убрал из списка.';

CREATE VIEW life.meeting_lines AS
SELECT l.meeting_id, l.n, l.start_sec, l.speaker, l.text, l.tsv, m.title, m.started_at, m.sphere
FROM crm.meeting_lines l
JOIN crm.meetings m ON m.id = l.meeting_id
WHERE m.owner_id = crm.owner_id();
COMMENT ON VIEW life.meeting_lines IS 'Расшифровки встреч репликами: n — порядок, start_sec — секунда от начала, speaker — кто говорит (как распознала служба). Поиск по словоформам: tsv @@ websearch_to_tsquery(''russian'', ''счёт оплата''); имена — text ILIKE ''%Павел%''. Встреча — life.meetings по meeting_id = id.';

-- «Только тебе» (dela_0027, аудит №8–11; место этапа 6): наблюдения владельца — оценки людей, несостыковки,
-- неуверенность, «кто за столом», что уточнить. Только его строки и без убранных: вид от владельца базы, RLS не
-- действует — поэтому явно owner_id = crm.owner_id().
DROP VIEW IF EXISTS life.insights;
CREATE VIEW life.insights AS
SELECT i.at, (i.at AT TIME ZONE 'Europe/Moscow')::date AS day, i.kind, i.text, i.quote, i.state,
       ARRAY(SELECT coalesce(pe.short, pe.name) FROM unnest(i.person_ids) WITH ORDINALITY AS u(pid, n)
             JOIN crm.people pe ON pe.id = u.pid ORDER BY u.n) AS people,
       p.name AS client, d.name AS deal, o.name AS org,
       coalesce(nullif(btrim(m.title), ''), m.source_title) AS meeting, m.ref AS meeting_ref, i.at_sec,
       i.source, i.source_ref, i.id, i.person_ids, i.project_id, i.deal_id, i.org_id, i.meeting_id
FROM crm.insights i
LEFT JOIN crm.projects p ON p.id = i.project_id
LEFT JOIN crm.deals d ON d.id = i.deal_id
LEFT JOIN crm.orgs o ON o.id = i.org_id
LEFT JOIN crm.meetings m ON m.id = i.meeting_id
WHERE i.owner_id = crm.owner_id() AND i.deleted_at IS NULL;
COMMENT ON VIEW life.insights IS 'Только Саше. Не цитировать в письмах, сообщениях и черновиках людям. «Только тебе» — его наблюдения о людях, клиентах, сделках и встречах: kind — вид (note — заметка; conflict — несостыковка, hedge — неуверенность, table — кто за столом, objection — возражение, signal — сигнал, ask — что уточнить и другие ключи разбора встреч и дайджеста), text и quote, people/client/deal/org/meeting — о ком, at_sec — секунда встречи, state: new, seen (видел), useful, dismissed (мимо), wrong (неверно), moved (перенёс). Записать — инструмент crm_private.';
