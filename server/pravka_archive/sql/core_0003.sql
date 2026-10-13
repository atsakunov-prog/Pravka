-- Узкие токены служб компа (13.10.2026). Владелец: «Я хочу, чтобы правда была на
-- сервере. А добавляю я спокойно из телефона или из компьютера».
--
-- Еда с этого дня — правда архива, а не телефона: приём пишут и Правка, и служба
-- «Еда» на компе, и каждая забирает чужие правки (POST /sync, sync.py). Телефон
-- ходит прежним токеном PRAVKA_INGEST_TOKEN, служба — своим: в базе только sha256,
-- писать она может лишь свои виды (kinds) и только от устройства svc-<имя>, а
-- забирать — только их же. Выдаёт и отзывает токен команда `token` (__main__.py).

CREATE TABLE core.tokens (
    name       text PRIMARY KEY CHECK (name ~ '^[a-z][a-z0-9-]{1,30}$'),
    kinds      text[] NOT NULL CHECK (cardinality(kinds) > 0),
    sha256     text NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz
);
COMMENT ON TABLE core.tokens IS 'Токены служб компа: имя, какие виды можно писать и забирать, sha256 токена.';
