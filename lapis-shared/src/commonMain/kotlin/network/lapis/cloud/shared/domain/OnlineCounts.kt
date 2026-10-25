package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V1.9.215 -- head counts of signed-in people, split by group. Only counts cross the wire: never a member id, a name or an e-mail address.
 * Invariant: `total == members + friends + guests + other`.
 */
@Serializable
data class OnlineGroupCountsDto(
    val total: Int,
    val members: Int,
    val friends: Int,
    val guests: Int,
    val other: Int,
)

/**
 * V1.9.215 -- the "signed in right now" tile of the admin dashboard. [active] counts people with a valid session that was used within the last
 * [activeWindowMinutes]; [signedIn] counts people with any valid session (it stays valid up to [sessionTtlHours]). [computedAt] is a system
 * timestamp (time class A: UTC, shown in the organization zone).
 */
@Serializable
data class OnlineCountsDto(
    val active: OnlineGroupCountsDto,
    val signedIn: OnlineGroupCountsDto,
    val activeWindowMinutes: Int = 15,
    val sessionTtlHours: Int = 8,
    val computedAt: LocalDateTime,
)
