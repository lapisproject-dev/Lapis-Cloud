package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.13 "USt-Voranmeldung (Nachweishilfe)" -- der Umsatzsteuersatz EINER [PostingDto]-Zeile.
 * See `10-accounting.kuml.kts` file header addendum and `docs/architecture/vat-return.adoc` for the
 * full rationale.
 *
 * [UNCLASSIFIED] ist NICHT "0 %": es heisst "nie klassifiziert" -- jede Bestandsbuchung (vor dieser
 * Welle) und jede automatisierte Buchung der fuenf Posting-Bridges traegt dieses Literal (Default,
 * siehe [PostingInput.vatRate] KDoc). [NOT_SUBJECT] (nicht steuerbar, ausserhalb der Umsatzsteuer --
 * z. B. ideeller Bereich/Vermoegensverwaltung) und [ZERO] (steuerbar, aber 0 %) sind bewusst zwei
 * verschiedene Literale: sie landen in einer echten UStVA in verschiedenen Zeilen. Literalreihenfolge
 * load-bearing (Schema-Drift-Test, siehe `network.lapis.cloud.server.db.AccountingSchemaDriftTest`).
 *
 * [percent] ist eine Doku-/Rechen-Hilfe (server-seitig von [network.lapis.cloud.server.rpc
 * .VatCalculator] verwendet); kotlinx.serialization kodiert weiterhin den Literalnamen, nie diesen
 * Wert.
 */
@Serializable
enum class VatRate(
    val percent: Int,
) {
    UNCLASSIFIED(0),
    NOT_SUBJECT(0),
    ZERO(0),
    REDUCED(7),
    STANDARD(19),
    ;

    /** `true` nur fuer [REDUCED]/[STANDARD] -- die einzigen Saetze, die je einen USt-Betrag > 0
     *  ergeben, und genau die Menge, die einen lexoffice-/sevDesk-Export blockiert (siehe
     *  [AccountingExportBlockerKind.VAT_BEARING_ENTRY]). */
    val bearsVat: Boolean get() = percent > 0

    /** `true` fuer [ZERO]/[REDUCED]/[STANDARD] -- steuerbare Umsaetze (0 % eingeschlossen), also
     *  jede Zeile, die in einer echten UStVA ueberhaupt auftaucht. [UNCLASSIFIED]/[NOT_SUBJECT]
     *  sind beide `false`. */
    val isTaxable: Boolean get() = this == ZERO || bearsVat
}

/**
 * Client-seitiger VORSCHLAG fuer den USt-Satz einer neu erfassten Buchungszeile, abgeleitet aus der
 * [GemeinnuetzigkeitSphere] der Zeile.
 *
 * **Der Server ruft diese Funktion NIEMALS auf.** Nicht als Validierung, nicht als Fallback, nicht
 * als Plausibilisierung. Die 7-%-Berechtigung eines Zweckbetriebs haengt am Wettbewerbsvorbehalt
 * (§12 Abs.2 Nr.8a UStG) -- eine Ermessensfrage, die diese Software nicht entscheiden kann. Jede der
 * 20 (Sphaere x VatRate)-Kombinationen ist serverseitig zulaessig -- siehe
 * `network.lapis.cloud.server.rpc.AccountingService` KDoc ("kein Guard gegen (Sphaere x Satz)-
 * Kombinationen") und die Tests `VatRateSphereIndependenceTest`/
 * `SuggestedVatRateNotCalledByServerTest` (letzterer belegt per Quelltext-Scan, dass kein
 * `lapis-server`-Quelltext diese Funktion referenziert).
 */
fun suggestedVatRate(sphere: GemeinnuetzigkeitSphere): VatRate =
    when (sphere) {
        GemeinnuetzigkeitSphere.IDEELLER_BEREICH -> VatRate.NOT_SUBJECT
        GemeinnuetzigkeitSphere.VERMOEGENSVERWALTUNG -> VatRate.NOT_SUBJECT
        GemeinnuetzigkeitSphere.ZWECKBETRIEB -> VatRate.REDUCED
        GemeinnuetzigkeitSphere.WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB -> VatRate.STANDARD
    }

/** Warum [VatReturnPreviewDto.applicable] `false` ist. */
@Serializable
enum class VatNotApplicableReason { VAT_DISABLED, KLEINUNTERNEHMER }

/**
 * §18 UStG-Voranmeldungsperiodizitaet, REIN INFORMATIV -- kein Scheduler, keine Frist, keine
 * Erinnerung. [UNKNOWN] bedeutet: Lapis Cloud deckt das Vorjahr nicht vollstaendig ab, eine
 * Einstufung ist aus den vorhandenen Daten nicht ableitbar.
 */
