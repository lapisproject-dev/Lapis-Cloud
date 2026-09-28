-- Welle V1.9.7 "SuperMailer" (Teil A HTML-Editor, Teil B Klick-Zählung, Teil C Öffnungs-Zählung).
-- Rein additiv und idempotent (Muster wie V51/V52). Datenmodell-Grundlage für alle drei Teile der
-- Welle -- siehe 03-communication.kuml.kts Datei-Kopf für den vollen Umfang je Spalte/Tabelle.
--
-- WICHTIG (analog V51, gleiche Falle wie bei jeder Vorgänger-Welle seit V11): V1__baseline.sql
-- trägt selbst noch ein INLINE, unbenanntes CHECK auf mailing_delivery_log.delivery_status
-- (H2-generierter Name, z.B. CONSTRAINT_nnn) -- dieses wird von H2 UNABHÄNGIG vom hier benannten
-- Constraint durchgesetzt. Jede frische Test-Datenbank lehnt einen INSERT mit delivery_status =
-- 'PENDING'/'FAILED'/'SKIPPED_NO_ADDRESS' ab, bis auch V1__baseline.sql's eigene Literalliste
-- erweitert ist -- siehe den entsprechenden Kommentar dort. V1__baseline.sql wurde deshalb in
-- gleicher Weise ergänzt -- auf einer bereits migrierten Instanz braucht das einen
-- `flyway repair` (die Checksumme von V1 ändert sich), analog jedem Vorgänger-Fund. Siehe
-- bootstrap/FlywayRepair.kt und deploy/example/README.adoc.

-- Block 1: mailing_message.body_html -- siehe MailingHtmlSanitizer/MailingMailRenderer.
ALTER TABLE mailing_message ADD COLUMN IF NOT EXISTS body_html TEXT NULL;

-- Block 2: mailing_list_subscription -- zwei unabhängige Opt-in-Zeitstempel, Grundlage für Teil B/C.
ALTER TABLE mailing_list_subscription ADD COLUMN IF NOT EXISTS open_tracking_consented_at TIMESTAMP NULL;
ALTER TABLE mailing_list_subscription ADD COLUMN IF NOT EXISTS click_tracking_consented_at TIMESTAMP NULL;

-- Block 3: mailing_delivery_log -- Tracking-Snapshot-Spalten, Grundlage für Teil B/C.
ALTER TABLE mailing_delivery_log ADD COLUMN IF NOT EXISTS tracking_token_hash VARCHAR(64) NULL;
ALTER TABLE mailing_delivery_log ADD COLUMN IF NOT EXISTS open_tracked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mailing_delivery_log ADD COLUMN IF NOT EXISTS click_tracked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mailing_delivery_log ADD COLUMN IF NOT EXISTS first_opened_at TIMESTAMP NULL;
ALTER TABLE mailing_delivery_log ADD COLUMN IF NOT EXISTS open_count INT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX IF NOT EXISTS uq_mailing_delivery_log_tracking_token_hash
    ON mailing_delivery_log (tracking_token_hash);
CREATE INDEX IF NOT EXISTS idx_mailing_delivery_log_message ON mailing_delivery_log (mailing_message_id);

ALTER TABLE mailing_delivery_log DROP CONSTRAINT IF EXISTS chk_mailing_delivery_log_open_count;
ALTER TABLE mailing_delivery_log ADD CONSTRAINT chk_mailing_delivery_log_open_count
    CHECK (open_count BETWEEN 0 AND 1000);

-- Block 4: DeliveryStatus PENDING/FAILED/SKIPPED_NO_ADDRESS -- Dual-DROP-Muster wie
-- V11/V13/.../V51, vollständige Literalliste muss wiederholt werden (H2 hat kein
-- ALTER TYPE ... ADD VALUE). Längstes neues Literal SKIPPED_NO_ADDRESS = 18 Zeichen, passt in die
-- bestehende VARCHAR(20)-Breite.
ALTER TABLE mailing_delivery_log DROP CONSTRAINT IF EXISTS mailing_delivery_log_delivery_status_check;
ALTER TABLE mailing_delivery_log DROP CONSTRAINT IF EXISTS chk_mailing_delivery_log_delivery_status;
ALTER TABLE mailing_delivery_log ADD CONSTRAINT chk_mailing_delivery_log_delivery_status
    CHECK (delivery_status IN (
        'SENT', 'BOUNCED', 'SKIPPED_UNSUBSCRIBED', 'PENDING', 'FAILED', 'SKIPPED_NO_ADDRESS'
    ));

-- Block 5: zwei neue Tabellen, Grundlage für Teil B (Klick-Zählung). Kein CASCADE (Repo-Konvention).
CREATE TABLE IF NOT EXISTS mailing_message_link (
    id                  UUID          NOT NULL PRIMARY KEY,
    link_index          INT           NOT NULL,
    target_url          VARCHAR(2048) NOT NULL,
    mailing_message_id  UUID          NOT NULL,
    CHECK (link_index BETWEEN 0 AND 199)
);

ALTER TABLE mailing_message_link DROP CONSTRAINT IF EXISTS fk_mailing_message_link_message;
ALTER TABLE mailing_message_link ADD CONSTRAINT fk_mailing_message_link_message
    FOREIGN KEY (mailing_message_id) REFERENCES mailing_message(id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_mailing_message_link_message_index
    ON mailing_message_link (mailing_message_id, link_index);

CREATE TABLE IF NOT EXISTS mailing_link_click (
    id                     UUID      NOT NULL PRIMARY KEY,
    link_index             INT       NOT NULL,
    first_clicked_at       TIMESTAMP NOT NULL,
    click_count            INT       NOT NULL,
    mailing_delivery_log_id UUID     NOT NULL,
    CHECK (link_index BETWEEN 0 AND 199),
    CHECK (click_count BETWEEN 1 AND 1000)
);

ALTER TABLE mailing_link_click DROP CONSTRAINT IF EXISTS fk_mailing_link_click_delivery_log;
ALTER TABLE mailing_link_click ADD CONSTRAINT fk_mailing_link_click_delivery_log
    FOREIGN KEY (mailing_delivery_log_id) REFERENCES mailing_delivery_log(id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_mailing_link_click_delivery_index
    ON mailing_link_click (mailing_delivery_log_id, link_index);
