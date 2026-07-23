package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
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
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.TransparenzregisterReminderTable
import network.lapis.cloud.server.db.generated.VoteBallotTable
import network.lapis.cloud.server.db.generated.VoteOptionTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.dsgvo.GovernancePersonalData
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgendaItemInput
import network.lapis.cloud.shared.domain.AttendanceInput
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BoardMembershipSnapshot
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeMembershipInput
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingInput
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionInput
import network.lapis.cloud.shared.domain.MotionResolutionInput
import network.lapis.cloud.shared.domain.MotionReviewDecision
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionInput
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionSnapshot
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.domain.VoteOpenInput
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Exercises [GovernanceService] end to end, mirroring [ServiceIntegrationTest]'s/
 * [DsgvoServiceTest]'s house style (throwaway routes calling the service class directly, no wire
 * format to reverse-engineer). Uses its own freshly created Gremien and members throughout — never
 * [DevSeedData]'s four fixed demo members as Committee members/Meeting participants — for the same
 * reason [DsgvoServiceTest] documents for its own fixtures: other Spec classes running in the same
 * H2-in-memory JVM assert exact counts against the shared demo fixtures, and this file's own
 * assertions (e.g. exact `eligibleMemberCount`) would themselves become order-dependent if a
 * shared member acquired an extra Committee role. [afterSpec] hard-deletes every row this file
 * created, same discipline as `cleanUpDsgvoTestData`.
 *
 * DevSeedData's ADMIN/BOARD accounts are still used as the *actors* performing privileged actions
 * (creating Gremien, adding members) — only the Gremien/Meetingen/members created *as test data*
 * are fresh.
 */
