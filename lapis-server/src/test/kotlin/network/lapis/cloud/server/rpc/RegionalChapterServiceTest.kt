package network.lapis.cloud.server.rpc

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.RegionalChapterOfficerTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RegionalChapterRules
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RegionalChapterInUseException
import network.lapis.cloud.shared.rpc.RegionalChapterLimitReachedException
import network.lapis.cloud.shared.rpc.RegionalChapterNameTakenException
import network.lapis.cloud.shared.rpc.RegionalChapterOfficerIneligibleException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" -- `testApplication`-Harness, same
 * pattern as [CarpoolServiceTest]/[MemberAdministrationTest]: a throwaway HTTP route calls the
 * service method directly, `X-Member-Id` resolves via the H2-test-mode trusted header.
 *
 * **Shared-database cleanup discipline (CLAUDE.md finding, Befund 10)**: any chapter left behind
 * by one test switches ON the "no active access without a chapter" rule for every FOLLOWING spec
 * in this same JVM's shared H2 database -- `afterSpec` therefore removes, in FK order: audit rows
 * this spec's own actors produced -> regional_chapter_officer -> member.regional_chapter_id=NULL
 * for every test-created member -> regional_chapter.
 */
class RegionalChapterServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdChapterIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                // Filtered by actorMemberId (the FK that actually blocks member deletion,
                // fk_audit_log_entry_actor_member_id), not entityId -- a REGIONAL_CHAPTER_OFFICER
                // audit row's entityId is the GRANT's own id (untracked here), so filtering by
                // entityId alone missed those rows and left the admin actor's row behind.
                AuditLogEntryTable.deleteWhere { actorMemberId inList createdMemberIds }
                RegionalChapterOfficerTable.deleteWhere { regionalChapterId inList createdChapterIds }
                RegionalChapterOfficerTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.update({ MemberTable.id inList createdMemberIds }) { it[regionalChapterId] = null }
                RegionalChapterTable.deleteWhere { id inList createdChapterIds }
                AccountTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.ACTIVE,
            role: AccountRole = AccountRole.MEMBER,
            withAccount: Boolean = true,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Landesverband-Test Mitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                if (withAccount) {
                    AccountTable.insert {
                        it[AccountTable.id] = Uuid.random()
                        it[memberId] = id
                        it[AccountTable.role] = role
                    }
                }
            }
            createdMemberIds += id
            return id
        }

        fun StatusPagesConfig.installExceptionHandlers() {
            exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
            exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
            exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
            exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
            exception<RegionalChapterNameTakenException> {
                call,
                cause,
                ->
                call.respondText(cause.message, status = HttpStatusCode.Conflict)
            }
            exception<RegionalChapterInUseException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            exception<RegionalChapterOfficerIneligibleException> { call, cause ->
                call.respondText(cause.message, status = HttpStatusCode.Conflict)
            }
            exception<RegionalChapterLimitReachedException> { call, cause ->
                call.respondText(cause.message, status = HttpStatusCode.Conflict)
            }
        }

        val testRoutes: io.ktor.server.application.Application.() -> Unit = {
            install(StatusPages) { installExceptionHandlers() }
            routing {
                post("/test/create") {
                    val service = RegionalChapterService(call)
                    val dto = service.createChapter(call.request.queryParameters["name"]!!)
                    call.respondText("${dto.id}:${dto.name}")
                }
                post("/test/rename/{id}") {
                    val service = RegionalChapterService(call)
                    val dto =
                        service.renameChapter(
                            chapterId = call.parameters["id"]!!,
                            name = call.request.queryParameters["name"]!!,
                        )
                    call.respondText(dto.name)
                }
                post("/test/delete/{id}") {
                    val service = RegionalChapterService(call)
                    service.deleteChapter(call.parameters["id"]!!)
                    call.respondText("ok")
                }
                get("/test/list") {
                    val service = RegionalChapterService(call)
                    val overview = service.listChapters()
                    call.respondText(
                        overview.chapters.joinToString(",") {
                            "${it.id}:${it.name}:${it.activeMemberCount}:${it.assignedMemberCount}:${it.activeOfficerCount}"
                        } + "|unassigned=${overview.unassignedCount}",
                    )
                }
                post("/test/assign/{memberId}") {
                    val service = RegionalChapterService(call)
                    service.assignMemberToChapter(
                        memberId = call.parameters["memberId"]!!,
                        chapterId = call.request.queryParameters["chapterId"],
                    )
                    call.respondText("ok")
                }
                post("/test/grant") {
                    val service = RegionalChapterService(call)
                    val dto =
                        service.grantOfficer(
                            memberId = call.request.queryParameters["memberId"]!!,
                            chapterId = call.request.queryParameters["chapterId"]!!,
                        )
                    call.respondText(dto.grantId)
                }
                post("/test/revoke/{grantId}") {
                    val service = RegionalChapterService(call)
                    service.revokeOfficer(call.parameters["grantId"]!!)
                    call.respondText("ok")
                }
                get("/test/officers/{chapterId}") {
                    val service = RegionalChapterService(call)
                    val list = service.listOfficers(call.parameters["chapterId"]!!)
                    call.respondText(list.joinToString(",") { it.memberId })
                }
            }
        }

        test("happy path: create, rename, empty delete") {
            testApplication {
                application(testRoutes)
                val admin = createTestMember(email = "rc-admin-1-${Uuid.random()}@example.org", role = AccountRole.ADMIN)

                val created =
                    client.post("/test/create?name=Bayern") { header("X-Member-Id", admin.toString()) }.bodyAsText()
                val chapterId = Uuid.parse(created.substringBefore(":"))
                createdChapterIds += chapterId
                created shouldBe "$chapterId:Bayern"

                val renamed =
                    client.post("/test/rename/$chapterId?name=Freistaat%20Bayern") { header("X-Member-Id", admin.toString()) }.bodyAsText()
                renamed shouldBe "Freistaat Bayern"

                val deleteResponse = client.post("/test/delete/$chapterId") { header("X-Member-Id", admin.toString()) }
                deleteResponse.status shouldBe HttpStatusCode.OK
                createdChapterIds -= chapterId
            }
        }

        test("createChapter: name taken case-insensitively") {
            testApplication {
                application(testRoutes)
                val admin = createTestMember(email = "rc-admin-2-${Uuid.random()}@example.org", role = AccountRole.ADMIN)

                val first = client.post("/test/create?name=Sachsen") { header("X-Member-Id", admin.toString()) }.bodyAsText()
                createdChapterIds += Uuid.parse(first.substringBefore(":"))

                val dup = client.post("/test/create?name=SACHSEN") { header("X-Member-Id", admin.toString()) }
                dup.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("role checks: BOARD/MEMBER rejected for ADMIN-only mutations") {
            testApplication {
                application(testRoutes)
                val board = createTestMember(email = "rc-board-1-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val member = createTestMember(email = "rc-member-1-${Uuid.random()}@example.org", role = AccountRole.MEMBER)

                for (actor in listOf(board, member)) {
                    val response = client.post("/test/create?name=Rejected-${Uuid.random()}") { header("X-Member-Id", actor.toString()) }
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        // Security fix (MEDIUM, test-coverage gap) -- the "role checks: BOARD/MEMBER rejected"
        // test above exercises ONLY /test/create. grantOfficer is the ONE path through which a
        // plain MEMBER account could gain read access to another regional chapter's roster (its
        // members' name/email/joinedAt, see RegionalChapterVisibility) -- without a regression
        // test here, a later refactor could silently loosen `requireRole(ADMIN)` to
        // `requireRole(BOARD, ADMIN)` (or drop the check entirely) and every OTHER test in this
        // file would stay green, because none of them ever calls these endpoints as a
        // non-privileged actor. Covers every remaining mutation/read this service exposes:
        // renameChapter/deleteChapter/grantOfficer/revokeOfficer/listOfficers (ADMIN only) and
        // listChapters/assignMemberToChapter (BOARD/ADMIN, so MEMBER/TREASURER must still be
        // rejected) -- plus the unauthenticated case for both role tiers. All ids used below are
        // syntactically-valid-but-nonexistent UUIDs: every one of these methods calls
        // `current.requireRole(...)` as its FIRST statement, before any id parsing/DB lookup, so
        // the Forbidden/Unauthorized check is reached regardless of whether the id resolves to a
        // real row.
        test("role checks: every remaining endpoint rejects MEMBER/TREASURER (and BOARD where ADMIN-only), unauthenticated rejected") {
            testApplication {
                application(testRoutes)
                val member = createTestMember(email = "rc-member-2-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val treasurer = createTestMember(email = "rc-treasurer-1-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val board = createTestMember(email = "rc-board-4-${Uuid.random()}@example.org", role = AccountRole.BOARD)

                val randomId = { Uuid.random().toString() }

                val adminOnlyEndpoints: Map<String, suspend (String) -> HttpResponse> =
                    mapOf(
                        "renameChapter" to { actor: String ->
                            client.post("/test/rename/${randomId()}?name=Forbidden-${Uuid.random()}") { header("X-Member-Id", actor) }
                        },
                        "deleteChapter" to { actor: String ->
                            client.post("/test/delete/${randomId()}") { header("X-Member-Id", actor) }
                        },
                        "grantOfficer" to { actor: String ->
                            client.post("/test/grant?memberId=${randomId()}&chapterId=${randomId()}") { header("X-Member-Id", actor) }
                        },
                        "revokeOfficer" to { actor: String ->
                            client.post("/test/revoke/${randomId()}") { header("X-Member-Id", actor) }
                        },
                        "listOfficers" to { actor: String ->
                            client.get("/test/officers/${randomId()}") { header("X-Member-Id", actor) }
                        },
                    )
                for ((name, call) in adminOnlyEndpoints) {
                    for (actor in listOf(member, treasurer, board)) {
                        withClue(name) { call(actor.toString()).status shouldBe HttpStatusCode.Forbidden }
                    }
                }

                val boardOrAdminEndpoints: Map<String, suspend (String) -> HttpResponse> =
                    mapOf(
                        "listChapters" to { actor: String -> client.get("/test/list") { header("X-Member-Id", actor) } },
                        "assignMemberToChapter" to { actor: String ->
                            client.post("/test/assign/${randomId()}?chapterId=${randomId()}") { header("X-Member-Id", actor) }
                        },
                    )
                for ((name, call) in boardOrAdminEndpoints) {
                    for (actor in listOf(member, treasurer)) {
                        withClue("$name") { call(actor.toString()).status shouldBe HttpStatusCode.Forbidden }
                    }
                }

                // Unauthenticated: no X-Member-Id header at all -- one representative from each
                // role tier (ADMIN-only and BOARD/ADMIN-gated).
                client.post("/test/delete/${randomId()}").status shouldBe HttpStatusCode.Unauthorized
                client.get("/test/list").status shouldBe HttpStatusCode.Unauthorized
                client.post("/test/assign/${randomId()}?chapterId=${randomId()}").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("assignMemberToChapter: BOARD may assign, wrong status rejected") {
            testApplication {
                application(testRoutes)
                val admin = createTestMember(email = "rc-admin-3-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val board = createTestMember(email = "rc-board-2-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val active = createTestMember(email = "rc-active-1-${Uuid.random()}@example.org", status = MemberStatus.ACTIVE)
                val donor = createTestMember(email = "rc-donor-1-${Uuid.random()}@example.org", status = MemberStatus.DONOR)

                val chapterId =
                    Uuid.parse(
                        client
                            .post("/test/create?name=Hessen-${Uuid.random()}") { header("X-Member-Id", admin.toString()) }
                            .bodyAsText()
                            .substringBefore(":"),
                    )
                createdChapterIds += chapterId

                val ok = client.post("/test/assign/$active?chapterId=$chapterId") { header("X-Member-Id", board.toString()) }
                ok.status shouldBe HttpStatusCode.OK

                val rejected = client.post("/test/assign/$donor?chapterId=$chapterId") { header("X-Member-Id", board.toString()) }
                rejected.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("deleteChapter: blocked while a member is assigned") {
            testApplication {
                application(testRoutes)
                val admin = createTestMember(email = "rc-admin-4-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val active = createTestMember(email = "rc-active-2-${Uuid.random()}@example.org", status = MemberStatus.ACTIVE)

                val chapterId =
                    Uuid.parse(
                        client
                            .post("/test/create?name=NRW-${Uuid.random()}") { header("X-Member-Id", admin.toString()) }
                            .bodyAsText()
                            .substringBefore(":"),
                    )
                createdChapterIds += chapterId

                client.post("/test/assign/$active?chapterId=$chapterId") { header("X-Member-Id", admin.toString()) }
                val blockedByAssignment = client.post("/test/delete/$chapterId") { header("X-Member-Id", admin.toString()) }
                blockedByAssignment.status shouldBe HttpStatusCode.Conflict

                // Unassign, then delete succeeds (F2 KDoc: withdrawn/deceased history is what stays
                // blocked -- an unassigned chapter with zero remaining rows is deletable).
                client.post("/test/assign/$active") { header("X-Member-Id", admin.toString()) }
                val deleted = client.post("/test/delete/$chapterId") { header("X-Member-Id", admin.toString()) }
                deleted.status shouldBe HttpStatusCode.OK
                createdChapterIds -= chapterId
            }
        }

        test("deleteChapter: blocked while an officer grant is active, even with no assigned member left") {
            testApplication {
                application(testRoutes)
                val admin = createTestMember(email = "rc-admin-7-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val active = createTestMember(email = "rc-active-3-${Uuid.random()}@example.org", status = MemberStatus.ACTIVE)

                val chapterId =
                    Uuid.parse(
                        client
                            .post("/test/create?name=Bremen-${Uuid.random()}") { header("X-Member-Id", admin.toString()) }
                            .bodyAsText()
                            .substringBefore(":"),
                    )
                createdChapterIds += chapterId

                client.post("/test/assign/$active?chapterId=$chapterId") { header("X-Member-Id", admin.toString()) }
                client.post("/test/grant?memberId=$active&chapterId=$chapterId") { header("X-Member-Id", admin.toString()) }

                val stillBlocked = client.post("/test/delete/$chapterId") { header("X-Member-Id", admin.toString()) }
                stillBlocked.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("grantOfficer: eligible member succeeds, ineligible member rejected, idempotent re-grant") {
            testApplication {
                application(testRoutes)
                val admin = createTestMember(email = "rc-admin-5-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val eligible = createTestMember(email = "rc-eligible-1-${Uuid.random()}@example.org", status = MemberStatus.ACTIVE)
                val noAccount =
                    createTestMember(
                        email = "rc-noaccount-1-${Uuid.random()}@example.org",
                        status = MemberStatus.ACTIVE,
                        withAccount = false,
                    )
                val wrongChapterMember =
                    createTestMember(email = "rc-wrongchapter-1-${Uuid.random()}@example.org", status = MemberStatus.ACTIVE)

                val chapterId =
                    Uuid.parse(
                        client
                            .post("/test/create?name=Berlin-${Uuid.random()}") { header("X-Member-Id", admin.toString()) }
                            .bodyAsText()
                            .substringBefore(":"),
                    )
                createdChapterIds += chapterId

                // Not yet assigned to the chapter -> ineligible.
                val notAssignedYet =
                    client.post("/test/grant?memberId=$eligible&chapterId=$chapterId") { header("X-Member-Id", admin.toString()) }
                notAssignedYet.status shouldBe HttpStatusCode.Conflict

                client.post("/test/assign/$eligible?chapterId=$chapterId") { header("X-Member-Id", admin.toString()) }
                val granted = client.post("/test/grant?memberId=$eligible&chapterId=$chapterId") { header("X-Member-Id", admin.toString()) }
                granted.status shouldBe HttpStatusCode.OK
                val grantId = granted.bodyAsText()

                // Idempotent: granting again for the SAME chapter is a no-op, not an error.
                val reGranted =
                    client.post(
                        "/test/grant?memberId=$eligible&chapterId=$chapterId",
                    ) { header("X-Member-Id", admin.toString()) }
                reGranted.status shouldBe HttpStatusCode.OK

                val noAccountResult =
                    client.post("/test/grant?memberId=$noAccount&chapterId=$chapterId") { header("X-Member-Id", admin.toString()) }
                noAccountResult.status shouldBe HttpStatusCode.Conflict

                val wrongChapterResult =
                    client.post("/test/grant?memberId=$wrongChapterMember&chapterId=$chapterId") { header("X-Member-Id", admin.toString()) }
                wrongChapterResult.status shouldBe HttpStatusCode.Conflict

                val revokeResponse = client.post("/test/revoke/$grantId") { header("X-Member-Id", admin.toString()) }
                revokeResponse.status shouldBe HttpStatusCode.OK
                // Idempotent revoke.
                val revokeAgain = client.post("/test/revoke/$grantId") { header("X-Member-Id", admin.toString()) }
                revokeAgain.status shouldBe HttpStatusCode.OK

                val officersAfterRevoke =
                    client.get("/test/officers/$chapterId") { header("X-Member-Id", admin.toString()) }.bodyAsText()
                officersAfterRevoke shouldBe ""
            }
        }

        test("one audit entry per write path") {
            testApplication {
                application(testRoutes)
                val admin = createTestMember(email = "rc-admin-6-${Uuid.random()}@example.org", role = AccountRole.ADMIN)

                val created =
                    client.post("/test/create?name=Audit-Test-${Uuid.random()}") { header("X-Member-Id", admin.toString()) }.bodyAsText()
                val chapterId = Uuid.parse(created.substringBefore(":"))
                createdChapterIds += chapterId

                val count =
                    transaction {
                        AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId eq chapterId }.count()
                    }
                count shouldBe 1L
            }
        }

        // Review-fix test-coverage gap: only createChapter's own name-uniqueness check had a test
        // ("createChapter: name taken case-insensitively" above) -- renameChapter's SEPARATE
        // `takenByAnother` check (RegionalChapterService.renameChapter) had none.
        test("renameChapter: rejected when the new name is taken by ANOTHER chapter") {
            testApplication {
                application(testRoutes)
                val admin = createTestMember(email = "rc-admin-8-${Uuid.random()}@example.org", role = AccountRole.ADMIN)

                val firstId =
                    Uuid.parse(
                        client
                            .post("/test/create?name=Saarland-${Uuid.random()}") { header("X-Member-Id", admin.toString()) }
                            .bodyAsText()
                            .substringBefore(":"),
                    )
                createdChapterIds += firstId
                val secondName = "Thueringen-${Uuid.random()}"
                val secondId =
                    Uuid.parse(
                        client
                            .post("/test/create?name=$secondName") { header("X-Member-Id", admin.toString()) }
                            .bodyAsText()
                            .substringBefore(":"),
                    )
                createdChapterIds += secondId

                val renameToTaken =
                    client.post("/test/rename/$firstId?name=$secondName") { header("X-Member-Id", admin.toString()) }
                renameToTaken.status shouldBe HttpStatusCode.Conflict

                // Renaming a chapter to ITS OWN current name (case-different) must NOT be rejected
                // as "taken by another" -- `takenByAnother`'s own `id neq id` exclusion.
                val renameToSelf =
                    client.post("/test/rename/$secondId?name=${secondName.uppercase()}") { header("X-Member-Id", admin.toString()) }
                renameToSelf.status shouldBe HttpStatusCode.OK
            }
        }

        // Review-fix test-coverage gap: RegionalChapterRules.MAX_CHAPTERS had no test at all --
        // bulk-seed directly (bypassing the HTTP round-trip for speed) up to exactly one slot
        // short of the limit, so only the BOUNDARY-crossing call goes through the real service.
        // Seed count is computed from the CURRENT row count (not assumed to start at zero) --
        // this spec's own database is shared across every `test { }` in this file (cleanup only
        // runs once, in `afterSpec`, see this class's own KDoc "Shared-database cleanup
        // discipline") -- and every chapter this test creates is deleted again BEFORE it returns
        // (not left for `afterSpec`), so it does not leave the shared database sitting AT the
        // limit for every test that runs after it.
        test("createChapter: MAX_CHAPTERS limit is enforced") {
            testApplication {
                application(testRoutes)
                val admin = createTestMember(email = "rc-admin-9-${Uuid.random()}@example.org", role = AccountRole.ADMIN)

                val existingCount = transaction { RegionalChapterTable.selectAll().count() }
                val toSeed = (RegionalChapterRules.MAX_CHAPTERS - 1 - existingCount).coerceAtLeast(0L)
                val seededIds =
                    transaction {
                        (1..toSeed).map { i ->
                            val id = Uuid.random()
                            val name = "MaxChapters-$i-${Uuid.random()}"
                            RegionalChapterTable.insert {
                                it[RegionalChapterTable.id] = id
                                it[RegionalChapterTable.name] = name
                                it[nameKey] = name.lowercase()
                                it[createdAt] = DbClock.nowLocalDateTime()
                            }
                            id
                        }
                    }
                try {
                    // Exactly one slot left -- this one must still succeed.
                    val lastAllowed =
                        client.post("/test/create?name=MaxChapters-last-${Uuid.random()}") { header("X-Member-Id", admin.toString()) }
                    lastAllowed.status shouldBe HttpStatusCode.OK
                    val lastAllowedId = Uuid.parse(lastAllowed.bodyAsText().substringBefore(":"))

                    // Now genuinely at the limit -- the NEXT create must be rejected.
                    val overLimit =
                        client.post("/test/create?name=MaxChapters-overflow-${Uuid.random()}") { header("X-Member-Id", admin.toString()) }
                    overLimit.status shouldBe HttpStatusCode.Conflict

                    transaction { RegionalChapterTable.deleteWhere { id eq lastAllowedId } }
                } finally {
                    transaction { RegionalChapterTable.deleteWhere { id inList seededIds } }
                }
            }
        }

        // Review-fix test-coverage gap: RegionalChapterRules.MAX_ACTIVE_OFFICERS_PER_CHAPTER had no
        // test at all -- bulk-create MAX_ACTIVE_OFFICERS_PER_CHAPTER eligible members+grants
        // directly (bypassing the HTTP round-trip for speed) so only the boundary-crossing grant
        // goes through the real service.
        test("grantOfficer: MAX_ACTIVE_OFFICERS_PER_CHAPTER limit is enforced") {
            testApplication {
                application(testRoutes)
                val admin = createTestMember(email = "rc-admin-10-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val chapterId =
                    Uuid.parse(
                        client
                            .post("/test/create?name=MaxOfficers-${Uuid.random()}") { header("X-Member-Id", admin.toString()) }
                            .bodyAsText()
                            .substringBefore(":"),
                    )
                createdChapterIds += chapterId

                transaction {
                    repeat(RegionalChapterRules.MAX_ACTIVE_OFFICERS_PER_CHAPTER) { i ->
                        val memberId = Uuid.random()
                        MemberTable.insert {
                            it[MemberTable.id] = memberId
                            it[displayName] = "MaxOfficers-Seed-$i"
                            it[email] = "rc-maxofficers-seed-$i-${Uuid.random()}@example.org"
                            it[status] = MemberStatus.ACTIVE
                            it[joinedAt] = LocalDate(2026, 1, 1)
                            it[membershipTierId] = null
                            it[regionalChapterId] = chapterId
                        }
                        AccountTable.insert {
                            it[AccountTable.id] = Uuid.random()
                            it[AccountTable.memberId] = memberId
                            it[role] = AccountRole.MEMBER
                        }
                        RegionalChapterOfficerTable.insert {
                            it[id] = Uuid.random()
                            it[RegionalChapterOfficerTable.memberId] = memberId
                            it[RegionalChapterOfficerTable.regionalChapterId] = chapterId
                            it[grantedAt] = DbClock.nowLocalDateTime()
                            it[grantedByMemberId] = admin
                            it[revokedAt] = null
                            it[activeForMemberId] = memberId
                        }
                        createdMemberIds += memberId
                    }
                }

                // The chapter is now at MAX_ACTIVE_OFFICERS_PER_CHAPTER -- one more eligible member
                // must be rejected.
                val oneMore = createTestMember(email = "rc-maxofficers-overflow-${Uuid.random()}@example.org", status = MemberStatus.ACTIVE)
                client.post("/test/assign/$oneMore?chapterId=$chapterId") { header("X-Member-Id", admin.toString()) }
                val overLimit =
                    client.post("/test/grant?memberId=$oneMore&chapterId=$chapterId") { header("X-Member-Id", admin.toString()) }
                overLimit.status shouldBe HttpStatusCode.Conflict
            }
        }

        // Review-fix test-coverage gap: assignMemberToChapter's own "moving an officer OUT of
        // their chapter auto-revokes their grant" behavior (RegionalChapterService
        // .assignMemberToChapter KDoc "The member's own ACTIVE officer grant...") had no test.
        test("assignMemberToChapter: moving an officer to a DIFFERENT chapter auto-revokes their old grant") {
            testApplication {
                application(testRoutes)
                val admin = createTestMember(email = "rc-admin-11-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val officer = createTestMember(email = "rc-officer-1-${Uuid.random()}@example.org", status = MemberStatus.ACTIVE)

                val chapterAId =
                    Uuid.parse(
                        client
                            .post("/test/create?name=AutoRevokeA-${Uuid.random()}") { header("X-Member-Id", admin.toString()) }
                            .bodyAsText()
                            .substringBefore(":"),
                    )
                createdChapterIds += chapterAId
                val chapterBId =
                    Uuid.parse(
                        client
                            .post("/test/create?name=AutoRevokeB-${Uuid.random()}") { header("X-Member-Id", admin.toString()) }
                            .bodyAsText()
                            .substringBefore(":"),
                    )
                createdChapterIds += chapterBId

                client.post("/test/assign/$officer?chapterId=$chapterAId") { header("X-Member-Id", admin.toString()) }
                val grantId =
                    client
                        .post("/test/grant?memberId=$officer&chapterId=$chapterAId") { header("X-Member-Id", admin.toString()) }
                        .bodyAsText()

                // Move the officer to chapter B -- their chapter-A grant must be auto-revoked (BOARD
                // performs the move, see class KDoc "D9/F9: BOARD can therefore indirectly REVOKE").
                val board = createTestMember(email = "rc-board-3-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                client.post("/test/assign/$officer?chapterId=$chapterBId") { header("X-Member-Id", board.toString()) }

                val revokedAt =
                    transaction {
                        RegionalChapterOfficerTable
                            .selectAll()
                            .where { RegionalChapterOfficerTable.id eq Uuid.parse(grantId) }
                            .single()[RegionalChapterOfficerTable.revokedAt]
                    }
                (revokedAt != null) shouldBe true

                val officersOfA =
                    client.get("/test/officers/$chapterAId") { header("X-Member-Id", admin.toString()) }.bodyAsText()
                officersOfA shouldBe ""
            }
        }

        // Security fix (LOW, Peer-Schutz) coverage -- RegionalChapterService.assignMemberToChapter
        // now applies the same ESCALATED_ROLES peer-protection boundary
        // MemberService/MemberFamilyService already establish elsewhere: a BOARD caller may not
        // reassign a fellow BOARD/TREASURER/ADMIN peer, nor themselves (BOARD is itself an
        // escalated role) -- only ADMIN may.
        test("assignMemberToChapter: BOARD may not reassign an escalated-role peer (or itself); ADMIN still may") {
            testApplication {
                application(testRoutes)
                val admin = createTestMember(email = "rc-admin-12-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val board = createTestMember(email = "rc-board-5-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val otherBoard = createTestMember(email = "rc-board-6-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val targetAdmin = createTestMember(email = "rc-admin-13-${Uuid.random()}@example.org", role = AccountRole.ADMIN)

                val chapterId =
                    Uuid.parse(
                        client
                            .post("/test/create?name=PeerProtection-${Uuid.random()}") { header("X-Member-Id", admin.toString()) }
                            .bodyAsText()
                            .substringBefore(":"),
                    )
                createdChapterIds += chapterId

                // BOARD moving a fellow BOARD peer -- rejected.
                val boardMovesPeer =
                    client.post("/test/assign/$otherBoard?chapterId=$chapterId") { header("X-Member-Id", board.toString()) }
                boardMovesPeer.status shouldBe HttpStatusCode.Forbidden

                // BOARD moving an ADMIN account -- rejected.
                val boardMovesAdmin =
                    client.post("/test/assign/$targetAdmin?chapterId=$chapterId") { header("X-Member-Id", board.toString()) }
                boardMovesAdmin.status shouldBe HttpStatusCode.Forbidden

                // BOARD moving THEMSELVES -- rejected (BOARD is itself in ESCALATED_ROLES).
                val boardMovesSelf =
                    client.post("/test/assign/$board?chapterId=$chapterId") { header("X-Member-Id", board.toString()) }
                boardMovesSelf.status shouldBe HttpStatusCode.Forbidden

                // ADMIN performing the exact same moves still succeeds.
                val adminMovesPeer =
                    client.post("/test/assign/$otherBoard?chapterId=$chapterId") { header("X-Member-Id", admin.toString()) }
                adminMovesPeer.status shouldBe HttpStatusCode.OK
                val adminMovesTargetAdmin =
                    client.post("/test/assign/$targetAdmin?chapterId=$chapterId") { header("X-Member-Id", admin.toString()) }
                adminMovesTargetAdmin.status shouldBe HttpStatusCode.OK
            }
        }
    })
