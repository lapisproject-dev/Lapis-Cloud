-- Welle V1.9.196 "Vertragstexte als Tabelle mit Detailseite, Markdown fuer neue Fassungen" -- see 70-agreement-texts.kuml.kts. V1..V111 untouched.
-- Purely additive, H2 MODE=PostgreSQL- and Postgres-compatible (IF NOT EXISTS, DROP CONSTRAINT IF EXISTS before every ADD CONSTRAINT).
--
-- A dedicated V112 rather than an edit of V104: V104 has been migrated on running instances since v0.36.0, so changing it would break the Flyway
-- checksum there.
--
-- body_format says how the stored body is to be DISPLAYED: 'plain' (the text exactly as before, shown as text) or 'markdown' (rendered to sanitized
-- HTML by the server). Every row that exists before this migration is 'plain' (the column default); a new draft and a version published from it
-- are 'markdown'. The format is NOT part of the consent hash (SHA-256 over "<version_label>\n<body>" stays unchanged), and it is immutable after
-- publication: the application never UPDATEs agreement_text_version (AgreementTextStoreTripwireTest).

ALTER TABLE agreement_text_version ADD COLUMN IF NOT EXISTS body_format VARCHAR(16) NOT NULL DEFAULT 'plain';
ALTER TABLE agreement_text_version DROP CONSTRAINT IF EXISTS chk_agreement_text_version_format;
ALTER TABLE agreement_text_version ADD CONSTRAINT chk_agreement_text_version_format CHECK (body_format IN ('plain', 'markdown'));

ALTER TABLE agreement_text_draft ADD COLUMN IF NOT EXISTS body_format VARCHAR(16) NOT NULL DEFAULT 'plain';
ALTER TABLE agreement_text_draft DROP CONSTRAINT IF EXISTS chk_agreement_text_draft_format;
ALTER TABLE agreement_text_draft ADD CONSTRAINT chk_agreement_text_draft_format CHECK (body_format IN ('plain', 'markdown'));
