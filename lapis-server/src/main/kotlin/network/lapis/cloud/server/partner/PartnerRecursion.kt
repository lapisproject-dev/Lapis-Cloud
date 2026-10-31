package network.lapis.cloud.server.partner

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import network.lapis.cloud.shared.domain.PartnerRecursionSummaryDto
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.245 "Partnerserver P2" -- every bound of the recursion in ONE place (docs/architecture/partner-server-feed.adoc, "Bounds"). The numbers
 * cap what a malicious partner can make this server do: how many foreign hosts it contacts per run, per root, per list, how much it stores.
 */
internal object PartnerRecursionLimits {
    const val MAX_DISCOVERED_PER_RUN = 40
    const val MAX_DISCOVERED_PER_ROOT = 15
    const val MAX_FANOUT_PER_SERVER = 20
    const val MAX_EVENTS_PER_DISCOVERED = 100
    const val MAX_DISCOVERED_EVENTS_TOTAL = 2000
    val RUN_TIME_BUDGET = 5.minutes
    val RUN_INTERVAL = 60.minutes
    const val RUN_JITTER_SECONDS = 300L
    const val STALE_DISCOVERED_DAYS = 7L
    const val MAX_PARALLEL = 4

    /** Storage bound of `partner_discovered_server` rows (an attacker rotating hosts cannot grow the table without limit). */
    const val MAX_DISCOVERED_ROWS = 200
    const val MAX_DISCOVERED_ROWS_PER_ROOT = 60

    /** A row that was reached less than this long ago is not evicted to make room. */
    const val EVICTION_MIN_AGE_HOURS = 24L

    const val MIN_DEPTH = 1
    const val MAX_DEPTH = 3

    /** `hops` of the direct partner is 1; the deepest foreign server is at `1 + depth`. */
    const val MAX_HOPS = 1 + MAX_DEPTH
}

/** A direct (hop 1) partner as the root of a sub-tree. [partners] are the (re-validated) hosts of its stored partner list. */
internal data class RecursionRoot(
    val partnerId: Uuid,
    val host: String,
    val name: String,
    val remoteServerId: Uuid?,
    val partners: List<String>,
)

/**
 * A foreign server admitted for the next level. [via] = the hosts between us and it, nearest first (the root is the first entry), so
 * `via.size == hops - 1`.
 */
internal data class RecursionCandidate(
    val host: String,
    val hops: Int,
    val root: RecursionRoot,
    val via: List<String>,
    val lastFetchAt: LocalDateTime?,
)

/** A node whose partner list is walked to find the next level: the root itself (hop 1) or a fetched candidate. */
internal data class RecursionNode(
    val host: String,
    val root: RecursionRoot,
    /** The chain a child of this node gets as `via`: everything from the root down to and including this node. */
    val chain: List<String>,
    val partners: List<String>,
)

/**
 * The hosts and server ids already taken in ONE run (a server id is bound to exactly one host). Seeded with this server itself and with EVERY direct partner (also the inactive ones: a
 * paused partner must not show up through the back door, F4 of the plan). Thread-safe -- the fetches of a level run in parallel.
 */
internal class RecursionVisited(
    ownHost: String?,
    ownServerId: Uuid,
    directHosts: Collection<String>,
    directIds: Collection<Uuid>,
    /** server id -> host of the discovered rows already stored: an existing binding wins over whoever asks first in this run. */
    boundIds: Map<Uuid, String> = emptyMap(),
) {
    private val hosts = HashSet<String>()

    /** server id -> the host it is bound to. Own and direct-partner ids are bound to [NO_HOST], which no candidate host can equal. */
    private val ids = HashMap<Uuid, String>()

    init {
        if (ownHost != null) hosts += ownHost
        hosts += directHosts
        ids.putAll(boundIds)
        ids[ownServerId] = NO_HOST
        directIds.forEach { ids[it] = NO_HOST }
    }

    @Synchronized
    fun tryVisitHost(host: String): Boolean = hosts.add(host)

    @Synchronized
    fun tryVisitId(
        id: Uuid,
        host: String,
    ): Boolean {
        val owner = ids.putIfAbsent(id, host)
        return owner == null || owner == host
    }

    private companion object {
        const val NO_HOST = ""
    }
}

/** The counters of one run; thread-safe, [toDto] is the summary written to the settings. */
internal class RecursionCounters {
    private val reached = Array(PartnerRecursionLimits.MAX_DEPTH) { AtomicInteger() }
    private val events = Array(PartnerRecursionLimits.MAX_DEPTH) { AtomicInteger() }
    val skippedBlocked = AtomicInteger()
    val skippedHiddenList = AtomicInteger()
    val skippedVisited = AtomicInteger()
    val skippedCap = AtomicInteger()
    val serverIdConflicts = AtomicInteger()
    val errors = AtomicInteger()

