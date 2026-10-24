package network.lapis.cloud.server.db

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The one fixed point in time every relative date of the staging/demo seed is computed from (Welle V1.9.63).
 *
 * Captured exactly once per seed run, so the whole dataset is internally consistent: "daysAgo = 30" means the same
 * day for the meeting, its agenda, its resolution and the contribution paid the same week. The three time classes of
 * `docs/architecture/time-and-timezones.adoc` stay apart:
 * - class A (UTC system stamps, `createdAt`, `paidAt`, `votedAt`, ...) -> [now], [utcAt]
 * - class B (wall clock of the organization zone, `scheduledAt`, `startsAt`, `closesAt`, ...) -> [wallNow], [wallAt]
 * - class D (calendar dates, `joinedAt`, `dueDate`, `periodStart`, ...) -> [today], [dayAgo]
 */
internal class SeedClock(
    val now: LocalDateTime,
    val orgZone: TimeZone,
) {
    /** Class B "now": the wall clock of the organization zone. */
    val wallNow: LocalDateTime = ServerClock.systemToWall(utc = now, orgZone = orgZone)

    /** Class D "today" in the organization zone. */
    val today: LocalDate = wallNow.date

    /** The calendar day [days] before [today] (negative: in the future). */
    fun dayAgo(days: Int): LocalDate = today.minus(days, DateTimeUnit.DAY)

    /**
     * Class A: the UTC stamp of [hour]:00 organization wall-clock time, [daysAgo] days before [today].
     * Daylight-saving safe (goes through `toInstant(orgZone)`), never later than [now].
     */
    fun utcAt(
        daysAgo: Int,
        hour: Int = 10,
    ): LocalDateTime {
        val wall = dayAgo(daysAgo).atTime(LocalTime(hour, 0))
        val utc = wall.toInstant(orgZone).toLocalDateTime(TimeZone.UTC)
        return minOf(utc, now)
    }

    /** Class B: [hour]:00 wall clock, [daysFromToday] days after [today] (negative: before). */
    fun wallAt(
        daysFromToday: Int,
        hour: Int,
    ): LocalDateTime = today.plus(daysFromToday, DateTimeUnit.DAY).atTime(LocalTime(hour, 0))

    /** Class A stamp of a past wall-clock instant (e.g. the start of a meeting held in the past). */
    fun wallToUtc(wall: LocalDateTime): LocalDateTime = minOf(wall.toInstant(orgZone).toLocalDateTime(TimeZone.UTC), now)

    /** [base] moved by [byDays] days and [byMinutes] minutes (pure UTC arithmetic on a class-A stamp). */
    fun utcShift(
        base: LocalDateTime,
        byDays: Int = 0,
        byMinutes: Int = 0,
    ): LocalDateTime = (base.toInstant(TimeZone.UTC) + byDays.days + byMinutes.minutes).toLocalDateTime(TimeZone.UTC)

    /** A wall-clock [base] moved forward by [hours] (plain arithmetic on the wall clock, no zone involved). */
    fun shiftWall(
        base: LocalDateTime,
        hours: Int,
    ): LocalDateTime = (base.toInstant(TimeZone.UTC) + hours.hours).toLocalDateTime(TimeZone.UTC)

    /** The first instant of [date] in the organization zone, as a UTC stamp. */
    fun startOfDayUtc(date: LocalDate): LocalDateTime = date.atStartOfDayIn(orgZone).toLocalDateTime(TimeZone.UTC)

    companion object {
        fun capture(orgZone: TimeZone): SeedClock = SeedClock(now = DbClock.nowLocalDateTime(), orgZone = orgZone)
    }
}

/**
 * The fictitious people the later seed areas need to reference. Resolved once in [StagingSeedData] from the members it
 * inserted, so the other seed objects never touch the member table.
 */
