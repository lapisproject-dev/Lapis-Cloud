-- Welle V1.4.4.5 "Mitgliederlebenszyklus: Sterbefall-Workflow" -- siehe 00-foundation.kuml.kts.
-- V23__member_family.sql wird NICHT angefasst (deployed, Checksum verbraucht) -- gleiche Disziplin,
-- die V23 selbst gegenueber V22 dokumentiert.
--
-- V1__baseline.sql wird hier BEWUSST NICHT in-place mitgepflegt -- anders als V10, das dieselbe
-- Tabelle erweitert hat. V10 musste den INLINE, UNBENANNTEN chk_member_status im CREATE TABLE
-- aufweiten: den erreicht kein ALTER, er existiert nur in einer frischen Datenbank. Hier gibt es
-- keinen solchen Fall -- eine nullable Spalte plus ein BENANNTER CHECK erreichen frische und
-- bereits migrierte Datenbanken identisch ueber genau diese Datei. Damit entfaellt fuer pdv2/ELB
-- das sonst noetige `flyway repair`.
--
-- Fachlich: § 38 BGB -- die Mitgliedschaft erlischt AUTOMATISCH mit dem Tod. Diese Spalte ist rein
-- deklaratorisch/dokumentierend, nicht konstitutiv. DSGVO ErwG 27: Verstorbene sind keine
-- betroffenen Personen -- der Wert wird deshalb von FoundationPersonalData.eraseMember bewusst
-- NICHT genullt (siehe dort).

ALTER TABLE member ADD COLUMN IF NOT EXISTS date_of_death DATE NULL;

-- Ein Sterbedatum ohne Status DECEASED waere ein widerspruechlicher Datensatz. `status` ist NOT
-- NULL, der CHECK kann also nie zu NULL (= "erfuellt", siehe die Drei-wertige-Logik-Falle in
-- V23__member_family.sql) auswerten -- er ist entweder TRUE oder FALSE.
ALTER TABLE member DROP CONSTRAINT IF EXISTS chk_member_date_of_death_requires_status;
ALTER TABLE member ADD CONSTRAINT chk_member_date_of_death_requires_status
    CHECK (date_of_death IS NULL OR status = 'DECEASED');