    @Volatile var aborted: Boolean = false

    fun reached(hops: Int) {
        reached.getOrNull(hops - 2)?.incrementAndGet()
    }

    fun events(
        hops: Int,
        count: Int,
    ) {
        events.getOrNull(hops - 2)?.addAndGet(count)
    }

    fun toDto(
        durationMs: Long,
        depth: Int,
    ): PartnerRecursionSummaryDto =
        PartnerRecursionSummaryDto(
            reachedByHop = reached.take(depth).map { it.get() },
            eventsByHop = events.take(depth).map { it.get() },
            skippedBlocked = skippedBlocked.get(),
            skippedHiddenList = skippedHiddenList.get(),
            skippedVisited = skippedVisited.get(),
            skippedCap = skippedCap.get(),
            serverIdConflicts = serverIdConflicts.get(),
            errors = errors.get(),
            durationMs = durationMs,
            aborted = aborted,
        )
}

/** The pure part of the breadth-first walk: which hosts of the next level are admitted. No I/O, no clock. */
internal object PartnerRecursionFrontier {
    /**
     * From the nodes of level n the admitted candidates of level n+1, in a FIXED order (see the plan, 2.2):
     *  1. parents sorted by `(root.host, host)`;
     *  2. per parent `partners.distinct().sorted().take(MAX_FANOUT_PER_SERVER)` -- the rest counts as `skippedCap`;
     *  3. per host: blocklist first (no visit, no DNS), then the visited set -- the first root in the sort order wins a host reached twice;
     *  4. only then the caps: candidates sorted `(lastFetchAt NULLS FIRST, root.host, host)`, admitted while the run total and the per-root
     *     total allow it. A host refused by a cap stays in the visited set on purpose (it must not slip in on a longer path in the same run).
     *
     * Strings are compared by code point in Kotlin, never with a database collation.
     */
    fun nextLevel(
        parents: List<RecursionNode>,
        visited: RecursionVisited,
        blockPatterns: List<String>,
        perRootUsed: MutableMap<Uuid, Int>,
        runUsed: Int,
        lastFetchByHost: Map<String, LocalDateTime?>,
        counters: RecursionCounters,
        hops: Int,
    ): List<RecursionCandidate> {
        val pending = ArrayList<RecursionCandidate>()
        for (parent in parents.sortedWith(compareBy({ it.root.host }, { it.host }))) {
            val hosts = parent.partners.distinct().sorted()
            val taken = hosts.take(PartnerRecursionLimits.MAX_FANOUT_PER_SERVER)
            counters.skippedCap.addAndGet(hosts.size - taken.size)
            for (host in taken) {
                if (PartnerBlocklistPattern.isBlocked(host = host, patterns = blockPatterns)) {
                    counters.skippedBlocked.incrementAndGet()
                    continue
                }
                if (!visited.tryVisitHost(host)) {
                    counters.skippedVisited.incrementAndGet()
                    continue
                }
                pending +=
                    RecursionCandidate(
                        host = host,
                        hops = hops,
                        root = parent.root,
                        via = parent.chain,
                        lastFetchAt = lastFetchByHost[host],
                    )
            }
        }
        val ordered =
            pending.sortedWith(
                compareBy<RecursionCandidate, LocalDateTime?>(nullsFirst(naturalOrder())) { it.lastFetchAt }
                    .thenBy { it.root.host }
                    .thenBy { it.host },
            )
        val admitted = ArrayList<RecursionCandidate>()
        for (candidate in ordered) {
            val perRoot = perRootUsed[candidate.root.partnerId] ?: 0
            if (runUsed + admitted.size < PartnerRecursionLimits.MAX_DISCOVERED_PER_RUN &&
                perRoot < PartnerRecursionLimits.MAX_DISCOVERED_PER_ROOT
            ) {
                perRootUsed[candidate.root.partnerId] = perRoot + 1
                admitted += candidate
            } else {
                counters.skippedCap.incrementAndGet()
            }
        }
        return admitted
    }

    /** `true` when no run is stored yet or [now] is at least one interval plus a deterministic offset in `[0, RUN_JITTER_SECONDS)` s after it. */
    fun isRunDue(
        lastRunAt: LocalDateTime?,
        now: LocalDateTime,
        salt: String,
    ): Boolean {
        if (lastRunAt == null) return true
        val digest = MessageDigest.getInstance("SHA-256").digest("recursion|$salt".toByteArray(Charsets.UTF_8))
        val value = digest.take(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) } and Long.MAX_VALUE
        val offset = value % PartnerRecursionLimits.RUN_JITTER_SECONDS
        val due =
            lastRunAt
                .toJavaLocalDateTime()
                .plusSeconds(PartnerRecursionLimits.RUN_INTERVAL.inWholeSeconds + offset)
        return !now.toJavaLocalDateTime().isBefore(due)
    }
}
