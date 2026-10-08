-- Второй человек в архиве (08.10.2026). Владелец: «Марианна начала
-- пользоваться приложением… в моей базе под Марианну отдельные все записи».
--
-- До этого архив был одного человека: запись — пара (вид, ключ), а ключ у
-- суток ленты, телефона, зарядки — дата. Сутки Марианны 2026-10-08 легли бы
-- на сутки Саши 2026-10-08 и перетёрли бы их, поэтому чужой профиль сервер
-- не пускал вовсе.
--
-- Теперь у события и записи есть person: '' — хозяин архива (PRAVKA_PROFILE),
-- иначе ключ профиля телефона (marianna). Пустая строка, а не имя: всё, что
-- уже лежит, — хозяина, и миграции не нужно переписывать журнал. Ключ записи —
-- (person, kind, key). Виды каждого человека — своей схемой (life — хозяин,
-- marianna — Марианна), их собирает migrate из того же life.sql.

ALTER TABLE core.events ADD COLUMN person text NOT NULL DEFAULT '';
ALTER TABLE core.records ADD COLUMN person text NOT NULL DEFAULT '';
ALTER TABLE core.said_index ADD COLUMN person text NOT NULL DEFAULT '';

ALTER TABLE core.records DROP CONSTRAINT records_pkey;
ALTER TABLE core.records ADD CONSTRAINT records_pkey PRIMARY KEY (person, kind, key);

DROP INDEX core.records_live;
CREATE INDEX records_live ON core.records (person, kind) WHERE NOT deleted;
DROP INDEX core.events_record;
CREATE INDEX events_record ON core.events (person, kind, key, at);
DROP INDEX core.said_record;
CREATE INDEX said_record ON core.said_index (person, kind, key);
