package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.0 Videokonferenzen, Wave 9, security-audit round-6 R6-2 fix -- direct, unit-level coverage of
 * [SecretBallotStreamLock.requireStreamQuiescedForBallot]'s allowlist classification, exercising
 * ALL SEVEN [ConferenceStreamStatus] values individually (not a sample) against a single stream row,
 * proving each lands on the correct side of the fence: exactly the guarantee an exhaustive `when`
 * with no `else` branch is meant to buy over the inline blocklist literal it replaced. Deliberately a
 * plain unit test against the object directly -- no Election/SystemicConsensus fixtures, no HTTP
 * layer, no `castElectionBallot` round-trip -- [requireStreamQuiescedForBallot] itself never reads
 * either governance table, it only ever looks at `conference_stream.status` for rooms bound to a
 * meeting id (see that function's own body), so a full ballot-casting fixture would only add
 * unrelated setup cost without covering anything this test does not already cover more directly.
 */
class SecretBallotStreamLockTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdCommitteeIds = mutableListOf<Uuid>()
        val createdMeetingIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val createdStreamIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdStreamIds.isNotEmpty()) {
                    ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id inList createdStreamIds }
                }
                if (createdRoomIds.isNotEmpty()) {
                    ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList createdRoomIds }
                }
                if (createdMeetingIds.isNotEmpty()) {
                    MeetingTable.deleteWhere { MeetingTable.id inList createdMeetingIds }
                }
                if (createdCommitteeIds.isNotEmpty()) {
                    CommitteeTable.deleteWhere { CommitteeTable.id inList createdCommitteeIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "R6-2 Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun createMeeting(creatorId: Uuid): Uuid {
            val committeeId = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[CommitteeTable.id] = committeeId
                    it[CommitteeTable.name] = "R6-2 Testgremium"
                    it[type] = CommitteeType.EXECUTIVE_BOARD
                    it[description] = "SecretBallotStreamLockTest"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = DbClock.nowLocalDateTime()
                }
            }
            createdCommitteeIds += committeeId
            val meetingId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                MeetingTable.insert {
                    it[MeetingTable.id] = meetingId
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "R6-2 Test-Sitzung"
                    it[scheduledAt] = now
                    it[location] = null
                    it[format] = MeetingFormat.ONLINE
                    it[status] = MeetingStatus.PLANNED
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = now
                }
            }
            createdMeetingIds += meetingId
            return meetingId
        }

        /** A `conference_room` row bound to [meetingId] via `meeting_id` -- the ONE thing [SecretBallotStreamLock.roomIdsForMeeting] needs. */
        fun createBoundRoom(
            creatorId: Uuid,
            meetingId: Uuid,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "R6-2 Testraum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-r6-2-stream-lock-test-$id"
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[endedAt] = null
                    it[maxParticipants] = 25
                    it[ConferenceRoomTable.meetingId] = meetingId
                }
            }
            createdRoomIds += id
            return id
        }

        fun createStream(
            roomId: Uuid,
            startedByMemberId: Uuid,
            status: ConferenceStreamStatus,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceStreamTable.insert {
                    it[ConferenceStreamTable.id] = id
                    it[ConferenceStreamTable.roomId] = roomId
                    it[ConferenceStreamTable.startedByMemberId] = startedByMemberId
                    it[ConferenceStreamTable.status] = status
                    it[layout] = ConferenceStreamLayout.GRID
                    it[latencyMode] = ConferenceStreamLatencyMode.STANDARD
                    it[participantIdentity] = null
                    it[livekitEgressId] = "EG_r6_2_${status.name}"
                    it[startedAt] = now
                    it[pausedAt] = null
                    it[endedAt] = null
                    it[restartCount] = 0
                    it[failureReason] = null
                    it[pauseReason] = null
                }
            }
            createdStreamIds += id
            return id
        }

        fun setStreamStatus(
            streamId: Uuid,
            status: ConferenceStreamStatus,
        ) {
            transaction {
                ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamId }) {
                    it[ConferenceStreamTable.status] = status
                }
            }
        }

        // ── The 4 statuses R6-1's own NON_TERMINAL_STREAM_STATUSES + the ballot-gate agree are
        // NOT proven quiesced -- requireStreamQuiescedForBallot MUST throw for every one. ──────────

        listOf(
            ConferenceStreamStatus.STARTING,
            ConferenceStreamStatus.LIVE,
            ConferenceStreamStatus.PAUSING,
            ConferenceStreamStatus.STOPPING,
        ).forEach { unsafeStatus ->
            test("requireStreamQuiescedForBallot: throws ConflictException while the bound room's stream is $unsafeStatus") {
                val member = createMember("r6-2-unsafe-${unsafeStatus.name.lowercase()}@example.org")
                val meetingId = createMeeting(member)
                val roomId = createBoundRoom(member, meetingId)
                val streamId = createStream(roomId, member, unsafeStatus)

                transaction {
                    shouldThrow<ConflictException> { SecretBallotStreamLock.requireStreamQuiescedForBallot(meetingId) }
                }

                // Cleanup guard for the shared afterSpec sweep -- not strictly needed (status never
                // changes here) but keeps setStreamStatus's helper demonstrably reachable/used.
                setStreamStatus(streamId, unsafeStatus)
            }
        }

        // ── The 3 statuses proven safe -- requireStreamQuiescedForBallot MUST NOT throw for any. ───

        listOf(
            ConferenceStreamStatus.PAUSED,
            ConferenceStreamStatus.ENDED,
            ConferenceStreamStatus.FAILED,
        ).forEach { safeStatus ->
            test("requireStreamQuiescedForBallot: does NOT throw while the bound room's stream is $safeStatus") {
                val member = createMember("r6-2-safe-${safeStatus.name.lowercase()}@example.org")
                val meetingId = createMeeting(member)
                val roomId = createBoundRoom(member, meetingId)
                createStream(roomId, member, safeStatus)

                transaction {
                    shouldNotThrowAny { SecretBallotStreamLock.requireStreamQuiescedForBallot(meetingId) }
                }
            }
        }

        test("requireStreamQuiescedForBallot: exhaustively covers all 7 ConferenceStreamStatus values -- 4 unsafe + 3 safe, no gap") {
            val allStatuses = ConferenceStreamStatus.entries.toSet()
            val coveredUnsafe =
                setOf(
                    ConferenceStreamStatus.STARTING,
                    ConferenceStreamStatus.LIVE,
                    ConferenceStreamStatus.PAUSING,
                    ConferenceStreamStatus.STOPPING,
                )
            val coveredSafe =
                setOf(
                    ConferenceStreamStatus.PAUSED,
                    ConferenceStreamStatus.ENDED,
                    ConferenceStreamStatus.FAILED,
                )
            // Guards against a future new ConferenceStreamStatus value silently going untested by the
            // two parameterized blocks above -- if this fails, both the production `when` in
            // SecretBallotStreamLock AND this test's own two lists need a deliberate new entry.
            (coveredUnsafe + coveredSafe) shouldBe allStatuses
        }
    })
