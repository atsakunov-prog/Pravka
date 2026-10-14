-- Дела, миграция 17 (14.10.2026): одна книга трат Claude и недельные советы экономии.
--
-- Владелец: «значок денег Claude (все запросы в Claude) на главный экран… проаудировать, что мы всё учитываем, и
-- систематизировать лучше по задачам… раз в неделю сам Claude пускай предлагает варианты экономии (красная точка над
-- значком)». До этого траты лежали в десятке мест (crm.state llm_cost Дел, kv.llm_cost Встреч, usage.json Шаббата,
-- Еды, Денег, Диктовки, цена.json книг, экран «$» телефона) — каждое своим разрезом, и сложить их можно было только
-- руками. Книга — одна таблица на сервере: день · служба · задача · модель · источник, токены и $.
--
-- Кто приносит (без двойного счёта — у каждой строки один источник, src):
-- • Дела — сами, каждым своим вызовом (llm.account, src 'dela'): прибавляют к строке дня;
-- • телефон — своими тратами по дорогам (Stats), операцией spend.put, src — имя телефона: ЗАМЕНЯЕТ строку дня
--   целиком (телефон шлёт итог дня, а не прибавку — повтор безвреден);
-- • службы компа — сборщик «Дома» (токен dom, от SYSTEM читает книги служб), spend.put, src 'pc': тоже итогом дня.
--
-- Строки — числа без текстов: ни промптов, ни ответов, ни имён. Поэтому журнала (crm.history) нет — сознательно,
-- как у дайджеста: книга растёт каждым вызовом Claude и утопила бы общий журнал. Видит книгу только владелец
-- (вид spend и поле синка — только роли owner); телефон члена семьи пишет СВОИ строки (owner_id — он сам), владелец
-- читает все: это его ключи и его деньги.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

CREATE TABLE crm.claude_spend (
    owner_id       text NOT NULL REFERENCES crm.users,
    day            date NOT NULL,
    service        text NOT NULL CHECK (service ~ '^[a-z][a-z0-9_]{0,31}$'),
    task           text NOT NULL CHECK (task ~ '^[a-z0-9][a-z0-9_.-]{0,63}$'),
    model          text NOT NULL DEFAULT '' CHECK (length(model) <= 64),
    src            text NOT NULL CHECK (btrim(src) <> '' AND length(src) <= 64),
    key            text NOT NULL DEFAULT '' CHECK (length(key) <= 16),
    calls          integer NOT NULL DEFAULT 0 CHECK (calls >= 0),
    batch_calls    integer NOT NULL DEFAULT 0 CHECK (batch_calls >= 0),
    input          bigint NOT NULL DEFAULT 0 CHECK (input >= 0),
    output         bigint NOT NULL DEFAULT 0 CHECK (output >= 0),
    cache_write    bigint NOT NULL DEFAULT 0 CHECK (cache_write >= 0),
    cache_write_1h bigint NOT NULL DEFAULT 0 CHECK (cache_write_1h >= 0),
    cache_read     bigint NOT NULL DEFAULT 0 CHECK (cache_read >= 0),
    usd            numeric(14, 6) NOT NULL DEFAULT 0 CHECK (usd >= 0),
    usd_list       numeric(14, 6) CHECK (usd_list IS NULL OR usd_list >= 0),
    exact          boolean NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    rev            integer NOT NULL DEFAULT 1,
    seq            bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_id, day, service, task, model, src)
);
COMMENT ON TABLE crm.claude_spend IS 'Книга трат Claude: день (Москва) · служба · задача · модель · источник — вызовы, токены, $. Дела пишут сами (прибавкой), телефон и сборщик «Дома» — итогом дня (spend.put). Видит владелец.';
COMMENT ON COLUMN crm.claude_spend.service IS 'Служба: phone (Правка), dela, meetings, digest, dengi, eda, diktovka, shabbat, books, night… — подписи в контракте dela-spend.json.';
COMMENT ON COLUMN crm.claude_spend.task IS 'Задача (дорога): у телефона — ModelRoute строчными, у Дел — вид llm_cost (ask, parse, krug…), у служб компа — их разрез (clean, razbor, sverka…).';
COMMENT ON COLUMN crm.claude_spend.src IS 'Источник строки: dela, pc (сборщик «Дома»), имя телефона. Один источник — одна строка: двойного счёта нет.';
COMMENT ON COLUMN crm.claude_spend.key IS 'Каким ключом Anthropic платили: 1, 2, 3 (C:\\Bot\\README.md §5.2) или phone — ключ приложения.';
COMMENT ON COLUMN crm.claude_spend.usd IS 'Сколько заплачено, со скидкой пачки (Message Batches −50 %) и по ценам кэша.';
COMMENT ON COLUMN crm.claude_spend.usd_list IS 'Те же токены без скидки пачки (usd_list − usd — сэкономили пачкой). Пусто — источник не знает.';
COMMENT ON COLUMN crm.claude_spend.exact IS 'false — источник знает только $, без токенов (книга службы пишет одну сумму): кэш и пачку по строке не посчитать.';

CREATE INDEX claude_spend_day ON crm.claude_spend (day DESC);

CREATE TABLE crm.claude_tips (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    text NOT NULL REFERENCES crm.users,
    week        date NOT NULL,
    tips        jsonb NOT NULL CHECK (jsonb_typeof(tips) = 'array'),
    note        text NOT NULL DEFAULT '',
    week_usd    numeric(14, 6),
    model       text,
    usd         numeric(14, 6),
    seen_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    rev         integer NOT NULL DEFAULT 1,
    seq         bigint NOT NULL DEFAULT 0,
    UNIQUE (owner_id, week)
);
COMMENT ON TABLE crm.claude_tips IS 'Советы экономии Claude раз в неделю (spend_advice.py: воскресенье вечером, Opus пачкой): 3–5 вариантов с прикидкой $. seen_at пусто — красная точка над значком трат. Только владельцу.';
COMMENT ON COLUMN crm.claude_tips.week IS 'Понедельник недели, о которой советы.';
COMMENT ON COLUMN crm.claude_tips.tips IS '[{title, why, how, save_usd_month, risk}] — как вернул советник.';
COMMENT ON COLUMN crm.claude_tips.seen_at IS 'Когда владелец открыл советы (spend.seen): точка гаснет.';

-- Советы — только владельцу: строка не ляжет на не-владельца, даже если код ошибся (правило 3 — права в базе).
CREATE FUNCTION crm.claude_tips_rules() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM crm.users u WHERE u.id = NEW.owner_id AND u.role = 'owner') THEN
        RAISE EXCEPTION 'Дела: советы по тратам Claude — только владельцу Дел, а % не владелец', NEW.owner_id;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER rules BEFORE INSERT OR UPDATE ON crm.claude_tips FOR EACH ROW EXECUTE FUNCTION crm.claude_tips_rules();
CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON crm.claude_spend FOR EACH ROW EXECUTE FUNCTION crm.stamp();
CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON crm.claude_tips FOR EACH ROW EXECUTE FUNCTION crm.stamp();

-- Книга: пишет каждый свои строки; читает своё, а владелец — всё (телефон Марианны платит его ключом).
ALTER TABLE crm.claude_spend ENABLE ROW LEVEL SECURITY;
CREATE POLICY own ON crm.claude_spend FOR ALL
    USING (crm.me() IN (owner_id, 'system') OR crm.me() = crm.owner_id())
    WITH CHECK (crm.me() IN (owner_id, 'system'));

ALTER TABLE crm.claude_tips ENABLE ROW LEVEL SECURITY;
CREATE POLICY own ON crm.claude_tips FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));
