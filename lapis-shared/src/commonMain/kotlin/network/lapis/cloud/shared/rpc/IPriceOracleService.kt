package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.DonationConversionInput
import network.lapis.cloud.shared.domain.OraclePriceStatusDto
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import network.lapis.cloud.shared.domain.PriceOracleConfigInput
import network.lapis.cloud.shared.domain.PriceOracleConversionDto

/**
 * V0.6.5 Price-Oracle fuer die Anker-Bindung -- see `19-price-oracle.kuml.kts` file header for the
 * full fachlich model. Makes the anchor-asset peg load-bearing by adding the first real
 * money -> LTR conversion boundary this codebase has ever had ([convertDonationToLtr]).
 *
 * **Scope-cut (halt-queue)**: the concept document's persistent halt-queue with deferred/
 * retroactive-vs-forward pricing is NOT built. HALT means [convertDonationToLtr] REJECTS the
 * request (throws, mints nothing) instead of queueing it for later re-pricing -- fully satisfying
 * "conversions must halt rather than silently use a stale/unreliable price" without a
 * queueing/retro-booking mechanism. `PriceStatus.DEFERRED` is reserved-but-unused for a later wave.
 *
 * **Anchor coverage (V0.6.6)**: all three `AnchorAsset` literals have real, wired price sources --
 * see `network.lapis.cloud.server.economy.oracle.PriceOracleSource` KDoc. [updateOracleConfig]
 * still rejects an anchor switch this DEPLOYMENT cannot actually serve: it compares how many
 * sources are configured for the requested anchor (key-gated for GOLD_XAU, see
 * `network.lapis.cloud.server.economy.oracle.OracleSourceConfig`) against
 * `network.lapis.cloud.shared.domain.AnchorPolicy.quorumFloor` and throws a `ConflictException`
 * naming the missing `LAPIS_ORACLE_*` env vars if it falls short -- "fail clearly, not silently"
 * at config time rather than a mystery HALT at the first donation.
 *
 * **FIAT scope-cut**: [network.lapis.cloud.shared.domain.AnchorAsset.FIAT]'s unit is code-fixed to
 * exactly one EUR this wave -- no `anchor_fiat_currency` column exists, so a FIAT-anchored
 * deployment can only be pegged to EUR (a later wave could add an arbitrary fiat anchor currency).
 *
 * **Scope-cut (payment intake)**: [convertDonationToLtr] is an operator-triggered booking of an
 * already-received donation (same tier as [ILtrLedgerService.mintLtr]), not a PSP-webhook intake
 * -- no automatic payment-gateway integration exists or is planned this wave.
 */
@RpcService
interface IPriceOracleService {
    /** Role: TREASURER/BOARD/ADMIN. Reads the single seeded oracle-policy row. */
    suspend fun getOracleConfig(): PriceOracleConfigDto

    /**
     * Role: ADMIN only (same tier as [IOrganizationSettingsService.updateOrganizationSettings]).
     * Replaces every field wholesale. Rejects `minQuorum` below the requested anchor's
     * `AnchorPolicy.quorumFloor`, an anchor whose deployment has fewer configured sources than that
     * floor (see interface KDoc "Anchor coverage"), a `minQuorum` exceeding the configured source
     * count, a `cacheTtlSeconds` below the anchor's `AnchorPolicy.refreshIntervalSeconds`, an
     * invalid `donationCurrency`, a non-positive `cacheTtlSeconds`/`anchorUnitsPerLtr`, an
     * out-of-range `outlierThresholdBps`, or a `maxSpreadBps` below `outlierThresholdBps`.
     */
    suspend fun updateOracleConfig(input: PriceOracleConfigInput): PriceOracleConfigDto

    /**
     * Role: TREASURER/BOARD/ADMIN. Diagnostic read of the oracle's CURRENT price quote (or halt
     * status) -- lets an operator check oracle health WITHOUT minting anything. Never writes.
     */
    suspend fun previewCurrentPrice(): OraclePriceStatusDto

    /**
     * Role: TREASURER/BOARD/ADMIN. The load-bearing conversion path: fetches a current oracle
     * quote for the active anchor, and if (and only if) it is NOT halted, MINTs the computed LTR
     * amount into [DonationConversionInput.memberId]'s ledger (a real `MINT` `ltr_ledger_entry`
     * row) and writes a permanent `price_oracle_conversion` provenance row in the SAME
     * transaction. If the oracle quote is halted, THROWS -- no ledger row, no provenance row, no
     * partial state -- see interface KDoc "Scope-cut (halt-queue)".
     */
    suspend fun convertDonationToLtr(input: DonationConversionInput): PriceOracleConversionDto
}
