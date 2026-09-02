package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
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
 * V1.3.1 "API-Fundament, lesend" -- happy-path/pagination/field-set coverage for
 * [registerPublicApiRoutes]. Fixtures go direct-to-Exposed (mirroring [SocialPublicRoutesTest]'s
 * own house style), NOT through [network.lapis.cloud.server.rpc.GovernanceService]'s own write
 * paths, so tests here stay independent of that service's business-rule side effects.
 */
class PublicApiRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        // Mirrors SocialPublicRoutesTest's own cleanup discipline: this file's fixture members must
        // never leak into other Spec classes' exact-count assertions against DevSeedData's fixed
        // demo roster (e.g. ServiceIntegrationTest's "listMembers ... leaks no email/role" -- it
        // asserts exactly 4 ACTIVE members, and this shared H2-in-memory JVM instance is reused
        // across every Spec class in the same test run).
        afterTest {
            if (createdMemberIds.isNotEmpty()) {
                transaction { MemberTable.deleteWhere { MemberTable.id inList createdMemberIds } }
                createdMemberIds.clear()
            }
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun testApp(
            preAuthLimiter: FederationInboxRateLimiter = generousLimiter(),
            postAuthLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    routing {
                        registerPublicApiRoutes(preAuthRateLimiter = preAuthLimiter, postAuthRateLimiter = postAuthLimiter)
                    }
                }
                block()
            }
        }

        fun issueKey(label: String = "Test Key"): String = ApiKeyStore.issue(label = label, createdByMemberId = ADMIN_ID).rawKey

        fun insertCommittee(
            name: String = "Test Committee ${Uuid.random()}",
            active: Boolean = true,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[CommitteeTable.id] = id
                    it[CommitteeTable.name] = name
                    it[type] = CommitteeType.WORKING_GROUP
                    it[description] = "Fixture"
                    it[CommitteeTable.active] = active
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            return id
        }

        fun insertMeeting(
            committeeId: Uuid,
            status: MeetingStatus = MeetingStatus.PLANNED,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MeetingTable.insert {
                    it[MeetingTable.id] = id
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "Fixture Meeting"
                    it[scheduledAt] = LocalDateTime(2026, 3, 1, 18, 0)
                    it[location] = "Online"
                    it[format] = MeetingFormat.ONLINE
                    it[MeetingTable.status] = status
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

        fun insertResolution(meetingId: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                ResolutionTable.insert {
                    it[ResolutionTable.id] = id
                    it[number] = "TEST-2026-01"
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

        fun insertActiveMember(displayName: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[email] = "public-api-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        // ── /api/v1/members ────────────────────────────────────────────────────────────

        test("GET /api/v1/members returns only active members, id+displayName only") {
            testApp {
                val memberId = insertActiveMember("Public API Fixture Member")
                val response = client.get("/api/v1/members") { header("Authorization", "Bearer ${issueKey()}") }
                response.status shouldBe HttpStatusCode.OK
                val page = Json.decodeFromString(PublicApiMembersPageDto.serializer(), response.bodyAsText())
                page.items.any { it.id == memberId.toString() && it.displayName == "Public API Fixture Member" } shouldBe true
            }
        }

        test("GET /api/v1/members pagination -- limit/offset are honored and totalCount is accurate") {
            testApp {
                repeat(3) { insertActiveMember("Paging Fixture $it ${Uuid.random()}") }
                val fullPage = client.get("/api/v1/members?limit=1000") { header("Authorization", "Bearer ${issueKey()}") }
                val fullDto = Json.decodeFromString(PublicApiMembersPageDto.serializer(), fullPage.bodyAsText())

                val firstPage = client.get("/api/v1/members?limit=2&offset=0") { header("Authorization", "Bearer ${issueKey()}") }
                val firstDto = Json.decodeFromString(PublicApiMembersPageDto.serializer(), firstPage.bodyAsText())
                firstDto.items.size shouldBe 2
                firstDto.totalCount shouldBe fullDto.totalCount
                firstDto.limit shouldBe 2
                firstDto.offset shouldBe 0
            }
        }

        test("GET /api/v1/members pagination edge cases -- limit=0/huge/non-numeric/negative offset never bad_request, always clamped") {
            testApp {
                val key = issueKey()
                val zero = client.get("/api/v1/members?limit=0") { header("Authorization", "Bearer $key") }
                zero.status shouldBe HttpStatusCode.OK
                Json.decodeFromString(PublicApiMembersPageDto.serializer(), zero.bodyAsText()).limit shouldBe 25

                val huge = client.get("/api/v1/members?limit=999999") { header("Authorization", "Bearer $key") }
                Json.decodeFromString(PublicApiMembersPageDto.serializer(), huge.bodyAsText()).limit shouldBe 100

                val nonNumeric = client.get("/api/v1/members?limit=abc") { header("Authorization", "Bearer $key") }
                nonNumeric.status shouldBe HttpStatusCode.OK
                Json.decodeFromString(PublicApiMembersPageDto.serializer(), nonNumeric.bodyAsText()).limit shouldBe 25

                val negativeOffset = client.get("/api/v1/members?offset=-5") { header("Authorization", "Bearer $key") }
                negativeOffset.status shouldBe HttpStatusCode.OK
                Json.decodeFromString(PublicApiMembersPageDto.serializer(), negativeOffset.bodyAsText()).offset shouldBe 0
            }
        }

        // ── /api/v1/committees ─────────────────────────────────────────────────────────

        test("GET /api/v1/committees happy path, activeOnly defaults to true") {
            testApp {
                val activeId = insertCommittee(active = true)
                val inactiveId = insertCommittee(active = false)
                val response = client.get("/api/v1/committees") { header("Authorization", "Bearer ${issueKey()}") }
                val page = Json.decodeFromString(PublicApiCommitteesPageDto.serializer(), response.bodyAsText())
                page.items.any { it.id == activeId.toString() } shouldBe true
                page.items.any { it.id == inactiveId.toString() } shouldBe false
            }
        }

        test("GET /api/v1/committees?activeOnly=false includes inactive committees") {
            testApp {
                val inactiveId = insertCommittee(active = false)
                val response = client.get("/api/v1/committees?activeOnly=false") { header("Authorization", "Bearer ${issueKey()}") }
                val page = Json.decodeFromString(PublicApiCommitteesPageDto.serializer(), response.bodyAsText())
                page.items.any { it.id == inactiveId.toString() } shouldBe true
            }
        }

        // ── /api/v1/meetings ───────────────────────────────────────────────────────────

        test("GET /api/v1/meetings happy path plus committeeId/status filters") {
            testApp {
                val committeeId = insertCommittee()
                val plannedId = insertMeeting(committeeId = committeeId, status = MeetingStatus.PLANNED)
                val heldId = insertMeeting(committeeId = committeeId, status = MeetingStatus.HELD)
                val key = issueKey()

                val all = client.get("/api/v1/meetings?committeeId=$committeeId") { header("Authorization", "Bearer $key") }
                val allDto = Json.decodeFromString(PublicApiMeetingsPageDto.serializer(), all.bodyAsText())
                allDto.items.map { it.id }.toSet() shouldBe setOf(plannedId.toString(), heldId.toString())

                val filtered =
                    client.get("/api/v1/meetings?committeeId=$committeeId&status=HELD") { header("Authorization", "Bearer $key") }
                val filteredDto = Json.decodeFromString(PublicApiMeetingsPageDto.serializer(), filtered.bodyAsText())
                filteredDto.items.map { it.id } shouldBe listOf(heldId.toString())
            }
        }

        test("GET /api/v1/meetings?status=invalid-value returns 400 bad_request") {
            testApp {
                val response = client.get("/api/v1/meetings?status=NOT_A_REAL_STATUS") { header("Authorization", "Bearer ${issueKey()}") }
                response.status shouldBe HttpStatusCode.BadRequest
                Json.decodeFromString(PublicApiErrorDto.serializer(), response.bodyAsText()).error shouldBe "bad_request"
            }
        }

        test("GET /api/v1/meetings/{id} happy path") {
            testApp {
                val committeeId = insertCommittee()
                val meetingId = insertMeeting(committeeId = committeeId)
                val response = client.get("/api/v1/meetings/$meetingId") { header("Authorization", "Bearer ${issueKey()}") }
                response.status shouldBe HttpStatusCode.OK
                Json.decodeFromString(PublicApiMeetingDto.serializer(), response.bodyAsText()).id shouldBe meetingId.toString()
            }
        }

        test("GET /api/v1/meetings/{id} with an unknown (but well-formed) id returns 404 not_found") {
            testApp {
                val response = client.get("/api/v1/meetings/${Uuid.random()}") { header("Authorization", "Bearer ${issueKey()}") }
                response.status shouldBe HttpStatusCode.NotFound
                Json.decodeFromString(PublicApiErrorDto.serializer(), response.bodyAsText()).error shouldBe "not_found"
            }
        }

        test("GET /api/v1/meetings/{id} with a malformed id returns 400 bad_request, never 500") {
            testApp {
                val response = client.get("/api/v1/meetings/not-a-uuid") { header("Authorization", "Bearer ${issueKey()}") }
                response.status shouldBe HttpStatusCode.BadRequest
                Json.decodeFromString(PublicApiErrorDto.serializer(), response.bodyAsText()).error shouldBe "bad_request"
            }
        }

        // ── /api/v1/resolutions ────────────────────────────────────────────────────────

        test("GET /api/v1/resolutions happy path plus meetingId filter") {
            testApp {
                val committeeId = insertCommittee()
                val meetingId = insertMeeting(committeeId = committeeId)
                val resolutionId = insertResolution(meetingId)
                val response =
                    client.get("/api/v1/resolutions?meetingId=$meetingId") { header("Authorization", "Bearer ${issueKey()}") }
                val dto = Json.decodeFromString(PublicApiResolutionsPageDto.serializer(), response.bodyAsText())
                dto.items.map { it.id } shouldBe listOf(resolutionId.toString())
            }
        }

        // ── /api/v1/motions ────────────────────────────────────────────────────────────

        test("GET /api/v1/motions default (no ?status=) only ever returns whitelisted statuses -- SUBMITTED never leaks") {
            testApp {
                val committeeId = insertCommittee()
                val scheduledId = insertMotion(committeeId = committeeId, status = MotionStatus.SCHEDULED)
                insertMotion(committeeId = committeeId, status = MotionStatus.SUBMITTED)
                insertMotion(committeeId = committeeId, status = MotionStatus.REVIEWED)
                insertMotion(committeeId = committeeId, status = MotionStatus.WITHDRAWN)

                val response =
                    client.get("/api/v1/motions?targetCommitteeId=$committeeId") { header("Authorization", "Bearer ${issueKey()}") }
                val dto = Json.decodeFromString(PublicApiMotionsPageDto.serializer(), response.bodyAsText())
                dto.items.map { it.id } shouldBe listOf(scheduledId.toString())
            }
        }

        test("GET /api/v1/motions?status=SUBMITTED (a non-whitelisted internal status) returns 400 bad_request, not a silent empty list") {
            testApp {
                val response = client.get("/api/v1/motions?status=SUBMITTED") { header("Authorization", "Bearer ${issueKey()}") }
                response.status shouldBe HttpStatusCode.BadRequest
                Json.decodeFromString(PublicApiErrorDto.serializer(), response.bodyAsText()).error shouldBe "bad_request"
            }
        }

        test("GET /api/v1/motions?status=RESOLVED (a whitelisted status) filters correctly") {
            testApp {
                val committeeId = insertCommittee()
                val resolvedId = insertMotion(committeeId = committeeId, status = MotionStatus.RESOLVED)
                insertMotion(committeeId = committeeId, status = MotionStatus.SCHEDULED)
                val response =
                    client.get("/api/v1/motions?targetCommitteeId=$committeeId&status=RESOLVED") {
                        header("Authorization", "Bearer ${issueKey()}")
                    }
                val dto = Json.decodeFromString(PublicApiMotionsPageDto.serializer(), response.bodyAsText())
                dto.items.map { it.id } shouldBe listOf(resolvedId.toString())
            }
        }

        // ── Session isolation ──────────────────────────────────────────────────────────

        test("a valid session cookie alone (no Authorization header) never authenticates the public API -- 401") {
            testApp {
                val response = client.get("/api/v1/members") { header("Cookie", "lapis_session=whatever-it-does-not-matter") }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
