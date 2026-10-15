-- Дела, миграция 28 (15.10.2026, аудит №11): доступ «по сделке» — пока сделка живая, состав — хозяин проекта, синк знает.
--
-- Пользователь с clients = team видит клиента, пока он на его сделке (ведёт, в команде, среди людей сделки, dela_0002).
-- Но: (1) сделка в архиве давала доступ навсегда — работа кончилась, а клиент со всей хронологией оставался виден;
-- (2) кто стоит на сделке, мог поменять любой, кто её правит, — сотрудник ставил на сделку коллегу и тем открывал ему
-- клиента; (3) телефон и веб держат кэш, а событие «видимое сузилось» (crm.access_revoked) писалось только при снятии
-- строки project_access: сняли со сделки или увели её в архив — кэш у человека жил до полного синка.
--
-- Теперь:
-- • crm.on_client: у clients = team — только живые сделки (не архив). Льготного срока нет: он гас бы по часам, без
--   события для синка;
-- • кого из пользователей Дел ставить на сделку (и тем открывать клиента), решает хозяин проекта (crm.grants_project:
--   system, владелец Дел, хозяин проекта); свой person_id добавить или убрать может каждый; людей клиента без учётки
--   участник добавляет и убирает как прежде (crm.deal_rules — прежние правила плюс это);
-- • события доступа (crm.access_changed): у пользователей team, чей person_id вошёл в живой состав сделки или вышел из
--   него (архив — пустой состав), сделку перенесли к другому клиенту; у пользователя, которому команда user поменяла
--   clients, person_id, роль или деньги. Лишнее событие безвредно — один полный синк.
--
-- Обратимо по смыслу: функции — прежние тела плюс условие (dela_0002), триггеры новые; данные не трогаются, кроме
-- событий доступа всем с clients = team (их видимое сузилось).

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

CREATE OR REPLACE FUNCTION crm.on_client(u text, p uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM crm.users us JOIN crm.projects pr ON pr.id = p AND pr.kind = 'client'
        WHERE us.id = u AND (us.clients = 'all' OR us.role = 'owner' OR (us.clients = 'team' AND us.person_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM crm.deals d
            WHERE d.project_id = p AND d.stage <> 'archive'
              AND (d.lead_person_id = us.person_id OR us.person_id = ANY (d.team_ids) OR us.person_id = ANY (d.person_ids))))))
$$;
COMMENT ON FUNCTION crm.on_client(text, uuid) IS 'Клиент виден по ответственности (dela_0002; с dela_0028 — аудит №11): владелец и clients = all — все клиенты; clients = team — пока он на ЖИВОЙ сделке клиента (ведёт, в команде, среди людей сделки).';

CREATE FUNCTION crm.grants_project(u text, p uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT u = 'system' OR u = crm.owner_id() OR EXISTS (SELECT 1 FROM crm.projects pr WHERE pr.id = p AND pr.owner_id = u)
$$;
COMMENT ON FUNCTION crm.grants_project(text, uuid) IS 'Кто даёт доступ к клиенту (аудит №11): system, владелец Дел, хозяин проекта — он ставит на сделку пользователей Дел.';

CREATE FUNCTION crm.deal_users(p_lead uuid, p_team uuid[], p_ppl uuid[]) RETURNS uuid[] LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT coalesce(array_agg(DISTINCT us.person_id ORDER BY us.person_id), '{}')
      FROM crm.users us
     WHERE us.person_id IS NOT NULL
       AND (us.person_id = p_lead OR us.person_id = ANY (coalesce(p_team, '{}')) OR us.person_id = ANY (coalesce(p_ppl, '{}')))
$$;
COMMENT ON FUNCTION crm.deal_users(uuid, uuid[], uuid[]) IS 'Кто из ведущего, команды и людей сделки — пользователь Дел (crm.users.person_id): по ним clients = team видит клиента (аудит №11).';

-- Правила сделки: прежние (dela_0002 — деньги, «как я вижу», итог и архив, команда без повторов) плюс состав.
CREATE OR REPLACE FUNCTION crm.deal_rules() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    old_money text := CASE WHEN TG_OP = 'UPDATE' THEN concat_ws('|', OLD.fee_kind, OLD.fee_kop, OLD.retainer_kop, OLD.success_pct) ELSE '' END;
    new_money text := concat_ws('|', NEW.fee_kind, NEW.fee_kop, NEW.retainer_kop, NEW.success_pct);
    v_old uuid[];
    v_new uuid[];
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
        IF EXISTS (SELECT 1 FROM (SELECT unnest(v_old) EXCEPT SELECT unnest(v_new)
                                  UNION SELECT unnest(v_new) EXCEPT SELECT unnest(v_old)) AS x(pid)
                    WHERE x.pid IS DISTINCT FROM v_self) THEN
            RAISE EXCEPTION 'Дела: кого из команды ставить на сделку (и тем открывать клиента), решает хозяин проекта';
        END IF;
    END IF;
    RETURN NEW;
END $$;

-- ── События доступа ─────────────────────────────────────────────────────

CREATE FUNCTION crm.deal_access() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
DECLARE
    v_before uuid[] := '{}';
    v_after uuid[] := '{}';
    v_moved boolean := TG_OP = 'UPDATE' AND NEW.project_id IS DISTINCT FROM OLD.project_id;
    u record;
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.stage <> 'archive' THEN
        v_before := crm.deal_users(OLD.lead_person_id, OLD.team_ids, OLD.person_ids);
    END IF;
    IF NEW.stage <> 'archive' THEN
        v_after := crm.deal_users(NEW.lead_person_id, NEW.team_ids, NEW.person_ids);
    END IF;
    FOR u IN SELECT us.id FROM crm.users us
              WHERE us.clients = 'team' AND us.person_id IS NOT NULL
                AND ((us.person_id = ANY (v_before)) <> (us.person_id = ANY (v_after))
                     OR (v_moved AND us.person_id = ANY (v_before || v_after)))
    LOOP
        PERFORM crm.access_changed(u.id, NEW.project_id);
    END LOOP;
    RETURN NULL;
END $$;
CREATE TRIGGER access AFTER INSERT OR UPDATE OF stage, lead_person_id, team_ids, person_ids, project_id ON crm.deals
    FOR EACH ROW EXECUTE FUNCTION crm.deal_access();

CREATE FUNCTION crm.user_access() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    IF (NEW.clients, NEW.person_id, NEW.role, NEW.sees_money) IS DISTINCT FROM (OLD.clients, OLD.person_id, OLD.role, OLD.sees_money) THEN
        PERFORM crm.access_changed(NEW.id, NULL);
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER access AFTER UPDATE OF clients, person_id, role, sees_money ON crm.users
    FOR EACH ROW EXECUTE FUNCTION crm.user_access();

-- on_client сузился (архив больше не открывает клиента) — всем с clients = team полный синк.
SELECT crm.access_changed(u.id) FROM crm.users u WHERE u.clients = 'team';
