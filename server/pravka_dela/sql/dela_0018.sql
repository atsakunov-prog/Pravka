-- Дела, миграция 18 (14.10.2026): дайджест Telegram — по карточкам CRM, видит только владелец.
--
-- Владелец: «дайджесты Телеграма надо разносить по карточкам CRM, а также складывать на сервере, чтобы через коннектор
-- их можно было читать». Сводки уже лежат в crm.digests (dela_0014). Теперь каждый пункт сводки о рабочем человеке или
-- клиенте ложится записью в хронологию (crm.interactions, source = digest, kind = telegram): у человека, у его клиента
-- и сделки — так же, как встречи и звонки. Разносит сервер сам при digest.put, без Claude (digest_crm.py).
--
-- Но CRM видит команда ЗФ, а дайджест — личная переписка владельца (правило 21). Поэтому:
-- • у записи хронологии появляется private — «видит только хозяин записи»: политика see/upd её команде не отдаёт,
--   даже если проект клиента ей открыт; запись из дайджеста всегда private (CHECK);
-- • журнала у приватной записи нет — как у crm.digests: текст переписки не копится в crm.history, который читают
--   журналы карточек и выгрузки; без dela.actor она всё равно не ляжет (триггер private — вместо журнала);
-- • приватность не меняется после записи: «открыть команде» запись из Telegram было бы утечкой задним числом;
-- • source_ref записи — «<ref сводки>#<ключ пункта>»: повтор той же сводки не плодит записей (уникальный индекс),
--   а ссылка на сводку — всё до «#» (виды отдают её полем digest_ref).
-- crm.digests.sources — чаты окна, как их знает служба (название, личный или группа, id в Telegram, кто писал):
-- по ним сервер узнаёт людей. Строки не удаляются (правило 8): лишняя запись пункта — deleted_at.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

-- ── Хронология: запись «только хозяину» ─────────────────────────────────

ALTER TABLE crm.interactions ADD COLUMN private boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN crm.interactions.private IS 'Видит только хозяин записи (owner_id): команда не видит её ни в карточке, ни в синке, ни в журнале. Запись из дайджеста Telegram — всегда. Не меняется после записи.';

-- Журнал — только у общих записей. Приватность не меняется, поэтому у правки OLD.private = NEW.private.
DROP TRIGGER journal ON crm.interactions;
CREATE TRIGGER journal AFTER INSERT ON crm.interactions
    FOR EACH ROW WHEN (NOT NEW.private) EXECUTE FUNCTION crm.journal();
CREATE TRIGGER journal_upd AFTER UPDATE ON crm.interactions
    FOR EACH ROW WHEN (NOT OLD.private) EXECUTE FUNCTION crm.journal();
CREATE TRIGGER journal_del AFTER DELETE ON crm.interactions
    FOR EACH ROW WHEN (NOT OLD.private) EXECUTE FUNCTION crm.journal();

-- Записи из дайджеста, если они уже были (источник digest разрешён с первой миграции), — приватные: это Telegram
-- владельца. До триггера private: он запретил бы менять приватность.
UPDATE crm.interactions SET private = true WHERE source = 'digest' AND NOT private;
ALTER TABLE crm.interactions ADD CONSTRAINT interactions_digest_private CHECK (source <> 'digest' OR private);

CREATE FUNCTION crm.interaction_private() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.private IS DISTINCT FROM OLD.private THEN
        RAISE EXCEPTION 'Дела: приватность записи хронологии не меняется — запись из Telegram не открывают команде задним числом';
    END IF;
    -- У приватной записи журнала нет, а правило «без dela.actor не пишем» держал журнал — держим здесь.
    IF NEW.private AND crm.actor() IS NULL THEN
        RAISE EXCEPTION 'Дела: изменение без dela.actor — некому его подписать (crm.interactions)';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER private BEFORE INSERT OR UPDATE ON crm.interactions FOR EACH ROW EXECUTE FUNCTION crm.interaction_private();

-- Видимость: приватная запись — только хозяину, открытый проект клиента её не показывает.
DROP POLICY see ON crm.interactions;
CREATE POLICY see ON crm.interactions FOR SELECT
    USING (crm.me() IN (owner_id, 'system')
           OR (NOT private AND project_id IS NOT NULL AND crm.sees_project(crm.me(), project_id)));
DROP POLICY upd ON crm.interactions;
CREATE POLICY upd ON crm.interactions FOR UPDATE
    USING (crm.me() IN (owner_id, 'system')
           OR (NOT private AND project_id IS NOT NULL AND crm.edits_project(crm.me(), project_id)));

-- Пункт сводки — одна запись на (сводка, пункт, клиент): повтор сводки находит её, а не заводит новую.
CREATE UNIQUE INDEX interactions_digest_item ON crm.interactions (owner_id, source_ref) WHERE source = 'digest';

-- ── Сводка: чаты окна ───────────────────────────────────────────────────

ALTER TABLE crm.digests ADD COLUMN sources jsonb CHECK (sources IS NULL OR jsonb_typeof(sources) = 'array');
COMMENT ON COLUMN crm.digests.sources IS 'Чаты окна, как их знает служба: [{name, kind: личный|группа, tg_id, peer_id, username, senders: {имя: id}}]. По ним сервер узнаёт людей пунктов (digest_crm.py). Пусто — сводка до 14.10.2026 или старая служба.';
