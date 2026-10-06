-- Дела, миграция 4 (06.10.2026): Свод и одна карточка человека.
--
-- Владелец: «правда должна быть на сервере, телефон больше читает… если я что-то поменял на
-- сервере, оно должно уходить сразу туда и сюда; если на телефоне после сервера — тоже уходит,
-- но базу держит сервер». До этого промпты, словарь и правила жили копиями на телефоне и в
-- службах компа и расходились (долг партнёру, «Утро», напоминания). Свод — одно место для таких
-- знаний: ключ, текст или JSON, автор и причина. Едет телефону обычным синком Дел.
--
-- И про людей: «если я в расшифровке встречи человека поменял… это должен понимать и телефон,
-- и CRM, и сервер: Женя из встречи — это тот же Евгений из карточки клиента». Человек — строка
-- crm.people; встречи, голоса, звонки и Telegram ссылаются на её id. Дубль не удаляется
-- (правило 8): слитый уходит в архив со ссылкой на того, в кого слит.

-- ── Свод ────────────────────────────────────────────────────────────────

CREATE TABLE crm.svod (
    owner_id   text NOT NULL REFERENCES crm.users,
    key        text NOT NULL CHECK (key ~ '^[a-z][a-z0-9_.-]{0,79}$'),
    body       text,
    value      jsonb,
    author     text NOT NULL CHECK (btrim(author) <> ''),
    reason     text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    rev        integer NOT NULL DEFAULT 1,
    seq        bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_id, key),
    CHECK ((body IS NULL) <> (value IS NULL))
);
COMMENT ON TABLE crm.svod IS 'Свод: промпты, словарь, правила и справочники одного человека — одна правда для телефона, веба и служб компа. История — в crm.history.';
COMMENT ON COLUMN crm.svod.key IS 'Что это: prompt.clean, dict.main, money.partner, people.team… Список ключей — server/contract/svod.json.';
COMMENT ON COLUMN crm.svod.author IS 'Кто записал последним: phone, tuner, night, web, claude, dengi, meetings, seed.';

CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON crm.svod FOR EACH ROW EXECUTE FUNCTION crm.stamp();
CREATE TRIGGER journal AFTER INSERT OR UPDATE OR DELETE ON crm.svod
    FOR EACH ROW EXECUTE FUNCTION crm.journal('owner_id', 'key');

ALTER TABLE crm.svod ENABLE ROW LEVEL SECURITY;
-- Своё — только своё: Делами пользуется команда, а в Своде у владельца промпты с семьёй и деньгами.
CREATE POLICY own ON crm.svod FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));

-- ── Одна карточка человека ─────────────────────────────────────────────

ALTER TABLE crm.people ADD COLUMN merged_into uuid REFERENCES crm.people;
COMMENT ON COLUMN crm.people.merged_into IS 'Дубль слит в эту карточку (person.merge): строка в архиве, все ссылки переехали, имена — в aliases живой.';
