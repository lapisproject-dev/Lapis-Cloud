package network.lapis.cloud.server.economy.oracle

import network.lapis.cloud.shared.domain.AnchorAsset
import java.math.BigDecimal

/**
 * A code-fixed, deliberately WIDE sanity band per anchor, in donation-currency units per one anchor
 * unit. Not a price prediction -- a structural-error detector. Its real target is the classic
 * base/quote INVERSION bug in a metals/FX API (a gold source returning `0.00046` "ounces per EUR"
 * instead of `2160` "EUR per ounce"), which the median/outlier/spread machinery in
 * [PriceOracleOrchestrator] cannot catch when two sources are inverted the same way. An
 * out-of-band response is DROPPED (and counted as "did not respond" for the quorum), never
 * auto-inverted -- silently "fixing" a number that mints real LTR is not something this codebase
 * does. Applied centrally in [PriceOracleOrchestrator.currentQuote] immediately after the fan-out,
 * so a future source implementation cannot forget it.
 *
 * **[AnchorAsset.FIAT]'s band is deliberately much tighter than the other two anchors**
 * (Security-Audit-Runde 1 / S4, narrowed from the original `0.1..10`): [AnchorPolicy.quorumFloor] for
 * FIAT is 1 (a deliberate, already-approved earlier design decision -- the ECB reference feed is the
 * only authoritative free source, and a single source structurally has no spread to check), so this
 * band is FIAT's ONLY numeric defense once its one source is compromised or serves bad data -- there
 * is no median-of-multiple-sources safety net possible at n=1, unlike BTC/GOLD_XAU. A real EUR/USD
 * rate has not left roughly `0.5..2.0` in modern floating-rate history, so `0.5..2.0` is still
 * generously wide for legitimate rate movement while closing the ~11.6x over-mint the old `0.1..10`
 * band permitted for the FIAT+USD donation-currency combination specifically (a manipulated/buggy ECB
 * rate of `0.1` against a real ~1.16 EUR/USD rate would have passed the old band, reported `LIVE`,
 * and minted roughly 11.6x too much LTR per donation -- pure-EUR donations are unaffected, since the
 * EUR leg is a hardcoded `1` with no HTTP call at all).
 */
internal fun plausibilityBand(anchor: AnchorAsset): ClosedRange<BigDecimal> =
    when (anchor) {
        AnchorAsset.BITCOIN_BTC -> BigDecimal("1000")..BigDecimal("10000000")
        AnchorAsset.GOLD_XAU -> BigDecimal("100")..BigDecimal("100000")
        AnchorAsset.FIAT -> BigDecimal("0.5")..BigDecimal("2.0")
    }

/**
 * A code-fixed, deliberately WIDE sanity band per anchor for
 * [network.lapis.cloud.shared.domain.PriceOracleConfigDto.anchorUnitsPerLtr] itself -- Review Round
 * 1 / MAJOR-2. Unlike [plausibilityBand] (which sanity-checks a live SOURCE quote every fan-out),
 * this checks the ADMIN-configured PEG at `updateOracleConfig` time
 * (`PriceOracleService.validateConfigInput`), once, on save.
 *
 * **The bug this closes**: before this wave the anchor was locked to [AnchorAsset.BITCOIN_BTC], so
 * `anchorUnitsPerLtr`'s correct order of magnitude was structurally fixed (seeded at `0.000001`,
 * i.e. roughly 100 satoshi per LTR). Once the anchor became switchable, nothing checked that the peg
 * VALUE an ADMIN configures alongside a NEW anchor is even the right order of magnitude for that
 * anchor -- e.g. switching `anchorAsset` from BITCOIN_BTC to FIAT while leaving `anchorUnitsPerLtr`
 * at the old BTC-scale `0.000001` silently mints roughly 50,000x too much LTR per donation (a
 * EUR-anchored FIAT quote's `anchorPrice` is ~1.0, so `ltrMinted = donationAmount / 0.000001`
 * instead of the intended `donationAmount / ~1`).
 *
 * **How the bounds were chosen** (see `PriceOracleServiceTest`/`PriceOracleOrchestratorTest` for the
 * concrete peg values already in use as fixtures, all comfortably inside their anchor's band below):
 * the intent for every anchor is that one LTR represents a modest, human-meaningful value roughly on
 * the order of one donation-currency unit (see the FIAT design intent in
 * [network.lapis.cloud.shared.domain.PriceOracleConfigDto] KDoc). Each band is centered loosely
 * around that intent but kept WIDE (multiple orders of magnitude either side) precisely so it never
 * rejects a legitimate policy choice -- it exists only to catch a peg left over from a DIFFERENT
 * anchor, not to second-guess an ADMIN's actual valuation of LTR:
 * - [AnchorAsset.BITCOIN_BTC]: `1e-8..1e-2` BTC/LTR -- the seeded `0.000001` sits comfortably inside;
 *   the upper bound (`0.01` BTC/LTR) is already implausible for a currency meant to be a small
 *   fraction of one BTC, and is far below the `1.0` a leftover FIAT-scale peg would produce.
 * - [AnchorAsset.GOLD_XAU]: `1e-5..1e-1` troy oz/LTR -- a BTC-scale leftover peg (`1e-6`) falls just
 *   BELOW this band (too little gold to be a plausible LTR peg) and is rejected; the `0.01` fixture
 *   used throughout the test suite sits well inside.
 * - [AnchorAsset.FIAT]: `0.01..1000` currency-units/LTR -- a BTC-scale leftover peg (`1e-6`) falls far
 *   below this band and is rejected; the `5` used in the FIAT integration test fixture sits inside.
 *
 * These bounds are intentionally code-fixed rather than ADMIN-tunable (unlike
 * [PriceOracleConfigDto.anchorUnitsPerLtr] itself) -- the same reasoning [AnchorPolicy] KDoc gives
 * for its own code-fixed values: an operator who could loosen the very guard meant to catch their own
 * fat-fingered peg gains nothing but a false sense of safety. A fully ADMIN-overridable-but-defaulted
 * band (the [AnchorPolicy] pattern) was considered and rejected here as over-scoped for a fix round --
 * this is a hard reject with an actionable message, not a tunable policy.
 */
internal fun plausiblePegBand(anchor: AnchorAsset): ClosedRange<BigDecimal> =
    when (anchor) {
        AnchorAsset.BITCOIN_BTC -> BigDecimal("0.00000001")..BigDecimal("0.01")
        AnchorAsset.GOLD_XAU -> BigDecimal("0.00001")..BigDecimal("0.1")
        AnchorAsset.FIAT -> BigDecimal("0.01")..BigDecimal("1000")
    }
