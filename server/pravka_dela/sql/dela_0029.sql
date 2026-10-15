-- Дела, миграция 29 (15.10.2026, проверка пакета «Приватность», аудит №10 и №11): две дыры, найденные пробой.
--
-- №11. dela_0028 сверяла сырой состав сделки (кто из пользователей Дел ведёт, в команде, среди людей) до и после
-- правки. Но клиента открывает ЖИВОЙ состав: сделка в архиве никого не пускает, а сделка у другого клиента пускает к
-- другому. Поэтому два пути проходили мимо проверки — состав не менялся, а клиент открывался:
-- (а) участник возвращает из архива сделку, где стоит коллега (deal.set stage: active) — коллеге снова виден клиент;
-- (б) не-хозяин проекта переносит сделку с коллегой к другому клиенту (deal.set project_id) — коллеге открыт новый.
-- Теперь crm.deal_rules сверяет ещё и «живой состав пополнился»: до правки он — состав живой сделки того же клиента,
-- иначе пуст; после — состав, если сделка живая. Новый в нём пользователь Дел (кроме себя) — решение хозяина проекта.
-- Увести сделку в архив участник может по-прежнему: это сужение доступа.
--
-- №10. Ярус work (имена, контакты) проверялся раньше яруса colleague: пользователь Дел, который ведёт видимую сделку,
-- стоит в её команде или назван в видимом деле, отдавался коллегам с телефонами, почтой, Telegram и другими именами —
-- «кто это» узнавал его по номеру. Закон (правило 26 карты Дел, контракт dela-crm.json privacy): пользователям Дел друг
-- о друге — только имя, короткое имя, должность, компания. Теперь карточка пользователя Дел (crm.people.user_id или
-- crm.users.person_id) — colleague всем, кроме владельца Дел, system, автора карточки и его самого. Он сам — self и по
-- crm.users.person_id (раньше — только по crm.people.user_id: у карточки, связанной лишь с учёткой, он видел себя как
-- коллегу или по работе). Вид crm.v_people берёт ярус у crm.person_tier — одно правило в одном месте.
--
-- Обратимо по смыслу: функции — тела dela_0026/dela_0028 плюс условие, вид — те же колонки в том же порядке; строки не
-- трогаются; событие доступа всем не-владельцам — их телефоны и веб пересоберут кэш людей без скрытых полей коллег.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

-- ── №11: живой состав сделки пополняет только хозяин проекта ────────────

-- Правила сделки: dela_0028 (dela_0002 — деньги, «как я вижу», итог и архив, команда без повторов; состав) плюс живой
-- состав.
CREATE OR REPLACE FUNCTION crm.deal_rules() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    old_money text := CASE WHEN TG_OP = 'UPDATE' THEN concat_ws('|', OLD.fee_kind, OLD.fee_kop, OLD.retainer_kop, OLD.success_pct) ELSE '' END;
    new_money text := concat_ws('|', NEW.fee_kind, NEW.fee_kop, NEW.retainer_kop, NEW.success_pct);
    v_old uuid[];
    v_new uuid[];
    v_live_old uuid[];
    v_self uuid;
