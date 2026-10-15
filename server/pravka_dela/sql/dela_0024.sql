-- Дела, миграция 24 (15.10.2026, аудит №8): основания и подробности из личных каналов — приватным комментарием.
--
-- Сверка дайджеста и сверка встреч кладут в предложение «Нового» заметку (payload.notes) и цитату (quote) — пересказ
-- переписки Telegram и строку разбора встречи. Принятое «завести» писало заметку в само дело (tasks.notes), «закрыть»
-- и «уточнить» — общим комментарием «<пачка>: <цитата>»; комментарии видит каждый, кто видит дело, и синк отдаёт их
-- целиком. Так личная переписка владельца уходила команде клиента в карточке дела, в синке и в журнале дела.
--
-- Теперь у комментария есть private — «видит только автор»: политика see отдаёт его только автору (и system), журнал
-- (crm.history) его не держит — как у приватной записи хронологии (dela_0018); приватность не снимается (открыть
-- переписку команде задним числом — утечка), скрыть общий комментарий может только его автор. Что кладёт сервер в
-- приватное — решает store.py (PRIVATE_SOURCES): заметка машины из Telegram и встреч, основание «закрыть»/«уточнить»,
-- «Это оно» веба, «Человек не поставлен: …».
--
-- Общее для всего пакета «Приватность»: crm.access_revoked теперь значит «видимое поменялось — пересобери всё»
-- (синк уже так её читает), project_id в ней может быть пуст — «сузилось не по проекту» (карточки людей, сделка ушла в
-- архив, user поменял доступ); строку кладёт crm.access_changed.
--
-- Обратимо по смыслу: колонка новая (у всех прежних — false, как было), политика и триггеры — прежние плюс условие;
-- данные не трогаются — прежние основания делает приватными разовая команда privacy-fix после своей копии базы.

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true),
       set_config('dela.user', 'system', true);

-- ── Комментарий «только автору» ─────────────────────────────────────────

ALTER TABLE tasks.comments ADD COLUMN private boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN tasks.comments.private IS 'Видит только автор (и system): основание и подробности автоматики из Telegram и встреч, «Это оно», «только мне». Команда не видит его ни в карточке, ни в синке, ни в журнале. Не снимается; общий комментарий скрывает только его автор (аудит №8).';

-- Журнал — только у общих комментариев: текст переписки не копится в crm.history, который читают журналы карточек.
-- Правка false → true журналится (OLD общий) — видно, кто и когда скрыл; дальше у скрытого журнала нет.
DROP TRIGGER journal ON tasks.comments;
CREATE TRIGGER journal AFTER INSERT ON tasks.comments
    FOR EACH ROW WHEN (NOT NEW.private) EXECUTE FUNCTION crm.journal();
CREATE TRIGGER journal_upd AFTER UPDATE ON tasks.comments
    FOR EACH ROW WHEN (NOT OLD.private) EXECUTE FUNCTION crm.journal();
CREATE TRIGGER journal_del AFTER DELETE ON tasks.comments
    FOR EACH ROW WHEN (NOT OLD.private) EXECUTE FUNCTION crm.journal();

CREATE FUNCTION tasks.comment_private() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.private AND NOT NEW.private THEN
        RAISE EXCEPTION 'Дела: приватность комментария не снимается — основание из переписки и встреч не открывают команде задним числом';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.private AND NOT OLD.private
       AND crm.me() IS DISTINCT FROM 'system' AND crm.me() IS DISTINCT FROM NEW.author_id THEN
        RAISE EXCEPTION 'Дела: скрыть комментарий может только его автор';
    END IF;
    -- У приватного комментария журнала нет, а правило «без dela.actor не пишем» держал журнал — держим здесь.
    IF NEW.private AND crm.actor() IS NULL THEN
        RAISE EXCEPTION 'Дела: изменение без dela.actor — некому его подписать (tasks.comments)';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER private BEFORE INSERT OR UPDATE ON tasks.comments FOR EACH ROW EXECUTE FUNCTION tasks.comment_private();

DROP POLICY see ON tasks.comments;
CREATE POLICY see ON tasks.comments FOR SELECT
    USING (crm.sees_task_id(crm.me(), task_id) AND (NOT private OR crm.me() IN (author_id, 'system')));

-- ── «Видимое поменялось» — событие синку (весь пакет «Приватность») ──────────

ALTER TABLE crm.access_revoked ALTER COLUMN project_id DROP NOT NULL;
COMMENT ON TABLE crm.access_revoked IS 'Видимое человеку поменялось — его синк отдаёт full (пересобрать кэш): снятый доступ к проекту (project_id), а с 15.10.2026 (аудит №10, №11) и сужение не по проекту — карточки людей по тому, кто смотрит, сделка ушла в архив, доступ поменяли командой user (project_id пуст).';

CREATE FUNCTION crm.access_changed(p_user text, p_project uuid DEFAULT NULL) RETURNS void LANGUAGE sql SECURITY DEFINER
SET search_path = crm, pg_temp AS $$
    INSERT INTO crm.access_revoked (user_id, project_id, seq)
    SELECT p_user, p_project, nextval('crm.change_seq') WHERE EXISTS (SELECT 1 FROM crm.users WHERE id = p_user)
$$;
COMMENT ON FUNCTION crm.access_changed(text, uuid) IS 'Событие «видимое поменялось» человеку: следующий синк — full (аудит №10, №11). Лишнее событие безвредно — один полный синк.';
