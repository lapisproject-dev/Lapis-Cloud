package network.lapis.cloud.server.lupabb

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.mail.isValidMailboxAddress
import network.lapis.cloud.server.member.LegacyCountryDictionary
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbField
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode

/** The lookup tables the parser needs (id to label). */
internal class LupaBbLookups(
    val statuses: Map<Long, String>,
    val countries: Map<Long, String>,
    val regionalAssociations: Map<Long, String>,
)

/**
 * V1.9.226 -- turns a [WireMember] into a [ParsedRecord]. Everything the Lapis tables cannot hold is rejected HERE, with a fixed reason code and the
 * field key as parameter, never with the offending value: a control character, an over-long field, an invalid date, a missing name.
 */
internal object LupaBbRecordParser {
    const val MAX_PERSON_NUMBER = 1_000_000_000_000L
    private const val DISPLAY_NAME_MAX = 200
    private const val EMAIL_MAX = 320
    private val whitespace = Regex("""\s+""")

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun parse(
        wire: WireMember,
        lookups: LupaBbLookups,
    ): ParsedRecord {
        val number = wire.personNumber
        if (number == null || number <= 0 || number > MAX_PERSON_NUMBER) {
            return ParsedRecord.Rejected(
                personNumber = 0,
                name = "",
                email = null,
                category = LupaBbCategory.ERROR,
                reason = LupaBbRowReasonCode.INVALID_PERSON_NUMBER,
            )
        }
        val shownName =
            listOfNotNull(
                clean(wire.givenName),
                clean(wire.familyName),
                clean(wire.company),
            ).joinToString(" ").take(DISPLAY_NAME_MAX)

        fun reject(
            category: LupaBbCategory,
            reason: LupaBbRowReasonCode,
            param: String? = null,
        ) = ParsedRecord.Rejected(
            personNumber = number,
            name = shownName,
            email = null,
            category = category,
            reason = reason,
            param = param,
        )

        val raw =
            listOf(
                wire.company,
                wire.title,
                wire.givenName,
                wire.familyName,
                wire.nameAffix,
                wire.personType,
                wire.birthDate,
                wire.street,
                wire.houseNumber,
                wire.poBox,
                wire.city,
                wire.postalCode,
                wire.addressSupplement,
                wire.email,
                wire.phone,
                wire.mobile,
                wire.joinedAt,
            )
        if (raw.any { it != null && hasControlCharacter(it) }) return reject(LupaBbCategory.ERROR, LupaBbRowReasonCode.CONTROL_CHARACTER)

        // the status decides first: a cancelled or unknown record is skipped as a whole, whatever else is wrong with it
        val statusLabel = wire.statusId?.let { lookups.statuses[it] }
        val status =
            when (val mapping = if (statusLabel == null) null else LupaBbStatusMapping.map(statusLabel)) {
                is LupaBbStatusMapping.Mapped -> mapping.status
                is LupaBbStatusMapping.Skip -> return reject(LupaBbCategory.SKIPPED, mapping.reason)
                null -> return reject(LupaBbCategory.SKIPPED, LupaBbRowReasonCode.UNKNOWN_STATUS)
            }

        val given = clean(wire.givenName)
        val family = clean(wire.familyName)
        val title = clean(wire.title)
        val affix = clean(wire.nameAffix)
        val company = clean(wire.company)
        val personType =
            when (clean(wire.personType)?.lowercase()) {
                "nat" -> "NATURAL"
                "jur" -> "LEGAL"
                else -> null
            }
        val naturalName = listOfNotNull(title, given, family, affix).joinToString(" ")
        val displayName =
            when {
                personType == "LEGAL" -> company ?: naturalName.ifBlank { null }
                naturalName.isNotBlank() -> naturalName
                else -> company
            } ?: return reject(LupaBbCategory.ERROR, LupaBbRowReasonCode.NO_NAME)

        val street = clean(listOfNotNull(clean(wire.street), clean(wire.houseNumber)).joinToString(" "))
        val limits =
            listOf(
                LupaBbField.DISPLAY_NAME to (displayName to DISPLAY_NAME_MAX),
                LupaBbField.GIVEN_NAME to (given to 100),
                LupaBbField.FAMILY_NAME to (family to 100),
                LupaBbField.TITLE to (title to 50),
                LupaBbField.NAME_AFFIX to (affix to 50),
                LupaBbField.COMPANY to (company to 200),
                LupaBbField.STREET to (street to 200),
                LupaBbField.ADDRESS_SUPPLEMENT to (clean(wire.addressSupplement) to 200),
                LupaBbField.PO_BOX to (clean(wire.poBox) to 50),
                LupaBbField.POSTAL_CODE to (clean(wire.postalCode) to 20),
                LupaBbField.CITY to (clean(wire.city) to 200),
                LupaBbField.PHONE to (clean(wire.phone) to 50),
                LupaBbField.MOBILE to (clean(wire.mobile) to 50),
            )
        limits.firstOrNull { (_, v) -> (v.first?.length ?: 0) > v.second }?.let {
            return reject(LupaBbCategory.ERROR, LupaBbRowReasonCode.FIELD_TOO_LONG, it.first.name)
        }

        val birth = parseDate(wire.birthDate)
        if (birth is DateResult.Invalid) {
            return reject(
                LupaBbCategory.ERROR,
                LupaBbRowReasonCode.INVALID_DATE,
                LupaBbField.DATE_OF_BIRTH.name,
            )
        }
        val joined = parseDate(wire.joinedAt)
        if (joined is DateResult.Invalid) return reject(LupaBbCategory.ERROR, LupaBbRowReasonCode.INVALID_DATE, "JOINED_AT")

        val rawEmail = clean(wire.email)?.lowercase()
        val (email, emailIssue) =
            when {
                rawEmail == null -> null to LupaBbRowReasonCode.NO_EMAIL
                rawEmail.length > EMAIL_MAX || !isValidMailboxAddress(rawEmail) -> null to LupaBbRowReasonCode.INVALID_EMAIL
                else -> rawEmail to null
            }

        val country =
            when (val id = wire.countryId) {
                null -> LupaBbCountry.Empty
                else ->
                    when (val r = LegacyCountryDictionary.resolveCountry(lookups.countries[id])) {
                        is LegacyCountryDictionary.Resolution.Recognised -> LupaBbCountry.Code(r.codes.single())
                        else -> LupaBbCountry.Unresolved
                    }
            }

        return ParsedRecord.Valid(
            LupaBbRecord(
                personNumber = number,
                status = status,
                displayName = displayName,
                givenName = given,
                familyName = family,
                title = title,
                nameAffix = affix,
                company = company,
                personType = personType,
                street = street,
                addressSupplement = clean(wire.addressSupplement),
                poBox = clean(wire.poBox),
                postalCode = clean(wire.postalCode),
                city = clean(wire.city),
                country = country,
                dateOfBirth = (birth as? DateResult.Value)?.date,
                email = email,
                emailIssue = emailIssue,
                phone = clean(wire.phone),
                mobile = clean(wire.mobile),
                regionalAssociationLabel = wire.regionalAssociationId?.let { lookups.regionalAssociations[it] },
                joinedAt = (joined as? DateResult.Value)?.date,
            ),
        )
    }

    /** Trims, collapses every whitespace run (also TAB, LF, CR) to one space, blank becomes `null`. */
    fun clean(value: String?): String? = value?.replace(whitespace, " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** `true` for a C0 control character or DEL; TAB, LF and CR are not "raw" (they are collapsed to a space by [clean]). */
    fun hasControlCharacter(value: String): Boolean =
        value.any { c -> (c.code in 0x00..0x1F || c.code == 0x7F) && c != '\t' && c != '\n' && c != '\r' }

    private sealed interface DateResult {
        data object Absent : DateResult

        data object Invalid : DateResult

        data class Value(
            val date: LocalDate,
        ) : DateResult
    }

    private fun parseDate(raw: String?): DateResult {
        val text = clean(raw) ?: return DateResult.Absent
        if (text == "0000-00-00") return DateResult.Absent
        return try {
            DateResult.Value(LocalDate.parse(text))
        } catch (_: IllegalArgumentException) {
            DateResult.Invalid
        }
    }
}
