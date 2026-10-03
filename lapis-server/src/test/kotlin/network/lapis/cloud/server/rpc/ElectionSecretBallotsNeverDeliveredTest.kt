package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.conference.NoOpSecretBallotStreamGuard
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionBallotDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val BALLOT_COUNTS = listOf(0, 1, 2, 5, 20)

/**
 * V1.9.46 -- the single ballots of a SECRET election are never delivered: for every election type, ballot count, status and caller
 * role (plain member, board account, election board member) `listElectionBallots` is empty. Status, ballots and selections are
 * written straight into the database (not driven through the phases), so the gate is proven even for states in which a ballot
 * could not exist in practice (defense in depth). The open-ballot election is the control: it keeps its named list.
 */
class ElectionSecretBallotsNeverDeliveredTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val committeeIds = mutableListOf<Uuid>()
        val meetingIds = mutableListOf<Uuid>()
        val motionIds = mutableListOf<Uuid>()
        val electionIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                val ballotIds =
                    ElectionBallotTable
                        .selectAll()
                        .where { ElectionBallotTable.electionId inList electionIds }
                        .map { it[ElectionBallotTable.id] }
                ElectionBallotSelectionTable.deleteWhere { ElectionBallotSelectionTable.ballotId inList ballotIds }
                ElectionBallotTable.deleteWhere { ElectionBallotTable.electionId inList electionIds }
                ElectionOptionTable.deleteWhere { ElectionOptionTable.electionId inList electionIds }
                ElectionBoardMemberTable.deleteWhere { ElectionBoardMemberTable.electionId inList electionIds }
                ElectionTable.deleteWhere { ElectionTable.id inList electionIds }
                MotionTable.deleteWhere { MotionTable.id inList motionIds }
                MeetingTable.deleteWhere { MeetingTable.id inList meetingIds }
                CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList committeeIds }
                CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
                AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
                MemberTable.deleteWhere { MemberTable.id inList memberIds }
            }
        }

        fun member(
            tag: String,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Secret-Ballots $tag"
                    it[email] = "secret-ballots-$tag-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            memberIds += id
            return id
        }

        val committeeId = Uuid.random()
        val meetingId = Uuid.random()
        lateinit var plainMember: Uuid
        lateinit var boardAccount: Uuid
        lateinit var committeeMember: Uuid
        lateinit var openVoters: List<Uuid>

        beforeSpec {
            transaction {
                CommitteeTable.insert {
                    it[id] = committeeId
                    it[name] = "Secret-Ballots-Gremium"
                    it[type] = CommitteeType.EXECUTIVE_BOARD
                    it[description] = "Secret-Ballots"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
                MeetingTable.insert {
                    it[id] = meetingId
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "Secret-Ballots-Sitzung"
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
            plainMember = member("plain")
            boardAccount = member("board", AccountRole.BOARD)
            committeeMember = member("committee")
            openVoters = (1..20).map { member("voter-$it") }
        }

        /** One election with [ballots] ballots written directly. Secret ballots carry no member; open ones one distinct voter each. */
        fun seed(
            type: ElectionType,
            status: ElectionStatus,
            secret: Boolean,
            ballots: Int,
        ): Uuid {
            val motionId = Uuid.random()
            val electionId = Uuid.random()
            val creator = plainMember
            val at = DbClock.nowLocalDateTime()
            val snapshot = status in setOf(ElectionStatus.OPEN, ElectionStatus.CLOSED, ElectionStatus.TALLIED)
            transaction {
                MotionTable.insert {
                    it[id] = motionId
                    it[targetCommitteeId] = committeeId
                    it[title] = "Secret-Ballots-Antrag"
                    it[rationale] = "R"
                    it[text] = "T"
                    it[submitterMemberId] = creator
                    it[MotionTable.status] = MotionStatus.SCHEDULED
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = creator
                    it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
                ElectionTable.insert {
                    it[id] = electionId
                    it[title] = "Secret-Ballots-${type.name}-${status.name}"
                    it[electionType] = type
                    it[ElectionTable.secret] = secret
                    it[seatCount] = 1
                    it[targetCommitteeId] = null
                    it[targetRole] = null
                    it[requiredMajorityPercent] = 50
                    it[ElectionTable.status] = status
                    it[openedBy] = creator
                    it[openedAt] = at
                    it[votingOpenedAt] = if (snapshot) at else null
                    it[votingClosedAt] = if (status == ElectionStatus.CLOSED || status == ElectionStatus.TALLIED) at else null
                    it[tallyThreshold] = 2
                    it[tallyRunAt] = null
                    it[ElectionTable.motionId] = motionId
                    it[ElectionTable.meetingId] = meetingId
                    it[activeMotionId] = if (status == ElectionStatus.ABORTED) null else motionId
                }
                val optionIds =
                    (0..2).map { position ->
                        Uuid.random().also { optionId ->
                            ElectionOptionTable.insert {
                                it[id] = optionId
                                it[label] = "Option-$position"
                                it[ElectionOptionTable.position] = position
                                it[candidacyId] = null
                                it[ElectionOptionTable.electionId] = electionId
                            }
                        }
                    }
                repeat(ballots) { index ->
                    val ballotId = Uuid.random()
                    ElectionBallotTable.insert {
                        it[id] = ballotId
                        it[receiptCode] = "SB-${ballotId.toString().take(30)}"
                        it[castAt] = at
                        it[ElectionBallotTable.electionId] = electionId
                        it[memberId] = if (secret) null else openVoters[index]
                    }
                    ElectionBallotSelectionTable.insert {
                        it[id] = Uuid.random()
                        it[ElectionBallotSelectionTable.ballotId] = ballotId
                        it[optionId] = optionIds[index % optionIds.size]
                    }
                }
                ElectionBoardMemberTable.insert {
                    it[id] = Uuid.random()
                    it[appointedAt] = at
                    it[ElectionBoardMemberTable.electionId] = electionId
                    it[memberId] = committeeMember
                }
            }
            motionIds += motionId
            electionIds += electionId
            return electionId
        }

        fun disclosed(electionId: Uuid): List<ElectionBallotDto> =
            transaction {
                val row = ElectionTable.selectAll().where { ElectionTable.id eq electionId }.single()
                disclosedElectionBallots(row = row) { memberId -> memberId?.let { "Name $it" } }
            }

        fun testApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) {
                        exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                    }
                    routing {
                        get("/t/list-ballots/{id}") {
                            val service = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
                            val list = service.listElectionBallots(call.parameters["id"]!!)
                            call.respondText(Json.encodeToString(ListSerializer(ElectionBallotDto.serializer()), list))
                        }
                    }
                }
                block()
            }

        for (type in ElectionType.entries) {
            for (status in ElectionStatus.entries) {
                test("secret $type in $status: no single ballot at any count, for member, board account and election board member") {
                    val ids = BALLOT_COUNTS.associateWith { seed(type, status, secret = true, ballots = it) }
                    // the rows really exist (the gate, not an empty table, is what produces the empty list)
                    ids.forEach { (count, id) ->
                        transaction {
                            ElectionBallotTable
                                .selectAll()
                                .where { ElectionBallotTable.electionId eq id }
                                .count()
                                .toInt() shouldBe count
                        }
                        disclosed(id).shouldBeEmpty()
                    }
                    testApp {
                        ids.forEach { (_, id) ->
                            listOf(plainMember, boardAccount, committeeMember).forEach { caller ->
                                val response = client.get("/t/list-ballots/$id") { header("X-Member-Id", caller.toString()) }
                                response.status shouldBe HttpStatusCode.OK
                                response.bodyAsText() shouldBe "[]"
                            }
                        }
                    }
                }
            }
        }

        for (type in ElectionType.entries) {
            for (status in listOf(ElectionStatus.OPEN, ElectionStatus.TALLIED)) {
                test("control: open-ballot $type in $status keeps its named list, ordered, with labels") {
                    listOf(1, 5).forEach { count ->
                        val id = seed(type, status, secret = false, ballots = count)
                        val list = disclosed(id)
                        list.size shouldBe count
                        list.all { it.memberId != null && it.memberDisplayName != null && it.id.isNotBlank() } shouldBe true
                        list.all { it.selectedOptionLabels.size == 1 && it.selectedOptionLabels.single().startsWith("Option-") } shouldBe
                            true
                        list.map { it.castAt }.zipWithNext().all { (a, b) -> a <= b } shouldBe true
                    }
                }
            }
        }

        test("an unknown election id answers NotFound regardless of secrecy (no existence oracle)") {
            val secretId = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, ballots = 2)
            val openId = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = false, ballots = 2)
            testApp {
                val unknown = client.get("/t/list-ballots/${Uuid.random()}") { header("X-Member-Id", plainMember.toString()) }
                unknown.status shouldBe HttpStatusCode.NotFound
                val garbage = client.get("/t/list-ballots/not-a-uuid") { header("X-Member-Id", plainMember.toString()) }
                garbage.status shouldBe HttpStatusCode.NotFound
                // both known ids answer 200, so the secret flag is not what separates found from not found
                client.get("/t/list-ballots/$secretId") { header("X-Member-Id", plainMember.toString()) }.status shouldBe HttpStatusCode.OK
                client.get("/t/list-ballots/$openId") { header("X-Member-Id", plainMember.toString()) }.status shouldBe HttpStatusCode.OK
                unknown.bodyAsText() shouldNotBe ""
            }
        }
    })
