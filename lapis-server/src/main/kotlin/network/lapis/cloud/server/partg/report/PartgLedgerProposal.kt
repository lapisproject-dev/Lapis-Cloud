package network.lapis.cloud.server.partg.report

import network.lapis.cloud.server.partg.PartgDebitCredit
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgLedgerRules
import network.lapis.cloud.shared.domain.PartgPositions
import kotlin.uuid.Uuid

/** One mapped ledger account as the proposal sees it: its type and the position it feeds (`null` = deliberately not in the report). */
internal data class PartgProposalAccount(
    val type: LedgerAccountType,
    val positionCode: String?,
)

/** What the plausibility assistant knows about the bookings of ONE unit (V1.9.289, R4a). Counts and codes only, never an amount. */
internal data class PartgLedgerFacts(
    val mode: PartgBookkeepingMode,
    val bookingCount: Int,
    /** Unmapped accounts with postings in the report year. */
    val unmappedWithActivity: Int,
    /** The proposal per position code (signed cents) in modes A and B; empty in mode C. */
    val proposal: Map<String, Long>,
    /** Codes of the entered values whose source is the ledger. */
    val ledgerSourced: Set<String>,
)

/**
 * V1.9.289 (R4a) -- the proposal of the report values from the bookings. Pure: no database. INCOME and EXPENSE positions come from the flows of
 * the year, ASSET and LIABILITY positions from the balances at the closing date. A position has a proposal iff at least one account is mapped to
 * it; its value keeps the sign ([PartgLedgerRules.signedAmountCents]), a negative value is NOT turned into 0 -- the caller marks it as not
 * transferable. The rules for the targets live in [PartgLedgerRules] (shared with the client).
 */
internal object PartgLedgerProposal {
    /** Position code (LEAF) to its proposed signed amount in cents. */
    fun compute(
        accounts: Map<Uuid, PartgProposalAccount>,
        flows: Map<Uuid, PartgDebitCredit>,
        balances: Map<Uuid, PartgDebitCredit>,
    ): Map<String, Long> {
        val debit = HashMap<String, Long>()
        val credit = HashMap<String, Long>()
        val present = LinkedHashSet<String>()
        accounts.forEach { (accountId, account) ->
            val code = account.positionCode ?: return@forEach
            val position = PartgPositions.find(code) ?: return@forEach
            if (position.kind != PartgKind.LEAF ||
                !PartgLedgerRules.allowedTarget(accountType = account.type, position = position)
            ) {
                return@forEach
            }
            present += code
            val source = if (PartgLedgerRules.isFlow(position.section)) flows[accountId] else balances[accountId]
            if (source != null) {
                debit[code] = Math.addExact(debit[code] ?: 0L, source.debitCents)
                credit[code] = Math.addExact(credit[code] ?: 0L, source.creditCents)
            }
        }
        return PartgPositions.ALL
            .filter { it.code in present }
            .associate { position ->
                position.code to
                    PartgLedgerRules.signedAmountCents(
                        section = position.section,
                        debitCents = debit[position.code] ?: 0L,
                        creditCents = credit[position.code] ?: 0L,
                    )
            }
    }
}
