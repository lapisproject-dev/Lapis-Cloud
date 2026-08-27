-- V1.3.0 "Öffentliche Transparenz-Startseite" -- see
-- lapis-server/src/main/kuml/35-public-ranking-consent.kuml.kts file header for the full fachlich
-- model this table implements.
--
-- Idempotent by construction (CREATE TABLE IF NOT EXISTS / dual-DROP-then-ADD / CREATE INDEX IF
-- NOT EXISTS), same discipline as V2-V11.
--
-- GENUINELY NEW TABLE -- same V4 precedent (social_post): this migration does NOT touch
-- V1__baseline.sql. A fresh DB (every test run, via Flyway running V1..V12 in order) and an
-- already-migrated pdv2/ELB instance (via V12 alone) both reach the identical end state, so this
-- wave needs NO "run flyway repair before next deploy" operator warning, unlike V11.
--
-- No partial UNIQUE index for "exactly one current row per (member_id, ranking_kind)" -- H2 in
-- PostgreSQL-compatibility mode (this codebase's test path) does not support partial indexes. That
-- invariant is enforced in code under a member-row lock, see PublicRankingConsentStore KDoc.

CREATE TABLE IF NOT EXISTS public_ranking_consent_event (
    id              UUID        NOT NULL PRIMARY KEY,
    member_id       UUID        NOT NULL,
    ranking_kind    VARCHAR(12) NOT NULL,
    event_type      VARCHAR(7)  NOT NULL,
    occurred_at     TIMESTAMP   NOT NULL,
    superseded_at   TIMESTAMP   NULL,
    consent_version VARCHAR(50) NOT NULL,
    consent_sha256  VARCHAR(64) NOT NULL
);

ALTER TABLE public_ranking_consent_event DROP CONSTRAINT IF EXISTS fk_prce_member_id;
ALTER TABLE public_ranking_consent_event ADD CONSTRAINT fk_prce_member_id
    FOREIGN KEY (member_id) REFERENCES member(id);

ALTER TABLE public_ranking_consent_event DROP CONSTRAINT IF EXISTS chk_prce_ranking_kind;
ALTER TABLE public_ranking_consent_event ADD CONSTRAINT chk_prce_ranking_kind
    CHECK (ranking_kind IN ('LTR_HOLDINGS', 'DONATIONS'));

ALTER TABLE public_ranking_consent_event DROP CONSTRAINT IF EXISTS chk_prce_event_type;
ALTER TABLE public_ranking_consent_event ADD CONSTRAINT chk_prce_event_type
    CHECK (event_type IN ('GRANTED', 'REVOKED'));

CREATE INDEX IF NOT EXISTS idx_prce_current ON public_ranking_consent_event (ranking_kind, member_id, superseded_at);
CREATE INDEX IF NOT EXISTS idx_prce_member  ON public_ranking_consent_event (member_id);
