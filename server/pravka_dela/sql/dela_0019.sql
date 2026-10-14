-- Дела, миграция 19 (14.10.2026): люди сами — новые люди из Telegram и телефонной книжки в «Новом».
--
-- Владелец: «у меня будут появляться новые контакты — как они будут подхватываться CRMом… надо придумать протокол»;
-- на предложение «уверен — заводит сам с „Вернуть“, не уверен — вопрос в „Подскажи“, не деловой — больше не
-- спрашивать» ответил «согласен, давай делаем». До этого людей заводили только словом владельца и разовой сверкой;
-- новых собеседников из Telegram и новых номеров телефона Дела не видели вовсе.
--
-- Конвейер живёт у службы дайджеста на компе (сессия Telegram одна). Здесь — только предложение нового вида person:
-- • вопрос в «Подскажи»: «Новый в Telegram: Ivan Petrov, пишет про „Ромашку“ — завести?» или «Это Иван из
--   „Ромашки“?» — с вариантами кнопками (payload.options); «не надо» — «не для CRM», служба больше не спросит;
-- • заведённое само — «Сделано само» с «Вернуть» (карточка уходит в архив).
-- Дела у такого предложения нет (task_id пуст, как у create): результат — карточка человека (result.person_id).

ALTER TABLE tasks.suggestions DROP CONSTRAINT suggestions_kind_check;
ALTER TABLE tasks.suggestions ADD CONSTRAINT suggestions_kind_check
    CHECK (kind IN ('create', 'close', 'update', 'assign', 'person'));

-- «create или есть дело» — безымянная проверка из dela_0001: ищем её по смыслу, а не по имени, которое дал Postgres.
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c FROM pg_constraint
    WHERE conrelid = 'tasks.suggestions'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%task_id IS NOT NULL%';
    IF c IS NOT NULL THEN
        EXECUTE format('ALTER TABLE tasks.suggestions DROP CONSTRAINT %I', c);
    END IF;
END $$;
ALTER TABLE tasks.suggestions ADD CONSTRAINT suggestions_task_check
    CHECK (kind IN ('create', 'person') OR task_id IS NOT NULL);

-- Телефонная книжка (контакты владельца) — свой источник: человек появился там, а не в переписке.
ALTER TABLE tasks.suggestions DROP CONSTRAINT suggestions_source_check;
ALTER TABLE tasks.suggestions ADD CONSTRAINT suggestions_source_check
    CHECK (source IN ('meeting', 'telegram', 'userbot', 'bot', 'import', 'phone', 'mcp', 'user', 'krug', 'contacts'));

COMMENT ON COLUMN tasks.suggestions.kind IS 'create — новое дело; close — предложить закрыть («отправил»); update — поправить; assign — предложить дело пользователю (Марианне — только с её согласия); person — новый человек из Telegram или телефонной книжки (dela_0019): завести карточку (payload.person, org_name) или дописать в найденную (payload.person_id).';