class GovernanceServiceTest :
    FunSpec({
        val createdCommitteeIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpGovernanceTestData(createdCommitteeIds, createdMemberIds) }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.AKTIV,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Governance Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
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

        /**
         * Meritokratische Voteen (V0.2.3) tests seed a [LtrLedgerEntryTable] MINT row directly
         * rather than going through the real `LtrLedgerService` RPC surface (V0.6.1) — mirrors
         * how these tests already seed Committee/Meeting/Member rows directly instead of using a
         * UI flow. [LedgerBackedLtrBalanceProvider][network.lapis.cloud.server.economy
         * .LedgerBackedLtrBalanceProvider] then derives `freeBalance` as `SUM(amount_ltr)`, so a
         * single MINT of [balance] reproduces the exact same effective free balance the old
         * `ltr_balance` snapshot row used to provide directly.
         */
        fun seedLtrBalance(
            memberId: Uuid,
            balance: BigDecimal,
        ) {
            transaction {
                LtrLedgerEntryTable.insert {
                    it[id] = Uuid.random()
                    it[LtrLedgerEntryTable.memberId] = memberId
                    it[entryType] = LtrLedgerEntryType.MINT
                    it[amountLtr] = balance
                    it[referenceType] = null
                    it[referenceId] = null
                    it[note] = "GovernanceServiceTest seed"
                    it[createdBy] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
        }

        test("createCommittee requires BOARD/ADMIN; reads require authentication but no elevated role") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing {
                        post("/test/create-committee/{name}/{type}/{quorumPercent}") {
                            val service = GovernanceService(call)
                            val p = call.parameters
                            val g =
                                service.createCommittee(
                                    CommitteeInput(
                                        name = p["name"]!!,
                                        type = CommitteeType.valueOf(p["type"]!!),
                                        description = "Testcommittee",
                                        quorumPercent = p["quorumPercent"]!!.toInt(),
                                    ),
                                )
                            call.respondText(g.id)
                        }
                        get("/test/list-gremien") {
                            val service = GovernanceService(call)
                            call.respondText(service.listCommittees().joinToString(",") { it.id })
                        }
                    }
                }

                val forbidden =
                    client.post("/test/create-committee/Testverein/EXECUTIVE_BOARD/50") { header("X-Member-Id", MEMBER_ID) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val created =
                    client
                        .post("/test/create-committee/Executive Board%20Test/EXECUTIVE_BOARD/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                created.isBlank() shouldBe false
                createdCommitteeIds += Uuid.parse(created)

                val unauthenticated = client.get("/test/list-gremien")
                unauthenticated.status shouldBe HttpStatusCode.Unauthorized

                val listedByMember = client.get("/test/list-gremien") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                (created in listedByMember.split(",")) shouldBe true
            }
        }

        test(
            "Committee leadership authorization: plain member without a Committee role cannot create a " +
                "Meeting, CHAIR member can",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                        exception<NotFoundException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.NotFound)
                        }
                    }
                    routing {
                        post("/test/create-committee2/{quorumPercent}") {
                            val service = GovernanceService(call)
                            val g =
                                service.createCommittee(
                                    CommitteeInput(
                                        name = "Working Group IT",
                                        type = CommitteeType.WORKING_GROUP,
                                        description = "Testcommittee 2",
                                        quorumPercent = call.parameters["quorumPercent"]!!.toInt(),
                                    ),
                                )
                            call.respondText(g.id)
                        }
                        post("/test/add-member/{committeeId}/{memberId}/{role}") {
                            val service = GovernanceService(call)
                            val q = call.request.queryParameters
                            val since =
                                LocalDate(
                                    q["sinceYear"]?.toInt() ?: 2020,
                                    q["sinceMonth"]?.toInt() ?: 1,
                                    q["sinceDay"]?.toInt() ?: 1,
                                )
                            val m =
                                service.addCommitteeMember(
                                    call.parameters["committeeId"]!!,
                                    CommitteeMembershipInput(
                                        memberId = call.parameters["memberId"]!!,
                                        role = CommitteeRole.valueOf(call.parameters["role"]!!),
                                        since = since,
                                    ),
                                )
                            call.respondText(m.id)
                        }
                        post("/test/create-meeting/{committeeId}/{year}/{month}/{day}/{hour}") {
                            val service = GovernanceService(call)
                            val p = call.parameters
                            val scheduledAt =
                                LocalDateTime(p["year"]!!.toInt(), p["month"]!!.toInt(), p["day"]!!.toInt(), p["hour"]!!.toInt(), 0)
                            val s =
                                service.createMeeting(
                                    MeetingInput(
                                        committeeId = p["committeeId"]!!,
                                        title = "Testmeeting",
                                        scheduledAt = scheduledAt,
                                        location = "Vereinsheim",
                                        format = MeetingFormat.IN_PERSON,
                                    ),
                                )
                            call.respondText("${s.id}:${s.status}")
                        }
                    }
                }

                val committeeId = client.post("/test/create-committee2/50") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)

                val vorsitzMember = createTestMember("gov-vorsitz@example.org")
                val plainMember = createTestMember("gov-plain@example.org")

                client.post(
                    "/test/add-member/$committeeId/$vorsitzMember/CHAIR",
                ) { header("X-Member-Id", BOARD_ID) }

                val forbidden =
                    client.post(
                        "/test/create-meeting/$committeeId/2026/4/1/18",
                    ) { header("X-Member-Id", plainMember.toString()) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val allowed =
                    client
                        .post(
                            "/test/create-meeting/$committeeId/2026/4/1/18",
                        ) { header("X-Member-Id", vorsitzMember.toString()) }
                        .bodyAsText()
                allowed.substringBefore(":").isBlank() shouldBe false
                allowed.substringAfter(":") shouldBe MeetingStatus.PLANNED.name
            }
        }

        test(
            "full meeting lifecycle: agenda, mixed attendance, quorum reflects eligible-as-of-scheduledAt, " +
                "Resolution snapshots quorumMet, status update, protocol draft, guest does not count toward quorum",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                        exception<NotFoundException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.NotFound)
                        }
                    }
                    routing { registerGovernanceTestRoutes() }
                }

                val committeeId =
                    client
                        .post("/test/create-committee/Executive Board%20Lifecycle/EXECUTIVE_BOARD/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)

                val m1 = createTestMember("gov-m1@example.org")
                val m2 = createTestMember("gov-m2@example.org")
                val m3 = createTestMember("gov-m3@example.org")
                val m4 = createTestMember("gov-m4@example.org")
                val guest = createTestMember("gov-guest@example.org")

                client.post("/test/add-member/$committeeId/$m1/CHAIR") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeId/$m2/MEMBER") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeId/$m3/MEMBER") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeId/$m4/MEMBER") { header("X-Member-Id", BOARD_ID) }
                // guest is deliberately never added to the Committee.

                val meetingResponse =
                    client
                        .post("/test/create-meeting/$committeeId/2026/3/10/18") { header("X-Member-Id", m1.toString()) }
                        .bodyAsText()
                val meetingId = meetingResponse.substringBefore(":")
                meetingResponse.substringAfter(":") shouldBe MeetingStatus.PLANNED.name

                client.post("/test/add-top/$meetingId/1") { header("X-Member-Id", m1.toString()) }

                client.post("/test/record-attendance/$meetingId/$m1/PRESENT") { header("X-Member-Id", m1.toString()) }
                client.post("/test/record-attendance/$meetingId/$m2/EXCUSED") { header("X-Member-Id", m1.toString()) }
                client.post(
                    "/test/record-attendance/$meetingId/$m3/REPRESENTED?representedBy=$m1",
                ) { header("X-Member-Id", m1.toString()) }
                // m4 is intentionally never recorded (absent without excuse, no row at all).
                client.post("/test/record-attendance/$meetingId/$guest/PRESENT") { header("X-Member-Id", m1.toString()) }

                val quorum = client.get("/test/check-quorum/$meetingId") { header("X-Member-Id", m1.toString()) }.bodyAsText()
                // Eligible: m1..m4 (4) -- guest never counts. Present (PRESENT/REPRESENTED, eligible only):
                // m1 + m3 = 2. Required = ceil(4 * 50 / 100) = 2. met = true.
                quorum shouldBe "4:2:2:true"

                val resolution =
                    client
                        .post("/test/record-resolution/$meetingId") { header("X-Member-Id", m1.toString()) }
                        .bodyAsText()
                val parts = resolution.split(":")
                parts[0] shouldBe "EXECUTIVE_BOARD-2026-01"
                parts[1] shouldBe "true"
                parts[2] shouldBe ResolutionStatus.ADOPTED.name

                val statusUpdate =
                    client
                        .post(
                            "/test/update-status/$meetingId/${MeetingStatus.HELD}",
                        ) { header("X-Member-Id", m1.toString()) }
                        .bodyAsText()
                statusUpdate shouldBe MeetingStatus.HELD.name

                // 1 AgendaItem, 4 recorded Attendance rows (m1, m2, m3, guest -- not m4), 1 Resolution.
                val protocol =
                    client.get("/test/protocol-draft/$meetingId") { header("X-Member-Id", m1.toString()) }.bodyAsText()
                protocol shouldBe "1:4:1"
            }
        }

        test("quorum: single-member committee at the exact boundary, zero attendees is not met") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                        exception<NotFoundException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.NotFound)
                        }
                    }
                    routing { registerGovernanceTestRoutes() }
                }

                val committeeId =
                    client
                        .post("/test/create-committee/Einzelcommittee/COMMISSION/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)

                val soleMember = createTestMember("gov-sole@example.org")
                client.post("/test/add-member/$committeeId/$soleMember/CHAIR") { header("X-Member-Id", BOARD_ID) }

                val meetingId =
                    client
                        .post("/test/create-meeting/$committeeId/2026/5/1/10") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .substringBefore(":")

                // Zero attendees recorded yet: eligible=1, present=0, required=ceil(1*0.5)=1, not met.
                val beforeAttendance =
                    client.get("/test/check-quorum/$meetingId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                beforeAttendance shouldBe "1:0:1:false"

                client.post(
                    "/test/record-attendance/$meetingId/$soleMember/PRESENT",
                ) { header("X-Member-Id", soleMember.toString()) }

                // Exact boundary: present=1 == required=1 -> met.
                val afterAttendance =
                    client.get("/test/check-quorum/$meetingId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                afterAttendance shouldBe "1:1:1:true"
            }
        }

        test(
            "quorum: eligible-as-of-scheduledAt excludes memberships that end before, or start after, the meeting date",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                        exception<NotFoundException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.NotFound)
                        }
                    }
                    routing { registerGovernanceTestRoutes() }
                }

                val committeeId =
                    client
                        .post("/test/create-committee/Zeitcommittee/OTHER/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)

                val exMember = createTestMember("gov-ex@example.org")
                val futureMember = createTestMember("gov-future@example.org")

                // Membership ended well before the Meeting's scheduled date.
                client.post(
                    "/test/add-member/$committeeId/$exMember/MEMBER?sinceYear=2020&sinceMonth=1&sinceDay=1",
                ) { header("X-Member-Id", BOARD_ID) }
                transaction {
                    CommitteeMembershipTable.update({
                        (CommitteeMembershipTable.committeeId eq Uuid.parse(committeeId)) and
                            (CommitteeMembershipTable.memberId eq exMember)
                    }) {
                        it[until] = LocalDate(2026, 1, 1)
                    }
                }

                // Membership starts well after the Meeting's scheduled date.
                client.post(
                    "/test/add-member/$committeeId/$futureMember/MEMBER?sinceYear=2026&sinceMonth=6&sinceDay=1",
                ) { header("X-Member-Id", BOARD_ID) }

                val meetingId =
                    client
                        .post("/test/create-meeting/$committeeId/2026/3/1/10") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .substringBefore(":")

                // Neither membership is active as of 2026-03-01 -- eligible=0, vacuously met (0 required).
                val quorum = client.get("/test/check-quorum/$meetingId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                quorum shouldBe "0:0:0:true"
            }
        }

        test(
            "submitMotion: General Assembly target requires MemberStatus.AKTIV; Committee target requires " +
                "any-role membership, not just leadership; unauthenticated is rejected",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerMotionTestRoutes()
                    }
                }

                val mvCommitteeId =
                    client
                        .post("/test/create-committee/General Assembly/GENERAL_ASSEMBLY/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(mvCommitteeId)

                val aktivMember = createTestMember("motion-mv-aktiv@example.org")
                val motionStatusMember = createTestMember("motion-mv-motionstatus@example.org", MemberStatus.ANTRAG)

                val allowed =
                    client.post("/test/submit-motion/$mvCommitteeId") { header("X-Member-Id", aktivMember.toString()) }.bodyAsText()
                allowed.substringAfter(":") shouldBe MotionStatus.SUBMITTED.name

                val forbiddenNonAktiv =
                    client.post("/test/submit-motion/$mvCommitteeId") { header("X-Member-Id", motionStatusMember.toString()) }
                forbiddenNonAktiv.status shouldBe HttpStatusCode.Forbidden

                val unauthenticated = client.post("/test/submit-motion/$mvCommitteeId")
                unauthenticated.status shouldBe HttpStatusCode.Unauthorized

                val committeeId =
                    client
                        .post("/test/create-committee/AK%20Motion/WORKING_GROUP/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)

                val plainCommitteeMember = createTestMember("motion-plain-member@example.org")
                val nonMember = createTestMember("motion-nonmember@example.org")
                client.post(
                    "/test/add-member/$committeeId/$plainCommitteeMember/MEMBER",
                ) { header("X-Member-Id", BOARD_ID) }

                val committeeAllowed =
                    client
                        .post("/test/submit-motion/$committeeId") { header("X-Member-Id", plainCommitteeMember.toString()) }
                        .bodyAsText()
                committeeAllowed.substringAfter(":") shouldBe MotionStatus.SUBMITTED.name

                val committeeForbidden =
                    client.post("/test/submit-motion/$committeeId") { header("X-Member-Id", nonMember.toString()) }
                committeeForbidden.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "Motion lifecycle: SUBMITTED -> REVIEWED -> SCHEDULED -> resolveMotion creates a matching " +
                "Resolution; POSTPONED -> reschedule -> re-resolve produces a second Resolution and updates resolutionId",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerMotionTestRoutes()
                    }
                }

                val committeeId =
                    client
                        .post("/test/create-committee/Executive Board%20Motion/EXECUTIVE_BOARD/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)

                val chair = createTestMember("motion-lifecycle-chair@example.org")
                val submitter = createTestMember("motion-lifecycle-submitter@example.org")
                client.post("/test/add-member/$committeeId/$chair/CHAIR") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeId/$submitter/MEMBER") { header("X-Member-Id", BOARD_ID) }

                val motionId =
                    client
                        .post("/test/submit-motion/$committeeId") { header("X-Member-Id", submitter.toString()) }
                        .bodyAsText()
                        .substringBefore(":")

                val reviewForbidden =
                    client.post("/test/review-motion/$motionId/ACCEPT") { header("X-Member-Id", submitter.toString()) }
                reviewForbidden.status shouldBe HttpStatusCode.Forbidden

                val reviewed =
                    client.post("/test/review-motion/$motionId/ACCEPT") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                reviewed shouldBe MotionStatus.REVIEWED.name

                // Re-review is rejected: no longer SUBMITTED.
                val reReview = client.post("/test/review-motion/$motionId/ACCEPT") { header("X-Member-Id", chair.toString()) }
                reReview.status shouldBe HttpStatusCode.Conflict

                val meetingId1 =
                    client
                        .post("/test/create-meeting/$committeeId/2026/6/1/18") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")

                val scheduled1 =
                    client
                        .post("/test/schedule-motion/$motionId/$meetingId1/1") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                scheduled1.substringBefore(":") shouldBe MotionStatus.SCHEDULED.name

                client.post(
                    "/test/record-attendance/$meetingId1/$chair/PRESENT",
                ) { header("X-Member-Id", chair.toString()) }

                val vertagt =
                    client
                        .post("/test/resolve-motion/$motionId/POSTPONED") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                vertagt.substringBefore(":") shouldBe MotionStatus.POSTPONED.name
                val firstResolutionId = vertagt.substringAfter(":")

                // Reschedule onto a second Meeting (allowed again from POSTPONED) and resolve again.
                val meetingId2 =
                    client
                        .post("/test/create-meeting/$committeeId/2026/7/1/18") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")

                val scheduled2 =
                    client
                        .post("/test/schedule-motion/$motionId/$meetingId2/1") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                scheduled2.substringBefore(":") shouldBe MotionStatus.SCHEDULED.name

                client.post(
                    "/test/record-attendance/$meetingId2/$chair/PRESENT",
                ) { header("X-Member-Id", chair.toString()) }

                val angenommen =
                    client
                        .post("/test/resolve-motion/$motionId/ADOPTED") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                angenommen.substringBefore(":") shouldBe MotionStatus.RESOLVED.name
                val secondResolutionId = angenommen.substringAfter(":")
                (secondResolutionId != firstResolutionId) shouldBe true

                val finalMotion =
                    client.get("/test/get-motion/$motionId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                finalMotion shouldBe "${MotionStatus.RESOLVED.name}:$secondResolutionId"
            }
        }

        test(
            "V0.5.3 GoBD audit log: resolveMotion writes a RESOLUTION CREATE audit entry, not just " +
                "recordResolution -- fixes a review finding that only the manual-recording call site was audited",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerMotionTestRoutes()
                    }
                }

                val committeeId =
                    client
                        .post(
                            "/test/create-committee/Executive Board%20AuditResolveMotion/EXECUTIVE_BOARD/50",
                        ) { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)
                val chair = createTestMember("audit-resolvemotion-chair@example.org")
                client.post("/test/add-member/$committeeId/$chair/CHAIR") { header("X-Member-Id", BOARD_ID) }

                val motionId =
                    client
                        .post("/test/submit-motion/$committeeId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/review-motion/$motionId/ACCEPT") { header("X-Member-Id", chair.toString()) }
                val meetingId =
                    client
                        .post("/test/create-meeting/$committeeId/2026/6/10/18") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/schedule-motion/$motionId/$meetingId/1") { header("X-Member-Id", chair.toString()) }

                val resolved =
                    client
                        .post("/test/resolve-motion/$motionId/ADOPTED") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val resolutionId = resolved.substringAfter(":")

                val rows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.RESOLUTION) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(resolutionId))
                            }.toList()
                    }
                rows.size shouldBe 1
                transaction { rows.single()[AuditLogEntryTable.action] } shouldBe AuditAction.CREATE
                transaction { rows.single()[AuditLogEntryTable.actorMemberId] } shouldBe chair
                val snapshot =
                    transaction {
                        Json.decodeFromString(ResolutionSnapshot.serializer(), rows.single()[AuditLogEntryTable.afterSnapshot]!!)
                    }
                snapshot.resolutionMode shouldBe ResolutionMode.COMMITTEE_QUORUM
                snapshot.status shouldBe ResolutionStatus.ADOPTED
            }
        }

        test(
            "V0.5.3 GoBD audit log: closeVote writes a RESOLUTION CREATE audit entry tagged MERITOCRATIC",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerMotionTestRoutes()
                        registerVoteTestRoutes()
                    }
                }

                val committeeId =
                    client
                        .post(
                            "/test/create-committee/Executive Board%20AuditCloseVote/EXECUTIVE_BOARD/50",
                        ) { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)
                val chair = createTestMember("audit-closevote-chair@example.org")
                val voter = createTestMember("audit-closevote-voter@example.org")
                client.post("/test/add-member/$committeeId/$chair/CHAIR") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeId/$voter/MEMBER") { header("X-Member-Id", BOARD_ID) }
                seedLtrBalance(voter, BigDecimal("50.00"))

                val (motionId, _) = client.createTerminierterMotion(committeeId, chair, voter, 2026, 11, 20)
                val opened =
                    client.post("/test/open-vote/$motionId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val voteId = opened.substringBefore(":")
                val jaOptionId =
                    opened
                        .split(":", limit = 3)[2]
                        .split(";")
                        .first { it.endsWith("=YES") }
                        .substringBefore("=")
                client.post("/test/cast-vote-ballot/$voteId/$jaOptionId/10.00") { header("X-Member-Id", voter.toString()) }

                val closed =
                    client.post("/test/close-vote/$voteId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val resolutionId = closed.split(":")[3]

                val rows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.RESOLUTION) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(resolutionId))
                            }.toList()
                    }
                rows.size shouldBe 1
                transaction { rows.single()[AuditLogEntryTable.action] } shouldBe AuditAction.CREATE
                val snapshot =
                    transaction {
                        Json.decodeFromString(ResolutionSnapshot.serializer(), rows.single()[AuditLogEntryTable.afterSnapshot]!!)
                    }
                snapshot.resolutionMode shouldBe ResolutionMode.MERITOCRATIC
            }
        }

        test(
            "V0.5.3 GoBD audit log: addCommitteeMember/endCommitteeMembership on an EXECUTIVE_BOARD Committee " +
                "write BOARD_MEMBERSHIP CREATE/UPDATE audit entries, not just BoardMembershipService's own paths",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing { registerGovernanceTestRoutes() }
                }

                val committeeId =
                    client
                        .post(
                            "/test/create-committee/Executive Board%20AuditCoOption/EXECUTIVE_BOARD/50",
                        ) { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)
                val target = createTestMember("audit-cooption-target@example.org")

                val membershipId =
                    client
                        .post("/test/add-member/$committeeId/$target/MEMBER") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()

                val boardMembershipId =
                    transaction {
                        BoardMembershipTable.selectAll().where { BoardMembershipTable.memberId eq target }.single()[
                            BoardMembershipTable.id,
                        ]
                    }

                val createRows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.BOARD_MEMBERSHIP) and
                                    (AuditLogEntryTable.entityId eq boardMembershipId)
                            }.toList()
                    }
                createRows.size shouldBe 1
                transaction { createRows.single()[AuditLogEntryTable.action] } shouldBe AuditAction.CREATE

                client.post("/test/end-membership/$membershipId/2026-04-01") { header("X-Member-Id", BOARD_ID) }

                val updateRows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.BOARD_MEMBERSHIP) and
                                    (AuditLogEntryTable.entityId eq boardMembershipId) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE)
                            }.toList()
                    }
                updateRows.size shouldBe 1
                val afterSnapshot =
                    transaction {
                        Json.decodeFromString(
                            BoardMembershipSnapshot.serializer(),
                            updateRows.single()[AuditLogEntryTable.afterSnapshot]!!,
                        )
                    }
                afterSnapshot.endedAt shouldBe LocalDate(2026, 4, 1)
            }
        }

        test(
            "scheduleMotion: rejects wrong starting status, cross-Committee Meeting, non-PLANNED Meeting, and " +
                "position collision; the widened agenda_item.description column holds the full rationale",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerMotionTestRoutes()
                    }
                }

                val committeeA =
                    client
                        .post("/test/create-committee/Executive Board%20Schedule/EXECUTIVE_BOARD/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeA)
                val committeeB =
                    client
                        .post("/test/create-committee/AK%20Schedule/WORKING_GROUP/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeB)

                val chairA = createTestMember("motion-schedule-chairA@example.org")
                val chairB = createTestMember("motion-schedule-chairB@example.org")
                client.post("/test/add-member/$committeeA/$chairA/CHAIR") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeB/$chairB/CHAIR") { header("X-Member-Id", BOARD_ID) }

                val motionId =
                    client
                        .post(
                            "/test/submit-motion/$committeeA?rationaleLen=4000",
                        ) { header("X-Member-Id", chairA.toString()) }
                        .bodyAsText()
                        .substringBefore(":")

                val meetingA1 =
                    client
                        .post("/test/create-meeting/$committeeA/2026/8/1/18") { header("X-Member-Id", chairA.toString()) }
                        .bodyAsText()
                        .substringBefore(":")

                // Wrong starting status: still SUBMITTED, not REVIEWED/POSTPONED.
                val wrongStatus =
                    client.post("/test/schedule-motion/$motionId/$meetingA1/1") { header("X-Member-Id", chairA.toString()) }
                wrongStatus.status shouldBe HttpStatusCode.Conflict

                client.post("/test/review-motion/$motionId/ACCEPT") { header("X-Member-Id", chairA.toString()) }

                // Cross-Committee: a Meeting of committeeB cannot host an Motion targeting committeeA.
                val meetingB =
                    client
                        .post("/test/create-meeting/$committeeB/2026/8/1/18") { header("X-Member-Id", chairB.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                val crossCommittee =
                    client.post("/test/schedule-motion/$motionId/$meetingB/1") { header("X-Member-Id", chairA.toString()) }
                crossCommittee.status shouldBe HttpStatusCode.Conflict

                // Non-PLANNED: mark meetingA1 HELD, scheduling must be rejected.
                client.post(
                    "/test/update-status/$meetingA1/${MeetingStatus.HELD}",
                ) { header("X-Member-Id", chairA.toString()) }
                val nonGeplant =
                    client.post("/test/schedule-motion/$motionId/$meetingA1/1") { header("X-Member-Id", chairA.toString()) }
                nonGeplant.status shouldBe HttpStatusCode.Conflict

                // Fresh PLANNED Meeting under the correct Committee: position collision, then success.
                val meetingA2 =
                    client
                        .post("/test/create-meeting/$committeeA/2026/9/1/18") { header("X-Member-Id", chairA.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/add-top/$meetingA2/1") { header("X-Member-Id", chairA.toString()) }
                val collision =
                    client.post("/test/schedule-motion/$motionId/$meetingA2/1") { header("X-Member-Id", chairA.toString()) }
                collision.status shouldBe HttpStatusCode.Conflict

                val scheduled =
                    client
                        .post("/test/schedule-motion/$motionId/$meetingA2/2") { header("X-Member-Id", chairA.toString()) }
                        .bodyAsText()
                scheduled.substringBefore(":") shouldBe MotionStatus.SCHEDULED.name

                val topDescriptionLength =
                    client
                        .get(
                            "/test/top-description-length/$meetingA2/2",
                        ) { header("X-Member-Id", chairA.toString()) }
                        .bodyAsText()
                topDescriptionLength shouldBe "4000"
            }
        }

        test(
            "withdrawMotion: submitter can withdraw own SUBMITTED Motion; cannot withdraw someone else's or " +
                "their own past SUBMITTED; leadership can withdraw at any status but not twice",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerMotionTestRoutes()
                    }
                }

                val committeeId =
                    client
                        .post("/test/create-committee/Executive Board%20Withdraw/EXECUTIVE_BOARD/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)

                val chair = createTestMember("motion-withdraw-chair@example.org")
                val submitter = createTestMember("motion-withdraw-submitter@example.org")
                val other = createTestMember("motion-withdraw-other@example.org")
                client.post("/test/add-member/$committeeId/$chair/CHAIR") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeId/$submitter/MEMBER") { header("X-Member-Id", BOARD_ID) }

                val motionId1 =
                    client
                        .post("/test/submit-motion/$committeeId") { header("X-Member-Id", submitter.toString()) }
                        .bodyAsText()
                        .substringBefore(":")

                val otherForbidden =
                    client.post("/test/withdraw-motion/$motionId1") { header("X-Member-Id", other.toString()) }
                otherForbidden.status shouldBe HttpStatusCode.Forbidden

                val withdrawn =
                    client.post("/test/withdraw-motion/$motionId1") { header("X-Member-Id", submitter.toString()) }.bodyAsText()
                withdrawn shouldBe MotionStatus.WITHDRAWN.name

                // Submitter cannot withdraw their own Motion once it is past SUBMITTED.
                val motionId2 =
                    client
                        .post("/test/submit-motion/$committeeId") { header("X-Member-Id", submitter.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/review-motion/$motionId2/ACCEPT") { header("X-Member-Id", chair.toString()) }
                val submitterLateWithdraw =
                    client.post("/test/withdraw-motion/$motionId2") { header("X-Member-Id", submitter.toString()) }
                submitterLateWithdraw.status shouldBe HttpStatusCode.Forbidden

                // Leadership can withdraw at any status -- and a second withdraw is a Conflict.
                val chairWithdraw =
                    client.post("/test/withdraw-motion/$motionId2") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                chairWithdraw shouldBe MotionStatus.WITHDRAWN.name
                val chairDoubleWithdraw =
                    client.post("/test/withdraw-motion/$motionId2") { header("X-Member-Id", chair.toString()) }
                chairDoubleWithdraw.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "General Assembly quorum eligibility counts only MemberStatus.AKTIV members directly from " +
                "MemberTable, unaffected by CommitteeMembershipTable rows",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing { registerGovernanceTestRoutes() }
                }

                val mvCommitteeId =
                    client
                        .post("/test/create-committee/MV%20Quorum/GENERAL_ASSEMBLY/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(mvCommitteeId)

                val baselineMeetingId =
                    client
                        .post("/test/create-meeting/$mvCommitteeId/2026/10/1/18") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .substringBefore(":")
                val baselineEligible =
                    client
                        .get("/test/check-quorum/$baselineMeetingId") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .substringBefore(":")
                        .toInt()

                createTestMember("mv-quorum-aktiv-1@example.org")
                createTestMember("mv-quorum-aktiv-2@example.org")
                createTestMember("mv-quorum-motionstatus@example.org", MemberStatus.ANTRAG)
                // Deliberately never added to CommitteeMembershipTable for mvCommitteeId.

                val afterMeetingId =
                    client
                        .post("/test/create-meeting/$mvCommitteeId/2026/10/2/18") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .substringBefore(":")
                val afterEligible =
                    client
                        .get("/test/check-quorum/$afterMeetingId") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .substringBefore(":")
                        .toInt()

                (afterEligible - baselineEligible) shouldBe 2
            }
        }

        test(
            "DSGVO: GovernancePersonalData export/erase covers motion rows for both submitter and reviewer, " +
                "review_note retained verbatim (retain-with-reason, no field nulled)",
        ) {
            val committeeId = Uuid.random()
            val motionId = Uuid.random()
            val submitter = createTestMember("dsgvo-motion-submitter@example.org")
            val reviewer = createTestMember("dsgvo-motion-reviewer@example.org")
            transaction {
                CommitteeTable.insert {
                    it[id] = committeeId
                    it[name] = "DSGVO-Motion-Testcommittee"
                    it[type] = CommitteeType.OTHER
                    it[description] = "Nur fuer DSGVO-Motion-Test"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
                MotionTable.insert {
                    it[id] = motionId
                    it[targetCommitteeId] = committeeId
                    it[title] = "DSGVO-Testmotion"
                    it[rationale] = "Testrationale"
                    it[text] = "Motionstext"
                    it[submitterMemberId] = submitter
                    it[status] = MotionStatus.REVIEWED
                    it[submittedAt] = LocalDateTime(2026, 1, 2, 0, 0)
                    it[reviewedBy] = reviewer
                    it[reviewedAt] = LocalDateTime(2026, 1, 3, 0, 0)
                    it[reviewNote] = "Vertrauliche Pruefungsnotiz"
                    it[meetingId] = null
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
            }
            createdCommitteeIds += committeeId

            val exportSubmitter = transaction { GovernancePersonalData.export(submitter) }.toString()
            exportSubmitter shouldContain "DSGVO-Testmotion"

            val exportReviewer = transaction { GovernancePersonalData.export(reviewer) }.toString()
            exportReviewer shouldContain "DSGVO-Testmotion"

            val outcomes = transaction { GovernancePersonalData.erase(submitter, ErasureMode.ANONYMIZE) }
            val motionOutcome = outcomes.single { it.table == "motion" }
            motionOutcome.rowsRetained shouldBe 1

            transaction {
                val note = MotionTable.selectAll().where { MotionTable.id eq motionId }.single()[MotionTable.reviewNote]
                note shouldBe "Vertrauliche Pruefungsnotiz"
            }
        }

        test(
            "Meritokratische Vote happy path: open -> cast contested YES/NO baskets -> close computes " +
                "the Vickrey settlement exactly, creates a MERITOCRATIC Resolution linked to the Vote, " +
                "and transitions the Motion to RESOLVED",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerMotionTestRoutes()
                        registerVoteTestRoutes()
                    }
                }

                val committeeId =
                    client
                        .post("/test/create-committee/Executive Board%20Vote/EXECUTIVE_BOARD/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)

                val chair = createTestMember("abst-happy-chair@example.org")
                val m2 = createTestMember("abst-happy-m2@example.org")
                val m3 = createTestMember("abst-happy-m3@example.org")
                val m4 = createTestMember("abst-happy-m4@example.org")
                client.post("/test/add-member/$committeeId/$chair/CHAIR") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeId/$m2/MEMBER") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeId/$m3/MEMBER") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeId/$m4/MEMBER") { header("X-Member-Id", BOARD_ID) }

                seedLtrBalance(m2, BigDecimal("100.00"))
                seedLtrBalance(m3, BigDecimal("100.00"))
                seedLtrBalance(m4, BigDecimal("100.00"))

                val (motionId, meetingId) = client.createTerminierterMotion(committeeId, chair, m2, 2026, 11, 1)

                val opened =
                    client.post("/test/open-vote/$motionId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val openedParts = opened.split(":", limit = 3)
                val voteId = openedParts[0]
                openedParts[1] shouldBe VoteStatus.OPEN.name
                val optionIdByLabel =
                    openedParts[2].split(";").associate { entry ->
                        val (optId, label) = entry.split("=")
                        label to optId
                    }
                val jaOptionId = optionIdByLabel.getValue("YES")
                val neinOptionId = optionIdByLabel.getValue("NO")

                // Contested: YES basket = 60 (m2) + 30 (m3) = 90, NO basket = 50 (m4).
                client.post("/test/cast-vote-ballot/$voteId/$jaOptionId/60.00") { header("X-Member-Id", m2.toString()) }
                client.post("/test/cast-vote-ballot/$voteId/$jaOptionId/30.00") { header("X-Member-Id", m3.toString()) }
                client.post("/test/cast-vote-ballot/$voteId/$neinOptionId/50.00") { header("X-Member-Id", m4.toString()) }

                val closed =
                    client
                        .post("/test/close-vote/$voteId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val closedParts = closed.split(":")
                closedParts[0] shouldBe VoteStatus.CLOSED.name
                closedParts[1] shouldBe jaOptionId
                // secondPrice = the losing NO basket's total = 50.00 (winners collectively pay this much).
                closedParts[2] shouldBe "50.00"
                val resolutionId = closedParts[3]
                resolutionId.isBlank() shouldBe false

                // Vickrey proportional split of 50.00 between m2 (60/90 share) and m3 (30/90 share), largest-
                // remainder rounded to the cent: m2 = 33.33, m3 = 16.67, sum = 50.00 exactly. m4 (loser) = 0.00.
                val ballotsStr =
                    client.get("/test/list-ballots/$voteId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val settledByMember =
                    ballotsStr.split(";").associate { entry ->
                        val parts = entry.split(":")
                        parts[0] to parts[2]
                    }
                settledByMember.getValue(m2.toString()) shouldBe "33.33"
                settledByMember.getValue(m3.toString()) shouldBe "16.67"
                settledByMember.getValue(m4.toString()) shouldBe "0.00"

                val resolutionInfo =
                    client
                        .get("/test/resolution-for-meeting/$meetingId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val resolutionParts = resolutionInfo.split(":")
                resolutionParts[0] shouldBe ResolutionMode.MERITOCRATIC.name
                resolutionParts[1] shouldBe voteId
                resolutionParts[2] shouldBe ResolutionStatus.ADOPTED.name

                val motionInfo =
                    client.get("/test/get-motion/$motionId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                motionInfo shouldBe "${MotionStatus.RESOLVED.name}:$resolutionId"
            }
        }

        test(
            "Meritokratische Vote authz: non-leadership cannot open/close; only eligible (Committee-member) " +
                "callers can castVoteBallot",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerMotionTestRoutes()
                        registerVoteTestRoutes()
                    }
                }

                val committeeId =
                    client
                        .post(
                            "/test/create-committee/Executive Board%20Abst%20Authz/EXECUTIVE_BOARD/50",
                        ) { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)

                val chair = createTestMember("abst-authz-chair@example.org")
                val member = createTestMember("abst-authz-member@example.org")
                val outsider = createTestMember("abst-authz-outsider@example.org")
                client.post("/test/add-member/$committeeId/$chair/CHAIR") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeId/$member/MEMBER") { header("X-Member-Id", BOARD_ID) }
                // outsider is deliberately never added to the Committee.

                seedLtrBalance(member, BigDecimal("50.00"))
                seedLtrBalance(outsider, BigDecimal("50.00"))

                val (motionId, _) = client.createTerminierterMotion(committeeId, chair, member, 2026, 11, 2)

                val nonLeadershipOpen =
                    client.post("/test/open-vote/$motionId") { header("X-Member-Id", member.toString()) }
                nonLeadershipOpen.status shouldBe HttpStatusCode.Forbidden

                val opened =
                    client.post("/test/open-vote/$motionId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val voteId = opened.substringBefore(":")
                val jaOptionId =
                    opened
                        .split(":", limit = 3)[2]
                        .split(";")
                        .first { it.endsWith("=YES") }
                        .substringBefore("=")

                val outsiderCast =
                    client.post("/test/cast-vote-ballot/$voteId/$jaOptionId/10.00") { header("X-Member-Id", outsider.toString()) }
                outsiderCast.status shouldBe HttpStatusCode.Forbidden

                val memberCast =
                    client.post("/test/cast-vote-ballot/$voteId/$jaOptionId/10.00") { header("X-Member-Id", member.toString()) }
                memberCast.status shouldBe HttpStatusCode.OK

                val nonLeadershipClose =
                    client.post("/test/close-vote/$voteId") { header("X-Member-Id", member.toString()) }
                nonLeadershipClose.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "Meritokratische Vote state guards: openVote requires SCHEDULED and rejects a second " +
                "Vote on the same Motion; castVoteBallot/closeVote reject once CLOSED",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerMotionTestRoutes()
                        registerVoteTestRoutes()
                    }
                }

                val committeeId =
                    client
                        .post(
                            "/test/create-committee/Executive Board%20Abst%20Guards/EXECUTIVE_BOARD/50",
                        ) { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)

                val chair = createTestMember("abst-guards-chair@example.org")
                val member = createTestMember("abst-guards-member@example.org")
                client.post("/test/add-member/$committeeId/$chair/CHAIR") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeId/$member/MEMBER") { header("X-Member-Id", BOARD_ID) }
                seedLtrBalance(member, BigDecimal("50.00"))

                val motionId =
                    client
                        .post("/test/submit-motion/$committeeId") { header("X-Member-Id", member.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/review-motion/$motionId/ACCEPT") { header("X-Member-Id", chair.toString()) }

                // Still REVIEWED, not SCHEDULED yet: openVote must reject.
                val wrongStatus = client.post("/test/open-vote/$motionId") { header("X-Member-Id", chair.toString()) }
                wrongStatus.status shouldBe HttpStatusCode.Conflict

                val meetingId =
                    client
                        .post("/test/create-meeting/$committeeId/2026/11/3/18") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/schedule-motion/$motionId/$meetingId/1") { header("X-Member-Id", chair.toString()) }

                val opened =
                    client.post("/test/open-vote/$motionId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val voteId = opened.substringBefore(":")
                val jaOptionId =
                    opened
                        .split(":", limit = 3)[2]
                        .split(";")
                        .first { it.endsWith("=YES") }
                        .substringBefore("=")

                val duplicateOpen = client.post("/test/open-vote/$motionId") { header("X-Member-Id", chair.toString()) }
                duplicateOpen.status shouldBe HttpStatusCode.Conflict

                client.post("/test/cast-vote-ballot/$voteId/$jaOptionId/5.00") { header("X-Member-Id", member.toString()) }
                client.post("/test/close-vote/$voteId") { header("X-Member-Id", chair.toString()) }

                val castAfterClose =
                    client.post("/test/cast-vote-ballot/$voteId/$jaOptionId/5.00") { header("X-Member-Id", member.toString()) }
                castAfterClose.status shouldBe HttpStatusCode.Conflict

                val doubleClose = client.post("/test/close-vote/$voteId") { header("X-Member-Id", chair.toString()) }
                doubleClose.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "Meritokratische Vote validation: stake below the 0.01 LTR floor and stake exceeding the " +
                "member's free LTR balance are both rejected server-side, never trusting the client amount",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerMotionTestRoutes()
                        registerVoteTestRoutes()
                    }
                }

                val committeeId =
                    client
                        .post("/test/create-committee/Executive Board%20Abst%20Val/EXECUTIVE_BOARD/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)

                val chair = createTestMember("abst-val-chair@example.org")
                val member = createTestMember("abst-val-member@example.org")
                client.post("/test/add-member/$committeeId/$chair/CHAIR") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeId/$member/MEMBER") { header("X-Member-Id", BOARD_ID) }
                seedLtrBalance(member, BigDecimal("10.00"))

                val (motionId, _) = client.createTerminierterMotion(committeeId, chair, member, 2026, 11, 4)
                val opened =
                    client.post("/test/open-vote/$motionId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val voteId = opened.substringBefore(":")
                val jaOptionId =
                    opened
                        .split(":", limit = 3)[2]
                        .split(";")
                        .first { it.endsWith("=YES") }
                        .substringBefore("=")

                val belowFloor =
                    client.post("/test/cast-vote-ballot/$voteId/$jaOptionId/0.00") { header("X-Member-Id", member.toString()) }
                belowFloor.status shouldBe HttpStatusCode.Conflict

                val exceedsBalance =
                    client.post("/test/cast-vote-ballot/$voteId/$jaOptionId/15.00") { header("X-Member-Id", member.toString()) }
                exceedsBalance.status shouldBe HttpStatusCode.Conflict

                val valid =
                    client.post("/test/cast-vote-ballot/$voteId/$jaOptionId/5.00") { header("X-Member-Id", member.toString()) }
                valid.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "Meritokratische Vote: exact tie between the top two baskets closes with no winner, zero " +
                "settlement for everyone, and resolves the Motion to POSTPONED",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerMotionTestRoutes()
                        registerVoteTestRoutes()
                    }
                }

                val committeeId =
                    client
                        .post("/test/create-committee/Executive Board%20Abst%20Tie/EXECUTIVE_BOARD/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdCommitteeIds += Uuid.parse(committeeId)

                val chair = createTestMember("abst-tie-chair@example.org")
                val m2 = createTestMember("abst-tie-m2@example.org")
                val m3 = createTestMember("abst-tie-m3@example.org")
                client.post("/test/add-member/$committeeId/$chair/CHAIR") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeId/$m2/MEMBER") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-member/$committeeId/$m3/MEMBER") { header("X-Member-Id", BOARD_ID) }
                seedLtrBalance(m2, BigDecimal("50.00"))
                seedLtrBalance(m3, BigDecimal("50.00"))

                val (motionId, meetingId) = client.createTerminierterMotion(committeeId, chair, m2, 2026, 11, 5)
                val opened =
                    client.post("/test/open-vote/$motionId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val voteId = opened.substringBefore(":")
                val optionIdByLabel =
                    opened.split(":", limit = 3)[2].split(";").associate { entry ->
                        val (optId, label) = entry.split("=")
                        label to optId
                    }
                val jaOptionId = optionIdByLabel.getValue("YES")
                val neinOptionId = optionIdByLabel.getValue("NO")

                client.post("/test/cast-vote-ballot/$voteId/$jaOptionId/25.00") { header("X-Member-Id", m2.toString()) }
                client.post("/test/cast-vote-ballot/$voteId/$neinOptionId/25.00") { header("X-Member-Id", m3.toString()) }

                val closed =
                    client
                        .post("/test/close-vote/$voteId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val closedParts = closed.split(":")
                closedParts[0] shouldBe VoteStatus.CLOSED.name
                closedParts[1] shouldBe "" // no winner
                closedParts[2] shouldBe "0.00" // no settlement

                val ballotsStr =
                    client.get("/test/list-ballots/$voteId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                ballotsStr.split(";").forEach { entry ->
                    entry.split(":")[2] shouldBe "0.00"
                }

                val resolutionInfo =
                    client
                        .get("/test/resolution-for-meeting/$meetingId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                resolutionInfo.split(":")[2] shouldBe ResolutionStatus.POSTPONED.name

                val motionInfo =
                    client.get("/test/get-motion/$motionId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                motionInfo.substringBefore(":") shouldBe MotionStatus.POSTPONED.name
            }
        }

        test(
            "DSGVO: GovernancePersonalData export/erase covers vote (opened_by) and vote_ballot " +
                "(member_id) rows; staked LTR retained verbatim (retain-with-reason, property record)",
        ) {
            val committeeId = Uuid.random()
            val meetingId = Uuid.random()
            val motionId = Uuid.random()
            val voteId = Uuid.random()
            val optionId = Uuid.random()
            val opener = createTestMember("dsgvo-abst-opener@example.org")
            val voter = createTestMember("dsgvo-abst-voter@example.org")
            transaction {
                CommitteeTable.insert {
                    it[id] = committeeId
                    it[name] = "DSGVO-Vote-Testcommittee"
                    it[type] = CommitteeType.OTHER
                    it[description] = "Nur fuer DSGVO-Vote-Test"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
                MeetingTable.insert {
                    it[id] = meetingId
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "DSGVO-Vote-Testmeeting"
                    it[scheduledAt] = LocalDateTime(2026, 1, 4, 18, 0)
                    it[location] = null
                    it[format] = MeetingFormat.ONLINE
                    it[status] = MeetingStatus.PLANNED
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
                MotionTable.insert {
                    it[id] = motionId
                    it[targetCommitteeId] = committeeId
                    it[title] = "DSGVO-Testvote"
                    it[rationale] = "Testrationale"
                    it[text] = "Motionstext"
                    it[submitterMemberId] = opener
                    it[status] = MotionStatus.SCHEDULED
                    it[submittedAt] = LocalDateTime(2026, 1, 2, 0, 0)
                    it[reviewedBy] = null
                    it[reviewedAt] = null
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
                VoteTable.insert {
                    it[id] = voteId
                    it[VoteTable.motionId] = motionId
                    it[VoteTable.meetingId] = meetingId
                    it[title] = "DSGVO-Testvote"
                    it[status] = VoteStatus.OPEN
                    it[openedBy] = opener
                    it[openedAt] = LocalDateTime(2026, 1, 4, 0, 0)
                    it[closedAt] = null
                    it[winnerOptionId] = null
                    it[secondPriceLtr] = null
                    it[resolutionId] = null
                }
                VoteOptionTable.insert {
                    it[id] = optionId
                    it[VoteOptionTable.voteId] = voteId
                    it[label] = "YES"
                    it[position] = 0
                }
                VoteBallotTable.insert {
                    it[id] = Uuid.random()
                    it[VoteBallotTable.voteId] = voteId
                    it[VoteBallotTable.optionId] = optionId
                    it[VoteBallotTable.memberId] = voter
                    it[stakeLtr] = BigDecimal("12.34")
                    it[settledLtr] = null
                    it[castAt] = LocalDateTime(2026, 1, 5, 0, 0)
                }
            }
            createdCommitteeIds += committeeId

            val exportOpener = transaction { GovernancePersonalData.export(opener) }.toString()
            exportOpener shouldContain "DSGVO-Testvote"

            val exportVoter = transaction { GovernancePersonalData.export(voter) }.toString()
            exportVoter shouldContain "12.34"

            val outcomes = transaction { GovernancePersonalData.erase(voter, ErasureMode.ANONYMIZE) }
            val ballotOutcome = outcomes.single { it.table == "vote_ballot" }
            ballotOutcome.rowsRetained shouldBe 1

            transaction {
                val stake =
                    VoteBallotTable
                        .selectAll()
                        .where { VoteBallotTable.memberId eq voter }
                        .single()[VoteBallotTable.stakeLtr]
                stake shouldBe BigDecimal("12.34")
            }
        }
    })

/**
 * Shared throwaway routes for the multi-step tests (lifecycle, quorum edge cases) — pulled out
 * once because those tests all need the same nine service call sites. The single-route tests
 * above (Committee creation authz, leadership authz) keep their own smaller inline route sets since
 * they don't need every route.
 */
private fun Route.registerGovernanceTestRoutes() {
    post("/test/create-committee/{name}/{type}/{quorumPercent}") {
        val service = GovernanceService(call)
        val p = call.parameters
        val g =
            service.createCommittee(
                CommitteeInput(
                    name = p["name"]!!,
                    type = CommitteeType.valueOf(p["type"]!!),
                    description = "Testcommittee",
                    quorumPercent = p["quorumPercent"]!!.toInt(),
                ),
            )
        call.respondText(g.id)
    }
    post("/test/add-member/{committeeId}/{memberId}/{role}") {
        val service = GovernanceService(call)
        val q = call.request.queryParameters
        val since =
            LocalDate(
                q["sinceYear"]?.toInt() ?: 2020,
                q["sinceMonth"]?.toInt() ?: 1,
                q["sinceDay"]?.toInt() ?: 1,
            )
        val m =
            service.addCommitteeMember(
                call.parameters["committeeId"]!!,
                CommitteeMembershipInput(
                    memberId = call.parameters["memberId"]!!,
                    role = CommitteeRole.valueOf(call.parameters["role"]!!),
                    since = since,
                ),
            )
        call.respondText(m.id)
    }
    post("/test/end-membership/{membershipId}/{until}") {
        val service = GovernanceService(call)
        val m = service.endCommitteeMembership(call.parameters["membershipId"]!!, LocalDate.parse(call.parameters["until"]!!))
        call.respondText("${m.id}:${m.until}")
    }
    post("/test/create-meeting/{committeeId}/{year}/{month}/{day}/{hour}") {
        val service = GovernanceService(call)
        val p = call.parameters
        val scheduledAt = LocalDateTime(p["year"]!!.toInt(), p["month"]!!.toInt(), p["day"]!!.toInt(), p["hour"]!!.toInt(), 0)
        val s =
            service.createMeeting(
                MeetingInput(
                    committeeId = p["committeeId"]!!,
                    title = "Testmeeting",
                    scheduledAt = scheduledAt,
                    location = "Vereinsheim",
                    format = MeetingFormat.IN_PERSON,
                ),
            )
        call.respondText("${s.id}:${s.status}")
    }
    post("/test/add-top/{meetingId}/{position}") {
        val service = GovernanceService(call)
        val top =
            service.addAgendaItem(
                call.parameters["meetingId"]!!,
                AgendaItemInput(
                    position = call.parameters["position"]!!.toInt(),
                    title = "TOP ${call.parameters["position"]}",
                ),
            )
        call.respondText(top.id)
    }
    post("/test/record-attendance/{meetingId}/{memberId}/{status}") {
        val service = GovernanceService(call)
        val representedBy = call.request.queryParameters["representedBy"]
        val a =
            service.recordAttendance(
                call.parameters["meetingId"]!!,
                AttendanceInput(
                    memberId = call.parameters["memberId"]!!,
                    status = AttendanceStatus.valueOf(call.parameters["status"]!!),
                    representedByMemberId = representedBy,
                ),
            )
        call.respondText(a.id)
    }
    get("/test/check-quorum/{meetingId}") {
        val service = GovernanceService(call)
        val q = service.checkQuorum(call.parameters["meetingId"]!!)
        call.respondText("${q.eligibleMemberCount}:${q.presentCount}:${q.requiredCount}:${q.met}")
    }
    post("/test/record-resolution/{meetingId}") {
        val service = GovernanceService(call)
        val b =
            service.recordResolution(
                call.parameters["meetingId"]!!,
                ResolutionInput(
                    title = "Testresolution",
                    text = "Resolutiontext",
                    votesYes = 3,
                    votesNo = 1,
                    votesAbstain = 0,
                    status = ResolutionStatus.ADOPTED,
                ),
            )
        call.respondText("${b.number}:${b.quorumMet}:${b.status}")
    }
    post("/test/update-status/{meetingId}/{status}") {
        val service = GovernanceService(call)
        val s = service.updateMeetingStatus(call.parameters["meetingId"]!!, MeetingStatus.valueOf(call.parameters["status"]!!))
        call.respondText(s.status.name)
    }
    get("/test/protocol-draft/{meetingId}") {
        val service = GovernanceService(call)
        val draft = service.generateProtocolDraft(call.parameters["meetingId"]!!)
        call.respondText("${draft.agenda.size}:${draft.attendance.size}:${draft.resolutions.size}")
    }
}

/**
 * Shared [StatusPages] wiring for the Motion (V0.2.2) tests -- registers all four exception types
 * that surface across the Motion lifecycle (`UnauthenticatedException`/`ForbiddenException`/
 * `NotFoundException`/`ConflictException`), unlike the older single-route tests above which only
 * register the subset they individually need.
 */
private fun StatusPagesConfig.installGovernanceExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<ForbiddenException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Forbidden)
    }
    exception<NotFoundException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.NotFound)
    }
    exception<ConflictException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Conflict)
    }
}

/** Shared throwaway routes for the Motion (V0.2.2) lifecycle tests -- mirrors [registerGovernanceTestRoutes]'s style. */
private fun Route.registerMotionTestRoutes() {
    post("/test/submit-motion/{targetCommitteeId}") {
        val service = GovernanceService(call)
        val q = call.request.queryParameters
        val rationale = q["rationaleLen"]?.toInt()?.let { "A".repeat(it) } ?: (q["rationale"] ?: "Testrationale")
        val a =
            service.submitMotion(
                MotionInput(
                    targetCommitteeId = call.parameters["targetCommitteeId"]!!,
                    title = q["title"] ?: "Testmotion",
                    rationale = rationale,
                    text = q["text"] ?: "Motionstext",
                ),
            )
        call.respondText("${a.id}:${a.status}")
    }
    get("/test/get-motion/{id}") {
        val service = GovernanceService(call)
        val a = service.getMotion(call.parameters["id"]!!)
        call.respondText("${a.status}:${a.resolutionId ?: ""}")
    }
    post("/test/review-motion/{id}/{decision}") {
        val service = GovernanceService(call)
        val a =
            service.reviewMotion(
                call.parameters["id"]!!,
                MotionReviewDecision.valueOf(call.parameters["decision"]!!),
                call.request.queryParameters["note"],
            )
        call.respondText(a.status.name)
    }
    post("/test/schedule-motion/{id}/{meetingId}/{position}") {
        val service = GovernanceService(call)
        val a =
            service.scheduleMotion(
                call.parameters["id"]!!,
                call.parameters["meetingId"]!!,
                call.parameters["position"]!!.toInt(),
            )
        call.respondText("${a.status}:${a.agendaItemId}")
    }
    post("/test/resolve-motion/{id}/{status}") {
        val service = GovernanceService(call)
        val a =
            service.resolveMotion(
                call.parameters["id"]!!,
                MotionResolutionInput(
                    votesYes = 3,
                    votesNo = 1,
                    votesAbstain = 0,
                    status = ResolutionStatus.valueOf(call.parameters["status"]!!),
                ),
            )
        call.respondText("${a.status}:${a.resolutionId}")
    }
    post("/test/withdraw-motion/{id}") {
        val service = GovernanceService(call)
        val a = service.withdrawMotion(call.parameters["id"]!!)
        call.respondText(a.status.name)
    }
    get("/test/top-description-length/{meetingId}/{position}") {
        val service = GovernanceService(call)
        val detail = service.getMeetingDetail(call.parameters["meetingId"]!!)
        val top = detail.agenda.first { it.position == call.parameters["position"]!!.toInt() }
        call.respondText((top.description?.length ?: 0).toString())
    }
}

/**
 * Drives an Motion from SUBMITTED to SCHEDULED the same way the "Motion lifecycle" test above
 * does by hand — pulled into a shared helper for the Meritokratische Vote (V0.2.3) tests,
 * which all need exactly this precondition before `openVote` becomes reachable. [chairId]
 * both reviews and creates/schedules the Meeting (leadership actions); [submitterId] only needs
 * to be entitled to submit (any Committee role, or the chair themself). Requires
 * [registerGovernanceTestRoutes] and [registerMotionTestRoutes] to be installed on the same
 * [Route]. Returns `(motionId, meetingId)`.
 */
private suspend fun HttpClient.createTerminierterMotion(
    committeeId: String,
    chairId: Uuid,
    submitterId: Uuid,
    year: Int,
    month: Int,
    day: Int,
): Pair<String, String> {
    val motionId =
        post("/test/submit-motion/$committeeId") { header("X-Member-Id", submitterId.toString()) }
            .bodyAsText()
            .substringBefore(":")
    post("/test/review-motion/$motionId/ACCEPT") { header("X-Member-Id", chairId.toString()) }
    val meetingId =
        post("/test/create-meeting/$committeeId/$year/$month/$day/18") { header("X-Member-Id", chairId.toString()) }
            .bodyAsText()
            .substringBefore(":")
    post("/test/schedule-motion/$motionId/$meetingId/1") { header("X-Member-Id", chairId.toString()) }
    return motionId to meetingId
}

/**
 * Shared throwaway routes for the Meritokratische Vote (V0.2.3) tests. String encodings are
 * kept deliberately simple/parseable (`;`-separated entries, `=`/`:`-separated fields) — this is
 * the same "throwaway route calling the service class directly, no wire format to reverse-
 * engineer" house style [registerGovernanceTestRoutes]/[registerMotionTestRoutes] already use.
 */
private fun Route.registerVoteTestRoutes() {
    post("/test/open-vote/{motionId}") {
        val service = GovernanceService(call)
        val labels = call.request.queryParameters["labels"]?.split(",") ?: listOf("YES", "NO")
        val a =
            service.openVote(
                VoteOpenInput(motionId = call.parameters["motionId"]!!, optionLabels = labels),
            )
        val optionsStr = a.options.joinToString(";") { "${it.id}=${it.label}" }
        call.respondText("${a.id}:${a.status}:$optionsStr")
    }
    get("/test/get-vote/{id}") {
        val service = GovernanceService(call)
        val a = service.getVote(call.parameters["id"]!!)
        val optionsStr = a.options.joinToString(";") { "${it.id}=${it.label}:${it.basketTotalLtr}" }
        call.respondText("${a.status}:${a.winnerOptionId ?: ""}:${a.secondPriceLtr ?: ""}:${a.resolutionId ?: ""}:$optionsStr")
    }
    post("/test/cast-vote-ballot/{voteId}/{optionId}/{stake}") {
        val service = GovernanceService(call)
        val s =
            service.castVoteBallot(
                VoteBallotInput(
                    voteId = call.parameters["voteId"]!!,
                    optionId = call.parameters["optionId"]!!,
                    stakeLtr = BigDecimal(call.parameters["stake"]!!),
                ),
            )
        call.respondText("${s.id}:${s.stakeLtr}")
    }
    post("/test/close-vote/{id}") {
        val service = GovernanceService(call)
        val a = service.closeVote(call.parameters["id"]!!)
        call.respondText("${a.status}:${a.winnerOptionId ?: ""}:${a.secondPriceLtr ?: ""}:${a.resolutionId ?: ""}")
    }
    post("/test/abort-vote/{id}") {
        val service = GovernanceService(call)
        val a = service.abortVote(call.parameters["id"]!!)
        call.respondText(a.status.name)
    }
    get("/test/list-ballots/{voteId}") {
        val service = GovernanceService(call)
        val ballots = service.listVoteBallots(call.parameters["voteId"]!!)
        call.respondText(ballots.joinToString(";") { "${it.memberId}:${it.stakeLtr}:${it.settledLtr ?: ""}" })
    }
    get("/test/resolution-for-meeting/{meetingId}") {
        val service = GovernanceService(call)
        val b = service.listResolutions(meetingId = call.parameters["meetingId"]!!).single()
        call.respondText("${b.resolutionMode}:${b.voteId ?: ""}:${b.status}")
    }
}

/**
 * Hard-deletes every row this Spec created, child-before-parent, so no state leaks into other
 * Spec classes sharing the same H2 in-memory database — same discipline as
 * `DsgvoServiceTest.cleanUpDsgvoTestData`. [MotionTable] is deleted first since it references
 * committee/member/meeting/agenda_item/resolution, all of which are deleted further down.
 *
 * Meritokratische Voteen (V0.2.3): [VoteTable]/[ResolutionTable] form a cycle
 * (`resolution.vote_id` -> `vote.id`, `vote.resolution_id` -> `resolution.id`) --
 * both FKs are nulled out before either table's rows are deleted, same reasoning as the compile-
 * time circular column reference in `GovernanceTables.kt` (breaking the cycle explicitly rather
 * than relying on delete order alone). [LtrLedgerEntryTable] rows are matched by `memberIds` only
 * (never scoped by Committee) since a balance is a property of the member, not of any Committee.
 */
private fun cleanUpGovernanceTestData(
    committeeIds: List<Uuid>,
    memberIds: List<Uuid>,
) {
    if (committeeIds.isEmpty() && memberIds.isEmpty()) return
    transaction {
        // V0.5.3 GoBD audit log: recordResolution now writes an AuditLogEntryTable row per
        // Resolution, referencing the recording member via a real FK (actor_member_id) --
        // null it out first (audit_log_entry rows themselves are never deleted, see
        // AuditLogRecorder KDoc) so the MemberTable delete below does not violate that FK.
        if (memberIds.isNotEmpty()) {
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) {
                it[actorMemberId] = null
            }
        }

        val meetingIds =
            if (committeeIds.isEmpty()) {
                emptyList()
            } else {
                MeetingTable.selectAll().where { MeetingTable.committeeId inList committeeIds }.map { it[MeetingTable.id] }
            }

        val voteCondition =
            when {
                meetingIds.isNotEmpty() && memberIds.isNotEmpty() ->
                    (VoteTable.meetingId inList meetingIds) or (VoteTable.openedBy inList memberIds)
                meetingIds.isNotEmpty() -> VoteTable.meetingId inList meetingIds
                memberIds.isNotEmpty() -> VoteTable.openedBy inList memberIds
                else -> null
            }
        val voteIds =
            if (voteCondition != null) {
                VoteTable.selectAll().where { voteCondition }.map { it[VoteTable.id] }
            } else {
                emptyList()
            }
        if (voteIds.isNotEmpty()) {
            VoteTable.update({ VoteTable.id inList voteIds }) { it[resolutionId] = null }
            ResolutionTable.update({ ResolutionTable.voteId inList voteIds }) { it[ResolutionTable.voteId] = null }
            VoteBallotTable.deleteWhere { VoteBallotTable.voteId inList voteIds }
            VoteOptionTable.deleteWhere { VoteOptionTable.voteId inList voteIds }
            VoteTable.deleteWhere { VoteTable.id inList voteIds }
        }

        if (committeeIds.isNotEmpty() || memberIds.isNotEmpty()) {
            val motionCondition =
                when {
                    committeeIds.isNotEmpty() && memberIds.isNotEmpty() ->
                        (MotionTable.targetCommitteeId inList committeeIds) or
                            (MotionTable.submitterMemberId inList memberIds) or
                            (MotionTable.reviewedBy inList memberIds)
                    committeeIds.isNotEmpty() -> MotionTable.targetCommitteeId inList committeeIds
                    else -> (MotionTable.submitterMemberId inList memberIds) or (MotionTable.reviewedBy inList memberIds)
                }
            MotionTable.deleteWhere { motionCondition }
        }
        if (meetingIds.isNotEmpty()) {
            ResolutionTable.deleteWhere { ResolutionTable.meetingId inList meetingIds }
            AttendanceTable.deleteWhere { AttendanceTable.meetingId inList meetingIds }
            AgendaItemTable.deleteWhere { AgendaItemTable.meetingId inList meetingIds }
            MeetingTable.deleteWhere { MeetingTable.id inList meetingIds }
        }
        if (committeeIds.isNotEmpty()) {
            CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList committeeIds }
            CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
        }
        if (memberIds.isNotEmpty()) {
            // V0.5.2: addCommitteeMember/endCommitteeMembership on an EXECUTIVE_BOARD Committee
            // (several tests in this file use one, see createTestCommittee helpers) now hook into
            // BoardMembershipEvents.recordBoardJoin/recordBoardLeave, auto-creating
            // board_membership/transparenzregister_reminder rows -- both carry a member_id FK that
            // must be cleared before MemberTable rows are deleted, same as
            // cleanUpElectionTestData/cleanUpBoardMembershipTestData already do.
            TransparenzregisterReminderTable.update({ TransparenzregisterReminderTable.resolvedBy inList memberIds }) {
                it[resolvedBy] = null
            }
            TransparenzregisterReminderTable.deleteWhere { TransparenzregisterReminderTable.memberId inList memberIds }
            BoardMembershipTable.deleteWhere { BoardMembershipTable.memberId inList memberIds }
            LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList memberIds }
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}
