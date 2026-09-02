package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionDto
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.3.1 "API-Fundament, lesend" -- read-only facade over [CommitteeTable]/[MeetingTable]/
 * [ResolutionTable]/[MotionTable], shared by [GovernanceService]'s own RPC methods (which now
 * delegate here, see that class's `listCommittees`/`listMeetings`/`listResolutions`/`listMotions`)
 * AND `network.lapis.cloud.server.routes.PublicApiRoutes`' `/api/v1` REST handlers. Extracted so
 * the SAME query/mapping logic backs both surfaces -- never a second, driftable copy.
 *
 * **Must run inside an already-open `transaction {}`** -- every function here is a plain read, no
 * function opens its own transaction (mirrors [insertResolutionRow]/[computeQuorum] in
 * `ResolutionBook.kt`, which every pre-existing RPC call site already satisfies). A REST handler
 * that ALSO needs [network.lapis.cloud.server.security.ApiKeyStore] in the same request must
 * resolve the API key BEFORE opening the `transaction { GovernanceReads... }` block used here --
 * see `PublicApiSupport.requirePublicApiPrincipal` KDoc / plan Stolperfalle S16.
 *
 * `limit = null` means "no `.limit()` call at all" -- every pre-existing RPC call site (which never
 * passes `limit`) is therefore byte-identical in behavior to before this extraction. Only
 * `PublicApiRoutes` ever passes a concrete `limit`/`offset` (see `PublicApiSupport.parsePublicApiPageParams`).
 */
internal object GovernanceReads {
    fun listCommittees(
        activeOnly: Boolean,
        limit: Int? = null,
        offset: Int = 0,
    ): List<CommitteeDto> {
        val baseQuery = CommitteeTable.selectAll()
        val query = if (activeOnly) baseQuery.where { CommitteeTable.active eq true } else baseQuery
        val paged = if (limit != null) query.limit(limit).offset(offset.toLong()) else query
        return paged.map { it.toCommitteeDto() }
    }

    fun countCommittees(activeOnly: Boolean): Long {
        val baseQuery = CommitteeTable.selectAll()
        val query = if (activeOnly) baseQuery.where { CommitteeTable.active eq true } else baseQuery
        return query.count()
    }

    /** Welle V1.3.2 "Webhooks" (ausgehend), plan §8.3 -- `null` = not found. NO status/active filter, same as the list variant's own `activeOnly` being a caller-supplied parameter (default `true`, but `false` is legitimate) rather than a hard gate -- see `network.lapis.cloud.server.routes.PublicApiRoutes`' `/api/v1/committees/{id}` handler KDoc for why this is not a status oracle the way `/members/{id}`/`/motions/{id}` would be without their own hard filters. */
    fun getCommittee(id: Uuid): CommitteeDto? =
        CommitteeTable
            .selectAll()
            .where { CommitteeTable.id eq id }
            .singleOrNull()
            ?.toCommitteeDto()

    fun listMeetings(
        committeeId: Uuid? = null,
        status: MeetingStatus? = null,
        limit: Int? = null,
        offset: Int = 0,
        resolveMemberNames: Boolean = true,
    ): List<MeetingDto> {
        val conditions = meetingConditions(committeeId = committeeId, status = status)
        val baseQuery = (MeetingTable innerJoin CommitteeTable).selectAll()
        val filtered = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
        val paged = if (limit != null) filtered.limit(limit).offset(offset.toLong()) else filtered
        return paged.map { it.toMeetingDto(resolveMemberNames = resolveMemberNames) }
    }

    fun countMeetings(
        committeeId: Uuid? = null,
        status: MeetingStatus? = null,
    ): Long {
        val conditions = meetingConditions(committeeId = committeeId, status = status)
        val baseQuery = (MeetingTable innerJoin CommitteeTable).selectAll()
        return if (conditions.isEmpty()) baseQuery.count() else baseQuery.where { conditions.reduce { a, b -> a and b } }.count()
    }

    private fun meetingConditions(
        committeeId: Uuid?,
        status: MeetingStatus?,
    ): MutableList<Op<Boolean>> {
        val conditions = mutableListOf<Op<Boolean>>()
        if (committeeId != null) conditions += (MeetingTable.committeeId eq committeeId)
        if (status != null) conditions += (MeetingTable.status eq status)
        return conditions
    }

    /** `null` = not found -- lets a REST caller ([network.lapis.cloud.server.routes.PublicApiRoutes]'s `/api/v1/meetings/{id}`) turn this into a 404, unlike [GovernanceService.loadMeeting] which throws for the RPC surface. */
    fun getMeeting(
        id: Uuid,
        resolveMemberNames: Boolean = true,
    ): MeetingDto? =
        (MeetingTable innerJoin CommitteeTable)
            .selectAll()
            .where { MeetingTable.id eq id }
            .singleOrNull()
            ?.toMeetingDto(resolveMemberNames = resolveMemberNames)

