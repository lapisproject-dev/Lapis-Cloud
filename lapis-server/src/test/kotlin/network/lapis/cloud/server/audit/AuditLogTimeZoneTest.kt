package network.lapis.cloud.server.audit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.rpc.AuditLogService
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.AuditLogListQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"

/**
 * V1.9.38 -- the audit-log filter bounds are wall-clocks typed in the organization zone (the list shows organization time), while
 * `occurred_at` is a UTC stamp: the service converts the bounds before comparing, so "from 20:00" means 20:00 in Berlin.
 */
class AuditLogTimeZoneTest :
    FunSpec({
        val entityId = Uuid.random()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { TimeTestSupport.resetOrganizationZone() }
        afterSpec {
            // Test-only cleanup -- audit_log_entry stays append-only in production.
            transaction { AuditLogEntryTable.deleteWhere { AuditLogEntryTable.entityId eq entityId } }
            TimeTestSupport.resetOrganizationZone()
        }

        fun record(occurredAtUtc: LocalDateTime) {
            transaction {
                AuditLogRecorder.record(
                    actorMemberId = Uuid.parse(ADMIN_ID),
                    actorRole = AccountRole.ADMIN,
                    entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                    entityId = entityId,
                    action = AuditAction.UPDATE,
                    before = null,
                    after = "{}",
                    occurredAt = occurredAtUtc,
                )
            }
        }

        test("from/to are read in the organization zone: 20:00 Berlin is 18:00Z") {
            record(LocalDateTime(2026, 7, 1, 17, 0)) // 19:00 Berlin
            record(LocalDateTime(2026, 7, 1, 19, 0)) // 21:00 Berlin
            testApplication {
                application {
                    routing {
                        get("/audit/count") {
                            val q = call.request.queryParameters
                            val result =
                                AuditLogService(call).listAuditLog(
                                    AuditLogListQuery(
                                        entityId = entityId.toString(),
                                        from = q["from"]?.let { LocalDateTime.parse(it) },
                                        to = q["to"]?.let { LocalDateTime.parse(it) },
                                    ),
                                )
                            call.respondText(result.map { it.occurredAt.toString() }.sorted().joinToString(","))
                        }
                    }
                }
                // "from 20:00" (Berlin) keeps only the 21:00-Berlin entry (19:00Z) and drops the 19:00-Berlin one (17:00Z)
                client.get("/audit/count?from=2026-07-01T20:00") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe
                    "2026-07-01T19:00"
                // "to 20:00" (Berlin) keeps only the 19:00-Berlin entry (17:00Z)
                client.get("/audit/count?to=2026-07-01T20:00") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "2026-07-01T17:00"
                // a window covering both
                client
                    .get("/audit/count?from=2026-07-01T18:00&to=2026-07-01T22:00") { header("X-Member-Id", ADMIN_ID) }
                    .bodyAsText() shouldBe "2026-07-01T17:00,2026-07-01T19:00"
            }
        }
    })
