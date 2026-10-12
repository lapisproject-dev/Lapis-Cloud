-- Welle V1.9.122 "Reisekostenabrechnung nach PdV-Formular" -- see 45-travel-expense.kuml.kts and
-- 11-organization-settings.kuml.kts. V1..V96 untouched (V96 is already pushed, its checksum is spent).
--
-- Purely additive for DATA: no existing row is rewritten, every pre-V97 row still satisfies every
-- constraint below (V29-era MILEAGE/PER_DIEM/RECEIPTED lines keep their shape, advance_amount
-- defaults to 0). H2 MODE=PostgreSQL and real Postgres compatible: DROP CONSTRAINT IF EXISTS
-- before every ADD CONSTRAINT, no partial index, no ON DELETE CASCADE (house convention).
--
-- DELIBERATELY NOT done (documented decisions):
--   * organization_settings.travel_mileage_rate_per_km is NOT back-filled with 0.30 -- foreign
--     instances must not suddenly offer mileage reimbursement (V1.4.11 "no statutory date in code").
--     The PdV-specific tier values below ARE column defaults (explicitly requested), they are
--     provisional and shown as such in the settings screen.
--   * chk_ter_advance_range is DRAFT- and WITHDRAWN-tolerant (advance <= total only once submitted):
--     a draft's total moves while lines are edited, a hard check would turn an ordinary edit into a 500;
--     a DRAFT withdrawn straight away keeps its (possibly larger-than-total) advance.
--   * "one day line per (report, day)" is enforced by the application under the report row lock,
--     not by a unique index: ACCOMMODATION lines reuse day_date, and a unique index over a
--     nullable column cannot express "only for DAY_ALLOWANCE" portably.

-- ── travel_expense_line ────────────────────────────────────────────────────────────────────
ALTER TABLE travel_expense_line ADD COLUMN IF NOT EXISTS destination        VARCHAR(200)  NULL;
ALTER TABLE travel_expense_line ADD COLUMN IF NOT EXISTS day_date           DATE          NULL;
ALTER TABLE travel_expense_line ADD COLUMN IF NOT EXISTS time_from          TIME          NULL;
ALTER TABLE travel_expense_line ADD COLUMN IF NOT EXISTS time_to            TIME          NULL;
ALTER TABLE travel_expense_line ADD COLUMN IF NOT EXISTS breakfast_provided BOOLEAN       NOT NULL DEFAULT FALSE;
ALTER TABLE travel_expense_line ADD COLUMN IF NOT EXISTS lunch_provided     BOOLEAN       NOT NULL DEFAULT FALSE;
ALTER TABLE travel_expense_line ADD COLUMN IF NOT EXISTS dinner_provided    BOOLEAN       NOT NULL DEFAULT FALSE;
ALTER TABLE travel_expense_line ADD COLUMN IF NOT EXISTS passenger_count    INT           NULL;
ALTER TABLE travel_expense_line ADD COLUMN IF NOT EXISTS breakfast_included BOOLEAN       NULL;
ALTER TABLE travel_expense_line ADD COLUMN IF NOT EXISTS computed_minutes   INT           NULL;
ALTER TABLE travel_expense_line ADD COLUMN IF NOT EXISTS base_amount        NUMERIC(12,2) NULL;
ALTER TABLE travel_expense_line ADD COLUMN IF NOT EXISTS deduction_amount   NUMERIC(12,2) NOT NULL DEFAULT 0;

-- Both constraints reference `kind`; drop them before widening the column (PUBLIC_TRANSPORT = 16).
ALTER TABLE travel_expense_line DROP CONSTRAINT IF EXISTS chk_tel_kind;
ALTER TABLE travel_expense_line DROP CONSTRAINT IF EXISTS chk_tel_kind_shape;
ALTER TABLE travel_expense_line ALTER COLUMN kind TYPE VARCHAR(16);

ALTER TABLE travel_expense_line ADD CONSTRAINT chk_tel_kind
    CHECK (kind IN ('MILEAGE', 'PER_DIEM', 'RECEIPTED', 'DAY_ALLOWANCE', 'PUBLIC_TRANSPORT',
                    'ACCOMMODATION', 'HOSPITALITY', 'OTHER'));

