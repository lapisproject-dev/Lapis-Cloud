package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Span
import io.kvision.html.span
import io.kvision.i18n.gettext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * V1.9.38 "Einheitliche Zeitzonen" -- the ONE place where the client converts between zones. See
 * `docs/architecture/time-and-timezones.adoc` for the model; the short version:
 *
 *  - **Class A (system timestamps)** arrive as zone-less [LocalDateTime]s in UTC (`createdAt`, `postedAt`, `expiresAt`, ...).
 *    They are DISPLAYED in the organization's zone: use [formatSystemDateTime], [systemDateTimeToken], [systemDateTimeSpan],
 *    [systemTimestampColumn] ... below, never the plain `format*`/`*Token`/`*Column` of `DateTime.kt`.
 *  - **Class B (wall-clock typed in by a person)** (`startsAt`, `closesAt`, `scheduledAt`, ...) are shown exactly as stored:
 *    `DateTime.kt` unchanged, NEVER converted here.
 *  - **Class D (calendar dates)** are never converted.
 *
 * The tokens returned by the `system*Token` functions carry the value ALREADY converted to the organization's wall-clock, so
 * they follow a UI-language switch exactly like the plain tokens (the conversion happens when the token is built, the
 * language-specific rendering when it is resolved). A zone suffix is deliberately NOT appended to a token (rule T2:
 * a token is never concatenated into another string); only [formatSystemDateTimeWithZone] -- a finished string -- carries one.
 *
 * "Now" in the browser is also read here ([organizationNow], [organizationToday]) instead of from the browser's own zone, so a
 * member whose laptop is in another country still sees and enters times in the organization's zone (one clock, one zone).
 *
 * **Zone data**: kotlinx-datetime on Kotlin/JS knows only UTC and the browser's zone until the npm package `@js-joda/timezone`
 * is loaded. Kotlin/JS initializes top-level properties lazily, so the module is referenced from [ensureZoneData], which every
 * public function of this file calls -- without that, `TimeZone.of("Europe/Berlin")` would throw in the browser while every JVM
 * test stays green.
 */
@JsModule("@js-joda/timezone")
@JsNonModule
external object JsJodaTimeZoneModule

private val jsJodaTimeZone: Any = JsJodaTimeZoneModule

/** Touches the `@js-joda/timezone` module so it is certainly loaded before the first `TimeZone.of(<region id>)`. */
internal fun ensureZoneData(): Any = jsJodaTimeZone

internal const val DEFAULT_ORGANIZATION_ZONE_ID = "Europe/Berlin"

/** The organization's IANA zone id as delivered by `SessionInfoDto.organizationTimeZone`; set by [AppState.setSession]. */
object OrganizationTime {
    var zoneId: String = DEFAULT_ORGANIZATION_ZONE_ID
        internal set
}

/** [zoneId] resolved against the loaded zone data; anything unknown or malformed falls back to UTC (never throws). */
internal fun resolveZone(zoneId: String): TimeZone {
    ensureZoneData()
    return runCatching { TimeZone.of(zoneId) }.getOrDefault(TimeZone.UTC)
}

/** A class-A UTC wall-clock as the organization's wall-clock. */
fun toOrganizationZone(
    utc: LocalDateTime,
    zoneId: String = OrganizationTime.zoneId,
): LocalDateTime = utc.toInstant(TimeZone.UTC).toLocalDateTime(resolveZone(zoneId))

/** The calendar date of a class-A UTC stamp in the organization's zone (for grouping a feed by day). */
fun systemDate(utc: LocalDateTime): LocalDate = toOrganizationZone(utc).date

fun formatSystemDateTime(utc: LocalDateTime): String = formatDateTime(toOrganizationZone(utc))

fun formatSystemTimestamp(utc: LocalDateTime): String = formatTimestamp(toOrganizationZone(utc))

/**
 * Date + time + zone abbreviation, e.g. `02.10.2026, 03:48 MESZ` -- ONLY for the one place where "until when" matters
 * across zones (the session expiry on the dashboard) and for the zone preview in the admin settings. A finished string:
 * it freezes at the language of the moment, like every `gettext`-embedded value.
 */
fun formatSystemDateTimeWithZone(utc: LocalDateTime): String {
    val zone = resolveZone(OrganizationTime.zoneId)
    val instant = utc.toInstant(TimeZone.UTC)
    return formatDateTime(instant.toLocalDateTime(zone)) + NBSP + zoneAbbreviation(OrganizationTime.zoneId, instant)
}

internal fun systemDateTimeToken(utc: LocalDateTime): String = dateTimeToken(toOrganizationZone(utc))

