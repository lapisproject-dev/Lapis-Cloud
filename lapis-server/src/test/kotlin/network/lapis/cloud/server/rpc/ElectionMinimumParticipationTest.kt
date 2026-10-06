package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditHashChain
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.conference.NoOpSecretBallotStreamGuard
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.routes.PublicApiResolutionsPageDto
import network.lapis.cloud.server.routes.registerPublicApiRoutes
import network.lapis.cloud.server.security.ApiKeyStore
import network.lapis.cloud.server.shouldNotContainNumber
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.AuditLogEntryDto
import network.lapis.cloud.shared.domain.AuditLogListQuery
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.DisclosureRules
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionResultDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MeetingDetailDto
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionDto
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionSnapshot
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")

private const val YES = 0
private const val NO = 1
private const val ABSTAIN = 2

/** A seeded election plus its option ids in position order. */
private class Seeded(
    val id: Uuid,
    val optionIds: List<Uuid>,
    val receiptCodes: List<String>,
)

/**
 * V1.9.53 -- minimum participation for secret elections. Below [DisclosureRules.MIN_ANONYMOUS_RESPONSES] ballots no figure of a
 * SECRET election leaves the server, on any read path and for any role; the decision (winners, tie, majority) is unchanged.
 * Elections are seeded straight into the database, so every ballot count is exact and the open-ballot twin of each case (same
 * ballots) is the control that proves the decision did not change.
 */
