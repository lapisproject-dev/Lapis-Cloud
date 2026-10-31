package network.lapis.cloud.server.partner

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.time.ServerClock
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

/**
 * V1.9.245 -- a counter that is bumped by everything that changes WHAT the public pages may show of the partner cache (block list, activate /
 * deactivate / remove a partner, the recursion switch). It is part of the memo key of `GET /veranstaltungen`, so a block empties the 30-second
 * server-side preview at once. (Browsers and proxies keep `max-age=300`; documented.)
 */
internal object PartnerDisplayEpoch {
    private val value = AtomicLong(0)

    fun current(): Long = value.get()

    fun bump() {
        value.incrementAndGet()
    }
}

/** One foreign event as the public pages show it (already filtered, URL re-validated). Times: [startsAtWall] is the organisation's wall clock. */
internal data class PartnerEventView(
    val remoteServerId: Uuid,
    val remoteEventId: String,
    val title: String,
    val startsAtWall: LocalDateTime,
    val startsAtUtc: LocalDateTime,
    val endsAtUtc: LocalDateTime,
    val online: Boolean,
    val place: String?,
    val summary: String?,
    val recurrenceSentence: String?,
    val url: String,
    val hops: Int,
    val originHost: String,
    val originName: String?,
    val rootName: String,
)

/**
 * V1.9.245 -- the READ side of the partner cache for the public pages (`/veranstaltungen`, `/veranstaltung.ics?quelle=alle`). Everything that
 * decides visibility is applied HERE, at read time, so a block, a deactivation or a switch-off acts immediately -- the deletes of the stores
 * are the second line.
 */
internal object PartnerEventDisplay {
    const val MAX_PARTNER_ITEMS = 50
    const val MAX_PER_ORIGIN = 10
    const val MAX_SCAN = 5000
    private const val MAX_URL_LENGTH = 500
    private const val HTTPS_PORT = 443

    /** Needs an open transaction. */
    fun loadVisible(
        nowUtc: LocalDateTime,
        orgZone: TimeZone,
    ): List<PartnerEventView> {
        val settings = PartnerDiscoveryStore.recursionSettings()
        val patterns = PartnerServerStore.blockPatterns()
        val join =
            PartnerEventTable
                .join(PartnerServerTable, JoinType.INNER, PartnerEventTable.partnerServerId, PartnerServerTable.id)
                .join(PartnerDiscoveredServerTable, JoinType.LEFT, PartnerEventTable.discoveredServerId, PartnerDiscoveredServerTable.id)
        val rows =
            join
                .selectAll()
                .where { (PartnerEventTable.expiresAt greater nowUtc) and (PartnerEventTable.endsAt greater nowUtc) }
                .orderBy(PartnerEventTable.startsAt to SortOrder.ASC, PartnerEventTable.remoteEventId to SortOrder.ASC)
                .limit(MAX_SCAN)
                .toList()
        val views =
            rows.mapNotNull { row ->
                val hops = row[PartnerEventTable.hops].toInt()
                val originHost = row[PartnerEventTable.originHost]
                val via = PartnerProvenance.decode(row[PartnerEventTable.viaHosts])
                if (!isVisible(
                        hops = hops,
                        originHost = originHost,
                        via = via,
                        rootActive = row[PartnerServerTable.active],
                        settings = settings,
                        patterns = patterns,
                    )
                ) {
                    return@mapNotNull null
                }
                val url = revalidatedUrl(url = row[PartnerEventTable.url], originHost = originHost) ?: return@mapNotNull null
                val rootName = row[PartnerServerTable.displayName] ?: row[PartnerServerTable.remoteName] ?: row[PartnerServerTable.host]
                val startsUtc = row[PartnerEventTable.startsAt]
                PartnerEventView(
                    remoteServerId = row[PartnerEventTable.remoteServerId],
                    remoteEventId = row[PartnerEventTable.remoteEventId],
                    title = row[PartnerEventTable.title],
                    startsAtWall = ServerClock.systemToWall(utc = startsUtc, orgZone = orgZone),
                    startsAtUtc = startsUtc,
                    endsAtUtc = row[PartnerEventTable.endsAt],
                    online = row[PartnerEventTable.online],
                    place = row[PartnerEventTable.place],
                    summary = row[PartnerEventTable.summary],
                    recurrenceSentence = row[PartnerEventTable.recurrenceSentence],
                    url = url,
                    hops = hops,
                    originHost = originHost,
                    originName = if (hops >= 2) row[PartnerDiscoveredServerTable.remoteName] else rootName,
                    rootName = rootName,
                )
            }
        return capAndSort(views)
    }

    /**
     * Pure. A foreign event is shown when its ROOT (the direct partner) is active, neither its origin nor any host of its chain is blocked,
     * and -- for hop >= 2 -- the recursion is on and the hop is within the CURRENT depth.
     */
    internal fun isVisible(
        hops: Int,
        originHost: String,
        via: List<String>,
        rootActive: Boolean,
        settings: RecursionSettings,
        patterns: List<String>,
    ): Boolean {
        if (!rootActive) return false
        if (hops >= 2 && (!settings.enabled || hops > 1 + settings.depth)) return false
        if (PartnerBlocklistPattern.isBlocked(host = originHost, patterns = patterns)) return false
        return via.none { PartnerBlocklistPattern.isBlocked(host = it, patterns = patterns) }
    }

    /** Pure. Sorted by `(starts, origin host, event id)`, at most [MAX_PER_ORIGIN] per origin and [MAX_PARTNER_ITEMS] in total. */
    internal fun capAndSort(rows: List<PartnerEventView>): List<PartnerEventView> {
        val perOrigin = HashMap<String, Int>()
        val out = ArrayList<PartnerEventView>()
        for (row in rows.sortedWith(compareBy({ it.startsAtUtc }, { it.originHost }, { it.remoteEventId }))) {
            if (out.size >= MAX_PARTNER_ITEMS) break
            val used = perOrigin[row.originHost] ?: 0
            if (used >= MAX_PER_ORIGIN) continue
            perOrigin[row.originHost] = used + 1
            out += row
        }
        return out
    }

    /**
     * The link target is validated AGAIN at render time (the stored value is a cache of foreign data): `https`, no user info, port 443 or none,
     * the host is exactly the origin's own host, no blank or control characters. `null` = the row is not shown.
     */
    internal fun revalidatedUrl(
        url: String,
        originHost: String,
    ): String? {
        if (url.length > MAX_URL_LENGTH || url.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != HTTPS_PORT) return null
        val host = uri.host?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return null
        return if (host == originHost) url else null
    }
}
