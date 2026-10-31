package network.lapis.cloud.server.partner

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.events.series.PublicSeriesSummary
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.routes.COVER_SLUG_PATTERN
import network.lapis.cloud.server.routes.PublicSeriesQueries
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.EventOnlineKind
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * V1.9.244 -- builds the body of our own public partner feed (`GET /api/public/partner-feed/v1`).
 *
 * **What leaves the server** is exactly what `/veranstaltungen` and `/veranstaltung.ics` already show without a login: public, published,
 * not yet ended events (one entry per series -- the next occurrence plus the series sentence), cut to the next [HORIZON_DAYS] days. Per event:
 * id, title, start/end WITH offset, the organisation's IANA zone, an online yes/no, the location text, the short summary and the public page URL.
 * **Never**: an online URL, the long description, capacity, fee, registration numbers, members, creators, drafts or cancelled events.
 *
 * The result is bounded: at most [MAX_EVENTS] events and [MAX_BYTES] bytes (the tail is cut until it fits).
 */
internal object PartnerFeedBuilder {
    const val MAX_EVENTS = 500
    const val MAX_BYTES = 512 * 1024
    const val HORIZON_DAYS = 365L

    fun build(
        now: Instant = ServerClock.nowInstant(),
        publicBaseUrl: String = FederationConfig.publicBaseUrl,
    ): ByteArray {
        val zone = OrganizationTimeZone.current()
        val wallNow = now.toLocalDateTime(zone)
        val horizon = wallNow.toJavaLocalDateTime().plusDays(HORIZON_DAYS)
        val baseUrl = publicBaseUrl.trimEnd('/')
        val (serverId, name, partners, hidden, events) =
            transaction {
                val rows = PublicSeriesQueries.loadOverviewRepresentatives(wallNow = wallNow, limit = MAX_EVENTS)
                val stats = PublicSeriesQueries.loadSeriesStats(seriesIds = rows.mapNotNull { it[EventTable.seriesId] }, wallNow = wallNow)
                val mapped =
                    rows
                        .filter { !it[EventTable.startsAt].toJavaLocalDateTime().isAfter(horizon) }
                        .mapNotNull { row ->
                            val slug = row[EventTable.slug]
                            if (!COVER_SLUG_PATTERN.matches(slug)) {
                                logger.warn {
                                    "PartnerFeedBuilder: skipping event ${row[EventTable.id]}: slug does not match the expected pattern"
                                }
                                return@mapNotNull null
                            }
                            val sentence =
                                row[EventTable.seriesId]?.let { stats[it] }?.let {
                                    PublicSeriesSummary.repeatSentenceOrFallback(
                                        it.rrule,
                                    )
                                }
                            PartnerFeedEvent(
                                kind = PARTNER_FEED_EVENT_KIND,
                                id = row[EventTable.id].toString(),
                                title = row[EventTable.title],
                                startsAt = offsetString(wall = row[EventTable.startsAt], zoneId = zone.id),
                                endsAt = offsetString(wall = row[EventTable.endsAt], zoneId = zone.id),
                                timeZone = zone.id,
                                online = row[EventTable.onlineKind] != EventOnlineKind.NONE || row[EventTable.onlineUrl] != null,
                                place = ForeignText.clean(raw = row[EventTable.locationText], maxChars = 200),
                                summary = ForeignText.clean(raw = row[EventTable.summary], maxChars = 300),
                                url = "$baseUrl/veranstaltung/$slug",
                                recurrence = sentence?.let { PartnerFeedRecurrence(sentence = it) },
                            )
                        }
                val blocks = PartnerServerStore.blockPatterns()
                val hiddenList = PartnerServerStore.isPartnerListHidden()
                val listed =
                    if (hiddenList) {
                        emptyList()
                    } else {
                        PartnerServerStore
                            .listPartners()
                            .filter { it.active && !PartnerBlocklistPattern.isBlocked(host = it.host, patterns = blocks) }
                            .map {
                                PartnerFeedPartner(
                                    kind = PARTNER_FEED_PARTNER_KIND,
                                    host = it.host,
                                    name = ForeignText.clean(raw = it.displayName ?: it.remoteName ?: it.host, maxChars = 100) ?: it.host,
                                )
                            }
                    }
                FeedParts(
                    serverId = PartnerServerStore.ensureServerId().toString(),
                    name = ForeignText.clean(raw = PartnerServerStore.organizationName(), maxChars = 100) ?: "Lapis Cloud",
                    partners = listed,
                    hidden = hiddenList,
                    events = mapped,
                )
            }
        return serializeBounded(
            PartnerFeedV1(
                schemaVersion = 1,
                kind = PARTNER_FEED_KIND,
                serverId = serverId,
                name = name,
                generatedAt = now.toString(),
                events = events,
                partners = partners,
                partnersHidden = hidden,
            ),
        )
    }

    private data class FeedParts(
        val serverId: String,
        val name: String,
        val partners: List<PartnerFeedPartner>,
        val hidden: Boolean,
        val events: List<PartnerFeedEvent>,
    )

    /** Serialises; while the body is over [MAX_BYTES] the event list is halved (the latest events go first). */
    internal fun serializeBounded(feed: PartnerFeedV1): ByteArray {
        var current = feed
        while (true) {
            val bytes = PARTNER_FEED_JSON.encodeToString(PartnerFeedV1.serializer(), current).toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_BYTES || current.events.isEmpty()) return bytes
            current = current.copy(events = current.events.take(current.events.size / 2))
        }
    }

    /** `2026-11-05T19:00:00+01:00` -- the organisation's wall-clock with the offset that applies on that day (DST-correct). */
    private fun offsetString(
        wall: LocalDateTime,
        zoneId: String,
    ): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(wall.toJavaLocalDateTime().atZone(ZoneId.of(zoneId)).toOffsetDateTime())
}
