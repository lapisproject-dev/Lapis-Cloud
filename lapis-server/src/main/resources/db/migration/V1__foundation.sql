-- Foundation stub (see CLAUDE.md "Vorab-Befund" and docs/architecture/domain-model.adoc):
-- V0.1.2-V0.1.4 (Mitglieder-Stammdaten, Beitritts-/Austrittsworkflow, Auth/Session) do not
-- exist yet. member/account are modelled here only as granularly as V0.1.5 needs them as
-- foreign keys. Portable across PostgreSQL (prod) and H2 (test) — no vendor-specific
-- functions (no gen_random_uuid()/uuid-ossp): the application always generates UUIDs itself
-- before INSERT.

CREATE TABLE member (
    id           UUID PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    email        VARCHAR(320) NOT NULL UNIQUE,
    status       VARCHAR(20)  NOT NULL,
    joined_at    DATE         NOT NULL
);

CREATE TABLE account (
    id            UUID PRIMARY KEY,
    member_id     UUID         NOT NULL UNIQUE REFERENCES member (id),
    password_hash VARCHAR(200),
    oidc_subject  VARCHAR(200),
    role          VARCHAR(20)  NOT NULL
);
