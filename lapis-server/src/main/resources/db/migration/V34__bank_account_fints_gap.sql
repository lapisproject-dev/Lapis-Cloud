-- Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf" -- Review-Fix Runde 3 (MEDIUM, "Watermark-Gap
-- ist unsichtbar und selbstloeschend"): V33__bank_account_fints.sql / V34's own predecessor set
-- `fints_last_error_code` = 'FETCH_WINDOW_GAP' on a fetch that skips part of the watermark gap, but
-- that column is UNCONDITIONALLY overwritten (with `null`, or the NEXT tick's own error code) by
-- every later tick -- see FinTsPoller.handleMt940's own KDoc. A gap detected once therefore becomes
-- unrecoverably invisible again after at most `LAPIS_FINTS_POLL_INTERVAL_SECONDS` (default 6h), and
-- is ALSO structurally hidden while it IS still set, because the accounts screen's LAST_SUCCESS
-- branch wins over the ERROR_NO_SUCCESS branch that renders the error code text at all.
--
-- Fix: a SEPARATE, never-overwritten-by-an-ordinary-success record of the MOST RECENTLY detected
-- gap -- `fints_last_error_code` keeps its existing transient/self-healing behaviour unchanged (a
-- CURRENT-tick signal), these three columns are the durable "this account has, at some point,
-- missed statements" record the finding asked for. Deliberately NOT a full gap history table --
-- one row's worth of "most recent gap" is enough to make the fact findable after the fact, which is
-- the concrete gap this migration closes; a full audit trail of every gap ever detected is future
-- scope if it turns out to be needed.
--
-- V33__bank_account_fints.sql wird NICHT angefasst -- committet, Checksumme verbraucht (gleiche
-- Disziplin, die V33 gegenueber V32 und V32 gegenueber V31 bereits dokumentiert).
--
-- Portability: H2 MODE=PostgreSQL. DROP CONSTRAINT IF EXISTS vor jedem ADD (keiner hier noetig),
-- kein partieller Index, kein ON DELETE CASCADE, kein RANDOM_UUID()/gen_random_uuid().

ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS fints_gap_from        DATE      NULL;
ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS fints_gap_to          DATE      NULL;
ALTER TABLE bank_account ADD COLUMN IF NOT EXISTS fints_gap_detected_at TIMESTAMP NULL;