internal fun systemTimestampToken(utc: LocalDateTime): String = timestampToken(toOrganizationZone(utc))

/** The canonical ISO UTC form (`2026-10-02T01:48:00Z`), used as the hover `title` of audit/journal timestamps. */
fun isoUtcTitle(utc: LocalDateTime): String = utc.toInstant(TimeZone.UTC).toString()

fun Container.systemDateTimeSpan(
    utc: LocalDateTime,
    utcTitle: Boolean = false,
): Span =
    span(systemDateTimeToken(utc)) {
        addCssClass(TABULAR_NUMS_CLASS)
        if (utcTitle) title = isoUtcTitle(utc)
    }

fun Container.systemTimestampSpan(
    utc: LocalDateTime,
    utcTitle: Boolean = false,
): Span =
    span(systemTimestampToken(utc)) {
        addCssClass(TABULAR_NUMS_CLASS)
        if (utcTitle) title = isoUtcTitle(utc)
    }

/** The class-A sibling of [dateTimeColumn]: the cell is the token of the value converted to the organization's zone. */
fun <R> systemDateTimeColumn(
    title: String,
    numeric: Boolean = true,
    primary: Boolean = false,
    sortKey: String? = null,
    cssClasses: String? = null,
    value: (R) -> LocalDateTime?,
): DataColumn<R> =
    textColumn<R>(
        title = title,
        numeric = numeric,
        primary = primary,
        sortKey = sortKey,
        cssClasses = listOfNotNull(TABULAR_NUMS_CLASS, cssClasses).joinToString(" "),
    ) { row -> value(row)?.let { trusted(systemDateTimeToken(it)) } ?: "–" }

/** The class-A sibling of [timestampColumn]; with [utcTitle] the cell also carries the ISO UTC form as its hover title (audit log, journal). */
fun <R> systemTimestampColumn(
    title: String,
    numeric: Boolean = true,
    primary: Boolean = false,
    sortKey: String? = null,
    cssClasses: String? = null,
    utcTitle: Boolean = false,
    value: (R) -> LocalDateTime?,
): DataColumn<R> =
    DataColumn(
        title = title,
        numeric = numeric,
        primary = primary,
        sortKey = sortKey,
        cell = { container, row ->
            val utc = value(row)
            val span = container.cellText(if (utc == null) "–" else trusted(systemTimestampToken(utc)), cssClasses)
            span.addCssClass(TABULAR_NUMS_CLASS)
            if (utcTitle && utc != null) span.title = isoUtcTitle(utc)
        },
    )

/** "Now" as the organization's wall-clock (class B frame) -- replaces the browser-zone `Clock.System.now().toLocalDateTime(...)`. */
fun organizationNow(): LocalDateTime = Clock.System.now().toLocalDateTime(resolveZone(OrganizationTime.zoneId))

/** Today's calendar date in the organization's zone. */
fun organizationToday(): LocalDate = organizationNow().date

/**
 * The zone's short name at [at]. The Central European family shows `MEZ`/`MESZ` (translated); everything else the neutral
 * `UTC+4` / `UTC+5:30` / `UTC-3`; an unknown id is `UTC`.
 */
fun zoneAbbreviation(
    zoneId: String,
    at: Instant,
): String {
    val zone = resolveZone(zoneId)
    if (zone == TimeZone.UTC) return "UTC"
    val offset = zone.offsetAt(at)
    if (zoneId in CENTRAL_EUROPEAN_ZONES) {
        when (offset.totalSeconds) {
            3600 -> return gettext("MEZ")
            7200 -> return gettext("MESZ")
        }
    }
    return utcOffsetLabel(offset)
}

internal fun utcOffsetLabel(offset: UtcOffset): String {
    val total = offset.totalSeconds
    if (total == 0) return "UTC"
    val sign = if (total < 0) "-" else "+"
    val abs = if (total < 0) -total else total
    val hours = abs / 3600
    val minutes = (abs % 3600) / 60
    val minutePart = if (minutes == 0) "" else ":${pad2(minutes)}"
    return "UTC$sign$hours$minutePart"
}

private val CENTRAL_EUROPEAN_ZONES =
    setOf(
        "Europe/Berlin",
        "Europe/Vienna",
        "Europe/Zurich",
        "Europe/Paris",
        "Europe/Amsterdam",
        "Europe/Brussels",
        "Europe/Luxembourg",
        "Europe/Rome",
        "Europe/Madrid",
        "Europe/Copenhagen",
        "Europe/Oslo",
        "Europe/Stockholm",
        "Europe/Prague",
        "Europe/Warsaw",
        "Europe/Budapest",
        "Europe/Bratislava",
        "Europe/Ljubljana",
        "Europe/Zagreb",
    )
