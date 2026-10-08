-- Сказанное: один указатель на всех людей архива (08.10.2026 вынесен из
-- life.sql). Строится один раз после всех схем людей: core.said_source
-- читает все записи разом и несёт person, а вид said каждой схемы берёт из
-- core.said_index только своего человека (core.said_life, core.said_marianna).
-- Время ЧЧ:ММ — функцией хозяина life.hm: схема life есть всегда.

DROP VIEW IF EXISTS core.said_source;
CREATE VIEW core.said_source AS
SELECT r.person, r.kind, r.key, 'raw:' || (e->>'id') AS part, 'засечка' AS domain,
       (r.data->>'day')::date AS day, (e->>'start')::timestamptz AS at, life.hm(e->>'start') AS time_local,
       't' || (e->>'id') AS ref, split_part(e->>'raw', E'\nКБЖУ:', 1) AS text
FROM core.records r, jsonb_array_elements(r.data->'entries') e
WHERE r.kind = 'zasechka.day' AND NOT r.deleted AND COALESCE(e->>'raw', '') <> ''
UNION ALL
SELECT r.person, r.kind, r.key, 'comment:' || (e->>'id'), 'засечка', (r.data->>'day')::date, (e->>'start')::timestamptz,
       life.hm(e->>'start'), 't' || (e->>'id'), e->>'comment'
FROM core.records r, jsonb_array_elements(r.data->'entries') e
WHERE r.kind = 'zasechka.day' AND NOT r.deleted AND COALESCE(e->>'comment', '') <> ''
UNION ALL
SELECT r.person, r.kind, r.key, 'raw', 'еда', (r.data->>'day')::date, (r.data->>'at')::timestamptz, life.hm(r.data->>'at'),
       r.key, r.data->>'raw'
FROM core.records r
WHERE r.kind = 'food.meal' AND NOT r.deleted AND COALESCE(r.data->>'raw', '') <> ''
UNION ALL
SELECT r.person, r.kind, r.key, 'text', CASE r.kind WHEN 'money.take' THEN 'деньги' ELSE 'силовые' END,
       (r.data->>'day')::date, (r.data->>'at')::timestamptz, life.hm(r.data->>'at'), r.key, r.data->>'text'
FROM core.records r
WHERE r.kind IN ('strength.take', 'money.take') AND NOT r.deleted AND COALESCE(r.data->>'text', '') <> ''
UNION ALL
SELECT r.person, r.kind, r.key, 'note', 'силовые', COALESCE((r.data->>'day')::date, CASE WHEN r.kind = 'strength.gtg' THEN r.key::date END),
       NULL::timestamptz, NULL, r.key, r.data->>'note'
FROM core.records r
WHERE r.kind IN ('strength.session', 'strength.gtg') AND NOT r.deleted AND COALESCE(r.data->>'note', '') <> ''
UNION ALL
SELECT r.person, r.kind, r.key, 'text', 'правка', (r.data->>'day')::date, (r.data->>'at')::timestamptz, life.hm(r.data->>'at'),
       r.key, r.data->>'text'
FROM core.records r
WHERE r.kind = 'pravka.take' AND NOT r.deleted AND COALESCE(r.data->>'text', '') <> ''
UNION ALL
SELECT r.person, r.kind, r.key, 'question', 'тренер', (r.data->>'day')::date, (r.data->>'at')::timestamptz, life.hm(r.data->>'at'),
       r.key, r.data->>'question'
FROM core.records r
WHERE r.kind = 'sport.talk' AND NOT r.deleted AND COALESCE(r.data->>'question', '') <> ''
UNION ALL
SELECT r.person, r.kind, r.key, 'answer', 'тренер', (r.data->>'day')::date, (r.data->>'at')::timestamptz, life.hm(r.data->>'at'),
       r.key, r.data->>'answer'
FROM core.records r
WHERE r.kind = 'sport.talk' AND NOT r.deleted AND COALESCE(r.data->>'answer', '') <> ''
UNION ALL
SELECT r.person, r.kind, r.key, 'comment', 'форма', r.key::date, NULL, NULL, r.key, r.data->>'comments'
FROM core.records r
WHERE r.kind = 'icu.wellness' AND NOT r.deleted AND COALESCE(r.data->>'comments', '') <> ''
UNION ALL
SELECT r.person, r.kind, r.key, 'description', 'тренировки', left(r.data->>'start_date_local', 10)::date,
       (r.data->>'start_date')::timestamptz, substring(r.data->>'start_date_local' from 12 for 5), r.key, r.data->>'description'
FROM core.records r
WHERE r.kind = 'icu.activity' AND NOT r.deleted AND COALESCE(r.data->>'description', '') <> '';
