-- Дела, миграция 23 (15.10.2026, аудит №23): слияние людей переносит всё и только у владельца.
--
-- person.merge переносил ссылки шестью UPDATE под правами того, кто сливает. Не переезжали: участники встреч
-- (crm.meetings.person_ids), команда сделки (crm.deals.team_ids — по ней сотрудник видит клиента), ждущие вопросы
-- «Нового» (payload.person_id и варианты ответа) и семья в Своде (people.family — «Семья» звонков). Строки, которые
-- сливающий не видит (дело сотрудника, хронология чужого проекта, дубль, заведённый другим человеком), RLS молча
-- пропускала — вплоть до самого merged_into: дубль оставался живым при уехавших ссылках. Уже слитый дубль можно было
-- слить второй раз, а сливать мог любой пользователь.
--
-- Теперь слияние — одна функция базы crm.person_merge(дубль, живая): только владелец Дел (или system), проверки словами
-- (разные карточки, живая жива, дубль ещё не слит, не оба пользователи Дел), все ссылки — мимо RLS, все строки; прежние
-- дубли этого дубля (merged_into = он) — сразу на живую (цепочек нет); учётка дубля — живой (crm.users.person_id, её
-- может переставить только system — crm.users_guard); пустые поля живой — из дубля. Имена и номера дубля дописывает
-- питон (people.op_person_merge → op_person_add: чужое имя другим именем не пишется, аудит №1).
--
-- Обратимо по смыслу: новая функция и помощник, данные не трогаются; прежнее слияние — people.op_person_merge до 15.10.

-- Массив id без одного, с другим на его месте, без повторов — порядок первого появления (участники встречи — по
-- порядку разговора).
CREATE FUNCTION crm.ids_swap(p_ids uuid[], p_from uuid, p_to uuid) RETURNS uuid[] LANGUAGE sql IMMUTABLE AS $$
    SELECT coalesce(ARRAY(SELECT x FROM unnest(array_replace(p_ids, p_from, p_to)) WITH ORDINALITY AS u(x, i)
                          GROUP BY x ORDER BY min(i)), '{}')
$$;

