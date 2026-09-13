-- Welle V1.4.13 "USt-Voranmeldung (Nachweishilfe)" -- siehe 10-accounting.kuml.kts (posting.vat_rate/
-- vat_amount, vat_compliance_acknowledgment) und 11-organization-settings.kuml.kts (vat_enabled,
-- is_kleinunternehmer) fuer das vollstaendige fachliche Modell, docs/architecture/vat-return.adoc
-- fuer die Brutto-Semantik, die Rundungsregel und die bewusst NICHT gebaute USt-Splitbuchung.
--
-- V30__volunteer_allowance.sql wird NICHT angefasst (gepusht, Checksum verbraucht) -- gleiche
-- Disziplin, die V30 selbst gegenueber V29 dokumentiert.
--
-- Portability: H2 in MODE=PostgreSQL (gesamte Testsuite, DatabaseConfig.kt). DROP CONSTRAINT IF
-- EXISTS vor jedem ADD CONSTRAINT, kein partieller Index, kein ON DELETE CASCADE (Hauskonvention).

-- ---------------------------------------------------------------------------
-- posting.vat_rate / posting.vat_amount
-- vat_rate VARCHAR(12): laengstes VatRate-Literal ist 'UNCLASSIFIED' (12 Zeichen) -- exakt die
-- Groesse, die der kUML-Generator fuer diesen Enum auto-sized (vgl. sphere -> VARCHAR(34)).
-- DEFAULT 'UNCLASSIFIED' migriert JEDE Bestandszeile auf "nie klassifiziert" -- ausdruecklich NICHT
-- auf NOT_SUBJECT, das waere eine steuerliche Behauptung ueber Altdaten (Jobs-Review).
-- vat_amount DECIMAL(15,2): identische Praezision wie posting.amount (V1__baseline.sql).
-- ---------------------------------------------------------------------------
ALTER TABLE posting ADD COLUMN IF NOT EXISTS vat_rate   VARCHAR(12)   NOT NULL DEFAULT 'UNCLASSIFIED';
ALTER TABLE posting ADD COLUMN IF NOT EXISTS vat_amount DECIMAL(15,2) NOT NULL DEFAULT 0.00;

ALTER TABLE posting DROP CONSTRAINT IF EXISTS chk_posting_vat_rate;
ALTER TABLE posting ADD CONSTRAINT chk_posting_vat_rate
    CHECK (vat_rate IN ('UNCLASSIFIED', 'NOT_SUBJECT', 'ZERO', 'REDUCED', 'STANDARD'));

ALTER TABLE posting DROP CONSTRAINT IF EXISTS chk_posting_vat_amount_non_negative;
ALTER TABLE posting ADD CONSTRAINT chk_posting_vat_amount_non_negative
    CHECK (vat_amount >= 0);

-- vat_amount MUSS 0 sein, wo kein steuerbarer Satz gesetzt ist -- strukturelle Absicherung der
-- Berechnungsregel gegen jeden kuenftigen Schreibpfad, nicht nur gegen den heutigen.
ALTER TABLE posting DROP CONSTRAINT IF EXISTS chk_posting_vat_amount_zero_when_untaxed;
ALTER TABLE posting ADD CONSTRAINT chk_posting_vat_amount_zero_when_untaxed
    CHECK (vat_rate IN ('REDUCED', 'STANDARD') OR vat_amount = 0);

-- ---------------------------------------------------------------------------
-- organization_settings.vat_enabled  (READ-ONLY-Tier: settable NUR via IVatService.enableVat/
-- disableVat, NIEMALS via updateOrganizationSettings -- exakt der dunning_enabled-Tier)
-- organization_settings.is_kleinunternehmer (gewoehnliches ADMIN-Feld, wirksam nur bei vat_enabled)
-- Beide DEFAULT FALSE => der Zustand nach dieser Migration ist fuer jeden Bestandsnutzer nicht von
-- V1.4.12 unterscheidbar (Raskin-Entscheidung: kein neues UI, keine juristische Behauptung).
-- ---------------------------------------------------------------------------
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS vat_enabled          BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS is_kleinunternehmer  BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------------
-- vat_compliance_acknowledgment -- exakter struktureller Spiegel von
-- dunning_compliance_acknowledgment (V9__dunning.sql) / sepa_compliance_acknowledgment (V7).
-- Append-only: Nachweis, WER WELCHE VatComplianceDisclaimer-Version quittiert hat, niemals ein
-- blosses Boolean-Umschalten.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vat_compliance_acknowledgment (
    id                        UUID        NOT NULL PRIMARY KEY,
    acknowledged_by_member_id UUID        NOT NULL,
    acknowledged_at           TIMESTAMP   NOT NULL,
    disclaimer_version        VARCHAR(20) NOT NULL,
    disclaimer_sha256         VARCHAR(64) NOT NULL
);

ALTER TABLE vat_compliance_acknowledgment DROP CONSTRAINT IF EXISTS fk_vat_compliance_acknowledgment_member_id;
ALTER TABLE vat_compliance_acknowledgment ADD CONSTRAINT fk_vat_compliance_acknowledgment_member_id
    FOREIGN KEY (acknowledged_by_member_id) REFERENCES member (id);

-- Security Round 2 (HARDENING): the "exact structural mirror" claim above was incomplete -- V7
-- (sepa_compliance_acknowledgment) indexes acknowledged_at, this table did not. VatService
-- .loadVatSettingsDto does an `ORDER BY acknowledged_at DESC LIMIT 1` over the WHOLE table on
-- EVERY getVatSettings/getVatReturnPreview-adjacent call (unbounded growth: enableVat is neither
-- idempotent nor rate-limited, see VatService.enableVat KDoc -- every re-toggle appends a row).
-- VatPersonalData.exportMember/eraseMember additionally filter on acknowledged_by_member_id twice
-- -- indexed too, same as the FK column always deserves an explicit index (Postgres does not
-- create one automatically for a foreign key).
CREATE INDEX IF NOT EXISTS idx_vat_compliance_ack_acknowledged_at ON vat_compliance_acknowledgment (acknowledged_at);
CREATE INDEX IF NOT EXISTS idx_vat_compliance_ack_member_id ON vat_compliance_acknowledgment (acknowledged_by_member_id);

-- audit_log_entry.entity_type-CHECK-Verbreiterung: ORGANIZATION_SETTINGS existiert bereits, es wird
-- KEIN neuer entity_type gebraucht -- enableVat/disableVat protokollieren gegen
-- ORGANIZATION_SETTINGS, exakt wie enableDunning es tut. Daher hier bewusst KEINE Aenderung an
-- chk_audit_log_entry_entity_type (Dual-DROP-Muster nicht noetig).
