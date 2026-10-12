package network.lapis.cloud.server.rpc

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.mail.isValidMailboxAddress
import network.lapis.cloud.shared.domain.CalcResult
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseContactDto
import network.lapis.cloud.shared.domain.TravelExpenseDefaults
import network.lapis.cloud.shared.domain.TravelExpenseLineInput
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseRateSet
import network.lapis.cloud.shared.domain.TravelExpenseSettingsInput
import network.lapis.cloud.shared.rpc.BadRequestException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

/*
 * Welle V1.9.122 -- server-side validation and conversion for the PdV-form travel expense lines
 * and the operator settings. Everything here is stateless and `internal`; the arithmetic itself
 * lives in the shared `TravelExpenseCalculator` (the ONE calculation core), this file only decides
 * "is this input acceptable" and converts between the wire `Decimal` and whole cents.
 */

/** JSON used for `travel_expense_report.rates_snapshot`. `encodeDefaults` is load-bearing: a frozen snapshot must not depend on future defaults. */
internal val travelExpenseSnapshotJson: Json =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

/** Everything the service needs from `organization_settings` for this domain, loaded in ONE read. */
internal data class TravelExpenseSettings(
    val rateSet: TravelExpenseRateSet,
    /** Raw `travel_mileage_rate_per_km` (EUR/km), kept for the legacy `Decimal` wire fields. */
    val mileageRate: BigDecimal?,
    val perDiemRate: BigDecimal?,
    val contact: TravelExpenseContactDto,
    val expenseAccountId: Uuid?,
    val bankAccountId: Uuid?,
    val advanceAccountId: Uuid?,
    val donationWaiverAccountId: Uuid?,
)

internal object TravelExpenseSettingsStore {
    /** Must run inside a `transaction {}`. A missing row degrades to the PdV template (never happens: V1 seeds it). */
    fun load(): TravelExpenseSettings {
        val row =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
                ?: return TravelExpenseSettings(
                    rateSet = TravelExpenseDefaults.pdvTemplate(),
                    mileageRate = null,
                    perDiemRate = null,
                    contact = TravelExpenseContactDto(),
                    expenseAccountId = null,
                    bankAccountId = null,
                    advanceAccountId = null,
                    donationWaiverAccountId = null,
                )
        val mileage = row[OrganizationSettingsTable.travelMileageRatePerKm]
        val perDiem = row[OrganizationSettingsTable.travelPerDiemRate]
        val rateSet =
            TravelExpenseRateSet(
                tierHours =
                    listOf(
                        row[OrganizationSettingsTable.travelTier1Hours],
                        row[OrganizationSettingsTable.travelTier2Hours],
                        row[OrganizationSettingsTable.travelTier3Hours],
                    ),
                tierCents =
                    listOf(
                        row[OrganizationSettingsTable.travelTier1Cents].toLong(),
                        row[OrganizationSettingsTable.travelTier2Cents].toLong(),
                        row[OrganizationSettingsTable.travelTier3Cents].toLong(),
                    ),
                breakfastPercent = row[OrganizationSettingsTable.travelBreakfastPct],
                lunchPercent = row[OrganizationSettingsTable.travelLunchPct],
                dinnerPercent = row[OrganizationSettingsTable.travelDinnerPct],
                // A pre-V1.9.122 rate with sub-cent digits (the legacy column allowed 4) is rounded HALF_UP
                // to whole cents here -- documented limitation, see CHANGELOG.
                mileageCentsPerKm = mileage?.toCents(),
                passengerCentsPerKm = row[OrganizationSettingsTable.travelPassengerCentsPerKm].toLong(),
                legacyPerDiemCents = perDiem?.toCents(),
            )
        return TravelExpenseSettings(
            rateSet = rateSet,
            mileageRate = mileage,
            perDiemRate = perDiem,
            contact =
                TravelExpenseContactDto(
                    name = row[OrganizationSettingsTable.travelContactName],
                    email = row[OrganizationSettingsTable.travelContactEmail],
                    phone = row[OrganizationSettingsTable.travelContactPhone],
                    hint = row[OrganizationSettingsTable.travelContactHint],
                ),
            expenseAccountId = row[OrganizationSettingsTable.travelExpenseAccountId],
            bankAccountId = row[OrganizationSettingsTable.paymentBankAccountId],
            advanceAccountId = row[OrganizationSettingsTable.travelAdvanceAccountId],
            donationWaiverAccountId = row[OrganizationSettingsTable.travelDonationWaiverAccountId],
        )
    }
}

