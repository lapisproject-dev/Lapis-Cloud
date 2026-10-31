package network.lapis.cloud.server.partner

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import network.lapis.cloud.shared.domain.PartnerFetchStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.time.temporal.ChronoUnit
import kotlin.math.min
import kotlin.uuid.Uuid

internal data class PartnerRow(
    val id: Uuid,
    val host: String,
    val displayName: String?,
    val remoteServerId: Uuid?,
    val remoteName: String?,
    val active: Boolean,
    val lastFetchAt: LocalDateTime?,
    val lastStatus: PartnerFetchStatus,
    val lastError: String?,
    val eventCount: Int,
    val consecutiveFailures: Int,
    val nextFetchAt: LocalDateTime,
    val etag: String?,
)

internal data class BlockRow(
    val id: Uuid,
    val pattern: String,
)

/** The outcome of an insert attempt of a probed partner. */
internal sealed interface PartnerInsertResult {
    data class Inserted(
        val id: Uuid,
    ) : PartnerInsertResult

    data class Failed(
        val error: PartnerFetchErrorCode,
    ) : PartnerInsertResult
}

/**
 * V1.9.244 -- THE writer of `partner_server`, `partner_blocklist`, the events of the DIRECT partners (`partner_event.hops = 1`) and the two
 * partner columns of `organization_settings`. Since V1.9.245 [PartnerDiscoveryStore] is the second (and last) writer: it owns
 * `partner_discovered_server`, the events with `hops > 1` and the `partner_recursion_*` columns.
 * Every function needs an open `transaction {}`; none of them touches the network (a fetch inside a transaction would be repeated by Exposed on
 * an SQLException). Time values are class A (UTC) `LocalDateTime`s handed in by the caller.
 */
internal object PartnerServerStore {
    /** How long a cached event survives without a successful (or `304`) refresh. */
    const val CACHE_TTL_HOURS = 6L
    private const val BACKOFF_BASE_HOURS = 1L
    private const val BACKOFF_MAX_HOURS = 24L
    private const val BACKOFF_MAX_EXPONENT = 5
    private const val SLOT_SPREAD_SECONDS = 3600L
    const val DUE_LIMIT = 50

    // ── own identity and the list switch ────────────────────────────────────────

    /** The stable public id of this server; created on first use. Race-free: the UPDATE only fills a NULL, then the row is read again. */
    fun ensureServerId(): Uuid {
        OrganizationSettingsTable.update({
            (OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID) and OrganizationSettingsTable.partnerServerId.isNull()
        }) { it[partnerServerId] = Uuid.random() }
        return OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .single()[OrganizationSettingsTable.partnerServerId]
            ?: error("partner_server_id is NULL after ensureServerId")
    }

    fun isPartnerListHidden(): Boolean =
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .single()[OrganizationSettingsTable.partnerListHidden]

