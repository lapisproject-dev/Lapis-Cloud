package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val T = RECURSION_NOW

private fun candidate(
    host: String,
    rootId: Uuid,
    rootHost: String,
) = RecursionCandidate(
    host = host,
    hops = 2,
    root = RecursionRoot(partnerId = rootId, host = rootHost, name = rootHost, remoteServerId = null, partners = listOf(host)),
    via = listOf(rootHost),
    lastFetchAt = null,
)

private fun okParsed(
    host: String,
    serverId: Uuid = Uuid.random(),
    count: Int = 2,
    start: LocalDateTime = LocalDateTime(2026, 11, 12, 18, 0, 0),
) = PartnerParseResult.Ok(
    remoteServerId = serverId,
    remoteName = "Server $host",
    events =
        (1..count).map {
            ParsedPartnerEvent(
                id = "e$it",
                title = "T$it",
                startsAt = start,
                endsAt = start.plusHoursKt(2),
                timeZone = "UTC",
                online = false,
                place = null,
                summary = null,
                recurrenceSentence = null,
                url = "https://$host/e/$it",
            )
        },
    partnersJson = null,
    droppedEvents = 0,
)

private fun eventCount() = transaction { PartnerEventTable.selectAll().count() }

/**
 * V1.9.245 -- the discovery store on the migrated V132 schema, on H2 and on PostgreSQL: cascades, the new unique constraint under a savepoint
 * (no `25P02`), the deterministic order of the lists, and the races between a block and a write-back and between two claims of one server id.
 */
