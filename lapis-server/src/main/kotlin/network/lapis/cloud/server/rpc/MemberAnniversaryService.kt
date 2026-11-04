package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.number
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnniversaryCalendar
import network.lapis.cloud.shared.domain.AnniversaryEntryDto
import network.lapis.cloud.shared.domain.AnniversaryEntryKind
import network.lapis.cloud.shared.domain.MemberAnniversaryOverviewDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.TodaysAnniversariesDto
import network.lapis.cloud.shared.domain.TodaysAnniversaryDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.IMemberAnniversaryService
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.datetime.day
import org.jetbrains.exposed.v1.datetime.month
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}
private val ANNIVERSARY_READ_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * Welle V1.4.4.2 "Geburtstage & Jubiläen" -- see [IMemberAnniversaryService] and
 * [MemberAnniversaryOverviewDto] for the design. [clock] injectable for tests, same
 * constructor-default pattern as `SepaBatchPoller`/`DunningPoller`.
 */
class MemberAnniversaryService(
    private val call: ApplicationCall,
    private val clock: () -> LocalDate = { OrganizationTimeZone.today() },
    private val todayRateLimiter: FederationInboxRateLimiter = DEFAULT_TODAY_LIMITER,
) : IMemberAnniversaryService {
    companion object {
        /** Calls per member and minute of [getTodaysAnniversaries]. */
        const val TODAY_MAX_CALLS = 12

        /** Entries the dashboard card can receive at most; the rest is only counted in `totalCount`. */
        const val TODAY_CAP = 50

        /** Per-instance fallback for tests; production passes ONE shared limiter (the service is rebuilt per call). */
        internal val DEFAULT_TODAY_LIMITER = FederationInboxRateLimiter(maxRequests = TODAY_MAX_CALLS, window = 1.minutes)
    }

    override suspend fun getUpcomingAnniversaries(windowDays: Int): MemberAnniversaryOverviewDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ANNIVERSARY_READ_ROLES)
        if (windowDays !in 1..AnniversaryCalendar.MAX_WINDOW_DAYS) {
            throw BadRequestException(
                "windowDays must be in 1..${AnniversaryCalendar.MAX_WINDOW_DAYS}, got $windowDays",
            )
        }
        val today = clock()
        val through = AnniversaryCalendar.windowEnd(today = today, windowDays = windowDays)

        val rows = transaction { loadEligibleMembers() }

        val eligibleMemberCount = rows.size
        val membersWithoutDateOfBirth = rows.count { it.dateOfBirth == null }

        val entries = entriesFor(rows = rows, today = today, through = through).sortedWith(ENTRY_ORDER)

        logger.info {
            "member anniversary overview read: actor=${current.memberId} actorRole=${current.role} " +
                "windowDays=$windowDays birthdayHits=${entries.count { it.kind == AnniversaryEntryKind.BIRTHDAY }} " +
                "anniversaryHits=${entries.count { it.kind == AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY }}"
        }

        return MemberAnniversaryOverviewDto(
            windowDays = windowDays,
            from = today,
            through = through,
            entries = entries,
            eligibleMemberCount = eligibleMemberCount,
            membersWithoutDateOfBirth = membersWithoutDateOfBirth,
        )
    }

    override suspend fun getTodaysAnniversaries(): TodaysAnniversariesDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ANNIVERSARY_READ_ROLES)
        if (!todayRateLimiter.checkAndRecord("anniversary:today:${current.memberId}")) throw RateLimitedException()
        val today = clock()
        val all =
            entriesFor(rows = transaction { loadTodaysCandidates(today) }, today = today, through = today)
                .filter { it.occursOn == today }
                .sortedWith(ENTRY_ORDER)
        // No name in the log, no audit row, no cache.
        logger.info {
            "member anniversaries today read: actor=${current.memberId} actorRole=${current.role} " +
                "birthdayHits=${all.count { it.kind == AnniversaryEntryKind.BIRTHDAY }} " +
                "anniversaryHits=${all.count { it.kind == AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY }}"
        }
        return TodaysAnniversariesDto(entries = all.take(TODAY_CAP).map { it.toToday() }, totalCount = all.size)
    }

    private fun loadEligibleMembers(): List<RawMember> =
        MemberTable
            .selectAll()
            .where {
                (MemberTable.status inList MemberStatusSets.ANNIVERSARY_ELIGIBLE) and
                    MemberTable.anonymizedAt.isNull()
            }.map { it.toRaw() }
}

