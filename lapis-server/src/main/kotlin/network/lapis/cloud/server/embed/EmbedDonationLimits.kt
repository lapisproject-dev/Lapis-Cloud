package network.lapis.cloud.server.embed

import java.math.BigDecimal

/**
 * Welle V1.4.1b "Öffentliche Website-Integration -- anonymer Spenden-Pfad" -- the amount range the
 * public embed-widget checkout endpoint (`POST /api/embed/v1/donation/checkout`) accepts. Both
 * bounds are inclusive, compared exclusively via [BigDecimal.compareTo] -- never `equals`/`==`
 * (`BigDecimal("500")` and `BigDecimal("500.00")` are `equals`-unequal; see
 * `network.lapis.cloud.server.rpc.PartyDonationComplianceCalculator` KDoc for the same house rule).
 */
internal object EmbedDonationLimits {
    /** Mindestbetrag -- Missbrauchsökonomie (Card Testing läuft mit Cent-/1-Euro-Beträgen), keine Rechtsvorgabe. */
    val MIN_AMOUNT_EUR: BigDecimal = BigDecimal("5.00")

    /**
     * Höchstbetrag des Widgets, inklusiv. BEWUSST als eigene Produktkonstante geführt, nicht als
     * Alias auf `PartyDonationComplianceCalculator.ANONYMOUS_FORWARDING_THRESHOLD_EUR`: jene ist
     * eine Rechtskonstante, die nur für `isPoliticalParty` gilt; diese hier gilt für JEDE
     * Organisation. Der ZAHLENWERT ist absichtlich identisch gewählt -- eine Widget-Spende soll nie
     * die Weiterleitungspflicht an die Bundestagsverwaltung auslösen. [EmbedDonationLimitsTest]
     * hält beide Werte per Assertion aneinander; driftet die Rechtskonstante, bricht der Build hier
     * auf.
     */
    val MAX_AMOUNT_EUR: BigDecimal = BigDecimal("500.00")

    /** `min(MAX_AMOUNT_EUR, pspMax)` -- die betreiberseitige Obergrenze (`LAPIS_PSP_MAX_CHECKOUT_AMOUNT_EUR`) darf nur SENKEN. */
    fun effectiveMaxAmountEur(pspMax: BigDecimal): BigDecimal = if (pspMax.compareTo(MAX_AMOUNT_EUR) < 0) pspMax else MAX_AMOUNT_EUR

    /** `false`, wenn der Betreiber `pspMax` unter [MIN_AMOUNT_EUR] gesetzt hat -- dann ist der Bereich leer. */
    fun rangeIsUsable(pspMax: BigDecimal): Boolean = effectiveMaxAmountEur(pspMax).compareTo(MIN_AMOUNT_EUR) >= 0
}
