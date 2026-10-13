-- Дела, миграция 14 (13.10.2026): дайджест Telegram — к телефону через сервер («Правка 5.0»).
--
-- Владелец хочет видеть последнюю сводку переписки на экране «Сегодня» телефона (карточка растворяется книзу,
-- нажатие — целиком). До этого сводка жила только у службы дайджеста на компе (C:\Bot\Digest\auto.py) и уходила
-- ботом в Telegram: у телефона не было к ней дороги. Дорога — как у «Нового»: служба дайджеста кладёт сводку в Дела
-- своим токеном (операция digest.put), телефон забирает её обычным синком Дел (последняя — полем digest) или видом
-- digest (список за пару дней).
--
-- Дайджест — личная переписка владельца: видит только он (роль owner), не семья и не команда. Поэтому:
-- • строка принадлежит пользователю токена службы (owner_id), RLS «только своё» — как у встреч и Свода;
-- • триггер не пускает строку на не-владельца: служебный токен, выданный по ошибке на Наташу, дайджест не запишет;
-- • журнала (crm.history) нет — сознательно, как у реплик встречи и точек «Где мы»: это готовый вывод службы, а
--   не правка человека, и текст переписки не должен копиться в общем журнале, который читают карточки и выгрузки.
--   Версия и номер изменения (rev, seq) есть — по ним синк отдаёт новую сводку.
-- Строки не удаляются (правило 8): сводка за прошлую неделю — та же история, что и «Новое» той пачки.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

CREATE TABLE crm.digests (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id    text NOT NULL REFERENCES crm.users,
    ref         text NOT NULL CHECK (btrim(ref) <> '' AND length(ref) <= 64),
    started_at  timestamptz,
    ended_at    timestamptz NOT NULL,
    label       text NOT NULL CHECK (btrim(label) <> ''),
    chats       integer CHECK (chats >= 0),
    msgs        integer CHECK (msgs >= 0),
    summary     jsonb NOT NULL CHECK (jsonb_typeof(summary) = 'object'),
    text        text NOT NULL,
    dela        jsonb CHECK (dela IS NULL OR jsonb_typeof(dela) = 'object'),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    rev         integer NOT NULL DEFAULT 1,
    seq         bigint NOT NULL DEFAULT 0,
    UNIQUE (owner_id, ref),
    CHECK (started_at IS NULL OR started_at < ended_at)
);
COMMENT ON TABLE crm.digests IS 'Сводки дайджеста Telegram (служба дайджеста на компе): окно переписки, сводка пунктами и текстом, что ушло в Дела. Видит только владелец.';
COMMENT ON COLUMN crm.digests.ref IS 'Окно у службы: digest:ГГГГ-ММ-ДДTЧЧММ (конец окна) — тот же batch_ref, что у «Нового» этой пачки. Повторная присылка обновляет ту же строку.';
COMMENT ON COLUMN crm.digests.summary IS 'Сводка пунктами: waiting [{chat, who, what}] — ждут ответа, work [{topic, text}] — по работе, family [строки] — дом, other — прочее одной фразой.';
COMMENT ON COLUMN crm.digests.text IS 'Та же сводка простым текстом (собирает сервер, digest.text_of): разделы, пункты, строка «В Дела».';
COMMENT ON COLUMN crm.digests.dela IS 'Что ушло в Дела из этого окна: created, asks, close, update, closed, updated — числа; batch — batch_ref пачки в «Новом». Пусто — Дела в тот миг не ответили.';

CREATE INDEX digests_last ON crm.digests (owner_id, ended_at DESC);

-- Только владельцу Дел: роль решает база, а не клиент (правило 3).
CREATE FUNCTION crm.digest_rules() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM crm.users u WHERE u.id = NEW.owner_id AND u.role = 'owner') THEN
        RAISE EXCEPTION 'Дела: дайджест — только владельцу Дел, а % не владелец', NEW.owner_id;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER rules BEFORE INSERT OR UPDATE ON crm.digests FOR EACH ROW EXECUTE FUNCTION crm.digest_rules();
CREATE TRIGGER zz_stamp BEFORE INSERT OR UPDATE ON crm.digests FOR EACH ROW EXECUTE FUNCTION crm.stamp();

ALTER TABLE crm.digests ENABLE ROW LEVEL SECURITY;
CREATE POLICY own ON crm.digests FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));