/** EUR -> whole cents, HALF_UP. */
internal fun BigDecimal.toCents(): Long = movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()

/** Whole cents -> EUR with scale 2 (exact, never rounds). */
internal fun Long.centsToAmount(): BigDecimal = BigDecimal.valueOf(this, 2)

/** Cents -> EUR/km with scale 4 (the `rate_snapshot` column). */
internal fun Long.centsToRate(): BigDecimal = BigDecimal.valueOf(this, 2).setScale(4)

internal object TravelExpenseLineRules {
    /** Unwraps a calculator result; a [CalcResult.Err] becomes a [BadRequestException] carrying the stable code. */
    fun <T> CalcResult<T>.orBadRequest(context: String = ""): T =
        when (this) {
            is CalcResult.Ok -> value
            is CalcResult.Err -> throw BadRequestException("${if (context.isEmpty()) "" else "$context: "}${error.code} (${error.key})")
        }

    /**
     * Resolves the amount of a receipted line from the legacy `amount` Decimal and/or the new
     * `amountCents`. If both are supplied they must agree (mass-assignment/ambiguity guard).
     */
    fun receiptedCents(input: TravelExpenseLineInput): Long {
        val fromDecimal = input.amount?.setScale(2, RoundingMode.HALF_UP)?.toCents()
        val fromCents = input.amountCents
        if (fromDecimal != null && fromCents != null && fromDecimal != fromCents) {
            throw BadRequestException("amount and amountCents disagree")
        }
        return fromCents ?: fromDecimal ?: throw BadRequestException("amount is required for kind=${input.kind}")
    }

    fun requireDescription(input: TravelExpenseLineInput): String {
        val description = input.description.trim()
        if (description.isEmpty()) throw BadRequestException("description must not be blank")
        if (description.length > TravelExpenseAmountRules.MAX_DESCRIPTION_LENGTH) {
            throw BadRequestException("description must be at most ${TravelExpenseAmountRules.MAX_DESCRIPTION_LENGTH} characters")
        }
        return description
    }

    fun requireDestination(destination: String?): String? {
        val trimmed = destination?.trim()?.ifEmpty { null } ?: return null
        if (trimmed.length > TravelExpenseAmountRules.MAX_DESTINATION_LENGTH) {
            throw BadRequestException("destination must be at most ${TravelExpenseAmountRules.MAX_DESTINATION_LENGTH} characters")
        }
        if (trimmed.any { it.isISOControl() }) throw BadRequestException("destination must not contain control characters")
        return trimmed
    }

    /** MILEAGE kilometres must be whole numbers 1..5000 -- the form counts whole kilometres. */
    fun wholeKilometers(kilometers: BigDecimal): Long {
        val stripped = kilometers.stripTrailingZeros()
        if (stripped.scale() > 0) throw BadRequestException("kilometers must be a whole number")
        val value =
            runCatching { stripped.longValueExact() }.getOrElse {
                throw BadRequestException("kilometers must be between 1 and ${TravelExpenseAmountRules.MAX_KM_PER_LINE}")
            }
        if (value < 1 || value > TravelExpenseAmountRules.MAX_KM_PER_LINE) {
            throw BadRequestException("kilometers must be between 1 and ${TravelExpenseAmountRules.MAX_KM_PER_LINE}")
        }
        return value
    }

    fun requirePassengerCount(count: Int?): Int {
        val value = count ?: 0
        if (value < 0 || value > TravelExpenseAmountRules.MAX_PASSENGERS) {
            throw BadRequestException("passengerCount must be between 0 and ${TravelExpenseAmountRules.MAX_PASSENGERS}")
        }
        return value
    }

    /** ACCOMMODATION: first night + nights must lie inside the travel period. */
    fun requireAccommodationInRange(
        firstNight: LocalDate,
        nights: Int,
        travelFrom: LocalDate,
        travelTo: LocalDate,
    ) {
        if (nights < 1 || nights > TravelExpenseAmountRules.MAX_NIGHTS) {
            throw BadRequestException("nights must be between 1 and ${TravelExpenseAmountRules.MAX_NIGHTS}")
        }
        val lastMorning = firstNight.plus(nights, DateTimeUnit.DAY)
        if (firstNight < travelFrom || lastMorning > travelTo) {
            throw BadRequestException("accommodation_outside_range")
        }
    }

