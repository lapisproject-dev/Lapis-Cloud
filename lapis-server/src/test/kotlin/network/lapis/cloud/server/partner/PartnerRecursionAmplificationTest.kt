package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** V1.9.245 -- a malicious direct partner lists thousands of hosts, and each of them thousands more: the cost stays bounded. */
class PartnerRecursionAmplificationTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { wipePartnerTables() }
        afterSpec { wipePartnerTables() }

        test("5000 hosts per list, two levels deep: at most 15 fetches in the sub-tree, 40 per run, storage bounded") {
            val world = FakeWorld()
            val level1 = (1..5000).map { "h$it.beispiel.de" }
            // the stored partner list is capped at 100 hosts by the parser; the feed of a discovered server is capped the same way
            addDirectPartner(host = "evil.beispiel.de", partners = level1.take(PartnerFeedParser.MAX_REMOTE_PARTNERS))
            level1.take(PartnerFeedParser.MAX_REMOTE_PARTNERS).forEach { host ->
                world.serve(
                    host = host,
                    partners = (1..5000).map { "k$it-$host" }.take(PartnerFeedParser.MAX_REMOTE_PARTNERS),
                    eventIds = (1..150).map { "e$it" },
                )
            }
            val summary = runnerFor(world = world).run().shouldNotBeNull()
            // 15 per root across all levels: the second level shares the budget of the same root
            (world.totalCalls() <= PartnerRecursionLimits.MAX_DISCOVERED_PER_ROOT) shouldBe true
            (summary.reachedByHop.sum() <= PartnerRecursionLimits.MAX_DISCOVERED_PER_ROOT) shouldBe true
            (world.totalCalls() <= PartnerRecursionLimits.MAX_DISCOVERED_PER_RUN) shouldBe true
            transaction { PartnerDiscoveredServerTable.selectAll().count() } shouldBe world.totalCalls().toLong()
            (transaction { PartnerEventTable.selectAll().count() } <= 1L + 15 * 100) shouldBe true
        }

        test("the same list repeated in every later run costs the same, never more") {
            val world = FakeWorld()
            val hosts = (1..PartnerFeedParser.MAX_REMOTE_PARTNERS).map { "h$it.beispiel.de" }
            addDirectPartner(host = "evil.beispiel.de", partners = hosts)
            hosts.forEach { world.serve(host = it) }
            repeat(4) { run ->
                val before = world.totalCalls()
                runnerFor(world = world, now = { RECURSION_NOW.plusHoursKt(2L * (run + 1)) }).run()
                (world.totalCalls() - before <= PartnerRecursionLimits.MAX_DISCOVERED_PER_ROOT) shouldBe true
            }
            (transaction { PartnerDiscoveredServerTable.selectAll().count() } <= 100) shouldBe true
        }
    })
