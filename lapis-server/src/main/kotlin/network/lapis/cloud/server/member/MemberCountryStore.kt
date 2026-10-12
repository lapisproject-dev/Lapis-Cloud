package network.lapis.cloud.server.member

import network.lapis.cloud.server.db.generated.MemberNationalityTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.geo.CountryDisplayNames
import network.lapis.cloud.shared.domain.CountryCodes
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.121 -- the **only** reader and writer of `member.country_code` and `member_nationality` (apart from the explicitly
 * allowlisted callers, see `CountryCodeAccessTripwireTest`): the reconciliation, the erasure contributor, the CSV import and the seed.
 *
 * Every function runs inside the caller's `transaction {}`. A write requires that the caller already holds the member row lock
 * (`forMemberUpdate()` / the peer-guard lock) in THIS transaction: the 10-row cap and the XS/ZZ exclusivity are application rules
 * (a CHECK with a subquery is not portable between H2 and Postgres), and the lock is what serialises two writers of one member.
 * Lock order is always member row first, then `member_nationality`.
 *
 * **Legacy text.** `member.country` / `member.nationality` stay as read-only legacy values. A human choosing a code clears the legacy
 * text; clearing the code leaves it alone.
 */
object MemberCountryStore {
    /** Nationality codes of every given member in ONE query (sorted per member); members without rows are absent from the map. */
    fun loadNationalities(memberIds: Collection<Uuid>): Map<Uuid, List<String>> {
        if (memberIds.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Uuid, MutableList<String>>()
        memberIds.chunked(IN_LIST_CHUNK).forEach { chunk ->
            MemberNationalityTable
                .selectAll()
                .where { MemberNationalityTable.memberId inList chunk }
                .orderBy(MemberNationalityTable.memberId to SortOrder.ASC, MemberNationalityTable.countryCode to SortOrder.ASC)
                .forEach { row ->
                    result.getOrPut(row[MemberNationalityTable.memberId]) { mutableListOf() } +=
                        row[MemberNationalityTable.countryCode]
                }
        }
        return result
    }

    fun loadNationalities(memberId: Uuid): List<String> = loadNationalities(listOf(memberId))[memberId].orEmpty()

    /** `true` when the member has at least one nationality row. */
    fun hasNationality(memberId: Uuid): Boolean =
        MemberNationalityTable
            .selectAll()
            .where { MemberNationalityTable.memberId eq memberId }
            .limit(1)
            .any()

    /**
     * Sets the country of [memberId] to [code] (already validated by the caller via [CountryCodes.validateCountry]) and clears the
     * legacy text. `null` clears only the code.
     */
    fun setCountry(
        memberId: Uuid,
        code: String?,
    ): Int {
        require(code == null || CountryCodes.isCountry(code)) { "country code not selectable" }
        return MemberTable.update({ MemberTable.id eq memberId }) {
            it[countryCode] = code
            if (code != null) it[country] = null
        }
    }

    /**
     * Replaces all nationality rows of [memberId]. [codes] is re-validated here (cap, exclusivity, list membership) so no caller can
     * bypass the rules; a violation throws [IllegalArgumentException] WITHOUT the value. A non-empty list clears the legacy text.
     */
    fun replaceNationalities(
        memberId: Uuid,
        codes: List<String>,
        clearLegacy: Boolean = true,
    ) {
        val valid =
            when (val result = CountryCodes.normalizeNationalities(codes)) {
                is CountryCodes.NationalitySelection.Valid -> result.codes
                is CountryCodes.NationalitySelection.Invalid -> throw IllegalArgumentException(
                    "invalid nationality selection: ${result.reason}",
                )
            }
        MemberNationalityTable.deleteWhere { MemberNationalityTable.memberId eq memberId }
        if (valid.isNotEmpty()) {
            MemberNationalityTable.batchInsert(valid) { code ->
                this[MemberNationalityTable.memberId] = memberId
                this[MemberNationalityTable.countryCode] = code
            }
            if (clearLegacy) MemberTable.update({ MemberTable.id eq memberId }) { it[nationality] = null }
        }
    }

    /** Reconciliation only: sets the code and KEEPS the legacy text next to it. */
    fun setCountryKeepLegacy(
        memberId: Uuid,
        code: String,
    ): Int {
        require(CountryCodes.isCountry(code)) { "country code not selectable" }
        return MemberTable.update({ MemberTable.id eq memberId }) { it[countryCode] = code }
    }

    /** Erasure / anonymisation: removes the code and every nationality row. The legacy columns are handled by the erasing caller. */
    fun clearAll(memberId: Uuid): Int {
        MemberTable.update({ MemberTable.id eq memberId }) { it[countryCode] = null }
        return MemberNationalityTable.deleteWhere { MemberNationalityTable.memberId eq memberId }
    }

    /** German country name of [code], else the legacy text, else `null`. */
    fun effectiveCountry(
        code: String?,
        legacy: String?,
    ): String? = if (code != null) CountryDisplayNames.germanName(code) else legacy

    /** German nationality text ("Deutschland, Polen"), else the legacy text, else `null`. */
    fun effectiveNationality(
        codes: List<String>,
        legacy: String?,
    ): String? = if (codes.isNotEmpty()) CountryDisplayNames.germanNationalityText(codes) else legacy

    private const val IN_LIST_CHUNK = 500
}
