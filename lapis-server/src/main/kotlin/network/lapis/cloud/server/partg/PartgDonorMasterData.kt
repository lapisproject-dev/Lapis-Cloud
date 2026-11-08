package network.lapis.cloud.server.partg

import network.lapis.cloud.server.db.generated.ExternalDonorTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.PartgAnnexRules
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * V1.9.293 (R5b) -- the master data of ONE booking donor as the proposal for statement B needs it. PERSONAL DATA OF THIRD PARTIES: never logged,
 * never put into an exception, never audited.
 *
 * @property anonymised the member was anonymised (erasure): such a donor is only COUNTED, never named
 * @property country a two-letter upper-case code, or `null` when the master data holds none (free text like "Deutschland" is NOT guessed)
 */
internal data class PartgDonorMaster(
    val anonymised: Boolean,
    val name: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String?,
)

/**
 * V1.9.293 (R5b) -- the ONLY reader of `external_donor` in the package `partg` (pinned by `PartgDonorProposalTripwireTest`) and the reader of the
 * member master data for the proposal. Values are normalised with [PartgAnnexRules.normalize]; they are NEVER shortened -- a value that is too long
 * stays as it is and is refused by [PartgAnnexRules.validateDonor] in the caller.
 */
internal object PartgDonorMasterData {
    private const val CHUNK = 1000
    private val countryPattern = Regex("[A-Za-z]{2}")

    /** Keys are `M:<member uuid>` and `X:<external donor uuid>`; a key without master data row is simply missing in the result. */
    fun load(
        memberIds: Collection<Uuid>,
        externalIds: Collection<Uuid>,
    ): Map<String, PartgDonorMaster> {
        val result = HashMap<String, PartgDonorMaster>()
        memberIds.distinct().chunked(CHUNK).forEach { ids ->
            MemberTable
                .select(
                    MemberTable.id,
                    MemberTable.displayName,
                    MemberTable.street,
                    MemberTable.postalCode,
                    MemberTable.city,
                    MemberTable.countryCode,
                    MemberTable.anonymizedAt,
                ).where { MemberTable.id inList ids }
                .forEach { row ->
                    result["M:${row[MemberTable.id]}"] =
                        PartgDonorMaster(
                            anonymised = row[MemberTable.anonymizedAt] != null,
                            name = PartgAnnexRules.normalize(row[MemberTable.displayName]).orEmpty(),
                            street = PartgAnnexRules.normalize(row[MemberTable.street]),
                            postalCode = PartgAnnexRules.normalize(row[MemberTable.postalCode]),
                            city = PartgAnnexRules.normalize(row[MemberTable.city]),
                            country = countryOf(row[MemberTable.countryCode]),
                        )
                }
        }
        externalIds.distinct().chunked(CHUNK).forEach { ids ->
            ExternalDonorTable
                .select(
                    ExternalDonorTable.id,
                    ExternalDonorTable.displayName,
                    ExternalDonorTable.street,
                    ExternalDonorTable.postalCode,
                    ExternalDonorTable.city,
                    ExternalDonorTable.country,
                ).where { ExternalDonorTable.id inList ids }
                .forEach { row ->
                    result["X:${row[ExternalDonorTable.id]}"] =
                        PartgDonorMaster(
                            anonymised = false,
                            name = PartgAnnexRules.normalize(row[ExternalDonorTable.displayName]).orEmpty(),
                            street = PartgAnnexRules.normalize(row[ExternalDonorTable.street]),
                            postalCode = PartgAnnexRules.normalize(row[ExternalDonorTable.postalCode]),
                            city = PartgAnnexRules.normalize(row[ExternalDonorTable.city]),
                            country = countryOf(row[ExternalDonorTable.country]),
                        )
                }
        }
        return result
    }

    /** Exactly two letters become the upper-case code; anything else (blank, a country name, a longer text) is "no country". */
    fun countryOf(raw: String?): String? =
        PartgAnnexRules
            .normalize(raw)
            ?.takeIf { countryPattern.matches(it) }
            ?.uppercase()
}
