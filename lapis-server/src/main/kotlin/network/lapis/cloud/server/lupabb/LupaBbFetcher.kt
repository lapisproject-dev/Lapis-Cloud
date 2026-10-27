package network.lapis.cloud.server.lupabb

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import java.security.MessageDigest

/** The whole LupaBB data set of one fetch, validated and sorted by person number. */
internal class LupaBbDataset(
    val parsed: List<ParsedRecord>,
    /** `true` when at least one record carries a regional association; otherwise the chapter is not touched at all. */
    val regionalAssociationsSupplied: Boolean,
    /** SHA-256 over the canonical form of [parsed]: identifies exactly the data a preview was made from. */
    val dataSha256: String,
) {
    val validRecords: List<LupaBbRecord> get() = parsed.filterIsInstance<ParsedRecord.Valid>().map { it.record }
}

/** V1.9.226 -- fetches the lookups and every page of members under the total timeout and the size limits. */
internal object LupaBbFetcher {
    suspend fun fetchAll(
        client: LupaBbClient,
        limits: LupaBbLimits = LupaBbLimits.DEFAULT,
    ): LupaBbDataset =
        try {
            withTimeout(limits.totalTimeout) { fetchAllUnbounded(client = client, limits = limits) }
        } catch (_: TimeoutCancellationException) {
            throw LupaBbFetchException(LupaBbErrorCode.TIMEOUT)
        }

    private suspend fun fetchAllUnbounded(
        client: LupaBbClient,
        limits: LupaBbLimits,
    ): LupaBbDataset {
        val statuses = client.fetchStatuses().associate { it.id to it.label }
        val countries = client.fetchCountries().associate { it.id to it.label }
        val associations = client.fetchRegionalChapters().associate { it.id to it.label }
        val wire = ArrayList<WireMember>()
        var page = 1
        var max = 1
        do {
            val result = client.fetchMembersPage(page)
            if (result.current != null && result.current != page) throw LupaBbFetchException(LupaBbErrorCode.UNEXPECTED_RESPONSE)
            // without a page count the dataset could be cut off after page one: never guess, the planner would mark everybody else as missing
            if (result.max == null && result.members.isNotEmpty()) throw LupaBbFetchException(LupaBbErrorCode.UNEXPECTED_RESPONSE)
            max = result.max ?: 1
            if (max > limits.maxPages) throw LupaBbFetchException(LupaBbErrorCode.LIMIT_EXCEEDED)
            if (result.members.isEmpty() && page < max) throw LupaBbFetchException(LupaBbErrorCode.UNEXPECTED_RESPONSE)
            wire += result.members
            if (wire.size > limits.maxRecords) throw LupaBbFetchException(LupaBbErrorCode.LIMIT_EXCEEDED)
            page++
        } while (page <= max)
        return assemble(
            wire = wire,
            lookups = LupaBbLookups(statuses = statuses, countries = countries, regionalAssociations = associations),
        )
    }

    /** Parses, rejects duplicate person numbers (BOTH records), sorts and hashes. Public to the module so tests need no HTTP. */
    fun assemble(
        wire: List<WireMember>,
        lookups: LupaBbLookups,
    ): LupaBbDataset {
        val parsed = wire.map { LupaBbRecordParser.parse(wire = it, lookups = lookups) }
        val duplicates =
            parsed
                .filter { it.personNumber > 0 }
                .groupingBy { it.personNumber }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        val checked =
            parsed.map { record ->
                if (record.personNumber in duplicates) {
                    val name = (record as? ParsedRecord.Valid)?.record?.displayName ?: (record as? ParsedRecord.Rejected)?.name.orEmpty()
                    ParsedRecord.Rejected(
                        personNumber = record.personNumber,
                        name = name,
                        email = null,
                        category = LupaBbCategory.ERROR,
                        reason = LupaBbRowReasonCode.DUPLICATE_PERSON_NUMBER,
                    )
                } else {
                    record
                }
            }
        val sorted = checked.sortedWith(compareBy({ it.personNumber }, { canonical(it) }))
        val digest = MessageDigest.getInstance("SHA-256")
        sorted.forEach { digest.update((canonical(it) + "\n").toByteArray(Charsets.UTF_8)) }
        return LupaBbDataset(
            parsed = sorted,
            regionalAssociationsSupplied = sorted.any { it is ParsedRecord.Valid && it.record.regionalAssociationLabel != null },
            dataSha256 = digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    private const val SEP = "\u0001"

    private fun canonical(parsed: ParsedRecord): String =
        when (parsed) {
            is ParsedRecord.Rejected -> listOf("R", parsed.personNumber, parsed.category, parsed.reason, parsed.param.orEmpty())
            is ParsedRecord.Valid ->
                parsed.record.let { r ->
                    listOf(
                        "V",
                        r.personNumber,
                        r.status,
                        r.displayName,
                        r.givenName,
                        r.familyName,
                        r.title,
                        r.nameAffix,
                        r.company,
                        r.personType,
                        r.street,
                        r.addressSupplement,
                        r.poBox,
                        r.postalCode,
                        r.city,
                        r.country,
                        r.dateOfBirth,
                        r.email,
                        r.phone,
                        r.mobile,
                        r.regionalAssociationLabel,
                        r.joinedAt,
                    )
                }
        }.joinToString(SEP) { it?.toString().orEmpty() }
}
