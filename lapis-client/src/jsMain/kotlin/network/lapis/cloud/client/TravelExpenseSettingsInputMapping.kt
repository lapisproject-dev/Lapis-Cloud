package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import network.lapis.cloud.shared.domain.TravelExpenseContactDto
import network.lapis.cloud.shared.domain.TravelExpenseDefaults
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseSettingsInput

/**
 * Welle V1.9.122 -- the pure mapping between what the settings form shows (text fields) and
 * [TravelExpenseSettingsInput] (whole cents, ints), testable without a DOM, same role as
 * `OrganizationSettingsInputMapping`. Money fields are typed in EUR ("6,00") and travel as whole cents;
 * a value that does not parse maps to `null` (the form's field rules have already refused it on screen,
 * this is the second line of defence, never a silent 0).
 */
internal data class TravelExpenseSettingsFormValues(
    val tier1Hours: String,
    val tier2Hours: String,
    val tier3Hours: String,
    val tier1Eur: String,
    val tier2Eur: String,
    val tier3Eur: String,
    val breakfastPercent: String,
    val lunchPercent: String,
    val dinnerPercent: String,
    /** EUR per km, blank = not configured (mileage is then not offered). */
    val mileageEur: String,
    /** EUR per km and passenger. */
    val passengerEur: String,
    /** The legacy flat per-day rate (EUR), blank = none. Only pre-V1.9.122 lines read it. */
    val perDiemLegacyEur: String,
    val contactName: String,
    val contactEmail: String,
    val contactPhone: String,
    val contactHint: String,
    val advanceAccountId: String,
    val donationWaiverAccountId: String,
)

/** The form values for what the server currently holds. */
internal fun travelExpenseSettingsFormValues(rates: TravelExpenseRatesDto): TravelExpenseSettingsFormValues {
    val set = rates.rateSet ?: TravelExpenseDefaults.pdvTemplate()
    return TravelExpenseSettingsFormValues(
        tier1Hours = set.tierHours[0].toString(),
        tier2Hours = set.tierHours[1].toString(),
        tier3Hours = set.tierHours[2].toString(),
        tier1Eur = travelExpenseCentsText(set.tierCents[0]),
        tier2Eur = travelExpenseCentsText(set.tierCents[1]),
        tier3Eur = travelExpenseCentsText(set.tierCents[2]),
        breakfastPercent = set.breakfastPercent.toString(),
        lunchPercent = set.lunchPercent.toString(),
        dinnerPercent = set.dinnerPercent.toString(),
        mileageEur = set.mileageCentsPerKm?.let { travelExpenseCentsText(it) }.orEmpty(),
        passengerEur = travelExpenseCentsText(set.passengerCentsPerKm),
        perDiemLegacyEur = set.legacyPerDiemCents?.let { travelExpenseCentsText(it) }.orEmpty(),
        contactName = rates.contact.name.orEmpty(),
        contactEmail = rates.contact.email.orEmpty(),
        contactPhone = rates.contact.phone.orEmpty(),
        contactHint = rates.contact.hint.orEmpty(),
        advanceAccountId = rates.advanceAccountId.orEmpty(),
        donationWaiverAccountId = rates.donationWaiverAccountId.orEmpty(),
    )
}

/**
 * "Standardwerte der PdV-Vorlage übernehmen": replaces ONLY the tier/percentage/passenger values of [current] with the template. The mileage
 * rate, the legacy rate, the contact person and the account mappings stay as they are -- the template has no opinion on them. Nothing is saved
 * by this; the administration still presses "Speichern".
 */
internal fun travelExpensePdvTemplateValues(current: TravelExpenseSettingsFormValues): TravelExpenseSettingsFormValues {
    val template = TravelExpenseDefaults.pdvTemplate()
    return current.copy(
        tier1Hours = template.tierHours[0].toString(),
        tier2Hours = template.tierHours[1].toString(),
        tier3Hours = template.tierHours[2].toString(),
        tier1Eur = travelExpenseCentsText(template.tierCents[0]),
        tier2Eur = travelExpenseCentsText(template.tierCents[1]),
        tier3Eur = travelExpenseCentsText(template.tierCents[2]),
        breakfastPercent = template.breakfastPercent.toString(),
        lunchPercent = template.lunchPercent.toString(),
        dinnerPercent = template.dinnerPercent.toString(),
        passengerEur = travelExpenseCentsText(template.passengerCentsPerKm),
    )
}

/** `null` if any numeric field does not parse (the server validates the ranges). */
internal fun travelExpenseSettingsInput(values: TravelExpenseSettingsFormValues): TravelExpenseSettingsInput? {
    val hours = listOf(values.tier1Hours, values.tier2Hours, values.tier3Hours).map { it.trim().toIntOrNull() ?: return null }
    val cents = listOf(values.tier1Eur, values.tier2Eur, values.tier3Eur).map { travelExpenseParseCents(it) ?: return null }
    val breakfast = values.breakfastPercent.trim().toIntOrNull() ?: return null
    val lunch = values.lunchPercent.trim().toIntOrNull() ?: return null
    val dinner = values.dinnerPercent.trim().toIntOrNull() ?: return null
    val mileage = if (values.mileageEur.isBlank()) null else travelExpenseParseCents(values.mileageEur) ?: return null
    val passenger = travelExpenseParseCents(values.passengerEur) ?: return null
    val perDiem = if (values.perDiemLegacyEur.isBlank()) null else travelExpenseParseCents(values.perDiemLegacyEur) ?: return null
    return TravelExpenseSettingsInput(
        tierHours = hours,
        tierCents = cents,
        breakfastPercent = breakfast,
        lunchPercent = lunch,
        dinnerPercent = dinner,
        mileageCentsPerKm = mileage,
        passengerCentsPerKm = passenger,
        // Wire type is Decimal; whole cents / 100 is exact for any value below 2^53 cents.
        perDiemLegacyRate = perDiem?.let { (it / 100.0).toDecimal() },
        contact =
            TravelExpenseContactDto(
                name = values.contactName.trim().ifEmpty { null },
                email = values.contactEmail.trim().ifEmpty { null },
                phone = values.contactPhone.trim().ifEmpty { null },
                hint = values.contactHint.trim().ifEmpty { null },
            ),
        advanceAccountId = values.advanceAccountId.ifBlank { null },
        donationWaiverAccountId = values.donationWaiverAccountId.ifBlank { null },
    )
}
