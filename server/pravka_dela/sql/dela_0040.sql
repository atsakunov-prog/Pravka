-- Дела, миграция 40 (23.11.2026): календарь Google в Делах — зеркало событий владельца.
--
-- Владелец 23.11: «мне дела просто некуда записывать… записываю в календарь, а это ужас», а утром — «доделаем
-- календарь». Главный вид Дел (/t) должен показывать день целиком: дела и встречи рядом, как Todoist с подключённым
-- календарём. Ключ Google есть только у службы встреч (она же ставит встречи из дел в календарь, gcal.py), поэтому
-- она раз в несколько минут кладёт сюда события окна «неделя назад — восемь недель вперёд» операцией cal.put, а веб
-- читает их видом cal. Правда — Google: событие, пропавшее там, уходит и здесь.
--
-- Это зеркало чужого источника, а не данные Дел: журнала (crm.history) нет — сознательно, как у книги трат: зеркало
-- переписывается каждые пять минут и утопило бы журнал; строки удаляются (правило 8 — про данные Дел, у зеркала нет
-- синка и надгробий). Видит каждый только своё: событие владельца — не дело проекта, команде его не показать.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

CREATE TABLE crm.cal_events (
    owner_id   text NOT NULL REFERENCES crm.users,
    id         text NOT NULL CHECK (btrim(id) <> '' AND length(id) <= 300),
    title      text NOT NULL DEFAULT '' CHECK (length(title) <= 500),
    all_day    boolean NOT NULL DEFAULT false,
    start_at   timestamptz,
    end_at     timestamptz,
    day        date NOT NULL,
    last_day   date NOT NULL,
    location   text NOT NULL DEFAULT '' CHECK (length(location) <= 500),
    link       text NOT NULL DEFAULT '' CHECK (length(link) <= 1000),
    url        text NOT NULL DEFAULT '' CHECK (length(url) <= 1000),
    task_id    uuid,
    busy       boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, id),
    CHECK (last_day >= day),
    CHECK (all_day OR (start_at IS NOT NULL AND end_at IS NOT NULL AND end_at >= start_at))
);
COMMENT ON TABLE crm.cal_events IS 'Календарь Google владельца — зеркало окна «неделя назад — восемь недель вперёд»: кладёт служба встреч (cal.put, раз в 5 минут), читает веб (вид cal). Правда — Google; журнала нет, строки уходят вместе с событием. Видит только хозяин.';
COMMENT ON COLUMN crm.cal_events.id IS 'id события Google (у повторов — id экземпляра).';
COMMENT ON COLUMN crm.cal_events.day IS 'Первый день события по Москве.';
COMMENT ON COLUMN crm.cal_events.last_day IS 'Последний день события по Москве, включительно (у Google конец «весь день» — исключительно).';
COMMENT ON COLUMN crm.cal_events.link IS 'Ссылка на созвон: Meet, Zoom, Teams, Телемост — из события, места или описания.';
COMMENT ON COLUMN crm.cal_events.url IS 'Событие в Google Календаре (htmlLink).';
COMMENT ON COLUMN crm.cal_events.task_id IS 'Событие поставлено службой встреч из дела (extendedProperties dela_task): дело и так в списке, веб второй строкой его не рисует.';
COMMENT ON COLUMN crm.cal_events.busy IS 'false — «свободен» в Google (transparency transparent).';

CREATE INDEX cal_events_days ON crm.cal_events (owner_id, day, last_day);

-- Когда служба последний раз клала окно: веб честно скажет «календарь не обновлялся N часов», если служба встреч
-- лежит, а не будет молча показывать вчерашнее.
CREATE TABLE crm.cal_sync (
    owner_id  text PRIMARY KEY REFERENCES crm.users,
    at        timestamptz NOT NULL DEFAULT now(),
    events    integer NOT NULL DEFAULT 0 CHECK (events >= 0),
    day_from  date NOT NULL,
    day_to    date NOT NULL CHECK (day_to >= day_from)
);
COMMENT ON TABLE crm.cal_sync IS 'Последняя присылка календаря (cal.put): когда, сколько событий, какое окно.';

ALTER TABLE crm.cal_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY own ON crm.cal_events FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));

ALTER TABLE crm.cal_sync ENABLE ROW LEVEL SECURITY;
CREATE POLICY own ON crm.cal_sync FOR ALL
    USING (crm.me() IN (owner_id, 'system')) WITH CHECK (crm.me() IN (owner_id, 'system'));
