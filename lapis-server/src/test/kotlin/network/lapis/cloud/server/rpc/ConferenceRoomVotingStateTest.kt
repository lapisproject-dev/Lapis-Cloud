package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.LiveKitRoomInfo
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ConferenceBreakoutRoomTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.RoomBallotKind
import network.lapis.cloud.shared.domain.RoomBallotStatus
import network.lapis.cloud.shared.domain.RoomVotingStateDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val VOTING_ENABLED_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            else -> null
        }
    }

private const val UNAVAILABLE = "Room voting state is not available"

/**
 * V1.9.24 -- [ConferenceService.getRoomVotingState]: authorization matrix (every denial is the SAME
 * Forbidden + message), status filter, caps, own-flag semantics, ballot-secrecy field reduction, rate
 * limit. Elections are seeded directly into the DB (one motion each: `uq_election_active_motion` allows
 * only one non-aborted election per motion). [afterSpec] hard-deletes everything this file created.
 */
class ConferenceRoomVotingStateTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val committeeIds = mutableListOf<Uuid>()
        val meetingIds = mutableListOf<Uuid>()
        val motionIds = mutableListOf<Uuid>()
        val electionIds = mutableListOf<Uuid>()
        val roomIds = mutableListOf<Uuid>()
        val breakoutIds = mutableListOf<Uuid>()
        val json = Json { ignoreUnknownKeys = false }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (breakoutIds.isNotEmpty()) ConferenceBreakoutRoomTable.deleteWhere { ConferenceBreakoutRoomTable.id inList breakoutIds }
                ConferenceParticipationTable.deleteWhere { ConferenceParticipationTable.roomId inList roomIds }
                ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList roomIds }
                ElectionBallotTable.deleteWhere { ElectionBallotTable.electionId inList electionIds }
                ElectionParticipationTable.deleteWhere { ElectionParticipationTable.electionId inList electionIds }
                ElectionEligibleVoterTable.deleteWhere { ElectionEligibleVoterTable.electionId inList electionIds }
                ElectionTable.deleteWhere { ElectionTable.id inList electionIds }
                MotionTable.deleteWhere { MotionTable.id inList motionIds }
                MeetingTable.deleteWhere { MeetingTable.id inList meetingIds }
                CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
                AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
                MemberTable.deleteWhere { MemberTable.id inList memberIds }
            }
        }

        fun member(
            tag: String,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "VotingState $tag"
                    it[email] = "votingstate-$tag-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    if (status == MemberStatus.FRIEND) it[friendSince] = LocalDate(2026, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            memberIds += id
            return id
        }

        class Fixture(
            val committeeId: Uuid,
            val meetingId: Uuid,
            val creator: Uuid,
        )

        fun fixture(tag: String): Fixture {
            val committeeId = Uuid.random()
            val meetingId = Uuid.random()
            val creator = member("$tag-creator")
            transaction {
                CommitteeTable.insert {
                    it[id] = committeeId
                    it[name] = "VotingState-$tag"
                    it[type] = CommitteeType.EXECUTIVE_BOARD
                    it[description] = "VotingState"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
                MeetingTable.insert {
                    it[id] = meetingId
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "VotingState-Sitzung $tag"
                    it[scheduledAt] = LocalDateTime(2026, 3, 1, 18, 0)
                    it[location] = "Vereinsheim"
                    it[format] = MeetingFormat.IN_PERSON
                    it[status] = MeetingStatus.PLANNED
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = LocalDateTime(2026, 3, 1, 18, 0)
                }
            }
            committeeIds += committeeId
            meetingIds += meetingId
            return Fixture(committeeId, meetingId, creator)
        }

        fun room(
            f: Fixture,
            meetingId: Uuid? = f.meetingId,
            allowGuests: Boolean = false,
            ended: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "VotingState-Raum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-vs-${id.toString().take(40)}"
                    it[createdByMemberId] = f.creator
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[endedAt] = if (ended) DbClock.nowLocalDateTime() else null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = allowGuests
                    it[ConferenceRoomTable.meetingId] = meetingId
                }
            }
            roomIds += id
            return id
        }

        fun participate(
            roomId: Uuid,
            memberId: Uuid,
            left: Boolean = false,
        ) {
            transaction {
                ConferenceParticipationTable.insert {
                    it[id] = Uuid.random()
                    it[ConferenceParticipationTable.roomId] = roomId
                    it[ConferenceParticipationTable.memberId] = memberId
                    it[role] = ConferenceRole.PARTICIPANT
                    it[joinedAt] = DbClock.nowLocalDateTime()
                    it[leftAt] = if (left) DbClock.nowLocalDateTime() else null
                }
            }
        }

        fun hoursAgo(h: Int): LocalDateTime {
            val zone = TimeZone.currentSystemDefault()
            return (DbClock.nowLocalDateTime().toInstant(zone) - h.hours).toLocalDateTime(zone)
        }

        /** Seeds one election (with its own motion) directly; returns the election id. */
        fun election(
            f: Fixture,
            title: String,
            status: ElectionStatus,
            secret: Boolean = false,
            meetingId: Uuid = f.meetingId,
            tallyRunAt: LocalDateTime? = null,
            openedAt: LocalDateTime = DbClock.nowLocalDateTime(),
        ): Uuid {
            val motionId = Uuid.random()
            val electionId = Uuid.random()
            val snapshot = status in setOf(ElectionStatus.OPEN, ElectionStatus.CLOSED, ElectionStatus.TALLIED)
            transaction {
                MotionTable.insert {
                    it[id] = motionId
                    it[targetCommitteeId] = f.committeeId
                    it[MotionTable.title] = "Antrag zu $title"
                    it[rationale] = "R"
                    it[text] = "T"
                    it[submitterMemberId] = f.creator
                    it[MotionTable.status] = MotionStatus.SCHEDULED
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = f.creator
                    it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
                ElectionTable.insert {
                    it[id] = electionId
                    it[ElectionTable.title] = title
                    it[electionType] = ElectionType.YES_NO
                    it[ElectionTable.secret] = secret
                    it[seatCount] = 1
                    it[targetCommitteeId] = null
                    it[targetRole] = null
                    it[requiredMajorityPercent] = 50
                    it[ElectionTable.status] = status
                    it[openedBy] = f.creator
                    it[ElectionTable.openedAt] = openedAt
                    it[votingOpenedAt] = if (snapshot) openedAt else null
                    it[votingClosedAt] = if (status == ElectionStatus.CLOSED || status == ElectionStatus.TALLIED) openedAt else null
                    it[tallyThreshold] = 2
                    it[ElectionTable.tallyRunAt] = tallyRunAt
                    it[ElectionTable.motionId] = motionId
                    it[ElectionTable.meetingId] = meetingId
                    it[activeMotionId] = if (status == ElectionStatus.ABORTED) null else motionId
                }
            }
            motionIds += motionId
            electionIds += electionId
            return electionId
        }

        fun eligible(
            electionId: Uuid,
            memberId: Uuid,
        ) = transaction {
            ElectionEligibleVoterTable.insert {
                it[id] = Uuid.random()
                it[ElectionEligibleVoterTable.electionId] = electionId
                it[ElectionEligibleVoterTable.memberId] = memberId
            }
        }

        fun votedSecret(
            electionId: Uuid,
            memberId: Uuid,
            receipt: String? = null,
        ) = transaction {
            ElectionParticipationTable.insert {
                it[id] = Uuid.random()
                it[votedAt] = DbClock.nowLocalDateTime()
                it[ElectionParticipationTable.electionId] = electionId
                it[ElectionParticipationTable.memberId] = memberId
            }
            if (receipt != null) {
                ElectionBallotTable.insert {
                    it[id] = Uuid.random()
                    it[receiptCode] = receipt
                    it[castAt] = DbClock.nowLocalDateTime()
                    it[ElectionBallotTable.electionId] = electionId
                    it[ElectionBallotTable.memberId] = null
                }
            }
        }

        fun votedOpen(
            electionId: Uuid,
            memberId: Uuid,
        ) = transaction {
            ElectionBallotTable.insert {
                it[id] = Uuid.random()
                it[receiptCode] = "OPEN-${Uuid.random().toString().take(30)}"
                it[castAt] = DbClock.nowLocalDateTime()
                it[ElectionBallotTable.electionId] = electionId
                it[ElectionBallotTable.memberId] = memberId
            }
        }

        suspend fun HttpClient.state(
            roomId: String,
            asMember: Uuid,
        ): HttpResponse = get("/test/room-voting-state?roomId=$roomId") { header("X-Member-Id", asMember.toString()) }

        suspend fun HttpClient.stateOk(
            roomId: Uuid,
            asMember: Uuid,
        ): RoomVotingStateDto {
            val r = state(roomId.toString(), asMember)
            r.status shouldBe HttpStatusCode.OK
            return json.decodeFromString(RoomVotingStateDto.serializer(), r.bodyAsText())
        }

        suspend fun HttpClient.shouldBeUniformlyDenied(
            roomId: String,
            asMember: Uuid,
        ) {
            val r = state(roomId, asMember)
            r.status shouldBe HttpStatusCode.Forbidden
            r.bodyAsText() shouldBe UNAVAILABLE
        }

        fun testApp(
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
            config: ConferenceConfig = VOTING_ENABLED_CONFIG,
            limiter: () -> FederationInboxRateLimiter = { FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes) },
        ) = testApplication {
            application {
                install(StatusPages) { installVotingStateExceptionHandlers() }
                routing { registerVotingStateTestRoutes(config = config, limiter = limiter()) }
            }
            block()
        }

        test("authorization matrix: every denial is the SAME Forbidden with the SAME message, positives succeed") {
            testApp({
                val f = fixture("authz")
                val main = room(f, allowGuests = false)
                val open = room(f, allowGuests = true)
                election(f, "Wahl A", ElectionStatus.OPEN)

                val inRoom = member("inroom").also { participate(main, it) }
                val notInRoom = member("notinroom")
                val left = member("left").also { participate(main, it, left = true) }
                val withdrawn = member("withdrawn", MemberStatus.WITHDRAWN).also { participate(main, it) }
                val friendDenied = member("friend-denied", MemberStatus.FRIEND).also { participate(main, it) }
                val friendOk = member("friend-ok", MemberStatus.FRIEND).also { participate(open, it) }
                val friendNoPart = member("friend-nopart", MemberStatus.FRIEND)
                val guestOk = member("guest-ok", MemberStatus.GUEST).also { participate(open, it) }
                val guestNoPart = member("guest-nopart", MemberStatus.GUEST)

                // Positives.
                client.stateOk(main, inRoom).bound shouldBe true
                client
                    .stateOk(open, friendOk)
                    .ballots
                    .single()
                    .ownEligible shouldBe false
                client
                    .stateOk(open, guestOk)
                    .ballots
                    .single()
                    .ownEligible shouldBe false

                // Room creator without own participation is denied (participation is required for EVERY role).
                client.shouldBeUniformlyDenied(main.toString(), f.creator)
                client.shouldBeUniformlyDenied(main.toString(), notInRoom)
                client.shouldBeUniformlyDenied(main.toString(), left)
                client.shouldBeUniformlyDenied(main.toString(), withdrawn)
                client.shouldBeUniformlyDenied(main.toString(), friendDenied)
                client.shouldBeUniformlyDenied(open.toString(), friendNoPart)
                client.shouldBeUniformlyDenied(open.toString(), guestNoPart)

                // Removed participant: leftAt gets set by removeParticipant -- covered by `left`; a FOREIGN room
                // (caller in main only) is a plain "not in room".
                client.shouldBeUniformlyDenied(open.toString(), inRoom)

                // Unknown / ended / breakout / malformed ids.
                val ended = room(f, ended = true).also { participate(it, inRoom) }
                client.shouldBeUniformlyDenied(Uuid.random().toString(), inRoom)
                client.shouldBeUniformlyDenied(ended.toString(), inRoom)
                client.shouldBeUniformlyDenied("not-a-uuid", inRoom)
                client.shouldBeUniformlyDenied("%20", inRoom)

                val breakoutId = Uuid.random()
                val breakoutName = "lc-vs-breakout-${breakoutId.toString().take(30)}"
                transaction {
                    ConferenceBreakoutRoomTable.insert {
                        it[id] = breakoutId
                        it[parentRoomId] = main
                        it[label] = "Gruppe 1"
                        it[livekitRoomName] = breakoutName
                        it[createdByMemberId] = f.creator
                        it[createdAt] = DbClock.nowLocalDateTime()
                        it[closedAt] = null
                    }
                }
                breakoutIds += breakoutId
                client.shouldBeUniformlyDenied(breakoutId.toString(), inRoom)
                client.shouldBeUniformlyDenied(breakoutName, inRoom)
                // A member sitting in a breakout keeps its OPEN main-room participation and asks with the MAIN room id.
                client.stateOk(main, inRoom).ballots.size shouldBe 1
            })
        }

        test(
            "status filter and ordering: only OPEN, CLOSED and recently TALLIED elections of the room's Sitzung, mapped to neutral statuses",
        ) {
            testApp({
                val f = fixture("status")
                val other = fixture("status-other")
                val r = room(f)
                val me = member("status-me").also { participate(r, it) }
                election(f, "prep", ElectionStatus.PREPARATION)
                election(f, "released", ElectionStatus.CANDIDATE_LIST_RELEASED)
                election(f, "tallied-13h", ElectionStatus.TALLIED, tallyRunAt = hoursAgo(13))
                election(f, "aborted", ElectionStatus.ABORTED)
                election(other, "foreign-open", ElectionStatus.OPEN, meetingId = other.meetingId)
                election(f, "tallied-1h", ElectionStatus.TALLIED, tallyRunAt = hoursAgo(1))
                election(f, "closed", ElectionStatus.CLOSED)
                election(f, "open", ElectionStatus.OPEN)

                val s = client.stateOk(r, me)
                s.bound shouldBe true
                s.truncated shouldBe false
                s.ballots.map { it.title } shouldBe listOf("open", "closed", "tallied-1h")
                s.ballots.map { it.status } shouldBe
                    listOf(RoomBallotStatus.OPEN, RoomBallotStatus.CLOSED_AWAITING_TALLY, RoomBallotStatus.DECIDED)
                s.ballots.all { it.kind == RoomBallotKind.ELECTION } shouldBe true
                s.ballots.first().motionTitle shouldBe "Antrag zu open"
            })
        }

        test("cap: 25 open elections -> 20 delivered, truncated; 20 open + 5 tallied -> only open ones delivered") {
            testApp({
                val f = fixture("cap")
                val r = room(f)
                val me = member("cap-me").also { participate(r, it) }
                repeat(25) { election(f, "open-$it", ElectionStatus.OPEN) }
                val s = client.stateOk(r, me)
                s.ballots.size shouldBe 20
                s.truncated shouldBe true

                val f2 = fixture("cap2")
                val r2 = room(f2)
                val me2 = member("cap2-me").also { participate(r2, it) }
                repeat(20) { election(f2, "open-$it", ElectionStatus.OPEN) }
                repeat(5) { election(f2, "tallied-$it", ElectionStatus.TALLIED, tallyRunAt = hoursAgo(1)) }
                val s2 = client.stateOk(r2, me2)
                s2.ballots.size shouldBe 20
                s2.ballots.all { it.status == RoomBallotStatus.OPEN } shouldBe true
            })
        }

        test("unbound room: bound=false, empty list, no error") {
            testApp({
                val f = fixture("unbound")
                val r = room(f, meetingId = null)
                val me = member("unbound-me").also { participate(r, it) }
                election(f, "irrelevant", ElectionStatus.OPEN)
                val s = client.stateOk(r, me)
                s.bound shouldBe false
                s.ballots shouldBe emptyList()
                s.truncated shouldBe false
            })
        }

        test("own flags: secret and open elections report only the caller's eligibility and vote status") {
            testApp({
                val f = fixture("own")
                val r = room(f)
                val notEligible = member("own-noel").also { participate(r, it) }
                val eligibleNotVoted = member("own-el").also { participate(r, it) }
                val voted = member("own-voted").also { participate(r, it) }
                val secret = election(f, "secret", ElectionStatus.OPEN, secret = true)
                val open = election(f, "open", ElectionStatus.OPEN, secret = false)
                listOf(eligibleNotVoted, voted).forEach {
                    eligible(secret, it)
                    eligible(open, it)
                }
                votedSecret(secret, voted)
                votedOpen(open, voted)

                fun List<network.lapis.cloud.shared.domain.RoomBallotDto>.byTitle(t: String) = single { it.title == t }

                client.stateOk(r, notEligible).ballots.forEach {
                    it.ownEligible shouldBe false
                    it.ownHasVoted shouldBe false
                }
                client.stateOk(r, eligibleNotVoted).ballots.forEach {
                    it.ownEligible shouldBe true
                    it.ownHasVoted shouldBe false
                }
                val v = client.stateOk(r, voted).ballots
                v.byTitle("secret").also {
                    it.secret shouldBe true
                    it.ownEligible shouldBe true
                    it.ownHasVoted shouldBe true
                }
                v.byTitle("open").also {
                    it.secret shouldBe false
                    it.ownHasVoted shouldBe true
                }
            })
        }

        test("non-members are never eligible, even with an (artificially inserted) snapshot row") {
            testApp({
                val f = fixture("nonmember")
                val r = room(f, allowGuests = true)
                val e = election(f, "snap", ElectionStatus.OPEN)
                val guest = member("nm-guest", MemberStatus.GUEST).also { participate(r, it) }
                val friend = member("nm-friend", MemberStatus.FRIEND).also { participate(r, it) }
                eligible(e, guest)
                eligible(e, friend)
                client
                    .stateOk(r, guest)
                    .ballots
                    .single()
                    .ownEligible shouldBe false
                client
                    .stateOk(r, friend)
                    .ballots
                    .single()
                    .ownEligible shouldBe false
            })
        }

        test("field reduction: exact JSON keys, and no receipt code or foreign member id after a secret vote") {
            testApp({
                val f = fixture("fields")
                val r = room(f)
                val me = member("fields-me").also { participate(r, it) }
                val other = member("fields-other").also { participate(r, it) }
                val secret = election(f, "geheim", ElectionStatus.OPEN, secret = true)
                eligible(secret, me)
                eligible(secret, other)
                votedSecret(secret, other, receipt = "RCPT-SECRET-0123456789")
                val body = client.state(r.toString(), me).bodyAsText()
                val root: JsonObject = json.parseToJsonElement(body).jsonObject
                root.keys shouldBe setOf("roomId", "bound", "ballots", "truncated")
                root["ballots"]!!
                    .jsonArray
                    .single()
                    .jsonObject.keys shouldBe
                    setOf("kind", "id", "motionId", "motionTitle", "title", "status", "secret", "ownEligible", "ownHasVoted")
                body shouldNotContain "RCPT-SECRET-0123456789"
                body shouldNotContain other.toString()
                body shouldNotContain me.toString()
                body shouldNotContain f.meetingId.toString()
            })
        }

        test(
            "rate limit: exhausting the own budget yields the rate error (Conflict), not the uniform denial; disabled conference likewise",
        ) {
            testApp(
                block = {
                    val f = fixture("rate")
                    val r = room(f)
                    val me = member("rate-me").also { participate(r, it) }
                    client.state(r.toString(), me).status shouldBe HttpStatusCode.OK
                    client.state(r.toString(), me).status shouldBe HttpStatusCode.OK
                    val third = client.state(r.toString(), me)
                    third.status shouldBe HttpStatusCode.Conflict
                    third.bodyAsText() shouldNotBe UNAVAILABLE
                },
                limiter = { FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes) },
            )
            testApp(
                block = {
                    val f = fixture("disabled")
                    val r = room(f)
                    val me = member("disabled-me").also { participate(r, it) }
                    val resp = client.state(r.toString(), me)
                    resp.status shouldBe HttpStatusCode.Conflict
                    resp.bodyAsText() shouldNotBe UNAVAILABLE
                },
                config = ConferenceConfig.load { null },
            )
        }
    })

private object VotingStateStubLiveKit : LiveKitAdminClient {
    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
    ): LiveKitRoomInfo = error("not used")

    override suspend fun deleteRoom(name: String): Unit = error("not used")

    override suspend fun listRooms(): List<LiveKitRoomInfo> = error("not used")

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> = error("not used")

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ): Unit = error("not used")
}

private fun StatusPagesConfig.installVotingStateExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}

private fun Route.registerVotingStateTestRoutes(
    config: ConferenceConfig,
    limiter: FederationInboxRateLimiter,
) {
    get("/test/room-voting-state") {
        val service =
            ConferenceService(
                call = call,
                liveKitAdminClient = VotingStateStubLiveKit,
                createRoomRateLimiter = LoginRateLimiter(),
                config = config,
                conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(),
                roomVotingStateRateLimiter = limiter,
            )
        val dto = service.getRoomVotingState(call.request.queryParameters["roomId"]!!)
        call.respondText(Json.encodeToString(RoomVotingStateDto.serializer(), dto))
    }
}
