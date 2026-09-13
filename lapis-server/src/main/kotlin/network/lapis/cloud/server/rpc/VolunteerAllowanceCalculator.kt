package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.domain.VolunteerAllowanceDeclarationSource
import network.lapis.cloud.shared.domain.VolunteerAllowanceVerdict
import network.lapis.cloud.shared.rpc.BadRequestException
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" (§3 Nr. 26 / 26a EStG) -- pure, DB-free cap
 * arithmetic. [check] never throws and never touches the database; the caller
 * ([VolunteerAllowanceService]) supplies [VolunteerAllowanceResult.newTotal]'s ingredients from a
 * fresh `priorPostedAllowanceTotalThisYear` query taken under the payment row's `forUpdate()` lock.
 *
 * **Every comparison uses [BigDecimal.compareTo], never `equals`/`==`** -- the same scale trap
 * [PartyDonationComplianceCalculator]/`JournalEntryBalance` document in their own KDoc:
 * `BigDecimal("960") != BigDecimal("960.00")` under `equals()`, but `compareTo` treats them as
 * equal. [VolunteerAllowanceVerdict.CAP_EXHAUSTED] is exactly `newTotal.compareTo(cap) == 0`.
 */
internal object VolunteerAllowanceCalculator {
    /**
     * §3 Nr. 26 EStG Übungsleiterpauschale -- Stand 2026 nach dem Steueränderungsgesetz 2025.
     * **Dieser Betrag ist ein Gesetzesdatum im Code und ändert sich.** Das StÄndG 2025 ist der
     * Präzedenzfall (Erhöhung von 3.000 auf 3.300 EUR) -- gegen den aktuellen Gesetzestext
     * verifizieren, idealerweise mit einer Steuerberaterin, bevor sich eine reale Organisation
     * darauf verlässt. Gleiche Disclaimer-Klasse wie
     * [PartyDonationComplianceCalculator.FOREIGN_DONOR_ANNUAL_CAP_EUR].
     *
     * **Der Deckel gilt PRO PERSON PRO KALENDERJAHR über ALLE Organisationen hinweg.** Lapis
     * Cloud kennt ausschließlich die vereinsinterne Summe -- jeder von hier zurückgegebene
     * `remaining`-Wert ist deshalb ein Maximum, keine Zusage (Kay-Ruling, siehe
     * [network.lapis.cloud.shared.domain.VolunteerAllowanceYearStatusDto.remainingInThisOrganization]).
     */
    val INSTRUCTOR_ANNUAL_CAP_EUR: BigDecimal = BigDecimal("3300")

    /** §3 Nr. 26a EStG Ehrenamtspauschale -- gleicher Verifikations-Vorbehalt wie oben. */
    val HONORARY_ANNUAL_CAP_EUR: BigDecimal = BigDecimal("960")

    private const val AMOUNT_SCALE = 2

    fun capFor(category: VolunteerAllowanceCategory): BigDecimal =
        when (category) {
            VolunteerAllowanceCategory.INSTRUCTOR -> INSTRUCTOR_ANNUAL_CAP_EUR
            VolunteerAllowanceCategory.HONORARY -> HONORARY_ANNUAL_CAP_EUR
        }.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)

    fun check(
        category: VolunteerAllowanceCategory,
        amount: BigDecimal,
        priorPostedTotalThisYear: BigDecimal,
    ): VolunteerAllowanceResult {
        val cap = capFor(category)
        val priorTotal = priorPostedTotalThisYear.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
        val requested = amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
        val newTotal = (priorTotal + requested).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
        val remainingBefore = (cap - priorTotal).let { if (it.signum() < 0) BigDecimal.ZERO.setScale(AMOUNT_SCALE) else it }
        val freeAmount = if (requested.compareTo(remainingBefore) <= 0) requested else remainingBefore
        val exceedingAmount = (requested - freeAmount).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
        val verdict =
            when {
                newTotal.compareTo(cap) < 0 -> VolunteerAllowanceVerdict.WITHIN_CAP
                newTotal.compareTo(cap) == 0 -> VolunteerAllowanceVerdict.CAP_EXHAUSTED
                else -> VolunteerAllowanceVerdict.EXCEEDS_CAP
            }
        return VolunteerAllowanceResult(
            cap = cap,
            priorTotal = priorTotal,
            newTotal = newTotal,
            freeAmount = freeAmount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP),
            exceedingAmount = exceedingAmount,
            verdict = verdict,
        )
    }
}

internal data class VolunteerAllowanceResult(
    val cap: BigDecimal,
    val priorTotal: BigDecimal,
    val newTotal: BigDecimal,
    /** The tax-free portion of THIS payment, always `>= 0`. */
    val freeAmount: BigDecimal,
    /** `amount - freeAmount`, always `>= 0`. */
    val exceedingAmount: BigDecimal,
    val verdict: VolunteerAllowanceVerdict,
)

/**
 * Mirrors `chk_vasd_source_shape` in code (server-side pre-check before the DB constraint would
 * otherwise surface as a raw `ExposedSQLException`/500) plus one additional rule the DB cannot
 * express: [signedOn] must not be in the future. Throws [BadRequestException] on any violation,
 * never silently coerces.
 */
internal fun requireSelfDeclarationShape(
    source: VolunteerAllowanceDeclarationSource,
    memberId: Uuid,
    recordedBy: Uuid,
    signedOn: LocalDate?,
    today: LocalDate,
) {
    when (source) {
        VolunteerAllowanceDeclarationSource.IN_APP -> {
            if (recordedBy != memberId) {
                throw BadRequestException("source=IN_APP requires recordedBy to equal memberId (the member declares for themselves)")
            }
            if (signedOn != null) throw BadRequestException("signedOn must not be set for source=IN_APP")
        }
        VolunteerAllowanceDeclarationSource.ON_PAPER -> {
            if (recordedBy == memberId) {
                throw BadRequestException(
                    "source=ON_PAPER requires recordedBy to differ from memberId (someone else records a paper declaration)",
                )
            }
            if (signedOn == null) throw BadRequestException("signedOn is required for source=ON_PAPER")
            if (signedOn > today) throw BadRequestException("signedOn must not be in the future")
        }
    }
}
