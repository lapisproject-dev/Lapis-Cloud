package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.VatRate
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Welle V1.4.13 "USt-Voranmeldung (Nachweishilfe)" -- die EINZIGE Stelle, an der aus einem
 * Bruttobetrag ein Umsatzsteuerbetrag wird.
 *
 * **Brutto ist und bleibt brutto.** `posting.amount` ist der Rechnungsbetrag inkl. USt -- die
 * Semantik aendert sich durch diese Welle NICHT, und sie darf es nicht: jede bestehende POSTED-Zeile
 * und jeder bestehende Bericht (GuV, Bilanz, Vier-Sphaeren, Kassenbuch, Mittelverwendung) rechnet
 * damit, und POSTED-Daten sind unveraenderlich -- eine Semantikaenderung wuerde abgeschlossene
 * Perioden rueckwirkend umdeuten. Die Soll-/Haben-Balance ([JournalEntryBalance]) prueft weiterhin
 * ausschliesslich Bruttobetraege.
 *
 * **Rundung, EINE Richtung, verbindlich**: `net = ROUND_HALF_UP(gross / (1 + p/100), 2)`,
 * `vat = gross - net`. Niemals die USt direkt aus `gross * p/(100+p)` UND das Netto separat runden
 * -- die beiden Ergebnisse koennen um einen Cent auseinanderlaufen. So gilt `net + vat == gross`
 * per Konstruktion, fuer jeden Betrag, immer.
 */
internal object VatCalculator {
    private val HUNDRED = BigDecimal("100")

    /** `0.00` fuer [VatRate.UNCLASSIFIED]/[VatRate.NOT_SUBJECT]/[VatRate.ZERO], sonst
     *  `gross - netOf(gross, rate)`. */
    fun vatAmountOf(
        gross: BigDecimal,
        rate: VatRate,
    ): BigDecimal =
        if (!rate.bearsVat) {
            BigDecimal.ZERO.setScale(2)
        } else {
            gross.setScale(2, RoundingMode.UNNECESSARY) - netOf(gross = gross, rate = rate)
        }

    /** `ROUND_HALF_UP(gross / (1 + percent/100), 2)`; fuer `!rate.bearsVat` == `gross` (scale 2). */
    fun netOf(
        gross: BigDecimal,
        rate: VatRate,
    ): BigDecimal =
        if (!rate.bearsVat) {
            gross.setScale(2, RoundingMode.UNNECESSARY)
        } else {
            gross.divide(BigDecimal.ONE + BigDecimal(rate.percent).divide(HUNDRED), 2, RoundingMode.HALF_UP)
        }
}
