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
import network.lapis.cloud.shared.rpc.ConflictException
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
 * Welle V1.9.332 -- the instance mail language: [OrganizationSettingsService.updateMailLanguage] is ADMIN-only, accepts exactly the eight codes,
 * audits exactly once per real change, and is NOT reachable through the generic [OrganizationSettingsService.updateOrganizationSettings].
 */
class OrganizationSettingsMailLanguageTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        fun setRaw(value: String) =
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) { it[mailLanguage] = value }
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

        afterTest { setRaw("de") }

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
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing { registerMailLanguageTestRoutes() }
                }
                block()
            }

        suspend fun HttpClient.setLang(
            who: String?,
            language: String,
        ) = post("/t/lang?lang=${java.net.URLEncoder.encode(language, "UTF-8")}") {
            if (who != null) header("X-Member-Id", who)
        }

        test("ADMIN saves and reads the language; the default is de") {
            withApp {
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "de"
                client.setLang(ADMIN_ID, "pl").bodyAsText() shouldBe "pl"
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "pl"
            }
        }

        test("every supported code is accepted") {
            withApp {
                listOf("de", "en", "es", "fr", "it", "nl", "pl", "ru").forEach { client.setLang(ADMIN_ID, it).bodyAsText() shouldBe it }
            }
        }

        test("invalid codes are refused and nothing changes") {
            withApp {
                for (bad in listOf("xx", "", "PL", " pl", "pl ", "pl\n", "polish")) {
                    client.setLang(ADMIN_ID, bad).status shouldBe HttpStatusCode.Conflict
                }
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "de"
            }
        }

        test("BOARD, TREASURER, MEMBER and anonymous callers are refused") {
            withApp {
                for (who in listOf(BOARD_ID, TREASURER_ID, MEMBER_ID)) client.setLang(who, "en").status shouldBe HttpStatusCode.Forbidden
                client.setLang(null, "en").status shouldBe HttpStatusCode.Unauthorized
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "de"
            }
        }

        test("a real change writes one audit entry with a one-field snapshot; a no-op writes nothing") {
            withApp {
                val start = auditCount()
                client.setLang(ADMIN_ID, "de").status shouldBe HttpStatusCode.OK
                auditCount() shouldBe start
                client.setLang(ADMIN_ID, "pl").status shouldBe HttpStatusCode.OK
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
                entry[AuditLogEntryTable.beforeSnapshot] shouldBe "{\"mailLanguage\":\"de\"}"
                entry[AuditLogEntryTable.afterSnapshot] shouldBe "{\"mailLanguage\":\"pl\"}"
                entry[AuditLogEntryTable.afterSnapshot]!! shouldNotContain "@"
                client.setLang(ADMIN_ID, "pl").status shouldBe HttpStatusCode.OK
                auditCount() shouldBe start + 1
            }
        }

        test("the generic updateOrganizationSettings leaves the language untouched") {
            withApp {
                setRaw("ru")
                client.post("/t/generic?name=Generisch") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "ru"
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "ru"
            }
        }

        test("a foreign value in the column reads as de; the store never throws") {
            setRaw("xx")
            MailLanguageStore.loadOrDefault() shouldBe "de"
            withApp { client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "de" }
            setRaw("fr")
            MailLanguageStore.loadOrDefault() shouldBe "fr"
        }
    })

private fun service(call: io.ktor.server.application.ApplicationCall) =
    OrganizationSettingsService(call = call, pulpitRecording = network.lapis.cloud.server.encounter.NO_PULPIT_RECORDING)

private fun Route.registerMailLanguageTestRoutes() {
    post("/t/lang") { call.respondText(service(call).updateMailLanguage(call.request.queryParameters["lang"].orEmpty()).mailLanguage) }
    get("/t/get") { call.respondText(service(call).getOrganizationSettings().mailLanguage) }
    post("/t/generic") {
        val dto =
            service(call).updateOrganizationSettings(
                network.lapis.cloud.shared.domain
                    .OrganizationSettingsInput(name = call.request.queryParameters["name"]!!),
            )
        call.respondText(dto.mailLanguage)
    }
}
