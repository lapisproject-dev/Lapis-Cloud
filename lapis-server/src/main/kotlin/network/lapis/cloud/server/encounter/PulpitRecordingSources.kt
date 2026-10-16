package network.lapis.cloud.server.encounter

import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.EncounterSpaceRoleTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PULPIT_RECORDING_MAX_CONCURRENT_PER_ROOM
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** The outcome of [PulpitRecordingSources.select]. [capped] = more pulpit persons qualified than the per-room limit allows. */
internal data class PulpitSourceSelection(
    val sources: List<Uuid>,
    /** PULPIT persons present and connected who still have to confirm (never recorded before they did). */
    val unconfirmed: List<Uuid>,
    val capped: Boolean,
)

/**
 * V1.9.143 -- the ONE place that decides whose camera and microphone are recorded. It is deliberately pure of any request input: the client
 * names no person, the result follows from the database and from who is connected.
 *
 * **Only an ACTIVE PULPIT office holder of this space can be a source.** A STEWARD never is (the steward moderates, and a steward's voice is not
 * what the room is recorded for). A person with a temporary floor holds no office and so can never be selected; the floor additionally stops
 * the whole recording (echo risk, see the floor rule in [PulpitRecordingCoordinator]).
 */
internal object PulpitRecordingSources {
    /**
     * Inside a transaction: the ACTIVE PULPIT holders of [spaceId] who have a presence row in [roomId], ordered by member id (a stable
     * order, so the cap always keeps the same persons), intersected with [liveIdentities] (exact UUID string match against the LiveKit roster), and --
     * when [requireConfirmation] -- split into the confirmed ones (the sources) and the others ([PulpitSourceSelection.unconfirmed]).
     */
    fun select(
        spaceId: Uuid,
        roomId: Uuid,
        liveIdentities: Set<String>,
        confirmed: Set<Uuid>,
        requireConfirmation: Boolean,
        alreadyRecorded: Set<Uuid> = emptySet(),
    ): PulpitSourceSelection {
        val pulpitHolders =
            (EncounterSpaceRoleTable innerJoin MemberTable)
                .selectAll()
                .where {
                    (EncounterSpaceRoleTable.spaceId eq spaceId) and
                        (EncounterSpaceRoleTable.role eq EncounterSpaceRole.PULPIT.name) and
                        (MemberTable.status eq MemberStatus.ACTIVE)
                }.map { it[EncounterSpaceRoleTable.memberId] }
        val present =
            ConferenceParticipationTable
                .selectAll()
                .where { ConferenceParticipationTable.roomId eq roomId }
                .map { it[ConferenceParticipationTable.memberId] }
                .toSet()
        val qualified =
            pulpitHolders
                .filter { it in present && it.toString() in liveIdentities }
                .distinct()
                .sortedBy { it.toString() }
        val allowed = if (requireConfirmation) qualified.filter { it in confirmed } else qualified
        val unconfirmed = if (requireConfirmation) qualified.filter { it !in confirmed } else emptyList()
        // Persons that are recorded already keep their slot first; then the stable order fills the rest up to the per-room limit.
        val ordered = allowed.filter { it in alreadyRecorded } + allowed.filter { it !in alreadyRecorded }
        val sources = ordered.take(PULPIT_RECORDING_MAX_CONCURRENT_PER_ROOM)
        return PulpitSourceSelection(sources = sources, unconfirmed = unconfirmed, capped = ordered.size > sources.size)
    }

    /** Inside a transaction: `true` iff [identity] parses as a UUID AND names an ACTIVE PULPIT holder of [spaceId] (the abort guard of the poller). */
    fun isStillPulpit(
        spaceId: Uuid,
        identity: String,
    ): Boolean {
        val memberId = runCatching { Uuid.parse(identity) }.getOrNull() ?: return false
        return EncounterRoles.roleOf(spaceId = spaceId, memberId = memberId) == EncounterSpaceRole.PULPIT
    }
}