internal class SeedActors(
    val admin: SeedMemberRef,
    val board: SeedMemberRef,
    val treasurer: SeedMemberRef,
    val members: List<SeedMemberRef>,
) {
    val adminActor: CurrentMember = CurrentMember(memberId = admin.id, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
    val treasurerActor: CurrentMember = CurrentMember(memberId = treasurer.id, role = AccountRole.TREASURER, status = MemberStatus.ACTIVE)
    val boardActor: CurrentMember = CurrentMember(memberId = board.id, role = AccountRole.BOARD, status = MemberStatus.ACTIVE)

    /** Members whose CURRENT status is ACTIVE, in seed order. */
    val active: List<SeedMemberRef> get() = members.filter { it.status == MemberStatus.ACTIVE }
}

internal data class SeedMemberRef(
    val id: Uuid,
    val displayName: String,
    val status: MemberStatus,
    val accountRole: AccountRole?,
    val tierId: Uuid,
    val chapterId: Uuid?,
    /** Days before today of the first ACTIVE step, null if the person never was an active member. */
    val activeSinceDaysAgo: Int?,
    /** Days before today the member left ACTIVE again (withdrawn/deceased), null while still active. */
    val leftDaysAgo: Int?,
)

/** Fixed-id namespaces of the staging seed. All inside `0000000a-...`, disjoint from DevSeedData and the migration sentinels. */
internal object SeedIds {
    fun member(n: Int): Uuid = Uuid.parse("0000000a-0000-0000-0000-1000000000%02x".format(n))

    fun finance(n: Int): Uuid = Uuid.parse("0000000a-0000-0000-0000-2000000000%02x".format(n))

    fun governance(n: Int): Uuid = Uuid.parse("0000000a-0000-0000-0000-3000000000%02x".format(n))

    fun community(n: Int): Uuid = Uuid.parse("0000000a-0000-0000-0000-4000000000%02x".format(n))

    /** Tiers (n in 1..0x0f) and ledger accounts (n from 0x10). */
    fun base(n: Int): Uuid = Uuid.parse("0000000a-0000-0000-0000-5000000000%02x".format(n))

    /** LTR top-up (V1.9.197): 16-bit index; 0x0000-0x0fff mints, 0x1000+ peer_transfer, 0x2000+ OUT entry, 0x3000+ IN entry. */
    fun ltr(n: Int): Uuid {
        require(n in 0..0xffff) { "ltr index out of range: $n" }
        return Uuid.parse("0000000a-0000-0000-0000-60000000%04x".format(n))
    }

    /**
     * Top-up part namespaces (V1.9.205): 16-bit index per part inside `0000000a-0000-0000-0000-PP000000NNNN`, PP in 0x70..0x77, disjoint from
     * every namespace above. Ranges inside a part: 0x0000-0x0fff root entities, 0x1000+ ledger rows, 0x2000+ children, 0x3000+ further children.
     * Indices are fixed per plan entry (never the list position at run time); plans only grow at the end (pinned by `StagingSeedPartsPlanTest`).
     */
    private fun part(
        prefix: Int,
        n: Int,
    ): Uuid {
        require(n in 0..0xffff) { "part index out of range: $n" }
        return Uuid.parse("0000000a-0000-0000-0000-%02x000000%04x".format(prefix, n))
    }

    fun crowdfunding(n: Int): Uuid = part(prefix = 0x70, n = n)

    fun auctions(n: Int): Uuid = part(prefix = 0x71, n = n)

    fun social(n: Int): Uuid = part(prefix = 0x72, n = n)

    fun politicians(n: Int): Uuid = part(prefix = 0x73, n = n)

    fun governanceTopUp(n: Int): Uuid = part(prefix = 0x74, n = n)

    fun tenders(n: Int): Uuid = part(prefix = 0x75, n = n)

    fun events(n: Int): Uuid = part(prefix = 0x76, n = n)

    fun oracle(n: Int): Uuid = part(prefix = 0x77, n = n)
}
