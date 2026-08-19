-- V1.1.5 "Moderation, DSA-Melde-Mechanismus, DSGVO-Content-Hard-Delete" -- see
-- lapis-server/src/main/kuml/32-social-network.kuml.kts file header for the full fachlich model.
--
-- content_erased_at/content_erasure_note are exactly the two columns V4__social_network_core.sql's
-- own header announced ("those belong to later waves (V1.1.2/V1.1.5)") -- names are load-bearing,
-- pinned by SocialNetworkSchemaDriftTest/SocialNetworkPersonalData's own KDoc.
--
-- Idempotent by construction (IF NOT EXISTS / dual-DROP-then-ADD), same discipline as V2-V5.

ALTER TABLE social_post ADD COLUMN IF NOT EXISTS content_erased_at    TIMESTAMP     NULL;
ALTER TABLE social_post ADD COLUMN IF NOT EXISTS content_erasure_note VARCHAR(2000) NULL;

-- Welle V1.1.5, DSA Art. 16 Meldungen.
CREATE TABLE IF NOT EXISTS social_post_report (
    id                   UUID          NOT NULL PRIMARY KEY,
    post_id              UUID          NOT NULL,
    reported_at          TIMESTAMP     NOT NULL,
    reporter_member_id   UUID          NULL,     -- NULL = anonyme oeffentliche Meldung
    reporter_contact     VARCHAR(320)  NULL,     -- optional, DSA Art. 16 Abs. 2 lit. c / Abs. 4-5
    category             VARCHAR(20)   NOT NULL, -- SocialPostReportCategory
    description          VARCHAR(4000) NOT NULL, -- DSA Art. 16 Abs. 2 lit. a, Pflichtfeld
    good_faith_confirmed BOOLEAN       NOT NULL, -- DSA Art. 16 Abs. 2 lit. d
    status               VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
    decided_by           UUID          NULL,
    decided_at           TIMESTAMP     NULL,
    decision_note        VARCHAR(2000) NULL
);

ALTER TABLE social_post_report DROP CONSTRAINT IF EXISTS chk_social_post_report_category;
ALTER TABLE social_post_report ADD CONSTRAINT chk_social_post_report_category
    CHECK (category IN ('ILLEGAL_CONTENT', 'DEFAMATION', 'COPYRIGHT', 'PERSONAL_DATA', 'HATE_SPEECH', 'SPAM', 'OTHER'));

ALTER TABLE social_post_report DROP CONSTRAINT IF EXISTS chk_social_post_report_status;
ALTER TABLE social_post_report ADD CONSTRAINT chk_social_post_report_status
    CHECK (status IN ('OPEN', 'UNDER_REVIEW', 'ACTION_TAKEN', 'DISMISSED'));

ALTER TABLE social_post_report DROP CONSTRAINT IF EXISTS fk_social_post_report_post;
ALTER TABLE social_post_report ADD CONSTRAINT fk_social_post_report_post
    FOREIGN KEY (post_id) REFERENCES social_post(id);

ALTER TABLE social_post_report DROP CONSTRAINT IF EXISTS fk_social_post_report_reporter;
ALTER TABLE social_post_report ADD CONSTRAINT fk_social_post_report_reporter
    FOREIGN KEY (reporter_member_id) REFERENCES member(id);

ALTER TABLE social_post_report DROP CONSTRAINT IF EXISTS fk_social_post_report_decided_by;
ALTER TABLE social_post_report ADD CONSTRAINT fk_social_post_report_decided_by
    FOREIGN KEY (decided_by) REFERENCES member(id);

CREATE INDEX IF NOT EXISTS idx_social_post_report_post     ON social_post_report (post_id);
CREATE INDEX IF NOT EXISTS idx_social_post_report_status   ON social_post_report (status, reported_at);
CREATE INDEX IF NOT EXISTS idx_social_post_report_reporter ON social_post_report (reporter_member_id);
-- Security-Audit Runde 2 Fund N-5: `idx_social_post_report_status` only serves `listReports`' filtered
-- (`status = ?`) keyset page. The unfiltered "Alle Status" path -- the client screen's own default --
-- had no supporting index and degraded to a full scan + sort per page, undercutting the point of the
-- MAJOR-2 pagination fix for exactly the callers who page deepest. Column order matches the keyset
-- comparison in SocialNetworkService.listReports (`reportedAt DESC, id DESC`).
CREATE INDEX IF NOT EXISTS idx_social_post_report_keyset ON social_post_report (reported_at DESC, id DESC);

