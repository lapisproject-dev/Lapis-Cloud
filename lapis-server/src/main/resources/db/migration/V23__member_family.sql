-- Welle V1.4.4.4 "Mitgliederlebenszyklus: Familienmitgliedschaften" -- see 42-member-family.kuml.kts.
-- V22__datev_export.sql wird NICHT angefasst (deployed, Checksum verbraucht) -- gleiche Disziplin,
-- die V22 selbst gegenueber V21 dokumentiert.

CREATE TABLE IF NOT EXISTS member_family (
    id          UUID         NOT NULL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    created_by  UUID         NOT NULL,
    created_at  TIMESTAMP    NOT NULL
);
ALTER TABLE member_family DROP CONSTRAINT IF EXISTS fk_member_family_created_by;
ALTER TABLE member_family ADD CONSTRAINT fk_member_family_created_by
    FOREIGN KEY (created_by) REFERENCES member(id);

-- payer_family_id (Schatten-Spalte): H2s MODE=PostgreSQL -- der Modus, gegen den die GESAMTE
-- Testsuite laeuft (DatabaseConfig.kt) -- lehnt einen partiellen Unique-Index
-- (`CREATE UNIQUE INDEX ... WHERE`) ab und lehnt den Generated-Column-Workaround ebenfalls ab
-- (Postgres verlangt STORED, H2 lehnt STORED ab) -- beides empirisch verifiziert in
-- V8__sepa_mandates.sql/V9__dunning.sql. Diese applikationsgepflegte Schatten-Spalte ist die
-- portable Alternative, exakt wie event_registration.active_participant_key (V18__events.sql):
-- NULL fuer DEPENDENT, = family_id fuer PAYER. Ein gewoehnlicher UNIQUE-Index darauf erzwingt
-- "hoechstens ein Zahler je Familie" -- mehrere NULLs sind unter einem Unique-Index in H2 UND
-- Postgres erlaubt (dieselbe Eigenschaft, auf die uq_crm_contact_email bereits baut). Der CHECK
-- darunter macht die Spalte faelschungssicher: sie KANN nicht von role/family_id abweichen.
-- Geschrieben AUSSCHLIESSLICH von MemberFamilyService.
CREATE TABLE IF NOT EXISTS member_family_link (
    id               UUID        NOT NULL PRIMARY KEY,
    family_id        UUID        NOT NULL,
    member_id        UUID        NOT NULL,
    role             VARCHAR(9)  NOT NULL,
    payer_family_id  UUID        NULL,
    linked_at        TIMESTAMP   NOT NULL,
    linked_by        UUID        NOT NULL
);

ALTER TABLE member_family_link DROP CONSTRAINT IF EXISTS fk_member_family_link_family;
ALTER TABLE member_family_link ADD CONSTRAINT fk_member_family_link_family
    FOREIGN KEY (family_id) REFERENCES member_family(id);
ALTER TABLE member_family_link DROP CONSTRAINT IF EXISTS fk_member_family_link_member;
ALTER TABLE member_family_link ADD CONSTRAINT fk_member_family_link_member
    FOREIGN KEY (member_id) REFERENCES member(id);
ALTER TABLE member_family_link DROP CONSTRAINT IF EXISTS fk_member_family_link_linked_by;
ALTER TABLE member_family_link ADD CONSTRAINT fk_member_family_link_linked_by
    FOREIGN KEY (linked_by) REFERENCES member(id);

ALTER TABLE member_family_link DROP CONSTRAINT IF EXISTS chk_member_family_link_role;
ALTER TABLE member_family_link ADD CONSTRAINT chk_member_family_link_role
    CHECK (role IN ('PAYER','DEPENDENT'));

-- Drei-wertige Logik-Falle (gefunden waehrend der Implementierung dieser Welle): ein simples
-- "payer_family_id = family_id" allein waere fuer role='PAYER' UND payer_family_id=NULL nicht
-- FALSE, sondern NULL (unbekannt) -- und ein CHECK, der zu NULL auswertet, gilt in SQL als
-- ERFUELLT, nicht als verletzt. Ohne das explizite "payer_family_id IS NOT NULL" haette ein
-- PAYER-Datensatz mit NULL-Schattenwert die Pruefung unbemerkt bestanden.
ALTER TABLE member_family_link DROP CONSTRAINT IF EXISTS chk_member_family_link_payer_shadow;
ALTER TABLE member_family_link ADD CONSTRAINT chk_member_family_link_payer_shadow
    CHECK ((role = 'PAYER'     AND payer_family_id IS NOT NULL AND payer_family_id = family_id)
        OR (role = 'DEPENDENT' AND payer_family_id IS NULL));

CREATE UNIQUE INDEX IF NOT EXISTS uq_member_family_link_member ON member_family_link (member_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_member_family_link_payer  ON member_family_link (payer_family_id);
CREATE INDEX        IF NOT EXISTS idx_member_family_link_family ON member_family_link (family_id);
