-- CRM поверх справочника Дел (решение владельца 04.10.2026: «сделай CRM как надо,
-- с доступом команде и связкой с Засечкой и Делами»).
--
-- Что добавляет:
-- * Команда ЗФ и её доступ к клиентам. clients у пользователя: own — только своё
--   и открытое руками (Марианна); team — клиенты, где он ведёт сделку, в её
--   команде или участник (юристы, аналитики); all — все клиенты фирмы (владелец,
--   операционный директор, партнёр). Доступ идёт за ответственностью: поставил
--   Алёну на сделку — она видит клиента, сняли — перестала.
-- * Деньги видит не каждый: sees_money. Гонорар сделки и оплаты без него не
--   видны и не меняются (вид crm.v_deals прячет, триггер не пускает).
-- * Воронка с итогом: архив теперь знает, чем кончилось (выиграли, проиграли,
--   заморожено) и почему. Стадии прежние — телефон их знает.
-- * Экономика сделки: модель гонорара, ретейнер, success fee, вероятность,
--   когда ждём решения, кто привёл. План и факт оплат — crm.payments.
-- * Хронология без удаления (deleted_at) и с длительностью.
-- * Время из Засечки — функциями владельца: часы по клиентам и сделкам, записи
--   ленты и «клиенты Засечки, которых нет в справочнике».

-- ── Пользователи: команда и её доступ ───────────────────────────────────

ALTER TABLE crm.users
    ADD COLUMN clients    text NOT NULL DEFAULT 'own' CHECK (clients IN ('own', 'team', 'all')),
    ADD COLUMN sees_money boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN crm.users.clients IS 'Какие клиенты видны: own — только свои и открытые руками; team — где ведёт сделку, в её команде или участник; all — все клиенты фирмы.';
COMMENT ON COLUMN crm.users.sees_money IS 'Видит и меняет гонорары сделок и оплаты. Владельцу — всегда.';

UPDATE crm.users SET clients = 'all', sees_money = true WHERE role = 'owner';

-- Роль, доступ и деньги меняет только система (командой user), не сам человек
-- через API: политика users разрешает каждому править свою строку (настройки).
CREATE FUNCTION crm.users_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF crm.me() IS DISTINCT FROM 'system' AND (NEW.role, NEW.clients, NEW.sees_money, NEW.person_id, NEW.telegram_id)
       IS DISTINCT FROM (OLD.role, OLD.clients, OLD.sees_money, OLD.person_id, OLD.telegram_id) THEN
        RAISE EXCEPTION 'Дела: роль, доступ к клиентам и деньгам меняет только владелец командой user';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER guard BEFORE UPDATE ON crm.users FOR EACH ROW EXECUTE FUNCTION crm.users_guard();

-- ── Сделки: итог, экономика, команда ────────────────────────────────────

