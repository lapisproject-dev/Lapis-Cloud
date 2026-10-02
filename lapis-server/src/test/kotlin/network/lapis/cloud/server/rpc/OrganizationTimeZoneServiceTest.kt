package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * V1.9.38 -- [OrganizationTimeZoneService]: ADMIN-only on both methods (negative tests for every other role and for an anonymous
 * caller), allow-list validation, audit entry with the old and the new zone, immediate effect through the cache, the zone delivered
 * in `SessionInfoDto`, and the zone NOT being writable through the generic organization-settings update (mass-assignment guard).
 */
class OrganizationTimeZoneServiceTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { TimeTestSupport.resetOrganizationZone() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        suspend fun io.ktor.server.testing.ApplicationTestBuilder.app() {
            application {
                install(StatusPages) {
                    exception<UnauthenticatedException> {
                        call,
                        cause,
                        ->
                        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                    }
                    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
                }
                routing { registerTimeZoneTestRoutes() }
            }
        }

        fun storedZone(): String = transaction { OrganizationSettingsTable.selectAll().single()[OrganizationSettingsTable.timezone] }

        fun auditCount(): Long =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS }
                    .count()
            }

        test("the migration default is Europe/Berlin and the provider reads it") {
            storedZone() shouldBe "Europe/Berlin"
            OrganizationTimeZone.current().id shouldBe "Europe/Berlin"
        }

        test("ADMIN reads the zone and the allow-list") {
            testApplication {
                app()
                val response = client.get("/tz/get") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Europe/Berlin:"
                response.bodyAsText() shouldContain ",Asia/Tbilisi,"
            }
        }

        test("BOARD, TREASURER, MEMBER and an anonymous caller are refused on read and on write, and nothing changes") {
            testApplication {
                app()
                val before = auditCount()
                listOf(BOARD_ID, TREASURER_ID, MEMBER_ID).forEach { who ->
                    client.get("/tz/get") { header("X-Member-Id", who) }.status shouldBe HttpStatusCode.Forbidden
                    client.post("/tz/set/Asia~Tbilisi") { header("X-Member-Id", who) }.status shouldBe HttpStatusCode.Forbidden
                }
                client.get("/tz/get").status shouldBe HttpStatusCode.Unauthorized
                client.post("/tz/set/Asia~Tbilisi").status shouldBe HttpStatusCode.Unauthorized
                storedZone() shouldBe "Europe/Berlin"
                auditCount() shouldBe before
            }
        }

        test("ADMIN changes the zone: stored, effective at once (cache invalidated) and audited with old and new zone") {
            testApplication {
                app()
                val before = auditCount()
                OrganizationTimeZone.current().id shouldBe "Europe/Berlin" // primes the cache
                val response = client.post("/tz/set/Asia~Tbilisi") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "Asia/Tbilisi"
                storedZone() shouldBe "Asia/Tbilisi"
                OrganizationTimeZone.current().id shouldBe "Asia/Tbilisi"
                auditCount() shouldBe before + 1
                val entry =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS }
                            .orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.DESC)
                            .first()
                    }
                entry[AuditLogEntryTable.beforeSnapshot] shouldBe """{"timezone":"Europe/Berlin"}"""
                entry[AuditLogEntryTable.afterSnapshot] shouldBe """{"timezone":"Asia/Tbilisi"}"""
                entry[AuditLogEntryTable.actorMemberId] shouldBe Uuid.parse(ADMIN_ID)
            }
        }

        test("setting the same zone again writes no audit entry") {
            testApplication {
                app()
                val before = auditCount()
                client.post("/tz/set/Europe~Berlin") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                auditCount() shouldBe before
            }
        }

        test("an unknown or malformed zone is rejected with the fixed message and nothing is stored") {
            testApplication {
                app()
                listOf("CET", "%2B02:00", "Etc~GMT%2B5", "Europe~Nowhere", "..~..~etc").forEach { bad ->
                    val response = client.post("/tz/set/$bad") { header("X-Member-Id", ADMIN_ID) }
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.bodyAsText() shouldBe "Unbekannte Zeitzone"
                }
                storedZone() shouldBe "Europe/Berlin"
            }
        }

        // The shared test database may hold chain breaks left by other specs' append-only-violating test cleanup, so the check is
        // scoped to THIS entry's own sequence number: its stored hash must equal the recomputation over its own fields.
        test("the audit entry of a zone change carries a valid hash") {
            testApplication {
                app()
                client.post("/tz/set/Asia~Tbilisi") { header("X-Member-Id", ADMIN_ID) }
                val sequence =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS }
                            .orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.DESC)
                            .first()[AuditLogEntryTable.sequenceNumber]
                    }
                val verification = client.get("/tz/verify-audit/$sequence") { header("X-Member-Id", ADMIN_ID) }
                verification.bodyAsText() shouldBe "valid=true"
            }
        }

        test("SessionInfoDto carries the organization zone") {
            testApplication {
                app()
                val issued = SessionStore.createSession(Uuid.parse(ADMIN_ID))
                try {
                    client.get("/tz/session") { header("Authorization", "Bearer ${issued.rawToken}") }.bodyAsText() shouldBe "Europe/Berlin"
                    client.post("/tz/set/Asia~Tbilisi") { header("X-Member-Id", ADMIN_ID) }
                    client.get("/tz/session") { header("Authorization", "Bearer ${issued.rawToken}") }.bodyAsText() shouldBe "Asia/Tbilisi"
                } finally {
                    transaction { SessionTable.deleteWhere { SessionTable.memberId eq Uuid.parse(ADMIN_ID) } }
                }
            }
        }

        test("the generic updateOrganizationSettings cannot change the zone (mass-assignment guard)") {
            testApplication {
                app()
                client.post("/tz/set/Asia~Tbilisi") { header("X-Member-Id", ADMIN_ID) }
                val settings = transaction { OrganizationSettingsTable.selectAll().single() }
                settings[OrganizationSettingsTable.timezone] shouldBe "Asia/Tbilisi"
                val response = client.post("/tz/generic-update") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.OK
                storedZone() shouldBe "Asia/Tbilisi"
            }
        }

        test("an invalid stored value (a manual SQL fix gone wrong) falls back to Europe/Berlin instead of failing") {
            transaction {
                OrganizationSettingsTable.update(
                    { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID },
                ) { it[timezone] = "Not/AZone" }
            }
            OrganizationTimeZone.invalidate()
            OrganizationTimeZone.current().id shouldBe "Europe/Berlin"
        }
    })

private fun Route.registerTimeZoneTestRoutes() {
    get("/tz/get") {
        val dto = OrganizationTimeZoneService(call).getOrganizationTimeZone()
        call.respondText("${dto.zoneId}:${dto.availableZoneIds.joinToString(",", prefix = ",", postfix = ",")}")
    }
    post("/tz/set/{zone}") {
        val zone = call.parameters["zone"]!!.replace('~', '/')
        call.respondText(OrganizationTimeZoneService(call).updateOrganizationTimeZone(zone).zoneId)
    }
    get("/tz/session") {
        call.respondText(AuthService(call = call).getSessionInfo().organizationTimeZone)
    }
    get("/tz/verify-audit/{seq}") {
        val seq = call.parameters["seq"]!!.toLong()
        call.respondText("valid=${AuditLogService(call).verifyChainIntegrity(fromSequenceNumber = seq, toSequenceNumber = seq).valid}")
    }
    post("/tz/generic-update") {
        val service = OrganizationSettingsService(call)
        val current = service.getOrganizationSettings()
        service.updateOrganizationSettings(OrganizationSettingsInput(name = current.name))
        call.respondText("ok")
    }
}
