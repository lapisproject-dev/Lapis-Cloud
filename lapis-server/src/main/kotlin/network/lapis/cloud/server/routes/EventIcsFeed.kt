package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventSeriesTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.events.series.RecurrenceExpander
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Welle V1.4.1c "iCal-Feed für öffentliche Veranstaltungen" -- data loading + RFC-5545 text
 * rendering for `GET /veranstaltung.ics`. Kept separate from `EventPublicRoutes.kt` after the
 * same pattern `SocialPublicSitemap`/`SocialPublicRoutes` already establish (that class' own KDoc
 * spells out the reasoning): this file owns the query + text-shape logic, the route file owns
 * request handling/caching/headers.
 *
 * **Sichtbarkeitsregel identisch zu `loadPublicEventView`** (`EventPublicRoutes.kt`): nur
 * `visibility=PUBLIC AND status=PUBLISHED` -- ein Event, das über diesen Feed sichtbar ist, ist
 * IMMER auch unter `/veranstaltung/{slug}` ohne Login abrufbar, nie mehr. Es gibt deshalb bewusst
 * KEIN Secret/Token für diesen Feed -- er zeigt nichts, was nicht schon öffentlich abrufbar ist.
 *
 * **Nicht gestreamt** -- wie `SocialPublicSitemap`: [loadUpcomingPublicPublished] läuft in der
 * Caller-eigenen kurzen `transaction {}`, [render] läuft AUSSERHALB dieser Transaktion (der
 * VCALENDAR-String ist für [MAX_EVENTS] Events höchstens ein paar hundert KB) -- ein Streaming-
 * Writer innerhalb einer offenen Transaktion würde eine Pool-Connection für die gesamte
 * String-Bau-Zeit halten, dieselbe Begründung wie `SocialPublicSitemap` KDoc dokumentiert.
 *
 * **Zeitzone**: `event.starts_at`/`.ends_at` sind `LocalDateTime` OHNE eigene TZ-Spalte -- ihre
 * Bedeutung ist Wandzeit in `TimeZone.currentSystemDefault()`, exakt wie `EventStore.list()`/
 * `EventPolicy`/`loadPublicEventView` sie beim Now-Vergleich bereits interpretieren (siehe
 * `EventTable`/`EventPublicRoutes.kt`). [icsUtc] konvertiert deshalb über
 * [kotlinx.datetime.LocalDateTime.toInstant] nach UTC -- KEIN hartes `Z`-Anhängen an die rohe
 * `LocalDateTime` (das wäre bei einer Server-Zone != UTC falsch verschoben, siehe genau diese
 * Stolperfalle in der Klassen-Historie dieser Welle).
 */
internal object EventIcsFeed {
    /**
     * DoS-Guard-Konsistenz mit dem Rest des Codebase (`EventStore.MAX_PAGE_SIZE`,
     * `SocialPublicSitemap.MAX_URLS_PER_FILE`) -- aber dieser Feed wird NICHT paginiert, ein
     * iCal-Client erwartet eine einzelne Datei. Bei Überschreitung werden die 500
     * nächststartenden Events zurückgegeben (`ORDER BY starts_at ASC LIMIT 500`), der Rest wird
     * still abgeschnitten + geloggt (siehe `EventPublicRoutes.kt`, analog Sitemap-Truncation-Log).
     */
    const val MAX_EVENTS = 500

    /**
     * Nur `visibility=PUBLIC AND status=PUBLISHED`, nur `endsAt > now` (echte Grenze, nicht
     * `>=`) -- ein Event, das GENAU jetzt endet, fällt aus dem Feed, wie
     * `EventStore.list(includePast=false)`'s Default es ebenfalls tut.
     *
     * **`limit`** defaults to [MAX_EVENTS] (this feed's own cap) -- Welle V1.4.33
     * "Veranstaltungsliste als Embed-Widget" (`EmbedEventsFeedRoutes.kt`) passes a much smaller
     * value (`EmbedEventsFeedLimits.MAX_EVENTS`, 50) for its own DoS/fan-out-guard reasoning (see
     * that file's own KDoc) -- purely additive, the iCal caller ([registerEventPublicRoutes]) keeps
     * calling this with no `limit` argument and is unaffected.
     */
    fun loadUpcomingPublicPublished(
        now: LocalDateTime,
        limit: Int = MAX_EVENTS,
    ): List<ResultRow> =
        EventTable
            .selectAll()
            .where {
                (EventTable.visibility eq EventVisibility.PUBLIC) and
                    (EventTable.status eq EventStatus.PUBLISHED) and
                    (EventTable.endsAt greater now)
            }.orderBy(EventTable.startsAt to SortOrder.ASC, EventTable.id to SortOrder.ASC)
            .limit(limit)
            .toList()

