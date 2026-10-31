package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val T0 = LocalDateTime(2026, 11, 5, 10, 0, 0)

private fun parsed(
    serverId: Uuid,
    count: Int = 2,
    hostEvents: String = "x",
) = PartnerParseResult.Ok(
    remoteServerId = serverId,
    remoteName = "Partner",
    events =
        (1..count).map {
            ParsedPartnerEvent(
                id = "$hostEvents$it",
                title = "T$it",
                startsAt = LocalDateTime(2026, 11, 12, 18, 0, 0),
                endsAt = LocalDateTime(2026, 11, 12, 20, 0, 0),
                timeZone = "Europe/Berlin",
                online = false,
                place = null,
                summary = null,
                recurrenceSentence = null,
                url = "https://p.beispiel.de/veranstaltung/$it",
            )
        },
    partnersJson = null,
    droppedEvents = 0,
)

/**
 * V1.9.244 -- the store on the migrated V131 schema, on H2 and on PostgreSQL: the unique constraints that make the cache poison-proof, the
 * savepoint around a lost insert race (no `25P02`), the block-vs-fetch race, and the deterministic order of the lists.
 */
abstract class PartnerServerStoreScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        beforeTest {
            transaction {
                PartnerEventTable.deleteAll()
                PartnerServerTable.deleteAll()
                PartnerBlocklistTable.deleteAll()
            }
        }
        afterSpec {
            try {
                transaction {
                    PartnerEventTable.deleteAll()
                    PartnerServerTable.deleteAll()
                    PartnerBlocklistTable.deleteAll()
                }
            } finally {
                db.deactivate()
            }
        }

        test("two different hosts cannot hold the same announced server id, and the same event id may exist at two partners") {
            val shared = Uuid.random()
            val a =
                transaction {
                    PartnerServerStore.insertProbedPartner(
                        host = "a.beispiel.de",
                        displayName = null,
                        parsed = parsed(serverId = shared),
                        etag = null,
                        now = T0,
                    )
                }
            a.shouldBe(a)
            (a is PartnerInsertResult.Inserted) shouldBe true
            val second =
                transaction {
                    PartnerServerStore.insertProbedPartner(
                        host = "b.beispiel.de",
                        displayName = null,
                        parsed = parsed(serverId = shared),
                        etag = null,
                        now = T0,
                    )
                }
            (second as PartnerInsertResult.Failed).error shouldBe PartnerFetchErrorCode.SERVER_ID_TAKEN
            val other =
                transaction {
                    PartnerServerStore.insertProbedPartner(
                        host = "c.beispiel.de",
                        displayName = null,
                        parsed = parsed(serverId = Uuid.random()),
                        etag = null,
                        now = T0,
                    )
                }
            (other is PartnerInsertResult.Inserted) shouldBe true
            transaction { PartnerEventTable.selectAll().count() } shouldBe 4L
        }

        test("a fetch cannot move a partner onto a server id that is bound to another host") {
            val idA = Uuid.random()
            val idB = Uuid.random()
            val a =
                (
                    transaction {
                        PartnerServerStore.insertProbedPartner(
                            host = "a.beispiel.de",
                            displayName = null,
                            parsed = parsed(serverId = idA),
                            etag = null,
                            now = T0,
                        )
                    } as PartnerInsertResult.Inserted
                ).id
            transaction {
                PartnerServerStore.insertProbedPartner(
                    host = "b.beispiel.de",
                    displayName = null,
                    parsed = parsed(serverId = idB),
                    etag = null,
                    now = T0,
                )
            }
            transaction { PartnerServerStore.applyFetchSuccess(id = a, parsed = parsed(serverId = idB), etag = null, now = T0) } shouldBe
                false
            transaction { PartnerServerStore.findPartner(a)!!.lastError } shouldBe "SERVER_ID_TAKEN"
            transaction { PartnerServerStore.findPartner(a)!!.remoteServerId } shouldBe idA
        }

        test("concurrent probes of the same host leave exactly one row and the loser sees DUPLICATE, not a broken transaction") {
            val results =
                coroutineScope {
                    (1..6)
                        .map { n ->
                            async(Dispatchers.IO) {
                                runCatching {
                                    transaction {
                                        PartnerServerStore.insertProbedPartner(
                                            host = "race.beispiel.de",
                                            displayName = null,
                                            parsed = parsed(serverId = Uuid.random()),
                                            etag = null,
                                            now = T0,
                                        )
                                    }
                                }
                            }
                        }.awaitAll()
                }
            results.count { it.isFailure } shouldBe 0
            results.map { it.getOrThrow() }.count { it is PartnerInsertResult.Inserted } shouldBe 1
            results
                .map {
                    it.getOrThrow()
                }.filterIsInstance<PartnerInsertResult.Failed>()
                .all { it.error == PartnerFetchErrorCode.DUPLICATE } shouldBe
                true
            transaction { PartnerServerTable.selectAll().count() } shouldBe 1L
        }

        test("a block added while a fetch is on the wire: the fetch result is dropped, no event of the blocked host survives") {
            val id = Uuid.random()
            val partner =
                (
                    transaction {
                        PartnerServerStore.insertProbedPartner(
                            host = "w.beispiel.de",
                            displayName = null,
                            parsed = parsed(serverId = id),
                            etag = null,
                            now = T0,
                        )
                    } as PartnerInsertResult.Inserted
                ).id
            transaction { PartnerServerStore.addBlock(pattern = "w.beispiel.de", now = T0) }
            transaction {
                PartnerServerStore.applyFetchSuccess(id = partner, parsed = parsed(serverId = id, count = 5), etag = "\"e\"", now = T0)
            } shouldBe false
            transaction { PartnerEventTable.selectAll().count() } shouldBe 0L
            // and the same race the other way round, concurrently
            transaction { PartnerBlocklistTable.deleteAll() }
            transaction {
                PartnerServerStore.applyFetchSuccess(id = partner, parsed = parsed(serverId = id, count = 3), etag = null, now = T0)
            } shouldBe true
            coroutineScope {
                listOf(
                    async(
                        Dispatchers.IO,
                    ) {
                        transaction {
                            PartnerServerStore.applyFetchSuccess(
                                id = partner,
                                parsed = parsed(serverId = id, count = 4),
                                etag = null,
                                now = T0,
                            )
                        }
                    },
                    async(Dispatchers.IO) { transaction { PartnerServerStore.addBlock(pattern = "w.beispiel.de", now = T0) } },
                ).awaitAll()
            }
            transaction { PartnerEventTable.selectAll().count() } shouldBe 0L
        }

        test("the lists have a deterministic order") {
            listOf("c.beispiel.de", "a.beispiel.de", "b.beispiel.de").forEach {
                transaction {
                    PartnerServerStore.insertProbedPartner(
                        host = it,
                        displayName = null,
                        parsed = parsed(serverId = Uuid.random()),
                        etag = null,
                        now = T0,
                    )
                }
            }
            listOf("z.de", "*.m.de", "a.de").forEach { transaction { PartnerServerStore.addBlock(pattern = it, now = T0) } }
            transaction { PartnerServerStore.listPartners().map { it.host } } shouldContainExactly
                listOf("a.beispiel.de", "b.beispiel.de", "c.beispiel.de")
            transaction { PartnerServerStore.listBlocklist().map { it.pattern } } shouldContainExactly listOf("*.m.de", "a.de", "z.de")
        }

        test("the check constraints refuse an upper-case host, a negative counter and a wrong status") {
            fun rejected(sql: String): Boolean =
                runCatching {
                    transaction {
                        exec(sql)
                    }
                }.isFailure
            val id = Uuid.random()
            rejected(
                "INSERT INTO partner_server (id, host, created_at, next_fetch_at) VALUES ('$id', 'UPPER.de', TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 10:00:00')",
            ) shouldBe true
            rejected(
                "INSERT INTO partner_server (id, host, event_count, created_at, next_fetch_at) VALUES ('$id', 'ok.de', -1, TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 10:00:00')",
            ) shouldBe true
            rejected(
                "INSERT INTO partner_server (id, host, last_status, created_at, next_fetch_at) VALUES ('$id', 'ok.de', 'WAT', TIMESTAMP '2026-11-05 10:00:00', TIMESTAMP '2026-11-05 10:00:00')",
            ) shouldBe true
            rejected(
                "INSERT INTO partner_blocklist (id, pattern, created_at) VALUES ('$id', 'UP.de', TIMESTAMP '2026-11-05 10:00:00')",
            ) shouldBe
                true
        }
    })
