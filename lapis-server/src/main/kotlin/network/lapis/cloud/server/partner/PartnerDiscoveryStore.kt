package network.lapis.cloud.server.partner

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import network.lapis.cloud.shared.domain.PartnerFetchStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** The recursion switch and bookkeeping (`organization_settings`). */
internal data class RecursionSettings(
    val enabled: Boolean,
    val depth: Int,
    val lastRunAt: LocalDateTime?,
    val summaryJson: String?,
)

/** A row of `partner_discovered_server` as the recursion run and the admin list see it. */
internal data class DiscoveredRow(
    val id: Uuid,
    val host: String,
    val remoteServerId: Uuid?,
    val remoteName: String?,
    val rootPartnerId: Uuid,
    val hops: Int,
    val via: List<String>,
    val lastReachedAt: LocalDateTime?,
    val lastFetchAt: LocalDateTime?,
    val lastStatus: PartnerFetchStatus,
    val lastError: String?,
    val eventCount: Int,
    val consecutiveFailures: Int,
    val nextFetchAt: LocalDateTime,
    val etag: String?,
    /** The hosts of the stored partner list (re-validated); empty when hidden or none. */
    val partners: List<String>,
    val partnersHidden: Boolean,
)

/** Everything one recursion run reads in its first transaction. */
internal data class RecursionSnapshot(
    val settings: RecursionSettings,
    val ownHost: String?,
    val ownServerId: Uuid,
    val blockPatterns: List<String>,
    val directHosts: List<String>,
    val directIds: List<Uuid>,
    val roots: List<RecursionRoot>,
    val discovered: Map<String, DiscoveredRow>,
)

internal sealed interface DiscoveredWriteResult {
    data class Stored(
        val eventCount: Int,
    ) : DiscoveredWriteResult

    /** The server id is taken by another server (direct partner or another discovered one), or the host is a direct partner. */
    data object Conflict : DiscoveredWriteResult

    /** A block-list entry hit the host or its chain while the request was on the wire. */
    data object Blocked : DiscoveredWriteResult

    /** The root vanished / is inactive, the recursion was switched off or the depth lowered meanwhile. */
    data object Gone : DiscoveredWriteResult

    /** A storage cap ([PartnerRecursionLimits.MAX_DISCOVERED_ROWS]) refused a NEW row. */
    data object CapReached : DiscoveredWriteResult
}

/**
 * V1.9.245 "Partnerserver P2" -- the SECOND AND LAST writer of the partner cache: `partner_discovered_server`, the events of `partner_event` with
 * `hops > 1`, and the `partner_recursion_*` columns of `organization_settings`. Every function needs an open `transaction {}`; none of them
 * touches the network.
 *
 * **Lock order (deadlock-free):** `partner_server` first, then `partner_discovered_server`, then `partner_event`. A write that depends on the
 * block list locks its ROOT partner row first, exactly like [PartnerServerStore.applyFetchSuccess] -- so it cannot write back after an
 * [PartnerServerStore.addBlock] that was committed while its request was on the wire.
 *
 * **The direct partner wins:** a host or server id that belongs to a direct partner is never stored here, and adding a direct partner removes a
 * discovered row with the same host or id ([deleteDiscoveredForHostOrId]).
 */
internal object PartnerDiscoveryStore {
    // ── settings ────────────────────────────────────────────────────────────────

    fun recursionSettings(): RecursionSettings {
        val row =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .single()
        return RecursionSettings(
            enabled = row[OrganizationSettingsTable.partnerRecursionEnabled],
            depth = row[OrganizationSettingsTable.partnerRecursionDepth].toInt(),
            lastRunAt = row[OrganizationSettingsTable.partnerRecursionLastRunAt],
            summaryJson = row[OrganizationSettingsTable.partnerRecursionLastSummary],
        )
    }

