-- Дела, миграция 33 (15.10.2026, этап 6 аудита — люди встречи): как к человеку обращаются.
--
-- Разбор встречи у службы встреч говорит про каждого говорящего, как к нему обращаются: «вы, Иван Петрович»,
-- «ты, Оля». Владелец подтвердил спикера («Это он» в «Подскажи», имя в вебе встреч, «Уточнить» в Telegram) — обращение
-- ложится в карточку (people.learn): только в пустое поле (поменять — владелец, в карточке или строкой Claude), не
-- команде ЗФ, не семье и не самому владельцу. Видит команда: это не оценка человека, а как к нему обращаться.
-- Журнал — общий триггер crm.people.
--
-- Поезд, вагон 5 (16.10.2026) — поверх закона приватности (правило 26, dela_0026/dela_0029): карточку для людей (синк,
-- виды, ответы операций, «кто это») читают из crm.v_people, а он перечисляет поля явно — без него поле не дошло бы ни до
-- веба, ни до телефона. Вид пересобран с тем же телом dela_0029 и полем address в конце (CREATE OR REPLACE добавляет
-- колонки только последними): обращение — по ярусу, как другие имена, — owner, self и work видят, colleague (только
-- имя, короткое имя, должность, компания) — нет: так пользователю Дел оно не откроет больше, чем закон.
--
-- Обратимо: новая пустая колонка, данные не трогаются.
--
-- Номер 33 дала сборка поезда веток (16.10.2026): в ветке speakers файл звался dela_0024.sql, а номера 24–32 заняли
-- «Приватность» (24–29), дела внутри дела (30), режим отладки (31) и захват (32). Колонка зависит только от crm.people,
-- вид — от dela_0029 (crm.person_tier); повтор безвреден — колонка IF NOT EXISTS, вид OR REPLACE: копия, где миграция уже
-- стояла под прежним именем, примет dela_0033 второй раз без вреда.

ALTER TABLE crm.people ADD COLUMN IF NOT EXISTS address text CHECK (address IS NULL OR length(address) <= 120);

COMMENT ON COLUMN crm.people.address IS 'Как к человеку обращаются: «вы, Иван Петрович», «ты, Оля» (этап 6 аудита, 15.10.2026). Пишет обучение после подтверждения спикера встречи (people.learn) — только пустое; правит владелец.';

-- ── Карточка для людей — с обращением, по ярусу (поезд, вагон 5; тело — dela_0029) ──────

CREATE OR REPLACE VIEW crm.v_people WITH (security_invoker = true) AS
SELECT pe.id, pe.name, pe.short,
       CASE WHEN z.t <> 'colleague' THEN pe.aliases ELSE '{}'::text[] END AS aliases,
       pe.org_id, pe.role,
       CASE WHEN z.t <> 'colleague' THEN pe.phones ELSE '{}'::text[] END AS phones,
       CASE WHEN z.t <> 'colleague' THEN pe.emails ELSE '{}'::text[] END AS emails,
       CASE WHEN z.t <> 'colleague' THEN pe.telegram_id END AS telegram_id,
       CASE WHEN z.t <> 'colleague' THEN pe.telegram_username END AS telegram_username,
       CASE WHEN z.t IN ('owner', 'self') THEN pe.birth_day END AS birth_day,
       CASE WHEN z.t IN ('owner', 'self') THEN pe.birth_month END AS birth_month,
       CASE WHEN z.t IN ('owner', 'self') THEN pe.birth_year END AS birth_year,
       CASE WHEN z.t = 'owner' THEN pe.source END AS source,
       CASE WHEN z.t = 'owner' THEN pe.seeks END AS seeks,
       CASE WHEN z.t = 'owner' THEN pe.offers END AS offers,
       CASE WHEN z.t = 'owner' THEN pe.traits END AS traits,
       CASE WHEN z.t = 'owner' THEN pe.cadence END AS cadence,
       CASE WHEN z.t = 'owner' THEN pe.hub ELSE false END AS hub,
       pe.user_id, pe.owner_id,
       CASE WHEN z.t = 'owner' THEN pe.note END AS note,
       pe.notion_id, pe.archived_at, pe.created_at, pe.updated_at, pe.rev, pe.seq, pe.merged_into,
       z.t AS tier,
       CASE WHEN z.t <> 'colleague' THEN pe.address END AS address
FROM crm.people pe
CROSS JOIN LATERAL (SELECT crm.person_tier(crm.me(), pe.id) AS t) z;
COMMENT ON VIEW crm.v_people IS 'Карточка человека для показа (аудит №10): строки — по политике see crm.people, поля — по ярусу смотрящего (tier = crm.person_tier, одно правило; с dela_0029 пользователь Дел коллегам — всегда colleague): owner — всё; self — всё, кроме пометок (source, seeks, offers, traits, cadence, hub, note); work — без пометок и дня рождения; colleague — только имя, короткое имя, должность, компания. Скрытое — NULL, [] и false. address (обращение, dela_0033) — как другие имена: всем, кроме colleague. Читатели карточек для людей (синк, виды, ответы операций, «кто это») — этот вид.';