-- Payload exclusivity. The three V29 branches are unchanged apart from "the new day/passenger/
-- breakfast fields stay empty" (MILEAGE may carry passenger_count).
ALTER TABLE travel_expense_line ADD CONSTRAINT chk_tel_kind_shape
    CHECK ((kind = 'MILEAGE' AND kilometers IS NOT NULL AND rate_snapshot IS NOT NULL AND days IS NULL
                AND day_date IS NULL AND breakfast_included IS NULL)
        OR (kind = 'PER_DIEM' AND days IS NOT NULL AND rate_snapshot IS NOT NULL AND kilometers IS NULL
                AND day_date IS NULL AND passenger_count IS NULL AND breakfast_included IS NULL)
        OR (kind = 'RECEIPTED' AND kilometers IS NULL AND days IS NULL AND rate_snapshot IS NULL
                AND day_date IS NULL AND passenger_count IS NULL AND breakfast_included IS NULL)
        OR (kind = 'DAY_ALLOWANCE' AND day_date IS NOT NULL AND kilometers IS NULL AND days IS NULL
                AND rate_snapshot IS NULL AND passenger_count IS NULL AND breakfast_included IS NULL
                AND computed_minutes IS NOT NULL AND base_amount IS NOT NULL)
        OR (kind = 'ACCOMMODATION' AND day_date IS NOT NULL AND days IS NOT NULL
                AND breakfast_included IS NOT NULL AND kilometers IS NULL AND rate_snapshot IS NULL
                AND passenger_count IS NULL)
        OR (kind IN ('PUBLIC_TRANSPORT', 'HOSPITALITY', 'OTHER') AND kilometers IS NULL AND days IS NULL
                AND rate_snapshot IS NULL AND day_date IS NULL AND passenger_count IS NULL
                AND breakfast_included IS NULL));

-- JournalEntryBalance needs strictly positive postings: a day line may be 0 (below the first tier
-- or fully deducted) but is then never booked (the bridge filters 0 lines).
ALTER TABLE travel_expense_line DROP CONSTRAINT IF EXISTS chk_tel_amount_positive;
ALTER TABLE travel_expense_line ADD CONSTRAINT chk_tel_amount_positive
    CHECK ((kind = 'DAY_ALLOWANCE' AND amount >= 0) OR amount > 0);

ALTER TABLE travel_expense_line DROP CONSTRAINT IF EXISTS chk_tel_passenger_range;
ALTER TABLE travel_expense_line ADD CONSTRAINT chk_tel_passenger_range
    CHECK (passenger_count IS NULL OR (passenger_count >= 0 AND passenger_count <= 8));

ALTER TABLE travel_expense_line DROP CONSTRAINT IF EXISTS chk_tel_deduction_nonneg;
ALTER TABLE travel_expense_line ADD CONSTRAINT chk_tel_deduction_nonneg
    CHECK (deduction_amount >= 0);

-- Times only on day lines; where both are given, to >= from.
ALTER TABLE travel_expense_line DROP CONSTRAINT IF EXISTS chk_tel_day_times;
ALTER TABLE travel_expense_line ADD CONSTRAINT chk_tel_day_times
    CHECK (((time_from IS NULL AND time_to IS NULL) OR kind = 'DAY_ALLOWANCE')
       AND (time_from IS NULL OR time_to IS NULL OR time_to >= time_from));

ALTER TABLE travel_expense_line DROP CONSTRAINT IF EXISTS chk_tel_meals_only_day;
ALTER TABLE travel_expense_line ADD CONSTRAINT chk_tel_meals_only_day
    CHECK (kind = 'DAY_ALLOWANCE'
        OR (breakfast_provided = FALSE AND lunch_provided = FALSE AND dinner_provided = FALSE));

CREATE INDEX IF NOT EXISTS idx_tel_report_day ON travel_expense_line (report_id, day_date);

