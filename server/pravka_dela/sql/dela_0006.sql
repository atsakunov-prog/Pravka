-- Карточки клиента и проекта (09.10.2026, разбор владельца по вебу): описание сверху и правится
-- руками, статус клиента «поддержание отношений», папка и файлы (договор, счета, NDA), а у счёта —
-- кому и как его отправили (он встаёт в хронологию рядом со звонками и встречами).
--
-- * crm.deals.description — что за проект, словами; видят все, кто видит сделку. «Как я вижу»
--   (my_view) остаётся только владельцу, тексты из Notion (next_step, ball, log) веб больше не
--   показывает — они справка до мая 2026 и живут только для Claude.
-- * crm.projects.status — статус клиента помимо его сделок: relations — «поддержание отношений»:
--   уже не лид, но и не сделка (работали, держим связь). Пусто — статус по сделкам.
--   Описание клиента — давнее crm.projects.note.
-- * folder_url и files у сделки и у клиента: ссылка на папку и ссылки на документы
--   [{kind, title, url, at}], kind — contract (договор), invoice (счёт), nda, act (акт), other.
--   Сами файлы лежат там, где лежали (диск, почта), здесь — ссылки.
-- * crm.payments.sent_to, sent_via — кому и как ушёл счёт (почтой, в Telegram, через ЭДО).

SELECT set_config('dela.actor', 'svc:migrate', true), set_config('dela.via', 'system', true);

ALTER TABLE crm.deals
    ADD COLUMN description text,
    ADD COLUMN folder_url  text CHECK (folder_url ~* '^https?://'),
    ADD COLUMN files       jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(files) = 'array');
COMMENT ON COLUMN crm.deals.description IS 'Что за проект — словами, правит человек или Claude. Видят все, кто видит сделку (в отличие от «как я вижу»).';
COMMENT ON COLUMN crm.deals.folder_url IS 'Папка проекта (диск, облако) — ссылкой.';
COMMENT ON COLUMN crm.deals.files IS 'Документы ссылками: [{kind, title, url, at}], kind — contract, invoice, nda, act, other.';

ALTER TABLE crm.projects
    ADD COLUMN status     text CHECK (status IN ('relations')),
    ADD COLUMN folder_url text CHECK (folder_url ~* '^https?://'),
    ADD COLUMN files      jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(files) = 'array');
COMMENT ON COLUMN crm.projects.status IS 'Статус клиента помимо сделок: relations — поддержание отношений (не лид и не сделка). Пусто — по сделкам.';
COMMENT ON COLUMN crm.projects.folder_url IS 'Папка клиента (диск, облако) — ссылкой.';
COMMENT ON COLUMN crm.projects.files IS 'Документы клиента ссылками: [{kind, title, url, at}] — как у сделки.';

ALTER TABLE crm.payments
    ADD COLUMN sent_to  text,
    ADD COLUMN sent_via text;
COMMENT ON COLUMN crm.payments.sent_to IS 'Кому отправили счёт (человек клиента, бухгалтерия).';
COMMENT ON COLUMN crm.payments.sent_via IS 'Как отправили счёт: почта, Telegram, ЭДО, курьер — словами.';

-- Вид сделки дописывает новые колонки в хвост: CREATE OR REPLACE не умеет иначе.
CREATE OR REPLACE VIEW crm.v_deals WITH (security_invoker = true) AS
SELECT d.id, d.project_id, d.name, d.stage, d.outcome, d.lost_reason, d.closed_on, d.deal_type,
       d.lead_person_id, d.team_ids, d.person_ids, d.source_person_id,
       CASE WHEN crm.money_ok(crm.me()) THEN d.fee_kind END AS fee_kind,
       CASE WHEN crm.money_ok(crm.me()) THEN d.fee_kop END AS fee_kop,
       CASE WHEN crm.money_ok(crm.me()) THEN d.retainer_kop END AS retainer_kop,
       CASE WHEN crm.money_ok(crm.me()) THEN d.success_pct END AS success_pct,
       d.probability, d.expected_on, d.deadline, d.wheel, d.ball, d.next_step,
       CASE WHEN crm.me() IN (crm.owner_id(), 'system') THEN d.my_view END AS my_view,
       d.ideas, d.log, d.notion_id, d.created_at, d.updated_at, d.rev, d.seq,
       d.description, d.folder_url, d.files
FROM crm.deals d;
