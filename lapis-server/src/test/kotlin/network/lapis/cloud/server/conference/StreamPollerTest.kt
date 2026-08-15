package network.lapis.cloud.server.conference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Same all-zero 32-byte key [buildPoller] injects via `LAPIS_SECRET_ENCRYPTION_KEY` -- lets fixtures that need a REAL [SecretBox]-encrypted stream key (the `restartEgressForStream` reconciliation path) decrypt successfully against the poller under test. */
private val POLLER_TEST_ENCRYPTION_KEY = ByteArray(32)

private const val FAKE_ROOM_NAME_PREFIX = "lc-stream-poller-test-"

/**
 * Records every call for assertions -- [listEgress] returns a per-room canned response
 * ([egressListByRoom]), or throws [LiveKitAdminException] when [failListEgress] holds, or a plain
 * [RuntimeException] when [roomName] equals [throwUnexpectedForRoom] (used by the tick()
 * exception-safety test -- an UNCAUGHT throwable type, unlike the [LiveKitAdminException] every
 * other resilience test in this file exercises).
 */
private class StreamPollerFakeEgressClient(
    private val failListEgress: Boolean = false,
    private val throwUnexpectedForRoom: String? = null,
    /**
     * Security-audit round-3 NEU-2 regression tests -- fires synchronously inside [stopEgress], BEFORE
     * it returns. Lets a test deterministically simulate a resurrection (a concurrent
     * `startStream`/`restartEgressForStream` "abandoned" branch attaching a FRESH egress id to the
     * SAME row) landing in the exact window between this poller requesting a stop and it actually
     * confirming/finalizing -- without a real, timing-dependent thread race: the hook mutates the row
     * directly, synchronously, right where the real resurrection would have committed.
     */
    private val onStopEgress: ((String) -> Unit)? = null,
    /**
     * Security-audit round-6 R6-1 regression test -- fires synchronously inside [listEgress], right
     * before it returns [egressListByRoom]'s canned response. [handleLive]'s "egress vanished from
     * ListEgress" branch has NO `stopEgress` call between its own `ListEgress` observation and the
     * [StreamPoller.markFailed] write that follows -- unlike [handlePausing]/[handleStopping], which
     * request a `StopEgress` first -- so [onStopEgress]'s hook point does not exist on that path at
     * all. This hook simulates the SAME class of resurrection ([onStopEgress]'s own KDoc) landing in
     * that shorter window instead: right where a concurrent `startStream`/`restartEgressForStream`
     * "abandoned" branch would commit a fresh, actually-publishing egress id to the row.
     */
    private val onListEgress: ((String) -> Unit)? = null,
) : LiveKitEgressClient {
    val egressListByRoom = mutableMapOf<String, List<LiveKitEgressInfo>>()
    val stopCalls = mutableListOf<Pair<String, String>>()
    val listEgressCalls = AtomicInteger(0)

    /** Records every `restartEgressForStream`/`resumeStream`-style restart attempt (roomName, layout, rtmpUrls). */
    val startRoomCompositeEgressCalls = mutableListOf<Triple<String, ConferenceStreamLayout, List<String>>>()

    /** `null` -> [startRoomCompositeEgress] throws [LiveKitAdminException] (simulated restart failure); otherwise its result. */
    var startRoomCompositeEgressResult: (() -> LiveKitEgressInfo)? = {
        LiveKitEgressInfo(egressId = "EG_default_restart", status = "EGRESS_STARTING")
    }

    override suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): LiveKitEgressInfo = error("not used by StreamPollerTest")

    override suspend fun stopEgress(
        roomName: String,
        egressId: String,
    ): LiveKitEgressInfo {
        stopCalls += roomName to egressId
        onStopEgress?.invoke(egressId)
        return LiveKitEgressInfo(egressId = egressId, status = "EGRESS_ENDING")
    }

    override suspend fun listEgress(roomName: String): List<LiveKitEgressInfo> {
        listEgressCalls.incrementAndGet()
        if (roomName == throwUnexpectedForRoom) throw RuntimeException("simulated unexpected failure")
        if (failListEgress) throw LiveKitAdminException("ListEgress failed (simulated outage)")
        onListEgress?.invoke(roomName)
        return egressListByRoom[roomName] ?: emptyList()
    }

    override suspend fun startRoomCompositeEgress(
        roomName: String,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo {
        startRoomCompositeEgressCalls += Triple(roomName, layout, rtmpUrls)
        return startRoomCompositeEgressResult?.invoke() ?: throw LiveKitAdminException("simulated StartRoomCompositeEgress failure")
    }

    override suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo = error("not used by StreamPollerTest")

    override suspend fun updateStream(
        roomName: String,
        egressId: String,
        addUrls: List<String>,
        removeUrls: List<String>,
    ): LiveKitEgressInfo = error("not used by StreamPollerTest")
}

/**
 * [StreamPoller.tick] end to end -- real H2 DB rows (mirrors [RecordingPollerTest]'s own house
 * style), a fake [LiveKitEgressClient], no real network/LiveKit involvement. Covers the
 * STARTING/LIVE/PAUSED/STOPPING branches, the `url_fingerprint` matching discipline, the sanitized
 * failure vocabulary, and `tick()`'s own exception-safety.
 */
class StreamPollerTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val createdDestinationIds = mutableListOf<Uuid>()
        val createdStreamIds = mutableListOf<Uuid>()
        // V1.0 Videokonferenzen, Wave 9 -- governance fixtures, only used by the PAUSED+SECRET_BALLOT
        // "still open" regression test (SecretBallotStreamLock.hasOpenSecretBallot needs a real,
        // OPEN+secret Election bound via conference_room.meeting_id -- see createOpenSecretElection).
        val createdCommitteeIds = mutableListOf<Uuid>()
        val createdMeetingIds = mutableListOf<Uuid>()
        val createdMotionIds = mutableListOf<Uuid>()
        val createdElectionIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        // [tick] scans EVERY non-terminal conference_stream row in the whole table, not just rows
        // this test itself created -- a stream left LIVE/STARTING/PAUSED/STOPPING by one test would
        // otherwise be picked up (and call the NEXT test's OWN fake LiveKitEgressClient instance,
        // for a room that client knows nothing about) by every later test's own tick() call in this
        // spec. Force every stream created SO FAR into a terminal status after each test so the
        // next test's tick() only ever sees its own rows.
        afterTest {
            transaction {
                ConferenceStreamTable.update({ ConferenceStreamTable.id inList createdStreamIds }) {
                    it[status] = ConferenceStreamStatus.ENDED
                }
            }
        }

        afterSpec {
            transaction {
                if (createdStreamIds.isNotEmpty()) {
                    ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId inList createdStreamIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                }
                if (createdStreamIds.isNotEmpty()) {
                    ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id inList createdStreamIds }
                }
                if (createdDestinationIds.isNotEmpty()) {
                    ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.id inList createdDestinationIds }
                }
                if (createdRoomIds.isNotEmpty()) {
                    ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList createdRoomIds }
                }
                // Governance fixtures (see their own comment above) -- FK-safe order Election ->
                // Motion -> Meeting -> Committee, AFTER conference_room rows are gone (conference_room
                // .meeting_id references meeting) and BEFORE member rows (Motion/Election reference member).
                if (createdElectionIds.isNotEmpty()) {
                    ElectionTable.deleteWhere { ElectionTable.id inList createdElectionIds }
                }
                if (createdMotionIds.isNotEmpty()) {
                    MotionTable.deleteWhere { MotionTable.id inList createdMotionIds }
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
                    it[displayName] = "StreamPoller Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
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

        fun createRoom(
            creatorId: Uuid,
            ended: Boolean = false,
        ): Pair<Uuid, String> {
            val id = Uuid.random()
            val livekitRoomName = "$FAKE_ROOM_NAME_PREFIX$id"
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "Poller-Test-Raum"
                    it[description] = ""
                    it[ConferenceRoomTable.livekitRoomName] = livekitRoomName
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[endedAt] = if (ended) now else null
                    it[maxParticipants] = 25
                }
            }
            createdRoomIds += id
            return id to livekitRoomName
        }

        /**
         * [streamKey] `null` (default) -- an intentionally UN-decryptable placeholder ciphertext,
         * fine for every test that never actually decrypts it (everything except the
         * `restartEgressForStream` reconciliation path). Pass a real [streamKey] to get a genuinely
         * [SecretBox]-sealed ciphertext (against [POLLER_TEST_ENCRYPTION_KEY], the SAME key
         * [buildPoller] injects) for tests that exercise that decryption.
         */
        fun createDestination(
            creatorId: Uuid,
            streamKey: String? = null,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            val ciphertext =
                if (streamKey != null) {
                    SecretBox(POLLER_TEST_ENCRYPTION_KEY).seal(streamKey, aad = id.toString())
                } else {
                    "v1:unused:unused"
                }
            transaction {
                ConferenceStreamDestinationTable.insert {
                    it[ConferenceStreamDestinationTable.id] = id
                    it[label] = "Poller-Test-Ziel-$id"
                    it[platform] = ConferenceStreamPlatform.GENERIC_RTMP
                    it[rtmpUrl] = "rtmp://sink.example.org:1935/live"
                    it[streamKeyCiphertext] = ciphertext
                    it[streamKeySetAt] = now
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[enabled] = true
                }
            }
            createdDestinationIds += id
            return id
        }

        fun createStream(
            roomId: Uuid,
            startedByMemberId: Uuid,
            status: ConferenceStreamStatus,
            startedAt: LocalDateTime = DbClock.nowLocalDateTime(),
            livekitEgressId: String? = null,
            restartCount: Int = 0,
            pauseReason: ConferenceStreamPauseReason? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceStreamTable.insert {
                    it[ConferenceStreamTable.id] = id
                    it[ConferenceStreamTable.roomId] = roomId
                    it[ConferenceStreamTable.startedByMemberId] = startedByMemberId
                    it[ConferenceStreamTable.status] = status
                    it[layout] = ConferenceStreamLayout.GRID
                    it[latencyMode] = ConferenceStreamLatencyMode.STANDARD
                    it[participantIdentity] = null
                    it[ConferenceStreamTable.livekitEgressId] = livekitEgressId
                    it[ConferenceStreamTable.startedAt] = startedAt
                    it[pausedAt] = null
                    it[endedAt] = null
                    it[ConferenceStreamTable.restartCount] = restartCount
                    it[failureReason] = null
                    it[ConferenceStreamTable.pauseReason] = pauseReason
                }
            }
            createdStreamIds += id
            return id
        }

        fun createTarget(
            streamId: Uuid,
            destinationId: Uuid,
            urlFingerprint: String,
            status: ConferenceStreamTargetStatus = ConferenceStreamTargetStatus.PENDING,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceStreamTargetTable.insert {
                    it[ConferenceStreamTargetTable.id] = id
                    it[ConferenceStreamTargetTable.streamId] = streamId
                    it[ConferenceStreamTargetTable.destinationId] = destinationId
                    it[ConferenceStreamTargetTable.status] = status
                    it[ConferenceStreamTargetTable.urlFingerprint] = urlFingerprint
                    it[startedAtEpochNanos] = null
                    it[endedAtEpochNanos] = null
                    it[retries] = 0
                    it[failureReason] = null
                }
            }
            return id
        }

        // ── Governance fixtures -- ONLY for the PAUSED+SECRET_BALLOT "ballot still open" regression
        // test, which needs SecretBallotStreamLock.hasOpenSecretBallot to genuinely return true.
        // Direct table inserts bypassing ElectionService's own authorization/validation, same
        // "fixtures only need to exist, not be exercised through the service" house style
        // ElectionServiceTest's own createTestCommittee/createTestMeeting/createTerminierterMotion
        // establish (mirrored here, not shared, since that file is out of scope for this pass).

        fun createCommittee(name: String): Uuid {
            val id = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[CommitteeTable.id] = id
                    it[CommitteeTable.name] = name
                    it[type] = CommitteeType.EXECUTIVE_BOARD
                    it[description] = "StreamPoller-Testgremium"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = DbClock.nowLocalDateTime()
                }
            }
            createdCommitteeIds += id
            return id
        }

        fun createMeeting(committeeId: Uuid): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                MeetingTable.insert {
                    it[MeetingTable.id] = id
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "StreamPoller-Test-Sitzung"
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
            createdMeetingIds += id
            return id
        }

        /** Directly seeds an already-[MotionStatus.SCHEDULED] Motion -- purely to satisfy [ElectionTable.motionId]'s NOT NULL FK, never itself exercised. */
        fun createMotion(
            committeeId: Uuid,
            meetingId: Uuid,
            submitterId: Uuid,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                MotionTable.insert {
                    it[MotionTable.id] = id
                    it[targetCommitteeId] = committeeId
                    it[title] = "StreamPoller-Testantrag"
                    it[rationale] = "Rationale"
                    it[text] = "Antragstext"
                    it[submitterMemberId] = submitterId
                    it[status] = MotionStatus.SCHEDULED
                    it[submittedAt] = now
                    it[reviewedBy] = submitterId
                    it[reviewedAt] = now
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[resolutionId] = null
                }
            }
            createdMotionIds += id
            return id
        }

        /** An [ElectionStatus.OPEN] + `secret=true` Election bound to [meetingId] -- the ONE thing [SecretBallotStreamLock.hasOpenSecretBallot] needs to return `true`. */
        fun createOpenSecretElection(
            meetingId: Uuid,
            motionId: Uuid,
            openedByMemberId: Uuid,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ElectionTable.insert {
                    it[ElectionTable.id] = id
                    it[title] = "StreamPoller-Testwahl (geheim)"
                    it[electionType] = ElectionType.YES_NO
                    it[secret] = true
                    it[seatCount] = 0
                    it[targetCommitteeId] = null
                    it[targetRole] = null
                    it[requiredMajorityPercent] = 50
                    it[status] = ElectionStatus.OPEN
                    it[ElectionTable.openedBy] = openedByMemberId
                    it[openedAt] = now
                    it[candidateListApprovedAt] = null
                    it[votingOpenedAt] = now
                    it[votingClosedAt] = null
                    it[tallyThreshold] = 2
                    it[tallyRunAt] = null
                    it[ElectionTable.motionId] = motionId
                    it[ElectionTable.meetingId] = meetingId
                    it[resolutionId] = null
                }
            }
            createdElectionIds += id
            return id
        }

        fun bindRoomToMeeting(
            roomId: Uuid,
            meetingId: Uuid,
        ) {
            transaction {
                ConferenceRoomTable.update({ ConferenceRoomTable.id eq roomId }) { it[ConferenceRoomTable.meetingId] = meetingId }
            }
        }

        fun streamRow(id: Uuid) = transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq id }.single() }

        fun targetRow(id: Uuid) =
            transaction { ConferenceStreamTargetTable.selectAll().where { ConferenceStreamTargetTable.id eq id }.single() }

        fun buildPoller(
            egressClient: LiveKitEgressClient,
            maxDurationMinutes: Long = 480,
            startupTimeoutSeconds: Long = 60,
            clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
        ): StreamPoller {
            val config =
                ConferenceStreamingConfig.load { key ->
                    when (key) {
                        "LAPIS_STREAMING_ENABLED" -> "true"
                        "LAPIS_SECRET_ENCRYPTION_KEY" ->
                            java.util.Base64
                                .getEncoder()
                                .encodeToString(ByteArray(32))
                        "LAPIS_STREAM_MAX_DURATION_MINUTES" -> maxDurationMinutes.toString()
                        "LAPIS_STREAM_STARTUP_TIMEOUT_SECONDS" -> startupTimeoutSeconds.toString()
                        else -> null
                    }
                }
            return StreamPoller(egressClient, config, clock)
        }

        // ── LIVE ─────────────────────────────────────────────────────────

        test("LIVE: refreshes target status/retries from ListEgress stream_results, matched via url_fingerprint -- stream stays LIVE") {
            val member = createMember("poller-live-refresh@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.LIVE, livekitEgressId = "EG_live1")
            val fingerprint = "rtmp://sink.example.org:1935/live/{tes...123}"
            val targetId = createTarget(streamId, destId, fingerprint)

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] =
                listOf(
                    LiveKitEgressInfo(
                        egressId = "EG_live1",
                        status = "EGRESS_ACTIVE",
                        streamResults = listOf(LiveKitStreamInfo(url = fingerprint, status = "ACTIVE", retries = 2)),
                    ),
                )

            buildPoller(egressClient).tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
            targetRow(targetId)[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.ACTIVE
            targetRow(targetId)[ConferenceStreamTargetTable.retries] shouldBe 2
        }

        test(
            "LIVE: security-audit MINOR-5 -- a LIVE stream whose room is bound to a meeting with an open secret " +
                "ballot (a bind-after-openVoting's-own-lock-snapshot race, never caught by " +
                "ConferenceStreamPauseCoordinator.markPausingForSecretBallot at ballot-open time) gets reconciled " +
                "to PAUSING on this tick, WITHOUT ever calling ListEgress/StopEgress for it -- handleLive itself " +
                "has no ballot-awareness at all, this reconciliation is what supplies it",
        ) {
            val member = createMember("poller-live-missedballotpause@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val committeeId = createCommittee("StreamPoller Vorstand LIVE-Reconcile")
            val meetingId = createMeeting(committeeId)
            val motionId = createMotion(committeeId, meetingId, member)
            createOpenSecretElection(meetingId, motionId, member)
            // The bind happens AFTER the election is already OPEN -- exactly the interleaving
            // ElectionService.openVoting's own room-lock snapshot (taken once, at openVoting time)
            // cannot retroactively catch.
            bindRoomToMeeting(roomId, meetingId)

            val streamId = createStream(roomId, member, ConferenceStreamStatus.LIVE, livekitEgressId = "EG_live_missed_pause")
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{mis...sed}")

            val egressClient = StreamPollerFakeEgressClient()
            // Deliberately NOT configured with any egressListByRoom entry for roomName -- if
            // handleLive ran (it must NOT), it would call ListEgress and, finding nothing, mark the
            // stream FAILED instead of PAUSING; asserting listEgressCalls stays 0 proves handleLive's
            // own body never ran this tick.
            buildPoller(egressClient).tick()

            val stream = streamRow(streamId)
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING
            stream[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.SECRET_BALLOT
            // Untouched -- markPausingForSecretBallot is a pure DB write, no LiveKit call at all
            // (the actual StopEgress happens later, via SecretBallotStreamGuard/StreamPoller's own
            // PAUSING handling on a SUBSEQUENT tick).
            stream[ConferenceStreamTable.livekitEgressId] shouldBe "EG_live_missed_pause"
            egressClient.listEgressCalls.get() shouldBe 0
            egressClient.stopCalls shouldBe emptyList()
        }

        test(
            "LIVE: a terminal EgressInfo.status marks the STREAM and every non-terminal target FAILED with a SANITIZED reason -- never the raw LiveKit text",
        ) {
            val member = createMember("poller-live-terminal@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.LIVE, livekitEgressId = "EG_live2")
            val fingerprint = "rtmp://sink.example.org:1935/live/{tes...456}"
            val targetId = createTarget(streamId, destId, fingerprint)

            val rawError = "Failed to connect: Error resolving “nonexistent-bad-host-xyz”: Name or service not known"
            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] =
                listOf(
                    LiveKitEgressInfo(
                        egressId = "EG_live2",
                        status = "EGRESS_FAILED",
                        error = rawError,
                        streamResults = listOf(LiveKitStreamInfo(url = fingerprint, status = "FAILED", error = rawError, retries = 3)),
                    ),
                )

            buildPoller(egressClient).tick()

            val stream = streamRow(streamId)
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.FAILED
            stream[ConferenceStreamTable.failureReason] shouldBe "Die Verbindung zum Streaming-Ziel konnte nicht hergestellt werden."
            (stream[ConferenceStreamTable.failureReason]?.contains("nonexistent-bad-host-xyz") ?: true) shouldBe false

            val target = targetRow(targetId)
            target[ConferenceStreamTargetTable.status] shouldBe ConferenceStreamTargetStatus.FAILED
            target[ConferenceStreamTargetTable.failureReason] shouldBe "Die Verbindung zum Streaming-Ziel konnte nicht hergestellt werden."
        }

        test("LIVE: egress no longer reported by ListEgress -> FAILED immediately") {
            val member = createMember("poller-live-vanished@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.LIVE, livekitEgressId = "EG_gone")
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{gon...one}")

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] = emptyList()

            buildPoller(egressClient).tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.FAILED
        }

        test(
            "LIVE: security-audit round-6 R6-1 -- a resurrection that attaches a FRESH egress id to " +
                "the row WHILE this tick observes the OLD (stale, snapshotted) egress id vanished from " +
                "ListEgress must not be silently overwritten with FAILED; the row stays LIVE under the " +
                "fresh id, and the NEXT tick correctly evaluates that fresh id on its own merits",
        ) {
            val member = createMember("poller-live-r6-1-resurrection@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.LIVE, livekitEgressId = "EG_stale")
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{sta...ale}")

            val egressClient =
                StreamPollerFakeEgressClient(
                    // Fires exactly where the real race lands: DURING this tick's ListEgress
                    // observation, which (unlike handlePausing/handleStopping) is the ONLY step
                    // between "row snapshotted with EG_stale" and markFailed's write on this code
                    // path -- simulates a concurrent startStream/restartEgressForStream "abandoned"
                    // branch resurrecting this SAME row onto a fresh, actually-publishing egress right
                    // here, before this tick's own FAILED write would otherwise land.
                    onListEgress = { name ->
                        if (name == roomName) {
                            transaction {
                                ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamId }) {
                                    it[livekitEgressId] = "EG_fresh"
                                }
                            }
                        }
                    },
                )
            // EG_stale is genuinely gone from ListEgress's own point of view -- that vanishing is
            // exactly what lets handleLive's info == null branch reach the guarded markFailed call in
            // the first place.
            egressClient.egressListByRoom[roomName] = emptyList()

            buildPoller(egressClient).tick()

            // The R6-1 fix: markFailed's finalizing write is guarded on the egress id it actually
            // confirmed vanished (EG_stale) still matching the row's CURRENT livekit_egress_id --
            // since the row now carries EG_fresh, the write is skipped entirely. Without the fix, this
            // assertion fails: the row would read FAILED, and EG_fresh would never be revisited by
            // anything (FAILED sits outside NON_TERMINAL_STREAM_STATUSES, so tick() would never query
            // this row again -- and outside SecretBallotStreamLock's quiesced-allowlist would then
            // incorrectly treat it as safe to let a secret ballot proceed).
            val afterFirstTick = streamRow(streamId)
            afterFirstTick[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
            afterFirstTick[ConferenceStreamTable.livekitEgressId] shouldBe "EG_fresh"
            afterFirstTick[ConferenceStreamTable.failureReason] shouldBe null

            // A second tick re-reads a FRESH row snapshot (egressId = EG_fresh this time). EG_fresh is
            // reported as genuinely gone too, on its own merits -- this time correctly FAILED, proving
            // the fresh egress is never permanently stranded, merely deferred to the next honest
            // observation of ITS OWN id.
            egressClient.egressListByRoom[roomName] = emptyList()
            buildPoller(egressClient).tick()

            val afterSecondTick = streamRow(streamId)
            afterSecondTick[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.FAILED
            afterSecondTick[ConferenceStreamTable.livekitEgressId] shouldBe "EG_fresh"
        }

        test("LIVE: auto-stops (StopEgress + ENDED) once maxDurationMinutes has elapsed since startedAt") {
            val member = createMember("poller-live-maxduration@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId = createStream(roomId, member, ConferenceStreamStatus.LIVE, startedAt = startedAt, livekitEgressId = "EG_maxdur")
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{max...dur}", status = ConferenceStreamTargetStatus.ACTIVE)

            val fakeNow = startedAt.shiftedByMinutes(11)
            val egressClient = StreamPollerFakeEgressClient()
            val poller = buildPoller(egressClient, maxDurationMinutes = 10, clock = { fakeNow })

            poller.tick()

            egressClient.stopCalls shouldBe listOf(roomName to "EG_maxdur")
            val stream = streamRow(streamId)
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
            (stream[ConferenceStreamTable.endedAt] != null) shouldBe true
        }

        test("LIVE: room has ENDED -> auto-stop regardless of duration/egress status") {
            val member = createMember("poller-live-roomended@example.org")
            val (roomId, roomName) = createRoom(member, ended = true)
            val destId = createDestination(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.LIVE, livekitEgressId = "EG_roomended")
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{roo...ded}")

            val egressClient = StreamPollerFakeEgressClient()
            buildPoller(egressClient).tick()

            egressClient.stopCalls shouldBe listOf(roomName to "EG_roomended")
            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
        }

        // ── PAUSED ───────────────────────────────────────────────────────

        test("PAUSED: auto-finalizes to ENDED once maxDurationMinutes elapsed -- NO StopEgress call, nothing is running") {
            val member = createMember("poller-paused-maxduration@example.org")
            val (roomId, _) = createRoom(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId =
                createStream(roomId, member, ConferenceStreamStatus.PAUSED, startedAt = startedAt, livekitEgressId = "EG_paused_stale")

            val fakeNow = startedAt.shiftedByMinutes(11)
            val egressClient = StreamPollerFakeEgressClient()
            val poller = buildPoller(egressClient, maxDurationMinutes = 10, clock = { fakeNow })

            poller.tick()

            egressClient.stopCalls shouldBe emptyList()
            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
        }

        test("PAUSED: within maxDurationMinutes -> left untouched") {
            val member = createMember("poller-paused-within@example.org")
            val (roomId, _) = createRoom(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.PAUSED)

            buildPoller(StreamPollerFakeEgressClient(), maxDurationMinutes = 480).tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
        }

        // ── PAUSED -- Wave 9 "Stream-Pause bei geheimen Abstimmungen": pauseReason-driven behavior ──

        test(
            "PAUSED: pauseReason=MANUAL past maxDurationMinutes still auto-ends -- SECRET_BALLOT's own " +
                "max-duration suspension must never leak to a moderator-initiated pause",
        ) {
            val member = createMember("poller-paused-manual-maxduration@example.org")
            val (roomId, _) = createRoom(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId =
                createStream(
                    roomId,
                    member,
                    ConferenceStreamStatus.PAUSED,
                    startedAt = startedAt,
                    pauseReason = ConferenceStreamPauseReason.MANUAL,
                )

            val fakeNow = startedAt.shiftedByMinutes(11)
            val poller = buildPoller(StreamPollerFakeEgressClient(), maxDurationMinutes = 10, clock = { fakeNow })

            poller.tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
        }

        test(
            "PAUSED: pauseReason=SECRET_BALLOT with a STILL-OPEN secret Election on the bound meeting suspends the " +
                "max-duration ceiling entirely -- the actual Wave 9 bug-fix regression test (Stolperfalle §9.2)",
        ) {
            val member = createMember("poller-paused-secretballot-open@example.org")
            val (roomId, _) = createRoom(member)
            val committeeId = createCommittee("StreamPoller Vorstand")
            val meetingId = createMeeting(committeeId)
            bindRoomToMeeting(roomId, meetingId)
            val motionId = createMotion(committeeId, meetingId, member)
            createOpenSecretElection(meetingId, motionId, member)

            val startedAt = DbClock.nowLocalDateTime()
            val streamId =
                createStream(
                    roomId,
                    member,
                    ConferenceStreamStatus.PAUSED,
                    startedAt = startedAt,
                    pauseReason = ConferenceStreamPauseReason.SECRET_BALLOT,
                )

            // Far past any max-duration ceiling -- would auto-end a MANUAL/null-reason PAUSED stream
            // (see the contrast test above and the pre-existing generic PAUSED test), must NOT touch
            // this one while the ballot that caused the pause is still open.
            val fakeNow = startedAt.shiftedByMinutes(11)
            val poller = buildPoller(StreamPollerFakeEgressClient(), maxDurationMinutes = 10, clock = { fakeNow })

            poller.tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
        }

        test(
            "PAUSED: pauseReason=SECRET_BALLOT with NO open secret ballot on the (unbound) room triggers " +
                "restartEgressForStream's orphan-reconciliation and mints a fresh egress -- back to LIVE",
        ) {
            val member = createMember("poller-paused-secretballot-reconcile@example.org")
            // meeting_id stays NULL (never bound) -- SecretBallotStreamLock.hasOpenSecretBallot is
            // false by construction for an unbound room, the exact "resume was lost to a crash"
            // scenario restartEgressForStream KDoc "secretBox" describes.
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member, streamKey = "reconcile-stream-key")
            val streamId =
                createStream(
                    roomId,
                    member,
                    ConferenceStreamStatus.PAUSED,
                    livekitEgressId = "EG_paused_secretballot_stale",
                    pauseReason = ConferenceStreamPauseReason.SECRET_BALLOT,
                )
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{sta...ale}")

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.startRoomCompositeEgressResult =
                { LiveKitEgressInfo(egressId = "EG_reconciled_fresh", status = "EGRESS_STARTING") }

            buildPoller(egressClient).tick()

            egressClient.startRoomCompositeEgressCalls.size shouldBe 1
            val (calledRoomName, calledLayout, _) = egressClient.startRoomCompositeEgressCalls.single()
            calledRoomName shouldBe roomName
            calledLayout shouldBe ConferenceStreamLayout.GRID

            val stream = streamRow(streamId)
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
            stream[ConferenceStreamTable.livekitEgressId] shouldBe "EG_reconciled_fresh"
            stream[ConferenceStreamTable.pauseReason] shouldBe null
            stream[ConferenceStreamTable.restartCount] shouldBe 1
        }

        test(
            "PAUSED: pauseReason=SECRET_BALLOT reconciliation, LiveKit egress start fails -> FAILED with the sanitized reason",
        ) {
            val member = createMember("poller-paused-secretballot-reconcile-fail@example.org")
            val (roomId, _) = createRoom(member)
            val destId = createDestination(member, streamKey = "reconcile-stream-key-fail")
            val streamId =
                createStream(
                    roomId,
                    member,
                    ConferenceStreamStatus.PAUSED,
                    livekitEgressId = "EG_paused_secretballot_stale_fail",
                    pauseReason = ConferenceStreamPauseReason.SECRET_BALLOT,
                )
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{fai...led}")

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.startRoomCompositeEgressResult = null // -> throws LiveKitAdminException, see the fake's own KDoc

            buildPoller(egressClient).tick()

            val stream = streamRow(streamId)
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.FAILED
            stream[ConferenceStreamTable.restartCount] shouldBe 1
        }

        // ── PAUSED -- security-audit-round-2 F3: a declined auto-resume must converge, not loop forever ──

        test(
            "PAUSED: security-audit-round-2 F3 -- an auto-resume declined for a disabled destination " +
                "(MINOR-9) escalates pauseReason to MANUAL, so handlePaused's SECRET_BALLOT branch is " +
                "never re-entered on the NEXT tick -- no infinite per-tick retry loop, no repeated " +
                "restartEgressForStream attempts",
        ) {
            val member = createMember("poller-paused-f3-disabled-destination@example.org")
            // meeting_id stays NULL (never bound) -- SecretBallotStreamLock.hasOpenSecretBallot is
            // false by construction, same "resume was lost to a crash" setup the reconciliation test
            // above uses -- handlePaused's SECRET_BALLOT branch therefore attempts the auto-resume on
            // the very first tick.
            val (roomId, _) = createRoom(member)
            val destId = createDestination(member)
            transaction {
                ConferenceStreamDestinationTable.update({ ConferenceStreamDestinationTable.id eq destId }) { it[enabled] = false }
            }
            val streamId =
                createStream(
                    roomId,
                    member,
                    ConferenceStreamStatus.PAUSED,
                    livekitEgressId = "EG_f3_stale",
                    pauseReason = ConferenceStreamPauseReason.SECRET_BALLOT,
                )
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{f3d...isb}")

            val egressClient = StreamPollerFakeEgressClient()
            val poller = buildPoller(egressClient)

            poller.tick()

            // Before the F3 fix, this write left pauseReason=SECRET_BALLOT untouched -- the stream
            // never leaves the SECRET_BALLOT auto-resume path as long as the destination stays
            // disabled.
            val afterFirstTick = streamRow(streamId)
            afterFirstTick[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
            afterFirstTick[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.MANUAL
            egressClient.startRoomCompositeEgressCalls shouldBe emptyList()

            // A SECOND (and any subsequent) tick must NOT re-enter the SECRET_BALLOT auto-resume branch
            // at all -- pauseReason is no longer SECRET_BALLOT, so handlePaused falls straight through
            // to its ordinary (non-suspended) maxDurationMinutes check instead, converging rather than
            // looping forever and holding the room's one-active-stream-per-room slot open indefinitely.
            poller.tick()
            val afterSecondTick = streamRow(streamId)
            afterSecondTick[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
            afterSecondTick[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.MANUAL
            egressClient.startRoomCompositeEgressCalls shouldBe emptyList()
        }

        // ── PAUSED -- security-audit round-3 NEU-3: a THIRD location with the same F3 infinite-loop shape ──

        test(
            "PAUSED: security-audit round-3 NEU-3 -- an auto-resume declined because " +
                "LAPIS_SECRET_ENCRYPTION_KEY is unset/invalid escalates pauseReason to MANUAL too, " +
                "exactly like the disabled-destination MINOR-9/F3 branch -- no infinite per-tick retry " +
                "loop, no repeated restartEgressForStream attempts",
        ) {
            val member = createMember("poller-paused-neu3-no-key@example.org")
            // meeting_id stays NULL (never bound) -- SecretBallotStreamLock.hasOpenSecretBallot is
            // false by construction, same "resume was lost to a crash" setup the F3/reconciliation
            // tests above use -- handlePaused's SECRET_BALLOT branch therefore attempts the auto-resume
            // on the very first tick, straight into restartEgressForStream's own secretBox==null gate.
            val (roomId, _) = createRoom(member)
            val destId = createDestination(member)
            val streamId =
                createStream(
                    roomId,
                    member,
                    ConferenceStreamStatus.PAUSED,
                    livekitEgressId = "EG_neu3_stale",
                    pauseReason = ConferenceStreamPauseReason.SECRET_BALLOT,
                )
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{neu...3ky}")

            val egressClient = StreamPollerFakeEgressClient()
            // Deliberately OMITS BOTH LAPIS_STREAMING_ENABLED and LAPIS_SECRET_ENCRYPTION_KEY --
            // ConferenceStreamingConfig.load's own fail-fast (see its KDoc "Fail-fast on the
            // encryption key") only throws when LAPIS_STREAMING_ENABLED="true", so this is the only
            // way to reach a config with secretEncryptionKey=null via the public load() factory (the
            // constructor itself is private). restartEgressForStream and StreamPoller.tick() neither
            // one checks streamingConfig.enabled directly (that gate lives one layer up, in
            // Application.module's own StreamPoller.start() wiring) -- calling tick() directly here
            // reaches restartEgressForStream's own `secretBox = streamingConfig.secretEncryptionKey
            // ?.let { SecretBox(it) }` unobstructed, resolving to null exactly as this fix's target
            // branch (ConferenceStreamingService.kt's restartEgressForStream, "if (secretBox ==
            // null)") requires. Matches the KDoc's own framing of this branch as "operator
            // misconfiguration, not a reachable steady state for a caller with a validated config" --
            // this test reaches it the same way that misconfiguration would: a streamingConfig whose
            // key went missing independently of the instance StreamPoller itself was built with.
            val noKeyConfig =
                ConferenceStreamingConfig.load { key ->
                    when (key) {
                        "LAPIS_STREAM_MAX_DURATION_MINUTES" -> "480"
                        "LAPIS_STREAM_STARTUP_TIMEOUT_SECONDS" -> "60"
                        else -> null
                    }
                }
            val poller = StreamPoller(egressClient, noKeyConfig)

            poller.tick()

            // Before the NEU-3 fix, this write left pauseReason=SECRET_BALLOT untouched -- the stream
            // never leaves the SECRET_BALLOT auto-resume path as long as the key stays unset/invalid.
            val afterFirstTick = streamRow(streamId)
            afterFirstTick[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
            afterFirstTick[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.MANUAL
            egressClient.startRoomCompositeEgressCalls shouldBe emptyList()

            // A SECOND tick must NOT re-enter the SECRET_BALLOT auto-resume branch at all.
            poller.tick()
            val afterSecondTick = streamRow(streamId)
            afterSecondTick[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
            afterSecondTick[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.MANUAL
            egressClient.startRoomCompositeEgressCalls shouldBe emptyList()
        }

        // ── PAUSING -- Wave 9 "Stream-Pause bei geheimen Abstimmungen": StopEgress requested, not
        // yet confirmed terminal ────────────────────────────────────────────────────────────────

        test("PAUSING: egressId null, within startupTimeoutSeconds -> left alone, no ListEgress call at all") {
            val member = createMember("poller-pausing-tooearly@example.org")
            val (roomId, _) = createRoom(member)
            val destId = createDestination(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId =
                createStream(
                    roomId,
                    member,
                    ConferenceStreamStatus.PAUSING,
                    startedAt = startedAt,
                    livekitEgressId = null,
                    pauseReason = ConferenceStreamPauseReason.SECRET_BALLOT,
                )
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{pau...rly}")

            val egressClient = StreamPollerFakeEgressClient()
            buildPoller(egressClient, startupTimeoutSeconds = 60, clock = { startedAt }).tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING
            egressClient.listEgressCalls.get() shouldBe 0
        }

        test(
            "PAUSING: egressId null, timeout exceeded, matching orphan found via ListEgress -> adopts the egress id " +
                "and immediately requests StopEgress, stays PAUSING for the next tick to confirm",
        ) {
            val member = createMember("poller-pausing-adopt@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId =
                createStream(
                    roomId,
                    member,
                    ConferenceStreamStatus.PAUSING,
                    startedAt = startedAt,
                    livekitEgressId = null,
                    pauseReason = ConferenceStreamPauseReason.SECRET_BALLOT,
                )
            val fingerprint = "rtmp://sink.example.org:1935/live/{pau...opt}"
            createTarget(streamId, destId, fingerprint)

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] =
                listOf(
                    LiveKitEgressInfo(
                        egressId = "EG_pausing_adopted",
                        status = "EGRESS_ACTIVE",
                        streamResults = listOf(LiveKitStreamInfo(url = fingerprint, status = "ACTIVE")),
                    ),
                )
            val fakeNow = startedAt.shiftedBySeconds(90)
            buildPoller(egressClient, startupTimeoutSeconds = 60, clock = { fakeNow }).tick()

            val stream = streamRow(streamId)
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING
            stream[ConferenceStreamTable.livekitEgressId] shouldBe "EG_pausing_adopted"
            egressClient.stopCalls shouldBe listOf(roomName to "EG_pausing_adopted")
        }

        test("PAUSING: egressId null, timeout exceeded, no matching orphan -> PAUSED directly (nothing was ever publishing)") {
            val member = createMember("poller-pausing-noorphan@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId =
                createStream(
                    roomId,
                    member,
                    ConferenceStreamStatus.PAUSING,
                    startedAt = startedAt,
                    livekitEgressId = null,
                    pauseReason = ConferenceStreamPauseReason.SECRET_BALLOT,
                )
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{pau...one}")

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] = emptyList()
            val fakeNow = startedAt.shiftedBySeconds(90)
            buildPoller(egressClient, startupTimeoutSeconds = 60, clock = { fakeNow }).tick()

            val stream = streamRow(streamId)
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
            (stream[ConferenceStreamTable.pausedAt] != null) shouldBe true
            egressClient.stopCalls shouldBe emptyList()
        }

        test("PAUSING: egressId present -> requests StopEgress then confirms via ListEgress; egress gone from the list -> PAUSED") {
            val member = createMember("poller-pausing-confirm-gone@example.org")
            val (roomId, roomName) = createRoom(member)
            val streamId =
                createStream(
                    roomId,
                    member,
                    ConferenceStreamStatus.PAUSING,
                    livekitEgressId = "EG_pausing_gone",
                    pauseReason = ConferenceStreamPauseReason.SECRET_BALLOT,
                )

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] = emptyList()
            buildPoller(egressClient).tick()

            egressClient.stopCalls shouldBe listOf(roomName to "EG_pausing_gone")
            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
        }

        test(
            "PAUSING: security-audit round-3 NEU-2 -- a resurrection that attaches a FRESH egress id to " +
                "the row WHILE this tick is confirming the OLD (stale, snapshotted) egress id's stop must " +
                "not be silently overwritten with PAUSED; the row stays PAUSING under the fresh id, and " +
                "the NEXT tick correctly confirms+stops that fresh id for real",
        ) {
            val member = createMember("poller-pausing-neu2-resurrection@example.org")
            val (roomId, roomName) = createRoom(member)
            val streamId =
                createStream(
                    roomId,
                    member,
                    ConferenceStreamStatus.PAUSING,
                    livekitEgressId = "EG_stale",
                    pauseReason = ConferenceStreamPauseReason.SECRET_BALLOT,
                )

            val egressClient =
                StreamPollerFakeEgressClient(
                    // Fires exactly where the real race lands: after this tick has requested the stop
                    // of the STALE snapshotted egress id, but BEFORE it has confirmed/finalized --
                    // simulates a concurrent startStream/restartEgressForStream "abandoned" branch
                    // resurrecting this SAME row onto a fresh, actually-publishing egress right here.
                    onStopEgress = { id ->
                        if (id == "EG_stale") {
                            transaction {
                                ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamId }) {
                                    it[livekitEgressId] = "EG_fresh"
                                }
                            }
                        }
                    },
                )
            // EG_stale is genuinely gone (the poller's own StopEgress just above is what actually
            // stopped it) -- listEgress reporting it absent is what lets this tick's confirmation
            // succeed and reach the guarded markPaused write in the first place.
            egressClient.egressListByRoom[roomName] = emptyList()

            buildPoller(egressClient).tick()

            // The NEU-2 fix: markPaused's finalizing write is guarded on the egress id it actually
            // confirmed (EG_stale) still matching the row's CURRENT livekit_egress_id -- since the row
            // now carries EG_fresh, the write is skipped entirely. Without the fix, this assertion
            // fails: the row would read PAUSED, and EG_fresh would never be revisited by anything
            // (handlePaused polls no egress at all).
            val afterFirstTick = streamRow(streamId)
            afterFirstTick[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING
            afterFirstTick[ConferenceStreamTable.livekitEgressId] shouldBe "EG_fresh"
            afterFirstTick[ConferenceStreamTable.pausedAt] shouldBe null

            // A second tick re-reads a FRESH row snapshot (egressId = EG_fresh this time), confirms it
            // for real, and only THEN reaches PAUSED -- proving the fresh egress is never abandoned.
            egressClient.egressListByRoom[roomName] = emptyList()
            buildPoller(egressClient).tick()

            egressClient.stopCalls shouldBe listOf(roomName to "EG_stale", roomName to "EG_fresh")
            val afterSecondTick = streamRow(streamId)
            afterSecondTick[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED
            (afterSecondTick[ConferenceStreamTable.pausedAt] != null) shouldBe true
        }

        test(
            "PAUSING: egressId present -> StopEgress requested, ListEgress still reports a non-terminal status -> " +
                "stays PAUSING for the next tick to retry",
        ) {
            val member = createMember("poller-pausing-confirm-active@example.org")
            val (roomId, roomName) = createRoom(member)
            val streamId =
                createStream(
                    roomId,
                    member,
                    ConferenceStreamStatus.PAUSING,
                    livekitEgressId = "EG_pausing_still_active",
                    pauseReason = ConferenceStreamPauseReason.SECRET_BALLOT,
                )

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] =
                listOf(LiveKitEgressInfo(egressId = "EG_pausing_still_active", status = "EGRESS_ACTIVE"))
            buildPoller(egressClient).tick()

            egressClient.stopCalls shouldBe listOf(roomName to "EG_pausing_still_active")
            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING
        }

        test("PAUSING: auto-stops (finalizeEnded, no StopEgress) once maxDurationMinutes elapsed -- same ceiling as PAUSED") {
            val member = createMember("poller-pausing-maxduration@example.org")
            val (roomId, _) = createRoom(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId =
                createStream(
                    roomId,
                    member,
                    ConferenceStreamStatus.PAUSING,
                    startedAt = startedAt,
                    livekitEgressId = "EG_pausing_maxdur",
                    pauseReason = ConferenceStreamPauseReason.SECRET_BALLOT,
                )

            val fakeNow = startedAt.shiftedByMinutes(11)
            val egressClient = StreamPollerFakeEgressClient()
            val poller = buildPoller(egressClient, maxDurationMinutes = 10, clock = { fakeNow })

            poller.tick()

            egressClient.stopCalls shouldBe emptyList()
            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
        }

        // ── STOPPING ─────────────────────────────────────────────────────

        test("STOPPING: completes an interrupted stop -- best-effort StopEgress, then ENDED") {
            val member = createMember("poller-stopping@example.org")
            val (roomId, roomName) = createRoom(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.STOPPING, livekitEgressId = "EG_stopping")

            val egressClient = StreamPollerFakeEgressClient()
            buildPoller(egressClient).tick()

            egressClient.stopCalls shouldBe listOf(roomName to "EG_stopping")
            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
        }

        test("STOPPING: a StopEgress failure (LiveKitAdminException) is logged and swallowed -- still finalizes to ENDED") {
            val member = createMember("poller-stopping-failure@example.org")
            val (roomId, _) = createRoom(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.STOPPING, livekitEgressId = "EG_stopping_fail")

            val egressClient =
                object : LiveKitEgressClient {
                    override suspend fun startTrackEgress(
                        roomName: String,
                        trackId: String,
                        outputFilepathWithoutExtension: String,
                    ) = error("not used")

                    override suspend fun stopEgress(
                        roomName: String,
                        egressId: String,
                    ): LiveKitEgressInfo = throw LiveKitAdminException("simulated StopEgress failure")

                    override suspend fun listEgress(roomName: String) = emptyList<LiveKitEgressInfo>()

                    override suspend fun startRoomCompositeEgress(
                        roomName: String,
                        layout: ConferenceStreamLayout,
                        latencyMode: ConferenceStreamLatencyMode,
                        rtmpUrls: List<String>,
                    ) = error("not used")

                    override suspend fun startParticipantEgress(
                        roomName: String,
                        identity: String,
                        latencyMode: ConferenceStreamLatencyMode,
                        rtmpUrls: List<String>,
                    ) = error("not used")

                    override suspend fun updateStream(
                        roomName: String,
                        egressId: String,
                        addUrls: List<String>,
                        removeUrls: List<String>,
                    ) = error("not used")
                }
            buildPoller(egressClient).tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
        }

        test(
            "STOPPING: security-audit-round-2 F4 -- force-finalizes to ENDED once maxDurationMinutes has " +
                "elapsed, even though the egress never reports a terminal ListEgress status -- STOPPING " +
                "previously had no upper bound at all and could fail-closed-block secret-ballot casting " +
                "for a meeting forever",
        ) {
            val member = createMember("poller-stopping-maxduration@example.org")
            val (roomId, roomName) = createRoom(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId =
                createStream(
                    roomId,
                    member,
                    ConferenceStreamStatus.STOPPING,
                    startedAt = startedAt,
                    livekitEgressId = "EG_stopping_maxdur",
                )

            val fakeNow = startedAt.shiftedByMinutes(11)
            val egressClient = StreamPollerFakeEgressClient()
            // The egress never leaves the list, i.e. ListEgress never reports it gone/terminal --
            // without the F4 fix this row would stay STOPPING on every single tick, forever.
            egressClient.egressListByRoom[roomName] =
                listOf(LiveKitEgressInfo(egressId = "EG_stopping_maxdur", status = "EGRESS_ACTIVE"))
            val poller = buildPoller(egressClient, maxDurationMinutes = 10, clock = { fakeNow })

            poller.tick()

            val stream = streamRow(streamId)
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
            (stream[ConferenceStreamTable.endedAt] != null) shouldBe true
        }

        test("STOPPING: within maxDurationMinutes, egress never reports terminal -- stays STOPPING, retried on the next tick") {
            val member = createMember("poller-stopping-within-maxduration@example.org")
            val (roomId, roomName) = createRoom(member)
            val streamId = createStream(roomId, member, ConferenceStreamStatus.STOPPING, livekitEgressId = "EG_stopping_within")

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] =
                listOf(LiveKitEgressInfo(egressId = "EG_stopping_within", status = "EGRESS_ACTIVE"))
            buildPoller(egressClient, maxDurationMinutes = 480).tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.STOPPING
        }

        // ── STARTING -- orphan reconciliation ───────────────────────────

        test("STARTING: adopts a matching orphan egress once startupTimeoutSeconds has elapsed") {
            val member = createMember("poller-starting-adopt@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId = createStream(roomId, member, ConferenceStreamStatus.STARTING, startedAt = startedAt, livekitEgressId = null)
            val fingerprint = "rtmp://sink.example.org:1935/live/{ado...pte}"
            createTarget(streamId, destId, fingerprint)

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] =
                listOf(
                    LiveKitEgressInfo(
                        egressId = "EG_adopted",
                        status = "EGRESS_ACTIVE",
                        streamResults = listOf(LiveKitStreamInfo(url = fingerprint, status = "ACTIVE")),
                    ),
                )
            val fakeNow = startedAt.shiftedBySeconds(90)
            buildPoller(egressClient, startupTimeoutSeconds = 60, clock = { fakeNow }).tick()

            val stream = streamRow(streamId)
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
            stream[ConferenceStreamTable.livekitEgressId] shouldBe "EG_adopted"
        }

        test(
            "STARTING: adopts a matching orphan egress while a secret ballot is open on the bound meeting -- " +
                "stays PAUSING/SECRET_BALLOT, never LIVE (review-round finding: handleStarting was missing the " +
                "hasOpenSecretBallot guard handlePaused/restartEgressForStream already had)",
        ) {
            val member = createMember("poller-starting-secretballot@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val committeeId = createCommittee("StreamPoller Vorstand STARTING-Guard")
            val meetingId = createMeeting(committeeId)
            bindRoomToMeeting(roomId, meetingId)
            val motionId = createMotion(committeeId, meetingId, member)
            createOpenSecretElection(meetingId, motionId, member)

            val startedAt = DbClock.nowLocalDateTime()
            val streamId = createStream(roomId, member, ConferenceStreamStatus.STARTING, startedAt = startedAt, livekitEgressId = null)
            val fingerprint = "rtmp://sink.example.org:1935/live/{sec...bal}"
            createTarget(streamId, destId, fingerprint)

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] =
                listOf(
                    LiveKitEgressInfo(
                        egressId = "EG_starting_secretballot",
                        status = "EGRESS_ACTIVE",
                        streamResults = listOf(LiveKitStreamInfo(url = fingerprint, status = "ACTIVE")),
                    ),
                )
            val fakeNow = startedAt.shiftedBySeconds(90)
            buildPoller(egressClient, startupTimeoutSeconds = 60, clock = { fakeNow }).tick()

            val stream = streamRow(streamId)
            // Before the fix, handleStarting wrote `status = LIVE` unconditionally here, leaking a
            // real, publishing egress into a room whose meeting has an open secret ballot -- exactly
            // the fail-closed guarantee ConferenceStreamPauseCoordinator.markPausingForSecretBallot/
            // ConferenceStreamingService.startStream's own D3/§6.3 re-check exist to uphold. The
            // fixed handleStarting re-checks SecretBallotStreamLock.hasOpenSecretBallot in the SAME
            // forUpdate()-locked transaction that adopts the orphan egress.
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING
            stream[ConferenceStreamTable.pauseReason] shouldBe ConferenceStreamPauseReason.SECRET_BALLOT
            // The adopted egress id is still recorded -- never leaked, even though it must not go LIVE.
            stream[ConferenceStreamTable.livekitEgressId] shouldBe "EG_starting_secretballot"
        }

        test("STARTING: not yet past startupTimeoutSeconds -> left alone, no ListEgress call at all") {
            val member = createMember("poller-starting-tooearly@example.org")
            val (roomId, _) = createRoom(member)
            val destId = createDestination(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId = createStream(roomId, member, ConferenceStreamStatus.STARTING, startedAt = startedAt, livekitEgressId = null)
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{too...rly}")

            val egressClient = StreamPollerFakeEgressClient()
            buildPoller(egressClient, startupTimeoutSeconds = 60, clock = { startedAt }).tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.STARTING
            egressClient.listEgressCalls.get() shouldBe 0
        }

        test("STARTING: no matching egress after the timeout -> FAILED (Zeitueberschreitung)") {
            val member = createMember("poller-starting-timeout@example.org")
            val (roomId, roomName) = createRoom(member)
            val destId = createDestination(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId = createStream(roomId, member, ConferenceStreamStatus.STARTING, startedAt = startedAt, livekitEgressId = null)
            createTarget(streamId, destId, "rtmp://sink.example.org:1935/live/{unm...tch}")

            val egressClient = StreamPollerFakeEgressClient()
            egressClient.egressListByRoom[roomName] = emptyList()
            val fakeNow = startedAt.shiftedBySeconds(90)
            buildPoller(egressClient, startupTimeoutSeconds = 60, clock = { fakeNow }).tick()

            val stream = streamRow(streamId)
            stream[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.FAILED
            stream[ConferenceStreamTable.failureReason] shouldBe "Der Stream wurde vom Server beendet (Zeitüberschreitung)."
        }

        test("STARTING: zero target rows -- fast-fails to FAILED without ever calling ListEgress") {
            val member = createMember("poller-starting-notargets@example.org")
            val (roomId, _) = createRoom(member)
            val startedAt = DbClock.nowLocalDateTime()
            val streamId = createStream(roomId, member, ConferenceStreamStatus.STARTING, startedAt = startedAt, livekitEgressId = null)

            val egressClient = StreamPollerFakeEgressClient()
            val fakeNow = startedAt.shiftedBySeconds(90)
            buildPoller(egressClient, startupTimeoutSeconds = 60, clock = { fakeNow }).tick()

            streamRow(streamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.FAILED
            egressClient.listEgressCalls.get() shouldBe 0
        }

        // ── tick() exception safety ──────────────────────────────────────

        test("tick(): an unexpected exception for one stream does not prevent another stream's own progress in the same tick") {
            val member = createMember("poller-exception-safety@example.org")
            val (healthyRoomId, healthyRoomName) = createRoom(member)
            val (brokenRoomId, brokenRoomName) = createRoom(member)
            val destId = createDestination(member)

            val healthyStreamId = createStream(healthyRoomId, member, ConferenceStreamStatus.LIVE, livekitEgressId = "EG_healthy")
            val brokenStreamId = createStream(brokenRoomId, member, ConferenceStreamStatus.LIVE, livekitEgressId = "EG_broken")
            val healthyFingerprint = "rtmp://sink.example.org:1935/live/{hea...thy}"
            createTarget(healthyStreamId, destId, healthyFingerprint)
            createTarget(brokenStreamId, destId, "rtmp://sink.example.org:1935/live/{bro...ken}")

            val egressClient = StreamPollerFakeEgressClient(throwUnexpectedForRoom = brokenRoomName)
            egressClient.egressListByRoom[healthyRoomName] =
                listOf(
                    LiveKitEgressInfo(
                        egressId = "EG_healthy",
                        status = "EGRESS_ACTIVE",
                        streamResults = listOf(LiveKitStreamInfo(url = healthyFingerprint, status = "ACTIVE")),
                    ),
                )
            buildPoller(egressClient).tick()

            // The broken stream's own ListEgress call throws an UNCAUGHT RuntimeException inside
            // handleLive -- tick() must swallow it per-row (see class KDoc "exception-safe at TWO
            // levels") and still refresh the healthy one in the SAME pass.
            streamRow(healthyStreamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
            streamRow(brokenStreamId)[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
        }
    })

private val TZ = TimeZone.currentSystemDefault()

/** Test-only helper -- shifts a [LocalDateTime] by whole minutes using kotlinx-datetime's own Instant arithmetic, same conversion [StreamPoller] itself uses. */
private fun LocalDateTime.shiftedByMinutes(minutes: Long): LocalDateTime = this.toInstant(TZ).plus(minutes.minutes).toLocalDateTime(TZ)

/** Same as [shiftedByMinutes] but for sub-minute shifts -- [StreamPoller]'s STARTING-orphan timeout is seconds-granular, not minutes. */
private fun LocalDateTime.shiftedBySeconds(seconds: Long): LocalDateTime = this.toInstant(TZ).plus(seconds.seconds).toLocalDateTime(TZ)
