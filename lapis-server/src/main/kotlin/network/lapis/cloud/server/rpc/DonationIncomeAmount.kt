package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingSide
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.times
import java.math.BigDecimal

/**
 * Welle V1.4.4.1 "Beitragshistorie" -- the vorzeichenrichtige Ertrags-Betragsspalte for any
 * donor-attributed booking, extracted out of
 * [network.lapis.cloud.server.routes.PublicTransparencyReader.loadTopDonors] (V1.3.0) so the
 * public donor ranking and the new internal per-member financial history never derive the sign
 * rule twice. The sign rule itself lives in exactly ONE place still:
 * [GeneralLedgerCalculator.normalBalanceSideOf] -- this object only bakes that ONE compile-time-
 * fixed [PostingSide] value (for [LedgerAccountType.INCOME]) into a SQL `CASE`.
 */
internal object DonationIncomeAmount {
    val normalSide: PostingSide = GeneralLedgerCalculator.normalBalanceSideOf(LedgerAccountType.INCOME)

    /**
     * `CASE WHEN posting.side = normalSide THEN posting.amount ELSE -posting.amount END`.
     * Deliberately no explicit return type -- the expression must stay `.sum()`-able
     * (`ExpressionWithColumnType`); a manually narrowed `Expression<BigDecimal>` return type would
     * break [PublicTransparencyReader.loadTopDonors]'s own `.sum()` call on the result.
     */
    fun signedAmount() =
        Case()
            .When(PostingTable.side eq normalSide, PostingTable.amount)
            .Else(PostingTable.amount times BigDecimal(-1))
}
