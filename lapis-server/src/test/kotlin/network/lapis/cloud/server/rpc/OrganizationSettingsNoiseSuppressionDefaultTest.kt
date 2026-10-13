package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Welle V1.9.124 -- the instance default of the optional noise suppression: [OrganizationSettingsService.updateNoiseSuppressionDefault]
 * is ADMIN-only, audited exactly once per real change (and not at all when nothing changes), carries no personal data in the audit
 * snapshot, and is NOT reachable through the generic [OrganizationSettingsService.updateOrganizationSettings]. Throwaway routes call the
 * service class directly (house style of [OrganizationSettingsServiceTest]).
 */
class OrganizationSettingsNoiseSuppressionDefaultTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun setDefault(value: Boolean) =
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[noiseSuppressionDefault] = value
                }
            }

        fun auditCount(): Long =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS) and
                            (AuditLogEntryTable.entityId eq ORGANIZATION_SETTINGS_ID)
                    }.count()
            }

        afterTest { setDefault(false) }

        fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    }
                    routing { registerNoiseDefaultTestRoutes() }
                }
                block()
            }

        suspend fun HttpClient.setNoise(
            who: String?,
            enabled: Boolean,
        ) = post("/t/noise?enabled=$enabled") { if (who != null) header("X-Member-Id", who) }

        test("ADMIN sets and clears the default; BOARD, TREASURER, MEMBER and anonymous callers are refused and nothing changes") {
            withApp {
                for (who in listOf(BOARD_ID, TREASURER_ID, MEMBER_ID)) {
                    client.setNoise(who, true).status shouldBe HttpStatusCode.Forbidden
                }
                client.setNoise(null, true).status shouldBe HttpStatusCode.Unauthorized
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "false"

                client.setNoise(ADMIN_ID, true).bodyAsText() shouldBe "true"
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "true"
                client.get("/t/get") { header("X-Member-Id", TREASURER_ID) }.bodyAsText() shouldBe "true"
                client.setNoise(ADMIN_ID, false).bodyAsText() shouldBe "false"
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "false"
            }
        }

        test("a real change writes one audit entry with a one-field before/after snapshot and no personal data; a no-op writes nothing") {
            withApp {
                val start = auditCount()
                client.setNoise(ADMIN_ID, false).status shouldBe HttpStatusCode.OK // already false: no change
                auditCount() shouldBe start

                client.setNoise(ADMIN_ID, true).status shouldBe HttpStatusCode.OK
                auditCount() shouldBe start + 1
                val entry =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS) and
                                    (AuditLogEntryTable.entityId eq ORGANIZATION_SETTINGS_ID)
                            }.orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.DESC)
                            .first()
                    }
                entry[AuditLogEntryTable.action] shouldBe AuditAction.UPDATE
                entry[AuditLogEntryTable.beforeSnapshot] shouldBe "{\"noiseSuppressionDefault\":false}"
                entry[AuditLogEntryTable.afterSnapshot] shouldBe "{\"noiseSuppressionDefault\":true}"
                entry[AuditLogEntryTable.afterSnapshot]!! shouldNotContain "@"

                client.setNoise(ADMIN_ID, true).status shouldBe HttpStatusCode.OK // same value again
                auditCount() shouldBe start + 1

                client.setNoise(ADMIN_ID, false).status shouldBe HttpStatusCode.OK
                auditCount() shouldBe start + 2
            }
        }

        test("the generic updateOrganizationSettings leaves the default untouched, switched on and switched off") {
            withApp {
                for (preset in listOf(true, false)) {
                    setDefault(preset)
                    val answer = client.post("/t/generic?name=Generisch") { header("X-Member-Id", ADMIN_ID) }
                    answer.status shouldBe HttpStatusCode.OK
                    answer.bodyAsText() shouldBe preset.toString()
                    client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe preset.toString()
                    transaction {
                        OrganizationSettingsTable
                            .selectAll()
                            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                            .single()[OrganizationSettingsTable.noiseSuppressionDefault]
                    } shouldBe preset
                }
            }
        }

        test("the store answers off for a missing value and never throws") {
            setDefault(true)
            NoiseSuppressionDefaultStore.currentValue() shouldBe true
            setDefault(false)
            NoiseSuppressionDefaultStore.currentValue() shouldBe false
        }

        test("a snapshot written by a change names only the one field") {
            withApp {
                client.setNoise(ADMIN_ID, true)
                val after =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS }
                            .orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.DESC)
                            .first()[AuditLogEntryTable.afterSnapshot]!!
                    }
                after shouldContain "noiseSuppressionDefault"
                after.length shouldBe "{\"noiseSuppressionDefault\":true}".length
            }
        }
    })

private fun Route.registerNoiseDefaultTestRoutes() {
    post("/t/noise") {
        val dto = OrganizationSettingsService(call).updateNoiseSuppressionDefault(call.request.queryParameters["enabled"]!!.toBoolean())
        call.respondText(dto.noiseSuppressionDefault.toString())
    }
    get("/t/get") {
        call.respondText(OrganizationSettingsService(call).getOrganizationSettings().noiseSuppressionDefault.toString())
    }
    post("/t/generic") {
        val service = OrganizationSettingsService(call)
        val dto =
            service.updateOrganizationSettings(
                network.lapis.cloud.shared.domain
                    .OrganizationSettingsInput(name = call.request.queryParameters["name"]!!),
            )
        call.respondText(dto.noiseSuppressionDefault.toString())
    }
}
