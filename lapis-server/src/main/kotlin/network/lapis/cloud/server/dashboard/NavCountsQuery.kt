package network.lapis.cloud.server.dashboard

import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.rpc.notAnEventRoomPredicate
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.NavCountsDto
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * V1.9.224 -- the two head counts behind "(aktiv/gesamt)" in the sidebar, from the database ONLY. This class deliberately has no
 * `LiveKitAdminClient`: asking the media server for every sidebar refresh of every member would be a network call per poll, so a
 * room whose last participant left a moment ago may still read "active" for up to the cache time plus the poll interval (about 90 s).
 * A tripwire test forbids the import.
 *
 * "Total" is the number of rooms that are OPEN right now, not the number that ever existed: an ended room disappears from the list the
 * same way. "Active" are those among them with at least one participation whose `left_at` is empty. Must run inside a transaction.
 */
internal object NavCountsQuery {
    fun count(
        status: MemberStatus,
        conferenceEnabled: Boolean,
        spacesAllowed: Boolean = true,
    ): NavCountsDto {
        val conference = if (conferenceEnabled && status in MemberStatusSets.ORGANIZATION_MEMBER) conferenceCounts() else ZERO
        val encounter = if (spacesAllowed && SignalGates.showsEncounterSpaces(status)) encounterCounts(status) else ZERO
        return NavCountsDto(
            conferenceActive = conference.first,
            conferenceTotal = conference.second,
            encounterActive = encounter.first,
            encounterTotal = encounter.second,
        )
    }

    /** The rooms [network.lapis.cloud.server.rpc.ConferenceService.listActiveRooms] shows: open, no encounter session, no event room. */
    private fun ordinaryOpenRoom(): Op<Boolean> =
        ConferenceRoomTable.endedAt.isNull() and ConferenceRoomTable.encounterSpaceId.isNull() and notAnEventRoomPredicate()

    private fun conferenceCounts(): Pair<Int, Int> {
        val total =
            ConferenceRoomTable
                .selectAll()
                .where { ordinaryOpenRoom() }
                .count()
                .toInt()
        if (total == 0) return ZERO
        val active =
            ConferenceParticipationTable
                .innerJoin(ConferenceRoomTable)
                .select(ConferenceParticipationTable.roomId)
                .where { ordinaryOpenRoom() and ConferenceParticipationTable.leftAt.isNull() }
                .withDistinct()
                .count()
                .toInt()
        return active to total
    }

    /**
     * Not archived (BOARD and ADMIN see archived spaces in the list, the menu counter deliberately does not count them: no maintenance
     * stock
     * in the menu). A guest or friend counts only the spaces that admit non-members, like `listSpaces`.
     */
    private fun encounterCounts(status: MemberStatus): Pair<Int, Int> {
        val admitted: Op<Boolean> =
            if (status in MemberStatusSets.NON_MEMBER) {
                EncounterSpaceTable.guestPolicy eq EncounterGuestPolicy.MEMBERS_AND_GUESTS.name
            } else {
                Op.TRUE
            }
        val visible: Op<Boolean> = EncounterSpaceTable.archivedAt.isNull() and admitted
        val total =
            EncounterSpaceTable
                .selectAll()
                .where { visible }
                .count()
                .toInt()
        if (total == 0) return ZERO
        val active =
            ConferenceRoomTable
                .innerJoin(EncounterSpaceTable)
                .select(ConferenceRoomTable.encounterSpaceId)
                .where { visible and ConferenceRoomTable.encounterSpaceId.isNotNull() and ConferenceRoomTable.endedAt.isNull() }
                .withDistinct()
                .count()
                .toInt()
        return active to total
    }

    private val ZERO = 0 to 0
}
