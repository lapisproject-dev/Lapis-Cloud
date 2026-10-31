package network.lapis.cloud.server.partner

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.PartnerRecursionSummaryDto
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.245 "Partnerserver P2" -- the bounded breadth-first walk over the partners of the partners. Called ONLY by [PartnerFeedPoller.tick]
 * (pinned by `PartnerServerTripwireTest`): never from a request path, never from `probeAndSave`.
 *
 * One run = one snapshot transaction, then per level: the pure [PartnerRecursionFrontier.nextLevel] picks the hosts, the fetches of the level
 * run in parallel (at most [PartnerRecursionLimits.MAX_PARALLEL]) OUTSIDE every transaction through the poller's own [PartnerFeedFetcher]
 * (SSRF guard, IP pinning, https:443 only, no redirects, 512 KiB, 10 s), and each result is written in its own short transaction by
 * [PartnerDiscoveryStore]. A run takes at most [PartnerRecursionLimits.RUN_TIME_BUDGET]; whatever is left follows in the next run. Logs carry
 * numbers only -- never a host above DEBUG, never a body.
 */
internal class PartnerRecursionRunner(
    private val fetcher: PartnerFeedFetcher,
    private val monotonic: TimeSource = TimeSource.Monotonic,
    private val clock: () -> LocalDateTime = { ServerClock.now() },
    private val publicBaseUrl: String = FederationConfig.publicBaseUrl,
    private val timeBudget: Duration = PartnerRecursionLimits.RUN_TIME_BUDGET,
) {
    private val summaryJson = Json { encodeDefaults = true }

    /** Runs when the switch is on and the last run is at least an hour (plus a deterministic offset) old. */
    suspend fun runIfDue() {
        val now = clock()
        val due =
            transaction {
                val settings = PartnerDiscoveryStore.recursionSettings()
                settings.enabled &&
                    PartnerRecursionFrontier.isRunDue(
                        lastRunAt = settings.lastRunAt,
                        now = now,
                        salt = PartnerServerStore.ensureServerId().toString(),
                    )
            }
        if (due) run()
    }

    /** One run. `null` when the recursion is switched off. */
    @Suppress("LongMethod")
    internal suspend fun run(): PartnerRecursionSummaryDto? {
        val mark = monotonic.markNow()
        val now = clock()
        val ownHost = PartnerHost.ownHostOf(publicBaseUrl)
        val snapshot =
            transaction {
                val loaded = PartnerDiscoveryStore.loadRunSnapshot(now = now, ownHost = ownHost)
                if (loaded.settings.enabled) PartnerDiscoveryStore.claimRun(now)
                loaded.takeIf { it.settings.enabled }
            } ?: return null
        val depth = snapshot.settings.depth
        val visited =
            RecursionVisited(
                ownHost = snapshot.ownHost,
                ownServerId = snapshot.ownServerId,
                directHosts = snapshot.directHosts,
                directIds = snapshot.directIds,
                boundIds =
                    snapshot.discovered.values
                        .mapNotNull { row -> row.remoteServerId?.let { it to row.host } }
                        .toMap(),
            )
        val counters = RecursionCounters()
        val perRootUsed = HashMap<Uuid, Int>()
        val lastFetch = snapshot.discovered.mapValues { it.value.lastFetchAt }
        val permits = Semaphore(PartnerRecursionLimits.MAX_PARALLEL)
        val instant = ServerClock.systemToInstant(now)
        var runUsed = 0
        var nodes = snapshot.roots.map { RecursionNode(host = it.host, root = it, chain = listOf(it.host), partners = it.partners) }
        for (hops in 2..(1 + depth)) {
            if (nodes.isEmpty() || counters.aborted) break
            val candidates =
                PartnerRecursionFrontier.nextLevel(
                    parents = nodes,
                    visited = visited,
                    blockPatterns = snapshot.blockPatterns,
                    perRootUsed = perRootUsed,
                    runUsed = runUsed,
                    lastFetchByHost = lastFetch,
                    counters = counters,
                    hops = hops,
                )
            runUsed += candidates.size
            nodes =
                coroutineScope {
                    candidates
                        .map { c ->
                            async(Dispatchers.IO) {
                                permits.withPermit {
                                    processOne(
                                        c = c,
                                        snapshot = snapshot,
                                        instant = instant,
                                        now = now,
                                        visited = visited,
                                        counters = counters,
                                        elapsed = { mark.elapsedNow() },
                                    )
                                }
                            }
                        }.awaitAll()
                        .filterNotNull()
                }
        }
        val summary = counters.toDto(durationMs = mark.elapsedNow().inWholeMilliseconds, depth = depth)
        transaction { PartnerDiscoveryStore.storeSummary(summaryJson.encodeToString(PartnerRecursionSummaryDto.serializer(), summary)) }
        logger.info {
            "PartnerRecursionRunner: run done reached=${summary.reachedByHop} events=${summary.eventsByHop} " +
                "blocked=${summary.skippedBlocked} hidden=${summary.skippedHiddenList} visited=${summary.skippedVisited} " +
                "cap=${summary.skippedCap} conflicts=${summary.serverIdConflicts} errors=${summary.errors} " +
                "aborted=${summary.aborted} ms=${summary.durationMs}"
        }
        return summary
    }

    /** Fetches (or reuses the cache of) one candidate and writes the result. Returns the node whose partner list is walked next, or `null`. */
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    private suspend fun processOne(
        c: RecursionCandidate,
        snapshot: RecursionSnapshot,
        instant: Instant,
        now: LocalDateTime,
        visited: RecursionVisited,
        counters: RecursionCounters,
        elapsed: () -> Duration,
    ): RecursionNode? {
        try {
            val row = snapshot.discovered[c.host]
            if (row != null && row.nextFetchAt > now) {
                // Back-off (or simply not due yet): no request. The stored list is usable while it is at most CACHE_TTL_HOURS old.
                val fresh = row.lastFetchAt != null && row.lastFetchAt >= now.minusHoursKt(PartnerServerStore.CACHE_TTL_HOURS)
                if (!fresh) return null
                row.remoteServerId?.let { visited.tryVisitId(id = it, host = c.host) }
                val result = transaction { PartnerDiscoveryStore.markReachedFromCache(c = c, now = now) }
                return afterWrite(result = result, c = c, counters = counters, partnersHidden = row.partnersHidden, partners = row.partners)
            }
            if (elapsed() > timeBudget) {
                counters.aborted = true
                return null
            }
            val outcome =
                fetcher.fetch(
                    host = c.host,
                    etag = row?.etag,
                    ownServerId = snapshot.ownServerId,
                    knownRemoteId = row?.remoteServerId,
                    now = instant,
                )
            return when (outcome) {
                is PartnerFetchOutcome.Fetched -> {
                    val parsed = outcome.parsed
                    val result =
                        if (visited.tryVisitId(id = parsed.remoteServerId, host = c.host)) {
                            transaction {
                                PartnerDiscoveryStore.applyDiscoveredSuccess(
                                    c = c,
                                    parsed = parsed,
                                    etag = outcome.etag,
                                    now = now,
                                )
                            }
                        } else {
                            transaction { PartnerDiscoveryStore.applyDiscoveredConflict(c = c, now = now) }
                        }
                    afterWrite(
                        result = result,
                        c = c,
                        counters = counters,
                        partnersHidden = parsed.partnersHidden,
                        partners = PartnerFeedParser.partnerHostsOf(parsed.partnersJson),
                    )
                }
                is PartnerFetchOutcome.NotModified -> {
                    if (row == null) return null
                    row.remoteServerId?.let { visited.tryVisitId(id = it, host = c.host) }
                    val result = transaction { PartnerDiscoveryStore.applyDiscoveredNotModified(c = c, now = now) }
                    afterWrite(result = result, c = c, counters = counters, partnersHidden = row.partnersHidden, partners = row.partners)
                }
                is PartnerFetchOutcome.Failed -> {
                    counters.errors.incrementAndGet()
                    transaction { PartnerDiscoveryStore.applyDiscoveredFailure(c = c, error = outcome.error, now = now) }
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            counters.errors.incrementAndGet()
            logger.warn { "PartnerRecursionRunner: a server failed unexpectedly (${e.javaClass.simpleName})" }
            return null
        }
    }

    private fun afterWrite(
        result: DiscoveredWriteResult,
        c: RecursionCandidate,
        counters: RecursionCounters,
        partnersHidden: Boolean,
        partners: List<String>,
    ): RecursionNode? =
        when (result) {
            is DiscoveredWriteResult.Stored -> {
                counters.reached(c.hops)
                counters.events(hops = c.hops, count = result.eventCount)
                if (partnersHidden) {
                    counters.skippedHiddenList.incrementAndGet()
                    null
                } else {
                    RecursionNode(host = c.host, root = c.root, chain = c.via + c.host, partners = partners)
                }
            }
            DiscoveredWriteResult.Conflict -> {
                counters.serverIdConflicts.incrementAndGet()
                null
            }
            DiscoveredWriteResult.Blocked -> {
                counters.skippedBlocked.incrementAndGet()
                null
            }
            DiscoveredWriteResult.CapReached -> {
                counters.skippedCap.incrementAndGet()
                null
            }
            DiscoveredWriteResult.Gone -> null
        }
}
