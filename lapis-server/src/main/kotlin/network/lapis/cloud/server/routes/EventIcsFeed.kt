package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.selectAll

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
     */
    fun loadUpcomingPublicPublished(now: LocalDateTime): List<ResultRow> =
        EventTable
            .selectAll()
            .where {
                (EventTable.visibility eq EventVisibility.PUBLIC) and
                    (EventTable.status eq EventStatus.PUBLISHED) and
                    (EventTable.endsAt greater now)
            }.orderBy(EventTable.startsAt to SortOrder.ASC, EventTable.id to SortOrder.ASC)
            .limit(MAX_EVENTS)
            .toList()

    /**
     * `baseUrl` bereits `trimEnd('/')` -- wie überall sonst in `EventPublicRoutes.kt`/
     * `Application.kt` übergeben. Erzeugt ein valides `VCALENDAR` auch für eine leere [rows]-Liste
     * (kein `VEVENT`-Block) -- manche Kalender-Clients scheitern sonst beim Parsen eines
     * `VCALENDAR` ohne jedes `VEVENT`.
     */
    fun render(
        rows: List<ResultRow>,
        baseUrl: String,
        brandTitle: String,
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
        for (row in rows) {
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
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
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