ALTER TABLE crm.deals
    ADD COLUMN outcome          text CHECK (outcome IN ('won', 'lost', 'paused')),
    ADD COLUMN lost_reason      text,
    ADD COLUMN closed_on        date,
    ADD COLUMN fee_kind         text CHECK (fee_kind IN ('fixed', 'retainer', 'success', 'hourly', 'mixed')),
    ADD COLUMN retainer_kop     bigint CHECK (retainer_kop >= 0),
    ADD COLUMN success_pct      numeric(6, 3) CHECK (success_pct >= 0 AND success_pct <= 100),
    ADD COLUMN probability      smallint CHECK (probability BETWEEN 0 AND 100),
    ADD COLUMN expected_on      date,
    ADD COLUMN source_person_id uuid REFERENCES crm.people,
    ADD COLUMN team_ids         uuid[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT deal_outcome_only_in_archive CHECK (outcome IS NULL OR stage = 'archive');
COMMENT ON COLUMN crm.deals.outcome IS 'Чем кончилось (только у архива): won — сделали и получили, lost — не взяли или отказ, paused — заморожено. Пусто у архива — итог не записан (старые из Notion).';
COMMENT ON COLUMN crm.deals.lost_reason IS 'Почему проиграли или заморозили — для разбора воронки.';
COMMENT ON COLUMN crm.deals.closed_on IS 'Когда ушла в архив. Ставится само.';
COMMENT ON COLUMN crm.deals.fee_kind IS 'Модель гонорара: fixed — фикс, retainer — ежемесячно, success — успех (процент), hourly — почасово, mixed — смешанная.';
COMMENT ON COLUMN crm.deals.fee_kop IS 'Гонорар ЗФ всего, оценка или по договору (копейки).';
COMMENT ON COLUMN crm.deals.retainer_kop IS 'Ретейнер в месяц (копейки).';
COMMENT ON COLUMN crm.deals.success_pct IS 'Success fee, процент от сделки.';
COMMENT ON COLUMN crm.deals.probability IS 'Вероятность выиграть, %. Пусто — по стадии (лид 10, КП 30, мандат и дальше 90).';
COMMENT ON COLUMN crm.deals.expected_on IS 'Когда ждём решения клиента или подписи.';
COMMENT ON COLUMN crm.deals.source_person_id IS 'Кто привёл сделку — видно, через кого идут деньги.';
COMMENT ON COLUMN crm.deals.team_ids IS 'Команда ЗФ на сделке кроме ответственного (люди-пользователи).';
COMMENT ON COLUMN crm.deals.ball IS 'Мяч текстом из Notion (до 04.10.2026). Живой мяч — у дел сделки.';

CREATE INDEX deals_stage ON crm.deals (stage);

-- Клиент виден по ответственности: владелец и пользователь с clients = all
-- видят всех клиентов, с team — тех, где он на сделке. SECURITY DEFINER: смотрит в
-- сделки мимо RLS (иначе политика сделок спросила бы сама себя).
CREATE FUNCTION crm.on_client(u text, p uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM crm.users us JOIN crm.projects pr ON pr.id = p AND pr.kind = 'client'
        WHERE us.id = u AND (us.clients = 'all' OR us.role = 'owner' OR (us.clients = 'team' AND us.person_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM crm.deals d
            WHERE d.project_id = p
              AND (d.lead_person_id = us.person_id OR us.person_id = ANY (d.team_ids) OR us.person_id = ANY (d.person_ids))))))
$$;

