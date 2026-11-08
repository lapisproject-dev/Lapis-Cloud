package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/** V1.9.290 (R5): which kind of intra-party relationship a transfer pair records. */
@Serializable
enum class PartgTransferKind { SUBSIDY, BALANCE }

/** V1.9.290 (R5): whether the two sides of a transfer pair agree. */
@Serializable
enum class PartgMatchState { MATCH, DIFFERS, COUNTERPART_MISSING }

/**
 * V1.9.290 "Rechenschaftsbericht (PartG), R5" -- the ONE rule set of the consolidation, shared by client and server.
 *
 * A pair is the yearly sum of ONE relationship between two units. SUBSIDY: the payer's A03 ("Zuschuesse an Gliederungen") against the payee's
 * E10 ("Zuschuesse von Gliederungen"). BALANCE: the payer's BP05 (claim on subdivisions) against the payee's SP04 (liability). Only the part
 * both sides state is eliminated (the minimum); a difference stays visible and is never booked away.
 */
object PartgConsolidationRules {
    const val MAX_TRANSFERS_PER_YEAR = 1000
    const val MAX_COMBINATIONS_PER_YEAR = 500
    const val MIN_ROWS = 2
    const val MAX_ROWS = 20
    const val NOTE_MAX = 200

    /** P17 is raised only when the difference is STRICTLY above this many cents. */
    const val P17_TOLERANCE_CENTS = 100L

    /** The most candidates the server sends; the full number travels separately. */
    const val MAX_CANDIDATES = 500

    /** Same bound as the report values (`partg_report_value`). */
    const val MAX_AMOUNT_CENTS = 99_999_999_999_999L

    /** The position of the PAYER side of a pair of this kind. */
    fun payerPosition(kind: PartgTransferKind): String =
        when (kind) {
            PartgTransferKind.SUBSIDY -> "A03"
            PartgTransferKind.BALANCE -> "BP05"
        }

    /** The position of the PAYEE side of a pair of this kind. */
    fun payeePosition(kind: PartgTransferKind): String =
        when (kind) {
            PartgTransferKind.SUBSIDY -> "E10"
            PartgTransferKind.BALANCE -> "SP04"
        }

    /** The amount that is eliminated: the minimum of both sides, but only when BOTH are stated; else `null` (nothing is eliminated). */
    fun eliminable(
        payer: Long?,
        payee: Long?,
    ): Long? = if (payer != null && payee != null) minOf(payer, payee) else null

    fun matchState(
        payer: Long?,
        payee: Long?,
    ): PartgMatchState =
        when {
            payer == null || payee == null -> PartgMatchState.COUNTERPART_MISSING
            payer == payee -> PartgMatchState.MATCH
            else -> PartgMatchState.DIFFERS
        }

    /** `null` = valid; else a stable error code. */
    fun validateAmounts(
        payer: Long?,
        payee: Long?,
    ): String? =
        when {
            payer == null && payee == null -> "AMOUNT_MISSING"
            (payer != null && (payer < 1L || payer > MAX_AMOUNT_CENTS)) ||
                (payee != null && (payee < 1L || payee > MAX_AMOUNT_CENTS)) -> "AMOUNT_RANGE"
            else -> null
        }

    /** Trimmed note; empty = `null`; `null` is returned as the second value of the pair only when [note] is too long or has control characters. */
    fun normalizeNote(note: String?): Result<String?> {
        val trimmed = note?.trim().orEmpty()
        if (trimmed.isEmpty()) return Result.success(null)
        if (trimmed.length > NOTE_MAX) return Result.failure(IllegalArgumentException("NOTE_TOO_LONG"))
        if (trimmed.any { it.isISOControl() }) return Result.failure(IllegalArgumentException("NOTE_CONTROL"))
        return Result.success(trimmed)
    }
}