-- ── travel_expense_report ──────────────────────────────────────────────────────────────────
ALTER TABLE travel_expense_report ADD COLUMN IF NOT EXISTS advance_amount NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE travel_expense_report ADD COLUMN IF NOT EXISTS payout_choice VARCHAR(15) NULL;
ALTER TABLE travel_expense_report ADD COLUMN IF NOT EXISTS membership_number_snapshot VARCHAR(16) NULL;
ALTER TABLE travel_expense_report ADD COLUMN IF NOT EXISTS rates_snapshot VARCHAR(2000) NULL;

ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS chk_ter_payout_choice;
ALTER TABLE travel_expense_report ADD CONSTRAINT chk_ter_payout_choice
    CHECK (payout_choice IS NULL OR payout_choice IN ('PAYOUT', 'DONATION_WAIVER'));

ALTER TABLE travel_expense_report DROP CONSTRAINT IF EXISTS chk_ter_advance_range;
ALTER TABLE travel_expense_report ADD CONSTRAINT chk_ter_advance_range
    CHECK (advance_amount >= 0 AND (status IN ('DRAFT', 'WITHDRAWN') OR advance_amount <= total_amount));

-- NOT added: "submitted needs payout_choice" -- REQUESTED reports from before V97 have none; the
-- application enforces the choice at submitReport.

-- ── organization_settings ──────────────────────────────────────────────────────────────────
-- PdV-template defaults (provisional, PdV-specific -- NOT the statutory 14/28 EUR).
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_tier1_hours INT NOT NULL DEFAULT 8;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_tier2_hours INT NOT NULL DEFAULT 14;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_tier3_hours INT NOT NULL DEFAULT 24;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_tier1_cents INT NOT NULL DEFAULT 600;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_tier2_cents INT NOT NULL DEFAULT 1200;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_tier3_cents INT NOT NULL DEFAULT 2800;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_breakfast_pct INT NOT NULL DEFAULT 20;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_lunch_pct INT NOT NULL DEFAULT 40;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_dinner_pct INT NOT NULL DEFAULT 40;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_passenger_cents_per_km INT NOT NULL DEFAULT 2;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_contact_name VARCHAR(120) NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_contact_email VARCHAR(254) NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_contact_phone VARCHAR(40) NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_contact_hint VARCHAR(1000) NULL;
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_advance_account_id UUID NULL
    REFERENCES ledger_account (id);
ALTER TABLE organization_settings ADD COLUMN IF NOT EXISTS travel_donation_waiver_account_id UUID NULL
    REFERENCES ledger_account (id);

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_os_travel_tiers;
ALTER TABLE organization_settings ADD CONSTRAINT chk_os_travel_tiers
    CHECK (travel_tier1_hours >= 1 AND travel_tier1_hours < travel_tier2_hours
       AND travel_tier2_hours < travel_tier3_hours AND travel_tier3_hours <= 24);

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_os_travel_tier_cents;
ALTER TABLE organization_settings ADD CONSTRAINT chk_os_travel_tier_cents
    CHECK (travel_tier1_cents >= 0 AND travel_tier1_cents <= travel_tier2_cents
       AND travel_tier2_cents <= travel_tier3_cents AND travel_tier3_cents <= 10000);

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_os_travel_pct;
ALTER TABLE organization_settings ADD CONSTRAINT chk_os_travel_pct
    CHECK (travel_breakfast_pct BETWEEN 0 AND 100 AND travel_lunch_pct BETWEEN 0 AND 100
       AND travel_dinner_pct BETWEEN 0 AND 100);

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_os_travel_passenger;
ALTER TABLE organization_settings ADD CONSTRAINT chk_os_travel_passenger
    CHECK (travel_passenger_cents_per_km BETWEEN 0 AND 20);

ALTER TABLE organization_settings DROP CONSTRAINT IF EXISTS chk_os_travel_mileage_max;
ALTER TABLE organization_settings ADD CONSTRAINT chk_os_travel_mileage_max
    CHECK (travel_mileage_rate_per_km IS NULL OR travel_mileage_rate_per_km <= 1.00);
