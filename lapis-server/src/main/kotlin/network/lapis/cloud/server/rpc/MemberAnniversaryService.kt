package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnniversaryCalendar
import network.lapis.cloud.shared.domain.AnniversaryEntryDto
import network.lapis.cloud.shared.domain.AnniversaryEntryKind
import network.lapis.cloud.shared.domain.MemberAnniversaryOverviewDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.IMemberAnniversaryService
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
    private val clock: () -> LocalDate = { DbClock.nowLocalDateTime().date },
) : IMemberAnniversaryService {
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

        val rows =
            transaction {
                MemberTable
                    .selectAll()
                    .where {
                        (MemberTable.status inList MemberStatusSets.ANNIVERSARY_ELIGIBLE) and
                            MemberTable.anonymizedAt.isNull()
                    }.map { row ->
                        RawMember(
                            id = row[MemberTable.id],
                            displayName = row[MemberTable.displayName],
                            status = row[MemberTable.status],
                            joinedAt = row[MemberTable.joinedAt],
                            dateOfBirth = row[MemberTable.dateOfBirth],
                        )
                    }
            }

        val eligibleMemberCount = rows.size
        val membersWithoutDateOfBirth = rows.count { it.dateOfBirth == null }

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

        entries.sortWith(
            compareBy<AnniversaryEntryDto> { it.occursOn }
                .thenBy { it.kind }
                .thenBy { it.memberDisplayName }
                .thenBy { it.memberId },
        )

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
}

private data class RawMember(
    val id: Uuid,
    val displayName: String,
    val status: MemberStatus,
    val joinedAt: LocalDate,
    val dateOfBirth: LocalDate?,
)