    fun listResolutions(
        committeeId: Uuid? = null,
        meetingId: Uuid? = null,
        limit: Int? = null,
        offset: Int = 0,
    ): List<ResolutionDto> {
        val base =
            when {
                meetingId != null -> ResolutionTable.selectAll().where { ResolutionTable.meetingId eq meetingId }
                committeeId != null ->
                    (ResolutionTable innerJoin MeetingTable)
                        .selectAll()
                        .where { MeetingTable.committeeId eq committeeId }
                else -> ResolutionTable.selectAll()
            }
        val paged = if (limit != null) base.limit(limit).offset(offset.toLong()) else base
        return paged.map { it.toResolutionDto() }
    }

    fun countResolutions(
        committeeId: Uuid? = null,
        meetingId: Uuid? = null,
    ): Long =
        when {
            meetingId != null -> ResolutionTable.selectAll().where { ResolutionTable.meetingId eq meetingId }.count()
            committeeId != null ->
                (ResolutionTable innerJoin MeetingTable)
                    .selectAll()
                    .where { MeetingTable.committeeId eq committeeId }
                    .count()
            else -> ResolutionTable.selectAll().count()
        }

    /** Welle V1.3.2 "Webhooks" (ausgehend), plan §8.3 -- `null` = not found. No filter, same as [listResolutions] (the list variant shows every Resolution unconditionally). */
    fun getResolution(id: Uuid): ResolutionDto? =
        ResolutionTable
            .selectAll()
            .where { ResolutionTable.id eq id }
            .singleOrNull()
            ?.toResolutionDto()

    /**
     * `allowedStatuses`: `null` (the RPC default, [GovernanceService.listMotions]) means NO
     * restriction beyond [status] itself -- every pre-existing RPC call site's behavior stays
     * byte-identical. Non-null (`PublicApiRoutes`' own [network.lapis.cloud.server.routes.PublicApiMotionStatuses],
     * hard-intersected here, not just validated at the query-parameter layer) is the REST-only
     * whitelist (Design-Team decision, plan §6/S18): internal-only statuses (`SUBMITTED`/`REVIEWED`/
     * `REJECTED_PRELIMINARY`/`WITHDRAWN`) are then STRUCTURALLY unreachable through this function,
     * not merely absent by default -- a REST caller that omits `?status=` still only ever sees the
     * intersection with [allowedStatuses], never everything.
     */
    fun listMotions(
        targetCommitteeId: Uuid? = null,
        status: MotionStatus? = null,
        amendsMotionId: Uuid? = null,
        allowedStatuses: Set<MotionStatus>? = null,
        limit: Int? = null,
        offset: Int = 0,
    ): List<MotionDto> {
        val conditions =
            motionConditions(
                targetCommitteeId = targetCommitteeId,
                status = status,
                amendsMotionId = amendsMotionId,
                allowedStatuses = allowedStatuses,
            )
        val baseQuery = (MotionTable innerJoin CommitteeTable).selectAll()
        val filtered = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
        val paged = if (limit != null) filtered.limit(limit).offset(offset.toLong()) else filtered
        return paged.map { it.toMotionDto() }
    }

    fun countMotions(
        targetCommitteeId: Uuid? = null,
        status: MotionStatus? = null,
        amendsMotionId: Uuid? = null,
        allowedStatuses: Set<MotionStatus>? = null,
    ): Long {
        val conditions =
            motionConditions(
                targetCommitteeId = targetCommitteeId,
                status = status,
                amendsMotionId = amendsMotionId,
                allowedStatuses = allowedStatuses,
            )
        val baseQuery = (MotionTable innerJoin CommitteeTable).selectAll()
        return if (conditions.isEmpty()) baseQuery.count() else baseQuery.where { conditions.reduce { a, b -> a and b } }.count()
    }

    /**
     * Welle V1.3.2 "Webhooks" (ausgehend), plan §8.3 -- `null` = not found OR outside
     * [allowedStatuses] (the caller cannot distinguish the two, same "no status oracle" reasoning
     * [MemberReads.getActiveMember] KDoc gives). `null` [allowedStatuses] (the RPC default) means
     * no restriction beyond [id] itself -- identical semantics to [listMotions]'s own
     * [allowedStatuses] parameter, reusing the SAME [motionConditions] builder so the whitelist is
     * never formulated a second time.
     */
    fun getMotion(
        id: Uuid,
        allowedStatuses: Set<MotionStatus>? = null,
    ): MotionDto? {
        val conditions =
            motionConditions(targetCommitteeId = null, status = null, amendsMotionId = null, allowedStatuses = allowedStatuses)
        var condition: Op<Boolean> = MotionTable.id eq id
        conditions.forEach { condition = condition and it }
        return (MotionTable innerJoin CommitteeTable)
            .selectAll()
            .where { condition }
            .singleOrNull()
            ?.toMotionDto()
    }

    private fun motionConditions(
        targetCommitteeId: Uuid?,
        status: MotionStatus?,
        amendsMotionId: Uuid?,
        allowedStatuses: Set<MotionStatus>?,
    ): MutableList<Op<Boolean>> {
        val conditions = mutableListOf<Op<Boolean>>()
        if (targetCommitteeId != null) conditions += (MotionTable.targetCommitteeId eq targetCommitteeId)
        if (status != null) conditions += (MotionTable.status eq status)
        if (amendsMotionId != null) conditions += (MotionTable.amendsMotionId eq amendsMotionId)
        if (allowedStatuses != null) conditions += (MotionTable.status inList allowedStatuses.toList())
        return conditions
    }
}
