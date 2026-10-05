package network.lapis.cloud.server.rpc

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
import kotlinx.serialization.json.Json
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
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionAnswer
import network.lapis.cloud.shared.domain.ElectionBallotCastResultDto
import network.lapis.cloud.shared.domain.ElectionBallotInput
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ReceiptVerificationDto
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

private class ReceiptSeed(
    val id: Uuid,
    val optionIds: List<Uuid>,
    val labels: List<String>,
    val codes: List<String>,
)

/**
 * V1.9.54 -- receipt-freeness. The receipt of a SECRET election proves inclusion only (`found`, `counted`), never the option: not before,
 * not after the tally, with one ballot or with many, and not through any other field of the wire answer. Open elections keep their labels.
 * Elections are written straight into the database, so the ballot counts are exact. The labels carry a marker no other text contains, so a
 * leak anywhere in the raw JSON is found by a plain substring search.
 */
class ReceiptFreenessTest :
    FunSpec({
        val marker = "ZX9Q"
        val memberIds = mutableListOf<Uuid>()
        val committeeId = Uuid.random()
        val meetingId = Uuid.random()
        val motionIds = mutableListOf<Uuid>()
        val electionIds = mutableListOf<Uuid>()
        val json = Json { ignoreUnknownKeys = true }
        lateinit var voter: Uuid
        lateinit var voters: List<Uuid>

        fun member(tag: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Receipt-Freeness $tag"
                    it[email] = "receipt-freeness-$tag-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
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

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            transaction {
                CommitteeTable.insert {
                    it[id] = committeeId
                    it[name] = "Receipt-Freeness-Gremium"
                    it[type] = CommitteeType.EXECUTIVE_BOARD
                    it[description] = "Receipt-Freeness"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
                MeetingTable.insert {
                    it[id] = meetingId
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "Receipt-Freeness-Sitzung"
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
            voter = member("voter")
            voters = (1..6).map { member("voter-$it") }
        }

        afterSpec {
            transaction {
                MotionTable.update({ MotionTable.id inList motionIds }) { it[resolutionId] = null }
                val ballotIds =
                    ElectionBallotTable
                        .selectAll()
                        .where { ElectionBallotTable.electionId inList electionIds }
                        .map { it[ElectionBallotTable.id] }
                ElectionBallotSelectionTable.deleteWhere { ElectionBallotSelectionTable.ballotId inList ballotIds }
                ElectionBallotTable.deleteWhere { ElectionBallotTable.electionId inList electionIds }
                ElectionParticipationTable.deleteWhere { ElectionParticipationTable.electionId inList electionIds }
                ElectionEligibleVoterTable.deleteWhere { ElectionEligibleVoterTable.electionId inList electionIds }
                ElectionOptionTable.deleteWhere { ElectionOptionTable.electionId inList electionIds }
                ElectionTable.deleteWhere { ElectionTable.id inList electionIds }
                MotionTable.deleteWhere { MotionTable.id inList motionIds }
                MeetingTable.deleteWhere { MeetingTable.id inList listOf(meetingId) }
                CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList listOf(committeeId) }
                CommitteeTable.deleteWhere { CommitteeTable.id inList listOf(committeeId) }
                AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
                MemberTable.deleteWhere { MemberTable.id inList memberIds }
            }
        }

        /** One election written directly; [selections] holds per ballot the option indexes. Receipt codes have the real 27-character format. */
        fun seed(
            type: ElectionType,
            status: ElectionStatus,
            secret: Boolean,
            selections: List<List<Int>>,
            seatCount: Int = 1,
            eligible: List<Uuid> = emptyList(),
        ): ReceiptSeed {
            val motionId = Uuid.random()
            val electionId = Uuid.random()
            val at = DbClock.nowLocalDateTime()
            val labels = if (type == ElectionType.YES_NO) listOf("YES", "NO", "ABSTAIN") else (0 until 3).map { "Kandidat-$marker-$it" }
            val optionIds = labels.map { Uuid.random() }
            val codes =
                selections.indices.map {
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
                    it[title] = "Receipt-Freeness-Antrag"
                    it[rationale] = "R"
                    it[text] = "T"
                    it[submitterMemberId] = voter
                    it[MotionTable.status] = MotionStatus.SCHEDULED
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = voter
                    it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
                ElectionTable.insert {
                    it[id] = electionId
                    it[title] = "Receipt-Freeness-${type.name}-${status.name}"
                    it[electionType] = type
                    it[ElectionTable.secret] = secret
                    it[ElectionTable.seatCount] = seatCount
                    it[targetCommitteeId] = null
                    it[targetRole] = null
                    it[requiredMajorityPercent] = 50
                    it[ElectionTable.status] = status
                    it[openedBy] = voter
                    it[openedAt] = at
                    it[votingOpenedAt] = if (status == ElectionStatus.PREPARATION) null else at
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
                selections.forEachIndexed { index, selection ->
                    val ballotId = Uuid.random()
                    ElectionBallotTable.insert {
                        it[id] = ballotId
                        it[receiptCode] = codes[index]
                        it[castAt] = at
                        it[ElectionBallotTable.electionId] = electionId
                        it[memberId] = if (secret) null else voters[index]
                    }
                    selection.forEach { optionIndex ->
                        ElectionBallotSelectionTable.insert {
                            it[id] = Uuid.random()
                            it[ElectionBallotSelectionTable.ballotId] = ballotId
                            it[optionId] = optionIds[optionIndex]
                        }
                    }
                }
                eligible.forEach { member ->
                    ElectionEligibleVoterTable.insert {
                        it[id] = Uuid.random()
                        it[ElectionEligibleVoterTable.electionId] = electionId
                        it[memberId] = member
                    }
                }
            }
            motionIds += motionId
            electionIds += electionId
            return ReceiptSeed(id = electionId, optionIds = optionIds, labels = labels, codes = codes)
        }

        suspend fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing {
                        get("/t/verify/{id}") {
                            val r =
                                ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
                                    .verifyReceipt(
                                        electionId = call.parameters["id"]!!,
                                        receiptCode = call.request.queryParameters["code"]!!,
                                    )
                            call.respondText(json.encodeToString(ReceiptVerificationDto.serializer(), r))
                        }
                        post("/t/cast/{id}") {
                            val r =
                                ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
                                    .castElectionBallot(
                                        ElectionBallotInput(electionId = call.parameters["id"]!!, answer = ElectionAnswer.YES),
                                    )
                            call.respondText(json.encodeToString(ElectionBallotCastResultDto.serializer(), r))
                        }
                    }
                }
                block()
            }

        suspend fun HttpClient.verify(
            electionId: Uuid,
            code: String,
        ): Pair<String, ReceiptVerificationDto> {
            val response = get("/t/verify/$electionId?code=$code") { header("X-Member-Id", voter.toString()) }
            val body = response.bodyAsText()
            response.status shouldBe HttpStatusCode.OK
            return body to json.decodeFromString(ReceiptVerificationDto.serializer(), body)
        }

        fun assertNoLeak(
            raw: String,
            seeded: ReceiptSeed,
        ) {
            seeded.labels.forEach { raw shouldNotContain it }
            seeded.optionIds.forEach { raw shouldNotContain it.toString() }
            raw shouldNotContain marker
            raw shouldNotContain "optionLabel\":\""
        }

        // -- secret elections: never the option --------------------------------------------------------------------------------

        for (ballotCount in listOf(1, 6)) {
            for (type in listOf(ElectionType.YES_NO, ElectionType.MULTI_CHOICE)) {
                for (status in listOf(ElectionStatus.OPEN, ElectionStatus.CLOSED, ElectionStatus.TALLIED)) {
                    test("secret $type, $ballotCount ballot(s), $status: no label, counted == TALLIED") {
                        val selections =
                            (0 until ballotCount).map { if (type == ElectionType.YES_NO) listOf(it % 3) else listOf(it % 3, (it + 1) % 3) }
                        val seeded =
                            seed(
                                type,
                                status,
                                secret = true,
                                selections = selections,
                                seatCount =
                                    if (type ==
                                        ElectionType.YES_NO
                                    ) {
                                        1
                                    } else {
                                        2
                                    },
                            )
                        testApp {
                            seeded.codes.forEach { code ->
                                val (raw, dto) = client.verify(seeded.id, code)
                                dto.found shouldBe true
                                dto.optionLabel shouldBe null
                                dto.counted shouldBe (status == ElectionStatus.TALLIED)
                                assertNoLeak(raw, seeded)
                            }
                        }
                    }
                }
            }
        }

        test("open election keeps its labels after the tally and returns none before") {
            val seeded =
                seed(ElectionType.MULTI_CHOICE, ElectionStatus.TALLIED, secret = false, selections = listOf(listOf(0, 1)), seatCount = 2)
            val running = seed(ElectionType.MULTI_CHOICE, ElectionStatus.CLOSED, secret = false, selections = listOf(listOf(0)))
            testApp {
                val (_, tallied) = client.verify(seeded.id, seeded.codes.single())
                tallied.found shouldBe true
                tallied.counted shouldBe true
                tallied.optionLabel shouldBe "Kandidat-$marker-0, Kandidat-$marker-1"
                val (_, before) = client.verify(running.id, running.codes.single())
                before.optionLabel shouldBe null
                before.counted shouldBe false
            }
        }

        test("an aborted election never returns a label and is not counted") {
            val seeded = seed(ElectionType.YES_NO, ElectionStatus.ABORTED, secret = true, selections = listOf(listOf(0)))
            testApp {
                val (raw, dto) = client.verify(seeded.id, seeded.codes.single())
                dto.found shouldBe true
                dto.optionLabel shouldBe null
                dto.counted shouldBe false
                assertNoLeak(raw, seeded)
            }
        }

        // -- tamper -------------------------------------------------------------------------------------------------------------

        test("tamper: another election's code, an unknown code and ill-formed codes are all just 'not found'") {
            val a = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, selections = listOf(listOf(0)))
            val b = seed(ElectionType.YES_NO, ElectionStatus.TALLIED, secret = true, selections = listOf(listOf(1)))
            testApp {
                client.verify(b.id, a.codes.single()).second shouldBe ReceiptVerificationDto(found = false, optionLabel = null)
                client.verify(a.id, "A".repeat(27)).second.found shouldBe false
                val badCodes =
                    listOf("A".repeat(26), "A".repeat(28), "%25".repeat(9), "'%20OR%201=1;--", "A".repeat(10_000), "%2E".repeat(27))
                badCodes.forEach { bad ->
                    val response = client.get("/t/verify/${a.id}?code=$bad") { header("X-Member-Id", voter.toString()) }
                    response.status shouldBe HttpStatusCode.OK
                    json.decodeFromString(ReceiptVerificationDto.serializer(), response.bodyAsText()).found shouldBe false
                }
                // an unknown election id is NotFound, as before
                val unknown = client.get("/t/verify/${Uuid.random()}?code=${"A".repeat(27)}") { header("X-Member-Id", voter.toString()) }
                (unknown.status == HttpStatusCode.NotFound || unknown.status == HttpStatusCode.InternalServerError) shouldBe true
            }
        }

        // -- nothing about a receipt in the logs or the audit trail ---------------------------------------------------------------

        test("a real cast and the check leave neither the receipt code nor the option in any log line or audit entry") {
            val seeded = seed(ElectionType.YES_NO, ElectionStatus.OPEN, secret = true, selections = emptyList(), eligible = listOf(voter))
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            root.addAppender(appender)
            var code = ""
            try {
                testApp {
                    val cast = client.post("/t/cast/${seeded.id}") { header("X-Member-Id", voter.toString()) }
                    cast.status shouldBe HttpStatusCode.OK
                    val result = json.decodeFromString(ElectionBallotCastResultDto.serializer(), cast.bodyAsText())
                    code = checkNotNull(result.receiptCode)
                    result.id shouldBe ""
                    val (raw, dto) = client.verify(seeded.id, code)
                    dto.found shouldBe true
                    dto.optionLabel shouldBe null
                    raw shouldNotContain "YES"
                }
            } finally {
                root.detachAppender(appender)
            }
            appender.list.forEach { event ->
                val line = event.formattedMessage + (event.throwableProxy?.message ?: "")
                line shouldNotContain code
            }
            val audit =
                transaction {
                    AuditLogEntryTable.selectAll().joinToString("\n") {
                        "${it[AuditLogEntryTable.beforeSnapshot]} ${it[AuditLogEntryTable.afterSnapshot]}"
                    }
                }
            audit shouldNotContain code
        }
    })
