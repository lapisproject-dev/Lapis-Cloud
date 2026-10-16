package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
import network.lapis.cloud.server.encounter.PulpitRecordingNotice
import network.lapis.cloud.server.encounter.PulpitSettingsFixture
import network.lapis.cloud.server.encounter.PulpitStopReason
import network.lapis.cloud.server.encounter.RecordingControlSpy
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.domain.PULPIT_RECORDING_NOTICE_VERSION
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.PulpitRecordingNoticeOutdatedException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * V1.9.143 -- the instance switch of the pulpit recording ([OrganizationSettingsService.updatePulpitRecordingEnabled] / `...RequireConfirmation`):
 * ADMIN only; switching ON needs the CURRENT version of the operator notice (a distinct exception otherwise); every real change is audited once with
 * the version and the hash of the German reference text and no personal data; switching OFF stops every running recording (after the commit); and the
 * generic update never touches the three columns.
 */
class OrganizationSettingsPulpitRecordingTest :
    FunSpec({
        val spy = RecordingControlSpy()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterTest {
            PulpitSettingsFixture.reset()
            spy.stoppedAll.clear()
        }

        fun entries() =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS) and
                            (AuditLogEntryTable.entityId eq ORGANIZATION_SETTINGS_ID)
                    }.orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.DESC)
                    .toList()
            }

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
                        exception<ForbiddenException> { call, cause -> call.respondText("forbidden", status = HttpStatusCode.Forbidden) }
                        exception<PulpitRecordingNoticeOutdatedException> {
                            call,
                            _,
                            ->
                            call.respondText("outdated", status = HttpStatusCode.Conflict)
                        }
                        exception<ConflictException> { call, _ -> call.respondText("conflict", status = HttpStatusCode.BadRequest) }
                    }
                    routing { registerPulpitRoutes(spy) }
                }
                block()
            }

        suspend fun HttpClient.enable(
            who: String,
            enabled: Boolean,
            version: String?,
        ) = post("/t/enable?enabled=$enabled" + (version?.let { "&version=$it" } ?: "")) { header("X-Member-Id", who) }

        test("only an ADMIN may switch it; BOARD, TREASURER and MEMBER are refused and nothing changes") {
            withApp {
                for (who in listOf(BOARD_ID, TREASURER_ID, MEMBER_ID)) {
                    client.enable(who, true, PULPIT_RECORDING_NOTICE_VERSION).status shouldBe HttpStatusCode.Forbidden
                    client.post("/t/confirm?required=false") { header("X-Member-Id", who) }.status shouldBe HttpStatusCode.Forbidden
                }
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "false|null|true"
            }
        }

        test(
            "switching on needs the current version of the notice: none, an old and a made-up version are the distinct 'outdated' answer",
        ) {
            withApp {
                client.enable(ADMIN_ID, true, null).status shouldBe HttpStatusCode.Conflict
                client.enable(ADMIN_ID, true, "0").status shouldBe HttpStatusCode.Conflict
                client.enable(ADMIN_ID, true, "2").status shouldBe HttpStatusCode.Conflict
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "false|null|true"
                client.enable(ADMIN_ID, true, PULPIT_RECORDING_NOTICE_VERSION).bodyAsText() shouldBe
                    "true|$PULPIT_RECORDING_NOTICE_VERSION|true"
            }
        }

        test("a real change writes one audit entry with the version and the hash and no personal data; the same value writes nothing") {
            withApp {
                val start = entries().size
                client.enable(ADMIN_ID, true, PULPIT_RECORDING_NOTICE_VERSION).status shouldBe HttpStatusCode.OK
                entries().size shouldBe start + 1
                val entry = entries().first()
                entry[AuditLogEntryTable.action] shouldBe AuditAction.UPDATE
                entry[AuditLogEntryTable.beforeSnapshot]!! shouldContain "\"pulpitRecordingEnabled\":false"
                entry[AuditLogEntryTable.afterSnapshot]!! shouldContain "\"pulpitRecordingEnabled\":true"
                entry[AuditLogEntryTable.afterSnapshot]!! shouldContain
                    "\"pulpitRecordingNoticeVersion\":\"$PULPIT_RECORDING_NOTICE_VERSION\""
                entry[AuditLogEntryTable.afterSnapshot]!! shouldContain PulpitRecordingNotice.SHA256
                entry[AuditLogEntryTable.afterSnapshot]!! shouldNotContain "@"
                entry[AuditLogEntryTable.actorMemberId].toString() shouldBe ADMIN_ID
                client.enable(ADMIN_ID, true, PULPIT_RECORDING_NOTICE_VERSION).status shouldBe HttpStatusCode.OK
                entries().size shouldBe start + 1
            }
        }

        test("acknowledging the current version again while an older one is stored is a change and is audited") {
            withApp {
                PulpitSettingsFixture.set(enabled = true, noticeVersion = "0")
                val start = entries().size
                client.enable(ADMIN_ID, true, PULPIT_RECORDING_NOTICE_VERSION).bodyAsText() shouldBe
                    "true|$PULPIT_RECORDING_NOTICE_VERSION|true"
                entries().size shouldBe start + 1
                entries().first()[AuditLogEntryTable.beforeSnapshot]!! shouldContain "\"pulpitRecordingNoticeVersion\":\"0\""
            }
        }

        test(
            "switching off needs no version, keeps the acknowledged version as history and stops every running recording after the commit",
        ) {
            withApp {
                client.enable(ADMIN_ID, true, PULPIT_RECORDING_NOTICE_VERSION).status shouldBe HttpStatusCode.OK
                spy.stoppedAll.shouldBeEmpty()
                client.enable(ADMIN_ID, false, null).bodyAsText() shouldBe "false|$PULPIT_RECORDING_NOTICE_VERSION|true"
                spy.stoppedAll shouldBe listOf(PulpitStopReason.INSTANCE_DISABLED)
            }
        }

        test("the confirmation setting is ADMIN-only, audited on change and refused while the feature is off") {
            withApp {
                client.post("/t/confirm?required=false") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.BadRequest
                client.enable(ADMIN_ID, true, PULPIT_RECORDING_NOTICE_VERSION)
                val start = entries().size
                client.post("/t/confirm?required=false") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe
                    "true|$PULPIT_RECORDING_NOTICE_VERSION|false"
                entries().size shouldBe start + 1
                entries().first()[AuditLogEntryTable.afterSnapshot] shouldBe "{\"pulpitRecordingRequireConfirmation\":false}"
                client.post("/t/confirm?required=false") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                entries().size shouldBe start + 1
            }
        }

        test("the generic updateOrganizationSettings leaves all three columns untouched") {
            withApp {
                PulpitSettingsFixture.set(enabled = true, noticeVersion = "1", requireConfirmation = false)
                client.post("/t/generic?name=Generisch") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "true|1|false"
                PulpitSettingsFixture.set(enabled = false, noticeVersion = null, requireConfirmation = true)
                client.post("/t/generic?name=Generisch") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "false|null|true"
            }
        }

        test("the store answers off when nothing is set, and start is allowed only for the current notice version") {
            PulpitSettingsFixture.reset()
            PulpitRecordingSettingsStore.currentValue().startAllowed() shouldBe false
            PulpitSettingsFixture.set(enabled = true, noticeVersion = "0")
            PulpitRecordingSettingsStore.currentValue().startAllowed() shouldBe false
            PulpitSettingsFixture.set(enabled = true)
            PulpitRecordingSettingsStore.currentValue().startAllowed() shouldBe true
        }
    })