    fun setPartnerListHidden(hidden: Boolean) {
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) { it[partnerListHidden] = hidden }
    }

    fun organizationName(): String =
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .single()[OrganizationSettingsTable.name]

    // ── reads ───────────────────────────────────────────────────────────────────

    private fun ResultRow.toPartnerRow() =
        PartnerRow(
            id = this[PartnerServerTable.id],
            host = this[PartnerServerTable.host],
            displayName = this[PartnerServerTable.displayName],
            remoteServerId = this[PartnerServerTable.remoteServerId],
            remoteName = this[PartnerServerTable.remoteName],
            active = this[PartnerServerTable.active],
            lastFetchAt = this[PartnerServerTable.lastFetchAt],
            lastStatus = this[PartnerServerTable.lastStatus],
            lastError = this[PartnerServerTable.lastError],
            eventCount = this[PartnerServerTable.eventCount],
            consecutiveFailures = this[PartnerServerTable.consecutiveFailures],
            nextFetchAt = this[PartnerServerTable.nextFetchAt],
            etag = this[PartnerServerTable.etag],
        )

    /** Ordered by host IN KOTLIN: the database collation decides how `-` and `.` sort (PostgreSQL ignores punctuation at first level, H2 does not). */
    fun listPartners(): List<PartnerRow> =
        PartnerServerTable
            .selectAll()
            .map { it.toPartnerRow() }
            .sortedBy { it.host }

    fun findPartner(id: Uuid): PartnerRow? =
        PartnerServerTable
            .selectAll()
            .where { PartnerServerTable.id eq id }
            .singleOrNull()
            ?.toPartnerRow()

    /**
     * The partner row, locked `FOR UPDATE`. Every write that depends on the block list ([applyFetchSuccess], [applyNotModified]) takes this lock
     * FIRST and reads the block list afterwards; [addBlock] inserts the pattern and then locks the matching rows. Whichever side comes second waits
     * for the other, so a fetch result can never be written back after the block that was set while the request was on the wire.
     */
    internal fun lockPartner(id: Uuid): PartnerRow? =
        PartnerServerTable
            .selectAll()
            .where { PartnerServerTable.id eq id }
            .forUpdate()
            .singleOrNull()
            ?.toPartnerRow()

    fun findPartnerByHost(host: String): PartnerRow? =
        PartnerServerTable
            .selectAll()
            .where { PartnerServerTable.host eq host }
            .singleOrNull()
            ?.toPartnerRow()

    /** Ordered by pattern IN KOTLIN, for the same collation reason as [listPartners]. */
    fun listBlocklist(): List<BlockRow> =
        PartnerBlocklistTable
            .selectAll()
            .map { BlockRow(id = it[PartnerBlocklistTable.id], pattern = it[PartnerBlocklistTable.pattern]) }
            .sortedBy { it.pattern }

    fun blockPatterns(): List<String> =
        PartnerBlocklistTable.select(PartnerBlocklistTable.pattern).map { it[PartnerBlocklistTable.pattern] }

    /** The cached, not yet expired events of one partner (for tests and the later display wave). */
    fun cachedEventCount(
        partnerId: Uuid,
        now: LocalDateTime,
    ): Int =
        PartnerEventTable
            .selectAll()
            .where {
                (PartnerEventTable.partnerServerId eq partnerId) and (PartnerEventTable.hops eq 1.toShort()) and
                    (PartnerEventTable.expiresAt greater now)
            }.count()
            .toInt()

    /** Active, due partners that are not blocked -- the blocked ones only get a later `next_fetch_at` so they do not reappear every minute. */
    fun dueForFetch(
        now: LocalDateTime,
        limit: Int = DUE_LIMIT,
    ): List<PartnerRow> {
        val patterns = blockPatterns()
        val candidates =
            PartnerServerTable
                .selectAll()
                .where { PartnerServerTable.active eq true and (PartnerServerTable.nextFetchAt lessEq now) }
                .orderBy(PartnerServerTable.nextFetchAt to SortOrder.ASC, PartnerServerTable.host to SortOrder.ASC)
                .limit(limit)
                .map { it.toPartnerRow() }
        val (blocked, free) = candidates.partition { PartnerBlocklistPattern.isBlocked(host = it.host, patterns = patterns) }
        blocked.forEach { row ->
            PartnerServerTable.update({ PartnerServerTable.id eq row.id }) { it[nextFetchAt] = nextSlot(host = row.host, now = now) }
        }
        return free
    }

    // ── writes: partners ────────────────────────────────────────────────────────

    /**
     * Inserts a freshly probed partner with its events in ONE transaction. A second concurrent insert of the same host (or of an already bound
     * server id) loses on the unique constraint -- under a savepoint, so PostgreSQL's transaction stays usable -- and is reported as a code.
     */
    fun insertProbedPartner(
        host: String,
        displayName: String?,
        parsed: PartnerParseResult.Ok,
        etag: String?,
        now: LocalDateTime,
    ): PartnerInsertResult {
        if (PartnerBlocklistPattern.isBlocked(
                host = host,
                patterns = blockPatterns(),
            )
        ) {
            return PartnerInsertResult.Failed(PartnerFetchErrorCode.BLOCKED)
        }
        if (findPartnerByHost(host) != null) return PartnerInsertResult.Failed(PartnerFetchErrorCode.DUPLICATE)
        if (boundServerIdHost(parsed.remoteServerId) != null) return PartnerInsertResult.Failed(PartnerFetchErrorCode.SERVER_ID_TAKEN)
        val id = Uuid.random()
        return try {
            withSavepoint(name = "partner_insert") {
                PartnerServerTable.insert {
                    it[PartnerServerTable.id] = id
                    it[PartnerServerTable.host] = host
                    it[PartnerServerTable.displayName] = displayName
                    it[remoteServerId] = parsed.remoteServerId
                    it[remoteName] = parsed.remoteName
                    it[active] = true
                    it[createdAt] = now
                    it[lastFetchAt] = now
                    it[lastStatus] = PartnerFetchStatus.OK
                    it[lastError] = null
                    it[eventCount] = parsed.events.size
                    it[consecutiveFailures] = 0
                    it[nextFetchAt] = nextSlot(host = host, now = now)
                    it[PartnerServerTable.etag] = etag
                    it[remotePartnersJson] = parsed.partnersJson
                }
                // The direct partner wins: a discovered row with the same host or announced id must go first (unique (remote_server_id, remote_event_id)).
                PartnerDiscoveryStore.deleteDiscoveredForHostOrId(host = host, remoteServerId = parsed.remoteServerId)
                replaceEvents(partnerId = id, host = host, parsed = parsed, now = now)
            }
            PartnerInsertResult.Inserted(id)
        } catch (e: Exception) {
            if (e.isUniqueViolation()) {
                // Either the host or the bound server id lost a race; tell which one.
                val code = if (findPartnerByHost(host) != null) PartnerFetchErrorCode.DUPLICATE else PartnerFetchErrorCode.SERVER_ID_TAKEN
                PartnerInsertResult.Failed(code)
            } else {
                throw e
            }
        }
    }

    internal fun boundServerIdHost(remoteServerId: Uuid): String? =
        PartnerServerTable
            .selectAll()
            .where { PartnerServerTable.remoteServerId eq remoteServerId }
            .singleOrNull()
            ?.get(PartnerServerTable.host)

    /** Replaces the events of the DIRECT partner only (`hops = 1`); the events of its sub-tree are never touched here. */
    private fun replaceEvents(
        partnerId: Uuid,
        host: String,
        parsed: PartnerParseResult.Ok,
        now: LocalDateTime,
    ) {
        PartnerEventTable.deleteWhere { (PartnerEventTable.partnerServerId eq partnerId) and (PartnerEventTable.hops eq 1.toShort()) }
        val expires = now.plusHoursKt(CACHE_TTL_HOURS)
        if (parsed.events.isEmpty()) return
        PartnerEventTable.batchInsert(parsed.events) { ev ->
            this[PartnerEventTable.id] = Uuid.random()
            this[PartnerEventTable.partnerServerId] = partnerId
            this[PartnerEventTable.discoveredServerId] = null
            this[PartnerEventTable.hops] = 1.toShort()
            this[PartnerEventTable.originHost] = host
            this[PartnerEventTable.viaHosts] = null
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
    }

    /**
     * Stores a successful fetch. The block list is checked AGAIN in this very transaction: a host blocked while the request was on the wire must
     * not get its events written back (race between a fetch and `addBlock`). Returns `false` when it was dropped for that reason.
     */
    fun applyFetchSuccess(
        id: Uuid,
        parsed: PartnerParseResult.Ok,
        etag: String?,
        now: LocalDateTime,
    ): Boolean {
        val row = lockPartner(id) ?: return false
        if (PartnerBlocklistPattern.isBlocked(host = row.host, patterns = blockPatterns())) return false
        val bound = boundServerIdHost(parsed.remoteServerId)
        if (bound != null && bound != row.host) {
            applyFetchFailure(id = id, error = PartnerFetchErrorCode.SERVER_ID_TAKEN, now = now)
            return false
        }
        PartnerDiscoveryStore.deleteDiscoveredForHostOrId(host = row.host, remoteServerId = parsed.remoteServerId)
        replaceEvents(partnerId = id, host = row.host, parsed = parsed, now = now)
        PartnerServerTable.update({ PartnerServerTable.id eq id }) {
            it[remoteServerId] = parsed.remoteServerId
            it[remoteName] = parsed.remoteName
            it[lastFetchAt] = now
            it[lastStatus] = PartnerFetchStatus.OK
            it[lastError] = null
            it[eventCount] = parsed.events.size
            it[consecutiveFailures] = 0
            it[nextFetchAt] = nextSlot(host = row.host, now = now)
            it[PartnerServerTable.etag] = etag
            it[remotePartnersJson] = parsed.partnersJson
        }
        return true
    }

    /** `304 Not Modified`: the cache is still right, so it lives on. */
    fun applyNotModified(
        id: Uuid,
        now: LocalDateTime,
    ) {
        val row = lockPartner(id) ?: return
        if (PartnerBlocklistPattern.isBlocked(host = row.host, patterns = blockPatterns())) return
        PartnerEventTable.update({ (PartnerEventTable.partnerServerId eq id) and (PartnerEventTable.hops eq 1.toShort()) }) {
            it[expiresAt] = now.plusHoursKt(CACHE_TTL_HOURS)
        }
        PartnerServerTable.update({ PartnerServerTable.id eq id }) {
            it[lastFetchAt] = now
            it[lastStatus] = PartnerFetchStatus.OK
            it[lastError] = null
            it[consecutiveFailures] = 0
            it[nextFetchAt] = nextSlot(host = row.host, now = now)
        }
    }

    /** A failed fetch: exponential back-off (1 h, 2 h, 4 h ... capped at 24 h); the cached events stay until their own expiry. */
    fun applyFetchFailure(
        id: Uuid,
        error: PartnerFetchErrorCode,
        now: LocalDateTime,
    ) {
        val row = findPartner(id) ?: return
        val failures = row.consecutiveFailures + 1
        PartnerServerTable.update({ PartnerServerTable.id eq id }) {
            it[consecutiveFailures] = failures
            it[lastFetchAt] = now
            it[lastStatus] = PartnerFetchStatus.ERROR
            it[lastError] = error.name
            it[nextFetchAt] = now.plusHoursKt(backoffHours(failures))
        }
    }

    fun setActive(
        id: Uuid,
        active: Boolean,
        now: LocalDateTime,
    ): PartnerRow? {
        val row = findPartner(id) ?: return null
        PartnerServerTable.update({ PartnerServerTable.id eq id }) {
            it[PartnerServerTable.active] = active
            // Switching on makes the partner due right away (the next tick), switching off keeps the cache until it expires.
            if (active) it[nextFetchAt] = now
        }
        return row
    }

    fun remove(id: Uuid): PartnerRow? {
        val row = findPartner(id) ?: return null
        // The events of the whole sub-tree and the discovered rows follow by ON DELETE CASCADE (V132).
        PartnerEventTable.deleteWhere { PartnerEventTable.partnerServerId eq id }
        PartnerServerTable.deleteWhere { PartnerServerTable.id eq id }
        return row
    }

    // ── writes: block list ──────────────────────────────────────────────────────

    /** Adds [pattern] (idempotent) and, in the SAME transaction, deletes the cached events of every matching partner. */
    fun addBlock(
        pattern: String,
        now: LocalDateTime,
    ): Pair<BlockRow, Boolean> {
        val existing = listBlocklist().firstOrNull { it.pattern == pattern }
        var raced: BlockRow? = null
        val block =
            existing ?: run {
                val id = Uuid.random()
                try {
                    withSavepoint(name = "partner_block_insert") {
                        PartnerBlocklistTable.insert {
                            it[PartnerBlocklistTable.id] = id
                            it[PartnerBlocklistTable.pattern] = pattern
                            it[createdAt] = now
                        }
                    }
                    BlockRow(id = id, pattern = pattern)
                } catch (e: Exception) {
                    // A concurrent identical block won the race: stay idempotent and return the winner's row.
                    if (!e.isUniqueViolation()) throw e
                    raced = listBlocklist().firstOrNull { it.pattern == pattern } ?: throw e
                    raced!!
                }
            }
        // Lock ALL partner rows in id order (a handful of rows): a fetch that is writing right now finishes first, one that comes later sees the block.
        val locked =
            PartnerServerTable
                .selectAll()
                .orderBy(PartnerServerTable.id to SortOrder.ASC)
                .forUpdate()
                .map { it.toPartnerRow() }
        val matching = locked.filter { PartnerBlocklistPattern.matches(pattern = pattern, host = it.host) }.map { it.id }
        if (matching.isNotEmpty()) {
            PartnerEventTable.deleteWhere { PartnerEventTable.partnerServerId inList matching }
            // etag = NULL: after an unblock the next fetch must be unconditional, or a 304 would "confirm" an empty cache.
            PartnerServerTable.update({ PartnerServerTable.id inList matching }) {
                it[eventCount] = 0
                it[PartnerServerTable.etag] = null
            }
        }
        // Lock order: partner_server (above), then partner_discovered_server and partner_event (here).
        PartnerDiscoveryStore.purgeForBlock(pattern)
        return block to (existing == null && raced == null)
    }

    fun removeBlock(id: Uuid): BlockRow? {
        val row = listBlocklist().firstOrNull { it.id == id } ?: return null
        PartnerBlocklistTable.deleteWhere { PartnerBlocklistTable.id eq id }
        return row
    }

    /** Deletes cached events that outlived their TTL. */
    fun purgeExpired(now: LocalDateTime): Int {
        val affected =
            PartnerEventTable
                .select(PartnerEventTable.partnerServerId)
                .where { (PartnerEventTable.expiresAt less now) and (PartnerEventTable.hops eq 1.toShort()) }
                .withDistinct()
                .map { it[PartnerEventTable.partnerServerId] }
        if (affected.isEmpty()) return 0
        // Only the direct partners' events; the expired events of the sub-tree are PartnerDiscoveryStore.housekeeping's.
        val deleted = PartnerEventTable.deleteWhere { (PartnerEventTable.expiresAt less now) and (PartnerEventTable.hops eq 1.toShort()) }
        // The cache of these partners is (partly) gone: drop the ETag so the next fetch is unconditional, and fix the counter.
        affected.forEach { pid ->
            val remaining =
                PartnerEventTable
                    .selectAll()
                    .where { (PartnerEventTable.partnerServerId eq pid) and (PartnerEventTable.hops eq 1.toShort()) }
                    .count()
                    .toInt()
            PartnerServerTable.update({ PartnerServerTable.id eq pid }) {
                it[eventCount] = remaining
                it[PartnerServerTable.etag] = null
            }
        }
        return deleted
    }

    /**
     * The hosts of the stored partner list of a DIRECT partner, every one re-validated. With [PartnerDiscoveryStore.loadRunSnapshot] the only
     * place that reads `remote_partners_json` of `partner_server` (pinned by `PartnerServerTripwireTest`).
     */
    fun remotePartnerHosts(id: Uuid): List<String> =
        PartnerFeedParser.partnerHostsOf(
            PartnerServerTable
                .select(PartnerServerTable.remotePartnersJson)
                .where { PartnerServerTable.id eq id }
                .singleOrNull()
                ?.get(PartnerServerTable.remotePartnersJson),
        )

    // ── scheduling ──────────────────────────────────────────────────────────────

    /** 1, 2, 4, 8, 16, 24, 24 ... hours; the exponent is capped first so a huge failure count cannot overflow. */
    fun backoffHours(failures: Int): Long {
        val exponent = min((failures - 1).coerceAtLeast(0), BACKOFF_MAX_EXPONENT)
        return min(BACKOFF_BASE_HOURS shl exponent, BACKOFF_MAX_HOURS)
    }

    /**
     * The next regular fetch: the next full hour after [now] plus a per-host offset in `[0, 3600)` s. The offset is salted with this server's own id
     * so that not all servers hit the same partner in the same second.
     */
    fun nextSlot(
        host: String,
        now: LocalDateTime,
    ): LocalDateTime = nextSlot(host = host, now = now, salt = cachedSalt())

    internal fun nextSlot(
        host: String,
        now: LocalDateTime,
        salt: String,
    ): LocalDateTime {
        val digest = MessageDigest.getInstance("SHA-256").digest("$salt|$host".toByteArray(Charsets.UTF_8))
        val value = digest.take(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) } and Long.MAX_VALUE
        val offset = value % SLOT_SPREAD_SECONDS
        val nextHour = now.toJavaLocalDateTime().truncatedTo(ChronoUnit.HOURS).plusHours(1)
        return nextHour.plusSeconds(offset).toKotlinLocalDateTime()
    }

    private fun cachedSalt(): String = ensureServerId().toString()
}
