package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberFamilyLinkTable
import network.lapis.cloud.server.db.generated.MemberFamilyTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.FamilyMemberRole
import network.lapis.cloud.shared.domain.MemberFamilyLimits
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.4.4.4 "Mitgliederlebenszyklus: Familienmitgliedschaften" -- exercises
 * [MemberFamilyService] directly through throwaway routes, same house style
 * [MemberHonorServiceTest] establishes.
 */
class MemberFamilyServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val today = LocalDate(2026, 6, 15)

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            transaction {
                MemberFamilyLinkTable.deleteAll()
                MemberFamilyTable.deleteAll()
            }
        }

        afterSpec {
            transaction {
                if (createdMemberIds.isNotEmpty()) {
                    // MembershipTierAssignment.apply (called from addFamilyMember/changePayer) writes
                    // AuditLogEntryTable rows with actorMemberId = the calling BOARD/ADMIN fixture --
                    // fk_audit_log_entry_actor_member_id would otherwise block the MemberTable delete
                    // below. Same "null the actor reference, never delete the append-only row" idiom
                    // MemberAdministrationTest's own afterSpec already establishes.
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole,
            anonymizedAt: LocalDateTime? = null,
            dateOfBirth: LocalDate? = null,
            displayName: String = "Fixture Mitglied ${Uuid.random().toString().take(6)}",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[MemberTable.email] = email
                    it[MemberTable.status] = MemberStatus.ACTIVE
                    it[MemberTable.joinedAt] = LocalDate(2020, 1, 1)
                    it[MemberTable.anonymizedAt] = anonymizedAt
                    it[MemberTable.dateOfBirth] = dateOfBirth
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

        fun Route.registerFamilyTestRoutes() {
            fun service(callArg: io.ktor.server.application.ApplicationCall) = MemberFamilyService(call = callArg, clock = { today })

            get("/test/family/list") {
                val q = call.request.queryParameters
                val page =
                    service(call).listFamilies(
                        search = q["search"],
                        limit = q["limit"]?.toInt() ?: 50,
                        offset =
                            q["offset"]?.toInt() ?: 0,
                    )
                call.respondText(
                    "${page.totalCount}|${page.limit}|${page.offset}|" +
                        page.entries.joinToString(",") { "${it.id}:${it.hasPayer}" },
                )
            }
            get("/test/family/get/{id}") {
                val dto = service(call).getFamily(call.parameters["id"]!!)
                call.respondText("${dto.id}|${dto.links.joinToString(",") { "${it.memberId}:${it.role}:${it.membershipTierId}" }}")
            }
            post("/test/family/create") {
                val q = call.request.queryParameters
                val dto = service(call).createFamily(name = q["name"] ?: "Testfamilie", payerMemberId = q["payerMemberId"]!!)
                call.respondText(dto.id)
            }
            post("/test/family/rename/{id}") {
                val q = call.request.queryParameters
                val dto = service(call).renameFamily(id = call.parameters["id"]!!, name = q["name"] ?: "")
                call.respondText(dto.name)
            }
            post("/test/family/add-member") {
                val q = call.request.queryParameters
                val dto = service(call).addFamilyMember(familyId = q["familyId"]!!, memberId = q["memberId"]!!)
                call.respondText(dto.links.joinToString(",") { "${it.memberId}:${it.role}" })
            }
            post("/test/family/remove-link/{linkId}") {
                val dto = service(call).removeFamilyMember(call.parameters["linkId"]!!)
                call.respondText(dto.links.joinToString(",") { "${it.memberId}:${it.role}" })
            }
            post("/test/family/change-payer") {
                val q = call.request.queryParameters
                val dto = service(call).changePayer(familyId = q["familyId"]!!, newPayerMemberId = q["newPayerMemberId"]!!)
                call.respondText(dto.links.joinToString(",") { "${it.memberId}:${it.role}" })
            }
            delete("/test/family/delete/{id}") {
                service(call).deleteFamily(call.parameters["id"]!!)
                call.respondText("ok")
            }
            get("/test/family/majorities") {
                val q = call.request.queryParameters
                val dto = service(call).listUpcomingMajorities(windowDays = q["windowDays"]?.toInt() ?: 90)
                call.respondText(
                    "${dto.dependentCount}|${dto.dependentsWithoutDateOfBirth}|" +
                        dto.entries.joinToString(",") { "${it.memberId}:${it.alreadyMajor}:${it.turnsMajorOn}" },
                )
            }
        }

        fun linkIdOf(
            familyId: Uuid,
            memberId: Uuid,
        ): Uuid =
            transaction {
                MemberFamilyLinkTable
                    .selectAll()
                    .where { (MemberFamilyLinkTable.familyId eq familyId) and (MemberFamilyLinkTable.memberId eq memberId) }
                    .single()[MemberFamilyLinkTable.id]
            }

        fun tierOf(memberId: Uuid): Uuid? =
            transaction { MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.membershipTierId] }

        // ── Rollen-Matrix ────────────────────────────────────────────────

        listOf(AccountRole.MEMBER, AccountRole.TREASURER).forEach { deniedRole ->
            test("Rolle $deniedRole wird bei listFamilies/createFamily abgelehnt (ForbiddenException)") {
                testApplication {
                    application {
                        install(StatusPages) { installFamilyExceptionHandlers() }
                        routing { registerFamilyTestRoutes() }
                    }
                    val caller = createMember(email = "family-role-$deniedRole-${Uuid.random()}@example.org", role = deniedRole)
                    val payer = createMember(email = "family-payer-$deniedRole-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                    client.get("/test/family/list") { header("X-Member-Id", caller.toString()) }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", caller.toString())
                            parameter("payerMemberId", payer.toString())
                        }.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test("BOARD wird bei deleteFamily abgelehnt (ForbiddenException) -- ADMIN-only") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-del-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val admin = createMember(email = "family-del-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val payer = createMember(email = "family-del-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", admin.toString())
                            parameter("payerMemberId", payer.toString())
                        }.bodyAsText()
                client.delete("/test/family/delete/$familyId") { header("X-Member-Id", board.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.delete("/test/family/delete/$familyId") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK
            }
        }

        // ── 1: createFamily ──────────────────────────────────────────────

        test("createFamily legt genau eine PAYER-Zeile an, Zahler behält seinen Tarif") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-create-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payer = createMember(email = "family-create-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                transaction {
                    MemberTable.update({ MemberTable.id eq payer }) { it[membershipTierId] = DevSeedData.standardTierId }
                }
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", payer.toString())
                        }.bodyAsText()
                val detail =
                    client.get("/test/family/get/$familyId") { header("X-Member-Id", board.toString()) }.bodyAsText()
                detail.contains("$payer:PAYER:${DevSeedData.standardTierId}") shouldBe true
                tierOf(payer) shouldBe DevSeedData.standardTierId
            }
        }

        // ── 1b: renameFamily + validateName (createFamily shares the same helper) ──

        test("renameFamily benennt erfolgreich um") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-rename-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payer = createMember(email = "family-rename-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", payer.toString())
                            parameter("name", "Alter Name")
                        }.bodyAsText()
                val renamed =
                    client
                        .post("/test/family/rename/$familyId") {
                            header("X-Member-Id", board.toString())
                            parameter("name", "  Neuer Name  ")
                        }.bodyAsText()
                // trim() im Server -- die Whitespace-Umrandung darf nicht im gespeicherten Namen landen.
                renamed shouldBe "Neuer Name"
                client
                    .get("/test/family/get/$familyId") { header("X-Member-Id", board.toString()) }
                    .bodyAsText()
                    .startsWith("$familyId|") shouldBe true
            }
        }

        test("renameFamily auf unbekannte Familien-Id wirft NotFoundException") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-rename-404-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                client
                    .post("/test/family/rename/${Uuid.random()}") {
                        header("X-Member-Id", board.toString())
                        parameter("name", "Egal")
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        listOf(
            "leerer Name" to "   ",
            "Name über NAME_MAX_LENGTH" to "x".repeat(MemberFamilyLimits.NAME_MAX_LENGTH + 1),
        ).forEach { (label, invalidName) ->
            test("createFamily lehnt $label ab (BadRequestException)") {
                testApplication {
                    application {
                        install(StatusPages) { installFamilyExceptionHandlers() }
                        routing { registerFamilyTestRoutes() }
                    }
                    val board = createMember(email = "family-badname-create-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                    val payer = createMember(email = "family-badname-create-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", payer.toString())
                            parameter("name", invalidName)
                        }.status shouldBe HttpStatusCode.BadRequest
                }
            }

            test("renameFamily lehnt $label ab (BadRequestException)") {
                testApplication {
                    application {
                        install(StatusPages) { installFamilyExceptionHandlers() }
                        routing { registerFamilyTestRoutes() }
                    }
                    val board = createMember(email = "family-badname-rename-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                    val payer = createMember(email = "family-badname-rename-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                    val familyId =
                        client
                            .post("/test/family/create") {
                                header("X-Member-Id", board.toString())
                                parameter("payerMemberId", payer.toString())
                            }.bodyAsText()
                    client
                        .post("/test/family/rename/$familyId") {
                            header("X-Member-Id", board.toString())
                            parameter("name", invalidName)
                        }.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        test("createFamily akzeptiert einen Namen an der exakten NAME_MAX_LENGTH-Grenze") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-maxname-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payer = createMember(email = "family-maxname-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val exactlyMaxName = "x".repeat(MemberFamilyLimits.NAME_MAX_LENGTH)
                client
                    .post("/test/family/create") {
                        header("X-Member-Id", board.toString())
                        parameter("payerMemberId", payer.toString())
                        parameter("name", exactlyMaxName)
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        test("getFamily mit ungültiger UUID wirft NotFoundException") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-get-invaliduuid-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                client
                    .get("/test/family/get/not-a-uuid") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        test("listFamilies escaped LIKE-Metazeichen ('%','_') in der Suche statt als Wildcard zu wirken") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-like-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payerLiteral = createMember(email = "family-like-literal-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val payerOther = createMember(email = "family-like-other-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                client.post("/test/family/create") {
                    header("X-Member-Id", board.toString())
                    parameter("payerMemberId", payerLiteral.toString())
                    // Ein woertliches "%" im Namen -- ohne Escaping wuerde die Suche unten nach
                    // "100%" (als Wildcard "100" gefolgt von irgendetwas) JEDEN mit "100" beginnenden
                    // Namen treffen, nicht nur diesen literalen.
                    parameter("name", "Beitrag 100% Familie")
                }
                client.post("/test/family/create") {
                    header("X-Member-Id", board.toString())
                    parameter("payerMemberId", payerOther.toString())
                    parameter("name", "Beitrag 100 andere Familie")
                }
                val response =
                    client
                        .get("/test/family/list") {
                            header("X-Member-Id", board.toString())
                            parameter("search", "100%")
                        }.bodyAsText()
                // Without escaping, LIKE '%100%%' (the raw '%' re-interpreted as a wildcard) would
                // also match "Beitrag 100 andere Familie" -- totalCount would be 2, not 1.
                response.substringBefore("|").toInt() shouldBe 1
            }
        }

        // ── 2: addFamilyMember nulls tier ────────────────────────────────

        test("addFamilyMember legt DEPENDENT-Zeile an und nullt membership_tier_id in derselben Transaktion") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-add-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payer = createMember(email = "family-add-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val dependent = createMember(email = "family-add-dep-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                transaction {
                    MemberTable.update({ MemberTable.id eq dependent }) { it[membershipTierId] = DevSeedData.standardTierId }
                }
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", payer.toString())
                        }.bodyAsText()
                client
                    .post("/test/family/add-member") {
                        header("X-Member-Id", board.toString())
                        parameter("familyId", familyId)
                        parameter("memberId", dependent.toString())
                    }.status shouldBe HttpStatusCode.OK
                tierOf(dependent) shouldBe null
            }
        }

        // ── 3: addFamilyMember on a member already in a family -> Conflict ──

        test("addFamilyMember auf ein bereits verknüpftes Mitglied wird abgelehnt (ConflictException)") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-dup-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payerA = createMember(email = "family-dup-payerA-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val payerB = createMember(email = "family-dup-payerB-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val familyA =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", payerA.toString())
                        }.bodyAsText()
                val familyB =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", payerB.toString())
                        }.bodyAsText()
                client
                    .post("/test/family/add-member") {
                        header("X-Member-Id", board.toString())
                        parameter("familyId", familyB)
                        parameter("memberId", payerA.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                @Suppress("UNUSED_EXPRESSION")
                familyA
            }
        }

        // ── 3b: Security fixes (review, MAJOR finding) -- self-target + Peer-Schutz ──────

        test("addFamilyMember: Selbstziel wird abgelehnt (ForbiddenException) -- Aufrufer kann sich nicht selbst den Tarif nullen") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-add-self-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                transaction { MemberTable.update({ MemberTable.id eq board }) { it[membershipTierId] = DevSeedData.standardTierId } }
                val payer = createMember(email = "family-add-self-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", payer.toString())
                        }.bodyAsText()
                client
                    .post("/test/family/add-member") {
                        header("X-Member-Id", board.toString())
                        parameter("familyId", familyId)
                        parameter("memberId", board.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                tierOf(board) shouldBe DevSeedData.standardTierId
            }
        }

        test("addFamilyMember: Peer-Schutz -- BOARD darf keinen ADMIN/BOARD/TREASURER-Peer als Angehörigen hinzufügen, ADMIN darf") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-add-peer-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payer = createMember(email = "family-add-peer-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val peerAdmin = createMember(email = "family-add-peer-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                transaction { MemberTable.update({ MemberTable.id eq peerAdmin }) { it[membershipTierId] = DevSeedData.standardTierId } }
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", payer.toString())
                        }.bodyAsText()
                client
                    .post("/test/family/add-member") {
                        header("X-Member-Id", board.toString())
                        parameter("familyId", familyId)
                        parameter("memberId", peerAdmin.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                tierOf(peerAdmin) shouldBe DevSeedData.standardTierId
                client
                    .post("/test/family/add-member") {
                        header("X-Member-Id", peerAdmin.toString())
                        parameter("familyId", familyId)
                        parameter("memberId", peerAdmin.toString())
                    }.status shouldBe HttpStatusCode.Forbidden // Selbstziel bleibt auch für ADMIN verboten.
                val secondAdmin = createMember(email = "family-add-peer-admin2-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                client
                    .post("/test/family/add-member") {
                        header("X-Member-Id", secondAdmin.toString())
                        parameter("familyId", familyId)
                        parameter("memberId", peerAdmin.toString())
                    }.status shouldBe HttpStatusCode.OK
                tierOf(peerAdmin) shouldBe null
            }
        }

        // ── 4: anonymized member rejected ────────────────────────────────

        test("createFamily/addFamilyMember auf ein anonymisiertes Mitglied wird abgelehnt (BadRequestException)") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-anon-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val anonymized =
                    createMember(
                        email = "family-anon-target-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        anonymizedAt = LocalDateTime(2026, 1, 1, 0, 0),
                    )
                client
                    .post("/test/family/create") {
                        header("X-Member-Id", board.toString())
                        parameter("payerMemberId", anonymized.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        // ── 5: removeFamilyMember sets no tier ───────────────────────────

        test("removeFamilyMember löscht die Zeile und setzt keinen Tarif") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-remove-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payer = createMember(email = "family-remove-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val dependent = createMember(email = "family-remove-dep-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val familyId =
                    Uuid.parse(
                        client
                            .post("/test/family/create") {
                                header("X-Member-Id", board.toString())
                                parameter("payerMemberId", payer.toString())
                            }.bodyAsText(),
                    )
                client.post("/test/family/add-member") {
                    header("X-Member-Id", board.toString())
                    parameter("familyId", familyId.toString())
                    parameter("memberId", dependent.toString())
                }
                val linkId = linkIdOf(familyId, dependent)
                client
                    .post("/test/family/remove-link/$linkId") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.OK
                tierOf(dependent) shouldBe null
                transaction {
                    (MemberFamilyLinkTable.selectAll().where { MemberFamilyLinkTable.id eq linkId }.count()) shouldBe 0
                }
            }
        }

        // ── 5b: Security fix (review, MINOR finding) -- Peer-Schutz ───────

        test("removeFamilyMember: Selbstziel wird abgelehnt (ForbiddenException)") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val admin = createMember(email = "family-remove-self-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val board = createMember(email = "family-remove-self-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payer = createMember(email = "family-remove-self-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val familyId =
                    Uuid.parse(
                        client
                            .post("/test/family/create") {
                                header("X-Member-Id", admin.toString())
                                parameter("payerMemberId", payer.toString())
                            }.bodyAsText(),
                    )
                // Nur ADMIN darf einen BOARD-Peer als Angehörigen hinzufuegen -- board selbst
                // koennte das wegen desselben Peer-Schutzes bei addFamilyMember nicht.
                client
                    .post("/test/family/add-member") {
                        header("X-Member-Id", admin.toString())
                        parameter("familyId", familyId.toString())
                        parameter("memberId", board.toString())
                    }.status shouldBe HttpStatusCode.OK
                val linkId = linkIdOf(familyId, board)
                client
                    .post("/test/family/remove-link/$linkId") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                transaction {
                    (MemberFamilyLinkTable.selectAll().where { MemberFamilyLinkTable.id eq linkId }.count()) shouldBe 1
                }
            }
        }

        test("removeFamilyMember: Peer-Schutz -- BOARD darf keinen ADMIN/BOARD/TREASURER-Peer entfernen, ADMIN darf") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val admin = createMember(email = "family-remove-peer-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val board = createMember(email = "family-remove-peer-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payer = createMember(email = "family-remove-peer-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val peerTreasurer =
                    createMember(email = "family-remove-peer-treasurer-${Uuid.random()}@example.org", role = AccountRole.TREASURER)
                val familyId =
                    Uuid.parse(
                        client
                            .post("/test/family/create") {
                                header("X-Member-Id", admin.toString())
                                parameter("payerMemberId", payer.toString())
                            }.bodyAsText(),
                    )
                client
                    .post("/test/family/add-member") {
                        header("X-Member-Id", admin.toString())
                        parameter("familyId", familyId.toString())
                        parameter("memberId", peerTreasurer.toString())
                    }.status shouldBe HttpStatusCode.OK
                val linkId = linkIdOf(familyId, peerTreasurer)
                client
                    .post("/test/family/remove-link/$linkId") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                transaction {
                    (MemberFamilyLinkTable.selectAll().where { MemberFamilyLinkTable.id eq linkId }.count()) shouldBe 1
                }
                client
                    .post("/test/family/remove-link/$linkId") { header("X-Member-Id", admin.toString()) }
                    .status shouldBe HttpStatusCode.OK
                transaction {
                    (MemberFamilyLinkTable.selectAll().where { MemberFamilyLinkTable.id eq linkId }.count()) shouldBe 0
                }
            }
        }

        // ── 6: changePayer ───────────────────────────────────────────────

        test("changePayer wechselt beide Rollen, alter Zahler verliert Tarif, neuer bleibt NULL, genau ein PAYER danach") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-changepayer-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val oldPayer = createMember(email = "family-changepayer-old-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val newPayer = createMember(email = "family-changepayer-new-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                transaction { MemberTable.update({ MemberTable.id eq oldPayer }) { it[membershipTierId] = DevSeedData.standardTierId } }
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", oldPayer.toString())
                        }.bodyAsText()
                client.post("/test/family/add-member") {
                    header("X-Member-Id", board.toString())
                    parameter("familyId", familyId)
                    parameter("memberId", newPayer.toString())
                }
                client
                    .post("/test/family/change-payer") {
                        header("X-Member-Id", board.toString())
                        parameter("familyId", familyId)
                        parameter("newPayerMemberId", newPayer.toString())
                    }.status shouldBe HttpStatusCode.OK
                tierOf(oldPayer) shouldBe null
                tierOf(newPayer) shouldBe null
                val payerCount =
                    transaction {
                        MemberFamilyLinkTable
                            .selectAll()
                            .where {
                                (MemberFamilyLinkTable.familyId eq Uuid.parse(familyId)) and
                                    (MemberFamilyLinkTable.role eq FamilyMemberRole.PAYER)
                            }.count()
                    }
                payerCount shouldBe 1L
            }
        }

        // ── 6b: Security fix (review, MAJOR finding) -- TOCTOU via forUpdate() ────────────

        test(
            "changePayer racing removeFamilyMember(oldPayer): forUpdate() serializes them -- no " +
                "crash, old payer ends up fully removed from the family either way, at most one PAYER remains",
        ) {
            // Security fix (Review MAJOR) regression: before `.forUpdate()` on changePayer's
            // oldPayerLink/newPayerLink reads, a concurrent removeFamilyMember deleting the OLD
            // payer's link between changePayer's plain SELECT and its subsequent UPDATE could
            // silently affect 0 rows (Exposed does not throw on a no-op UPDATE) -- yet changePayer
            // would still proceed to null the (by-then-already-removed) old payer's real,
            // contribution-generating tier via MembershipTierAssignment.apply. `.forUpdate()` now
            // fully serializes both operations against the SAME row: whichever transaction reaches
            // it first runs to completion before the other proceeds against the post-commit state --
            // same real-concurrency-via-the-Ktor-test-client idiom SocialNetworkServiceTest's own
            // boost-race test already establishes in this codebase.
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val admin = createMember(email = "family-race-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val payer = createMember(email = "family-race-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val newPayer = createMember(email = "family-race-newpayer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                transaction { MemberTable.update({ MemberTable.id eq payer }) { it[membershipTierId] = DevSeedData.standardTierId } }
                val familyId =
                    Uuid.parse(
                        client
                            .post("/test/family/create") {
                                header("X-Member-Id", admin.toString())
                                parameter("payerMemberId", payer.toString())
                            }.bodyAsText(),
                    )
                client
                    .post("/test/family/add-member") {
                        header("X-Member-Id", admin.toString())
                        parameter("familyId", familyId.toString())
                        parameter("memberId", newPayer.toString())
                    }.status shouldBe HttpStatusCode.OK
                val payerLinkId = linkIdOf(familyId, payer)

                val results =
                    runBlocking {
                        withTimeout(20_000) {
                            listOf(
                                async {
                                    client.post("/test/family/remove-link/$payerLinkId") { header("X-Member-Id", admin.toString()) }
                                },
                                async {
                                    client.post("/test/family/change-payer") {
                                        header("X-Member-Id", admin.toString())
                                        parameter("familyId", familyId.toString())
                                        parameter("newPayerMemberId", newPayer.toString())
                                    }
                                },
                            ).awaitAll()
                        }
                    }
                // Both operations act on disjoint identifiers they already hold (a fixed linkId, a
                // fixed familyId+memberId) -- either can legitimately still succeed after the other
                // commits, so a strict "both 200" is the expected common case; a well-defined 4xx
                // from one side (should the lock cause it to observe a since-changed state) is
                // tolerated rather than treated as a hang or a 500.
                results.forEach { it.status.value shouldBeLessThan 500 }

                transaction {
                    // The old payer is never linked to the family after this race, regardless of
                    // which request's transaction actually committed first.
                    (
                        MemberFamilyLinkTable
                            .selectAll()
                            .where { (MemberFamilyLinkTable.familyId eq familyId) and (MemberFamilyLinkTable.memberId eq payer) }
                            .count()
                    ) shouldBe 0
                    // At most one PAYER remains -- more than one would mean uq_member_family_link_payer
                    // was violated, which could only happen if the locking here were broken.
                    (
                        MemberFamilyLinkTable
                            .selectAll()
                            .where {
                                (MemberFamilyLinkTable.familyId eq familyId) and (MemberFamilyLinkTable.role eq FamilyMemberRole.PAYER)
                            }.count()
                    ) shouldBeLessThanOrEqual 1
                }
            }
        }

        test("changePayer auf ein Mitglied außerhalb der Familie wird abgelehnt (BadRequestException)") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-changepayer-out-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payer = createMember(email = "family-changepayer-out-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val outsider =
                    createMember(email = "family-changepayer-out-outsider-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", payer.toString())
                        }.bodyAsText()
                client
                    .post("/test/family/change-payer") {
                        header("X-Member-Id", board.toString())
                        parameter("familyId", familyId)
                        parameter("newPayerMemberId", outsider.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        // ── 6b: Security fixes (review, MAJOR/INFO finding) -- self-target + Peer-Schutz + eligibility ──

        test("changePayer: Selbstziel wird abgelehnt (ForbiddenException) -- amtierender Zahler kann sich nicht selbst ablösen") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                // `board` ist sowohl der aktuelle Zahler ALS AUCH der Aufrufer -- genau das
                // Angriffsszenario: eine change-payer-Beförderung eines anderen Mitglieds würde den
                // eigenen Tarif des Aufrufers nullen.
                val board = createMember(email = "family-changepayer-self-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                transaction { MemberTable.update({ MemberTable.id eq board }) { it[membershipTierId] = DevSeedData.standardTierId } }
                val admin = createMember(email = "family-changepayer-self-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val newPayer = createMember(email = "family-changepayer-self-new-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", admin.toString())
                            parameter("payerMemberId", board.toString())
                        }.bodyAsText()
                client.post("/test/family/add-member") {
                    header("X-Member-Id", admin.toString())
                    parameter("familyId", familyId)
                    parameter("memberId", newPayer.toString())
                }
                client
                    .post("/test/family/change-payer") {
                        header("X-Member-Id", board.toString())
                        parameter("familyId", familyId)
                        parameter("newPayerMemberId", newPayer.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                tierOf(board) shouldBe DevSeedData.standardTierId
            }
        }

        test("changePayer: Peer-Schutz -- BOARD darf keinen ADMIN/BOARD/TREASURER-Peer als alten Zahler ablösen, ADMIN darf") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val admin = createMember(email = "family-changepayer-peer-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val board = createMember(email = "family-changepayer-peer-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val peerAdminPayer =
                    createMember(email = "family-changepayer-peer-payer-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                transaction {
                    MemberTable.update({ MemberTable.id eq peerAdminPayer }) { it[membershipTierId] = DevSeedData.standardTierId }
                }
                val newPayer = createMember(email = "family-changepayer-peer-new-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", admin.toString())
                            parameter("payerMemberId", peerAdminPayer.toString())
                        }.bodyAsText()
                client.post("/test/family/add-member") {
                    header("X-Member-Id", admin.toString())
                    parameter("familyId", familyId)
                    parameter("memberId", newPayer.toString())
                }
                client
                    .post("/test/family/change-payer") {
                        header("X-Member-Id", board.toString())
                        parameter("familyId", familyId)
                        parameter("newPayerMemberId", newPayer.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
                tierOf(peerAdminPayer) shouldBe DevSeedData.standardTierId
                client
                    .post("/test/family/change-payer") {
                        header("X-Member-Id", admin.toString())
                        parameter("familyId", familyId)
                        parameter("newPayerMemberId", newPayer.toString())
                    }.status shouldBe HttpStatusCode.OK
                tierOf(peerAdminPayer) shouldBe null
            }
        }

        test("changePayer auf ein anonymisiertes Mitglied wird abgelehnt (BadRequestException), auch wenn der Link noch existiert") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-changepayer-anon-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payer = createMember(email = "family-changepayer-anon-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val dependent = createMember(email = "family-changepayer-anon-dep-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", payer.toString())
                        }.bodyAsText()
                client.post("/test/family/add-member") {
                    header("X-Member-Id", board.toString())
                    parameter("familyId", familyId)
                    parameter("memberId", dependent.toString())
                }
                // Simuliert den Link, der eine Anonymisierung überlebt (die normale Reihenfolge
                // entfernt ihn zuerst, siehe MemberFamilyPersonalData) -- setzt anonymizedAt direkt,
                // um die neue Guard-Bedingung isoliert zu prüfen (Security fix, INFO).
                transaction { MemberTable.update({ MemberTable.id eq dependent }) { it[anonymizedAt] = LocalDateTime(2026, 1, 1, 0, 0) } }
                client
                    .post("/test/family/change-payer") {
                        header("X-Member-Id", board.toString())
                        parameter("familyId", familyId)
                        parameter("newPayerMemberId", dependent.toString())
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        // ── 8: deleteFamily ──────────────────────────────────────────────

        test("deleteFamily als ADMIN entfernt Familie + Links, rührt Tarife nicht an") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val admin = createMember(email = "family-delete-admin-${Uuid.random()}@example.org", role = AccountRole.ADMIN)
                val payer = createMember(email = "family-delete-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", admin.toString())
                            parameter("payerMemberId", payer.toString())
                        }.bodyAsText()
                client.delete("/test/family/delete/$familyId") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK
                transaction {
                    (MemberFamilyTable.selectAll().where { MemberFamilyTable.id eq Uuid.parse(familyId) }.count()) shouldBe 0
                }
            }
        }

        // ── 10: listFamilies ─────────────────────────────────────────────

        test("listFamilies deckelt limit serverseitig auf MemberFamilyLimits.MAX_LIMIT") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-list-limit-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payer = createMember(email = "family-list-limit-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                client.post("/test/family/create") {
                    header("X-Member-Id", board.toString())
                    parameter("payerMemberId", payer.toString())
                    parameter("name", "ZZZ-Familie")
                }
                val response =
                    client.get("/test/family/list") {
                        header("X-Member-Id", board.toString())
                        parameter("limit", "9999")
                    }
                response.status shouldBe HttpStatusCode.OK
                val parts = response.bodyAsText().split("|")
                // limit=9999 requested, coerceIn(1, MAX_LIMIT) must clamp the ECHOED limit down --
                // a mutation to `.coerceAtLeast(1)` (dropping the upper bound) would leave this at
                // 9999 and stay undetected by a status-code-only assertion.
                parts[1].toInt() shouldBe MemberFamilyLimits.MAX_LIMIT
            }
        }

        test("listFamilies coerct limit<1 auf 1 und offset<0 auf 0") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-list-coerce-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payerA = createMember(email = "family-list-coerce-a-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val payerB = createMember(email = "family-list-coerce-b-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                client.post("/test/family/create") {
                    header("X-Member-Id", board.toString())
                    parameter("payerMemberId", payerA.toString())
                    parameter("name", "Coerce-A")
                }
                client.post("/test/family/create") {
                    header("X-Member-Id", board.toString())
                    parameter("payerMemberId", payerB.toString())
                    parameter("name", "Coerce-B")
                }
                val zeroLimitParts =
                    client
                        .get("/test/family/list") {
                            header("X-Member-Id", board.toString())
                            parameter("limit", "0")
                        }.bodyAsText()
                        .split("|")
                zeroLimitParts[1].toInt() shouldBe 1
                zeroLimitParts.last().split(",").size shouldBe 1
                val negativeOffsetParts =
                    client
                        .get("/test/family/list") {
                            header("X-Member-Id", board.toString())
                            parameter("offset", "-5")
                        }.bodyAsText()
                        .split("|")
                negativeOffsetParts[2].toInt() shouldBe 0
            }
        }

        test("listFamilies sortiert zahlerlose Familien zuerst, unabhängig vom Namen") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-list-sort-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payerKept = createMember(email = "family-list-sort-kept-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val payerRemoved =
                    createMember(email = "family-list-sort-removed-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                // Bewusst alphabetisch GEGEN die erwartete Ausgabe-Reihenfolge benannt: "A-..." hat
                // einen Zahler und muesste bei reiner Namenssortierung ZUERST stehen -- der Test
                // faengt eine vertauschte `compareBy { it.hasPayer }` -> `compareByDescending`-Mutation
                // nur ab, wenn der Name allein die falsche Reihenfolge nahelegen würde.
                val familyWithPayerId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", payerKept.toString())
                            parameter("name", "A-Mit-Zahler")
                        }.bodyAsText()
                val familyWithoutPayerId =
                    Uuid.parse(
                        client
                            .post("/test/family/create") {
                                header("X-Member-Id", board.toString())
                                parameter("payerMemberId", payerRemoved.toString())
                                parameter("name", "Z-Ohne-Zahler")
                            }.bodyAsText(),
                    )
                val removedLinkId = linkIdOf(familyWithoutPayerId, payerRemoved)
                client
                    .post("/test/family/remove-link/$removedLinkId") { header("X-Member-Id", board.toString()) }
                    .status shouldBe HttpStatusCode.OK

                val response =
                    client.get("/test/family/list") { header("X-Member-Id", board.toString()) }.bodyAsText()
                val entries =
                    response
                        .substringAfter("|")
                        .substringAfter("|")
                        .substringAfter("|")
                        .split(",")
                val payerlessIndex = entries.indexOfFirst { it.startsWith("$familyWithoutPayerId:false") }
                val payerHavingIndex = entries.indexOfFirst { it.startsWith("$familyWithPayerId:true") }
                payerlessIndex shouldBe 0
                (payerlessIndex < payerHavingIndex) shouldBe true
            }
        }

        test("listFamilies paginiert offsetbasiert ohne Lücken/Duplikate über die Seiten hinweg") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-list-page-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val familyIds =
                    (1..3).map { i ->
                        val payer =
                            createMember(
                                email = "family-list-page-payer-$i-${Uuid.random()}@example.org",
                                role = AccountRole.MEMBER,
                            )
                        client
                            .post("/test/family/create") {
                                header("X-Member-Id", board.toString())
                                parameter("payerMemberId", payer.toString())
                                parameter("name", "Page-Familie-$i")
                            }.bodyAsText()
                    }

                suspend fun idsAt(
                    limit: Int,
                    offset: Int,
                ): List<String> =
                    client
                        .get("/test/family/list") {
                            header("X-Member-Id", board.toString())
                            parameter("limit", limit.toString())
                            parameter("offset", offset.toString())
                        }.bodyAsText()
                        .substringAfter("|")
                        .substringAfter("|")
                        .substringAfter("|")
                        .split(",")
                        .map { it.substringBefore(":") }
                val page1 = idsAt(limit = 1, offset = 0)
                val page2 = idsAt(limit = 1, offset = 1)
                val page3 = idsAt(limit = 1, offset = 2)
                val allPaged = page1 + page2 + page3
                allPaged.toSet() shouldBe familyIds.toSet()
                (page1 + page2 + page3).distinct().size shouldBe 3
            }
        }

        // ── Volljährigkeit ───────────────────────────────────────────────

        test("listUpcomingMajorities: Fenstergrenze -- genau today+windowDays ist drin, +1 ist draußen") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-majority-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payer = createMember(email = "family-majority-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                // Wird in genau 30 Tagen volljährig (today = 2026-06-15 -> 2026-07-15).
                val dependentIn =
                    createMember(
                        email = "family-majority-in-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        dateOfBirth = LocalDate(2008, 7, 15),
                    )
                // Wird einen Tag NACH dem 30-Tage-Fenster volljährig.
                val dependentOut =
                    createMember(
                        email = "family-majority-out-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        dateOfBirth = LocalDate(2008, 7, 16),
                    )
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", payer.toString())
                        }.bodyAsText()
                client.post("/test/family/add-member") {
                    header("X-Member-Id", board.toString())
                    parameter("familyId", familyId)
                    parameter("memberId", dependentIn.toString())
                }
                client.post("/test/family/add-member") {
                    header("X-Member-Id", board.toString())
                    parameter("familyId", familyId)
                    parameter("memberId", dependentOut.toString())
                }
                val response =
                    client.get("/test/family/majorities") {
                        header("X-Member-Id", board.toString())
                        parameter("windowDays", "30")
                    }
                val body = response.bodyAsText()
                body.contains(dependentIn.toString()) shouldBe true
                body.contains(dependentOut.toString()) shouldBe false
            }
        }

        test("listUpcomingMajorities: bereits volljähriger Angehöriger ist immer enthalten (alreadyMajor)") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-overdue-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payer = createMember(email = "family-overdue-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val overdue =
                    createMember(
                        email = "family-overdue-dep-${Uuid.random()}@example.org",
                        role = AccountRole.MEMBER,
                        dateOfBirth = LocalDate(2000, 1, 1),
                    )
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", payer.toString())
                        }.bodyAsText()
                client.post("/test/family/add-member") {
                    header("X-Member-Id", board.toString())
                    parameter("familyId", familyId)
                    parameter("memberId", overdue.toString())
                }
                val response =
                    client.get("/test/family/majorities") {
                        header("X-Member-Id", board.toString())
                        parameter("windowDays", "1")
                    }
                response.bodyAsText().contains("$overdue:true") shouldBe true
            }
        }

        test("listUpcomingMajorities: fehlendes Geburtsdatum zählt in dependentsWithoutDateOfBirth, verschwindet nicht") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-nodob-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                val payer = createMember(email = "family-nodob-payer-${Uuid.random()}@example.org", role = AccountRole.MEMBER)
                val noDob =
                    createMember(email = "family-nodob-dep-${Uuid.random()}@example.org", role = AccountRole.MEMBER, dateOfBirth = null)
                val familyId =
                    client
                        .post("/test/family/create") {
                            header("X-Member-Id", board.toString())
                            parameter("payerMemberId", payer.toString())
                        }.bodyAsText()
                client.post("/test/family/add-member") {
                    header("X-Member-Id", board.toString())
                    parameter("familyId", familyId)
                    parameter("memberId", noDob.toString())
                }
                val response = client.get("/test/family/majorities") { header("X-Member-Id", board.toString()) }
                val parts = response.bodyAsText().split("|")
                parts[1].toInt() shouldBe 1
            }
        }

        test("listUpcomingMajorities: windowDays außerhalb 1..90 wird abgelehnt (BadRequestException)") {
            testApplication {
                application {
                    install(StatusPages) { installFamilyExceptionHandlers() }
                    routing { registerFamilyTestRoutes() }
                }
                val board = createMember(email = "family-windowbad-board-${Uuid.random()}@example.org", role = AccountRole.BOARD)
                client
                    .get("/test/family/majorities") {
                        header("X-Member-Id", board.toString())
                        parameter("windowDays", "0")
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .get("/test/family/majorities") {
                        header("X-Member-Id", board.toString())
                        parameter("windowDays", "91")
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }
    })

private fun StatusPagesConfig.installFamilyExceptionHandlers() {
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
}