BEGIN
    IF new_money IS DISTINCT FROM old_money AND NOT crm.money_ok(crm.me()) THEN
        RAISE EXCEPTION 'Дела: гонорар сделки меняет тот, кому открыты деньги';
    END IF;
    IF NEW.my_view IS DISTINCT FROM (CASE WHEN TG_OP = 'UPDATE' THEN OLD.my_view END)
       AND crm.me() IS DISTINCT FROM 'system' AND crm.me() IS DISTINCT FROM crm.owner_id() THEN
        RAISE EXCEPTION 'Дела: «как я вижу» — заметка владельца';
    END IF;
    IF NEW.outcome IS NOT NULL AND NEW.stage <> 'archive'
       AND (TG_OP = 'INSERT' OR NEW.outcome IS DISTINCT FROM OLD.outcome) THEN
        NEW.stage := 'archive';
    END IF;
    IF NEW.stage = 'archive' THEN
        IF NEW.closed_on IS NULL AND (TG_OP = 'INSERT' OR OLD.stage <> 'archive') THEN
            NEW.closed_on := crm.today();
        END IF;
    ELSE
        NEW.outcome := NULL;
        NEW.closed_on := NULL;
        NEW.lost_reason := NULL;
    END IF;
    NEW.team_ids := ARRAY(SELECT DISTINCT unnest(NEW.team_ids));
    -- Кого из пользователей Дел ставить на сделку (и тем открывать клиента), решает хозяин проекта (аудит №11). Себя
    -- добавить или убрать может каждый; людей клиента без учётки — как прежде.
    IF NOT crm.grants_project(crm.me(), NEW.project_id) THEN
        v_old := CASE WHEN TG_OP = 'UPDATE' THEN crm.deal_users(OLD.lead_person_id, OLD.team_ids, OLD.person_ids) ELSE '{}' END;
        v_new := crm.deal_users(NEW.lead_person_id, NEW.team_ids, NEW.person_ids);
        SELECT us.person_id INTO v_self FROM crm.users us WHERE us.id = crm.me();
        -- Сырой состав (dela_0028): ставить и снимать коллег.
        IF EXISTS (SELECT 1 FROM (SELECT unnest(v_old) EXCEPT SELECT unnest(v_new)
                                  UNION SELECT unnest(v_new) EXCEPT SELECT unnest(v_old)) AS x(pid)
                    WHERE x.pid IS DISTINCT FROM v_self) THEN
            RAISE EXCEPTION 'Дела: кого из команды ставить на сделку (и тем открывать клиента), решает хозяин проекта';
        END IF;
        -- Живой состав (dela_0029): клиента открывает живая сделка этого клиента. Возврат из архива и перенос к другому
        -- клиенту с коллегой на сделке открыли бы ему клиента при том же сыром составе.
        v_live_old := CASE WHEN TG_OP = 'UPDATE' AND OLD.stage <> 'archive' AND OLD.project_id = NEW.project_id
                           THEN v_old ELSE '{}' END;
        IF NEW.stage <> 'archive' AND EXISTS (
               SELECT 1 FROM unnest(v_new) AS x(pid)
                WHERE x.pid IS DISTINCT FROM v_self AND NOT (x.pid = ANY (v_live_old))) THEN
            RAISE EXCEPTION 'Дела: кого из команды ставить на сделку (и тем открывать клиента), решает хозяин проекта';
        END IF;
    END IF;
    RETURN NEW;
END $$;

-- ── №10: карточка пользователя Дел — коллегам только имя и должность ────

CREATE OR REPLACE FUNCTION crm.person_tier(u text, p uuid) RETURNS text LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT CASE
        WHEN u = 'system' OR u = crm.owner_id() OR u = pe.owner_id THEN 'owner'
        WHEN u = pe.user_id OR EXISTS (SELECT 1 FROM crm.users us WHERE us.id = u AND us.person_id = pe.id) THEN 'self'
        WHEN pe.user_id IS NOT NULL OR EXISTS (SELECT 1 FROM crm.users us WHERE us.person_id = pe.id) THEN 'colleague'
        WHEN crm.person_work_linked(u, p) THEN 'work'
        ELSE 'colleague' END
      FROM crm.people pe WHERE pe.id = p
$$;
COMMENT ON FUNCTION crm.person_tier(text, uuid) IS 'Как u видит карточку человека (аудит №10; с dela_0029 — пользователь Дел коллегам всегда colleague): owner — владелец Дел, system, автор карточки — всё; self — это он сам (crm.people.user_id или crm.users.person_id) — всё, кроме пометок; colleague — карточка пользователя Дел (кому угодно ещё) или виден лишь как пользователь Дел — имя, короткое имя, должность, компания; work — связан видимым делом или сделкой — имена, компания, должность, контакты.';

CREATE OR REPLACE VIEW crm.v_people WITH (security_invoker = true) AS
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
CROSS JOIN LATERAL (SELECT crm.person_tier(crm.me(), pe.id) AS t) z;
COMMENT ON VIEW crm.v_people IS 'Карточка человека для показа (аудит №10): строки — по политике see crm.people, поля — по ярусу смотрящего (tier = crm.person_tier, одно правило; с dela_0029 пользователь Дел коллегам — всегда colleague): owner — всё; self — всё, кроме пометок (source, seeks, offers, traits, cadence, hub, note); work — без пометок и дня рождения; colleague — только имя, короткое имя, должность, компания. Скрытое — NULL, [] и false. Читатели карточек для людей (синк, виды, ответы операций, «кто это») — этот вид.';

-- ── Видимое не-владельцам сузилось (контакты коллег) — полный синк ──────

SELECT crm.access_changed(u.id) FROM crm.users u WHERE u.role <> 'owner';
