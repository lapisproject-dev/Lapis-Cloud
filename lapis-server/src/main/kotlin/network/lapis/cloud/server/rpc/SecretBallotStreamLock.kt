package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen".
 *
 * ## The ONLY place the Conference domain reads Governance tables
 *
 * [network.lapis.cloud.shared.rpc.IConferenceStreamingService]'s own KDoc held, up through Wave 8,
 * that there is NO coupling between the Conference domain and the Election/SystemicConsensus
 * domains. This wave introduces exactly one -- deliberately concentrated in ONE object as ONE
 * derived query, not scattered as a foreign-key cascade across both domains. Anyone who wants to
 * know where Conference and Governance touch reads exactly this file.
 *
 * ## Why DERIVED, not a hold-/lock-table
 *
 * A hold table with reference counting was considered and rejected: it would have a real
 * consistency hole -- if a room is bound to a Sitzung via `ConferenceService.setRoomMeeting` WHILE a
 * secret ballot is already open, no hold row would exist for it and `startStream` would not be
 * blocked. A derived query is immune to that by construction, and reference counting for N
 * simultaneous secret ballots falls out for free as a set-existence check.
 *
 * ## Locking order (deadlock avoidance)
 *
 * Every caller that ACTS on this predicate must lock the affected `conference_room` row(s) FIRST via
 * [lockRooms] -- the SAME row [network.lapis.cloud.server.rpc.ConferenceStreamingService.startStream]
 * already locks. Only that way do "start a stream" and "open a secret ballot" serialize against each
 * other. The global lock order is: `conference_room` -> `conference_stream` ->
 * `conference_stream_destination` -> [network.lapis.cloud.server.audit.AuditLogRecorder.record]
 * (always LAST -- see that object's own "deadlock-avoidance contract").
 *
 * ## Scope: Election + SystemicConsensus, deliberately NOT the LTR-/Vickrey-Pfad
 *
 * `VoteTable` (meritokratische Abstimmung / LTR-gewichtete Anträge) carries no `secret` field by
 * construction -- `VoteBallotTable.memberId` is `NOT NULL`, the vote is never anonymous. Widening
 * this lock to that path would be wrong (it would pause streams for no privacy benefit) and is
 * explicitly out of scope.
 */
object SecretBallotStreamLock {
    /** Alle Räume, die an [meetingId] gebunden sind. Leer, wenn keiner gebunden ist (Normalfall). */
    fun roomIdsForMeeting(meetingId: Uuid): List<Uuid> =
        ConferenceRoomTable
            .selectAll()
            .where { ConferenceRoomTable.meetingId eq meetingId }
            .map { it[ConferenceRoomTable.id] }

    /**
     * Locks every `conference_room` row named by [roomIds] via `forUpdate()`, ordered ASCENDING by
     * id (so two concurrent transactions with overlapping room sets never block on each other in
     * opposite order), and returns the locked ids. MUST be called before any state change that
     * relies on [hasOpenSecretBallot] -- see class KDoc "Locking order". No-op (empty result, zero
     * queries) for an empty [roomIds].
     */
    fun lockRooms(roomIds: List<Uuid>): List<Uuid> {
        if (roomIds.isEmpty()) return emptyList()
        return ConferenceRoomTable
            .selectAll()
            .where { ConferenceRoomTable.id inList roomIds }
            .orderBy(ConferenceRoomTable.id, SortOrder.ASC)
            .forUpdate()
            .map { it[ConferenceRoomTable.id] }
    }

    /**
     * See class KDoc "The ONLY place the Conference domain reads Governance tables". `true` iff
     * [roomId] is bound to a meeting that currently has an open SECRET ballot in EITHER Election
     * ([ElectionStatus.OPEN] + `secret`) or SystemicConsensus ([SystemicConsensusStatus.RATING] +
     * `secret`).
     */
    fun hasOpenSecretBallot(roomId: Uuid): Boolean {
        val meetingId = roomMeetingId(roomId) ?: return false
        return hasOpenSecretBallotForMeeting(meetingId)
    }

    /**
     * Like [hasOpenSecretBallot], but takes [meetingId] directly instead of deriving it from a
     * `conference_room` row -- needed by `ConferenceService.setRoomMeeting`'s own "hin-binden" check
     * (Wave 9 §6.4), where the room being newly bound has no `conference_room.meeting_id` row yet to
     * derive this from in the first place.
     */
    fun hasOpenSecretBallotForMeeting(meetingId: Uuid): Boolean =
        hasOpenElectionBallot(meetingId) || hasOpenSystemicConsensusBallot(meetingId)

    /**
     * Security-audit MAJOR-3 fix -- widens [hasOpenSecretBallotForMeeting] with the Vorbereitungs-
     * (pre-open) states: [ElectionStatus.PREPARATION]/[ElectionStatus.CANDIDATE_LIST_RELEASED] for
     * Election, [SystemicConsensusStatus.COLLECTION] for SystemicConsensus -- both secret-only, same
     * as the OPEN/RATING check. Used by `ConferenceService.setRoomMeeting`'s "Lösen" (unbind/rebind)
     * path: a room must not be freed from its bound Sitzung right as a secret ballot is *about* to
     * open there, only for the moderator to immediately re-stream once the binding to that Sitzung no
     * longer protects it. [hasOpenSecretBallotForMeeting] alone is NOT widened to include these states
     * -- `openVoting`/`freezeOptions` themselves are what pause a stream, and that only ever happens
     * at the actual OPEN/RATING transition, not before.
     */
    fun hasPendingOrOpenSecretBallot(meetingId: Uuid): Boolean {
        if (hasOpenSecretBallotForMeeting(meetingId)) return true
        val pendingElection =
            ElectionTable
                .selectAll()
                .where {
                    (ElectionTable.meetingId eq meetingId) and
                        (ElectionTable.secret eq true) and
                        (
                            ElectionTable.status inList
                                listOf(ElectionStatus.PREPARATION, ElectionStatus.CANDIDATE_LIST_RELEASED)
                        )
                }.limit(1)
                .any()
        if (pendingElection) return true
        return SystemicConsensusTable
            .selectAll()
            .where {
                (SystemicConsensusTable.meetingId eq meetingId) and
                    (SystemicConsensusTable.secret eq true) and
                    (SystemicConsensusTable.status eq SystemicConsensusStatus.COLLECTION)
            }.limit(1)
            .any()
    }

    /**
     * The fail-closed guard for casting a secret ballot. Throws [ConflictException] as long as ANY
     * room bound to [meetingId] has a stream in [ConferenceStreamStatus.STARTING],
     * [ConferenceStreamStatus.LIVE], [ConferenceStreamStatus.PAUSING], or
     * [ConferenceStreamStatus.STOPPING] -- i.e. as long as it is NOT yet proven that no egress could
     * still be publishing. Security-audit MAJOR-1 fix: [ConferenceStreamStatus.STOPPING] was missing
     * from this blocklist -- `ConferenceStreamingService.stopStream` writes `STOPPING` and commits
     * BEFORE its own `StopEgress` call is even confirmed (see that method's own KDoc), so a stream in
     * `STOPPING` is, exactly like `PAUSING`, one whose egress is NOT yet proven stopped. Must be
     * called INSIDE the caller's own cast-ballot transaction, after `requireActiveMembership`, and
     * BEFORE any write -- and, per `ElectionService.castElectionBallot`'s own `ExposedSQLException`
     * catch-all (see that method's KDoc), strictly BEFORE any `try` block that would translate a
     * thrown [ConflictException] into a misleading "already voted" message.
     *
     * Security-audit round-6 R6-2 fix -- the actual "which statuses block casting" decision now lives
     * in [NON_QUIESCED_STREAM_STATUSES], derived from [isQuiescedForBallot]'s exhaustive `when` rather
     * than being spelled out again here as its own inline blocklist literal.
     */
    fun requireStreamQuiescedForBallot(meetingId: Uuid) {
        val roomIds = roomIdsForMeeting(meetingId)
        if (roomIds.isEmpty()) return
        val stillPublishingOrStarting =
            ConferenceStreamTable
                .selectAll()
                .where {
                    (ConferenceStreamTable.roomId inList roomIds) and
                        (ConferenceStreamTable.status inList NON_QUIESCED_STREAM_STATUSES)
                }.limit(1)
                .any()
        if (stillPublishingOrStarting) {
            throw ConflictException(
                "Für diese Sitzung läuft noch ein Live-Stream, der gerade angehalten wird -- die " +
                    "Stimmabgabe ist erst möglich, sobald nachweislich kein Stream mehr überträgt.",
            )
        }
    }

    /**
     * Security-audit round-6 R6-2 fix -- replaces [requireStreamQuiescedForBallot]'s former inline
     * blocklist literal (`status inList listOf(STARTING, LIVE, PAUSING, STOPPING)`) with an ALLOWLIST
     * expressed as an exhaustive `when` over the full [ConferenceStreamStatus] enum, no `else` branch.
     * Mirrors the exhaustive `when` `restartEgressForStream` already uses for the same reason
     * (security-audit round-5, see that function's own comment). The difference matters: a blocklist
     * treats every value it does NOT name as implicitly safe -- a future new
     * [ConferenceStreamStatus] value (e.g. a `DRAINING`/`RESTARTING` state a later wave might add)
     * would silently fall on the "quiesced, ballot casting allowed" side with no code change and no
     * compiler warning, exactly the class of bug this fix exists to make structurally impossible. With
     * this `when`, the SAME new enum value is a Kotlin compile error here until a human explicitly
     * decides which side of the fence it belongs on.
     */
    private fun isQuiescedForBallot(status: ConferenceStreamStatus): Boolean =
        when (status) {
            ConferenceStreamStatus.PAUSED,
            ConferenceStreamStatus.ENDED,
            ConferenceStreamStatus.FAILED,
            -> true
            ConferenceStreamStatus.STARTING,
            ConferenceStreamStatus.LIVE,
            ConferenceStreamStatus.PAUSING,
            ConferenceStreamStatus.STOPPING,
            -> false
        }

    /** The complement of [isQuiescedForBallot], derived rather than hand-duplicated -- see that function's own KDoc. */
    private val NON_QUIESCED_STREAM_STATUSES: List<ConferenceStreamStatus> =
        ConferenceStreamStatus.entries.filterNot(::isQuiescedForBallot)

    private fun roomMeetingId(roomId: Uuid): Uuid? =
        ConferenceRoomTable
            .selectAll()
            .where { ConferenceRoomTable.id eq roomId }
            .singleOrNull()
            ?.get(ConferenceRoomTable.meetingId)

    private fun hasOpenElectionBallot(meetingId: Uuid): Boolean =
        ElectionTable
            .selectAll()
            .where {
                (ElectionTable.meetingId eq meetingId) and (ElectionTable.secret eq true) and (ElectionTable.status eq ElectionStatus.OPEN)
            }.limit(1)
            .any()

    private fun hasOpenSystemicConsensusBallot(meetingId: Uuid): Boolean =
        SystemicConsensusTable
            .selectAll()
            .where {
                (SystemicConsensusTable.meetingId eq meetingId) and
                    (SystemicConsensusTable.secret eq true) and
                    (SystemicConsensusTable.status eq SystemicConsensusStatus.RATING)
            }.limit(1)
            .any()
}