    // ── Settings ────────────────────────────────────────────────────────────────────────────

    private val PHONE_REGEX = Regex("^[+0-9 ()/\\-]{3,40}$")

    /** Validated + normalised settings input (contact fields trimmed, blank -> null). */
    fun validateSettings(input: TravelExpenseSettingsInput): TravelExpenseSettingsInput {
        if (input.tierHours.size != 3) throw BadRequestException("tierHours must contain exactly 3 values")
        if (input.tierCents.size != 3) throw BadRequestException("tierCents must contain exactly 3 values")
        val (h1, h2, h3) = input.tierHours
        if (h1 < 1 || h1 >= h2 || h2 >= h3 || h3 > 24) {
            throw BadRequestException("tierHours must be strictly ascending within 1..24")
        }
        val (c1, c2, c3) = input.tierCents
        if (c1 < 0 || c1 > c2 || c2 > c3 || c3 > 10_000) {
            throw BadRequestException("tierCents must be ascending within 0..10000")
        }
        listOf(input.breakfastPercent, input.lunchPercent, input.dinnerPercent).forEach {
            if (it !in 0..100) throw BadRequestException("meal percentages must be between 0 and 100")
        }
        if (input.passengerCentsPerKm !in 0..20) throw BadRequestException("passengerCentsPerKm must be between 0 and 20")
        input.mileageCentsPerKm?.let {
            if (it < 1 || it > 100) throw BadRequestException("mileageCentsPerKm must be between 1 and 100 (max 1.00 EUR/km)")
        }
        input.perDiemLegacyRate?.let {
            if (it <= BigDecimal.ZERO) throw BadRequestException("perDiemLegacyRate must be positive")
            if (it.scale() > 2) throw BadRequestException("perDiemLegacyRate must have at most 2 fractional digits")
        }
        return input.copy(contact = normalizeContact(input.contact))
    }

    fun normalizeContact(contact: TravelExpenseContactDto): TravelExpenseContactDto {
        fun clean(
            value: String?,
            max: Int,
            field: String,
            allowNewline: Boolean = false,
        ): String? {
            val trimmed = value?.replace("\r\n", "\n")?.trim()?.ifEmpty { null } ?: return null
            if (trimmed.length > max) throw BadRequestException("$field must be at most $max characters")
            val bad = trimmed.any { it.isISOControl() && !(allowNewline && it == '\n') }
            if (bad) throw BadRequestException("$field must not contain control characters")
            return trimmed
        }

        val name = clean(contact.name, TravelExpenseAmountRules.MAX_CONTACT_NAME_LENGTH, "contact name")
        val email = clean(contact.email, TravelExpenseAmountRules.MAX_CONTACT_EMAIL_LENGTH, "contact email")
        if (email != null &&
            (email.any { it.isWhitespace() || it == '<' || it == '>' || it == ',' || it == ';' } || !isValidMailboxAddress(email))
        ) {
            throw BadRequestException("contact email is not a valid address")
        }
        val phone = clean(contact.phone, TravelExpenseAmountRules.MAX_CONTACT_PHONE_LENGTH, "contact phone")
        if (phone != null && !PHONE_REGEX.matches(phone)) throw BadRequestException("contact phone contains invalid characters")
        val hint = clean(contact.hint, TravelExpenseAmountRules.MAX_CONTACT_HINT_LENGTH, "contact hint", allowNewline = true)
        return TravelExpenseContactDto(name = name, email = email, phone = phone, hint = hint)
    }

    /** True for the kinds [addLineKindAllowed] accepts through `addLine`. */
    fun addLineKindAllowed(kind: TravelExpenseLineKind): Boolean =
        kind == TravelExpenseLineKind.MILEAGE ||
            kind == TravelExpenseLineKind.PUBLIC_TRANSPORT ||
            kind == TravelExpenseLineKind.ACCOMMODATION ||
            kind == TravelExpenseLineKind.HOSPITALITY ||
            kind == TravelExpenseLineKind.OTHER
}
