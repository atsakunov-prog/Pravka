-- Дела, миграция 5 (06.10.2026): «Новое» — наговорки и «понятно» у закрытого само.
--
-- Владелец: «давай сделаем новое как последние 10 наговорок, и там будет указано, какие дела куда
-- попали… дело из наговорки потом пройдёт из дайджеста или встречи — надо сращивать, но в наговорках
-- оно должно оставаться». Дела наговорки и раньше несли её метку (source_ref = 'raznoska:<id>' у
-- телефона), а слов — нет: что было сказано, сервер не знал. Теперь наговорка — строка: текст, время,
-- откуда; дела ссылаются на неё тем же source_ref. Телефон шлёт её операцией dictation.add, только
-- увидев features: ["dictations"] в синке; разбор на сервере (звёздочка веба, правка словами
-- с телефона, когда команда целиком про новые дела) пишет её сам.
--
-- И про «Закрыто само»: «закрыто дело 268, так и висит… никуда не уходит». Закрытое автоматикой
-- висело в «Новом» неделю без кнопки «понятно». seen_at — человек видел и согласен; такое уходит.

CREATE TABLE tasks.dictations (
    id         text PRIMARY KEY CHECK (btrim(id) <> ''),
    owner_id   text NOT NULL REFERENCES crm.users,
    at         timestamptz NOT NULL DEFAULT now(),
    text       text NOT NULL CHECK (btrim(text) <> ''),
    source     text NOT NULL DEFAULT 'phone' CHECK (source IN ('phone', 'web', 'bot', 'mcp')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    rev        integer NOT NULL DEFAULT 1,
    seq        bigint NOT NULL DEFAULT 0
);
COMMENT ON TABLE tasks.dictations IS 'Наговорки, из которых вышли дела: что сказано дословно. Дело ссылается на свою source_ref = id.';
COMMENT ON COLUMN tasks.dictations.id IS 'Та же метка, что source_ref у её дел: raznoska:<id черновика> (Разноска телефона), parse:<uuid> (разбор на сервере).';
CREATE INDEX dictations_owner_at ON tasks.dictations (owner_id, at DESC);
CREATE INDEX tasks_source_ref ON tasks.tasks (source_ref) WHERE source_ref IS NOT NULL;

CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON tasks.dictations FOR EACH ROW EXECUTE FUNCTION crm.stamp();
CREATE TRIGGER journal AFTER INSERT OR UPDATE OR DELETE ON tasks.dictations FOR EACH ROW EXECUTE FUNCTION crm.journal();

ALTER TABLE tasks.dictations ENABLE ROW LEVEL SECURITY;
-- Своё — только своё: наговорка бывает и про семью, и про деньги.
CREATE POLICY own ON tasks.dictations FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));

ALTER TABLE tasks.suggestions ADD COLUMN seen_at timestamptz;
COMMENT ON COLUMN tasks.suggestions.seen_at IS 'Человек увидел решённое без него («Закрыто само») и согласился — из «Нового» уходит.';
