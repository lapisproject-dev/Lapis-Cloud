package network.lapis.cloud.server.carpool

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.clampHopLimit
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** The state of the carpool exchange of this server. [hopLimit] is always inside 1..3. */
internal data class CarpoolFederationSettings(
    val enabled: Boolean,
    val hopLimit: Int,
)

/**
 * One trip that is a CANDIDATE for the teaser feed of wave 2: opted in, an offer, inside the date window. Carries exactly the fields the one
 * eligibility rule and the one projection need -- no notes, no meeting note, no time, no author id.
 */
internal data class CarpoolTeaserCandidate(
    val postingId: Uuid,
    val type: CarpoolPostingType,
    val fromPlace: String,
    val toPlace: String,
    val departureDate: LocalDate,
    val seatsOffered: Int?,
    val authorActive: Boolean,
)

/**
 * V1.9.311 "Mitfahrzentrale-Foederation" W1 -- the ONLY reader and writer of `organization_settings.carpool_federation_*` and
 * `carpool_posting.federation_opt_in` (pinned by `CarpoolFederationTripwireTest`). All functions run inside the caller's transaction.
 * Nothing here talks to the network.
 */
internal object CarpoolFederationStore {
    fun settings(): CarpoolFederationSettings {
        val row =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .single()
        return CarpoolFederationSettings(
            enabled = row[OrganizationSettingsTable.carpoolFederationEnabled],
            hopLimit = clampHopLimit(row[OrganizationSettingsTable.carpoolFederationHopLimit].toInt()),
        )
    }

    /** Locks the settings row, then writes. The caller validates [hopLimit] (1..3). Returns the settings as they were BEFORE. */
    fun setSettings(
        enabled: Boolean,
        hopLimit: Int,
    ): CarpoolFederationSettings {
        require(hopLimit in 1..3) { "hop limit out of range" }
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .forUpdate()
            .single()
        val before = settings()
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[carpoolFederationEnabled] = enabled
            it[carpoolFederationHopLimit] = hopLimit.toShort()
        }
        return before
    }

    fun optIn(postingId: Uuid): Boolean =
        CarpoolPostingTable
            .selectAll()
            .where { CarpoolPostingTable.id eq postingId }
            .singleOrNull()
            ?.get(CarpoolPostingTable.federationOptIn) ?: false

    fun setOptIn(
        postingId: Uuid,
        value: Boolean,
    ) {
        CarpoolPostingTable.update({ CarpoolPostingTable.id eq postingId }) { it[federationOptIn] = value }
    }

    /**
     * V1.9.312: the opted-in OFFER trips departing in [from]..[until] (both inclusive), ordered by `(departure_date, id)` so the result is
     * deterministic. At most [scanLimit] + 1 rows come back: the caller detects "more than the scan limit" by the extra row (the feed is then
     * marked incomplete). The only reader of `federation_opt_in` for the feed (pinned by `CarpoolFederationTripwireTest`). The author is "active"
     * when his status is an organisation-member status and he is not anonymised -- the same floor as `requireActiveMembership`, one query.
     */
    fun teaserCandidates(
        from: LocalDate,
        until: LocalDate,
        scanLimit: Int,
    ): List<CarpoolTeaserCandidate> =
        CarpoolPostingTable
            .innerJoin(MemberTable)
            .selectAll()
            .where {
                (CarpoolPostingTable.federationOptIn eq true) and
                    (CarpoolPostingTable.type eq CarpoolPostingType.OFFER) and
                    (CarpoolPostingTable.departureDate greaterEq from) and
                    (CarpoolPostingTable.departureDate lessEq until)
            }.orderBy(CarpoolPostingTable.departureDate to SortOrder.ASC, CarpoolPostingTable.id to SortOrder.ASC)
            .limit(scanLimit + 1)
            .map {
                CarpoolTeaserCandidate(
                    postingId = it[CarpoolPostingTable.id],
                    type = it[CarpoolPostingTable.type],
                    fromPlace = it[CarpoolPostingTable.fromPlace],
                    toPlace = it[CarpoolPostingTable.toPlace],
                    departureDate = it[CarpoolPostingTable.departureDate],
                    seatsOffered = it[CarpoolPostingTable.seatsOffered],
                    authorActive =
                        it[MemberTable.status] in MemberStatusSets.ORGANIZATION_MEMBER && it[MemberTable.anonymizedAt] == null,
                )
            }

    /** A trip that turns into a REQUEST loses the consent (it can never be shared). */
    fun clearOptIn(postingId: Uuid) = setOptIn(postingId = postingId, value = false)
}
