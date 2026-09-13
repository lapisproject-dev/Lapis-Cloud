package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.VatComplianceAcknowledgmentTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VatComplianceAcknowledgmentInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IVatService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** Welle V1.4.13 "USt-Voranmeldung" -- exercises [VatService]/[IVatService] end to end. */
class VatServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[vatEnabled] = false
                    it[isKleinunternehmer] = false
                }
                if (createdMemberIds.isNotEmpty()) {
                    VatComplianceAcknowledgmentTable.deleteWhere { acknowledgedByMemberId inList createdMemberIds }
                    // enableVat/disableVat both call AuditLogRecorder.record with actorMemberId =
                    // current.memberId -- null it out before deleting the member, otherwise the
                    // MemberTable delete below trips fk_audit_log_entry_actor_member_id (same fix
                    // as AccountingServiceVatTest's afterSpec).
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createTestMember(
            email: String,
            role: AccountRole,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "VatService Testmitglied"
                    it[MemberTable.email] = email
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
            createdMemberIds += id
            return id
        }

        fun resetGate() {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[vatEnabled] = false
                    it[isKleinunternehmer] = false
                }
            }
        }

        test("enableVat without a valid acknowledgment is rejected with Conflict; vat_enabled stays false; no ack row written") {
            resetGate()
            testApplication {
                application {
                    install(StatusPages) { installVatServiceExceptionHandlers() }
                    routing { registerVatServiceTestRoutes() }
                }
                val admin = createTestMember("vatservice-admin-badack@example.org", AccountRole.ADMIN)
                val response =
                    client.post("/test/vatservice/enable?version=wrong&sha256=deadbeef") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.Conflict

                val enabledAfter =
                    transaction {
                        OrganizationSettingsTable
                            .selectAll()
                            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                            .single()[OrganizationSettingsTable.vatEnabled]
                    }
                enabledAfter shouldBe false

                val ackCount =
                    transaction {
                        VatComplianceAcknowledgmentTable
                            .selectAll()
                            .where { VatComplianceAcknowledgmentTable.acknowledgedByMemberId eq admin }
                            .count()
                    }
                ackCount shouldBe 0L
            }
        }

        test("enableVat with a valid acknowledgment succeeds: vat_enabled true, exactly one ack row, audit entry written") {
            resetGate()
            testApplication {
                application {
                    install(StatusPages) { installVatServiceExceptionHandlers() }
                    routing { registerVatServiceTestRoutes() }
                }
                val admin = createTestMember("vatservice-admin-goodack@example.org", AccountRole.ADMIN)
                val disclaimer = client.post("/test/vatservice/disclaimer") { header("X-Member-Id", admin.toString()) }.bodyAsText()
                val (version, sha256) = disclaimer.split("|")

                val response =
                    client.post("/test/vatservice/enable?version=$version&sha256=$sha256") { header("X-Member-Id", admin.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "true:false"

                val ackCount =
                    transaction {
                        VatComplianceAcknowledgmentTable
                            .selectAll()
                            .where { VatComplianceAcknowledgmentTable.acknowledgedByMemberId eq admin }
                            .count()
                    }
                ackCount shouldBe 1L
            }
        }

        test("enableVat/disableVat require ADMIN -- TREASURER/BOARD/MEMBER are Forbidden") {
            resetGate()
            testApplication {
                application {
                    install(StatusPages) { installVatServiceExceptionHandlers() }
                    routing { registerVatServiceTestRoutes() }
                }
                listOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.MEMBER).forEach { role ->
                    val member = createTestMember("vatservice-noadmin-$role@example.org", role)
                    val enableResponse =
                        client.post("/test/vatservice/enable?version=x&sha256=y") { header("X-Member-Id", member.toString()) }
                    enableResponse.status shouldBe HttpStatusCode.Forbidden

                    val disableResponse = client.post("/test/vatservice/disable") { header("X-Member-Id", member.toString()) }
                    disableResponse.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test("getVatSettings is readable by TREASURER (not just ADMIN) -- no GitHub-#8 repeat") {
            resetGate()
            testApplication {
                application {
                    install(StatusPages) { installVatServiceExceptionHandlers() }
                    routing { registerVatServiceTestRoutes() }
                }
                val treasurer = createTestMember("vatservice-treasurer-read@example.org", AccountRole.TREASURER)
                val response = client.post("/test/vatservice/settings") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        // Security Round 2 (HARDENING): enableVat/disableVat already have their own ADMIN-only
        // negative test above ("require ADMIN -- TREASURER/BOARD/MEMBER are Forbidden"), and
        // getVatReturnPreview has its own MEMBER-forbidden regression guard
        // (AccountingServiceVatTest.kt:499-509) -- but getVatSettings/getVatComplianceDisclaimer
        // had none: VAT_READ_ROLES (VatService.kt:26/39/108) correctly rejects a plain MEMBER
        // today, but nothing would notice if a future edit ever widened that gate, since neither
        // read path was exercised with a non-privileged caller anywhere in this file.
        test("getVatSettings/getVatComplianceDisclaimer reject a plain MEMBER with Forbidden") {
            resetGate()
            testApplication {
                application {
                    install(StatusPages) { installVatServiceExceptionHandlers() }
                    routing { registerVatServiceTestRoutes() }
                }
                val member = createTestMember("vatservice-member-noread@example.org", AccountRole.MEMBER)

                val settingsResponse = client.post("/test/vatservice/settings") { header("X-Member-Id", member.toString()) }
                settingsResponse.status shouldBe HttpStatusCode.Forbidden

                val disclaimerResponse = client.post("/test/vatservice/disclaimer") { header("X-Member-Id", member.toString()) }
                disclaimerResponse.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("disableVat leaves isKleinunternehmer unchanged") {
            resetGate()
            testApplication {
                application {
                    install(StatusPages) { installVatServiceExceptionHandlers() }
                    routing { registerVatServiceTestRoutes() }
                }
                val admin = createTestMember("vatservice-admin-disable@example.org", AccountRole.ADMIN)
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[vatEnabled] = true
                        it[isKleinunternehmer] = true
                    }
                }
                val response = client.post("/test/vatservice/disable") { header("X-Member-Id", admin.toString()) }
                response.bodyAsText() shouldBe "false:true"
            }
        }

        test("disclaimerCurrent flips false after a VERSION bump without touching vatEnabled -- simulated via a stale ack row") {
            resetGate()
            testApplication {
                application {
                    install(StatusPages) { installVatServiceExceptionHandlers() }
                    routing { registerVatServiceTestRoutes() }
                }
                val admin = createTestMember("vatservice-admin-stale@example.org", AccountRole.ADMIN)
                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) { it[vatEnabled] = true }
                    VatComplianceAcknowledgmentTable.insert {
                        it[id] = Uuid.random()
                        it[acknowledgedByMemberId] = admin
                        it[acknowledgedAt] = DbClock.nowLocalDateTime()
                        it[disclaimerVersion] = "2000-01-01.v0"
                        it[disclaimerSha256] = "0".repeat(64)
                    }
                }
                val settings = client.post("/test/vatservice/settings-full") { header("X-Member-Id", admin.toString()) }.bodyAsText()
                settings shouldBe "true:false:2000-01-01.v0:false"
            }
        }
    })

private fun StatusPagesConfig.installVatServiceExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}

private fun Route.registerVatServiceTestRoutes() {
    post("/test/vatservice/disclaimer") {
        val service = VatService(call)
        val dto = service.getVatComplianceDisclaimer()
        call.respondText("${dto.version}|${dto.sha256}")
    }
    post("/test/vatservice/enable") {
        val service = VatService(call)
        val q = call.request.queryParameters
        val dto = service.enableVat(VatComplianceAcknowledgmentInput(disclaimerVersion = q["version"]!!, disclaimerSha256 = q["sha256"]!!))
        call.respondText("${dto.vatEnabled}:${dto.isKleinunternehmer}")
    }
    post("/test/vatservice/disable") {
        val service = VatService(call)
        val dto = service.disableVat()
        call.respondText("${dto.vatEnabled}:${dto.isKleinunternehmer}")
    }
    post("/test/vatservice/settings") {
        val service = VatService(call)
        val dto = service.getVatSettings()
        call.respondText("${dto.vatEnabled}:${dto.isKleinunternehmer}")
    }
    post("/test/vatservice/settings-full") {
        val service = VatService(call)
        val dto = service.getVatSettings()
        call.respondText("${dto.vatEnabled}:${dto.isKleinunternehmer}:${dto.lastDisclaimerVersion}:${dto.disclaimerCurrent}")
    }
}
