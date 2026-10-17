-- Welle V1.9.148 "Eintrittshinweise Begegnungsraum" -- see 70-agreement-texts.kuml.kts. V1..V107 untouched.
-- A dedicated V108 rather than an edit of V107: V107 is on origin/master and may be migrated on staging/forks.
-- Two further kinds ENC_CHURCH (10 chars) and ENC_ASSEM (9 chars); VARCHAR(10) unchanged, only the two CHECKs grow.
-- encounter_consent_acknowledgment unchanged (no kind column on purpose; label uniqueness across ENC kinds is enforced in the service).
ALTER TABLE agreement_text_version DROP CONSTRAINT IF EXISTS chk_agreement_text_version_kind;
ALTER TABLE agreement_text_version ADD CONSTRAINT chk_agreement_text_version_kind
    CHECK (kind IN ('MEMBERSHIP', 'FRIEND', 'REC_ROOM', 'REC_PULPIT', 'FINDER', 'ENC_CHURCH', 'ENC_ASSEM'));

ALTER TABLE agreement_text_draft DROP CONSTRAINT IF EXISTS chk_agreement_text_draft_kind;
ALTER TABLE agreement_text_draft ADD CONSTRAINT chk_agreement_text_draft_kind
    CHECK (kind IN ('MEMBERSHIP', 'FRIEND', 'REC_ROOM', 'REC_PULPIT', 'FINDER', 'ENC_CHURCH', 'ENC_ASSEM'));