    /**
     * Follow-up wave "Wiederkehrende Veranstaltungen: iCal-Feed" -- everything [render] needs about
     * ONE `event_series` row to emit an RFC-5545-compliant RRULE master `VEVENT` for it, gathered
     * INSIDE a transaction (unlike [render] itself, which -- see class KDoc -- deliberately runs
     * outside any transaction). [eventsByOriginalStart] is EVERY still-attached `event` row of this
     * series (any status/visibility, keyed by [EventTable.seriesOriginalStart]) -- [render] needs the
     * full set, not just the publicly-visible [loadUpcomingPublicPublished] subset, to tell "this
     * occurrence was hard-deleted/cancelled/hidden -> EXDATE" apart from "this occurrence is a normal,
     * still-covered-by-the-rule instance -> no per-occurrence VEVENT at all" apart from "this
     * occurrence was individually edited/detached -> its own RECURRENCE-ID VEVENT".
     */
    data class SeriesRenderData(
        val rrule: String,
        val dtstart: LocalDateTime,
        val zone: ZoneId,
        val durationMinutes: Int,
        val eventsByOriginalStart: Map<LocalDateTime, ResultRow>,
    )

    /**
     * Loads [SeriesRenderData] for every id in [seriesIds] -- MUST run inside an open transaction
     * (same discipline [loadUpcomingPublicPublished] already establishes; [EventStore]/[EventSeriesTable]
     * access requires one). A series id with no `event_series` row anymore (should not happen --
     * `event.series_id` has no `ON DELETE` semantics that would strand it, but defense in depth
     * matches this class' general posture) is silently skipped; [render] falls back to rendering that
     * series' rows as plain standalone `VEVENT`s rather than losing them.
     */
    fun loadSeriesRenderData(seriesIds: Collection<Uuid>): Map<Uuid, SeriesRenderData> {
        val result = LinkedHashMap<Uuid, SeriesRenderData>()
        for (seriesId in seriesIds.distinct()) {
            val seriesRow = EventStore.getSeriesOrNull(seriesId) ?: continue
            val eventsByOriginalStart =
                EventStore
                    .findAllSeriesEvents(seriesId)
                    .mapNotNull { row -> row[EventTable.seriesOriginalStart]?.let { it to row } }
                    .toMap()
            result[seriesId] =
                SeriesRenderData(
                    rrule = seriesRow[EventSeriesTable.rrule],
                    dtstart = seriesRow[EventSeriesTable.dtstart],
                    zone = ZoneId.of(seriesRow[EventSeriesTable.timezone]),
                    durationMinutes = seriesRow[EventSeriesTable.durationMinutes],
                    eventsByOriginalStart = eventsByOriginalStart,
                )
        }
        return result
    }

