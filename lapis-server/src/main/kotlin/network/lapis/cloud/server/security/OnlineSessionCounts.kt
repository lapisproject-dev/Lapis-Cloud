package network.lapis.cloud.server.security

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OnlineGroupCountsDto
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal enum class OnlineGroup { MEMBERS, FRIENDS, GUESTS, OTHER }

/**
 * V1.9.215 -- head counts of signed-in people (the admin dashboard tile). Counts only; nothing new is stored.
 *
 * **Signed in** = a person with at least one session that would authenticate right now: same triple join as [SessionStore.resolve]
 * (`session` + `member` + `account`), `revoked_at IS NULL` and `expires_at > now` (exclusive). **Active** additionally needs
 * `last_used_at >= now - [ACTIVE_WINDOW]` (inclusive). [SessionStore.resolve] writes `last_used_at` on EVERY authenticated request (no throttling),
 * so the window is exact; a session never used since login (`last_used_at IS NULL`) is signed in but not active.
 * A session lives up to [SessionStore.SESSION_TTL] (8 hours), hence "signed in" can exceed "active".
 *
 * A person with several sessions counts once (`COUNT(DISTINCT member_id)`). OIDC guests are ordinary `member` rows (status GUEST) and counted.
 * **Not counted**: event guests without an account (`evg-` identities, LiveKit token only, no session row), API keys, and the `X-Member-Id`
 * test fallback (no session row).
 */
internal object OnlineSessionCounts {
    val ACTIVE_WINDOW: Duration = 15.minutes

    /** The ONE place that assigns a member status to a group. No `else`: a new [MemberStatus] must be decided here. */
    internal fun groupOf(status: MemberStatus): OnlineGroup =
        when (status) {
            MemberStatus.ACTIVE -> OnlineGroup.MEMBERS
            MemberStatus.FRIEND -> OnlineGroup.FRIENDS
            MemberStatus.GUEST -> OnlineGroup.GUESTS
            MemberStatus.APPLICATION,
            MemberStatus.WITHDRAWN,
            MemberStatus.REJECTED,
            MemberStatus.DONOR,
            MemberStatus.DECEASED,
            -> OnlineGroup.OTHER
        }

    /** Returns (active, signedIn) at [now] (UTC, DB precision). */
    internal fun count(now: LocalDateTime): Pair<OnlineGroupCountsDto, OnlineGroupCountsDto> {
        val cutoff = (now.toInstant(TimeZone.UTC) - ACTIVE_WINDOW).toLocalDateTime(TimeZone.UTC)
        return transaction {
            val signedIn = countGroups(now = now, cutoff = null)
            val active = countGroups(now = now, cutoff = cutoff)
            active to signedIn
        }
    }

    private fun countGroups(
        now: LocalDateTime,
        cutoff: LocalDateTime?,
    ): OnlineGroupCountsDto {
        val people = SessionTable.memberId.countDistinct()
        val perGroup = mutableMapOf<OnlineGroup, Int>()
        (SessionTable innerJoin MemberTable innerJoin AccountTable)
            .select(MemberTable.status, people)
            .where {
                val valid = SessionTable.revokedAt.isNull() and (SessionTable.expiresAt greater now)
                if (cutoff == null) valid else valid and (SessionTable.lastUsedAt greaterEq cutoff)
            }.groupBy(MemberTable.status)
            .forEach { row ->
                val group = groupOf(row[MemberTable.status])
                perGroup[group] = (perGroup[group] ?: 0) + row[people].toInt()
            }
        val members = perGroup[OnlineGroup.MEMBERS] ?: 0
        val friends = perGroup[OnlineGroup.FRIENDS] ?: 0
        val guests = perGroup[OnlineGroup.GUESTS] ?: 0
        val other = perGroup[OnlineGroup.OTHER] ?: 0
        return OnlineGroupCountsDto(
            total = members + friends + guests + other,
            members = members,
            friends = friends,
            guests = guests,
            other = other,
        )
    }
}