class ElectionMinimumParticipationTest :
    FunSpec({
        val minimum = DisclosureRules.MIN_ANONYMOUS_RESPONSES
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
                MotionTable.update({ MotionTable.id inList motionIds }) { it[resolutionId] = null }
                ElectionTable.update({ ElectionTable.id inList electionIds }) { it[resolutionId] = null }
                ResolutionTable.deleteWhere { ResolutionTable.electionId inList electionIds }
                val ballotIds =
                    ElectionBallotTable
                        .selectAll()
                        .where { ElectionBallotTable.electionId inList electionIds }
                        .map { it[ElectionBallotTable.id] }
                ElectionBallotSelectionTable.deleteWhere { ElectionBallotSelectionTable.ballotId inList ballotIds }
                ElectionBallotTable.deleteWhere { ElectionBallotTable.electionId inList electionIds }
                ElectionTallyApprovalTable.deleteWhere { ElectionTallyApprovalTable.electionId inList electionIds }
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
                    it[displayName] = "Min-Participation $tag"
                    it[email] = "min-participation-$tag-$id@example.org"
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
        lateinit var electionBoardMember: Uuid
        lateinit var electionBoardMember2: Uuid
        lateinit var openVoters: List<Uuid>

        beforeSpec {
            transaction {
                CommitteeTable.insert {
                    it[id] = committeeId
                    it[name] = "Min-Participation-Gremium"
                    it[type] = CommitteeType.EXECUTIVE_BOARD
                    it[description] = "Min-Participation"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
                MeetingTable.insert {
                    it[id] = meetingId
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "Min-Participation-Sitzung"
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
            // Actors of audit entries are seeded members: the append-only audit log references them, so they must outlive this spec.
            boardAccount = ADMIN_ID
            val seededActors =
                transaction {
                    MemberTable
                        .selectAll()
                        .where { MemberTable.id neq ADMIN_ID }
                        .limit(2)
                        .map { it[MemberTable.id] }
                }
            electionBoardMember = seededActors[0]
            electionBoardMember2 = seededActors[1]
            openVoters = (1..12).map { member("voter-$it") }
        }

        /**
         * One election written directly. [ballots] holds, per ballot, the selected option indexes (YES/NO/ABSTAIN for YES_NO).
         * Secret ballots carry no member; open ones one distinct voter each.
         */
        fun seed(
            type: ElectionType,
            status: ElectionStatus,
            secret: Boolean,
            ballots: List<List<Int>>,
            optionCount: Int = 3,
            seatCount: Int = 1,
            approved: Boolean = false,
        ): Seeded {
            val motionId = Uuid.random()
            val electionId = Uuid.random()
            val at = DbClock.nowLocalDateTime()
            val snapshot = status in setOf(ElectionStatus.OPEN, ElectionStatus.CLOSED, ElectionStatus.TALLIED)
            val labels =
                if (type == ElectionType.YES_NO) listOf("YES", "NO", "ABSTAIN") else (0 until optionCount).map { "Kandidat-$it" }
            val optionIds = labels.map { Uuid.random() }
            val codes =
                ballots.indices.map {
                    Uuid
                        .random()
                        .toString()
                        .replace("-", "")
                        .take(27)
                }
            transaction {
                MotionTable.insert {
                    it[id] = motionId
                    it[targetCommitteeId] = committeeId
                    it[title] = "Min-Participation-Antrag"
                    it[rationale] = "R"
                    it[text] = "T"
                    it[submitterMemberId] = plainMember
                    it[MotionTable.status] = MotionStatus.SCHEDULED
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = plainMember
                    it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
                ElectionTable.insert {
                    it[id] = electionId
                    it[title] = "Min-Participation-${type.name}-${status.name}"
                    it[electionType] = type
                    it[ElectionTable.secret] = secret
                    it[ElectionTable.seatCount] = seatCount
                    it[targetCommitteeId] = null
                    it[targetRole] = null
                    it[requiredMajorityPercent] = 50
                    it[ElectionTable.status] = status
                    it[openedBy] = plainMember
                    it[openedAt] = at
                    it[votingOpenedAt] = if (snapshot) at else null
                    it[votingClosedAt] = if (status == ElectionStatus.CLOSED || status == ElectionStatus.TALLIED) at else null
                    it[tallyThreshold] = 2
                    it[tallyRunAt] = null
                    it[ElectionTable.motionId] = motionId
                    it[ElectionTable.meetingId] = meetingId
                    it[activeMotionId] = if (status == ElectionStatus.ABORTED) null else motionId
                }
                labels.forEachIndexed { position, label ->
                    ElectionOptionTable.insert {
                        it[id] = optionIds[position]
                        it[ElectionOptionTable.label] = label
                        it[ElectionOptionTable.position] = position
                        it[candidacyId] = null
                        it[ElectionOptionTable.electionId] = electionId
                    }
                }
                ballots.forEachIndexed { index, selection ->
                    val ballotId = Uuid.random()
                    ElectionBallotTable.insert {
                        it[id] = ballotId
                        it[receiptCode] = codes[index]
                        it[castAt] = at
                        it[ElectionBallotTable.electionId] = electionId
                        it[memberId] = if (secret) null else openVoters[index]
                    }
                    selection.forEach { optionIndex ->
                        ElectionBallotSelectionTable.insert {
                            it[id] = Uuid.random()
                            it[ElectionBallotSelectionTable.ballotId] = ballotId
                            it[optionId] = optionIds[optionIndex]
                        }
                    }
                }
                ElectionBoardMemberTable.insert {
                    it[id] = Uuid.random()
                    it[appointedAt] = at
                    it[ElectionBoardMemberTable.electionId] = electionId
                    it[memberId] = electionBoardMember
                }
                ElectionBoardMemberTable.insert {
                    it[id] = Uuid.random()
                    it[appointedAt] = at
                    it[ElectionBoardMemberTable.electionId] = electionId
                    it[memberId] = electionBoardMember2
                }
                if (approved) {
                    listOf(electionBoardMember, electionBoardMember2).forEach { approver ->
                        ElectionTallyApprovalTable.insert {
                            it[id] = Uuid.random()
                            it[ElectionTallyApprovalTable.electionId] = electionId
                            it[memberId] = approver
                            it[approvedAt] = at
                        }
                    }
                }
            }
            motionIds += motionId
            electionIds += electionId
            return Seeded(id = electionId, optionIds = optionIds, receiptCodes = codes)
        }

        fun yesNo(vararg answers: Int): List<List<Int>> = answers.map { listOf(it) }

        val json = Json { ignoreUnknownKeys = true }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                        exception<BadRequestException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.BadRequest)
                        }
                    }
                    routing {
                        get("/t/result/{id}") {
                            val result =
                                ElectionService(
                                    call = call,
                                    streamGuard = NoOpSecretBallotStreamGuard,
                                ).getElectionResult(call.parameters["id"]!!)
                            call.respondText(json.encodeToString(ElectionResultDto.serializer(), result))
                        }
                        post("/t/tally/{id}") {
                            val result =
                                ElectionService(
                                    call = call,
                                    streamGuard = NoOpSecretBallotStreamGuard,
                                ).tally(call.parameters["id"]!!)
                            call.respondText(json.encodeToString(ElectionResultDto.serializer(), result))
                        }
                        get("/t/election/{id}") {
                            val election =
                                ElectionService(
                                    call = call,
                                    streamGuard = NoOpSecretBallotStreamGuard,
                                ).getElection(call.parameters["id"]!!)
                            call.respondText(json.encodeToString(ElectionDto.serializer(), election))
                        }
                        get("/t/elections") {
                            val list = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard).listElections()
                            call.respondText(json.encodeToString(ListSerializer(ElectionDto.serializer()), list))
                        }
                        get("/t/verify-receipt/{id}") {
                            val r =
                                ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
                                    .verifyReceipt(
                                        electionId = call.parameters["id"]!!,
                                        receiptCode = call.request.queryParameters["code"]!!,
                                    )
                            call.respondText("${r.found}:${r.optionLabel ?: ""}:${r.counted}")
                        }
                        get("/t/resolutions/{meetingId}") {
                            val list = GovernanceService(call = call).listResolutions(meetingId = call.parameters["meetingId"]!!)
                            call.respondText(json.encodeToString(ListSerializer(ResolutionDto.serializer()), list))
                        }
                        get("/t/meeting/{meetingId}") {
                            val detail = GovernanceService(call = call).getMeetingDetail(call.parameters["meetingId"]!!)
                            call.respondText(json.encodeToString(MeetingDetailDto.serializer(), detail))
                        }
                        get("/t/audit/{entityId}") {
                            val list =
                                AuditLogService(call).listAuditLog(
                                    AuditLogListQuery(entityType = AuditEntityType.RESOLUTION, entityId = call.parameters["entityId"]!!),
                                )
                            call.respondText(json.encodeToString(ListSerializer(AuditLogEntryDto.serializer()), list))
                        }
                        get("/t/audit-entry/{id}") {
                            val entry = AuditLogService(call).getAuditLogEntry(call.parameters["id"]!!)
                            call.respondText(json.encodeToString(AuditLogEntryDto.serializer(), entry))
                        }
                        registerPublicApiRoutes(preAuthRateLimiter = generousLimiter(), postAuthRateLimiter = generousLimiter())
                    }
                }
                block()
            }

        suspend fun HttpClient.read(
            path: String,
            caller: Uuid,
        ): String {
            val response = get(path) { header("X-Member-Id", caller.toString()) }
            response.status shouldBe HttpStatusCode.OK
            return response.bodyAsText()
        }

        suspend fun HttpClient.result(
            id: Uuid,
            caller: Uuid = plainMember,
        ): ElectionResultDto = json.decodeFromString(ElectionResultDto.serializer(), read("/t/result/$id", caller))

        /** The winners as option positions, independent of the random option ids of each seeded election. */
        fun winnerPositions(
            seeded: Seeded,
            result: ElectionResultDto,
        ): List<Int> = result.winnerOptionIds.map { id -> seeded.optionIds.indexOf(Uuid.parse(id)) }.sorted()

        fun withheldExpected(
            secret: Boolean,
            ballots: Int,
        ) = secret && ballots < minimum

        val yesNoPattern = listOf(YES, YES, NO, ABSTAIN, YES, NO)

        // -- the matrix: secret and open x 0, 1, 4, 5, 6 ballots --------------------------------------------------------------

        for (count in listOf(0, 1, 4, 5, 6)) {
            for (secret in listOf(true, false)) {
                test(
                    "YES_NO result, secret=$secret, $count ballots: figures withheld iff secret and below the minimum, decision unchanged",
                ) {
                    val ballots = yesNo(*yesNoPattern.take(count).toIntArray())
                    val subject = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = secret, ballots = ballots)
                    val twin = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = false, ballots = ballots)
                    testApp {
                        val result = client.result(subject.id)
                        val control = client.result(twin.id)
                        result.figuresWithheld shouldBe withheldExpected(secret, count)
                        result.minimumResponses shouldBe minimum
                        result.tie shouldBe control.tie
                        result.majorityMet shouldBe control.majorityMet
                        winnerPositions(subject, result) shouldBe winnerPositions(twin, control)
                        if (result.figuresWithheld) {
                            result.perOptionVotes.isEmpty() shouldBe true
                        } else {
                            result.perOptionVotes.values.sum() shouldBe count
                        }
                        control.figuresWithheld shouldBe false
                    }
                }
            }
        }

        test("the abstention counts as a ballot: 2 yes + 2 abstentions is 4 (withheld), 3 yes + 2 abstentions is 5 (disclosed)") {
            val four = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, ballots = yesNo(YES, YES, ABSTAIN, ABSTAIN))
            val five = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, ballots = yesNo(YES, YES, YES, ABSTAIN, ABSTAIN))
            testApp {
                client.result(four.id).figuresWithheld shouldBe true
                val disclosed = client.result(five.id)
                disclosed.figuresWithheld shouldBe false
                disclosed.perOptionVotes.getValue(five.optionIds[ABSTAIN].toString()) shouldBe 2
            }
        }

        test("YES_NO tie and rejection keep their decision while the figures are withheld") {
            val tie = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, ballots = yesNo(YES, NO))
            val rejected = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, ballots = yesNo(NO, NO, YES))
            testApp {
                val tieResult = client.result(tie.id)
                tieResult.figuresWithheld shouldBe true
                tieResult.tie shouldBe true
                tieResult.winnerOptionIds.shouldBeEmpty()
                val rejectedResult = client.result(rejected.id)
                rejectedResult.figuresWithheld shouldBe true
                rejectedResult.tie shouldBe false
                rejectedResult.majorityMet shouldBe false
                winnerPositions(rejected, rejectedResult) shouldBe listOf(NO)
            }
        }

        test("SINGLE_CHOICE: contested with a majority, contested without a winner, uncontested at 0 ballots") {
            val contestedWon =
                seed(
                    ElectionType.SINGLE_CHOICE,
                    ElectionStatus.TALLIED,
                    secret = true,
                    ballots = listOf(listOf(0), listOf(0), listOf(0), listOf(1)),
                )
            val contestedTie =
                seed(ElectionType.SINGLE_CHOICE, ElectionStatus.TALLIED, secret = true, ballots = listOf(listOf(0), listOf(1), listOf(2)))
            val uncontested =
                seed(ElectionType.SINGLE_CHOICE, ElectionStatus.TALLIED, secret = true, ballots = emptyList(), optionCount = 1)
            val twinWon =
                seed(
                    ElectionType.SINGLE_CHOICE,
                    ElectionStatus.TALLIED,
                    secret = false,
                    ballots = listOf(listOf(0), listOf(0), listOf(0), listOf(1)),
                )
            testApp {
                val won = client.result(contestedWon.id)
                won.figuresWithheld shouldBe true
                won.tie shouldBe false
                winnerPositions(contestedWon, won) shouldBe winnerPositions(twinWon, client.result(twinWon.id))
                winnerPositions(contestedWon, won) shouldBe listOf(0)
                val tie = client.result(contestedTie.id)
                tie.figuresWithheld shouldBe true
                tie.tie shouldBe true
                tie.winnerOptionIds.shouldBeEmpty()
                client.result(uncontested.id).figuresWithheld shouldBe true
            }
        }

        test("MULTI_CHOICE: the tie at the seat boundary is reported without figures") {
            val ballots = listOf(listOf(0, 1), listOf(0, 2), listOf(1, 2))
            val subject = seed(ElectionType.MULTI_CHOICE, ElectionStatus.TALLIED, secret = true, ballots = ballots, seatCount = 2)
            val twin = seed(ElectionType.MULTI_CHOICE, ElectionStatus.TALLIED, secret = false, ballots = ballots, seatCount = 2)
            testApp {
                val result = client.result(subject.id)
                val control = client.result(twin.id)
                result.figuresWithheld shouldBe true
                result.tie shouldBe control.tie
                result.perOptionVotes.isEmpty() shouldBe true
            }
        }

        test("MULTI_CHOICE withheld: winners are listed in ballot position order, not in vote-count order") {
            // Position 2 has the most votes (4), position 0 the second most (3): a count ranking would be [2, 0].
            val ballots = listOf(listOf(2, 0), listOf(2, 0), listOf(2, 0), listOf(2, 1))
            val subject = seed(ElectionType.MULTI_CHOICE, ElectionStatus.TALLIED, secret = true, ballots = ballots, seatCount = 2)
            testApp {
                val result = client.result(subject.id)
                result.figuresWithheld shouldBe true
                result.winnerOptionIds.map { id -> subject.optionIds.indexOf(Uuid.parse(id)) } shouldBe listOf(0, 2)
            }
        }

        test("no role is an exception: member, board account and election board member all get the withheld form") {
            val subject = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, ballots = yesNo(YES, YES, NO))
            testApp {
                listOf(plainMember, boardAccount, electionBoardMember).forEach { caller ->
                    val raw = client.read("/t/result/${subject.id}", caller)
                    val result = json.decodeFromString(ElectionResultDto.serializer(), raw)
                    result.figuresWithheld shouldBe true
                    raw shouldContain "\"perOptionVotes\":{}"
                    val election = client.read("/t/election/${subject.id}", caller)
                    Regex("\"voteCount\":[1-9]").containsMatchIn(election) shouldBe false
                }
            }
        }

        // -- getElection / listElections ----------------------------------------------------------------------------------

        test("getElection and listElections: voteCount is 0 with the flag when withheld, real counts otherwise, no flag before TALLIED") {
            val withheld = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, ballots = yesNo(YES, YES, NO))
            val closed = seed(ElectionType.YES_NO, ElectionStatus.CLOSED, secret = true, ballots = yesNo(YES, YES, NO))
            val disclosed = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, ballots = yesNo(YES, YES, YES, NO, ABSTAIN))
            val openOne = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = false, ballots = yesNo(YES))
            testApp {
                fun election(raw: String) = json.decodeFromString(ElectionDto.serializer(), raw)
                val w = election(client.read("/t/election/${withheld.id}", plainMember))
                w.figuresWithheld shouldBe true
                w.options.all { it.voteCount == 0 } shouldBe true
                val c = election(client.read("/t/election/${closed.id}", plainMember))
                c.figuresWithheld shouldBe false
                c.options.all { it.voteCount == 0 } shouldBe true
                val d = election(client.read("/t/election/${disclosed.id}", plainMember))
                d.figuresWithheld shouldBe false
                d.options.first { it.label == "YES" }.voteCount shouldBe 3
                val o = election(client.read("/t/election/${openOne.id}", plainMember))
                o.figuresWithheld shouldBe false
                o.options.first { it.label == "YES" }.voteCount shouldBe 1
                val listed =
                    json
                        .decodeFromString(ListSerializer(ElectionDto.serializer()), client.read("/t/elections", plainMember))
                        .associateBy { it.id }
                listed.getValue(withheld.id.toString()).figuresWithheld shouldBe true
                listed.getValue(withheld.id.toString()).options.all { it.voteCount == 0 } shouldBe true
                listed
                    .getValue(disclosed.id.toString())
                    .options
                    .first { it.label == "YES" }
                    .voteCount shouldBe 3
            }
        }

        // Assertion inverted per user decision 2026-10-05 (receipt-freeness, V1.9.54): the receipt proves inclusion only. A stricter expectation.
        test("verifyReceipt after the tally returns no label at 1 ballot, only that it was counted") {
            val subject = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, ballots = yesNo(YES))
            testApp {
                client.read("/t/verify-receipt/${subject.id}?code=${subject.receiptCodes.single()}", plainMember) shouldBe "true::true"
            }
        }

        // -- tally(): what is written, what is returned ---------------------------------------------------------------------

        test("tally of a secret YES_NO below the minimum writes 0/0/0 into the resolution book, the audit snapshot and the result") {
            val secret = seed(ElectionType.YES_NO, ElectionStatus.CLOSED, secret = true, ballots = yesNo(YES, YES, NO), approved = true)
            val open = seed(ElectionType.YES_NO, ElectionStatus.CLOSED, secret = false, ballots = yesNo(YES, YES, NO), approved = true)
            testApp {
                val secretResponse = client.post("/t/tally/${secret.id}") { header("X-Member-Id", electionBoardMember.toString()) }
                secretResponse.status shouldBe HttpStatusCode.OK
                val result = json.decodeFromString(ElectionResultDto.serializer(), secretResponse.bodyAsText())
                result.figuresWithheld shouldBe true
                result.perOptionVotes.isEmpty() shouldBe true
                val openResponse = client.post("/t/tally/${open.id}") { header("X-Member-Id", electionBoardMember.toString()) }
                json
                    .decodeFromString(ElectionResultDto.serializer(), openResponse.bodyAsText())
                    .perOptionVotes.values
                    .sum() shouldBe 3
            }
            transaction {
                val secretRow = ResolutionTable.selectAll().where { ResolutionTable.electionId eq secret.id }.single()
                secretRow[ResolutionTable.votesYes] shouldBe 0
                secretRow[ResolutionTable.votesNo] shouldBe 0
                secretRow[ResolutionTable.votesAbstain] shouldBe 0
                secretRow[ResolutionTable.status] shouldBe ResolutionStatus.ADOPTED
                val openRow = ResolutionTable.selectAll().where { ResolutionTable.electionId eq open.id }.single()
                openRow[ResolutionTable.votesYes] shouldBe 2
                openRow[ResolutionTable.votesNo] shouldBe 1
                val audit =
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.entityId eq secretRow[ResolutionTable.id] }
                        .single()[AuditLogEntryTable.afterSnapshot]
                        .orEmpty()
                audit shouldContain "\"votesYes\":0"
                audit shouldContain "\"votesNo\":0"
            }
        }

        // -- legacy rows: figures written before V1.9.53 are masked on every read path --------------------------------------

        test("a resolution row and an audit entry written with real figures are masked on every read path, the chain stays valid") {
            val secret = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, ballots = yesNo(YES, YES, NO))
            val open = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = false, ballots = yesNo(YES, YES, NO))
            val key = ApiKeyStore.issue(label = "Min-Participation ${Uuid.random()}", createdByMemberId = boardAccount)

            fun legacyResolution(electionId: Uuid): Uuid {
                val id = Uuid.random()
                transaction {
                    ResolutionTable.insert {
                        it[ResolutionTable.id] = id
                        it[ResolutionTable.meetingId] = meetingId
                        it[agendaItemId] = null
                        it[number] = "MP-2026-${id.toString().take(6)}"
                        it[title] = "Legacy Resolution"
                        it[text] = "Legacy text"
                        it[votesYes] = 3171
                        it[votesNo] = 1749
                        it[votesAbstain] = 1313
                        it[quorumMet] = true
                        it[status] = ResolutionStatus.ADOPTED
                        it[decidedAt] = LocalDateTime(2026, 3, 1, 19, 0)
                        it[recordedBy] = boardAccount
                        it[resolutionMode] = ResolutionMode.DEMOCRATIC
                        it[voteId] = null
                        it[ResolutionTable.electionId] = electionId
                        it[systemicConsensusId] = null
                    }
                    AuditLogRecorder.record(
                        actorMemberId = boardAccount,
                        actorRole = AccountRole.ADMIN,
                        entityType = AuditEntityType.RESOLUTION,
                        entityId = id,
                        action = AuditAction.CREATE,
                        before = null,
                        after =
                            Json.encodeToString(
                                ResolutionSnapshot.serializer(),
                                ResolutionSnapshot(
                                    meetingId = meetingId.toString(),
                                    number = "MP-LEGACY",
                                    title = "Legacy Resolution",
                                    text = "Legacy text",
                                    votesYes = 3171,
                                    votesNo = 1749,
                                    votesAbstain = 1313,
                                    quorumMet = true,
                                    status = ResolutionStatus.ADOPTED,
                                    decidedAt = LocalDateTime(2026, 3, 1, 19, 0),
                                    recordedBy = boardAccount.toString(),
                                    resolutionMode = ResolutionMode.DEMOCRATIC,
                                ),
                            ),
                    )
                }
                return id
            }
            val secretResolution = legacyResolution(secret.id)
            val openResolution = legacyResolution(open.id)
            testApp {
                val list = client.read("/t/resolutions/$meetingId", plainMember)
                val resolutions = json.decodeFromString(ListSerializer(ResolutionDto.serializer()), list).associateBy { it.id }
                resolutions.getValue(secretResolution.toString()).figuresWithheld shouldBe true
                resolutions.getValue(secretResolution.toString()).votesYes shouldBe 0
                resolutions.getValue(openResolution.toString()).figuresWithheld shouldBe false
                resolutions.getValue(openResolution.toString()).votesYes shouldBe 3171

                val detail = json.decodeFromString(MeetingDetailDto.serializer(), client.read("/t/meeting/$meetingId", plainMember))
                detail.resolutions.first { it.id == secretResolution.toString() }.votesYes shouldBe 0

                val auditList = client.read("/t/audit/$secretResolution", boardAccount)
                auditList shouldNotContainNumber "3171"
                auditList shouldNotContainNumber "1749"
                val entries = json.decodeFromString(ListSerializer(AuditLogEntryDto.serializer()), auditList)
                entries.single().figuresWithheld shouldBe true
                val single = client.read("/t/audit-entry/${entries.single().id}", boardAccount)
                single shouldNotContainNumber "3171"
                val singleEntry = json.decodeFromString(AuditLogEntryDto.serializer(), single)
                singleEntry.figuresWithheld shouldBe true
                val maskedSnapshot = json.decodeFromString(ResolutionSnapshot.serializer(), singleEntry.afterSnapshot.orEmpty())
                maskedSnapshot.votesYes shouldBe 0
                maskedSnapshot.votesNo shouldBe 0
                maskedSnapshot.votesAbstain shouldBe 0
                val openEntries = client.read("/t/audit/$openResolution", boardAccount)
                val openEntry = json.decodeFromString(ListSerializer(AuditLogEntryDto.serializer()), openEntries).single()
                openEntry.figuresWithheld shouldBe false
                json.decodeFromString(ResolutionSnapshot.serializer(), openEntry.afterSnapshot.orEmpty()).votesYes shouldBe 3171

                // The stored row is untouched by the masking: it still holds the real figures and its hash still matches a recomputation
                // (the global chain is not asserted here: other specs sharing the database may leave their own rows behind).
                transaction {
                    val raw = AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq secretResolution }.single()
                    raw[AuditLogEntryTable.afterSnapshot].orEmpty() shouldContain "\"votesYes\":3171"
                    AuditHashChain.computeHash(
                        AuditHashChain.ChainInput(
                            sequenceNumber = raw[AuditLogEntryTable.sequenceNumber],
                            occurredAt = raw[AuditLogEntryTable.occurredAt],
                            actorMemberId = raw[AuditLogEntryTable.actorMemberId],
                            actorRole = raw[AuditLogEntryTable.actorRole],
                            entityType = raw[AuditLogEntryTable.entityType],
                            entityId = raw[AuditLogEntryTable.entityId],
                            action = raw[AuditLogEntryTable.action],
                            beforeSnapshot = raw[AuditLogEntryTable.beforeSnapshot],
                            afterSnapshot = raw[AuditLogEntryTable.afterSnapshot],
                            previousEntryHash = raw[AuditLogEntryTable.previousEntryHash],
                        ),
                    ) shouldBe raw[AuditLogEntryTable.entryHash]
                }

                val publicList = client.get("/api/v1/resolutions?meetingId=$meetingId") { header("Authorization", "Bearer ${key.rawKey}") }
                publicList.status shouldBe HttpStatusCode.OK
                val publicBody = publicList.bodyAsText()
                val page = json.decodeFromString(PublicApiResolutionsPageDto.serializer(), publicBody).items.associateBy { it.id }
                page.getValue(secretResolution.toString()).votesYes shouldBe null
                page.getValue(secretResolution.toString()).votesNo shouldBe null
                page.getValue(secretResolution.toString()).votesAbstain shouldBe null
                page.getValue(secretResolution.toString()).figuresWithheld shouldBe true
                page.getValue(openResolution.toString()).votesYes shouldBe 3171
                page.getValue(openResolution.toString()).figuresWithheld shouldBe false
                // the null is on the wire, not omitted
                publicBody shouldContain "\"votesYes\":null"
                publicBody shouldContain "\"figuresWithheld\":true"
                val publicSingle = client.get("/api/v1/resolutions/$secretResolution") { header("Authorization", "Bearer ${key.rawKey}") }
                val singleBody = publicSingle.bodyAsText()
                singleBody shouldNotContainNumber "3171"
                singleBody shouldContain "\"votesYes\":null"
                singleBody shouldContain "\"figuresWithheld\":true"
            }
        }

        // -- audit masking, fail-closed + documented residual limit (E6) -------------------------------------------------------

        fun seedLegacyResolutionWithAudit(
            electionId: Uuid,
            afterSnapshot: String,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ResolutionTable.insert {
                    it[ResolutionTable.id] = id
                    it[ResolutionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[number] = "MP-2026-${id.toString().take(6)}"
                    it[title] = "Legacy Resolution"
                    it[text] = "Legacy text"
                    it[votesYes] = 3
                    it[votesNo] = 1
                    it[votesAbstain] = 0
                    it[quorumMet] = true
                    it[status] = ResolutionStatus.ADOPTED
                    it[decidedAt] = LocalDateTime(2026, 3, 1, 19, 0)
                    it[recordedBy] = boardAccount
                    it[resolutionMode] = ResolutionMode.DEMOCRATIC
                    it[voteId] = null
                    it[ResolutionTable.electionId] = electionId
                    it[systemicConsensusId] = null
                }
                AuditLogRecorder.record(
                    actorMemberId = boardAccount,
                    actorRole = AccountRole.ADMIN,
                    entityType = AuditEntityType.RESOLUTION,
                    entityId = id,
                    action = AuditAction.CREATE,
                    before = null,
                    after = afterSnapshot,
                )
            }
            return id
        }

        test("an undecodable resolution audit snapshot of a withheld resolution is withheld (null), never delivered unmasked") {
            val secret = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, ballots = yesNo(YES, YES, NO))
            val resolutionId = seedLegacyResolutionWithAudit(secret.id, "this is not a snapshot, votesYes=3 votesNo=1")
            testApp {
                val body = client.read("/t/audit/$resolutionId", boardAccount)
                body shouldNotContainNumber "3"
                val entry = json.decodeFromString(ListSerializer(AuditLogEntryDto.serializer()), body).single()
                entry.figuresWithheld shouldBe true
                entry.afterSnapshot shouldBe null
                entry.beforeSnapshot shouldBe null
            }
        }

        test(
            "documented limit E6: the figures of a pre-V1.9.53 audit entry stay recoverable from the delivered hash (pins the accepted behaviour)",
        ) {
            val secret = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, ballots = yesNo(YES, YES, NO))
            val real =
                Json.encodeToString(
                    ResolutionSnapshot.serializer(),
                    ResolutionSnapshot(
                        meetingId = meetingId.toString(),
                        number = "MP-LEGACY",
                        title = "Legacy Resolution",
                        text = "Legacy text",
                        votesYes = 3,
                        votesNo = 1,
                        votesAbstain = 0,
                        quorumMet = true,
                        status = ResolutionStatus.ADOPTED,
                        decidedAt = LocalDateTime(2026, 3, 1, 19, 0),
                        recordedBy = boardAccount.toString(),
                        resolutionMode = ResolutionMode.DEMOCRATIC,
                    ),
                )
            val resolutionId = seedLegacyResolutionWithAudit(secret.id, real)
            testApp {
                val entry =
                    json
                        .decodeFromString(
                            ListSerializer(AuditLogEntryDto.serializer()),
                            client.read("/t/audit/$resolutionId", boardAccount),
                        ).single()
                entry.figuresWithheld shouldBe true
                val masked = entry.afterSnapshot.orEmpty()
                val found =
                    (0..4).flatMap { y -> (0..4).flatMap { n -> (0..4).map { a -> Triple(y, n, a) } } }.filter { (y, n, a) ->
                        val candidate =
                            masked
                                .replace("\"votesYes\":0", "\"votesYes\":$y")
                                .replace("\"votesNo\":0", "\"votesNo\":$n")
                                .replace("\"votesAbstain\":0", "\"votesAbstain\":$a")
                        AuditHashChain.computeHash(
                            AuditHashChain.ChainInput(
                                sequenceNumber = entry.sequenceNumber,
                                occurredAt = entry.occurredAt,
                                actorMemberId = entry.actorMemberId?.let { Uuid.parse(it) },
                                actorRole = entry.actorRole,
                                entityType = entry.entityType,
                                entityId = Uuid.parse(entry.entityId),
                                action = entry.action,
                                beforeSnapshot = entry.beforeSnapshot,
                                afterSnapshot = candidate,
                                previousEntryHash = entry.previousEntryHash,
                            ),
                        ) == entry.entryHash
                    }
                // If this ever fails (found.isEmpty), the hash fields were withheld: update KDoc, elections-integrity.adoc (E6) and CHANGELOG.
                found shouldBe listOf(Triple(3, 1, 0))
            }
        }

        // -- the batch helper ----------------------------------------------------------------------------------------------

        test("withheldElectionIds is a batch: empty input, mixed secret/open and below/at the minimum") {
            transaction { withheldElectionIds(emptyList()).shouldBeEmpty() }
            val below = (1..3).map { seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, ballots = yesNo(YES, NO)) }
            val atMinimum =
                (1..3).map {
                    seed(
                        ElectionType.YES_NO,
                        ElectionStatus.TALLIED,
                        secret = true,
                        ballots = yesNo(YES, YES, NO, NO, YES),
                    )
                }
            val open = (1..3).map { seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = false, ballots = yesNo(YES)) }
            val empty = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, ballots = emptyList())
            val all = (below + atMinimum + open + empty).map { it.id }
            transaction {
                withheldElectionIds(all) shouldBe (below.map { it.id } + empty.id).toSet()
            }
        }
    })
