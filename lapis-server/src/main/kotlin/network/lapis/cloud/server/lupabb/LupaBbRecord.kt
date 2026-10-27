package network.lapis.cloud.server.lupabb

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.MemberStatus

/** The country of a record: not given by LupaBB, resolved to a selectable code, or given but not unambiguous (then Lapis keeps its value). */
internal sealed interface LupaBbCountry {
    data object Empty : LupaBbCountry

    data class Code(
        val code: String,
    ) : LupaBbCountry

    data object Unresolved : LupaBbCountry
}

/**
 * V1.9.226 -- one LupaBB member, validated and normalised: one value per Lapis field, trimmed, whitespace collapsed, within the column widths
 * of V122. Never carries the gender or the nationality (data minimisation). The e-mail address is only used to find and create members, it is
 * never written to an existing one.
 */
internal data class LupaBbRecord(
    val personNumber: Long,
    val status: MemberStatus,
    val displayName: String,
    val givenName: String?,
    val familyName: String?,
    val title: String?,
    val nameAffix: String?,
    val company: String?,
    /** `NATURAL`, `LEGAL` or `null`. */
    val personType: String?,
    val street: String?,
    val addressSupplement: String?,
    val poBox: String?,
    val postalCode: String?,
    val city: String?,
    val country: LupaBbCountry,
    val dateOfBirth: LocalDate?,
    /** Lower case, syntactically valid; `null` when absent or invalid (see [emailIssue]). */
    val email: String?,
    val emailIssue: LupaBbRowReasonCode?,
    val phone: String?,
    val mobile: String?,
    /** `null` = LupaBB did not send a regional association for this record. */
    val regionalAssociationLabel: String?,
    val joinedAt: LocalDate?,
)

/** The outcome of reading one wire record. */
internal sealed interface ParsedRecord {
    val personNumber: Long

    data class Valid(
        val record: LupaBbRecord,
    ) : ParsedRecord {
        override val personNumber: Long get() = record.personNumber
    }

    /** Not usable: [category] is [LupaBbCategory.SKIPPED] (a status that is ignored on purpose) or [LupaBbCategory.ERROR]. */
    data class Rejected(
        override val personNumber: Long,
        val name: String,
        val email: String?,
        val category: LupaBbCategory,
        val reason: LupaBbRowReasonCode,
        val param: String? = null,
    ) : ParsedRecord
}
