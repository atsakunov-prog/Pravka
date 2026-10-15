-- Дела, миграция 26 (15.10.2026, аудит №10): карточка человека и компании — поля по тому, кто смотрит.
--
-- Карточку человека (crm.people) RLS отдаёт строкой целиком каждому, кто её видит: кому она связана с видимым делом или
-- сделкой — и всем пользователям Дел карточки друг друга. Синк, «Связи», карточка клиента и ответы операций отдавали
-- с ней черты, пометки владельца, «ищет / предлагает», «откуда знаем», теплоту и день рождения; «Люди сами» писали в
-- пометку новой карточки «<откуда>. <почему>» — пересказ переписки. Сотрудник мог переписать пометки о себе.
--
-- Теперь поля — по ярусу того, кто смотрит (crm.person_tier):
-- • owner — владелец Дел, system, автор карточки (owner_id): всё;
-- • self — это он сам (user_id): всё, кроме пометок владельца;
-- • work — связан видимым делом (кто, кто просил) или сделкой видимого клиента (ведёт, люди сделки): имя, короткое имя,
--   другие имена, компания, должность, телефоны, почта, Telegram — без чего не поработать;
-- • colleague — виден только потому, что пользователь Дел: имя, короткое имя, должность, компания.
-- Вид crm.v_people (security_invoker) прячет остальное (пусто, [] и false); строки отдаёт та же политика see, что раньше.
-- Пометка компании (crm.orgs.note) — как пометка человека: crm.v_orgs. Пометки меняет только владелец Дел, system или
-- автор карточки (триггер people_rules): сам человек правит свои контакты и день рождения, но не пометки о себе.
--
-- Обратимо по смыслу: функции и виды новые, crm.person_linked — тот же смысл (одна ветка вынесена), строки не трогаются;
-- событие доступа всем не-владельцам — их телефоны и веб пересоберут кэш людей и компаний без скрытых полей.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

-- ── Ярус того, кто смотрит ──────────────────────────────────────────────

CREATE FUNCTION crm.person_work_linked(u text, p uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM tasks.tasks t
        WHERE (t.person_id = p OR t.requested_by = p) AND crm.sees_task(u, t.project_id, t.owner_id))
    OR EXISTS (
        SELECT 1 FROM crm.deals d WHERE (d.lead_person_id = p OR p = ANY (d.person_ids)) AND crm.sees_project(u, d.project_id))
$$;
COMMENT ON FUNCTION crm.person_work_linked(text, uuid) IS 'Человек связан с работой u: видимым делом (кто, кто просил) или сделкой видимого клиента (ведёт, люди клиента) — аудит №10.';

