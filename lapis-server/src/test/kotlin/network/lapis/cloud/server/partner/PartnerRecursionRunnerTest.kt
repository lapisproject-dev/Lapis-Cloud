package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import network.lapis.cloud.shared.domain.PartnerFetchStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import kotlin.uuid.Uuid

private fun discovered() = transaction { PartnerDiscoveredServerTable.selectAll().toList() }

private fun discoveredHosts() = discovered().map { it[PartnerDiscoveredServerTable.host] }

private fun foreignEvents() = transaction { PartnerEventTable.selectAll().where { PartnerEventTable.hops greater 1.toShort() }.toList() }

private fun rowOf(host: String) = discovered().single { it[PartnerDiscoveredServerTable.host] == host }

/** V1.9.245 -- the recursion on H2 against a fake network of several servers. */
class PartnerRecursionRunnerTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { wipePartnerTables() }
        afterSpec { wipePartnerTables() }

        test("A -> B -> C: depth 2 reaches B (hop 2) and C (hop 3), events carry origin, chain and root") {
            val world = FakeWorld()
            val a = addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"))
            world.serve(host = "b.beispiel.de", partners = listOf("c.beispiel.de"))
            world.serve(host = "c.beispiel.de")
            val summary = runnerFor(world = world).run().shouldNotBeNull()
            summary.reachedByHop shouldBe listOf(1, 1)
            summary.eventsByHop shouldBe listOf(1, 1)
            rowOf("b.beispiel.de")[PartnerDiscoveredServerTable.hops].toInt() shouldBe 2
            rowOf("c.beispiel.de")[PartnerDiscoveredServerTable.hops].toInt() shouldBe 3
            rowOf("c.beispiel.de")[PartnerDiscoveredServerTable.rootPartnerServerId] shouldBe a
            val cEvent = foreignEvents().single { it[PartnerEventTable.originHost] == "c.beispiel.de" }
            cEvent[PartnerEventTable.hops].toInt() shouldBe 3
            cEvent[PartnerEventTable.viaHosts] shouldBe "a.beispiel.de b.beispiel.de"
            cEvent[PartnerEventTable.partnerServerId] shouldBe a
            // the direct partner's own events are untouched
            transaction { PartnerEventTable.selectAll().where { PartnerEventTable.hops eq 1.toShort() }.count() } shouldBe 1L
        }

        test("depth 1 stops at B, depth 3 follows one more level") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"))
            world.serve(host = "b.beispiel.de", partners = listOf("c.beispiel.de"))
            world.serve(host = "c.beispiel.de", partners = listOf("d.beispiel.de"))
            world.serve(host = "d.beispiel.de")
            transaction { PartnerDiscoveryStore.setRecursionSettings(enabled = true, depth = 1) }
            runnerFor(world = world).run()
            discoveredHosts() shouldContainExactlyInAnyOrder listOf("b.beispiel.de")
            transaction { PartnerDiscoveryStore.setRecursionSettings(enabled = true, depth = 3) }
            runnerFor(world = world, now = { RECURSION_NOW.plusHoursKt(3) }).run()
            discoveredHosts() shouldContainExactlyInAnyOrder listOf("b.beispiel.de", "c.beispiel.de", "d.beispiel.de")
            rowOf("d.beispiel.de")[PartnerDiscoveredServerTable.hops].toInt() shouldBe 4
        }

        test("loops: A -> B -> A, our own host and a server that lists everyone are fetched once each per run") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de", "own.example.com"))
            world.serve(host = "b.beispiel.de", partners = listOf("a.beispiel.de", "c.beispiel.de", "own.example.com", "b.beispiel.de"))
            world.serve(host = "c.beispiel.de", partners = listOf("b.beispiel.de", "a.beispiel.de", "c.beispiel.de"))
            world.serve(host = "a.beispiel.de")
            runnerFor(world = world).run()
            world.callsTo("b.beispiel.de") shouldBe 1
            world.callsTo("c.beispiel.de") shouldBe 1
            world.callsTo("a.beispiel.de") shouldBe 0
            world.callsTo("own.example.com") shouldBe 0
        }

        test("switched off: nothing is fetched, nothing is claimed, and the housekeeping removes the foreign events") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"))
            world.serve(host = "b.beispiel.de")
            runnerFor(world = world).run()
            foreignEvents().size shouldBe 1
            transaction { PartnerDiscoveryStore.setRecursionSettings(enabled = false, depth = 2) }
            val before = world.totalCalls()
            runnerFor(world = world, now = { RECURSION_NOW.plusHoursKt(5) }).run().shouldBeNull()
            world.totalCalls() shouldBe before
            // visible at once: the read filter says no even before the housekeeping ran
            transaction {
                PartnerEventDisplay
                    .loadVisible(nowUtc = RECURSION_NOW, orgZone = kotlinx.datetime.TimeZone.UTC)
                    .filter { it.hops > 1 }
            }.shouldBeEmpty()
            transaction { PartnerDiscoveryStore.housekeeping(now = RECURSION_NOW, settings = PartnerDiscoveryStore.recursionSettings()) }
            foreignEvents().shouldBeEmpty()
        }

        test("a shorter chain wins: C first at hop 3, then at hop 2 through a new direct partner D; row and events follow") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"))
            world.serve(host = "b.beispiel.de", partners = listOf("c.beispiel.de"))
            world.serve(host = "c.beispiel.de")
            runnerFor(world = world).run()
            rowOf("c.beispiel.de")[PartnerDiscoveredServerTable.hops].toInt() shouldBe 3
            val d = addDirectPartner(host = "d.beispiel.de", partners = listOf("c.beispiel.de"), eventIds = listOf("dd"))
            runnerFor(world = world, now = { RECURSION_NOW.plusHoursKt(3) }).run()
            val c = rowOf("c.beispiel.de")
            c[PartnerDiscoveredServerTable.hops].toInt() shouldBe 2
            c[PartnerDiscoveredServerTable.rootPartnerServerId] shouldBe d
            c[PartnerDiscoveredServerTable.viaHosts] shouldBe "d.beispiel.de"
            val event = foreignEvents().single { it[PartnerEventTable.originHost] == "c.beispiel.de" }
            event[PartnerEventTable.hops].toInt() shouldBe 2
            event[PartnerEventTable.partnerServerId] shouldBe d
            event[PartnerEventTable.viaHosts] shouldBe "d.beispiel.de"
        }

        test("100 events per server and 2000 in total are never exceeded") {
            val world = FakeWorld()
            val roots = (1..3).map { r -> "r$r.beispiel.de" to (1..15).map { "s$r-%02d.beispiel.de".format(it) } }
            roots.forEach { (host, children) ->
                addDirectPartner(host = host, partners = children)
                children.forEach { world.serve(host = it, eventIds = (1..150).map { n -> "e$n" }) }
            }
            runnerFor(world = world).run().shouldNotBeNull()
            discovered().size shouldBe 40
            discovered().forEach { (it[PartnerDiscoveredServerTable.eventCount] <= 100) shouldBe true }
            (foreignEvents().size <= 2000) shouldBe true
            foreignEvents().size shouldBe 2000
        }

        test("a block-listed transit server is never contacted and its children stay unreachable; a later block empties the sub-tree") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"))
            world.serve(host = "b.beispiel.de", partners = listOf("c.beispiel.de"))
            world.serve(host = "c.beispiel.de")
            transaction { PartnerServerStore.addBlock(pattern = "b.beispiel.de", now = RECURSION_NOW) }
            val summary = runnerFor(world = world).run().shouldNotBeNull()
            world.totalCalls() shouldBe 0
            summary.skippedBlocked shouldBe 1
            discovered().shouldBeEmpty()
            // unblock, run, then block again: the whole sub-tree of B disappears at once
            transaction { PartnerServerStore.listBlocklist().forEach { PartnerServerStore.removeBlock(it.id) } }
            runnerFor(world = world, now = { RECURSION_NOW.plusHoursKt(3) }).run()
            foreignEvents().size shouldBe 2
            transaction { PartnerServerStore.addBlock(pattern = "b.beispiel.de", now = RECURSION_NOW) }
            foreignEvents().shouldBeEmpty()
            discoveredHosts() shouldBe listOf("c.beispiel.de")
        }

        test("blocking only the leaf removes only the leaf, the pass-through server keeps its events") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"))
            world.serve(host = "b.beispiel.de", partners = listOf("c.beispiel.de"))
            world.serve(host = "c.beispiel.de")
            runnerFor(world = world).run()
            transaction { PartnerServerStore.addBlock(pattern = "c.beispiel.de", now = RECURSION_NOW) }
            foreignEvents().single()[PartnerEventTable.originHost] shouldBe "b.beispiel.de"
            discoveredHosts() shouldBe listOf("b.beispiel.de")
        }

        test("a partner that hides its list: its own events are stored, its children are not fetched") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"))
            world.serve(host = "b.beispiel.de", partners = listOf("c.beispiel.de"), hidden = true)
            world.serve(host = "c.beispiel.de")
            val summary = runnerFor(world = world).run().shouldNotBeNull()
            foreignEvents().single()[PartnerEventTable.originHost] shouldBe "b.beispiel.de"
            world.callsTo("c.beispiel.de") shouldBe 0
            summary.skippedHiddenList shouldBe 1
        }

        test("a server that claims the id of a direct partner is a conflict: no events, code stored, not traversed") {
            val world = FakeWorld()
            val aId = Uuid.random()
            addDirectPartner(host = "a.beispiel.de", serverId = aId, partners = listOf("b.beispiel.de"))
            world.serve(host = "b.beispiel.de", serverId = aId, partners = listOf("c.beispiel.de"))
            world.serve(host = "c.beispiel.de")
            val summary = runnerFor(world = world).run().shouldNotBeNull()
            summary.serverIdConflicts shouldBe 1
            foreignEvents().shouldBeEmpty()
            rowOf("b.beispiel.de")[PartnerDiscoveredServerTable.lastError] shouldBe PartnerFetchErrorCode.SERVER_ID_CONFLICT.name
            world.callsTo("c.beispiel.de") shouldBe 0
        }

        test("two discovered servers claiming the same id: the second one is a conflict") {
            val world = FakeWorld()
            val shared = Uuid.random()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de", "c.beispiel.de"))
            world.serve(host = "b.beispiel.de", serverId = shared)
            world.serve(host = "c.beispiel.de", serverId = shared)
            val summary = runnerFor(world = world).run().shouldNotBeNull()
            summary.serverIdConflicts shouldBe 1
            summary.reachedByHop[0] shouldBe 1
            foreignEvents().size shouldBe 1
        }

        test("an already bound server id stays with its host: a later claimant becomes the conflict, the owner keeps events and sub-tree") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("y.beispiel.de"))
            val sId = world.serve(host = "y.beispiel.de", partners = listOf("z.beispiel.de"))
            world.serve(host = "z.beispiel.de")
            runnerFor(world = world).run().shouldNotBeNull()
            foreignEvents().map { it[PartnerEventTable.originHost] }.toSet() shouldBe setOf("y.beispiel.de", "z.beispiel.de")
            // an attacker, reachable at the same level through another direct partner, announces Y's id; Y is due again
            addDirectPartner(host = "d.beispiel.de", partners = listOf("c.beispiel.de"))
            world.serve(host = "c.beispiel.de", serverId = sId)
            transaction {
                PartnerDiscoveredServerTable.update({ PartnerDiscoveredServerTable.host eq "y.beispiel.de" }) {
                    it[nextFetchAt] = RECURSION_NOW.minusHoursKt(1)
                }
            }
            val summary = runnerFor(world = world).run().shouldNotBeNull()
            summary.serverIdConflicts shouldBe 1
            rowOf("c.beispiel.de")[PartnerDiscoveredServerTable.lastError] shouldBe PartnerFetchErrorCode.SERVER_ID_CONFLICT.name
            val y = rowOf("y.beispiel.de")
            y[PartnerDiscoveredServerTable.lastStatus] shouldBe PartnerFetchStatus.OK
            y[PartnerDiscoveredServerTable.remoteServerId] shouldBe sId
            foreignEvents().map { it[PartnerEventTable.originHost] }.toSet() shouldBe setOf("y.beispiel.de", "z.beispiel.de")
        }

        test("adding a direct partner with the host and id of a discovered server: the discovered row goes, no unique violation") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"))
            val bId = world.serve(host = "b.beispiel.de")
            runnerFor(world = world).run()
            foreignEvents().size shouldBe 1
            addDirectPartner(host = "b.beispiel.de", serverId = bId, eventIds = listOf("e1"))
            discovered().shouldBeEmpty()
            transaction { PartnerEventTable.selectAll().count() } shouldBe 2L
        }

        test("an inactive direct partner is never shown through the back door, and its own sub-tree is not followed") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("c.beispiel.de"))
            val paused = addDirectPartner(host = "b.beispiel.de", partners = listOf("a.beispiel.de", "c.beispiel.de"))
            world.serve(host = "a.beispiel.de")
            world.serve(host = "c.beispiel.de")
            transaction { PartnerServerStore.setActive(id = paused, active = false, now = RECURSION_NOW) }
            runnerFor(world = world).run()
            world.callsTo("a.beispiel.de") shouldBe 0
            world.callsTo("b.beispiel.de") shouldBe 0
            discoveredHosts() shouldBe listOf("c.beispiel.de")
            rowOf("c.beispiel.de")[PartnerDiscoveredServerTable.viaHosts] shouldBe "a.beispiel.de"
        }

        test("back-off: a server not yet due is not fetched again but its stored list is still walked while it is at most 6 hours old") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"))
            world.serve(host = "b.beispiel.de", partners = listOf("c.beispiel.de"))
            world.serve(host = "c.beispiel.de")
            runnerFor(world = world).run()
            val callsB = world.callsTo("b.beispiel.de")
            val callsC = world.callsTo("c.beispiel.de")
            runnerFor(world = world, now = { RECURSION_NOW.plusHoursKt(0).let { LocalDateTime(2026, 11, 5, 10, 30, 0) } }).run()
            // nextFetchAt is in the future for both -> no new requests, both still reached
            world.callsTo("b.beispiel.de") shouldBe callsB
            world.callsTo("c.beispiel.de") shouldBe callsC
            // B's list is stale (more than 6 h old) while B is still in back-off: not fetched, not walked, C not re-attached
            transaction {
                PartnerDiscoveredServerTable.update({ PartnerDiscoveredServerTable.host eq "b.beispiel.de" }) {
                    it[lastFetchAt] = RECURSION_NOW.minusHoursKt(8)
                    it[nextFetchAt] = RECURSION_NOW.plusHoursKt(20)
                }
                PartnerDiscoveredServerTable.update({ PartnerDiscoveredServerTable.host eq "c.beispiel.de" }) {
                    it[lastReachedAt] = RECURSION_NOW.minusHoursKt(2)
                }
            }
            runnerFor(world = world, now = { RECURSION_NOW.plusHoursKt(1) }).run()
            world.callsTo("b.beispiel.de") shouldBe callsB
            rowOf("c.beispiel.de")[PartnerDiscoveredServerTable.lastReachedAt] shouldBe RECURSION_NOW.minusHoursKt(2)
        }

        test("a failing server backs off and keeps the time of its last success") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"))
            world.serve(host = "b.beispiel.de")
            runnerFor(world = world).run()
            val lastOk = rowOf("b.beispiel.de")[PartnerDiscoveredServerTable.lastFetchAt]
            world.feeds["b.beispiel.de"] = "broken".toByteArray()
            val summary = runnerFor(world = world, now = { RECURSION_NOW.plusHoursKt(3) }).run().shouldNotBeNull()
            summary.errors shouldBe 1
            val row = rowOf("b.beispiel.de")
            row[PartnerDiscoveredServerTable.consecutiveFailures] shouldBe 1
            row[PartnerDiscoveredServerTable.lastError] shouldBe "INVALID_FEED"
            row[PartnerDiscoveredServerTable.lastFetchAt] shouldBe lastOk
            row[PartnerDiscoveredServerTable.nextFetchAt] shouldBe RECURSION_NOW.plusHoursKt(4)
        }

        test("the time budget stops a run; the rest is left for the next one") {
            val world = FakeWorld()
            val time = TestTimeSource()
            world.onRequest = { time += 3.minutes }
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"))
            world.serve(host = "b.beispiel.de", partners = listOf("c.beispiel.de"))
            world.serve(host = "c.beispiel.de")
            val summary = runnerFor(world = world, monotonic = time, budget = 5.minutes).run().shouldNotBeNull()
            // B took 3 minutes, so the budget (5) is not yet used up when B is fetched ... and the second fetch finds it used up
            summary.aborted shouldBe false
            world.onRequest = { time += 6.minutes }
            val second =
                runnerFor(
                    world = world,
                    now = { RECURSION_NOW.plusHoursKt(3) },
                    monotonic = time,
                    budget = 5.minutes,
                ).run().shouldNotBeNull()
            second.aborted shouldBe true
            world.callsTo("c.beispiel.de") shouldBe 1
        }

        test("a rotating attacker cannot grow the table beyond 60 rows per root, 200 in total") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = emptyList())
            repeat(10) { run ->
                val hosts = (1..15).map { "r$run-h%02d.beispiel.de".format(it) }
                transaction {
                    PartnerServerTable.update({ PartnerServerTable.host eq "a.beispiel.de" }) {
                        it[remotePartnersJson] = partnersJson(hosts)
                    }
                }
                hosts.forEach { world.serve(host = it) }
                runnerFor(world = world, now = { RECURSION_NOW.plusHoursKt(25L * (run + 1)) }).run()
                (discovered().size <= PartnerRecursionLimits.MAX_DISCOVERED_ROWS_PER_ROOT) shouldBe true
            }
            discovered().size shouldBe PartnerRecursionLimits.MAX_DISCOVERED_ROWS_PER_ROOT
        }

        test("without a stale row to evict, a new host is refused at the per-root cap") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = emptyList())
            var lastSummary = runnerFor(world = world).run()
            repeat(5) { run ->
                val hosts = (1..15).map { "q$run-h%02d.beispiel.de".format(it) }
                transaction {
                    PartnerServerTable.update({ PartnerServerTable.host eq "a.beispiel.de" }) {
                        it[remotePartnersJson] =
                            partnersJson(hosts)
                    }
                }
                hosts.forEach { world.serve(host = it) }
                lastSummary = runnerFor(world = world, now = { RECURSION_NOW.plusHoursKt(run.toLong()) }).run()
            }
            discovered().size shouldBe PartnerRecursionLimits.MAX_DISCOVERED_ROWS_PER_ROOT
            (lastSummary.shouldNotBeNull().skippedCap > 0) shouldBe true
        }

        test("housekeeping: unreached rows go after 7 days, deeper rows go when the depth is lowered, expired events are counted off") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"))
            world.serve(host = "b.beispiel.de", partners = listOf("c.beispiel.de"))
            world.serve(host = "c.beispiel.de")
            runnerFor(world = world).run()
            transaction { PartnerDiscoveryStore.setRecursionSettings(enabled = true, depth = 1) }
            transaction { PartnerDiscoveryStore.housekeeping(now = RECURSION_NOW, settings = PartnerDiscoveryStore.recursionSettings()) }
            discoveredHosts() shouldBe listOf("b.beispiel.de")
            foreignEvents().size shouldBe 1
            // events expire after 6 h
            transaction {
                PartnerDiscoveryStore.housekeeping(
                    now = RECURSION_NOW.plusHoursKt(7),
                    settings = PartnerDiscoveryStore.recursionSettings(),
                )
            }
            foreignEvents().shouldBeEmpty()
            rowOf("b.beispiel.de")[PartnerDiscoveredServerTable.eventCount] shouldBe 0
            rowOf("b.beispiel.de")[PartnerDiscoveredServerTable.etag].shouldBeNull()
            transaction {
                PartnerDiscoveryStore.housekeeping(
                    now = RECURSION_NOW.plusHoursKt(24 * 8),
                    settings = PartnerDiscoveryStore.recursionSettings(),
                )
            }
            discovered().shouldBeEmpty()
        }

        test("removing the direct partner removes its whole sub-tree and every event") {
            val world = FakeWorld()
            val a = addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"))
            world.serve(host = "b.beispiel.de", partners = listOf("c.beispiel.de"))
            world.serve(host = "c.beispiel.de")
            runnerFor(world = world).run()
            transaction { PartnerServerStore.remove(a) }
            discovered().shouldBeEmpty()
            transaction { PartnerEventTable.selectAll().count() } shouldBe 0L
        }

        test("the summary is stored as numbers only") {
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"))
            world.serve(host = "b.beispiel.de", name = "Geheimer Fremdname")
            runnerFor(world = world).run()
            val settings = transaction { PartnerDiscoveryStore.recursionSettings() }
            settings.lastRunAt shouldBe RECURSION_NOW
            val json = settings.summaryJson.shouldNotBeNull()
            (json.length < 2000) shouldBe true
            (json.contains("beispiel.de") || json.contains("Geheimer")) shouldBe false
        }
    })
