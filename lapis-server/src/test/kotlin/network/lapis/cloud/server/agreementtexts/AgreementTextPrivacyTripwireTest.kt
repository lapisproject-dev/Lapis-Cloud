package network.lapis.cloud.server.agreementtexts

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.rpc.AgreementTextAdminService
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AgreementTextDraftInput
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

private const val MARKER = "EINDEUTIGE-MARKE-VERTRAGSTEXT-9981"

/**
 * V1.9.134 -- the text of an agreement never leaves the two tables: (a) saving and publishing with a text that carries a unique marker leaves
 * the marker in NO `before`/`after` column of `audit_log_entry`, (b) in no log line of the process, (c) the log calls of the feature's own sources
 * never reference the text.
 */
class AgreementTextPrivacyTripwireTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterEach { AgreementTextTestState.reset() }
        afterSpec { AgreementTextTestState.reset() }

        test("(a) and (b): the marker is in neither the audit chain nor any log line") {
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            root.addAppender(appender)
            try {
                testApplication {
                    application {
                        routing {
                            post("/t/save") {
                                val body = call.receiveText()
                                val service = AgreementTextAdminService(call)
                                val saved =
                                    service.saveDraft(
                                        AgreementTextDraftInput(
                                            kind = AgreementTextKind.MEMBERSHIP,
                                            versionLabel = "privacy.v1",
                                            validFrom = LocalDate.parse(call.request.queryParameters["validFrom"]!!),
                                            body = body,
                                        ),
                                    )
                                service.publishDraft(kind = AgreementTextKind.MEMBERSHIP, expectedDraftSha256 = saved.sha256)
                                call.respondText("OK")
                            }
                        }
                    }
                    val response =
                        client.post("/t/save?validFrom=${OrganizationTimeZone.today()}") {
                            header("X-Member-Id", "00000000-0000-0000-0000-000000000001")
                            setBody("Vertrag mit $MARKER mittendrin.")
                        }
                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsText() shouldBe "OK"
                }
                val columns =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.AGREEMENT_TEXT }
                            .map { "${it[AuditLogEntryTable.beforeSnapshot]} ${it[AuditLogEntryTable.afterSnapshot]}" }
                    }
                (columns.size >= 2) shouldBe true
                columns.forEach { it shouldNotContain MARKER }
                appender.list.forEach { it.formattedMessage shouldNotContain MARKER }
            } finally {
                root.detachAppender(appender)
            }
        }

        test("(c) the log calls of the feature's own sources never reference the body or the text") {
            val offenders =
                SourceScan
                    .mainFiles()
                    .filter { it.path.contains("/agreementtexts/") || it.name == "AgreementTextAdminService.kt" }
                    .flatMap { file ->
                        file.readLines().filter { "logger." in it }.filter { Regex("""\b(body|text)\b""").containsMatchIn(it) }
                    }
            offenders shouldBe emptyList()
        }
    })