CREATE OR REPLACE FUNCTION crm.sees_project(u text, p uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT u = 'system' OR EXISTS (
        SELECT 1 FROM crm.projects pr
        WHERE pr.id = p AND (pr.owner_id = u OR EXISTS (
            SELECT 1 FROM crm.project_access a WHERE a.project_id = p AND a.user_id = u)))
        OR crm.on_client(u, p)
$$;

CREATE OR REPLACE FUNCTION crm.edits_project(u text, p uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT u = 'system' OR EXISTS (
        SELECT 1 FROM crm.projects pr
        WHERE pr.id = p AND (pr.owner_id = u OR EXISTS (
            SELECT 1 FROM crm.project_access a WHERE a.project_id = p AND a.user_id = u AND a.role = 'edit')))
        OR crm.on_client(u, p)
$$;

DROP POLICY see ON crm.projects;
DROP POLICY upd ON crm.projects;
CREATE POLICY see ON crm.projects FOR SELECT
    USING (crm.me() IN (owner_id, 'system') OR crm.has_access(crm.me(), id, false) OR crm.on_client(crm.me(), id));
CREATE POLICY upd ON crm.projects FOR UPDATE
    USING (crm.me() IN (owner_id, 'system') OR crm.has_access(crm.me(), id, true) OR crm.on_client(crm.me(), id))
    WITH CHECK (crm.me() IN (owner_id, 'system') OR crm.has_access(crm.me(), id, true) OR crm.on_client(crm.me(), id));

-- Деньги: владельцу и тем, кому открыты.
CREATE FUNCTION crm.money_ok(u text) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT u = 'system' OR EXISTS (SELECT 1 FROM crm.users us WHERE us.id = u AND (us.sees_money OR us.role = 'owner'))
$$;

CREATE FUNCTION crm.sees_deal(u text, d uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM crm.deals x WHERE x.id = d AND crm.sees_project(u, x.project_id))
$$;

CREATE FUNCTION crm.edits_deal(u text, d uuid) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM crm.deals x WHERE x.id = d AND crm.edits_project(u, x.project_id))
$$;

-- Правила сделки, не зависящие от клиента: деньги и личная оценка владельца
-- защищены, архив сам ставит дату, вернувшаяся в работу сделка теряет итог.
CREATE FUNCTION crm.deal_rules() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    old_money text := CASE WHEN TG_OP = 'UPDATE' THEN concat_ws('|', OLD.fee_kind, OLD.fee_kop, OLD.retainer_kop, OLD.success_pct) ELSE '' END;
    new_money text := concat_ws('|', NEW.fee_kind, NEW.fee_kop, NEW.retainer_kop, NEW.success_pct);
BEGIN
    IF new_money IS DISTINCT FROM old_money AND NOT crm.money_ok(crm.me()) THEN
        RAISE EXCEPTION 'Дела: гонорар сделки меняет тот, кому открыты деньги';
    END IF;
    IF NEW.my_view IS DISTINCT FROM (CASE WHEN TG_OP = 'UPDATE' THEN OLD.my_view END)
       AND crm.me() IS DISTINCT FROM 'system' AND crm.me() IS DISTINCT FROM crm.owner_id() THEN
        RAISE EXCEPTION 'Дела: «как я вижу» — заметка владельца';
    END IF;
    -- Итог сам уводит в архив («выиграли» — значит, закрыта); вернули в работу — итог стёрт.
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
    RETURN NEW;
END $$;
CREATE TRIGGER rules BEFORE INSERT OR UPDATE ON crm.deals FOR EACH ROW EXECUTE FUNCTION crm.deal_rules();

-- ── Оплаты: план и факт ─────────────────────────────────────────────────

CREATE TABLE crm.payments (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    deal_id      uuid NOT NULL REFERENCES crm.deals,
    kind         text NOT NULL DEFAULT 'stage' CHECK (kind IN ('advance', 'stage', 'final', 'success', 'retainer', 'extra')),
    title        text,
    amount_kop   bigint NOT NULL CHECK (amount_kop > 0),
    due_on       date,
    invoiced_on  date,
    paid_on      date,
    cancelled_at timestamptz,
    note         text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    rev          integer NOT NULL DEFAULT 1,
    seq          bigint NOT NULL DEFAULT 0
);
COMMENT ON TABLE crm.payments IS 'Оплаты по сделке: план (due_on), счёт (invoiced_on), деньги пришли (paid_on). Не будет — cancelled_at, строка остаётся. Видят только те, кому открыты деньги.';
COMMENT ON COLUMN crm.payments.kind IS 'advance — аванс, stage — этап, final — финальный, success — success fee, retainer — ретейнер за месяц, extra — допработы.';
CREATE INDEX payments_deal ON crm.payments (deal_id);
CREATE INDEX payments_seq ON crm.payments (seq);

CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON crm.payments FOR EACH ROW EXECUTE FUNCTION crm.stamp();
CREATE TRIGGER journal AFTER INSERT OR UPDATE OR DELETE ON crm.payments FOR EACH ROW EXECUTE FUNCTION crm.journal();

ALTER TABLE crm.payments ENABLE ROW LEVEL SECURITY;
CREATE POLICY see ON crm.payments FOR SELECT USING (crm.money_ok(crm.me()) AND crm.sees_deal(crm.me(), deal_id));
CREATE POLICY ins ON crm.payments FOR INSERT WITH CHECK (crm.money_ok(crm.me()) AND crm.edits_deal(crm.me(), deal_id));
CREATE POLICY upd ON crm.payments FOR UPDATE USING (crm.money_ok(crm.me()) AND crm.edits_deal(crm.me(), deal_id))
    WITH CHECK (crm.money_ok(crm.me()) AND crm.edits_deal(crm.me(), deal_id));

-- ── Хронология ──────────────────────────────────────────────────────────

ALTER TABLE crm.interactions
    ADD COLUMN duration_min integer CHECK (duration_min > 0),
    ADD COLUMN deleted_at   timestamptz;
ALTER TABLE crm.interactions DROP CONSTRAINT interactions_source_check;
ALTER TABLE crm.interactions ADD CONSTRAINT interactions_source_check
    CHECK (source IN ('manual', 'meeting', 'digest', 'userbot', 'phone', 'raznoska', 'import', 'notion', 'web', 'mcp'));
COMMENT ON COLUMN crm.interactions.deleted_at IS 'Убрано из хронологии (ошибка). Строки не удаляются.';
CREATE INDEX interactions_project ON crm.interactions (project_id, at) WHERE deleted_at IS NULL;
CREATE INDEX interactions_deal ON crm.interactions (deal_id, at) WHERE deleted_at IS NULL;
CREATE INDEX interactions_people ON crm.interactions USING gin (person_ids);
CREATE INDEX interactions_source_ref ON crm.interactions (source, source_ref);

-- Поправить или убрать запись может автор и тот, кто правит её проект.
DROP POLICY upd ON crm.interactions;
CREATE POLICY upd ON crm.interactions FOR UPDATE
    USING (crm.me() IN (owner_id, 'system') OR (project_id IS NOT NULL AND crm.edits_project(crm.me(), project_id)));

-- ── Виды для службы ─────────────────────────────────────────────────────

-- Сделка такой, какой её можно показать этому человеку: без денег тем, кому
-- они закрыты, без личной оценки владельца — всем, кроме него.
CREATE VIEW crm.v_deals WITH (security_invoker = true) AS
SELECT d.id, d.project_id, d.name, d.stage, d.outcome, d.lost_reason, d.closed_on, d.deal_type,
       d.lead_person_id, d.team_ids, d.person_ids, d.source_person_id,
       CASE WHEN crm.money_ok(crm.me()) THEN d.fee_kind END AS fee_kind,
       CASE WHEN crm.money_ok(crm.me()) THEN d.fee_kop END AS fee_kop,
       CASE WHEN crm.money_ok(crm.me()) THEN d.retainer_kop END AS retainer_kop,
       CASE WHEN crm.money_ok(crm.me()) THEN d.success_pct END AS success_pct,
       d.probability, d.expected_on, d.deadline, d.wheel, d.ball, d.next_step,
       CASE WHEN crm.me() IN (crm.owner_id(), 'system') THEN d.my_view END AS my_view,
       d.ideas, d.log, d.notion_id, d.created_at, d.updated_at, d.rev, d.seq
FROM crm.deals d;
COMMENT ON VIEW crm.v_deals IS 'Сделка для показа: деньги — только тем, кому открыты; «как я вижу» — только владельцу.';

-- С какого дня сделка в нынешней стадии: по журналу (последняя смена стадии),
-- иначе с создания.
CREATE FUNCTION crm.stage_since(d uuid) RETURNS date LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT (coalesce(
        (SELECT max(h.at) FROM crm.history h WHERE h.entity = 'crm.deals' AND h.entity_id = d::text
           AND h.op = 'update' AND h.after ? 'stage'),
        (SELECT x.created_at FROM crm.deals x WHERE x.id = d)) AT TIME ZONE 'Europe/Moscow')::date
$$;

-- Журнал сделки тому, кто её видит: деньги вырезаны у того, кому они закрыты,
-- «как я вижу» — у всех, кроме владельца. Журнал целиком роли службы не виден.
CREATE FUNCTION crm.deal_history(d uuid)
RETURNS TABLE (at timestamptz, actor text, via text, op text, before jsonb, after jsonb)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = crm, pg_temp AS $$
    WITH hide AS (
        SELECT CASE WHEN crm.money_ok(crm.me()) THEN '{}'::text[] ELSE '{fee_kop,fee_kind,retainer_kop,success_pct}'::text[] END
            || CASE WHEN crm.me() IN (crm.owner_id(), 'system') THEN '{}'::text[] ELSE '{my_view}'::text[] END AS keys
    )
    SELECT h.at, h.actor, h.via, h.op, h.before - hide.keys, h.after - hide.keys
    FROM crm.history h, hide
    WHERE h.entity = 'crm.deals' AND h.entity_id = d::text AND crm.sees_deal(crm.me(), d)
    ORDER BY h.id
$$;

-- ── Время из Засечки (лента владельца) ──────────────────────────────────
-- Лента — одного человека, поэтому ответы — только ему. plpgsql и EXECUTE:
-- схему life архив пересобирает целиком, ссылка в теле не должна её держать.

CREATE FUNCTION crm.time_by_project(since date) RETURNS TABLE (project_id uuid, minutes bigint, last_day date, days bigint)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = crm, pg_temp AS $$
BEGIN
    IF crm.me() IS DISTINCT FROM crm.owner_id() OR to_regclass('life.work_time') IS NULL THEN
        RETURN;
    END IF;
    RETURN QUERY EXECUTE
        'SELECT project_id, sum(minutes)::bigint, max(day), count(DISTINCT day) FROM life.work_time '
        'WHERE project_id IS NOT NULL AND day >= $1 GROUP BY project_id'
        USING since;
END $$;

-- Сделка получает время через дело: запись Засечки, начатая из дела сделки.
CREATE FUNCTION crm.time_by_deal(since date) RETURNS TABLE (deal_id uuid, minutes bigint, last_day date)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = crm, pg_temp AS $$
BEGIN
    IF crm.me() IS DISTINCT FROM crm.owner_id() OR to_regclass('life.work_time') IS NULL THEN
        RETURN;
    END IF;
    BEGIN
        RETURN QUERY EXECUTE
            'SELECT t.deal_id, sum(w.minutes)::bigint, max(w.day) FROM life.work_time w '
            'JOIN tasks.tasks t ON t.id = w.task_id WHERE t.deal_id IS NOT NULL AND w.day >= $1 GROUP BY t.deal_id'
            USING since;
    EXCEPTION WHEN undefined_column THEN
        RETURN;  -- архив ещё без связи записи с делом
    END;
END $$;

-- Записи ленты по клиенту (и сделке, если задана) — для хронологии карточки.
CREATE FUNCTION crm.time_entries(p uuid, d uuid, lim integer)
RETURNS TABLE (day date, start_local text, end_local text, minutes integer, title text, task_num integer, deal_id uuid)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = crm, pg_temp AS $$
BEGIN
    IF crm.me() IS DISTINCT FROM crm.owner_id() OR to_regclass('life.work_time') IS NULL THEN
        RETURN;
    END IF;
    BEGIN
        RETURN QUERY EXECUTE
            'SELECT w.day, w.start_local, w.end_local, w.minutes, w.title, t.num, t.deal_id FROM life.work_time w '
            'LEFT JOIN tasks.tasks t ON t.id = w.task_id '
            'WHERE w.project_id = $1 AND ($2::uuid IS NULL OR t.deal_id = $2) '
            'ORDER BY w.day DESC, w.start_local DESC LIMIT $3'
            USING p, d, lim;
    EXCEPTION WHEN undefined_column THEN
        RETURN QUERY EXECUTE
            'SELECT w.day, w.start_local, w.end_local, w.minutes, w.title, NULL::integer, NULL::uuid FROM life.work_time w '
            'WHERE w.project_id = $1 AND $2::uuid IS NULL ORDER BY w.day DESC, w.start_local DESC LIMIT $3'
            USING p, d, lim;
    END;
END $$;

-- Клиенты, которых Засечка знает, а справочник нет: «Каппа», «Йота». Из CRM
-- их заводят клиентом или привязывают алиасом к существующему — и прошлые часы
-- сами встают на место (вид work_time сопоставляет по алиасам).
CREATE FUNCTION crm.time_unmatched(since date)
RETURNS TABLE (client text, minutes bigint, entries bigint, first_day date, last_day date)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = crm, pg_temp AS $$
BEGIN
    IF crm.me() IS DISTINCT FROM crm.owner_id() OR to_regclass('life.work_time') IS NULL THEN
        RETURN;
    END IF;
    RETURN QUERY EXECUTE
        'SELECT client, sum(minutes)::bigint, count(*), min(day), max(day) FROM life.work_time '
        'WHERE project_id IS NULL AND person_id IS NULL AND org_id IS NULL AND client IS NOT NULL AND day >= $1 '
        'GROUP BY client ORDER BY sum(minutes) DESC'
        USING since;
END $$;
