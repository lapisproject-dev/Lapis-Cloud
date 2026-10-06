package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.member.CountPeriod
import network.lapis.cloud.server.member.MemberCountAggregation
import network.lapis.cloud.server.member.MemberStatusHistorySource
import network.lapis.cloud.server.member.StatusDelta
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberCountHistoryDto
import network.lapis.cloud.shared.domain.MemberCountHistoryQuery
import network.lapis.cloud.shared.domain.MemberCountPointDto
import network.lapis.cloud.shared.domain.MemberStatisticsRules
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.IMemberStatisticsService
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}
private val MEMBER_STATISTICS_READ_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/**
 * Welle V1.9.59 "Mitgliederzahlen ueber Zeit" -- see [IMemberStatisticsService] KDoc. BOARD/ADMIN only, enforced as the FIRST statement,
 * before any input is looked at.
 *
 * **Privacy is enforced by the query shape**: one aggregated `GROUP BY (status, previous_status, effective_from)` over
 * `member_status_history`; `member_id` is never selected, so no row per member ever reaches memory, let alone the response. Small cells
 * (a period with one APPLICATION) are shown to BOARD/ADMIN, who see the member list anyway; see `docs/architecture/member-status-history.adoc`.
 *
 * **Cost**: one query, linear in the number of distinct (status, previous status, instant) groups -- bulk changes with one instant
 * (the CSV import) collapse in the database. Range limits (`from >= 1900`, `to <= today`, at most
 * [MemberStatisticsRules.MAX_POINTS] points) bound the work of the arithmetic. DB timeouts are the pool's (V1.9.55).
 */
class MemberStatisticsService(
    private val call: ApplicationCall,
) : IMemberStatisticsService {
    override suspend fun getMemberCountHistory(query: MemberCountHistoryQuery): MemberCountHistoryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*MEMBER_STATISTICS_READ_ROLES)

        val now = DbClock.nowLocalDateTime()
        val zone = OrganizationTimeZone.current()
        val today = OrganizationTimeZone.dateOf(now)
        if (query.from < MemberStatisticsRules.MIN_FROM) throw BadRequestException("from is before ${MemberStatisticsRules.MIN_FROM}")
        if (query.from > query.to) throw BadRequestException("from is after to")
        if (query.to > today) throw BadRequestException("to is in the future")
        if (MemberStatisticsRules.pointCount(from = query.from, to = query.to, granularity = query.granularity) >
            MemberStatisticsRules.MAX_POINTS
        ) {
            throw BadRequestException("the range has more than ${MemberStatisticsRules.MAX_POINTS} periods")
        }

        val periods =
            MemberCountAggregation.periods(
                from = query.from,
                to = query.to,
                granularity = query.granularity,
                orgZone = zone,
                now = now,
            )
        val lastBoundary = periods.last().boundary

        val loaded =
            transaction {
                val count = MemberStatusHistoryTable.memberId.count()
                val deltas =
                    MemberStatusHistoryTable
                        .select(
                            MemberStatusHistoryTable.status,
                            MemberStatusHistoryTable.previousStatus,
                            MemberStatusHistoryTable.effectiveFrom,
                            count,
                        ).where { MemberStatusHistoryTable.effectiveFrom less lastBoundary }
                        .groupBy(
                            MemberStatusHistoryTable.status,
                            MemberStatusHistoryTable.previousStatus,
                            MemberStatusHistoryTable.effectiveFrom,
                        ).orderBy(MemberStatusHistoryTable.effectiveFrom to SortOrder.ASC)
                        .map { row ->
                            StatusDelta(
                                status = MemberStatus.valueOf(row[MemberStatusHistoryTable.status]),
                                previous = row[MemberStatusHistoryTable.previousStatus]?.let { MemberStatus.valueOf(it) },
                                effectiveFrom = row[MemberStatusHistoryTable.effectiveFrom],
                                count = row[count],
                            )
                        }
                Loaded(deltas = deltas, earliest = earliestInstant(), reconstructedBefore = reconstructedBefore(now))
            }

        val counts =
            try {
                MemberCountAggregation.countsAt(deltas = loaded.deltas, boundaries = periods.map { it.boundary })
            } catch (e: IllegalStateException) {
                logger.error { "member status history is inconsistent (negative count) for range ${query.from}..${query.to}" }
                throw e
            }
        val reconstructedBefore = loaded.reconstructedBefore
        val response =
            MemberCountHistoryDto(
                granularity = query.granularity,
                points = periods.mapIndexed { i, p -> p.toPoint(counts = counts[i], reconstructedBefore = reconstructedBefore) },
                earliestDate = loaded.earliest?.let { OrganizationTimeZone.dateOf(it) },
                today = today,
                reconstructedBefore = reconstructedBefore,
            )
        // Actor, role, granularity and the number of points only -- never a count per person, never a member id.
        logger.info {
            "Mitgliederentwicklung abgerufen von ${current.memberId} (${current.role}): " +
                "granularity=${query.granularity}, points=${response.points.size}"
        }
        return response
    }

    private class Loaded(
        val deltas: List<StatusDelta>,
        val earliest: LocalDateTime?,
        val reconstructedBefore: LocalDateTime?,
    )

    private fun CountPeriod.toPoint(
        counts: Map<MemberStatus, Int>,
        reconstructedBefore: LocalDateTime?,
    ): MemberCountPointDto =
        MemberCountPointDto(
            periodStart = start,
            periodEnd = endExclusive,
            current = current,
            counts = counts,
            reconstructed = !current && reconstructedBefore != null && boundary <= reconstructedBefore,
        )

    private fun earliestInstant(): LocalDateTime? {
        val earliest = MemberStatusHistoryTable.effectiveFrom.min()
        return MemberStatusHistoryTable.select(earliest).single()[earliest]
    }

    /**
     * The instant up to which the history is a reconstruction, or null. Rows reconstructed by the V72 backfill have no `recorded_at`;
     * every live row has one. Between V72 and the first live row no status changes, so the oldest live `recorded_at` is the
     * (conservative) end of that reconstruction -- without needing the database clock or any zone. A CSV import is backdated by
     * design: its newest `recorded_at` extends the reconstructed range.
     */
    private fun reconstructedBefore(now: LocalDateTime): LocalDateTime? {
        val candidates = mutableListOf<LocalDateTime>()
        val hasBackfill =
            MemberStatusHistoryTable
                .selectAll()
                .where { MemberStatusHistoryTable.recordedAt.isNull() }
                .limit(1)
                .any()
        if (hasBackfill) {
            val oldest = MemberStatusHistoryTable.recordedAt.min()
            candidates +=
                MemberStatusHistoryTable.select(oldest).where { MemberStatusHistoryTable.recordedAt.isNotNull() }.single()[oldest] ?: now
        }
        val newestImport = MemberStatusHistoryTable.recordedAt.max()
        MemberStatusHistoryTable
            .select(newestImport)
            .where { MemberStatusHistoryTable.sourceKind eq MemberStatusHistorySource.IMPORT.name }
            .single()[newestImport]
            ?.let { candidates += it }
        return candidates.maxOrNull()
    }
}
