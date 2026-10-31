package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import kotlin.uuid.Uuid

private fun root(
    host: String,
    partners: List<String>,
) = RecursionRoot(partnerId = Uuid.random(), host = host, name = host, remoteServerId = null, partners = partners)

private fun node(r: RecursionRoot) = RecursionNode(host = r.host, root = r, chain = listOf(r.host), partners = r.partners)

private fun visited(
    own: String? = "own.example.com",
    direct: List<String> = emptyList(),
) = RecursionVisited(ownHost = own, ownServerId = Uuid.random(), directHosts = direct, directIds = emptyList())

private fun level(
    parents: List<RecursionNode>,
    visited: RecursionVisited = visited(),
    patterns: List<String> = emptyList(),
    perRoot: MutableMap<Uuid, Int> = HashMap(),
    runUsed: Int = 0,
    lastFetch: Map<String, LocalDateTime?> = emptyMap(),
    counters: RecursionCounters = RecursionCounters(),
) = PartnerRecursionFrontier.nextLevel(
    parents = parents,
    visited = visited,
    blockPatterns = patterns,
    perRootUsed = perRoot,
    runUsed = runUsed,
    lastFetchByHost = lastFetch,
    counters = counters,
    hops = 2,
)

class PartnerRecursionFrontierTest :
    FunSpec({
        test("a host reached through two roots belongs to the root that sorts first; the second is counted as visited") {
            val a = root(host = "a.beispiel.de", partners = listOf("x.beispiel.de"))
            val b = root(host = "b.beispiel.de", partners = listOf("x.beispiel.de"))
            val counters = RecursionCounters()
            val out = level(parents = listOf(node(b), node(a)), counters = counters)
            out.map { it.host to it.root.host } shouldContainExactly listOf("x.beispiel.de" to "a.beispiel.de")
            counters.skippedVisited.get() shouldBe 1
        }

        test("the via chain of a candidate is the chain of its parent") {
            val a = root(host = "a.beispiel.de", partners = listOf("x.beispiel.de"))
            level(parents = listOf(node(a))).single().via shouldBe listOf("a.beispiel.de")
        }

        test("at most 20 hosts per server are followed (the rest counts as cap), and then the 15 per root apply") {
            val a = root(host = "a.beispiel.de", partners = (1..35).map { "h%02d.beispiel.de".format(it) })
            val counters = RecursionCounters()
            val out = level(parents = listOf(node(a)), counters = counters)
            out.size shouldBe PartnerRecursionLimits.MAX_DISCOVERED_PER_ROOT
            // 15 hosts beyond the fan-out of 20, plus 5 of the 20 refused by the per-root cap
            counters.skippedCap.get() shouldBe 20
            out.map { it.host }.all { it <= "h20.beispiel.de" } shouldBe true
        }

        test("15 per root and 40 per run") {
            val roots = (1..4).map { r -> root(host = "r$r.beispiel.de", partners = (1..20).map { "h$r-%02d.beispiel.de".format(it) }) }
            val perRoot = HashMap<Uuid, Int>()
            val counters = RecursionCounters()
            val out = level(parents = roots.map(::node), perRoot = perRoot, counters = counters)
            out.groupBy { it.root.host }.values.forEach { (it.size <= 15) shouldBe true }
            out.size shouldBe 40
            roots.forEach { (perRoot[it.partnerId] ?: 0) shouldBe out.count { c -> c.root == it } }
            // a second level in the same run starts from what is already used
            level(parents = roots.map(::node), runUsed = 40, visited = visited()).size shouldBe 0
        }

        test("the least recently fetched hosts come first (NULLS FIRST), so the cap rotates over runs") {
            val hosts = (1..20).map { "h%02d.beispiel.de".format(it) }
            val a = root(host = "a.beispiel.de", partners = hosts)
            val lastFetch =
                hosts.associateWith { h -> if (h <= "h05.beispiel.de") LocalDateTime(2026, 11, 1, 10, 0, 0) else null }
            val out = level(parents = listOf(node(a)), lastFetch = lastFetch, perRoot = HashMap())
            out.size shouldBe 15
            // the five already-fetched hosts are the ones that are left out
            out.map { it.host }.none { it <= "h05.beispiel.de" } shouldBe true
        }

        test("a blocked host (also by wildcard) is neither visited nor admitted") {
            val a = root(host = "a.beispiel.de", partners = listOf("bad.spam.de", "ok.beispiel.de"))
            val v = visited()
            val counters = RecursionCounters()
            val out = level(parents = listOf(node(a)), visited = v, patterns = listOf("*.spam.de"), counters = counters)
            out.map { it.host } shouldBe listOf("ok.beispiel.de")
            counters.skippedBlocked.get() shouldBe 1
            v.tryVisitHost("bad.spam.de") shouldBe true
        }

        test("our own host and every direct partner host are pre-visited") {
            val a = root(host = "a.beispiel.de", partners = listOf("own.example.com", "b.beispiel.de", "c.beispiel.de"))
            val counters = RecursionCounters()
            val out =
                level(parents = listOf(node(a)), visited = visited(direct = listOf("b.beispiel.de", "a.beispiel.de")), counters = counters)
            out.map { it.host } shouldBe listOf("c.beispiel.de")
            counters.skippedVisited.get() shouldBe 2
        }

        test("a host refused by a cap stays visited and cannot slip in through a longer path") {
            val a = root(host = "a.beispiel.de", partners = (1..16).map { "h%02d.beispiel.de".format(it) })
            val v = visited()
            val out = level(parents = listOf(node(a)), visited = v)
            out.size shouldBe 15
            val refused = (1..16).map { "h%02d.beispiel.de".format(it) }.first { h -> out.none { it.host == h } }
            v.tryVisitHost(refused) shouldBe false
        }

        test("5000 hosts in one list cost at most 20 candidates") {
            val a = root(host = "a.beispiel.de", partners = (1..5000).map { "h$it.beispiel.de" })
            level(parents = listOf(node(a))).size shouldBe 15
        }

        test("server ids are visited once") {
            val own = Uuid.random()
            val direct = Uuid.random()
            val v = RecursionVisited(ownHost = null, ownServerId = own, directHosts = emptyList(), directIds = listOf(direct))
            v.tryVisitId(id = own, host = "x.example") shouldBe false
            v.tryVisitId(id = direct, host = "x.example") shouldBe false
            val fresh = Uuid.random()
            v.tryVisitId(id = fresh, host = "x.example") shouldBe true
            v.tryVisitId(id = fresh, host = "y.example") shouldBe false
            v.tryVisitId(id = fresh, host = "x.example") shouldBe true
        }
    })
