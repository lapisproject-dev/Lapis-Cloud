-- Welle V1.9.10 "Mitgliederzahl-Sichtbarkeit" -- Vorstand kann per Organisationseinstellung
-- entscheiden, ob die aktive Mitgliederzahl auf der oeffentlichen Startseite (/) und der
-- Transparenzseite (/transparenz) erscheint. Rein additiv, idempotent (ADD COLUMN IF NOT
-- EXISTS), gleiche Disziplin wie V35/V56. Default TRUE haelt das Verhalten fuer jede
-- bestehende Organisation unveraendert (reine Opt-out-Erweiterung).
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS show_public_member_count BOOLEAN NOT NULL DEFAULT TRUE;