-- Welle V1.1.5, post-bezogener DSGVO-Art.-17-Antrag (fuer betroffene Personen OHNE eigenes Konto --
-- der mitglieds-bezogene Pfad bleibt IDsgvoService/erasure_request, siehe SocialNetworkPersonalData
-- KDoc "die strukturelle Grenze").
CREATE TABLE IF NOT EXISTS social_post_erasure (
    id                UUID          NOT NULL PRIMARY KEY,
    post_id           UUID          NOT NULL,
    requested_at      TIMESTAMP     NOT NULL,
    requested_by      UUID          NULL,     -- NULL = externe betroffene Person ohne Konto
    subject_member_id UUID          NULL,     -- gesetzt, wenn die betroffene Person ein Konto hat
    requester_contact VARCHAR(320)  NULL,
    reason            VARCHAR(4000) NOT NULL,
    status            VARCHAR(16)   NOT NULL DEFAULT 'REQUESTED',
    decided_by        UUID          NULL,
    decided_at        TIMESTAMP     NULL,
    decision_note     VARCHAR(2000) NULL,
    executed_at       TIMESTAMP     NULL,
    source_report_id  UUID          NULL      -- wenn aus einer PERSONAL_DATA-Meldung entstanden
);

ALTER TABLE social_post_erasure DROP CONSTRAINT IF EXISTS chk_social_post_erasure_status;
ALTER TABLE social_post_erasure ADD CONSTRAINT chk_social_post_erasure_status
    CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'EXECUTED'));

ALTER TABLE social_post_erasure DROP CONSTRAINT IF EXISTS fk_social_post_erasure_post;
ALTER TABLE social_post_erasure ADD CONSTRAINT fk_social_post_erasure_post
    FOREIGN KEY (post_id) REFERENCES social_post(id);

ALTER TABLE social_post_erasure DROP CONSTRAINT IF EXISTS fk_social_post_erasure_requested_by;
ALTER TABLE social_post_erasure ADD CONSTRAINT fk_social_post_erasure_requested_by
    FOREIGN KEY (requested_by) REFERENCES member(id);

ALTER TABLE social_post_erasure DROP CONSTRAINT IF EXISTS fk_social_post_erasure_subject_member_id;
ALTER TABLE social_post_erasure ADD CONSTRAINT fk_social_post_erasure_subject_member_id
    FOREIGN KEY (subject_member_id) REFERENCES member(id);

ALTER TABLE social_post_erasure DROP CONSTRAINT IF EXISTS fk_social_post_erasure_decided_by;
ALTER TABLE social_post_erasure ADD CONSTRAINT fk_social_post_erasure_decided_by
    FOREIGN KEY (decided_by) REFERENCES member(id);

ALTER TABLE social_post_erasure DROP CONSTRAINT IF EXISTS fk_social_post_erasure_source_report;
ALTER TABLE social_post_erasure ADD CONSTRAINT fk_social_post_erasure_source_report
    FOREIGN KEY (source_report_id) REFERENCES social_post_report(id);

CREATE INDEX IF NOT EXISTS idx_social_post_erasure_post    ON social_post_erasure (post_id);
CREATE INDEX IF NOT EXISTS idx_social_post_erasure_status  ON social_post_erasure (status, requested_at);
CREATE INDEX IF NOT EXISTS idx_social_post_erasure_subject ON social_post_erasure (subject_member_id);
-- Security-Audit Runde 2 Fund N-5, siehe Begruendung bei idx_social_post_report_keyset oben. Spalten-
-- reihenfolge passt zur Keyset-Bedingung in SocialNetworkService.listContentErasures.
CREATE INDEX IF NOT EXISTS idx_social_post_erasure_keyset ON social_post_erasure (requested_at DESC, id DESC);

-- audit_log_entry.entity_type CHECK-Verbreiterung fuer SOCIAL_POST -- dual-DROP-Muster wie
-- V4 (ltr_ledger_entry) und jede spaetere V1__baseline.sql-in-place-Aenderung dieses Repos.
-- Auf einer FRISCHEN DB traegt der Constraint bereits (durch die in-place-Edition von V1) das
-- Literal, der Auto-generierte Name auf einer BEREITS migrierten pdv2-Instanz ist der Rate-Fall,
-- den beide DROPs abdecken -- VERIFY WITH `\d audit_log_entry` ON pdv2 BEFORE DEPLOY.
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS audit_log_entry_entity_type_check;
ALTER TABLE audit_log_entry DROP CONSTRAINT IF EXISTS chk_audit_log_entry_entity_type;
ALTER TABLE audit_log_entry ADD CONSTRAINT chk_audit_log_entry_entity_type
    CHECK (entity_type IN (
        'JOURNAL_ENTRY', 'PARTY_DONATION_VERDICT', 'RESOLUTION', 'BOARD_MEMBERSHIP',
        'CONFERENCE_RECORDING', 'CONFERENCE_STREAM', 'CONFERENCE_STREAM_DESTINATION', 'CONFERENCE_ROOM',
        'SOCIAL_POST'
    ));