private fun Route.registerPulpitRoutes(spy: RecordingControlSpy) {
    fun svc(call: io.ktor.server.application.ApplicationCall) =
        OrganizationSettingsService(call = call, pulpitRecording = spy, recordingInfrastructureAvailable = true)

    fun summary(dto: network.lapis.cloud.shared.domain.OrganizationSettingsDto) =
        "${dto.pulpitRecordingEnabled}|${dto.pulpitRecordingAcknowledgedNoticeVersion}|${dto.pulpitRecordingRequireConfirmation}"
    post("/t/enable") {
        val q = call.request.queryParameters
        call.respondText(
            summary(svc(call).updatePulpitRecordingEnabled(enabled = q["enabled"]!!.toBoolean(), acknowledgedNoticeVersion = q["version"])),
        )
    }
    post("/t/confirm") {
        call.respondText(
            summary(svc(call).updatePulpitRecordingRequireConfirmation(call.request.queryParameters["required"]!!.toBoolean())),
        )
    }
    get("/t/get") { call.respondText(summary(svc(call).getOrganizationSettings())) }
    post("/t/generic") {
        call.respondText(
            summary(svc(call).updateOrganizationSettings(OrganizationSettingsInput(name = call.request.queryParameters["name"]!!))),
        )
    }
}
