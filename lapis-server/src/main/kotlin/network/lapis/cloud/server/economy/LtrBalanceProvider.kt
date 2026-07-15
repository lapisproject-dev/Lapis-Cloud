package network.lapis.cloud.server.economy

import network.lapis.cloud.server.db.generated.LtrBalanceTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Seam for the full LTR ledger V0.6 (LTR-Wirtschaft/Auktion/Price-Oracle) will introduce.
 * [GovernanceService][network.lapis.cloud.server.rpc.GovernanceService] takes this as a
 * constructor parameter (default [PlaceholderLtrBalanceProvider]) so
 * `Application.module`'s `registerService(IGovernanceService::class) { call -> GovernanceService(call) }`
 * stays unchanged, and V0.6 only has to swap in a ledger-backed implementation at that one call
 * site — no changes anywhere else. Deliberately read-only: `GovernanceService.castVoteBallot`
 * validates `stake <= freeBalance(member)`, but never debits through this interface — there is no
 * writable ledger yet in this wave. Implementations must run inside the caller's already-open
 * `transaction {}`, consistent with the rest of this codebase's "simple-transaction" style.
 */
interface LtrBalanceProvider {
    /** The member's current free LTR balance, or [BigDecimal.ZERO] if the member has none. */
    fun freeBalance(memberId: Uuid): BigDecimal
}

/**
 * Reads [LtrBalanceTable] directly — a snapshot balance, not a full ledger (see [LtrBalanceTable]
 * KDoc). A member without a row is treated as having a zero balance, not an error: most members
 * will not have earned any LTR yet when this wave ships.
 */
class PlaceholderLtrBalanceProvider : LtrBalanceProvider {
    override fun freeBalance(memberId: Uuid): BigDecimal =
        LtrBalanceTable
            .selectAll()
            .where { LtrBalanceTable.memberId eq memberId }
            .singleOrNull()
            ?.get(LtrBalanceTable.balanceLtr)
            ?: BigDecimal.ZERO
}
