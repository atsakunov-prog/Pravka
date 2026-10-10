-- Дела, миграция 8 (10.10.2026): «Где мы» — точки семьи на карте Правки.
--
-- Владелец: «у всех, у кого установлена Правка, может дать согласие на шеринг своего
-- местоположения… видим, где дети, где мы сами». Первая версия возила точки через облако
-- семьи (WebDAV), и в тот же вечер: «почему через облако семьи? Вроде мы его не
-- использовали толком». Телефоны живут с сервером Дел — точки поехали сюда.
--
-- Три решения, которые расходятся с общими правилами Дел — сознательно:
--  - журнала нет. Каждое изменение в crm.history (правило 7) превратило бы точку в историю
--    перемещений, а договорились хранить одну последнюю точку на телефон;
--  - строка удаляется. «Перестал делиться» — точка исчезает с карт сразу (правило 8 — про
--    дела и людей, а это согласие);
--  - видимость — не по проектам, а по семье: crm.users.family. Наташа и любые будущие
--    пользователи Дел карты не видят и на неё не пишут. Семью меняет только владелец
--    командой family (как роль и деньги — командой user, crm.users_guard).

ALTER TABLE crm.users ADD COLUMN family boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN crm.users.family IS 'Член семьи для «Где мы»: видит точки семьи и делится своей. Меняет только владелец командой family.';
-- С завода в семье владелец и Марианна; остальных добавляет команда family.
UPDATE crm.users SET family = true WHERE role = 'owner' OR id = 'marianna';

CREATE OR REPLACE FUNCTION crm.users_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF crm.me() IS DISTINCT FROM 'system'
       AND (NEW.role, NEW.clients, NEW.sees_money, NEW.person_id, NEW.telegram_id, NEW.family)
       IS DISTINCT FROM (OLD.role, OLD.clients, OLD.sees_money, OLD.person_id, OLD.telegram_id, OLD.family) THEN
        RAISE EXCEPTION 'Дела: роль, доступ к клиентам и деньгам, семью меняет только владелец (команды user и family)';
    END IF;
    RETURN NEW;
END $$;

-- Мимо RLS, как остальные функции видимости: политика точек спрашивает про чужую строку users.
CREATE FUNCTION crm.in_family(u text) RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT u = 'system' OR coalesce((SELECT family FROM crm.users WHERE id = u), false)
$$;

CREATE TABLE crm.where_points (
    device     text PRIMARY KEY CHECK (device ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
    user_id    text NOT NULL REFERENCES crm.users,
    point      jsonb NOT NULL CHECK (jsonb_typeof(point) = 'object'),
    avatar     bytea CHECK (avatar IS NULL OR octet_length(avatar) <= 131072),
    avatar_at  bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE crm.where_points IS '«Где мы»: одна последняя точка телефона, пока он делится. Истории нет, журнала нет; перестал делиться — строки нет.';
COMMENT ON COLUMN crm.where_points.point IS 'Точка как её пишет телефон (core/WherePolicy.kt): lat, lon, acc, at, src, battery, place, moving, sent…';
COMMENT ON COLUMN crm.where_points.avatar IS 'Аватар 256×256 JPEG; версия — avatar_at (момент выбора фото на телефоне).';

CREATE TABLE crm.where_asks (
    device  text PRIMARY KEY CHECK (device ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
    user_id text NOT NULL REFERENCES crm.users,
    at      timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE crm.where_asks IS '«Обновите точки»: свежую просьбу каждый делящийся телефон отрабатывает одним своим GPS.';

ALTER TABLE crm.where_points ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.where_asks ENABLE ROW LEVEL SECURITY;
-- Видит семья; пишет и удаляет — только своё и только член семьи.
CREATE POLICY see ON crm.where_points FOR SELECT USING (crm.in_family(crm.me()));
CREATE POLICY own ON crm.where_points FOR ALL
    USING (crm.me() IN (user_id, 'system') AND crm.in_family(crm.me()))
    WITH CHECK (crm.me() IN (user_id, 'system') AND crm.in_family(crm.me()));
CREATE POLICY see ON crm.where_asks FOR SELECT USING (crm.in_family(crm.me()));
CREATE POLICY own ON crm.where_asks FOR ALL
    USING (crm.me() IN (user_id, 'system') AND crm.in_family(crm.me()))
    WITH CHECK (crm.me() IN (user_id, 'system') AND crm.in_family(crm.me()));