/**
 * SQL pre-filter for "today": only members whose date of birth or join date falls on today's month/day. On 28 February of a
 * non-leap year the 29 February dates are included too (the calendar moves them to the 28th); the calendar itself decides
 * whether that case applies, no second leap-year rule lives here. Candidates are re-checked by [AnniversaryCalendar] afterwards.
 * No index supports this (an expression index would need a migration): the database still scans `member`, but only about
 * N/365 rows are transferred.
 */
internal fun loadTodaysCandidates(today: LocalDate): List<RawMember> {
    val leapDayShift =
        today.month == Month.FEBRUARY &&
            today.day == 28 &&
            AnniversaryCalendar.nextOccurrence(anniversary = LocalDate(2000, 2, 29), today = today) == today
    val m = today.month.number
    val d = today.day

    fun <T : LocalDate?> matches(date: ExpressionWithColumnType<T>): Op<Boolean> {
        val plain = (date.month() eq m) and (date.day() eq d)
        return if (leapDayShift) plain or ((date.month() eq 2) and (date.day() eq 29)) else plain
    }
    return MemberTable
        .selectAll()
        .where {
            (MemberTable.status inList MemberStatusSets.ANNIVERSARY_ELIGIBLE) and
                MemberTable.anonymizedAt.isNull() and
                (matches(MemberTable.dateOfBirth) or matches(MemberTable.joinedAt))
        }.map { it.toRaw() }
}

private fun ResultRow.toRaw() =
    RawMember(
        id = this[MemberTable.id],
        displayName = this[MemberTable.displayName],
        status = this[MemberTable.status],
        joinedAt = this[MemberTable.joinedAt],
        dateOfBirth = this[MemberTable.dateOfBirth],
    )

private val ENTRY_ORDER =
    compareBy<AnniversaryEntryDto> { it.occursOn }
        .thenBy { it.kind }
        .thenBy { it.memberDisplayName }
        .thenBy { it.memberId }

private fun AnniversaryEntryDto.toToday() =
    TodaysAnniversaryDto(memberId = memberId, memberDisplayName = memberDisplayName, kind = kind, years = years, emphasis = emphasis)

private fun entriesFor(
    rows: List<RawMember>,
    today: LocalDate,
    through: LocalDate,
): List<AnniversaryEntryDto> {
    val entries = mutableListOf<AnniversaryEntryDto>()
    for (row in rows) {
        row.dateOfBirth?.let { dob ->
            val occursOn = AnniversaryCalendar.nextOccurrence(anniversary = dob, today = today)
            if (occursOn <= through) {
                val years = AnniversaryCalendar.yearsBetween(anniversary = dob, onDate = occursOn)
                if (years > 0) {
                    entries +=
                        AnniversaryEntryDto(
                            kind = AnniversaryEntryKind.BIRTHDAY,
                            memberId = row.id.toString(),
                            memberDisplayName = row.displayName,
                            memberStatus = row.status,
                            occursOn = occursOn,
                            originalDate = dob,
                            shiftedFromLeapDay = AnniversaryCalendar.isLeapDayAnniversary(dob),
                            years = years,
                            emphasis = AnniversaryCalendar.milestoneEmphasis(years),
                        )
                }
            }
        }
        val occursOn = AnniversaryCalendar.nextOccurrence(anniversary = row.joinedAt, today = today)
        if (occursOn <= through) {
            val years = AnniversaryCalendar.yearsBetween(anniversary = row.joinedAt, onDate = occursOn)
            if (years > 0 && AnniversaryCalendar.isMilestoneYear(years)) {
                entries +=
                    AnniversaryEntryDto(
                        kind = AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY,
                        memberId = row.id.toString(),
                        memberDisplayName = row.displayName,
                        memberStatus = row.status,
                        occursOn = occursOn,
                        originalDate = row.joinedAt,
                        shiftedFromLeapDay = AnniversaryCalendar.isLeapDayAnniversary(row.joinedAt),
                        years = years,
                        emphasis = AnniversaryCalendar.milestoneEmphasis(years),
                    )
            }
        }
    }
    return entries
}

internal data class RawMember(
    val id: Uuid,
    val displayName: String,
    val status: MemberStatus,
    val joinedAt: LocalDate,
    val dateOfBirth: LocalDate?,
)
