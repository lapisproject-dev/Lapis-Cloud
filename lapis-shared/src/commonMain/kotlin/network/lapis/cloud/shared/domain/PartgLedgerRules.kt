package network.lapis.cloud.shared.domain

/**
 * V1.9.289 (R4a) -- the ONE set of rules that links the bookings to the report positions, shared by client and server (a parity test pins
 * both sides; the server re-checks every value, the client only pre-selects).
 */
object PartgLedgerRules {
    /** A proposed or stored amount never exceeds `chk_partg_value_amount` (99,999,999,999,999 cents). */
    const val MAX_CENTS: Long = 99_999_999_999_999L

    /** The report section a ledger account of [type] feeds; `null` for EQUITY (an equity account may only be excluded). */
    fun sectionOf(type: LedgerAccountType): PartgSection? =
        when (type) {
            LedgerAccountType.INCOME -> PartgSection.INCOME
            LedgerAccountType.EXPENSE -> PartgSection.EXPENSE
            LedgerAccountType.ASSET -> PartgSection.ASSET
            LedgerAccountType.LIABILITY -> PartgSection.LIABILITY
            LedgerAccountType.EQUITY -> null
        }

    /**
     * `true` iff an account of [accountType] may be mapped to [position]: the position must be a LEAF (a SUM is never a target) of the matching
     * section. EQUITY has no target at all (it may only be "deliberately not in the report").
     */
    fun allowedTarget(
        accountType: LedgerAccountType,
        position: PartgPositions.Position,
    ): Boolean = position.kind == PartgKind.LEAF && sectionOf(accountType) == position.section

    /** [allowedTarget] for a code; an unknown code is never allowed. */
    fun allowedTarget(
        accountType: LedgerAccountType,
        positionCode: String,
    ): Boolean = PartgPositions.find(positionCode)?.let { allowedTarget(accountType = accountType, position = it) } ?: false

    /**
     * The signed amount of one position from the debit and credit sums of its accounts, in cents: INCOME and LIABILITY are credit-normal
     * (credit minus debit), EXPENSE and ASSET debit-normal (debit minus credit). Negative results are kept (they are never silently turned
     * into 0; the proposal marks them as not transferable).
     */
    fun signedAmountCents(
        section: PartgSection,
        debitCents: Long,
        creditCents: Long,
    ): Long =
        when (section) {
            PartgSection.INCOME, PartgSection.LIABILITY -> creditCents - debitCents
            PartgSection.EXPENSE, PartgSection.ASSET -> debitCents - creditCents
        }

    /** INCOME and EXPENSE are flows over the year; ASSET and LIABILITY are balances at the closing date (31 December). */
    fun isFlow(section: PartgSection): Boolean = section == PartgSection.INCOME || section == PartgSection.EXPENSE

    /** A proposal exists for the unit only in the modes A and B and only for STATE/FEDERAL units (a SUB unit is always C). */
    fun proposalAvailable(
        level: PartgUnitLevel,
        mode: PartgBookkeepingMode,
    ): Boolean = level != PartgUnitLevel.SUBORDINATE && mode != PartgBookkeepingMode.C

    /** Only the federal level and a state unit have a mode that can be A or B. */
    fun modeAllowed(
        level: PartgUnitLevel,
        mode: PartgBookkeepingMode,
    ): Boolean = level != PartgUnitLevel.SUBORDINATE || mode == PartgBookkeepingMode.C

    /**
     * Whether a row of the proposal is pre-selected in the dialog: only when nothing is entered or the entered value itself came from the
     * bookings (`LEDGER`). A value typed in or imported (`MANUAL`, `IMPORT`) is NEVER pre-selected, and a negative proposal never is.
     */
    fun preselect(
        entered: Long?,
        source: PartgValueSource?,
        proposed: Long,
    ): Boolean {
        if (proposed < 0L) return false
        if (entered == null) return true
        return source == PartgValueSource.LEDGER
    }

    /** A proposed amount can be written: not negative and within the column range. */
    fun transferable(proposed: Long): Boolean = proposed in 0L..MAX_CENTS
}
