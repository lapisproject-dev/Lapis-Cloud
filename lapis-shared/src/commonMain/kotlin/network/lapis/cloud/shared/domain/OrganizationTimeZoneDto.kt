package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * V1.9.38 "Einheitliche Zeitzonen" -- the organization's IANA zone id plus the allow-list of ids the
 * server accepts (so client and server agree on the choices even if tzdata differs between the JVM and
 * the browser's js-joda data). Class-B wall-clock values are typed in this zone, class-A system
 * timestamps (UTC) are displayed in it. See `docs/architecture/time-and-timezones.adoc`.
 */
@Serializable
data class OrganizationTimeZoneDto(
    val zoneId: String,
    val availableZoneIds: List<String>,
)
