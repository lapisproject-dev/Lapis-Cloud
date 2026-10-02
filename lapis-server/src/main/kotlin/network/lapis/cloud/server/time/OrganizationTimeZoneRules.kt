package network.lapis.cloud.server.time

import kotlinx.datetime.TimeZone
import network.lapis.cloud.shared.rpc.BadRequestException
import java.time.ZoneId

/**
 * Validation of an organization time-zone id (V1.9.38). An ADMIN-supplied string ends up in the database,
 * in `SessionInfoDto` and (through the client) in the DOM, so it is checked against an allow-list rather
 * than parsed leniently.
 *
 * Accepted: `UTC`, or a region-style IANA id (`Europe/Berlin`, `America/Argentina/Buenos_Aires`) that the
 * running JVM knows. Rejected: fixed offsets (`+02:00`, no DST), legacy short ids (`CET`, `EST5EDT`),
 * `Etc/GMT+n` (the sign is inverted and misleading), the `SystemV` family, anything with path or control
 * characters, and anything longer than [MAX_LENGTH].
 */
object OrganizationTimeZoneRules {
    const val DEFAULT_ZONE_ID = "Europe/Berlin"
    const val MAX_LENGTH = 64

    private val SHAPE = Regex("^[A-Za-z]+(?:/[A-Za-z0-9_+\\-]+){1,2}$")

    /** `true` iff [raw] is exactly an acceptable zone id. No trimming: a trailing newline is invalid. */
    fun isValid(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > MAX_LENGTH) return false
        if (raw == "UTC") return true
        if (!SHAPE.matches(raw)) return false
        if (raw.startsWith("SystemV/") || raw.startsWith("Etc/")) return false
        return raw in ZoneId.getAvailableZoneIds()
    }

    /** @throws BadRequestException (message `Unbekannte Zeitzone`) if [raw] is not acceptable. */
    fun validateZoneId(raw: String): TimeZone {
        val trimmed = raw.trim()
        if (trimmed != raw || !isValid(trimmed)) throw BadRequestException("Unbekannte Zeitzone")
        return TimeZone.of(trimmed)
    }

    /** All ids [isValid] accepts, sorted. About 400 entries. */
    fun availableZoneIds(): List<String> = ZoneId.getAvailableZoneIds().filter { isValid(it) }.sorted()
}