CREATE FUNCTION crm.person_merge(p_dup uuid, p_into uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = crm, pg_temp AS $$
DECLARE
    v_me   text := crm.me();
    v_raw  text := current_setting('dela.user', true);
    v_dup  crm.people;
    v_into crm.people;
    v_n    integer := 0;
    v_k    integer;
    v_s    record;
    v_p    jsonb;
BEGIN
    -- Склеивает людей только владелец (закон 3): слияние трогает дела, сделки и встречи всех, а отмены у него нет.
    IF v_me IS DISTINCT FROM 'system' AND v_me IS DISTINCT FROM crm.owner_id() THEN
        RAISE EXCEPTION 'Дела: склеивает людей только владелец Дел — слияние переносит дела, сделки и встречи всех';
    END IF;
    IF p_dup IS NULL OR p_into IS NULL OR p_dup = p_into THEN
        RAISE EXCEPTION 'Дела: слияние — нужны две разные карточки: дубль и живая';
    END IF;
    SELECT * INTO v_dup FROM crm.people WHERE id = p_dup FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Дела: слияние — нет такого человека (дубль)';
    END IF;
    SELECT * INTO v_into FROM crm.people WHERE id = p_into FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Дела: слияние — нет такого человека (живая карточка)';
    END IF;
    IF v_dup.merged_into IS NOT NULL THEN
        RAISE EXCEPTION 'Дела: «%» уже слит в другую карточку — второй раз его не сливаем', v_dup.name;
    END IF;
    IF v_into.merged_into IS NOT NULL OR v_into.archived_at IS NOT NULL THEN
        RAISE EXCEPTION 'Дела: «%» сама в архиве — сливать в неё нельзя', v_into.name;
    END IF;
    IF v_dup.user_id IS NOT NULL AND v_into.user_id IS NOT NULL THEN
        RAISE EXCEPTION 'Дела: «%» и «%» — оба пользователи Дел, такое решает владелец руками', v_dup.name, v_into.name;
    END IF;

    -- Дела: о ком и кто просил.
    UPDATE tasks.tasks SET person_id = p_into WHERE person_id = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE tasks.tasks SET requested_by = p_into WHERE requested_by = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    -- Сделки: ведёт, привёл, люди клиента, команда (по team_ids сотрудник видит клиента — crm.on_client).
    UPDATE crm.deals SET lead_person_id = p_into WHERE lead_person_id = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.deals SET source_person_id = p_into WHERE source_person_id = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.deals SET person_ids = crm.ids_swap(person_ids, p_dup, p_into) WHERE p_dup = ANY (person_ids);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.deals SET team_ids = crm.ids_swap(team_ids, p_dup, p_into) WHERE p_dup = ANY (team_ids);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    -- Хронология (и приватные записи дайджеста — они тоже его) и участники встреч.
    UPDATE crm.interactions SET person_ids = crm.ids_swap(person_ids, p_dup, p_into) WHERE p_dup = ANY (person_ids);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    UPDATE crm.meetings SET person_ids = crm.ids_swap(person_ids, p_dup, p_into) WHERE p_dup = ANY (person_ids);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    -- Ждущие вопросы «Нового»: «это он?» (payload.person_id) и варианты ответа кнопками (options[].person_id). Решённые
    -- не трогаем — их result — история.
    FOR v_s IN
        SELECT s.id, s.payload FROM tasks.suggestions s
         WHERE s.status = 'pending'
           AND (s.payload ->> 'person_id' = p_dup::text
                OR (jsonb_typeof(s.payload -> 'options') = 'array'
                    AND EXISTS (SELECT 1 FROM jsonb_array_elements(s.payload -> 'options') AS e(o)
                                 WHERE o ->> 'person_id' = p_dup::text)))
    LOOP
        v_p := v_s.payload;
        IF v_p ->> 'person_id' = p_dup::text THEN
            v_p := jsonb_set(v_p, '{person_id}', to_jsonb(p_into::text));
        END IF;
        IF jsonb_typeof(v_p -> 'options') = 'array' THEN
            v_p := jsonb_set(v_p, '{options}', (
                SELECT coalesce(jsonb_agg(CASE WHEN o ->> 'person_id' = p_dup::text
                                               THEN jsonb_set(o, '{person_id}', to_jsonb(p_into::text)) ELSE o END
                                          ORDER BY i), '[]'::jsonb)
                  FROM jsonb_array_elements(v_p -> 'options') WITH ORDINALITY AS e(o, i)));
        END IF;
        UPDATE tasks.suggestions SET payload = v_p WHERE id = v_s.id;
        v_n := v_n + 1;
    END LOOP;
    -- Семья в Своде (people.family — массив id: «Семья» звонков на телефоне и дайджест).
    UPDATE crm.svod SET
           value = (SELECT coalesce(jsonb_agg(x ORDER BY i), '[]'::jsonb)
                      FROM (SELECT CASE WHEN e = to_jsonb(p_dup::text) THEN to_jsonb(p_into::text) ELSE e END AS x,
                                   min(i) AS i
                              FROM jsonb_array_elements(value) WITH ORDINALITY AS t(e, i) GROUP BY 1) q),
           author = 'merge', reason = 'слияние людей: «' || v_dup.name || '» → «' || v_into.name || '»'
     WHERE key = 'people.family' AND jsonb_typeof(value) = 'array' AND value @> jsonb_build_array(p_dup::text);
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    -- Прежние дубли этого дубля — сразу на живую: встреча или голос со старым id найдут живого за один шаг.
    UPDATE crm.people SET merged_into = p_into WHERE merged_into = p_dup;
    GET DIAGNOSTICS v_k = ROW_COUNT; v_n := v_n + v_k;
    -- Учётка Дел дубля — живой: user_id уникален (сначала снять с дубля), а crm.users.person_id переставляет только
    -- system (crm.users_guard) — на один запрос, потом прежний пользователь.
    IF v_dup.user_id IS NOT NULL THEN
        UPDATE crm.people SET user_id = NULL WHERE id = p_dup;
        UPDATE crm.people SET user_id = v_dup.user_id WHERE id = p_into;
    END IF;
    IF EXISTS (SELECT 1 FROM crm.users WHERE person_id = p_dup) THEN
        PERFORM set_config('dela.user', 'system', true);
        UPDATE crm.users SET person_id = p_into WHERE person_id = p_dup;
        PERFORM set_config('dela.user', coalesce(v_raw, ''), true);
    END IF;
    -- Пустое у живой — из дубля (компания, должность, день рождения, заметка). День и месяц пустуют только вместе.
    UPDATE crm.people SET org_id = coalesce(org_id, v_dup.org_id), role = coalesce(role, v_dup.role),
           birth_day = coalesce(birth_day, v_dup.birth_day), birth_month = coalesce(birth_month, v_dup.birth_month),
           birth_year = coalesce(birth_year, v_dup.birth_year), note = coalesce(note, v_dup.note)
     WHERE id = p_into;
    UPDATE crm.people SET merged_into = p_into, archived_at = coalesce(archived_at, now()) WHERE id = p_dup;
    RETURN jsonb_build_object('dup', to_jsonb(v_dup), 'moved', v_n);
END $$;

COMMENT ON FUNCTION crm.person_merge(uuid, uuid) IS 'Слить дубль в живую карточку (аудит №23, dela_0023): только владелец Дел или system; все ссылки мимо RLS — дела (person_id, requested_by), сделки (ведёт, привёл, люди клиента, команда), хронология, участники встреч, ждущие вопросы «Нового» (person_id и варианты), Свод people.family, прежние дубли дубля, учётка Дел; пустые поля живой — из дубля; дубль — archived_at и merged_into. Ответ: {dup — строка дубля до слияния, moved — сколько строк переехало}. Имена и номера дубля дописывает people.op_person_merge.';
