package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.ApiKeyStore
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")

/**
 * Welle V1.3.2 "Webhooks" (ausgehend), plan §8/D7 -- the four new single-resource `{id}` endpoints
 * under `/api/v1`: happy path (field set identical to the list variant), `400` for an unparsable id,
 * `404` for an unknown OR (members/motions) not-currently-visible id -- see [PublicApiRoutes]'s own
 * handler KDoc for the "no status oracle" reasoning behind the members/motions distinction from
 * committees/resolutions.
 */
class PublicApiSingleResourceRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            if (createdMemberIds.isNotEmpty()) {
                transaction { MemberTable.deleteWhere { MemberTable.id inList createdMemberIds } }
                createdMemberIds.clear()
            }
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
            testApplication {
                application {
                    routing {
                        registerPublicApiRoutes(preAuthRateLimiter = generousLimiter(), postAuthRateLimiter = generousLimiter())
                    }
                }
                block()
            }
        }

        fun issueKey(): String = ApiKeyStore.issue(label = "Single-Resource Test Key ${Uuid.random()}", createdByMemberId = ADMIN_ID).rawKey

        fun insertMember(
            displayName: String,
            status: MemberStatus,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[email] = "single-resource-test-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        fun insertCommittee(active: Boolean = true): Uuid {
            val id = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[CommitteeTable.id] = id
                    it[name] = "Single-Resource Fixture Committee ${Uuid.random()}"
                    it[type] = CommitteeType.WORKING_GROUP
                    it[description] = "Fixture"
                    it[CommitteeTable.active] = active
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            return id
        }

        fun insertMeeting(committeeId: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                MeetingTable.insert {
                    it[MeetingTable.id] = id
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "Fixture Meeting"
                    it[scheduledAt] = LocalDateTime(2026, 3, 1, 18, 0)
                    it[location] = "Online"
                    it[format] = MeetingFormat.ONLINE
                    it[MeetingTable.status] = MeetingStatus.HELD
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            return id
        }

        fun insertResolution(): Uuid {
            val id = Uuid.random()
            val meetingId = insertMeeting(insertCommittee())
            transaction {
                ResolutionTable.insert {
                    it[ResolutionTable.id] = id
                    it[number] = "SINGLE-2026-01"
                    it[title] = "Fixture Resolution"
                    it[text] = "Fixture text"
                    it[votesYes] = 5
                    it[votesNo] = 1
                    it[votesAbstain] = 0
                    it[quorumMet] = true
                    it[status] = ResolutionStatus.ADOPTED
                    it[decidedAt] = LocalDateTime(2026, 3, 1, 19, 0)
                    it[recordedBy] = ADMIN_ID
                    it[resolutionMode] = ResolutionMode.COMMITTEE_QUORUM
                    it[voteId] = null
                    it[electionId] = null
                    it[systemicConsensusId] = null
                    it[ResolutionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                }
            }
            return id
        }

        fun insertMotion(
            committeeId: Uuid,
            status: MotionStatus,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MotionTable.insert {
                    it[MotionTable.id] = id
                    it[targetCommitteeId] = committeeId
                    it[title] = "Fixture Motion"
                    it[rationale] = "Fixture"
                    it[text] = "Fixture text"
                    it[submitterMemberId] = ADMIN_ID
                    it[MotionTable.status] = status
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = null
                    it[reviewedAt] = null
                    it[reviewNote] = null
                    it[meetingId] = null
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                    it[amendsMotionId] = null
                    it[currentText] = null
                }
            }
            return id
        }

        // ── /api/v1/members/{id} ─────────────────────────────────────────────────────

        test("GET /api/v1/members/{id} happy path -- same field set as the list variant") {
            testApp {
                val id = insertMember("Single Fixture Member", MemberStatus.ACTIVE)
                val response = client.get("/api/v1/members/$id") { header("Authorization", "Bearer ${issueKey()}") }
                response.status shouldBe HttpStatusCode.OK
                val dto = Json.decodeFromString(PublicApiMemberDto.serializer(), response.bodyAsText())
                dto.id shouldBe id.toString()
                dto.displayName shouldBe "Single Fixture Member"
            }
        }

        test("GET /api/v1/members/{id} -- unparsable id is 400") {
            testApp {
                client.get("/api/v1/members/not-a-uuid") { header("Authorization", "Bearer ${issueKey()}") }.status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        test("GET /api/v1/members/{id} -- unknown well-formed id is 404") {
            testApp {
                client.get("/api/v1/members/${Uuid.random()}") { header("Authorization", "Bearer ${issueKey()}") }.status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        test("GET /api/v1/members/{id} -- WITHDRAWN/APPLICATION/DECEASED members are 404, not 200 (no status oracle)") {
            testApp {
                val key = issueKey()
                listOf(MemberStatus.WITHDRAWN, MemberStatus.APPLICATION, MemberStatus.DECEASED).forEach { status ->
                    val id = insertMember("Hidden Member $status", status)
                    client.get("/api/v1/members/$id") { header("Authorization", "Bearer $key") }.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        // ── /api/v1/committees/{id} ───────────────────────────────────────────────────

        test("GET /api/v1/committees/{id} happy path, including an INACTIVE committee (no status filter here)") {
            testApp {
                val key = issueKey()
                val activeId = insertCommittee(active = true)
                val inactiveId = insertCommittee(active = false)
                client.get("/api/v1/committees/$activeId") { header("Authorization", "Bearer $key") }.status shouldBe HttpStatusCode.OK
                client.get("/api/v1/committees/$inactiveId") { header("Authorization", "Bearer $key") }.status shouldBe HttpStatusCode.OK
            }
        }

        test("GET /api/v1/committees/{id} -- unparsable id is 400, unknown id is 404") {
            testApp {
                val key = issueKey()
                client.get("/api/v1/committees/not-a-uuid") { header("Authorization", "Bearer $key") }.status shouldBe
                    HttpStatusCode.BadRequest
                client.get("/api/v1/committees/${Uuid.random()}") { header("Authorization", "Bearer $key") }.status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        // ── /api/v1/resolutions/{id} ──────────────────────────────────────────────────

        test("GET /api/v1/resolutions/{id} happy path") {
            testApp {
                val id = insertResolution()
                val response = client.get("/api/v1/resolutions/$id") { header("Authorization", "Bearer ${issueKey()}") }
                response.status shouldBe HttpStatusCode.OK
                Json.decodeFromString(PublicApiResolutionDto.serializer(), response.bodyAsText()).id shouldBe id.toString()
            }
        }

        test("GET /api/v1/resolutions/{id} -- unparsable id is 400, unknown id is 404") {
            testApp {
                val key = issueKey()
                client.get("/api/v1/resolutions/not-a-uuid") { header("Authorization", "Bearer $key") }.status shouldBe
                    HttpStatusCode.BadRequest
                client.get("/api/v1/resolutions/${Uuid.random()}") { header("Authorization", "Bearer $key") }.status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        // ── /api/v1/motions/{id} ──────────────────────────────────────────────────────

        test("GET /api/v1/motions/{id} happy path for a SCHEDULED motion") {
            testApp {
                val committeeId = insertCommittee()
                val motionId = insertMotion(committeeId = committeeId, status = MotionStatus.SCHEDULED)
                val response = client.get("/api/v1/motions/$motionId") { header("Authorization", "Bearer ${issueKey()}") }
                response.status shouldBe HttpStatusCode.OK
                Json.decodeFromString(PublicApiMotionDto.serializer(), response.bodyAsText()).id shouldBe motionId.toString()
            }
        }

        test("GET /api/v1/motions/{id} -- unparsable id is 400, unknown id is 404") {
            testApp {
                val key = issueKey()
                client.get("/api/v1/motions/not-a-uuid") { header("Authorization", "Bearer $key") }.status shouldBe
                    HttpStatusCode.BadRequest
                client.get("/api/v1/motions/${Uuid.random()}") { header("Authorization", "Bearer $key") }.status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        test(
            "GET /api/v1/motions/{id} -- internal-workflow statuses (SUBMITTED/REVIEWED/REJECTED_PRELIMINARY/WITHDRAWN) are 404, not 200",
        ) {
            testApp {
                val key = issueKey()
                val committeeId = insertCommittee()
                val hiddenStatuses =
                    listOf(MotionStatus.SUBMITTED, MotionStatus.REVIEWED, MotionStatus.REJECTED_PRELIMINARY, MotionStatus.WITHDRAWN)
                hiddenStatuses.forEach { status ->
                    val id = insertMotion(committeeId = committeeId, status = status)
                    client.get("/api/v1/motions/$id") { header("Authorization", "Bearer $key") }.status shouldBe
                        HttpStatusCode.NotFound
                }
            }
        }

        test("GET /api/v1/*/{id} without an API key is 401 -- unchanged publicApiHandler behavior") {
            testApp {
                val id = insertResolution()
                client.get("/api/v1/resolutions/$id").status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
