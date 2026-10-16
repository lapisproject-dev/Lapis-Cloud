package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
 * Welle V1.9.129 -- the switch "members may use video overlays": [OrganizationSettingsService.updateVideoOverlaysOffered] is ADMIN-only,
 * audited exactly once per real change (never on a no-op), carries no personal data in the snapshot, and is NOT reachable through the
 * generic [OrganizationSettingsService.updateOrganizationSettings]. Same shape as [OrganizationSettingsNoiseSuppressionDefaultTest].
 */
class OrganizationSettingsVideoOverlaysOfferedTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun setOffered(value: Boolean) =
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[videoOverlaysOffered] = value
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

        afterTest { setOffered(false) }

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
                    routing { registerVideoOverlaysOfferedTestRoutes() }
                }
                block()
            }

        suspend fun HttpClient.setOffered(
            who: String?,
            enabled: Boolean,
        ) = post("/t/overlays?enabled=$enabled") { if (who != null) header("X-Member-Id", who) }

        test("ADMIN switches it on and off; BOARD, TREASURER, MEMBER and anonymous callers are refused and nothing changes") {
            withApp {
                for (who in listOf(BOARD_ID, TREASURER_ID, MEMBER_ID)) {
                    client.setOffered(who, true).status shouldBe HttpStatusCode.Forbidden
                }
                client.setOffered(null, true).status shouldBe HttpStatusCode.Unauthorized
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "false"

                client.setOffered(ADMIN_ID, true).bodyAsText() shouldBe "true"
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "true"
                client.setOffered(ADMIN_ID, false).bodyAsText() shouldBe "false"
            }
        }

        test("a real change writes one audit entry with a one-field snapshot and no personal data; a no-op writes nothing") {
            withApp {
                val start = auditCount()
                client.setOffered(ADMIN_ID, false).status shouldBe HttpStatusCode.OK
                auditCount() shouldBe start

                client.setOffered(ADMIN_ID, true).status shouldBe HttpStatusCode.OK
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
                entry[AuditLogEntryTable.beforeSnapshot] shouldBe "{\"videoOverlaysOffered\":false}"
                entry[AuditLogEntryTable.afterSnapshot] shouldBe "{\"videoOverlaysOffered\":true}"
                entry[AuditLogEntryTable.afterSnapshot]!! shouldNotContain "@"

                client.setOffered(ADMIN_ID, true).status shouldBe HttpStatusCode.OK
                auditCount() shouldBe start + 1
            }
        }

        test("the generic updateOrganizationSettings leaves the switch untouched, switched on and switched off") {
            withApp {
                for (preset in listOf(true, false)) {
                    setOffered(preset)
                    val answer = client.post("/t/generic?name=Generisch") { header("X-Member-Id", ADMIN_ID) }
                    answer.status shouldBe HttpStatusCode.OK
                    answer.bodyAsText() shouldBe preset.toString()
                    client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe preset.toString()
                }
            }
        }

        test("the store answers off for an unreadable value and never throws; the DTO of the settings carries the switch") {
            setOffered(true)
            VideoOverlaysOfferedStore.currentValue() shouldBe true
            setOffered(false)
            VideoOverlaysOfferedStore.currentValue() shouldBe false
        }
    })

private fun Route.registerVideoOverlaysOfferedTestRoutes() {
    post("/t/overlays") {
        val dto =
            OrganizationSettingsService(
                call = call,
                pulpitRecording = network.lapis.cloud.server.encounter.NO_PULPIT_RECORDING,
            ).updateVideoOverlaysOffered(call.request.queryParameters["enabled"]!!.toBoolean())
        call.respondText(dto.videoOverlaysOffered.toString())
    }
    get("/t/get") {
        call.respondText(
            OrganizationSettingsService(
                call = call,
                pulpitRecording = network.lapis.cloud.server.encounter.NO_PULPIT_RECORDING,
            ).getOrganizationSettings().videoOverlaysOffered.toString(),
        )
    }
    post("/t/generic") {
        val dto =
            OrganizationSettingsService(
                call = call,
                pulpitRecording = network.lapis.cloud.server.encounter.NO_PULPIT_RECORDING,
            ).updateOrganizationSettings(
                network.lapis.cloud.shared.domain
                    .OrganizationSettingsInput(name = call.request.queryParameters["name"]!!),
            )
        call.respondText(dto.videoOverlaysOffered.toString())
    }
}
