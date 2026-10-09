package network.lapis.cloud.server.events

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.audit.AuditChainVerifier
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.embed.EmbedOriginAllowlist
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.routes.registerEmbedEventsFeedRoutes
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.EventImportRowStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.9.82 -- the admin import of past events end to end, on H2 AND on PostgreSQL: dry run writes nothing, commit is all or
 * nothing, existing slugs are skipped and never updated, the hash guards against an edit between preview and commit, the audit chain
 * stays valid, a slug that appears concurrently is handled, and the authorization of the RPC service (BOARD/ADMIN only).
 */
abstract class EventImportScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val slugPrefix = "imp-${Uuid.random().toString().take(8)}-"
        beforeSpec {
            db.activate()
            // The acting member must exist for the foreign keys of `event.created_by` and `audit_log_entry.actor_member_id`. The audit log is
            // append-only, so a member created here could never be deleted again -- and `DevSeedData.seedIfEmpty` (used by other specs)
            // only seeds an EMPTY member table. On H2 the acting member is therefore a seeded demo member; the PostgreSQL lane runs in a
            // disposable database of its own and simply creates one.
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db)

        fun cleanEvents() {
            transaction { EventTable.deleteWhere { slug like "$slugPrefix%" } }
        }
        beforeTest { cleanEvents() }
        afterSpec {
            cleanEvents()
            db.deactivate()
        }

        fun createMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventImportScenarios ${role.name}"
                    it[email] = "event-import-${role.name.lowercase()}-$id@example.org"
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
            return id
        }

        val board: Uuid by lazy {
            val seeded = DevSeedData.demoMembers.first { it.role == AccountRole.BOARD }.id
            if (!db.isPostgres && transaction { MemberTable.selectAll().where { MemberTable.id eq seeded }.any() }) {
                seeded
            } else {
                createMember(AccountRole.BOARD)
            }
        }

        fun entry(
            n: Int,
            startsAt: String = "2025-03-01T19:00",
            endsAt: String? = "2025-03-01T22:00",
            extra: String = "",
            title: String = "Importiert $n",
        ): String {
            val parts =
                mutableListOf(
                    """"slug":"$slugPrefix$n"""",
                    """"title":"$title"""",
                    """"description":"Beschreibung $n\n\nZweiter Absatz"""",
                    """"startsAt":"$startsAt"""",
                    """"locationText":"Ort $n"""",
                )
            if (endsAt != null) parts += """"endsAt":"$endsAt""""
            if (extra.isNotBlank()) parts += extra
            return "{${parts.joinToString(",")}}"
        }

        fun payload(vararg entries: String) = "[${entries.joinToString(",")}]"

        fun wallNow() = OrganizationTimeZone.wallNowOf(DbClock.nowLocalDateTime())

        fun preview(json: String) = EventImporter.preview(json = json, wallNow = wallNow(), timeZoneId = "Europe/Berlin")

        fun commit(
            json: String,
            hash: String = EventImportPolicy.sha256Hex(json),
            onAttempt: () -> Unit = {},
        ): network.lapis.cloud.shared.domain.EventImportResultDto {
            val now = DbClock.nowLocalDateTime()
            return EventImporter.commit(
                json = json,
                payloadSha256 = hash,
                actorMemberId = board,
                actorRole = AccountRole.BOARD,
                now = now,
                wallNow = OrganizationTimeZone.wallNowOf(now),
                onAttempt = onAttempt,
            )
        }

        fun eventCount() =
            transaction {
                EventTable
                    .selectAll()
                    .where { EventTable.slug like "$slugPrefix%" }
                    .count()
                    .toInt()
            }

        fun auditCount(type: AuditEntityType) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityType eq type }
                    .count()
                    .toInt()
            }

        fun rowOf(n: Int) = transaction { EventTable.selectAll().where { EventTable.slug eq "$slugPrefix$n" }.single() }

        test("dry run writes nothing: no event, no audit entry") {
            val events = eventCount()
            val audits = auditCount(AuditEntityType.EVENT) + auditCount(AuditEntityType.EVENT_IMPORT)
            val result = preview(payload(entry(1), entry(2)))
            result.createCount shouldBe 2
            result.skipCount shouldBe 0
            result.errorCount shouldBe 0
            result.timeZoneId shouldBe "Europe/Berlin"
            eventCount() shouldBe events
            (auditCount(AuditEntityType.EVENT) + auditCount(AuditEntityType.EVENT_IMPORT)) shouldBe audits
        }

        test("preview rows: errors first, then by index; every row has a status") {
            val json = payload(entry(1), entry(2, startsAt = "2099-01-01T10:00", endsAt = null), entry(3))
            val rows = preview(json).rows
            rows.map { it.status } shouldContainExactly
                listOf(EventImportRowStatus.ERROR, EventImportRowStatus.CREATE, EventImportRowStatus.CREATE)
            rows.map { it.index } shouldContainExactly listOf(1, 0, 2)
            rows.first().reasons.isEmpty() shouldBe false
        }

        test("commit creates PUBLISHED + PUBLIC imported events, free, closed for registration, created by the actor") {
            val json =
                payload(
                    entry(1),
                    entry(
                        2,
                        extra = """"summary":"Kurz","coverImageAlt":"Plakat","onlineUrl":"https://meet.example/x","onlineUrlPublic":true""",
                    ),
                )
            val result = commit(json)
            result.created shouldBe 2
            result.skipped shouldBe 0
            val row = rowOf(1)
            row[EventTable.status] shouldBe EventStatus.PUBLISHED
            row[EventTable.visibility] shouldBe EventVisibility.PUBLIC
            row[EventTable.imported] shouldBe true
            row[EventTable.feeAmount].compareTo(BigDecimal.ZERO) shouldBe 0
            row[EventTable.feeCurrency] shouldBe "EUR"
            row[EventTable.capacity] shouldBe null
            row[EventTable.roomId] shouldBe null
            row[EventTable.seriesId] shouldBe null
            row[EventTable.createdBy] shouldBe board
            row[EventTable.startsAt] shouldBe LocalDateTime(2025, 3, 1, 19, 0)
            row[EventTable.endsAt] shouldBe LocalDateTime(2025, 3, 1, 22, 0)
            row[EventTable.registrationClosesAt] shouldBe LocalDateTime(2025, 3, 1, 19, 0)
            row[EventTable.description] shouldBe "Beschreibung 1\n\nZweiter Absatz"
            row[EventTable.onlineUrlPublic] shouldBe false
            val second = rowOf(2)
            second[EventTable.summary] shouldBe "Kurz"
            second[EventTable.coverImageAlt] shouldBe "Plakat"
            second[EventTable.onlineUrlPublic] shouldBe true
        }

        test("an existing slug is skipped and never updated") {
            commit(payload(entry(1, title = "Original")))
            val changed = payload(entry(1, title = "Ueberschrieben"), entry(2))
            preview(changed).skipCount shouldBe 1
            val result = commit(changed)
            result.created shouldBe 1
            result.skipped shouldBe 1
            result.skippedSlugs shouldContainExactly listOf("${slugPrefix}1")
            rowOf(1)[EventTable.title] shouldBe "Original"
            eventCount() shouldBe 2
        }

        test("a changed payload (hash mismatch) is a conflict and writes nothing") {
            val json = payload(entry(1))
            val hash = preview(json).payloadSha256
            shouldThrow<ConflictException> { commit(json + " ", hash = hash) }
            shouldThrow<ConflictException> { commit(json, hash = "0".repeat(64)) }
            eventCount() shouldBe 0
        }

        test("all or nothing: one error in the payload writes none of the valid entries") {
            val json = payload(entry(1), entry(2, startsAt = "2099-01-01T10:00", endsAt = null), entry(3))
            shouldThrow<BadRequestException> { commit(json) }
            eventCount() shouldBe 0
        }

        test("audit: one EVENT/CREATE per created event plus one EVENT_IMPORT summary; the chain stays valid; no payload text in the log") {
            val events = auditCount(AuditEntityType.EVENT)
            val imports = auditCount(AuditEntityType.EVENT_IMPORT)
            val json = payload(entry(1, title = "GEHEIMER-TITEL-4711"), entry(2))
            val hash = EventImportPolicy.sha256Hex(json)
            val lastSequenceBefore =
                transaction { AuditLogEntryTable.selectAll().maxOfOrNull { it[AuditLogEntryTable.sequenceNumber] } ?: 0L }
            commit(json)
            auditCount(AuditEntityType.EVENT) shouldBe events + 2
            auditCount(AuditEntityType.EVENT_IMPORT) shouldBe imports + 1
            transaction {
                // The window of this import only (its three entries): the shared test database may hold broken rows of other specs, but the
                // window's first entry must still link to its predecessor.
                val rows =
                    AuditLogEntryTable
                        .selectAll()
                        .where { AuditLogEntryTable.sequenceNumber greater lastSequenceBefore }
                        .orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.ASC)
                        .toList()
                rows.size shouldBe 3
                AuditChainVerifier.verify(rows).valid shouldBe true
                val latestImport = rows.last { it[AuditLogEntryTable.entityType] == AuditEntityType.EVENT_IMPORT }
                latestImport[AuditLogEntryTable.action] shouldBe AuditAction.CREATE
                latestImport[AuditLogEntryTable.actorMemberId] shouldBe board
                val summary = Json.parseToJsonElement(latestImport[AuditLogEntryTable.afterSnapshot]!!).jsonObject
                summary["created"]!!.jsonPrimitive.content shouldBe "2"
                summary["skipped"]!!.jsonPrimitive.content shouldBe "0"
                summary["payloadSha256"]!!.jsonPrimitive.content shouldBe hash
                rows.filter { it[AuditLogEntryTable.entityType] == AuditEntityType.EVENT }.takeLast(2).forEach { row ->
                    row[AuditLogEntryTable.afterSnapshot]!! shouldNotContain "GEHEIMER-TITEL-4711"
                    Json
                        .parseToJsonElement(row[AuditLogEntryTable.afterSnapshot]!!)
                        .jsonObject["imported"]!!
                        .jsonPrimitive.content shouldBe
                        "true"
                }
            }
        }

        test("a run that creates nothing writes no audit entry") {
            commit(payload(entry(1)))
            val before = auditCount(AuditEntityType.EVENT) + auditCount(AuditEntityType.EVENT_IMPORT)
            commit(payload(entry(1)))
            (auditCount(AuditEntityType.EVENT) + auditCount(AuditEntityType.EVENT_IMPORT)) shouldBe before
        }

        test("a slug created concurrently between read and insert is skipped on the retry; the block ran again and nothing is lost") {
            val runs = AtomicInteger(0)
            val racing = "${slugPrefix}2"
            val json = payload(entry(1), entry(2), entry(3))
            val result =
                commit(json) {
                    if (runs.incrementAndGet() == 1) {
                        val thread =
                            Thread {
                                transaction {
                                    EventTable.insert {
                                        it[id] = Uuid.random()
                                        it[slug] = racing
                                        it[title] = "Gleichzeitig angelegt"
                                        it[description] = "x"
                                        it[locationText] = "x"
                                        it[onlineUrl] = null
                                        it[startsAt] = LocalDateTime(2024, 1, 1, 10, 0)
                                        it[endsAt] = LocalDateTime(2024, 1, 1, 11, 0)
                                        it[capacity] = null
                                        it[feeAmount] = BigDecimal.ZERO
                                        it[feeCurrency] = "EUR"
                                        it[status] = EventStatus.PUBLISHED
                                        it[visibility] = EventVisibility.PUBLIC
                                        it[registrationClosesAt] = null
                                        it[createdAt] = DbClock.nowLocalDateTime()
                                        it[createdBy] = board
                                        it[cancelledAt] = null
                                    }
                                }
                            }
                        thread.start()
                        thread.join()
                    }
                }
            (runs.get() >= 2) shouldBe true
            result.created shouldBe 2
            result.skippedSlugs shouldContainExactly listOf(racing)
            rowOf(2)[EventTable.title] shouldBe "Gleichzeitig angelegt"
            rowOf(1)[EventTable.imported] shouldBe true
            rowOf(3)[EventTable.imported] shouldBe true
            eventCount() shouldBe 3
        }

        test("an imported event shows up in the archive feed and not in the upcoming feed") {
            commit(payload(entry(1)))
            val slug = "${slugPrefix}1"
            testApplication {
                application {
                    routing {
                        registerEmbedEventsFeedRoutes(
                            config =
                                EmbedConfig(
                                    enabled = true,
                                    allowlist = EmbedOriginAllowlist.parse(raw = "https://partei.example", allowInsecure = false).allowlist,
                                    allowInsecureOrigins = false,
                                ),
                            baseUrl = "https://lapis.example",
                            feedRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                            preflightRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                            pastFeedRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                        )
                    }
                }
                val upcoming = client.get("/api/embed/v1/events").bodyAsText()
                upcoming.contains(slug) shouldBe false
                var found = false
                var page = 1
                while (!found && page <= 100) {
                    val json = Json.parseToJsonElement(client.get("/api/embed/v1/events/past?limit=50&page=$page").bodyAsText()).jsonObject
                    found = json["events"]!!.jsonArray.any { it.jsonObject["slug"]!!.jsonPrimitive.content == slug }
                    if (!json["hasMore"]!!.jsonPrimitive.content.toBoolean()) break
                    page++
                }
                found shouldBe true
            }
        }

        test(
            "daylight saving time: nonexistent and ambiguous local times are stored as typed (class B) and the feed converts like for any event",
        ) {
            val json =
                payload(
                    entry(1, startsAt = "2025-03-30T02:30", endsAt = "2025-03-30T03:30"),
                    entry(2, startsAt = "2025-10-26T02:30", endsAt = "2025-10-26T03:30"),
                )
            commit(json)
            val zone = TimeZone.of("Europe/Berlin")
            // The importer itself never converts. A non-UTC PROCESS zone can still move a nonexistent local time inside the H2 test driver
            // (production runs with TZ=UTC, see time-and-timezones.adoc), so the exact round trip is only asserted under a UTC process zone.
            if (java.util.TimeZone
                    .getDefault()
                    .id in setOf("UTC", "Etc/UTC", "GMT")
            ) {
                rowOf(1)[EventTable.startsAt] shouldBe LocalDateTime(2025, 3, 30, 2, 30)
                rowOf(2)[EventTable.startsAt] shouldBe LocalDateTime(2025, 10, 26, 2, 30)
            }
            for (n in 1..2) {
                val row = rowOf(n)
                val expected = row[EventTable.startsAt].toInstant(zone).toLocalDateTime(TimeZone.UTC)
                val actual =
                    network.lapis.cloud.server.routes
                        .embedFeedUtc(dt = row[EventTable.startsAt], zone = zone)
                actual shouldBe
                    "%04d-%02d-%02dT%02d:%02d:%02dZ".format(
                        expected.year,
                        expected.monthNumber,
                        expected.dayOfMonth,
                        expected.hour,
                        expected.minute,
                        expected.second,
                    )
            }
        }

        test("the set of imported slugs is exactly what the payload said") {
            commit(payload(entry(5), entry(6), entry(7)))
            transaction {
                EventTable.selectAll().where { EventTable.slug like "$slugPrefix%" }.map { it[EventTable.slug] }
            }.shouldContainExactlyInAnyOrder("${slugPrefix}5", "${slugPrefix}6", "${slugPrefix}7")
        }
    })

class EventImportTest : EventImportScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class EventImportPostgresTest : EventImportScenarios(TestDatabase.Postgres())
