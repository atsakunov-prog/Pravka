-- Дела, миграция 31 (15.10.2026, правило 28): режим отладки — записки о багах логики вместе с тем, что было на экране.
--
-- Владелец: «Давай сделаем в делах накопление багов. Прямо такой debug mode. Наверху где-то прямо текстбокс в телефоне
-- и в компе. И туда я просто накидываю баги, причём они идут с тем, что у меня сейчас на экране». День он пользуется
-- Делами и кидает сюда «сказал X — сделал Y»; сессия Claude читает записки по живым данным (коннектор dela_debug,
-- CLI `python -m pravka_dela debug`), ставит статус и пишет ответ — его видно в вебе и на телефоне.
--
-- Записка — текст владельца, откуда (web / phone, версия, размер экрана), снимок экрана ДАННЫМИ (маршрут, фильтры,
-- открытая карточка, видимые строки — JSON, его собирает клиент), последние действия страницы (операции, правка словами
-- и ответ Claude, тосты, ошибки сети) и, если телефон смог, картинка экрана строкой data:. Видит и пишет только
-- владелец (роль owner): в синк команды не идёт, узким токенам служб вид и операции не отдаются. Журнала (crm.history)
-- нет — как у команд Засечки: снимок экрана несёт названия дел и людей, копить их в общем журнале незачем; сама строка
-- помнит, кто и когда поменял статус. Строки не удаляются (правило 8): разобранная — статусом.
--
-- Номер 31 дала сборка поезда веток (16.10.2026): в ветке debug файл звался dela_0024_debug.sql — у других веток были
-- свои dela_0024…, а номера 24–30 заняли «Приватность» и «Дела внутри дела». Миграция ни от одной из них не зависит
-- (только crm.users и функции dela_0001), а повтор безвреден — всё IF NOT EXISTS / OR REPLACE / DROP … IF EXISTS: копия,
-- где она уже стояла под прежним именем, примет dela_0031 второй раз без вреда (тест test_migration_again_is_harmless).

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

CREATE TABLE IF NOT EXISTS crm.debug_notes (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    text NOT NULL REFERENCES crm.users,
    num         integer NOT NULL CHECK (num > 0),
    said_at     timestamptz NOT NULL DEFAULT now(),
    text        text NOT NULL CHECK (btrim(text) <> '' AND length(text) <= 4000),
    source      text NOT NULL CHECK (source IN ('web', 'phone')),
    version     text NOT NULL DEFAULT '' CHECK (length(version) <= 80),
    screen      text NOT NULL DEFAULT '' CHECK (length(screen) <= 80),
    snapshot    jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(snapshot) = 'object'),
    actions     jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(actions) = 'array'),
    shot        text CHECK (shot IS NULL OR (shot LIKE 'data:image/%' AND length(shot) <= 400000)),
    status      text NOT NULL DEFAULT 'new' CHECK (status IN ('new', 'taken', 'fixed', 'not_bug', 'later')),
    answer      text NOT NULL DEFAULT '' CHECK (length(answer) <= 8000),
    status_by   text,
    status_at   timestamptz,
    via         text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    rev         integer NOT NULL DEFAULT 1,
    seq         bigint NOT NULL DEFAULT 0,
    UNIQUE (owner_id, num)
);
COMMENT ON TABLE crm.debug_notes IS 'Режим отладки Дел (15.10.2026): записка владельца о баге логики со снимком экрана данными и последними действиями страницы. Пишут веб и телефон (debug.add), разбирает сессия Claude (debug.set: статус и ответ). Только владельцу.';
COMMENT ON COLUMN crm.debug_notes.num IS 'Номер записки по порядку у владельца: «🐞 №12».';
COMMENT ON COLUMN crm.debug_notes.said_at IS 'Когда записал (часы устройства; без них — миг приёма).';
COMMENT ON COLUMN crm.debug_notes.source IS 'web — веб Дел, phone — вкладка Дел телефона.';
COMMENT ON COLUMN crm.debug_notes.version IS 'Версия кода: у веба — сборка app.js, у телефона — номер сборки APK.';
COMMENT ON COLUMN crm.debug_notes.screen IS 'Размер экрана: «1280×800», у телефона — ещё сложен ли.';
COMMENT ON COLUMN crm.debug_notes.snapshot IS 'Что было на экране, данными (не картинкой): маршрут, заголовок, режим и фильтры, выбранное, открытая карточка, видимые строки списка, свёрнутые блоки, «Новое» на экране, разговор с Claude. Формат — server/contract/dela-debug.json.';
COMMENT ON COLUMN crm.debug_notes.actions IS 'Последние события страницы (до 100), старые первыми: операции, правка словами и ответ Claude, тосты, ошибки сети, переходы.';
COMMENT ON COLUMN crm.debug_notes.shot IS 'Картинка экрана строкой data:image/… (телефон, сжатая, до ~300 КБ); пусто — не снимали.';
COMMENT ON COLUMN crm.debug_notes.status IS 'new — новое; taken — разбираю; fixed — починено; not_bug — не баг; later — потом (слова владельца — debug.STATUS_WORD).';
COMMENT ON COLUMN crm.debug_notes.answer IS 'Ответ разбора: что нашёл и что сделал (пишет сессия Claude), видно в вебе и на телефоне.';
COMMENT ON COLUMN crm.debug_notes.status_by IS 'Кто последним поменял статус или ответ: claude (коннектор, командная строка) или пользователь.';

CREATE INDEX IF NOT EXISTS debug_notes_open ON crm.debug_notes (owner_id, num DESC) WHERE status IN ('new', 'taken');

-- Только владельцу Дел: записки — про его экран (как команды Засечки, dela_0016). Текст и снимок не переписываются —
-- разбор меняет только статус и ответ.
CREATE OR REPLACE FUNCTION crm.debug_rules() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM crm.users u WHERE u.id = NEW.owner_id AND u.role = 'owner') THEN
        RAISE EXCEPTION 'Дела: режим отладки — только владельцу Дел, а % не владелец', NEW.owner_id;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.owner_id <> OLD.owner_id OR NEW.num <> OLD.num OR NEW.text <> OLD.text
                             OR NEW.snapshot <> OLD.snapshot OR NEW.actions <> OLD.actions
                             OR NEW.shot IS DISTINCT FROM OLD.shot OR NEW.said_at <> OLD.said_at) THEN
        RAISE EXCEPTION 'Дела: записку отладки не переписывают — меняются только статус и ответ';
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS rules ON crm.debug_notes;
CREATE TRIGGER rules BEFORE INSERT OR UPDATE ON crm.debug_notes FOR EACH ROW EXECUTE FUNCTION crm.debug_rules();
DROP TRIGGER IF EXISTS zz_stamp ON crm.debug_notes;
CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON crm.debug_notes FOR EACH ROW EXECUTE FUNCTION crm.stamp();

ALTER TABLE crm.debug_notes ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS own ON crm.debug_notes;
CREATE POLICY own ON crm.debug_notes FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));