    /**
     * `baseUrl` bereits `trimEnd('/')` -- wie überall sonst in `EventPublicRoutes.kt`/
     * `Application.kt` übergeben. Erzeugt ein valides `VCALENDAR` auch für eine leere [rows]-Liste
     * (kein `VEVENT`-Block) -- manche Kalender-Clients scheitern sonst beim Parsen eines
     * `VCALENDAR` ohne jedes `VEVENT`.
     *
     * **RRULE-Serien-Unterstützung** (Follow-up-Welle "Wiederkehrende Veranstaltungen"): a row whose
     * `series_id` has a matching entry in [seriesData] is rendered as part of that series' RRULE
     * master `VEVENT` (emitted exactly once per distinct series, the first time one of its rows is
     * encountered in [rows]) rather than as its own standalone `VEVENT` -- UNLESS the row is
     * individually detached ([EventTable.seriesDetached]), in which case it ALSO gets its own
     * `RECURRENCE-ID` exception `VEVENT` overriding that one occurrence. Occurrences the master's raw
     * RRULE expansion would produce but that are missing/cancelled/hidden in [seriesData] get an
     * `EXDATE` on the master instead, so a calendar client never regenerates a "ghost" instance of an
     * occurrence this feed would otherwise never show. A `series_id` with no entry in [seriesData]
     * (caller passed none, or [loadSeriesRenderData] found no `event_series` row) falls back to the
     * pre-existing plain per-row `VEVENT` rendering -- purely additive, no prior caller (a plain,
     * non-series event, or one passing the default empty map) sees any behavior change.
     */
    fun render(
        rows: List<ResultRow>,
        baseUrl: String,
        brandTitle: String,
        seriesData: Map<Uuid, SeriesRenderData> = emptyMap(),
    ): String {
        val host = baseUrl.substringAfter("://")
        // DbClock.nowLocalDateTime() OHNE TimeZone.UTC-Argument: icsUtc() erwartet -- wie an
        // jeder anderen Callsite (startsAt/endsAt) -- eine Wandzeit in
        // TimeZone.currentSystemDefault() und konvertiert selbst nach UTC. Ein `TimeZone.UTC`-Arg
        // hier würde eine bereits-UTC-Wandzeit ein zweites Mal (fälschlich als lokale Zeit)
        // verschieben, sobald die Server-Zone != UTC ist -- derselbe Bug-Typ, vor dem icsUtc()s
        // eigenes KDoc bei DTSTART/DTEND warnt, nur über einen anderen Mechanismus.
        val dtstamp = icsUtc(DbClock.nowLocalDateTime())
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append(foldLine("PRODID:-//Lapis Cloud//${icsEscape(brandTitle)} Events//DE"))
        sb.append("CALSCALE:GREGORIAN\r\n")
        sb.append("METHOD:PUBLISH\r\n")
        sb.append(foldLine("X-WR-CALNAME:${icsEscape(brandTitle)} – Veranstaltungen"))
        val renderedSeriesMasters = mutableSetOf<Uuid>()
        for (row in rows) {
            val seriesId = row[EventTable.seriesId]
            val data = seriesId?.let { seriesData[it] }
            if (seriesId != null && data != null) {
                if (renderedSeriesMasters.add(seriesId)) {
                    appendSeriesMaster(sb = sb, seriesId = seriesId, data = data, host = host, dtstamp = dtstamp, baseUrl = baseUrl)
                }
                if (row[EventTable.seriesDetached]) {
                    appendSeriesExceptionVevent(sb = sb, row = row, seriesId = seriesId, host = host, dtstamp = dtstamp, baseUrl = baseUrl)
                }
                // A plain, non-detached occurrence is already covered by the master's RRULE -- no
                // per-occurrence VEVENT for it.
            } else {
                appendSingleVevent(sb = sb, row = row, host = host, dtstamp = dtstamp, baseUrl = baseUrl)
            }
        }
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    private fun appendSingleVevent(
        sb: StringBuilder,
        row: ResultRow,
        host: String,
        dtstamp: String,
        baseUrl: String,
    ) {
        val eventId = row[EventTable.id]
        val slug = row[EventTable.slug]
        sb.append("BEGIN:VEVENT\r\n")
        sb.append(foldLine("UID:$eventId@$host"))
        sb.append(foldLine("DTSTAMP:$dtstamp"))
        sb.append(foldLine("DTSTART:${icsUtc(row[EventTable.startsAt])}"))
        sb.append(foldLine("DTEND:${icsUtc(row[EventTable.endsAt])}"))
        sb.append(foldLine("SUMMARY:${icsEscape(row[EventTable.title])}"))
        row[EventTable.description].takeIf { it.isNotBlank() }?.let {
            sb.append(foldLine("DESCRIPTION:${icsEscape(it)}"))
        }
        row[EventTable.locationText]?.takeIf { it.isNotBlank() }?.let {
            sb.append(foldLine("LOCATION:${icsEscape(it)}"))
        }
        sb.append(foldLine("URL:$baseUrl/veranstaltung/$slug"))
        sb.append("STATUS:CONFIRMED\r\n")
        sb.append("END:VEVENT\r\n")
    }

    /**
     * The RRULE-carrying master `VEVENT` for one series -- `UID` is `series-$seriesId@$host` (never
     * collides with a per-event `UID`, which is always a plain UUID). `SUMMARY`/`DESCRIPTION`/
     * `LOCATION`/`URL` are taken from a REPRESENTATIVE occurrence -- preferring the series' own
     * `DTSTART` occurrence (the "template" instance -- an [EventSeriesEditScope.ALL] edit updates
     * every non-detached occurrence's fields identically, so any of them would do, but the very first
     * one is the least surprising choice), falling back to any other non-detached occurrence, and
     * finally to any occurrence at all -- a series whose EVERY occurrence happens to be individually
     * detached still needs a title to render (defensive; [EventSeriesScopeEngine] never actually
     * produces that state today, since [network.lapis.cloud.shared.domain.EventSeriesEditScope.ALL]
     * scope always covers the first occurrence too, but this function makes no such assumption).
     * `EXDATE` is emitted once per raw-RRULE occurrence that is missing, cancelled, or hidden
     * (non-`PUBLIC`/non-`PUBLISHED`) in [SeriesRenderData.eventsByOriginalStart] -- see [render]'s own
     * KDoc for why that, and not skipping the date entirely, is required for a spec-compliant feed.
     */
    private fun appendSeriesMaster(
        sb: StringBuilder,
        seriesId: Uuid,
        data: SeriesRenderData,
        host: String,
        dtstamp: String,
        baseUrl: String,
    ) {
        val representative =
            data.eventsByOriginalStart[data.dtstart]
                ?: data.eventsByOriginalStart.values.firstOrNull { !it[EventTable.seriesDetached] }
                ?: data.eventsByOriginalStart.values.firstOrNull()
                ?: return
        val occurrences = RecurrenceExpander.expand(rrule = data.rrule, dtstart = data.dtstart, zone = data.zone)
        val exdates =
            occurrences.filter { occurrenceStart ->
                val ev = data.eventsByOriginalStart[occurrenceStart]
                ev == null ||
                    ev[EventTable.status] != EventStatus.PUBLISHED ||
                    ev[EventTable.visibility] != EventVisibility.PUBLIC
            }

        sb.append("BEGIN:VEVENT\r\n")
        sb.append(foldLine("UID:series-$seriesId@$host"))
        sb.append(foldLine("DTSTAMP:$dtstamp"))
        sb.append(foldLine("DTSTART:${icsUtc(data.dtstart)}"))
        sb.append(foldLine("DTEND:${icsUtc(data.dtstart.plusMinutesCompat(data.durationMinutes))}"))
        sb.append(foldLine("RRULE:${data.rrule}"))
        // RFC 5545 §3.8.5.1 permits either one EXDATE property per date or a single comma-separated
        // EXDATE listing several -- one property per date, matching this class' one-line-folding
        // helper without needing a separate comma-joining/length-budget calculation.
        exdates.forEach { occurrenceStart -> sb.append(foldLine("EXDATE:${icsUtc(occurrenceStart)}")) }
        sb.append(foldLine("SUMMARY:${icsEscape(representative[EventTable.title])}"))
        representative[EventTable.description].takeIf { it.isNotBlank() }?.let {
            sb.append(foldLine("DESCRIPTION:${icsEscape(it)}"))
        }
        representative[EventTable.locationText]?.takeIf { it.isNotBlank() }?.let {
            sb.append(foldLine("LOCATION:${icsEscape(it)}"))
        }
        sb.append(foldLine("URL:$baseUrl/veranstaltung/${representative[EventTable.slug]}"))
        sb.append("STATUS:CONFIRMED\r\n")
        sb.append("END:VEVENT\r\n")
    }

    /**
     * An individually-detached occurrence's own `VEVENT` -- same `UID` as its series' master
     * ([appendSeriesMaster]), plus `RECURRENCE-ID` set to the ORIGINAL (undetached) occurrence time
     * ([EventTable.seriesOriginalStart], not [EventTable.startsAt] -- RFC 5545 §3.8.4.4: `RECURRENCE-ID`
     * identifies WHICH instance of the recurrence set this override replaces, which is always the
     * time the master's own RRULE would have produced, regardless of how far [row]'s own `startsAt`
     * has since moved). Every other field (`DTSTART`/`DTEND`/`SUMMARY`/...) reflects [row]'s CURRENT,
     * possibly-edited values -- exactly what RFC 5545 exception semantics require a calendar client to
     * display in place of the ruled-generated instance.
     */
    private fun appendSeriesExceptionVevent(
        sb: StringBuilder,
        row: ResultRow,
        seriesId: Uuid,
        host: String,
        dtstamp: String,
        baseUrl: String,
    ) {
        val originalStart = row[EventTable.seriesOriginalStart] ?: return
        sb.append("BEGIN:VEVENT\r\n")
        sb.append(foldLine("UID:series-$seriesId@$host"))
        sb.append(foldLine("DTSTAMP:$dtstamp"))
        sb.append(foldLine("RECURRENCE-ID:${icsUtc(originalStart)}"))
        sb.append(foldLine("DTSTART:${icsUtc(row[EventTable.startsAt])}"))
        sb.append(foldLine("DTEND:${icsUtc(row[EventTable.endsAt])}"))
        sb.append(foldLine("SUMMARY:${icsEscape(row[EventTable.title])}"))
        row[EventTable.description].takeIf { it.isNotBlank() }?.let {
            sb.append(foldLine("DESCRIPTION:${icsEscape(it)}"))
        }
        row[EventTable.locationText]?.takeIf { it.isNotBlank() }?.let {
            sb.append(foldLine("LOCATION:${icsEscape(it)}"))
        }
        sb.append(foldLine("URL:$baseUrl/veranstaltung/${row[EventTable.slug]}"))
        sb.append("STATUS:CONFIRMED\r\n")
        sb.append("END:VEVENT\r\n")
    }

    /**
     * RFC 5545 §3.3.5 -- Instant über die Server-Default-Zone (siehe Klassen-KDoc "Zeitzone"),
     * formatiert als UTC (`...Z`-Suffix). NIEMALS `dt.toString() + "Z"` -- das wäre bei einer
     * Server-Zone != UTC ein falsch verschobener Zeitpunkt in jedem Kalender-Client.
     */
    private fun icsUtc(dt: LocalDateTime): String {
        val instant = dt.toInstant(TimeZone.currentSystemDefault())
        val utc = instant.toLocalDateTime(TimeZone.UTC)
        return "%04d%02d%02dT%02d%02d%02dZ".format(
            utc.year,
            utc.monthNumber,
            utc.dayOfMonth,
            utc.hour,
            utc.minute,
            utc.second,
        )
    }

    /** `LocalDateTime + N minutes` -- same idiom `EventSeriesMaterializer.plusMinutesKt` already establishes for the exact same arithmetic, duplicated here rather than shared to keep this file's dependency on the `events.series` package limited to [RecurrenceExpander] alone. */
    private fun LocalDateTime.plusMinutesCompat(minutes: Int): LocalDateTime =
        this.toJavaLocalDateTime().plusMinutes(minutes.toLong()).toKotlinLocalDateTime()

    /**
     * RFC 5545 §3.3.11 TEXT escaping -- backslash, semicolon, comma, dann literale Zeilenumbrüche
     * -> `\n`. Reihenfolge ist load-bearing: Backslash MUSS zuerst escaped werden, sonst werden
     * bereits erzeugte `\,`/`\;`/`\n`-Escapes selbst nochmal escaped (Doppel-Escaping-Bug).
     */
    private fun icsEscape(raw: String): String =
        raw
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")

    /**
     * RFC 5545 §3.1 line folding: Zeilen > 75 Oktette (UTF-8-Bytes, nicht Codepoints) werden per
     * CRLF + genau einem führenden Leerzeichen fortgesetzt. Die Byte-Clamp-Schleife verhindert
     * einen Split MITTEN in einer Mehrbyte-UTF-8-Sequenz (ein naiver `chunked(75)` auf dem
     * Kotlin-`String` würde bei Umlauten/Emoji sowohl die 75-Byte-Grenze verletzen als auch
     * kaputte Bytes erzeugen können).
     */
    private fun foldLine(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return line + "\r\n"
        val sb = StringBuilder()
        var rest = line
        var first = true
        while (rest.isNotEmpty()) {
            val chunkLimit = if (first) 75 else 74
            var end = minOf(chunkLimit, rest.length)
            while (end > 0 && rest.substring(0, end).toByteArray(Charsets.UTF_8).size > chunkLimit) end--
            if (end == 0) end = 1 // pathological single-char > chunkLimit bytes -- never split zero chars, avoid an infinite loop
            sb.append(if (first) "" else " ").append(rest.substring(0, end)).append("\r\n")
            rest = rest.substring(end)
            first = false
        }
        return sb.toString()
    }
}
