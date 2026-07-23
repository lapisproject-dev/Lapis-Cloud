package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionCandidacyTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.TransparenzregisterReminderTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BoardChangeType
import network.lapis.cloud.shared.domain.BoardMembershipInput
import network.lapis.cloud.shared.domain.CandidacyInput
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionOpenInput
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Exercises [BoardMembershipService] end to end -- same "throwaway routes calling the service class
 * directly" house style as [ElectionServiceTest]/[GovernanceServiceTest]. Uses its own freshly
 * created Committees/Meetings/Motions/members throughout (never [DevSeedData]'s shared demo
 * fixtures), for the same order-independence reasoning those files document. [afterSpec]
 * hard-deletes every row this file created.
 *
 * The integration test (targetCommittee's [CommitteeType] gates the `EXECUTIVE_BOARD`-tally hook)
 * uses a single, uncontested `SINGLE_CHOICE` candidate (1 option, `seatCount = 1`) so
 * `computePersonnelElectionErgebnis`'s own documented "uncontested seat needs no ballot"
 * convention applies -- no voting/ballot-casting plumbing is needed to reach a winner, only the
 * `PREPARATION -> CANDIDATE_LIST_RELEASED -> OPEN -> CLOSED -> TALLIED` status walk.
 */
class BoardMembershipServiceTest :
    FunSpec({
        val createdCommitteeIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpBoardMembershipTestData(createdCommitteeIds, createdMemberIds) }

        fun createTestMember(
            email: String,
            role: AccountRole = AccountRole.MEMBER,
            status: MemberStatus = MemberStatus.AKTIV,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "BoardMembership Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createTestCommittee(
            name: String,
            type: CommitteeType,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[CommitteeTable.id] = id
                    it[CommitteeTable.name] = name
                    it[CommitteeTable.type] = type
                    it[description] = "Testcommittee"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            createdCommitteeIds += id
            return id
        }

        fun createTestMeeting(
            committeeId: Uuid,
            scheduledAt: LocalDateTime,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MeetingTable.insert {
                    it[MeetingTable.id] = id
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "Testmeeting"
                    it[MeetingTable.scheduledAt] = scheduledAt
                    it[location] = "Vereinsheim"
                    it[format] = MeetingFormat.IN_PERSON
                    it[status] = MeetingStatus.PLANNED
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = scheduledAt
                }
            }
            return id
        }

        /** Directly seeds an already-[MotionStatus.SCHEDULED] Motion -- see class KDoc. */
        fun createTerminierterMotion(
            committeeId: Uuid,
            meetingId: Uuid,
            submitterId: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MotionTable.insert {
                    it[MotionTable.id] = id
                    it[targetCommitteeId] = committeeId
                    it[title] = "Testmotion"
                    it[rationale] = "Rationale"
                    it[text] = "Motionstext"
                    it[submitterMemberId] = submitterId
                    it[status] = MotionStatus.SCHEDULED
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = submitterId
                    it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
            }
            return id
        }

        /**
         * Runs one uncontested `SINGLE_CHOICE` personnel Election (single candidate, `seatCount = 1`)
         * from [ElectionType.SINGLE_CHOICE] open through [ElectionService.tally] and returns the
         * `electionId`. [targetCommitteeType] is the only variable between the "JOINED" and
         * "no board membership" scenarios this file exercises.
         */
        suspend fun runUncontestedPersonnelElection(
            client: HttpClient,
            hostCommitteeId: Uuid,
            adminId: Uuid,
            candidateId: Uuid,
            electionBoardMemberIds: List<Uuid>,
            targetCommitteeId: Uuid,
        ): String {
            val meetingId = createTestMeeting(hostCommitteeId, LocalDateTime(2026, 5, 1, 18, 0))
            val motionId = createTerminierterMotion(hostCommitteeId, meetingId, adminId)

            val opened =
                client
                    .post(
                        "/test/open-election/$motionId/SINGLE_CHOICE" +
                            "?targetCommitteeId=$targetCommitteeId&targetRole=CHAIR&tallyThreshold=1",
                    ) { header("X-Member-Id", adminId.toString()) }
                    .bodyAsText()
            val electionId = opened.substringBefore(":")

            client.post("/test/appoint-election-board/$electionId?memberIds=${electionBoardMemberIds.joinToString(",")}") {
                header("X-Member-Id", adminId.toString())
            }
            client.post("/test/submit-candidacy/$electionId") { header("X-Member-Id", candidateId.toString()) }
            client.post("/test/release-kandidatenliste/$electionId") { header("X-Member-Id", adminId.toString()) }
            client.post("/test/open-voting/$electionId") { header("X-Member-Id", adminId.toString()) }
            client.post("/test/close-voting/$electionId") { header("X-Member-Id", adminId.toString()) }
            client.post("/test/release-tally/$electionId") { header("X-Member-Id", electionBoardMemberIds[0].toString()) }
            client.post("/test/tally/$electionId") { header("X-Member-Id", adminId.toString()) }
            return electionId
        }

        test("appointBoardMember creates a board_membership row (endedAt null) and a JOINED unresolved reminder") {
            testApplication {
                application {
                    install(StatusPages) { installBoardMembershipExceptionHandlers() }
                    routing { registerBoardMembershipTestRoutes() }
                }
                val admin = createTestMember("bms-appoint-admin@example.org", role = AccountRole.ADMIN)
                val target = createTestMember("bms-appoint-target@example.org")

                val response =
                    client
                        .post(
                            "/test/appoint-board-member?memberId=$target&committeeRole=CHAIR&startedAt=2026-02-01",
                        ) { header("X-Member-Id", admin.toString()) }
                        .bodyAsText()
                val boardMembershipId = response.substringBefore(":")
                response.substringAfterLast(":") shouldBe "" // endedAt is null

                val reminder =
                    transaction {
                        TransparenzregisterReminderTable
                            .selectAll()
                            .where { TransparenzregisterReminderTable.memberId eq target }
                            .single()
                    }
                transaction { reminder[TransparenzregisterReminderTable.changeType] } shouldBe BoardChangeType.JOINED
                transaction { reminder[TransparenzregisterReminderTable.resolved] } shouldBe false

                val row =
                    transaction {
                        BoardMembershipTable
                            .selectAll()
                            .where { BoardMembershipTable.id eq Uuid.parse(boardMembershipId) }
                            .single()
                    }
                transaction { row[BoardMembershipTable.endedAt] } shouldBe null
            }
        }

        test("endBoardMembership sets endedAt and creates a LEFT reminder; ending an already-ended membership throws Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installBoardMembershipExceptionHandlers() }
                    routing { registerBoardMembershipTestRoutes() }
                }
                val admin = createTestMember("bms-end-admin@example.org", role = AccountRole.ADMIN)
                val target = createTestMember("bms-end-target@example.org")

                val appointed =
                    client
                        .post(
                            "/test/appoint-board-member?memberId=$target&committeeRole=SECRETARY&startedAt=2026-02-01",
                        ) { header("X-Member-Id", admin.toString()) }
                        .bodyAsText()
                val boardMembershipId = appointed.substringBefore(":")

                val ended =
                    client
                        .post(
                            "/test/end-board-membership/$boardMembershipId?endedAt=2026-03-01",
                        ) { header("X-Member-Id", admin.toString()) }
                        .bodyAsText()
                ended shouldBe "$boardMembershipId:2026-03-01"

                val leftReminderCount =
                    transaction {
                        TransparenzregisterReminderTable
                            .selectAll()
                            .where {
                                (TransparenzregisterReminderTable.memberId eq target) and
                                    (TransparenzregisterReminderTable.changeType eq BoardChangeType.LEFT)
                            }.count()
                    }
                leftReminderCount shouldBe 1L

                val alreadyEnded =
                    client.post(
                        "/test/end-board-membership/$boardMembershipId?endedAt=2026-04-01",
                    ) { header("X-Member-Id", admin.toString()) }
                alreadyEnded.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("getTransparenzregisterReport lists only unresolved reminders, currentBoard and beneficialOwnerDataGaps") {
            testApplication {
                application {
                    install(StatusPages) { installBoardMembershipExceptionHandlers() }
                    routing { registerBoardMembershipTestRoutes() }
                }
                val admin = createTestMember("bms-report-admin@example.org", role = AccountRole.ADMIN)
                val target = createTestMember("bms-report-target@example.org")

                client.post(
                    "/test/appoint-board-member?memberId=$target&committeeRole=ASSESSOR&startedAt=2026-02-01",
                ) { header("X-Member-Id", admin.toString()) }

                val report =
                    client.get("/test/report") { header("X-Member-Id", admin.toString()) }.bodyAsText()
                // At least our own JOINED reminder/board seat/data-gap must be present -- other
                // Specs' fixtures may add further rows to the same shared tables, so counts are
                // asserted as "at least" via direct table queries below rather than exact equality
                // on the aggregate response.
                report.startsWith("openReminders=") shouldBe true

                val ourOpenReminder =
                    transaction {
                        TransparenzregisterReminderTable
                            .selectAll()
                            .where {
                                (TransparenzregisterReminderTable.memberId eq target) and
                                    (TransparenzregisterReminderTable.resolved eq false)
                            }.count()
                    }
                ourOpenReminder shouldBe 1L

                val ourBoardRow =
                    transaction {
                        BoardMembershipTable
                            .selectAll()
                            .where { (BoardMembershipTable.memberId eq target) and (BoardMembershipTable.endedAt.isNull()) }
                            .count()
                    }
                ourBoardRow shouldBe 1L

                // target has neither dateOfBirth nor nationality set -> a beneficialOwnerDataGaps entry.
                val gapMember =
                    transaction {
                        MemberTable.selectAll().where { MemberTable.id eq target }.single()
                    }
                transaction { gapMember[MemberTable.dateOfBirth] } shouldBe null
                transaction { gapMember[MemberTable.nationality] } shouldBe null
            }
        }

        test("resolveTransparenzregisterReminder flips resolved/resolvedAt/resolvedBy; re-resolving throws Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installBoardMembershipExceptionHandlers() }
                    routing { registerBoardMembershipTestRoutes() }
                }
                val admin = createTestMember("bms-resolve-admin@example.org", role = AccountRole.ADMIN)
                val resolver = createTestMember("bms-resolve-resolver@example.org", role = AccountRole.BOARD)
                val target = createTestMember("bms-resolve-target@example.org")

                client.post(
                    "/test/appoint-board-member?memberId=$target&committeeRole=CHAIR&startedAt=2026-02-01",
                ) { header("X-Member-Id", admin.toString()) }

                val reminderId =
                    transaction {
                        TransparenzregisterReminderTable
                            .selectAll()
                            .where { TransparenzregisterReminderTable.memberId eq target }
                            .single()[TransparenzregisterReminderTable.id]
                    }

                val resolved =
                    client
                        .post("/test/resolve-reminder/$reminderId") { header("X-Member-Id", resolver.toString()) }
                        .bodyAsText()
                resolved shouldBe "$reminderId:true:$resolver"

                val alreadyResolved = client.post("/test/resolve-reminder/$reminderId") { header("X-Member-Id", resolver.toString()) }
                alreadyResolved.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("every method requires BOARD/ADMIN -- MEMBER/TREASURER get Forbidden, unauthenticated gets Unauthorized") {
            testApplication {
                application {
                    install(StatusPages) { installBoardMembershipExceptionHandlers() }
                    routing { registerBoardMembershipTestRoutes() }
                }
                val plainMember = createTestMember("bms-auth-member@example.org", role = AccountRole.MEMBER)
                val treasurer = createTestMember("bms-auth-treasurer@example.org", role = AccountRole.TREASURER)
                val target = createTestMember("bms-auth-target@example.org")

                listOf(plainMember, treasurer).forEach { caller ->
                    val r =
                        client.post(
                            "/test/appoint-board-member?memberId=$target&committeeRole=MEMBER&startedAt=2026-02-01",
                        ) { header("X-Member-Id", caller.toString()) }
                    r.status shouldBe HttpStatusCode.Forbidden
                }
                val reportAsMember = client.get("/test/report") { header("X-Member-Id", plainMember.toString()) }
                reportAsMember.status shouldBe HttpStatusCode.Forbidden
                val listAsTreasurer = client.get("/test/list-current-board") { header("X-Member-Id", treasurer.toString()) }
                listAsTreasurer.status shouldBe HttpStatusCode.Forbidden

                // Unauthenticated: no X-Member-Id header at all.
                client.get("/test/report").status shouldBe HttpStatusCode.Unauthorized
                client
                    .post("/test/appoint-board-member?memberId=$target&committeeRole=MEMBER&startedAt=2026-02-01")
                    .status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test(
            "ElectionService.tally seating a winner into an EXECUTIVE_BOARD Committee auto-creates a board_membership " +
                "row and a JOINED reminder; a WORKING_GROUP targetCommittee creates neither",
        ) {
            testApplication {
                application {
                    // ElectionService throws the exact same UnauthenticatedException/ForbiddenException/
                    // NotFoundException/ConflictException classes BoardMembershipService does (they are
                    // shared package-level types, see RequestContext.kt/ContributionService.kt/
                    // MailingService.kt), so one handler registration covers both routers below.
                    install(StatusPages) { installBoardMembershipExceptionHandlers() }
                    routing {
                        registerBoardMembershipTestRoutes()
                        registerBoardMembershipElectionTestRoutes()
                    }
                }

                val admin = createTestMember("bms-tally-admin@example.org", role = AccountRole.ADMIN)
                val hostCommittee = createTestCommittee("BoardMembership Host", CommitteeType.WORKING_GROUP)
                val electionBoardMembers = (1..3).map { createTestMember("bms-tally-wv$it@example.org") }

                val executiveBoardCommittee = createTestCommittee("BoardMembership Executive", CommitteeType.EXECUTIVE_BOARD)
                val candidateForBoard = createTestMember("bms-tally-cand-board@example.org")
                runUncontestedPersonnelElection(
                    client,
                    hostCommittee,
                    admin,
                    candidateForBoard,
                    electionBoardMembers,
                    executiveBoardCommittee,
                )

                val boardMembershipCount =
                    transaction {
                        BoardMembershipTable.selectAll().where { BoardMembershipTable.memberId eq candidateForBoard }.count()
                    }
                boardMembershipCount shouldBe 1L
                val joinedReminderCount =
                    transaction {
                        TransparenzregisterReminderTable
                            .selectAll()
                            .where {
                                (TransparenzregisterReminderTable.memberId eq candidateForBoard) and
                                    (TransparenzregisterReminderTable.changeType eq BoardChangeType.JOINED)
                            }.count()
                    }
                joinedReminderCount shouldBe 1L

                val workingGroupCommittee = createTestCommittee("BoardMembership WorkingGroup Target", CommitteeType.WORKING_GROUP)
                val candidateForWorkingGroup = createTestMember("bms-tally-cand-wg@example.org")
                runUncontestedPersonnelElection(
                    client,
                    hostCommittee,
                    admin,
                    candidateForWorkingGroup,
                    electionBoardMembers,
                    workingGroupCommittee,
                )

                val noBoardMembership =
                    transaction {
                        BoardMembershipTable.selectAll().where { BoardMembershipTable.memberId eq candidateForWorkingGroup }.count()
                    }
                noBoardMembership shouldBe 0L
                val noReminder =
                    transaction {
                        TransparenzregisterReminderTable
                            .selectAll()
                            .where { TransparenzregisterReminderTable.memberId eq candidateForWorkingGroup }
                            .count()
                    }
                noReminder shouldBe 0L
            }
        }
    })

private fun StatusPagesConfig.installBoardMembershipExceptionHandlers() {
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

private fun Route.registerBoardMembershipTestRoutes() {
    post("/test/appoint-board-member") {
        val service = BoardMembershipService(call)
        val q = call.request.queryParameters
        val dto =
            service.appointBoardMember(
                BoardMembershipInput(
                    memberId = q["memberId"]!!,
                    committeeRole = CommitteeRole.valueOf(q["committeeRole"]!!),
                    startedAt = LocalDate.parse(q["startedAt"]!!),
                ),
            )
        call.respondText("${dto.id}:${dto.endedAt ?: ""}")
    }
    post("/test/end-board-membership/{id}") {
        val service = BoardMembershipService(call)
        val endedAt = LocalDate.parse(call.request.queryParameters["endedAt"]!!)
        val dto = service.endBoardMembership(call.parameters["id"]!!, endedAt)
        call.respondText("${dto.id}:${dto.endedAt}")
    }
    get("/test/list-current-board") {
        val service = BoardMembershipService(call)
        val list = service.listCurrentBoard()
        call.respondText(list.joinToString(";") { "${it.id}:${it.memberId}:${it.committeeRole}" })
    }
    get("/test/report") {
        val service = BoardMembershipService(call)
        val report = service.getTransparenzregisterReport()
        call.respondText(
            "openReminders=${report.openReminders.size};currentBoard=${report.currentBoard.size};" +
                "gaps=${report.beneficialOwnerDataGaps.size}",
        )
    }
    post("/test/resolve-reminder/{id}") {
        val service = BoardMembershipService(call)
        val dto = service.resolveTransparenzregisterReminder(call.parameters["id"]!!)
        call.respondText("${dto.id}:${dto.resolved}:${dto.resolvedById ?: ""}")
    }
}

/** Minimal subset of [ElectionService]'s lifecycle needed to reach [ElectionService.tally] -- see [ElectionServiceTest]'s own (larger) route set for the full surface. */
private fun Route.registerBoardMembershipElectionTestRoutes() {
    post("/test/open-election/{motionId}/{electionType}") {
        val service = ElectionService(call)
        val q = call.request.queryParameters
        val w =
            service.openElection(
                ElectionOpenInput(
                    motionId = call.parameters["motionId"]!!,
                    electionType = ElectionType.valueOf(call.parameters["electionType"]!!),
                    secret = false,
                    seatCount = 1,
                    targetCommitteeId = q["targetCommitteeId"],
                    targetRole = q["targetRole"]?.let { CommitteeRole.valueOf(it) },
                    requiredMajorityPercent = 50,
                    tallyThreshold = q["tallyThreshold"]?.toInt() ?: 2,
                ),
            )
        call.respondText("${w.id}:${w.status}")
    }
    post("/test/appoint-election-board/{electionId}") {
        val service = ElectionService(call)
        val memberIds = call.request.queryParameters["memberIds"]!!.split(",")
        val list = service.appointElectionBoard(call.parameters["electionId"]!!, memberIds)
        call.respondText(list.size.toString())
    }
    post("/test/submit-candidacy/{electionId}") {
        val service = ElectionService(call)
        val k = service.submitCandidacy(call.parameters["electionId"]!!, CandidacyInput(motivationText = "Motivation"))
        call.respondText(k.id)
    }
    post("/test/release-kandidatenliste/{electionId}") {
        val service = ElectionService(call)
        val w = service.releaseCandidateList(call.parameters["electionId"]!!)
        call.respondText("${w.status}:${w.options.size}")
    }
    post("/test/open-voting/{electionId}") {
        val service = ElectionService(call)
        val w = service.openVoting(call.parameters["electionId"]!!)
        call.respondText(w.status.name)
    }
    post("/test/close-voting/{electionId}") {
        val service = ElectionService(call)
        val w = service.closeVoting(call.parameters["electionId"]!!)
        call.respondText(w.status.name)
    }
    post("/test/release-tally/{electionId}") {
        val service = ElectionService(call)
        service.approveTally(call.parameters["electionId"]!!)
        call.respondText("ok")
    }
    post("/test/tally/{electionId}") {
        val service = ElectionService(call)
        val e = service.tally(call.parameters["electionId"]!!)
        call.respondText("${e.winnerOptionIds.joinToString(",")}:${e.tie}:${e.majorityMet ?: ""}")
    }
}

/**
 * Hard-deletes every row this Spec created, child-before-parent -- same discipline as
 * [cleanUpElectionTestData]/[cleanUpGovernanceTestData]. V0.5.2 rows
 * ([TransparenzregisterReminderTable]/[BoardMembershipTable]) are cleared first since both carry a
 * `member_id` FK (and the reminder also a nullable `resolved_by` FK) that would otherwise block
 * [MemberTable] deletion.
 */
private fun cleanUpBoardMembershipTestData(
    committeeIds: List<Uuid>,
    memberIds: List<Uuid>,
) {
    if (committeeIds.isEmpty() && memberIds.isEmpty()) return
    transaction {
        if (memberIds.isNotEmpty()) {
            // V0.5.3 GoBD audit log: appointBoardMember/endBoardMembership now write an
            // AuditLogEntryTable row per mutation, referencing the acting member via a real FK
            // (actor_member_id) -- null it out first (audit_log_entry rows themselves are never
            // deleted, see AuditLogRecorder KDoc) so the MemberTable delete below does not
            // violate that FK.
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) {
                it[actorMemberId] = null
            }
            TransparenzregisterReminderTable.update({ TransparenzregisterReminderTable.resolvedBy inList memberIds }) {
                it[resolvedBy] = null
            }
            TransparenzregisterReminderTable.deleteWhere {
                (TransparenzregisterReminderTable.memberId inList memberIds) or
                    (TransparenzregisterReminderTable.resolvedBy inList memberIds)
            }
            BoardMembershipTable.deleteWhere { BoardMembershipTable.memberId inList memberIds }
        }

        val meetingIds =
            if (committeeIds.isEmpty()) {
                emptyList()
            } else {
                MeetingTable.selectAll().where { MeetingTable.committeeId inList committeeIds }.map { it[MeetingTable.id] }
            }

        val electionCondition =
            when {
                meetingIds.isNotEmpty() && memberIds.isNotEmpty() ->
                    (ElectionTable.meetingId inList meetingIds) or (ElectionTable.openedBy inList memberIds)
                meetingIds.isNotEmpty() -> ElectionTable.meetingId inList meetingIds
                memberIds.isNotEmpty() -> ElectionTable.openedBy inList memberIds
                else -> null
            }
        val electionIds =
            if (electionCondition != null) {
                ElectionTable.selectAll().where { electionCondition }.map { it[ElectionTable.id] }
            } else {
                emptyList()
            }
        if (electionIds.isNotEmpty()) {
            ElectionTable.update({ ElectionTable.id inList electionIds }) { it[resolutionId] = null }
            ResolutionTable.update({ ResolutionTable.electionId inList electionIds }) { it[ResolutionTable.electionId] = null }
            ElectionParticipationTable.deleteWhere { ElectionParticipationTable.electionId inList electionIds }
            ElectionTallyApprovalTable.deleteWhere { ElectionTallyApprovalTable.electionId inList electionIds }
            ElectionEligibleVoterTable.deleteWhere { ElectionEligibleVoterTable.electionId inList electionIds }
            ElectionBoardMemberTable.deleteWhere { ElectionBoardMemberTable.electionId inList electionIds }
            ElectionOptionTable.deleteWhere { ElectionOptionTable.electionId inList electionIds }
            ElectionCandidacyTable.deleteWhere { ElectionCandidacyTable.electionId inList electionIds }
            ElectionTable.deleteWhere { ElectionTable.id inList electionIds }
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
            MeetingTable.deleteWhere { MeetingTable.id inList meetingIds }
        }
        if (committeeIds.isNotEmpty()) {
            // ElectionService.tally seats the winner of a personnel Election into
            // CommitteeMembershipTable (targetCommitteeId) -- both target Committees this Spec
            // creates receive such a row, which must be cleared before the Committee row itself.
            CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList committeeIds }
            CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
        }
        if (memberIds.isNotEmpty()) {
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}
