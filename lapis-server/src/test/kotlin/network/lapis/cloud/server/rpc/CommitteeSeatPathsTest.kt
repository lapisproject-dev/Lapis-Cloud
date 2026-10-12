package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.keycloak.KeycloakConfig
import network.lapis.cloud.server.mail.FakeAdminPasswordResetNotificationMailer
import network.lapis.cloud.server.mail.FakeFriendVerificationMailer
import network.lapis.cloud.server.mail.FakePasswordResetMailer
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeAutoEndNoticeDto
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeSeatAutoEndReason
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private fun today(): LocalDate = OrganizationTimeZone.dateOf(DbClock.nowLocalDateTime())

/**
 * Welle V1.9.102 -- the three real call paths of the automatic seat end (admin status change, self-service exit, application rejection),
 * the paths that must NOT end seats, the audit order (seat audits right before the MEMBER audit), the permission gates in front of the hook
 * and the read side (notice band, visibility of the reason). Runs on H2; the lifecycle itself is also covered on PostgreSQL by
 * [CommitteeSeatLifecyclePostgresTest].
 */
class CommitteeSeatPathsTest :
    FunSpec({
        val fx = CommitteeSeatFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanUp() }

        fun memberService(call: io.ktor.server.application.ApplicationCall) =
            MemberService(
                call = call,
                passwordResetMailer = FakePasswordResetMailer(),
                adminPasswordResetNotificationMailer = FakeAdminPasswordResetNotificationMailer(),
                smtpConfigState = SmtpConfigState.NotConfigured,
                adminPasswordMailTargetRateLimiter = FederationInboxRateLimiter(),
                adminPasswordMailActorRateLimiter = FederationInboxRateLimiter(),
                adminPasswordNotificationTargetRateLimiter = FederationInboxRateLimiter(),
                memberCardIssueRateLimiter = FederationInboxRateLimiter(),
                memberAddressAdminReadRateLimiter = FederationInboxRateLimiter(),
                regionalChapterEnforcementConfig = RegionalChapterEnforcementConfig.load(env = { null }),
            )

        fun registrationService(call: io.ktor.server.application.ApplicationCall) =
            RegistrationService(
                call = call,
                registrationRateLimiter = LoginRateLimiter(),
                friendRegistrationRateLimiter = LoginRateLimiter(),
                friendSignupIpRateLimiter = FederationInboxRateLimiter(),
                friendVerificationMailer = FakeFriendVerificationMailer(),
                keycloakConfig = KeycloakConfig.load(env = { null }),
                regionalChapterEnforcementConfig = RegionalChapterEnforcementConfig.load(env = { null }),
            )

        fun routes(): Application.() -> Unit =
            {
                install(StatusPages) {
                    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                }
                routing {
                    post("/t/status/{id}") {
                        val death = call.request.queryParameters["death"]?.let { LocalDate.parse(it) }
                        val dto =
                            memberService(call).updateMemberStatus(
                                memberId = call.parameters["id"]!!,
                                newStatus = MemberStatus.valueOf(call.request.queryParameters["status"]!!),
                                reason = "Testgrund fuer Statuswechsel",
                                dateOfDeath = death,
                            )
                        call.respondText(dto.status.name)
                    }
                    post("/t/leave") { call.respondText(registrationService(call).leaveMembership().status.name) }
                    post("/t/reject/{id}") {
                        call.respondText(
                            registrationService(
                                call,
                            ).rejectApplication(memberId = call.parameters["id"]!!, reason = "Testablehnung").member.status.name,
                        )
                    }
                    post(
                        "/t/approve/{id}",
                    ) {
                        call.respondText(
                            registrationService(call)
                                .approveApplication(memberId = call.parameters["id"]!!)
                                .member.status.name,
                        )
                    }
                    get("/t/members/{committee}") {
                        val all = call.request.queryParameters["all"] == "true"
                        val list =
                            GovernanceService(
                                call = call,
                            ).listCommitteeMembers(committeeId = call.parameters["committee"]!!, activeOnly = !all)
                        call.respondText(Json.encodeToString(ListSerializer(CommitteeMembershipDto.serializer()), list))
                    }
                    get("/t/notice") {
                        call.respondText(
                            Json.encodeToString(
                                CommitteeAutoEndNoticeDto.serializer(),
                                GovernanceService(call = call).getCommitteeAutoEndNotice(),
                            ),
                        )
                    }
                    post("/t/endseat/{id}") {
                        GovernanceService(
                            call = call,
                        ).endCommitteeMembership(
                            membershipId = call.parameters["id"]!!,
                            until = LocalDate.parse(call.request.queryParameters["until"]!!),
                        )
                        call.respondText("OK")
                    }
                }
            }

        test("updateMemberStatus ACTIVE -> WITHDRAWN / DONOR / DECEASED ends every open seat on today with that reason") {
            testApplication {
                application(routes())
                val admin = fx.member(role = AccountRole.ADMIN)
                val board = fx.committee(type = CommitteeType.EXECUTIVE_BOARD)
                val group = fx.committee(type = CommitteeType.WORKING_GROUP)
                listOf(MemberStatus.WITHDRAWN, MemberStatus.DONOR, MemberStatus.DECEASED).forEach { target ->
                    val member = fx.member()
                    val seatA = fx.seat(committee = board, member = member, role = CommitteeRole.SECRETARY)
                    val seatB = fx.seat(committee = group, member = member)
                    val mark = fx.auditMark()

                    val death = if (target == MemberStatus.DECEASED) "&death=${today().minus(5, DateTimeUnit.DAY)}" else ""
                    client.post("/t/status/$member?status=${target.name}$death") { header("X-Member-Id", admin.toString()) }.status shouldBe
                        HttpStatusCode.OK

                    // until is the day of the status change, also for a death some days ago
                    listOf(seatA, seatB).forEach {
                        fx.seatRow(it)[CommitteeMembershipTable.until] shouldBe today()
                        fx.seatRow(it)[CommitteeMembershipTable.autoEndReason] shouldBe CommitteeSeatAutoEndReason.valueOf(target.name)
                    }
                    val seatAudits = fx.auditSince(mark = mark, type = AuditEntityType.COMMITTEE_MEMBERSHIP)
                    seatAudits shouldHaveSize 2
                    seatAudits.forEach { it[AuditLogEntryTable.actorMemberId] shouldBe admin }
                    seatAudits.forEach { it[AuditLogEntryTable.afterSnapshot]!! shouldNotContain "dateOfDeath" }
                    // the MEMBER audit keeps the human actor and is written AFTER the seat audits (phase 2 directly before it)
                    val memberAudit =
                        fx.auditSince(mark = mark, type = AuditEntityType.MEMBER).single {
                            it[AuditLogEntryTable.entityId] ==
                                member
                        }
                    memberAudit[AuditLogEntryTable.actorMemberId] shouldBe admin
                    (seatAudits.maxOf { it[AuditLogEntryTable.sequenceNumber] } < memberAudit[AuditLogEntryTable.sequenceNumber]) shouldBe
                        true
                    fx.auditSince(mark = mark, type = AuditEntityType.BOARD_MEMBERSHIP).shouldBeEmpty() // no open board membership existed
                    fx.chainProblemSince(mark) shouldBe null
                }
            }
        }

        test("an EXECUTIVE_BOARD seat ended through the status change also closes the board membership (actor = admin)") {
            testApplication {
                application(routes())
                val admin = fx.member(role = AccountRole.ADMIN)
                val member = fx.member()
                fx.seat(committee = fx.committee(type = CommitteeType.EXECUTIVE_BOARD), member = member, role = CommitteeRole.SECRETARY)
                val bm = fx.boardMembership(member = member, role = CommitteeRole.SECRETARY)
                val mark = fx.auditMark()
                client.post("/t/status/$member?status=WITHDRAWN") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK
                fx.boardRow(bm)[BoardMembershipTable.endedAt] shouldBe today()
                val boardAudit = fx.auditSince(mark = mark, type = AuditEntityType.BOARD_MEMBERSHIP).single()
                boardAudit[AuditLogEntryTable.actorMemberId] shouldBe admin
                fx.chainProblemSince(mark) shouldBe null
            }
        }

        test("leaveMembership (self-service exit) ends the seats with reason WITHDRAWN") {
            testApplication {
                application(routes())
                val member = fx.member()
                val seat = fx.seat(committee = fx.committee(), member = member)
                val mark = fx.auditMark()
                client.post("/t/leave") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.OK
                fx.seatRow(seat)[CommitteeMembershipTable.until] shouldBe today()
                fx.seatRow(seat)[CommitteeMembershipTable.autoEndReason] shouldBe CommitteeSeatAutoEndReason.WITHDRAWN
                val seatAudit = fx.auditSince(mark = mark, type = AuditEntityType.COMMITTEE_MEMBERSHIP).single()
                seatAudit[AuditLogEntryTable.actorMemberId] shouldBe member
                fx.chainProblemSince(mark) shouldBe null
            }
        }

        test("rejectApplication ends the seats with reason REJECTED -- also when the status falls back to FRIEND") {
            testApplication {
                application(routes())
                val board = fx.member(role = AccountRole.BOARD)
                val plain = fx.member(status = MemberStatus.APPLICATION)
                val former = fx.member(status = MemberStatus.APPLICATION, friendSince = LocalDate(2026, 1, 1))
                val committee = fx.committee()
                val plainSeat = fx.seat(committee = committee, member = plain)
                val formerSeat = fx.seat(committee = committee, member = former)
                val mark = fx.auditMark()

                client.post("/t/reject/$plain") { header("X-Member-Id", board.toString()) }.bodyAsText() shouldBe "REJECTED"
                client.post("/t/reject/$former") { header("X-Member-Id", board.toString()) }.bodyAsText() shouldBe "FRIEND"

                listOf(plainSeat, formerSeat).forEach {
                    fx.seatRow(it)[CommitteeMembershipTable.until] shouldBe today()
                    fx.seatRow(it)[CommitteeMembershipTable.autoEndReason] shouldBe CommitteeSeatAutoEndReason.REJECTED
                }
                fx.auditSince(mark = mark, type = AuditEntityType.COMMITTEE_MEMBERSHIP) shouldHaveSize 2
                fx.chainProblemSince(mark) shouldBe null
            }
        }

        test("status changes that end nothing leave seats and the audit log alone: approval, and a return to ACTIVE restores nothing") {
            testApplication {
                application(routes())
                val admin = fx.member(role = AccountRole.ADMIN)
                val applicant = fx.member(status = MemberStatus.APPLICATION)
                val seat = fx.seat(committee = fx.committee(), member = applicant)
                val mark = fx.auditMark()
                client.post("/t/approve/$applicant") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.OK
                fx.seatRow(seat)[CommitteeMembershipTable.until] shouldBe null
                fx.auditSince(mark = mark, type = AuditEntityType.COMMITTEE_MEMBERSHIP).shouldBeEmpty()

                // withdraw (ends the seat), come back: no new seat, the ended one stays ended
                client.post("/t/status/$applicant?status=WITHDRAWN") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK
                client.post("/t/status/$applicant?status=ACTIVE") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK
                fx.seatsOf(applicant) shouldHaveSize 1
                fx.seatRow(seat)[CommitteeMembershipTable.until] shouldBe today()
                fx.seatRow(seat)[CommitteeMembershipTable.autoEndReason] shouldBe CommitteeSeatAutoEndReason.WITHDRAWN
            }
        }

        test("the permission gates stay in front of the hook: BOARD against an ADMIN target and a status change of oneself end nothing") {
            testApplication {
                application(routes())
                val admin = fx.member(role = AccountRole.ADMIN)
                val board = fx.member(role = AccountRole.BOARD)
                val adminSeat = fx.seat(committee = fx.committee(), member = admin)
                val mark = fx.auditMark()

                client.post("/t/status/$admin?status=WITHDRAWN") { header("X-Member-Id", board.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                // an ADMIN cannot change their own status either
                (
                    client
                        .post(
                            "/t/status/$admin?status=WITHDRAWN",
                        ) { header("X-Member-Id", admin.toString()) }
                        .status == HttpStatusCode.OK
                ) shouldBe
                    false

                fx.seatRow(adminSeat)[CommitteeMembershipTable.until] shouldBe null
                fx.seatRow(adminSeat)[CommitteeMembershipTable.autoEndReason] shouldBe null
                fx.auditSince(mark = mark, type = AuditEntityType.COMMITTEE_MEMBERSHIP).shouldBeEmpty()
            }
        }

        test("getCommitteeAutoEndNotice: BOARD and ADMIN may read it, a plain member gets 403") {
            testApplication {
                application(routes())
                client.get("/t/notice") { header("X-Member-Id", fx.member(role = AccountRole.BOARD).toString()) }.status shouldBe
                    HttpStatusCode.OK
                client.get("/t/notice") { header("X-Member-Id", fx.member(role = AccountRole.ADMIN).toString()) }.status shouldBe
                    HttpStatusCode.OK
                client.get("/t/notice") { header("X-Member-Id", fx.member(role = AccountRole.MEMBER).toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("the notice: window of 30 days, newest first, vacant chair per executive board, legacy open seats of non-members") {
            testApplication {
                application(routes())
                val board = fx.member(role = AccountRole.BOARD)
                val board1 = fx.committee(type = CommitteeType.EXECUTIVE_BOARD, name = "Vorstand Notice A")
                val group = fx.committee(type = CommitteeType.WORKING_GROUP, name = "AG Notice")
                val left = fx.member(status = MemberStatus.WITHDRAWN)
                val recent =
                    fx.seat(
                        committee = group,
                        member = left,
                        until = today().minus(2, DateTimeUnit.DAY),
                        autoEndReason = CommitteeSeatAutoEndReason.WITHDRAWN,
                    )
                val old =
                    fx.seat(
                        committee = group,
                        member = left,
                        until = today().minus(31, DateTimeUnit.DAY),
                        autoEndReason = CommitteeSeatAutoEndReason.WITHDRAWN,
                    )
                val manual = fx.seat(committee = group, member = left, until = today().minus(1, DateTimeUnit.DAY))
                // a board that never had a chair / lost it counts as vacant, whatever the cause
                val chairless = fx.committee(type = CommitteeType.EXECUTIVE_BOARD, name = "Vorstand ohne Vorsitz")
                fx.seat(committee = board1, member = fx.member(), role = CommitteeRole.CHAIR)
                // legacy: DONOR with a still-open seat appears, an ACTIVE member with an open seat does not
                val donorLegacy = fx.member(status = MemberStatus.DONOR)
                val donorSeat = fx.seat(committee = group, member = donorLegacy)
                val activeSeat = fx.seat(committee = group, member = fx.member())

                val notice =
                    Json.decodeFromString(
                        CommitteeAutoEndNoticeDto.serializer(),
                        client.get("/t/notice") { header("X-Member-Id", board.toString()) }.bodyAsText(),
                    )
                val mine = notice.recentAutoEnds.filter { it.committeeId == group.toString() }
                mine.map { it.membershipId } shouldBe listOf(recent.toString())
                (old.toString() in notice.recentAutoEnds.map { it.membershipId }) shouldBe false
                (manual.toString() in notice.recentAutoEnds.map { it.membershipId }) shouldBe false
                notice.vacantMandatoryRoles.map { it.committeeId } shouldContain chairless.toString()
                notice.vacantMandatoryRoles.map { it.committeeId }.contains(board1.toString()) shouldBe false
                notice.openSeatsWithoutMembership.map { it.membershipId } shouldContain donorSeat.toString()
                notice.openSeatsWithoutMembership.map { it.membershipId }.contains(activeSeat.toString()) shouldBe false
                notice.openSeatsWithoutMembership.single { it.membershipId == donorSeat.toString() }.memberStatus shouldBe
                    MemberStatus.DONOR
            }
        }

        test("a seat the hook ended today is listed as an automatic end, not as an open legacy seat") {
            testApplication {
                application(routes())
                val admin = fx.member(role = AccountRole.ADMIN)
                val board = fx.member(role = AccountRole.BOARD)
                val member = fx.member()
                val seat = fx.seat(committee = fx.committee(type = CommitteeType.WORKING_GROUP), member = member)
                client.post("/t/status/$member?status=DONOR") { header("X-Member-Id", admin.toString()) }.status shouldBe HttpStatusCode.OK
                val notice =
                    Json.decodeFromString(
                        CommitteeAutoEndNoticeDto.serializer(),
                        client.get("/t/notice") { header("X-Member-Id", board.toString()) }.bodyAsText(),
                    )
                notice.recentAutoEnds.map { it.membershipId } shouldContain seat.toString()
                notice.openSeatsWithoutMembership.map { it.membershipId }.contains(seat.toString()) shouldBe false
            }
        }

        test("the notice is capped at 50 entries per list") {
            testApplication {
                application(routes())
                val board = fx.member(role = AccountRole.BOARD)
                val group = fx.committee(type = CommitteeType.WORKING_GROUP)
                val left = fx.member(status = MemberStatus.WITHDRAWN)
                repeat(
                    55,
                ) {
                    fx.seat(
                        committee = group,
                        member = left,
                        until = today().minus(1, DateTimeUnit.DAY),
                        autoEndReason = CommitteeSeatAutoEndReason.WITHDRAWN,
                    )
                }
                val notice =
                    Json.decodeFromString(
                        CommitteeAutoEndNoticeDto.serializer(),
                        client.get("/t/notice") { header("X-Member-Id", board.toString()) }.bodyAsText(),
                    )
                notice.recentAutoEnds shouldHaveSize 50
            }
        }

        test("listCommitteeMembers: the auto-end reason reaches BOARD/ADMIN only, a plain member always sees null") {
            testApplication {
                application(routes())
                val group = fx.committee()
                val left = fx.member(status = MemberStatus.DECEASED)
                fx.seat(committee = group, member = left, until = today(), autoEndReason = CommitteeSeatAutoEndReason.DECEASED)
                val plain = fx.member(role = AccountRole.MEMBER)
                val board = fx.member(role = AccountRole.BOARD)

                fun reasons(body: String) =
                    Json.decodeFromString(ListSerializer(CommitteeMembershipDto.serializer()), body).map { it.autoEndReason }

                reasons(client.get("/t/members/$group?all=true") { header("X-Member-Id", plain.toString()) }.bodyAsText()) shouldBe
                    listOf(null)
                reasons(client.get("/t/members/$group?all=true") { header("X-Member-Id", board.toString()) }.bodyAsText()) shouldBe
                    listOf(CommitteeSeatAutoEndReason.DECEASED)
                // with activeOnly the ended row is not listed at all
                reasons(client.get("/t/members/$group") { header("X-Member-Id", board.toString()) }.bodyAsText()).shouldBeEmpty()
            }
        }

        test("the manual end resets the reason on a previously automatic end, and the response shows the human path (reason null)") {
            testApplication {
                application(routes())
                val board = fx.member(role = AccountRole.BOARD)
                val group = fx.committee()
                val left = fx.member(status = MemberStatus.WITHDRAWN)
                val seat =
                    fx.seat(
                        committee = group,
                        member = left,
                        until = today().minus(3, DateTimeUnit.DAY),
                        autoEndReason = CommitteeSeatAutoEndReason.WITHDRAWN,
                    )
                client
                    .post(
                        "/t/endseat/$seat?until=${today().minus(2, DateTimeUnit.DAY)}",
                    ) { header("X-Member-Id", board.toString()) }
                    .status shouldBe
                    HttpStatusCode.OK
                fx.seatRow(seat)[CommitteeMembershipTable.autoEndReason] shouldBe null
                // a plain automatic row no longer shows up in the notice
                val notice =
                    Json.decodeFromString(
                        CommitteeAutoEndNoticeDto.serializer(),
                        client.get("/t/notice") { header("X-Member-Id", board.toString()) }.bodyAsText(),
                    )
                notice.recentAutoEnds.map { it.membershipId }.contains(seat.toString()) shouldBe false
                transaction { fx.seatRow(seat)[CommitteeMembershipTable.until] } shouldBe today().minus(2, DateTimeUnit.DAY)
            }
        }
    })