abstract class PartnerRecursionStoreScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        beforeTest { wipePartnerTables() }
        afterSpec {
            try {
                wipePartnerTables()
            } finally {
                db.deactivate()
            }
        }

        test("cascades: removing the root removes its discovered rows and every event of the sub-tree") {
            val a = addDirectPartner(host = "a.beispiel.de")
            transaction {
                PartnerDiscoveryStore.applyDiscoveredSuccess(
                    c = candidate(host = "b.beispiel.de", rootId = a, rootHost = "a.beispiel.de"),
                    parsed = okParsed(host = "b.beispiel.de"),
                    etag = null,
                    now = T,
                )
            }
            transaction {
                PartnerDiscoveryStore.applyDiscoveredSuccess(
                    c = candidate(host = "c.beispiel.de", rootId = a, rootHost = "a.beispiel.de"),
                    parsed = okParsed(host = "c.beispiel.de"),
                    etag = null,
                    now = T,
                )
            }
            transaction { PartnerDiscoveredServerTable.selectAll().count() } shouldBe 2L
            eventCount() shouldBe 5L // 1 direct + 2 + 2
            transaction { PartnerServerTable.deleteWhere { PartnerServerTable.id eq a } }
            transaction { PartnerDiscoveredServerTable.selectAll().count() } shouldBe 0L
            eventCount() shouldBe 0L
        }

        test("the unique (server id, event id) violation under a savepoint leaves the transaction usable and the block runs once") {
            val a = addDirectPartner(host = "a.beispiel.de")
            var runs = 0
            transaction {
                PartnerDiscoveryStore.applyDiscoveredSuccess(
                    c = candidate(host = "b.beispiel.de", rootId = a, rootHost = "a.beispiel.de"),
                    parsed = okParsed(host = "b.beispiel.de", count = 1),
                    etag = null,
                    now = T,
                )
                val existing = PartnerEventTable.selectAll().where { PartnerEventTable.hops eq 2.toShort() }.single()
                val outcome =
                    try {
                        withSavepoint(name = "dup") {
                            runs++
                            PartnerEventTable.insert {
                                it[id] = Uuid.random()
                                it[partnerServerId] = a
                                it[remoteServerId] = existing[PartnerEventTable.remoteServerId]
                                it[remoteEventId] = existing[PartnerEventTable.remoteEventId]
                                it[title] = "dup"
                                it[startsAt] = T
                                it[endsAt] = T
                                it[timeZone] = "UTC"
                                it[online] = false
                                it[url] = "https://b.beispiel.de/e"
                                it[fetchedAt] = T
                                it[expiresAt] = T
                                it[originHost] = "b.beispiel.de"
                            }
                        }
                        "no violation"
                    } catch (e: Exception) {
                        if (e.isUniqueViolation()) "unique" else throw e
                    }
                outcome shouldBe "unique"
                // still usable after the violation (PostgreSQL would answer 25P02 without the savepoint)
                PartnerEventTable.selectAll().count() shouldBe 2L
            }
            runs shouldBe 1
        }

        test("the lists are ordered deterministically in Kotlin, also for events that start at the same time") {
            val a = addDirectPartner(host = "a.beispiel.de")
            listOf("z-h.beispiel.de", "a-h.beispiel.de", "a.h.beispiel.de", "m.beispiel.de").forEach { host ->
                transaction {
                    PartnerDiscoveryStore.applyDiscoveredSuccess(
                        c = candidate(host = host, rootId = a, rootHost = "a.beispiel.de"),
                        parsed = okParsed(host = host, count = 1),
                        etag = null,
                        now = T,
                    )
                }
            }
            transaction { PartnerDiscoveryStore.listForAdmin().map { it.host } } shouldContainExactly
                listOf("a-h.beispiel.de", "a.h.beispiel.de", "m.beispiel.de", "z-h.beispiel.de")
            val first =
                transaction {
                    PartnerEventDisplay
                        .loadVisible(
                            nowUtc = T,
                            orgZone = TimeZone.UTC,
                        ).filter { it.hops == 2 }
                        .map { it.originHost }
                }
            val second =
                transaction {
                    PartnerEventDisplay
                        .loadVisible(
                            nowUtc = T,
                            orgZone = TimeZone.UTC,
                        ).filter { it.hops == 2 }
                        .map { it.originHost }
                }
            first shouldBe second
            first.sorted() shouldBe first.sortedBy { it }
            first shouldContainExactly listOf("a-h.beispiel.de", "a.h.beispiel.de", "m.beispiel.de", "z-h.beispiel.de")
        }

        test(
            "two roots claim the same announced id at the same time: exactly one wins, the other is a conflict, nobody sees an exception",
        ) {
            repeat(5) {
                wipePartnerTables()
                val a = addDirectPartner(host = "a.beispiel.de")
                val b = addDirectPartner(host = "b.beispiel.de")
                val shared = Uuid.random()
                val results =
                    coroutineScope {
                        listOf(
                            async(Dispatchers.IO) {
                                transaction {
                                    PartnerDiscoveryStore.applyDiscoveredSuccess(
                                        c = candidate(host = "x.beispiel.de", rootId = a, rootHost = "a.beispiel.de"),
                                        parsed = okParsed(host = "x.beispiel.de", serverId = shared),
                                        etag = null,
                                        now = T,
                                    )
                                }
                            },
                            async(Dispatchers.IO) {
                                transaction {
                                    PartnerDiscoveryStore.applyDiscoveredSuccess(
                                        c = candidate(host = "y.beispiel.de", rootId = b, rootHost = "b.beispiel.de"),
                                        parsed = okParsed(host = "y.beispiel.de", serverId = shared),
                                        etag = null,
                                        now = T,
                                    )
                                }
                            },
                        ).awaitAll()
                    }
                results.count { it is DiscoveredWriteResult.Stored } shouldBe 1
                results.count { it is DiscoveredWriteResult.Conflict } shouldBe 1
                transaction { PartnerEventTable.selectAll().where { PartnerEventTable.hops eq 2.toShort() }.count() } shouldBe 2L
            }
        }

        test("a block races a write-back: whatever the order, no event of the blocked host survives") {
            repeat(10) {
                wipePartnerTables()
                val a = addDirectPartner(host = "a.beispiel.de")
                val results =
                    coroutineScope {
                        val write =
                            async(Dispatchers.IO) {
                                transaction {
                                    PartnerDiscoveryStore.applyDiscoveredSuccess(
                                        c = candidate(host = "b.beispiel.de", rootId = a, rootHost = "a.beispiel.de"),
                                        parsed = okParsed(host = "b.beispiel.de"),
                                        etag = null,
                                        now = T,
                                    )
                                }
                            }
                        val block =
                            async(Dispatchers.IO) { transaction { PartnerServerStore.addBlock(pattern = "b.beispiel.de", now = T) } }
                        block.await()
                        write.await()
                    }
                results.shouldBeInstanceOf<DiscoveredWriteResult>()
                transaction { PartnerEventTable.selectAll().where { PartnerEventTable.originHost eq "b.beispiel.de" }.count() } shouldBe 0L
                transaction {
                    PartnerDiscoveredServerTable.selectAll().where { PartnerDiscoveredServerTable.host eq "b.beispiel.de" }.count()
                } shouldBe 0L
            }
        }
    })