    fun setRecursionSettings(
        enabled: Boolean,
        depth: Int,
    ) {
        require(depth in PartnerRecursionLimits.MIN_DEPTH..PartnerRecursionLimits.MAX_DEPTH) { "depth out of range: $depth" }
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[partnerRecursionEnabled] = enabled
            it[partnerRecursionDepth] = depth.toShort()
        }
    }

    /** Stamps the run BEFORE it starts, so a crash cannot turn into one run per minute. */
    fun claimRun(now: LocalDateTime) {
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[partnerRecursionLastRunAt] = now
        }
    }

    fun storeSummary(json: String) {
        require(json.length < SUMMARY_MAX_CHARS) { "summary too long" }
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[partnerRecursionLastSummary] = json
        }
    }

    // ── reads ───────────────────────────────────────────────────────────────────

    private fun ResultRow.toDiscovered() =
        DiscoveredRow(
            id = this[PartnerDiscoveredServerTable.id],
            host = this[PartnerDiscoveredServerTable.host],
            remoteServerId = this[PartnerDiscoveredServerTable.remoteServerId],
            remoteName = this[PartnerDiscoveredServerTable.remoteName],
            rootPartnerId = this[PartnerDiscoveredServerTable.rootPartnerServerId],
            hops = this[PartnerDiscoveredServerTable.hops].toInt(),
            via = PartnerProvenance.decode(this[PartnerDiscoveredServerTable.viaHosts]),
            lastReachedAt = this[PartnerDiscoveredServerTable.lastReachedAt],
            lastFetchAt = this[PartnerDiscoveredServerTable.lastFetchAt],
            lastStatus = this[PartnerDiscoveredServerTable.lastStatus],
            lastError = this[PartnerDiscoveredServerTable.lastError],
            eventCount = this[PartnerDiscoveredServerTable.eventCount],
            consecutiveFailures = this[PartnerDiscoveredServerTable.consecutiveFailures],
            nextFetchAt = this[PartnerDiscoveredServerTable.nextFetchAt],
            etag = this[PartnerDiscoveredServerTable.etag],
            partners =
                if (this[PartnerDiscoveredServerTable.partnersHidden]) {
                    emptyList()
                } else {
                    PartnerFeedParser.partnerHostsOf(this[PartnerDiscoveredServerTable.remotePartnersJson])
                },
            partnersHidden = this[PartnerDiscoveredServerTable.partnersHidden],
        )

    private fun findByHost(host: String): DiscoveredRow? =
        PartnerDiscoveredServerTable
            .selectAll()
            .where { PartnerDiscoveredServerTable.host eq host }
            .singleOrNull()
            ?.toDiscovered()

    private fun idOwnedByOtherDiscovered(
        remoteServerId: Uuid,
        host: String,
    ): Boolean =
        PartnerDiscoveredServerTable
            .selectAll()
            .where { (PartnerDiscoveredServerTable.remoteServerId eq remoteServerId) and (PartnerDiscoveredServerTable.host neq host) }
            .any()

    fun loadRunSnapshot(
        now: LocalDateTime,
        ownHost: String?,
    ): RecursionSnapshot {
        val settings = recursionSettings()
        val ownServerId = PartnerServerStore.ensureServerId()
        val patterns = PartnerServerStore.blockPatterns()
        val partners = PartnerServerStore.listPartners()
        val roots =
            partners
                .filter { it.active && !PartnerBlocklistPattern.isBlocked(host = it.host, patterns = patterns) }
                .map {
                    RecursionRoot(
                        partnerId = it.id,
                        host = it.host,
                        name = it.displayName ?: it.remoteName ?: it.host,
                        remoteServerId = it.remoteServerId,
                        partners = PartnerServerStore.remotePartnerHosts(it.id),
                    )
                }
        val discovered =
            PartnerDiscoveredServerTable
                .selectAll()
                .map { it.toDiscovered() }
                .associateBy { it.host }
        return RecursionSnapshot(
            settings = settings,
            ownHost = ownHost,
            ownServerId = ownServerId,
            blockPatterns = patterns,
            directHosts = partners.map { it.host },
            directIds = partners.mapNotNull { it.remoteServerId },
            roots = roots,
            discovered = discovered,
        )
    }

    /** At most [PartnerRecursionLimits.MAX_DISCOVERED_ROWS] rows, sorted by hop and host IN KOTLIN (no database collation). */
    fun listForAdmin(): List<DiscoveredRow> =
        PartnerDiscoveredServerTable
            .selectAll()
            .limit(PartnerRecursionLimits.MAX_DISCOVERED_ROWS)
            .map { it.toDiscovered() }
            .sortedWith(compareBy({ it.hops }, { it.host }))

    /** Per direct partner: (number of discovered servers, number of cached discovered events). */
    fun countsByRoot(): Map<Uuid, Pair<Int, Int>> =
        PartnerDiscoveredServerTable
            .selectAll()
            .map { it[PartnerDiscoveredServerTable.rootPartnerServerId] to it[PartnerDiscoveredServerTable.eventCount] }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, counts) -> counts.size to counts.sum() }

    // ── writes: results of a run ────────────────────────────────────────────────

    private sealed interface Gate {
        data class Open(
            val root: PartnerRow,
            val settings: RecursionSettings,
        ) : Gate

        data object Blocked : Gate

        data object Gone : Gate
    }

    /** Takes the ROOT lock first, then re-checks root, block list (host AND chain), switch and depth. */
    private fun gate(c: RecursionCandidate): Gate {
        val root = PartnerServerStore.lockPartner(c.root.partnerId) ?: return Gate.Gone
        if (!root.active) return Gate.Gone
        val patterns = PartnerServerStore.blockPatterns()
        if (PartnerBlocklistPattern.isBlocked(host = root.host, patterns = patterns) ||
            PartnerBlocklistPattern.isBlocked(host = c.host, patterns = patterns) ||
            c.via.any { PartnerBlocklistPattern.isBlocked(host = it, patterns = patterns) }
        ) {
            return Gate.Blocked
        }
        val settings = recursionSettings()
        if (!settings.enabled || c.hops > 1 + settings.depth) return Gate.Gone
        return Gate.Open(root = root, settings = settings)
    }

    fun applyDiscoveredSuccess(
        c: RecursionCandidate,
        parsed: PartnerParseResult.Ok,
        etag: String?,
        now: LocalDateTime,
    ): DiscoveredWriteResult {
        when (gate(c)) {
            Gate.Blocked -> return DiscoveredWriteResult.Blocked
            Gate.Gone -> return DiscoveredWriteResult.Gone
            is Gate.Open -> Unit
        }
        if (PartnerServerStore.findPartnerByHost(c.host) != null) return DiscoveredWriteResult.Conflict
        val directOwner = PartnerServerStore.boundServerIdHost(parsed.remoteServerId)
        val conflict = directOwner != null || idOwnedByOtherDiscovered(remoteServerId = parsed.remoteServerId, host = c.host)
        val existing = findByHost(c.host)
        if (conflict) {
            val id = ensureRow(c = c, existing = existing, now = now) ?: return DiscoveredWriteResult.CapReached
            clearEventsAndFail(id = id, error = PartnerFetchErrorCode.SERVER_ID_CONFLICT, c = c, now = now)
            return DiscoveredWriteResult.Conflict
        }
        val id = ensureRow(c = c, existing = existing, now = now) ?: return DiscoveredWriteResult.CapReached
        val partnerHostsJson = if (parsed.partnersHidden) null else parsed.partnersJson
        val stored =
            try {
                withSavepoint(name = "partner_discovered_events") {
                    PartnerDiscoveredServerTable.update({ PartnerDiscoveredServerTable.id eq id }) {
                        it[remoteServerId] = parsed.remoteServerId
                        it[remoteName] = parsed.remoteName
                        it[lastFetchAt] = now
                        it[lastStatus] = PartnerFetchStatus.OK
                        it[lastError] = null
                        it[consecutiveFailures] = 0
                        it[nextFetchAt] = PartnerServerStore.nextSlot(host = c.host, now = now)
                        it[PartnerDiscoveredServerTable.etag] = etag
                        it[remotePartnersJson] = partnerHostsJson
                        it[partnersHidden] = parsed.partnersHidden
                    }
                    replaceDiscoveredEvents(id = id, c = c, parsed = parsed, now = now)
                }
            } catch (e: Exception) {
                if (!e.isUniqueViolation()) throw e
                clearEventsAndFail(id = id, error = PartnerFetchErrorCode.SERVER_ID_CONFLICT, c = c, now = now)
                return DiscoveredWriteResult.Conflict
            }
        PartnerDiscoveredServerTable.update({ PartnerDiscoveredServerTable.id eq id }) { it[eventCount] = stored }
        return DiscoveredWriteResult.Stored(stored)
    }

    /** A server id that was already seen in this run (or belongs to a direct partner): the row records the conflict, its events go. */
    fun applyDiscoveredConflict(
        c: RecursionCandidate,
        now: LocalDateTime,
    ): DiscoveredWriteResult {
        when (gate(c)) {
            Gate.Blocked -> return DiscoveredWriteResult.Blocked
            Gate.Gone -> return DiscoveredWriteResult.Gone
            is Gate.Open -> Unit
        }
        if (PartnerServerStore.findPartnerByHost(c.host) != null) return DiscoveredWriteResult.Conflict
        val id = ensureRow(c = c, existing = findByHost(c.host), now = now) ?: return DiscoveredWriteResult.CapReached
        clearEventsAndFail(id = id, error = PartnerFetchErrorCode.SERVER_ID_CONFLICT, c = c, now = now)
        return DiscoveredWriteResult.Conflict
    }

    /** `304`: the cached list and events are still right; they live on, and the row is re-attached to the path of THIS run. */
    fun applyDiscoveredNotModified(
        c: RecursionCandidate,
        now: LocalDateTime,
    ): DiscoveredWriteResult {
        when (gate(c)) {
            Gate.Blocked -> return DiscoveredWriteResult.Blocked
            Gate.Gone -> return DiscoveredWriteResult.Gone
            is Gate.Open -> Unit
        }
        val existing = findByHost(c.host) ?: return DiscoveredWriteResult.Gone
        reattach(id = existing.id, c = c, now = now)
        PartnerEventTable.update({ PartnerEventTable.discoveredServerId eq existing.id }) {
            it[expiresAt] = now.plusHoursKt(PartnerServerStore.CACHE_TTL_HOURS)
        }
        PartnerDiscoveredServerTable.update({ PartnerDiscoveredServerTable.id eq existing.id }) {
            it[lastFetchAt] = now
            it[lastStatus] = PartnerFetchStatus.OK
            it[lastError] = null
            it[consecutiveFailures] = 0
            it[nextFetchAt] = PartnerServerStore.nextSlot(host = c.host, now = now)
        }
        return DiscoveredWriteResult.Stored(existing.eventCount)
    }

    /** The server is in back-off but its stored list is still fresh: only the path (root, hops, via) and the "reached" stamp are updated. */
    fun markReachedFromCache(
        c: RecursionCandidate,
        now: LocalDateTime,
    ): DiscoveredWriteResult {
        when (gate(c)) {
            Gate.Blocked -> return DiscoveredWriteResult.Blocked
            Gate.Gone -> return DiscoveredWriteResult.Gone
            is Gate.Open -> Unit
        }
        val existing = findByHost(c.host) ?: return DiscoveredWriteResult.Gone
        reattach(id = existing.id, c = c, now = now)
        return DiscoveredWriteResult.Stored(existing.eventCount)
    }

    /**
     * A failed fetch: same back-off as P1. `last_fetch_at` is deliberately NOT touched -- it stays the time of the last SUCCESS, so the age of the
     * stored partner list is always known. The cached events stay until their own expiry.
     */
    fun applyDiscoveredFailure(
        c: RecursionCandidate,
        error: PartnerFetchErrorCode,
        now: LocalDateTime,
    ): DiscoveredWriteResult {
        when (gate(c)) {
            Gate.Blocked -> return DiscoveredWriteResult.Blocked
            Gate.Gone -> return DiscoveredWriteResult.Gone
            is Gate.Open -> Unit
        }
        if (PartnerServerStore.findPartnerByHost(c.host) != null) return DiscoveredWriteResult.Conflict
        val existing = findByHost(c.host)
        val id = ensureRow(c = c, existing = existing, now = now) ?: return DiscoveredWriteResult.CapReached
        val failures = (existing?.consecutiveFailures ?: 0) + 1
        PartnerDiscoveredServerTable.update({ PartnerDiscoveredServerTable.id eq id }) {
            it[consecutiveFailures] = failures
            it[lastStatus] = PartnerFetchStatus.ERROR
            it[lastError] = error.name
            it[nextFetchAt] = now.plusHoursKt(PartnerServerStore.backoffHours(failures))
        }
        return DiscoveredWriteResult.Stored(existing?.eventCount ?: 0)
    }

    /** Inserts the row when it is new (subject to the row caps, evicting a stale row of the SAME root) or re-attaches an existing one. */
    private fun ensureRow(
        c: RecursionCandidate,
        existing: DiscoveredRow?,
        now: LocalDateTime,
    ): Uuid? {
        if (existing != null) {
            reattach(id = existing.id, c = c, now = now)
            return existing.id
        }
        lockStorageBudget()
        val total = PartnerDiscoveredServerTable.selectAll().count().toInt()
        val perRoot =
            PartnerDiscoveredServerTable
                .selectAll()
                .where { PartnerDiscoveredServerTable.rootPartnerServerId eq c.root.partnerId }
                .count()
                .toInt()
        if (total >= PartnerRecursionLimits.MAX_DISCOVERED_ROWS || perRoot >= PartnerRecursionLimits.MAX_DISCOVERED_ROWS_PER_ROOT) {
            if (!evictOneStale(rootId = c.root.partnerId, now = now)) return null
        }
        val id = Uuid.random()
        PartnerDiscoveredServerTable.insert {
            it[PartnerDiscoveredServerTable.id] = id
            it[host] = c.host
            it[rootPartnerServerId] = c.root.partnerId
            it[hops] = c.hops.toShort()
            it[viaHosts] = PartnerProvenance.encode(c.via)
            it[createdAt] = now
            it[lastReachedAt] = now
            it[nextFetchAt] = now
        }
        return id
    }

    /** Deletes the least recently reached row of [rootId] that was not reached within [PartnerRecursionLimits.EVICTION_MIN_AGE_HOURS]. */
    private fun evictOneStale(
        rootId: Uuid,
        now: LocalDateTime,
    ): Boolean {
        val cutoff = now.minusHoursKt(PartnerRecursionLimits.EVICTION_MIN_AGE_HOURS)
        val victim =
            PartnerDiscoveredServerTable
                .selectAll()
                .where { PartnerDiscoveredServerTable.rootPartnerServerId eq rootId }
                .map { it.toDiscovered() to it[PartnerDiscoveredServerTable.createdAt] }
                .filter { (row, created) -> (row.lastReachedAt ?: created) < cutoff }
                .minWithOrNull(compareBy({ it.first.lastReachedAt ?: it.second }, { it.first.host }))
                ?.first ?: return false
        PartnerDiscoveredServerTable.deleteWhere { PartnerDiscoveredServerTable.id eq victim.id }
        return true
    }

    /** A shorter (or merely different) path overwrites root, hops and via of the row AND of its events. */
    private fun reattach(
        id: Uuid,
        c: RecursionCandidate,
        now: LocalDateTime,
    ) {
        val via = PartnerProvenance.encode(c.via)
        PartnerDiscoveredServerTable.update({ PartnerDiscoveredServerTable.id eq id }) {
            it[rootPartnerServerId] = c.root.partnerId
            it[hops] = c.hops.toShort()
            it[viaHosts] = via
            it[lastReachedAt] = now
        }
        PartnerEventTable.update({ PartnerEventTable.discoveredServerId eq id }) {
            it[partnerServerId] = c.root.partnerId
            it[hops] = c.hops.toShort()
            it[viaHosts] = via
        }
    }

    private fun clearEventsAndFail(
        id: Uuid,
        error: PartnerFetchErrorCode,
        c: RecursionCandidate,
        now: LocalDateTime,
    ) {
        PartnerEventTable.deleteWhere { PartnerEventTable.discoveredServerId eq id }
        PartnerDiscoveredServerTable.update({ PartnerDiscoveredServerTable.id eq id }) {
            it[eventCount] = 0
            it[etag] = null
            it[remotePartnersJson] = null
            it[partnersHidden] = false
            it[lastStatus] = PartnerFetchStatus.ERROR
            it[lastError] = error.name
            it[consecutiveFailures] = 1
            it[nextFetchAt] = now.plusHoursKt(PartnerServerStore.backoffHours(1))
        }
        reattach(id = id, c = c, now = now)
    }

    /**
     * The storage caps (rows and events) must hold when four servers are written at once: every writer that counts takes the single settings
     * row first, so its count sees what the previous writer committed. Lock order stays partner_server -> settings row -> discovered/events;
     * nobody holds the settings row while waiting for a partner row.
     */
    private fun lockStorageBudget() {
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .forUpdate()
            .toList()
    }

    /** Replaces the events of one discovered server. Returns the number stored (capped per server and in total). */
    private fun replaceDiscoveredEvents(
        id: Uuid,
        c: RecursionCandidate,
        parsed: PartnerParseResult.Ok,
        now: LocalDateTime,
    ): Int {
        lockStorageBudget()
        PartnerEventTable.deleteWhere { PartnerEventTable.discoveredServerId eq id }
        val others =
            PartnerEventTable
                .selectAll()
                .where { (PartnerEventTable.hops greater 1.toShort()) and (PartnerEventTable.expiresAt greater now) }
                .count()
                .toInt()
        val allowed =
            minOf(
                PartnerRecursionLimits.MAX_EVENTS_PER_DISCOVERED,
                (PartnerRecursionLimits.MAX_DISCOVERED_EVENTS_TOTAL - others).coerceAtLeast(0),
            )
        val events = parsed.events.take(allowed)
        if (events.isEmpty()) return 0
        val expires = now.plusHoursKt(PartnerServerStore.CACHE_TTL_HOURS)
        val via = PartnerProvenance.encode(c.via)
        PartnerEventTable.batchInsert(events) { ev ->
            this[PartnerEventTable.id] = Uuid.random()
            this[PartnerEventTable.partnerServerId] = c.root.partnerId
            this[PartnerEventTable.discoveredServerId] = id
            this[PartnerEventTable.hops] = c.hops.toShort()
            this[PartnerEventTable.originHost] = c.host
            this[PartnerEventTable.viaHosts] = via
            this[PartnerEventTable.remoteServerId] = parsed.remoteServerId
            this[PartnerEventTable.remoteEventId] = ev.id
            this[PartnerEventTable.title] = ev.title
            this[PartnerEventTable.startsAt] = ev.startsAt
            this[PartnerEventTable.endsAt] = ev.endsAt
            this[PartnerEventTable.timeZone] = ev.timeZone
            this[PartnerEventTable.online] = ev.online
            this[PartnerEventTable.place] = ev.place
            this[PartnerEventTable.summary] = ev.summary
            this[PartnerEventTable.recurrenceSentence] = ev.recurrenceSentence
            this[PartnerEventTable.url] = ev.url
            this[PartnerEventTable.fetchedAt] = now
            this[PartnerEventTable.expiresAt] = expires
        }
        return events.size
    }

    // ── writes: other writers' hooks ────────────────────────────────────────────

    /** The direct partner wins: a discovered row with the same host or the same announced id is removed (its events follow by CASCADE). */
    fun deleteDiscoveredForHostOrId(
        host: String,
        remoteServerId: Uuid?,
    ) {
        PartnerDiscoveredServerTable.deleteWhere {
            if (remoteServerId == null) {
                PartnerDiscoveredServerTable.host eq host
            } else {
                (PartnerDiscoveredServerTable.host eq host) or (PartnerDiscoveredServerTable.remoteServerId eq remoteServerId)
            }
        }
    }

    /**
     * Called by [PartnerServerStore.addBlock] IN ITS transaction (the partner rows are locked by then): rows whose host matches go (events by
     * CASCADE); events whose origin or via chain matches go too -- a pass-through row keeps its row but loses those events.
     */
    fun purgeForBlock(pattern: String) {
        val rows = PartnerDiscoveredServerTable.selectAll().map { it.toDiscovered() }
        val doomedRows = rows.filter { PartnerBlocklistPattern.matches(pattern = pattern, host = it.host) }.map { it.id }
        doomedRows.chunked(CHUNK).forEach { chunk ->
            PartnerDiscoveredServerTable.deleteWhere { PartnerDiscoveredServerTable.id inList chunk }
        }
        val doomedEvents =
            PartnerEventTable
                .selectAll()
                .where { PartnerEventTable.hops greater 1.toShort() }
                .limit(PartnerRecursionLimits.MAX_DISCOVERED_EVENTS_TOTAL + CHUNK)
                .filter { row ->
                    val hosts = listOf(row[PartnerEventTable.originHost]) + PartnerProvenance.decode(row[PartnerEventTable.viaHosts])
                    hosts.any { PartnerBlocklistPattern.matches(pattern = pattern, host = it) }
                }
        val affectedRows = doomedEvents.mapNotNull { it[PartnerEventTable.discoveredServerId] }.toSet()
        doomedEvents
            .map { it[PartnerEventTable.id] }
            .chunked(CHUNK)
            .forEach { chunk -> PartnerEventTable.deleteWhere { PartnerEventTable.id inList chunk } }
        // The cache of these rows is (partly) gone: the next fetch must be unconditional, or a 304 would "confirm" the hole.
        affectedRows.chunked(CHUNK).forEach { chunk ->
            PartnerDiscoveredServerTable.update({ PartnerDiscoveredServerTable.id inList chunk }) { it[etag] = null }
        }
    }

    /**
     * Run by every poller tick. Switch off: every foreign event of hop >= 2 goes. Switch on: whatever is deeper than the current depth goes, as
     * do rows not reached for [PartnerRecursionLimits.STALE_DISCOVERED_DAYS] days. Expired events of hop >= 2 are deleted and the counters and
     * ETags of the rows follow.
     */
    fun housekeeping(
        now: LocalDateTime,
        settings: RecursionSettings,
    ) {
        val deepest = 1 + settings.depth
        if (!settings.enabled) {
            PartnerEventTable.deleteWhere { PartnerEventTable.hops greater 1.toShort() }
        } else {
            PartnerEventTable.deleteWhere { PartnerEventTable.hops greater deepest.toShort() }
            PartnerDiscoveredServerTable.deleteWhere { PartnerDiscoveredServerTable.hops greater deepest.toShort() }
        }
        val stale = now.minusHoursKt(PartnerRecursionLimits.STALE_DISCOVERED_DAYS * HOURS_PER_DAY)
        PartnerDiscoveredServerTable.deleteWhere {
            (PartnerDiscoveredServerTable.lastReachedAt less stale) or
                (PartnerDiscoveredServerTable.lastReachedAt.isNull() and (PartnerDiscoveredServerTable.createdAt less stale))
        }
        val expiredOwners =
            PartnerEventTable
                .selectAll()
                .where { (PartnerEventTable.hops greater 1.toShort()) and (PartnerEventTable.expiresAt less now) }
                .mapNotNull { it[PartnerEventTable.discoveredServerId] }
                .toSet()
        PartnerEventTable.deleteWhere { (PartnerEventTable.hops greater 1.toShort()) and (PartnerEventTable.expiresAt less now) }
        val remaining =
            PartnerEventTable
                .selectAll()
                .where { PartnerEventTable.hops greater 1.toShort() }
                .mapNotNull { it[PartnerEventTable.discoveredServerId] }
                .groupingBy { it }
                .eachCount()
        PartnerDiscoveredServerTable
            .selectAll()
            .map {
                it[PartnerDiscoveredServerTable.id] to it[PartnerDiscoveredServerTable.eventCount]
            }.forEach { (id, count) ->
                val actual = remaining[id] ?: 0
                if (actual != count || id in expiredOwners) {
                    PartnerDiscoveredServerTable.update({ PartnerDiscoveredServerTable.id eq id }) {
                        it[eventCount] = actual
                        if (id in expiredOwners) it[etag] = null
                    }
                }
            }
    }

    private const val CHUNK = 500
    private const val SUMMARY_MAX_CHARS = 2000
    private const val HOURS_PER_DAY = 24L
}

internal fun LocalDateTime.plusHoursKt(hours: Long): LocalDateTime = toJavaLocalDateTime().plusHours(hours).toKotlinLocalDateTime()

internal fun LocalDateTime.minusHoursKt(hours: Long): LocalDateTime = toJavaLocalDateTime().minusHours(hours).toKotlinLocalDateTime()
