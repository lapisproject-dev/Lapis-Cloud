package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import network.lapis.cloud.server.db.generated.MemberFinderConsentEventTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.encounter.NO_PULPIT_RECORDING
import network.lapis.cloud.server.memberfinder.FinderTestData
import network.lapis.cloud.server.memberfinder.MemberFinderBlockStore
import network.lapis.cloud.server.memberfinder.MemberFinderConsentStore
import network.lapis.cloud.server.memberfinder.MemberFinderNotice
import network.lapis.cloud.server.memberfinder.MemberFinderRevokeReason
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MEMBER_FINDER_NOTICE_VERSION
import network.lapis.cloud.shared.domain.OrganizationSettingsInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.MemberFinderNoticeOutdatedException
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
 * V1.9.144 -- the instance switch of the member finder ([OrganizationSettingsService.updateMemberFinderEnabled]): ADMIN only; switching ON needs the
 * CURRENT version of the operator notice; every real change is audited once with the version and the hash of the German reference text and NO count and
 * no member; switching OFF deletes requests, blocks and bands and ends every consent without reviving it on the next switch-on; the generic update never
 * touches the two columns.
 */
class OrganizationSettingsMemberFinderTest :
    FunSpec({
        val data = FinderTestData()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterTest { data.reset() }

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
                        exception<ForbiddenException> { call, _ -> call.respondText("forbidden", status = HttpStatusCode.Forbidden) }
                        exception<MemberFinderNoticeOutdatedException> {
                            call,
                            _,
                            ->
                            call.respondText("outdated", status = HttpStatusCode.Conflict)
                        }
                        exception<ConflictException> { call, _ -> call.respondText("conflict", status = HttpStatusCode.BadRequest) }
                    }
                    routing { registerFinderSwitchRoutes() }
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
                    client.enable(who, true, MEMBER_FINDER_NOTICE_VERSION).status shouldBe HttpStatusCode.Forbidden
                }
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "false|null"
            }
        }

        test(
            "switching on needs the current version of the notice: none, an old and a made-up version are the distinct 'outdated' answer",
        ) {
            withApp {
                client.enable(ADMIN_ID, true, null).status shouldBe HttpStatusCode.Conflict
                client.enable(ADMIN_ID, true, "0").status shouldBe HttpStatusCode.Conflict
                client.enable(ADMIN_ID, true, "2").status shouldBe HttpStatusCode.Conflict
                client.get("/t/get") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "false|null"
                client.enable(ADMIN_ID, true, MEMBER_FINDER_NOTICE_VERSION).bodyAsText() shouldBe "true|$MEMBER_FINDER_NOTICE_VERSION"
            }
        }

        test("a real change writes one audit entry with the version and the hash and no personal data; the same value writes nothing") {
            withApp {
                val start = entries().size
                client.enable(ADMIN_ID, true, MEMBER_FINDER_NOTICE_VERSION).status shouldBe HttpStatusCode.OK
                entries().size shouldBe start + 1
                val entry = entries().first()
                entry[AuditLogEntryTable.action] shouldBe AuditAction.UPDATE
                entry[AuditLogEntryTable.beforeSnapshot]!! shouldContain "\"memberFinderEnabled\":false"
                entry[AuditLogEntryTable.afterSnapshot]!! shouldContain "\"memberFinderEnabled\":true"
                entry[AuditLogEntryTable.afterSnapshot]!! shouldContain "\"memberFinderNoticeVersion\":\"$MEMBER_FINDER_NOTICE_VERSION\""
                entry[AuditLogEntryTable.afterSnapshot]!! shouldContain "\"noticeSha256\":\"${MemberFinderNotice.SHA256}\""
                entry[AuditLogEntryTable.afterSnapshot]!! shouldNotContain "@"
                entry[AuditLogEntryTable.actorMemberId].toString() shouldBe ADMIN_ID
                client.enable(ADMIN_ID, true, MEMBER_FINDER_NOTICE_VERSION).status shouldBe HttpStatusCode.OK
                entries().size shouldBe start + 1
            }
        }

        test("acknowledging the current version again while an older one is stored is a change and is audited") {
            withApp {
                data.enableFinder()
                setNoticeVersion("0")
                val start = entries().size
                client.enable(ADMIN_ID, true, MEMBER_FINDER_NOTICE_VERSION).bodyAsText() shouldBe "true|$MEMBER_FINDER_NOTICE_VERSION"
                entries().size shouldBe start + 1
                entries().first()[AuditLogEntryTable.beforeSnapshot]!! shouldContain "\"memberFinderNoticeVersion\":\"0\""
            }
        }

        test(
            "switching off deletes requests, blocks and bands, ends every consent with a system revoke, writes no count -- and a later switch-on revives nothing",
        ) {
            withApp {
                client.enable(ADMIN_ID, true, MEMBER_FINDER_NOTICE_VERSION).status shouldBe HttpStatusCode.OK
                val six = data.region(count = 6, label = "six")
                data.request(from = six[0], to = six[1])
                data.request(from = six[2], to = null)
                transaction { MemberFinderBlockStore.block(blockerMemberId = six[3], blockedMemberId = six[4], now = ServerClock.now()) }
                data.computeBands()
                val before = entries().size
                client.enable(ADMIN_ID, false, null).bodyAsText() shouldBe "false|$MEMBER_FINDER_NOTICE_VERSION"
                data.requests().size shouldBe 0
                data.blockCount() shouldBe 0
                data.bandRows() shouldBe 0
                for (m in six) {
                    transaction { MemberFinderConsentStore.openGrant(m) }.shouldBeNull()
                    // the consent events stay as the proof: GRANT and the system REVOKE
                    data.eventCount(m) shouldBe 2
                }
                transaction {
                    MemberFinderConsentEventTable
                        .selectAll()
                        .where { MemberFinderConsentEventTable.revokeReason eq MemberFinderRevokeReason.INSTANCE_OFF }
                        .count()
                } shouldBe 6L
                entries().size shouldBe before + 1
                val off = entries().first()[AuditLogEntryTable.afterSnapshot]!!
                off shouldNotContain "noticeSha256"
                off shouldBe "{\"memberFinderEnabled\":false,\"memberFinderNoticeVersion\":\"$MEMBER_FINDER_NOTICE_VERSION\"}"

                client.enable(ADMIN_ID, true, MEMBER_FINDER_NOTICE_VERSION).status shouldBe HttpStatusCode.OK
                for (m in six) transaction { MemberFinderConsentStore.openGrant(m) }.shouldBeNull()
            }
        }

        test("switching off when it was already off writes nothing and deletes nothing") {
            withApp {
                data.enableFinder(false)
                val six = data.region(count = 6, label = "six")
                data.request(from = six[0], to = six[1])
                val start = entries().size
                client.enable(ADMIN_ID, false, null).status shouldBe HttpStatusCode.OK
                entries().size shouldBe start
                data.requests().size shouldBe 1
            }
        }

        test("the generic updateOrganizationSettings leaves both columns untouched, and the input has no such field") {
            withApp {
                data.enableFinder()
                client.post("/t/generic?name=Generisch") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe
                    "true|$MEMBER_FINDER_NOTICE_VERSION"
                data.enableFinder(false)
                client.post("/t/generic?name=Generisch") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "false|null"
            }
            OrganizationSettingsInput::class.java.declaredFields
                .map { it.name }
                .filter { it.contains("memberFinder", ignoreCase = true) } shouldBe emptyList()
        }

        test("the store answers off when nothing is set, and the finder is active only for the current notice version") {
            data.enableFinder(false)
            MemberFinderSettingsStore.currentValue().active() shouldBe false
            data.enableFinder()
            MemberFinderSettingsStore.currentValue().active() shouldBe true
            setNoticeVersion("0")
            MemberFinderSettingsStore.currentValue().active() shouldBe false
            MemberFinderSettingsStore.currentValue().enabled shouldBe true
            MemberFinderSettingsStore.currentValue().noticeVersion.shouldNotBeNull()
        }
    })

private fun setNoticeVersion(version: String) {
    transaction {
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[memberFinderNoticeVersion] =
                version
        }
    }
}

private fun Route.registerFinderSwitchRoutes() {
    fun svc(call: io.ktor.server.application.ApplicationCall) =
        OrganizationSettingsService(call = call, pulpitRecording = NO_PULPIT_RECORDING)

    fun summary(dto: network.lapis.cloud.shared.domain.OrganizationSettingsDto) =
        "${dto.memberFinderEnabled}|${dto.memberFinderAcknowledgedNoticeVersion}"
    post("/t/enable") {
        val q = call.request.queryParameters
        call.respondText(
            summary(svc(call).updateMemberFinderEnabled(enabled = q["enabled"]!!.toBoolean(), acknowledgedNoticeVersion = q["version"])),
        )
    }
    get("/t/get") { call.respondText(summary(svc(call).getOrganizationSettings())) }
    post("/t/generic") {
        call.respondText(
            summary(svc(call).updateOrganizationSettings(OrganizationSettingsInput(name = call.request.queryParameters["name"]!!))),
        )
    }
}
