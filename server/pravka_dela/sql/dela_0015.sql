-- Дела, миграция 15 (14.10.2026): настройки телефона на сервере — страница «Телефон» в вебе Дел.
--
-- Владелец (замысел «Правки 5.0»): «настройки телефона править с ПК», «две копии одного и того же», «одна база на
-- сервере». Настройки живут в телефоне (их читают служба, кнопки, движок диктовки — без сети тоже), а сервер держит
-- их копию по устройству и очередь правок с компьютера:
-- • телефон сам знает свои настройки — присылает схему (группы, подписи, тип, допустимые значения, можно ли править
--   издалека) и текущие значения операцией phone.snapshot; ключей и токенов в снимке нет никогда;
-- • веб рисует страницу по схеме; правка — операция phone.set, строка «ждёт телефона» в crm.phone_edits;
-- • телефон забирает ждущие правки синком Дел (поле phone), ставит их тем же путём, что тумблер на экране, и
--   отвечает в следующем снимке: взял, не встало (почему) или на телефоне поменяли позже (последний побеждает).
--
-- Видит только хозяин устройства: RLS «только своё», как у Свода. Владелец не видит телефон Марианны, и наоборот.
-- Журнал — сама таблица правок (кто, когда, было, стало, взято ли): в crm.history её не копим — снимок приходит
-- каждые четверть часа, и общий журнал утонул бы в «телефон отозвался». Строки правок не удаляются (правило 8);
-- устройство владелец может убрать из списка (hidden_at), новый снимок его вернёт.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

CREATE TABLE crm.phone_devices (
    device      text PRIMARY KEY CHECK (device ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
    user_id     text NOT NULL REFERENCES crm.users,
    name        text NOT NULL DEFAULT '' CHECK (length(name) <= 120),
    app         text NOT NULL DEFAULT '' CHECK (length(app) <= 80),
    schema      jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(schema) = 'object'),
    schema_hash text NOT NULL DEFAULT '' CHECK (length(schema_hash) <= 80),
    vals        jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(vals) = 'object'),
    changed     jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(changed) = 'object'),
    status      jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(status) = 'object'),
    snapshot_at timestamptz,
    seen_at     timestamptz,
    hidden_at   timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    rev         integer NOT NULL DEFAULT 1,
    seq         bigint NOT NULL DEFAULT 0
);
COMMENT ON TABLE crm.phone_devices IS 'Настройки телефона Правки — копия по устройству: схема, значения, когда прислал. Правда — в самом телефоне; видит только хозяин.';
COMMENT ON COLUMN crm.phone_devices.device IS 'Имя телефона — то же, что в журналах Денег и «Где мы» (sasha-3f9a2c): заводится один раз на установку.';
COMMENT ON COLUMN crm.phone_devices.schema IS 'Схема от телефона: groups [{key, title, hint, icon, note, items [{key, title, hint, type, options, min, max, step, unit, edit, why, sec, row}]}]. Сервер по ней проверяет правку, веб по ней рисует страницу.';
COMMENT ON COLUMN crm.phone_devices.vals IS 'Значения из последнего снимка: ключ → значение. Секретов нет — ключи с key/token/pass/secret сервер выбрасывает и сам.';
COMMENT ON COLUMN crm.phone_devices.changed IS 'Когда настройку меняли на самом телефоне (мс по часам телефона): по нему решается спор «правил и там и там».';
COMMENT ON COLUMN crm.phone_devices.seen_at IS 'Когда телефон последний раз отзывался (снимок приходит при правке и раз в четверть часа).';

CREATE TABLE crm.phone_edits (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device      text NOT NULL REFERENCES crm.phone_devices ON DELETE CASCADE,
    user_id     text NOT NULL REFERENCES crm.users,
    key         text NOT NULL CHECK (key ~ '^[a-z][a-z0-9_.]{0,63}$'),
    title       text NOT NULL DEFAULT '' CHECK (length(title) <= 200),
    source      text NOT NULL CHECK (source IN ('web', 'phone')),
    was         jsonb,
    value       jsonb,
    status      text NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending', 'applied', 'rejected', 'conflict', 'superseded', 'cancelled')),
    note        text NOT NULL DEFAULT '' CHECK (length(note) <= 500),
    actor       text NOT NULL DEFAULT '',
    at          timestamptz NOT NULL DEFAULT now(),
    taken_at    timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    rev         integer NOT NULL DEFAULT 1,
    seq         bigint NOT NULL DEFAULT 0
);
COMMENT ON TABLE crm.phone_edits IS 'Журнал настроек телефона: правки с компьютера (source web: ждёт телефона → взята, не встала, спор, заменена, отменена) и что поменяли на самом телефоне (source phone).';
COMMENT ON COLUMN crm.phone_edits.at IS 'Когда правку сделали: у web — время сервера, у phone — время телефона (не позже сервера).';
COMMENT ON COLUMN crm.phone_edits.taken_at IS 'Когда телефон ответил на правку с компьютера — взял, не смог или настоял на своём.';

CREATE INDEX phone_edits_pending ON crm.phone_edits (user_id, at) WHERE status = 'pending';
CREATE INDEX phone_edits_device ON crm.phone_edits (device, at DESC);

-- Правка — только о своём телефоне: user_id строки обязан совпасть с хозяином устройства. Код phone.py это и так
-- проверяет; база держит правило на случай любого другого клиента (внешний ключ RLS не смотрит).
CREATE FUNCTION crm.phone_edit_rules() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM crm.phone_devices d WHERE d.device = NEW.device AND d.user_id = NEW.user_id) THEN
        RAISE EXCEPTION 'Дела: правка настройки — только для своего телефона (% не у %)', NEW.device, NEW.user_id;
    END IF;
    RETURN NEW;
END $$;

-- Чей телефон — мимо RLS: снимок с чужим именем устройства должен получить отказ словами, а не «нарушение ключа».
CREATE FUNCTION crm.phone_owner(d text) RETURNS text LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    SELECT user_id FROM crm.phone_devices WHERE device = d
$$;

CREATE TRIGGER rules BEFORE INSERT OR UPDATE ON crm.phone_edits FOR EACH ROW EXECUTE FUNCTION crm.phone_edit_rules();
CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON crm.phone_edits FOR EACH ROW EXECUTE FUNCTION crm.stamp();
CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON crm.phone_devices FOR EACH ROW EXECUTE FUNCTION crm.stamp();

ALTER TABLE crm.phone_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm.phone_edits ENABLE ROW LEVEL SECURITY;
CREATE POLICY own ON crm.phone_devices FOR ALL
    USING (crm.me() IN (user_id, 'system')) WITH CHECK (crm.me() IN (user_id, 'system'));
CREATE POLICY own ON crm.phone_edits FOR ALL
    USING (crm.me() IN (user_id, 'system')) WITH CHECK (crm.me() IN (user_id, 'system'));
