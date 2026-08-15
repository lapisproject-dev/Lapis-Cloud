package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.KassenbuchLineDto
import network.lapis.cloud.shared.domain.PostingSide
import java.math.BigDecimal

/**
 * Pure Kassenbuch (cash book, V0.3.5) derivation, extracted so it is unit-testable without a
 * database -- same "pure logic extracted to a sibling file" idiom as
 * [GeneralLedgerCalculator]/[JournalEntryBalance]/[UseOfFundsCalculator].
 *
 * A Kassenbuch is a specialized Hauptbuch (general ledger) view scoped to one
 * [network.lapis.cloud.shared.domain.LedgerAccountDto.isCashRegister] account -- not a parallel
 * bookkeeping system. Since `isCashRegister` may only be `true` on an `ASSET` account (enforced at
 * [AccountingService.requireCashRegisterOnlyOnAsset]), the running-balance math is identical to
 * [GeneralLedgerCalculator.runningBalances] with the `ASSET` (debit-normal) normal-balance side --
 * this object delegates to it rather than re-implementing the sign convention, to avoid a second,
 * drifting source of truth. The only Kassenbuch-specific transformation is re-labelling each line's
 * single signed DEBIT/CREDIT amount into an Einnahme (`amountIn`)/Ausgabe (`amountOut`) pair -- a
 * real Kassenbuch's columns -- and assigning a sequential, gapless [KassenbuchLineDto.kassenbuchNumber].
 */
internal object KassenbuchCalculator {
    /** One chronologically-ordered posting line against a cash-register account, before derivation. */
    data class KassenbuchSourceLine(
        val journalEntryId: String,
        val entryDate: LocalDate,
        val description: String,
        val voucherReference: String?,
        val side: PostingSide,
        val amount: BigDecimal,
    )

    /**
     * Derives [lines] (assumed already sorted chronologically by the caller -- this function does
     * not re-sort, same contract as [GeneralLedgerCalculator.runningBalances]) into
     * [KassenbuchLineDto]s: [PostingSide.DEBIT] -> [KassenbuchLineDto.amountIn], [PostingSide.CREDIT]
     * -> [KassenbuchLineDto.amountOut], the other of the pair always [BigDecimal.ZERO].
     * [KassenbuchLineDto.kassenbuchNumber] is 1-based and gapless, assigned strictly in input order.
     */
    fun kassenbuch(
        lines: List<KassenbuchSourceLine>,
        opening: BigDecimal = BigDecimal.ZERO,
    ): List<KassenbuchLineDto> {
        val ledgerLines =
            lines.map { line ->
                GeneralLedgerCalculator.LedgerLine(
                    journalEntryId = line.journalEntryId,
                    entryDate = line.entryDate,
                    description = line.description,
                    side = line.side,
                    amount = line.amount,
                )
            }
        val runningBalances =
            GeneralLedgerCalculator.runningBalances(
                lines = ledgerLines,
                normalBalanceSide = PostingSide.DEBIT,
                opening = opening,
            )

        return lines.zip(runningBalances).mapIndexed { index, (source, ledgerLine) ->
            KassenbuchLineDto(
                kassenbuchNumber = index + 1,
                journalEntryId = source.journalEntryId,
                entryDate = source.entryDate,
                description = source.description,
                voucherReference = source.voucherReference,
                amountIn = if (source.side == PostingSide.DEBIT) source.amount else BigDecimal.ZERO,
                amountOut = if (source.side == PostingSide.CREDIT) source.amount else BigDecimal.ZERO,
                runningBalance = ledgerLine.runningBalance,
            )
        }
    }
}
