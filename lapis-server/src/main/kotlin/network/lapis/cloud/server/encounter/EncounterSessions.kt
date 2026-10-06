package network.lapis.cloud.server.encounter

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.61 -- reads of the SESSION of an encounter space: "the open session of a space is the `conference_room` row with
 * `encounter_space_id = X AND ended_at IS NULL`" (there is deliberately no `current_room_id` column on `encounter_space`). The "at most
 * one open session per space" rule is NOT enforced here but by the row lock on the `encounter_space` row every writer takes first -- see
 * `docs/architecture/encounter-space.adoc` "Lock order". Must run INSIDE the caller's open `transaction {}`.
 */
internal object EncounterSessions {
    /** The open session room of [spaceId], or `null`. Pass [forUpdate] only after the space row itself is locked (lock order). */
    fun openSession(
        spaceId: Uuid,
        forUpdate: Boolean = false,
    ): ResultRow? {
        val query =
            ConferenceRoomTable
                .selectAll()
                .where { (ConferenceRoomTable.encounterSpaceId eq spaceId) and ConferenceRoomTable.endedAt.isNull() }
        return (if (forUpdate) query.forUpdate() else query).firstOrNull()
    }

    fun presentCount(roomId: Uuid): Int =
        ConferenceParticipationTable
            .selectAll()
            .where {
                ConferenceParticipationTable.roomId eq roomId
            }.count()
            .toInt()
}

/**
 * Welle V1.9.61 -- the ONE teardown of an encounter session, shared by `EncounterSpaceService.closeSpace`, `EncounterSpacePoller` and
 * the poller's start-up sweep. The rule it implements: **when a session ends (or a person leaves) no row remains that says who was
 * there.** `conference_participation` rows of an encounter session are DELETED (never kept with `left_at`), and
 * `conference_guest_consent_acknowledgment` rows -- which must never be written for an encounter session in the first place -- are
 * deleted too as defence in depth. Every function must run INSIDE the caller's open `transaction {}`.
 */
internal object EncounterSessionTeardown {
    fun deleteParticipationsFor(roomId: Uuid): Int =
        ConferenceParticipationTable.deleteWhere {
            ConferenceParticipationTable.roomId eq
                roomId
        }

    fun deleteLegacyGuestConsentsFor(roomId: Uuid): Int =
        ConferenceGuestConsentAcknowledgmentTable.deleteWhere { ConferenceGuestConsentAcknowledgmentTable.roomId eq roomId }

    /**
     * Stamps `ended_at` (only if still open) and deletes the session's participation and legacy consent rows. Returns `true` iff THIS
     * call ended the session. The caller already holds the space and room row locks.
     */
    fun endSessionInTx(
        roomId: Uuid,
        now: LocalDateTime,
    ): Boolean {
        val ended =
            ConferenceRoomTable.update({ (ConferenceRoomTable.id eq roomId) and ConferenceRoomTable.endedAt.isNull() }) {
                it[endedAt] = now
            }
        deleteParticipationsFor(roomId = roomId)
        deleteLegacyGuestConsentsFor(roomId = roomId)
        return ended > 0
    }

    /**
     * Deletes participation and legacy consent rows of every ENDED encounter session (orphans left by a crash between the room's
     * `ended_at` stamp and the delete, or by an older version). Idempotent; returns the number of rows removed.
     */
    fun purgeEndedSessions(): Int {
        val endedRooms =
            ConferenceRoomTable
                .select(ConferenceRoomTable.id)
                .where { ConferenceRoomTable.encounterSpaceId.isNotNull() and ConferenceRoomTable.endedAt.isNotNull() }
        val participations = ConferenceParticipationTable.deleteWhere { ConferenceParticipationTable.roomId inSubQuery endedRooms }
        val consents =
            ConferenceGuestConsentAcknowledgmentTable.deleteWhere { ConferenceGuestConsentAcknowledgmentTable.roomId inSubQuery endedRooms }
        return participations + consents
    }
}