@Serializable
enum class VatFilingPeriodicity { MONTHLY, QUARTERLY, UNKNOWN }

/**
 * Eine Satz-Zeile innerhalb einer [VatReturnPreviewDto] (Umsatzsteuer- ODER Vorsteuer-Seite).
 * [netTotal]/[vatTotal] sind Summen der GESPEICHERTEN [PostingDto.vatAmount]-Snapshots, nie neu
 * hergeleitet -- [netTotal] = [grossTotal] − [vatTotal] gilt damit per Konstruktion, nicht durch
 * eine zweite Berechnung.
 */
@Serializable
data class VatRateLineDto(
    val rate: VatRate,
    val grossTotal: Decimal,
    val netTotal: Decimal,
    val vatTotal: Decimal,
    val postingCount: Int,
)

/**
 * Vorschau/Nachweishilfe fuer eine USt-Voranmeldung ueber `[from, to]` -- siehe
 * `network.lapis.cloud.shared.rpc.IAccountingService.getVatReturnPreview` KDoc fuer die volle
 * "keine ELSTER-Uebermittlung"-Abgrenzung. [balance] > 0 bedeutet Zahllast, < 0 bedeutet
 * Erstattungsanspruch, == 0 bedeutet keine Zahllast. [unclassifiedPostingCount]/
 * [unclassifiedGrossTotal] zaehlen [VatRate.UNCLASSIFIED]-Buchungen ueber Einnahmen UND Ausgaben
 * zusammen -- das ist eine Datenqualitaetsangabe, keine Steuerposition. [taxableGrossTotal]
 * schliesst [VatRate.NOT_SUBJECT]/[VatRate.UNCLASSIFIED] aus, [VatRate.ZERO] aber ein.
 * [priorYearVatBalance] ist `null`, wenn Lapis Cloud das Vorjahr nicht vollstaendig abdeckt.
 * [exemptionOnRequestPossible] ist eine reine Information ("moeglich, auf Antrag"), niemals ein
 * automatischer Freistellungs-Schalter.
 */
@Serializable
data class VatReturnPreviewDto(
    val from: LocalDate,
    val to: LocalDate,
    val applicable: Boolean,
    val notApplicableReason: VatNotApplicableReason? = null,
    val outputVatLines: List<VatRateLineDto> = emptyList(),
    val inputVatLines: List<VatRateLineDto> = emptyList(),
    val totalOutputVat: Decimal,
    val totalInputVat: Decimal,
    val balance: Decimal,
    val unclassifiedPostingCount: Int = 0,
    val unclassifiedGrossTotal: Decimal,
    val taxableGrossTotal: Decimal,
    val filingPeriodicity: VatFilingPeriodicity = VatFilingPeriodicity.UNKNOWN,
    val exemptionOnRequestPossible: Boolean = false,
    val priorYearVatBalance: Decimal? = null,
    val disclaimerVersion: String,
)

/**
 * The gate/status state for the whole USt-Voranmeldung feature -- returned by
 * [network.lapis.cloud.shared.rpc.IVatService.getVatSettings]/`enableVat`/`disableVat`. Mirrors
 * [DunningSettingsDto]'s own shape.
 */
@Serializable
data class VatSettingsDto(
    val vatEnabled: Boolean,
    val isKleinunternehmer: Boolean,
    val lastDisclaimerVersion: String? = null,
    val lastAcknowledgedAt: LocalDateTime? = null,
    /** `true`, wenn [lastDisclaimerVersion] der AKTUELLEN `VatComplianceDisclaimer.VERSION`
     *  entspricht -- eine spaetere Versionserhoehung macht das `false`, ohne [vatEnabled] zu
     *  aendern (gleiche Semantik wie `DunningSettingsDto`). */
    val disclaimerCurrent: Boolean = false,
)

/** Version/Text/Hash-Triade fuer `network.lapis.cloud.server.rpc.VatComplianceDisclaimer` -- gleiche
 * Form wie [DunningComplianceDisclaimerDto]. */
@Serializable
data class VatComplianceDisclaimerDto(
    val version: String,
    val text: String,
    val sha256: String,
)

/** Quittungs-Payload fuer [network.lapis.cloud.shared.rpc.IVatService.enableVat] -- der Client
 * schickt Version/Hash des GELESENEN Disclaimers unveraendert zurueck. */
@Serializable
data class VatComplianceAcknowledgmentInput(
    val disclaimerVersion: String,
    val disclaimerSha256: String,
)
