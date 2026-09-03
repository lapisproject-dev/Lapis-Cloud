-- Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- see 38-crm.kuml.kts for the entity rationale.
-- Idempotent by construction (IF NOT EXISTS), same discipline as V2-V16.
-- V16__embed_anonymous_donation.sql is NOT touched (released, checksum consumed).
-- Deliberately does NOT touch audit_log_entry/its entity_type CHECK -- see 38-crm.kuml.kts file
-- header "Why NOT audit_log_entry".

CREATE TABLE IF NOT EXISTS crm_contact (
    id                       UUID          NOT NULL PRIMARY KEY,
    display_name             VARCHAR(300)  NOT NULL,
    email                    VARCHAR(320)  NULL,
    phone                    VARCHAR(50)   NULL,
    street                   VARCHAR(200)  NULL,
    postal_code              VARCHAR(20)   NULL,
    city                     VARCHAR(200)  NULL,
    country                  VARCHAR(100)  NULL,
    contact_type             VARCHAR(19)   NOT NULL,
    lawful_basis             VARCHAR(19)   NOT NULL,
    consent_source           VARCHAR(200)  NULL,
    consent_given_at         TIMESTAMP     NULL,
    consent_withdrawn_at     TIMESTAMP     NULL,
    external_donor_id        UUID          NULL REFERENCES external_donor(id),
    member_id                UUID          NULL REFERENCES member(id),
    created_at               TIMESTAMP     NOT NULL,
    created_by               UUID          NOT NULL REFERENCES member(id),
    last_interaction_at      TIMESTAMP     NULL,
    retention_review_due_at  TIMESTAMP     NOT NULL,
    archived_at              TIMESTAMP     NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_crm_contact_email ON crm_contact(email);
CREATE UNIQUE INDEX IF NOT EXISTS uq_crm_contact_external_donor ON crm_contact(external_donor_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_crm_contact_member ON crm_contact(member_id);
CREATE INDEX IF NOT EXISTS idx_crm_contact_retention_due ON crm_contact(archived_at, retention_review_due_at);
CREATE INDEX IF NOT EXISTS idx_crm_contact_type ON crm_contact(contact_type);

ALTER TABLE crm_contact DROP CONSTRAINT IF EXISTS chk_crm_contact_type;
ALTER TABLE crm_contact ADD CONSTRAINT chk_crm_contact_type
    CHECK (contact_type IN ('INTERESSENT', 'SYMPATHISANT', 'FOERDERER', 'EHEMALIGES_MITGLIED', 'PRESSE'));

ALTER TABLE crm_contact DROP CONSTRAINT IF EXISTS chk_crm_contact_lawful_basis;
ALTER TABLE crm_contact ADD CONSTRAINT chk_crm_contact_lawful_basis
    CHECK (lawful_basis IN ('CONSENT', 'LEGITIMATE_INTEREST', 'CONTRACT'));

-- Art. 6(1)(a) DSGVO: a CONSENT basis without a documented source/timestamp is not a real consent
-- record. See CrmContactPolicy.validate for the mirrored service-layer pre-check (this CHECK is the
-- backstop, not the only gate).
ALTER TABLE crm_contact DROP CONSTRAINT IF EXISTS chk_crm_contact_consent_fields;
ALTER TABLE crm_contact ADD CONSTRAINT chk_crm_contact_consent_fields
    CHECK (lawful_basis <> 'CONSENT' OR (consent_source IS NOT NULL AND consent_given_at IS NOT NULL));

-- Cannot withdraw a consent that was never recorded as given.
ALTER TABLE crm_contact DROP CONSTRAINT IF EXISTS chk_crm_contact_withdrawal_requires_consent;
ALTER TABLE crm_contact ADD CONSTRAINT chk_crm_contact_withdrawal_requires_consent
    CHECK (consent_withdrawn_at IS NULL OR consent_given_at IS NOT NULL);

CREATE TABLE IF NOT EXISTS crm_interaction (
    id            UUID          NOT NULL PRIMARY KEY,
    contact_id    UUID          NOT NULL REFERENCES crm_contact(id),
    occurred_at   TIMESTAMP     NOT NULL,
    kind          VARCHAR(7)    NOT NULL,
    summary       VARCHAR(4000) NOT NULL,
    recorded_by   UUID          NOT NULL REFERENCES member(id),
    recorded_at   TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_crm_interaction_contact ON crm_interaction(contact_id, occurred_at);

ALTER TABLE crm_interaction DROP CONSTRAINT IF EXISTS chk_crm_interaction_kind;
ALTER TABLE crm_interaction ADD CONSTRAINT chk_crm_interaction_kind
    CHECK (kind IN ('CALL', 'MEETING', 'EMAIL', 'LETTER', 'EVENT', 'NOTE'));

-- dsgvo_audit_log.subject_kind -- see network.lapis.cloud.server.dsgvo.DataSubject KDoc for why one
-- new column, not a second audit table: an audit row now needs to say WHICH kind of subject the
-- (still un-typed, UUID-only) subject_member_id column names, now that a dsgvo_audit_log row can
-- describe either a member or a crm_contact. Carries NO payload -- same doctrine
-- 04-dsgvo.kuml.kts's own file header documents for every other column on this table; see
-- PersonalDataCoverageTest's "dsgvo_audit_log rows never carry payload" test, updated in this wave
-- to include this column in its allowlist.
ALTER TABLE dsgvo_audit_log ADD COLUMN IF NOT EXISTS subject_kind VARCHAR(11) NOT NULL DEFAULT 'MEMBER';

ALTER TABLE dsgvo_audit_log DROP CONSTRAINT IF EXISTS chk_dsgvo_audit_log_subject_kind;
ALTER TABLE dsgvo_audit_log ADD CONSTRAINT chk_dsgvo_audit_log_subject_kind
    CHECK (subject_kind IN ('MEMBER', 'CRM_CONTACT'));

-- subject_member_id can no longer be a hard FK to member(id) alone: it now polymorphically holds
-- EITHER a member id (subject_kind = 'MEMBER') OR a crm_contact id (subject_kind = 'CRM_CONTACT').
-- Dropping this constraint is REQUIRED, not optional -- without it, every CRM_CONTACT-kind audit
-- row insert fails with a referential-integrity violation the instant the contact id is not also,
-- coincidentally, a real member id. See 04-dsgvo.kuml.kts's subjectMemberId attribute comment.
ALTER TABLE dsgvo_audit_log DROP CONSTRAINT IF EXISTS fk_dsgvo_audit_log_subject_member_id;
