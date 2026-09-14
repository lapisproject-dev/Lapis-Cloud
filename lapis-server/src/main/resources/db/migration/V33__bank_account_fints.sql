-- Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- fachliches Modell: 47-bank-account.kuml.kts.
-- Architekturdoku: docs/architecture/bank-account.adoc "FinTS/HBCI live retrieval (Wave 2)".
--
-- V32__bank_account.sql wird NICHT angefasst -- committet auf master, Checksumme verbraucht; dass
-- der Commit zum Zeitpunkt der Implementierung noch nicht gepusht war, aendert daran nichts
-- (Flyway-Identitaet != Git-Historie). Gleiche Disziplin, die V32 gegenueber V31 dokumentiert.
--
-- audit_log_entry.entity_type wird NICHT verbreitert -- 'BANK_ACCOUNT' hat V32 bereits eingefuehrt.
--
-- Portability: H2 MODE=PostgreSQL. DROP CONSTRAINT IF EXISTS vor jedem ADD, kein partieller Index,
-- kein ON DELETE CASCADE, kein RANDOM_UUID()/gen_random_uuid().

ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS fints_blz                VARCHAR(8)    NULL;
ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS fints_url                VARCHAR(2048) NULL;
ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS fints_user_id_ciphertext VARCHAR(1024) NULL;
ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS fints_pin_ciphertext     VARCHAR(1024) NULL;
ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS fints_status             VARCHAR(16)   NOT NULL DEFAULT 'NOT_CONFIGURED';
ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS fints_activated_by       UUID          NULL;
ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS fints_activated_at       TIMESTAMP     NULL;
ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS fints_pin_set_at         TIMESTAMP     NULL;
ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS fints_last_success_at    TIMESTAMP     NULL;
ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS fints_last_fetch_to      DATE          NULL;
ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS fints_last_error_code    VARCHAR(32)   NULL;

ALTER TABLE bank_account DROP CONSTRAINT IF EXISTS chk_bank_account_fints_status;
ALTER TABLE bank_account ADD CONSTRAINT chk_bank_account_fints_status
    CHECK (fints_status IN ('NOT_CONFIGURED', 'ACTIVE', 'REAUTH_REQUIRED'));

-- Alles-oder-nichts: NOT_CONFIGURED <=> alle vier Zugangsdaten-Spalten NULL. Zusaetzlich
-- fints_activated_by NOT NULL bei jedem Nicht-NOT_CONFIGURED-Status (Stolperfalle S19 im
-- Umsetzungsplan: ohne diese Bedingung koennte ein ACTIVE-Konto ohne actorMemberId entstehen und
-- den Poller beim import()-Aufruf mit einer NPE abstuerzen lassen).
ALTER TABLE bank_account DROP CONSTRAINT IF EXISTS chk_bank_account_fints_credentials_complete;
ALTER TABLE bank_account ADD CONSTRAINT chk_bank_account_fints_credentials_complete
    CHECK (
        (fints_status =  'NOT_CONFIGURED' AND fints_blz IS NULL AND fints_url IS NULL
            AND fints_user_id_ciphertext IS NULL AND fints_pin_ciphertext IS NULL AND fints_activated_by IS NULL)
     OR (fints_status <> 'NOT_CONFIGURED' AND fints_blz IS NOT NULL AND fints_url IS NOT NULL
            AND fints_user_id_ciphertext IS NOT NULL AND fints_pin_ciphertext IS NOT NULL AND fints_activated_by IS NOT NULL)
    );

ALTER TABLE bank_account DROP CONSTRAINT IF EXISTS fk_bank_account_fints_activated_by;
ALTER TABLE bank_account ADD CONSTRAINT fk_bank_account_fints_activated_by
    FOREIGN KEY (fints_activated_by) REFERENCES member (id);

-- Struktureller Spiegel von vat_compliance_acknowledgment (V31) / sepa_compliance_acknowledgment
-- (V7), erweitert um bank_account_id: die Quittung gilt JE KONTO. Append-only, ueberlebt
-- disableFinTs + Reaktivierung als Historie.
CREATE TABLE IF NOT EXISTS bank_account_fints_acknowledgment (
    id                        UUID        NOT NULL PRIMARY KEY,
    bank_account_id           UUID        NOT NULL,
    acknowledged_by_member_id UUID        NOT NULL,
    acknowledged_at           TIMESTAMP   NOT NULL,
    disclaimer_version        VARCHAR(20) NOT NULL,
    disclaimer_sha256         VARCHAR(64) NOT NULL
);

ALTER TABLE bank_account_fints_acknowledgment DROP CONSTRAINT IF EXISTS fk_ba_fints_ack_bank_account_id;
ALTER TABLE bank_account_fints_acknowledgment ADD CONSTRAINT fk_ba_fints_ack_bank_account_id
    FOREIGN KEY (bank_account_id) REFERENCES bank_account (id);

ALTER TABLE bank_account_fints_acknowledgment DROP CONSTRAINT IF EXISTS fk_ba_fints_ack_member_id;
ALTER TABLE bank_account_fints_acknowledgment ADD CONSTRAINT fk_ba_fints_ack_member_id
    FOREIGN KEY (acknowledged_by_member_id) REFERENCES member (id);

CREATE INDEX IF NOT EXISTS idx_ba_fints_ack_account ON bank_account_fints_acknowledgment (bank_account_id, acknowledged_at DESC);
