-- Дела, миграция 34 (15.10.2026, шаг 1 петли этапа 4): «Сейчас на завтра» — кандидаты правилом, Саша отмечает до пяти.
--
-- Саша (ответ 1а, 15.10.2026): «с 19:00 в „Подскажи“ до семи кандидатов „Сейчас на завтра“, у каждого — почему и
-- откуда; Саша отмечает до пяти»; тем же вечером заход Пн–Чт перенесён на 18:00 (asks.py). Кандидатов кладёт сервер
-- Дел правилом, без Claude (plan.py): свои предложения с источником plan. Отметка ставит focus_on будущим днём — предел пяти держит сервер на каждый день (store._now_guard).
--
-- 1. Источник plan у предложений — свой, а не bot: счёт автоматики (week.automation) и число «Нового» его не путают.
-- 2. tasks.now_days — снимок «Сейчас» по дням (plan.snap: 11:00, 18:00 перед прогоном, 23:55): что стояло, сколько дней
--    подряд (carried), пришло ли из плана, сделано ли в этот день. Без него «сделано x из y» потеряло бы дело,
--    перенесённое кнопкой на завтра, а третий перенос было бы не узнать. Из него же мерила недели (шаг 6: М5, М7).
--    Журнала (crm.history) нет сознательно — снимок растёт каждый день и утопил бы общий журнал; строки не удаляются.
-- 3. Слова у focus_on — что значит будущая и прошедшая дата.
--
-- UPDATE нет — подпись svc:migrate не нужна. Обратимо по смыслу: таблица новая (DROP), проверка источника шире прежней.
--
-- Номер 34 дала сборка поезда веток (16.10.2026): в ветке loop-1 файл звался dela_0024.sql, а номера 24–33 заняли
-- «Приватность» (24–29), дела внутри дела (30), режим отладки (31), захват (32) и люди встречи (33). Миграция ни от
-- одной из них не зависит (источник plan дописан к списку dela_0019, его больше никто не пересоздавал); повтор
-- безвреден — проверка источника пересоздаётся, таблица IF NOT EXISTS, политика заново, слова — COMMENT.

ALTER TABLE tasks.suggestions DROP CONSTRAINT suggestions_source_check;
ALTER TABLE tasks.suggestions ADD CONSTRAINT suggestions_source_check
    CHECK (source IN ('meeting', 'telegram', 'userbot', 'bot', 'import', 'phone', 'mcp', 'user', 'krug', 'contacts',
                      'plan'));
COMMENT ON COLUMN tasks.suggestions.source IS 'Откуда предложение: meeting, telegram, userbot, bot, import, phone, mcp, user; krug — разборщик Круга; contacts — телефонная книжка; plan — «Сейчас на завтра» (dela_0034, plan.py): кандидат правилом, payload.for_date, basket, why, from; batch_ref plan:<день>.';

CREATE TABLE IF NOT EXISTS tasks.now_days (
    owner_id   text NOT NULL REFERENCES crm.users,
    day        date NOT NULL,
    task_id    uuid NOT NULL REFERENCES tasks.tasks,
    carried    smallint NOT NULL DEFAULT 1 CHECK (carried >= 1),
    from_plan  boolean NOT NULL DEFAULT false,
    first_seen timestamptz NOT NULL DEFAULT now(),
    done       boolean NOT NULL DEFAULT false,
    done_at    timestamptz,
    PRIMARY KEY (owner_id, day, task_id)
);
COMMENT ON TABLE tasks.now_days IS 'Снимок «Сейчас» по дням (dela_0034, plan.snap — 11:00, 18:00, 23:55 по Москве): дело стояло в «Сейчас» этого дня (focus_on = day) или было закрыто в этот день. Строка, однажды записанная, остаётся в дне, даже если дело перенесли кнопкой на завтра. Только владельцу; журнала нет, строки не удаляются.';
COMMENT ON COLUMN tasks.now_days.carried IS 'Какой день подряд дело в «Сейчас»: 1 + carried прошлого дня со снимком, если там оно не сделано (после пятницы — понедельник). Третий день — больше не кандидат плана, а строка «разбить, отдать или убрать?».';
COMMENT ON COLUMN tasks.now_days.from_plan IS 'Есть принятое предложение «Сейчас на завтра» (source plan) с этим днём для этого дела.';
COMMENT ON COLUMN tasks.now_days.first_seen IS 'Когда снимок впервые увидел дело в этом дне.';
COMMENT ON COLUMN tasks.now_days.done IS 'Закрыто в этот день по Москве (done_at — когда).';

ALTER TABLE tasks.now_days ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS own ON tasks.now_days;
CREATE POLICY own ON tasks.now_days FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));

COMMENT ON COLUMN tasks.tasks.focus_on IS 'Сегодня — дело в «Сейчас»; будущий день — отмечено заранее («Сейчас на завтра», dela_0034): в «Сейчас» встанет в свой день; прошедший — погас. Не больше пяти на каждый день (store._now_guard).';