CREATE OR REPLACE FUNCTION crm.person_linked(u text, p uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT crm.person_work_linked(u, p) OR EXISTS (SELECT 1 FROM crm.users us WHERE us.person_id = p)
$$;

CREATE FUNCTION crm.person_tier(u text, p uuid) RETURNS text LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT CASE
        WHEN u = 'system' OR u = crm.owner_id() OR u = pe.owner_id THEN 'owner'
        WHEN u = pe.user_id THEN 'self'
        WHEN crm.person_work_linked(u, p) THEN 'work'
        ELSE 'colleague' END
      FROM crm.people pe WHERE pe.id = p
$$;
COMMENT ON FUNCTION crm.person_tier(text, uuid) IS 'Как u видит карточку человека (аудит №10): owner — владелец Дел, system, автор карточки — всё; self — это он сам — всё, кроме пометок; work — связан видимым делом или сделкой — имена, компания, должность, контакты; colleague — виден лишь как пользователь Дел — имя, короткое имя, должность, компания.';

-- ── Карточки для показа ─────────────────────────────────────────────────

CREATE VIEW crm.v_people WITH (security_invoker = true) AS
SELECT pe.id, pe.name, pe.short,
       CASE WHEN z.t <> 'colleague' THEN pe.aliases ELSE '{}'::text[] END AS aliases,
       pe.org_id, pe.role,
       CASE WHEN z.t <> 'colleague' THEN pe.phones ELSE '{}'::text[] END AS phones,
       CASE WHEN z.t <> 'colleague' THEN pe.emails ELSE '{}'::text[] END AS emails,
       CASE WHEN z.t <> 'colleague' THEN pe.telegram_id END AS telegram_id,
       CASE WHEN z.t <> 'colleague' THEN pe.telegram_username END AS telegram_username,
       CASE WHEN z.t IN ('owner', 'self') THEN pe.birth_day END AS birth_day,
       CASE WHEN z.t IN ('owner', 'self') THEN pe.birth_month END AS birth_month,
       CASE WHEN z.t IN ('owner', 'self') THEN pe.birth_year END AS birth_year,
       CASE WHEN z.t = 'owner' THEN pe.source END AS source,
       CASE WHEN z.t = 'owner' THEN pe.seeks END AS seeks,
       CASE WHEN z.t = 'owner' THEN pe.offers END AS offers,
       CASE WHEN z.t = 'owner' THEN pe.traits END AS traits,
       CASE WHEN z.t = 'owner' THEN pe.cadence END AS cadence,
       CASE WHEN z.t = 'owner' THEN pe.hub ELSE false END AS hub,
       pe.user_id, pe.owner_id,
       CASE WHEN z.t = 'owner' THEN pe.note END AS note,
       pe.notion_id, pe.archived_at, pe.created_at, pe.updated_at, pe.rev, pe.seq, pe.merged_into,
       z.t AS tier
FROM crm.people pe
CROSS JOIN LATERAL (SELECT CASE
        WHEN crm.me() = 'system' OR crm.me() = pe.owner_id OR crm.me() = crm.owner_id() THEN 'owner'
        WHEN crm.me() = pe.user_id THEN 'self'
        WHEN crm.person_work_linked(crm.me(), pe.id) THEN 'work'
        ELSE 'colleague' END AS t) z;
COMMENT ON VIEW crm.v_people IS 'Карточка человека для показа (аудит №10): строки — по политике see crm.people, поля — по ярусу смотрящего (tier, crm.person_tier): owner — всё; self — всё, кроме пометок (source, seeks, offers, traits, cadence, hub, note); work — без пометок и дня рождения; colleague — только имя, короткое имя, должность, компания. Скрытое — NULL, [] и false. Читатели карточек для людей (синк, виды, ответы операций, «кто это») — этот вид.';

CREATE VIEW crm.v_orgs WITH (security_invoker = true) AS
SELECT o.id, o.name, o.aliases, o.kind, o.owner_id,
       CASE WHEN crm.me() IN ('system', o.owner_id) OR crm.me() = crm.owner_id() THEN o.note END AS note,
       o.notion_id, o.archived_at, o.created_at, o.updated_at, o.rev, o.seq
FROM crm.orgs o;
COMMENT ON VIEW crm.v_orgs IS 'Компания для показа (аудит №10): пометка (note) — только владельцу Дел, system и автору карточки компании; остальное — как у crm.orgs.';

-- ── Пометки меняет владелец или автор ───────────────────────────────────

CREATE FUNCTION crm.people_rules() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    IF (NEW.note, NEW.traits, NEW.seeks, NEW.offers, NEW.source, NEW.cadence, NEW.hub)
       IS DISTINCT FROM (OLD.note, OLD.traits, OLD.seeks, OLD.offers, OLD.source, OLD.cadence, OLD.hub)
       AND crm.me() IS DISTINCT FROM 'system' AND crm.me() IS DISTINCT FROM crm.owner_id()
       AND crm.me() IS DISTINCT FROM OLD.owner_id THEN
        RAISE EXCEPTION 'Дела: пометки о человеке (черты, заметка, ищет, предлагает, откуда, теплота) меняет владелец Дел или автор карточки — сам человек правит свои контакты и день рождения';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER people_rules BEFORE UPDATE ON crm.people FOR EACH ROW EXECUTE FUNCTION crm.people_rules();

-- ── Видимое не-владельцам сузилось — полный синк ────────────────────────

SELECT crm.access_changed(u.id) FROM crm.users u WHERE u.role <> 'owner';
