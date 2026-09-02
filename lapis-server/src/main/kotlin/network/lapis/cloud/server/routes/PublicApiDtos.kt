package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus

/**
 * V1.3.1 "API-Fundament, lesend" -- DTOs for the `/api/v1` REST surface (see [PublicApiRoutes]).
 * Server-local, NOT `lapis-shared` (precedent: `RestoreResultResponse` in `BackupRoutes.kt`) -- this
 * API is consumed by external HTTP clients, not `lapis-client`'s Kilua-RPC layer, so there is no
 * reason to travel through the KMP-shared module. Concrete, non-generic page envelopes (precedent:
 * `MemberAdminPageDto`) rather than a generic `PublicApiPage<T>` -- this codebase has no generic
 * `@Serializable` type anywhere, and a generic one would need `.serializer(childSerializer)`
 * ceremony at every call site for no real benefit at this DTO count.
 *
 * Field sets are a DELIBERATE subset of the corresponding RPC DTO -- see `docs/api/public-api-v1.adoc`
 * for what is (and, for `/members`, permanently is not) exposed, and [PublicApiFieldReductionTest]
 * for the enforcement.
 */
@Serializable
data class PublicApiMemberDto(
    val id: String,
    val displayName: String,
)

@Serializable
data class PublicApiMembersPageDto(
    val items: List<PublicApiMemberDto>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class PublicApiCommitteeDto(
    val id: String,
    val name: String,
    val type: CommitteeType,
    val description: String,
    val active: Boolean,
    val quorumPercent: Int,
)

@Serializable
data class PublicApiCommitteesPageDto(
    val items: List<PublicApiCommitteeDto>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class PublicApiMeetingDto(
    val id: String,
    val committeeId: String,
    val committeeName: String,
    val title: String,
    val scheduledAt: LocalDateTime,
    val location: String?,
    val format: MeetingFormat,
    val status: MeetingStatus,
)

@Serializable
data class PublicApiMeetingsPageDto(
    val items: List<PublicApiMeetingDto>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class PublicApiResolutionDto(
    val id: String,
    val meetingId: String,
    val number: String,
    val title: String,
    val text: String,
    val status: ResolutionStatus,
    val resolutionMode: ResolutionMode,
    val decidedAt: LocalDateTime,
    val votesYes: Int,
    val votesNo: Int,
    val votesAbstain: Int,
    val quorumMet: Boolean,
)

@Serializable
data class PublicApiResolutionsPageDto(
    val items: List<PublicApiResolutionDto>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class PublicApiMotionDto(
    val id: String,
    val targetCommitteeId: String,
    val targetCommitteeName: String,
    val targetCommitteeType: CommitteeType,
    val title: String,
    val text: String,
    val status: MotionStatus,
    val submittedAt: LocalDateTime,
    val meetingId: String?,
    val resolutionId: String?,
    val amendsMotionId: String?,
)

@Serializable
data class PublicApiMotionsPageDto(
    val items: List<PublicApiMotionDto>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

/** Flat error body every `/api/v1` error response shares -- see [PublicApiSupport] for the codes. */
@Serializable
data class PublicApiErrorDto(
    val error: String,
    val message: String,
)

/**
 * The ONLY [MotionStatus] values `/api/v1/motions` will ever return, whether via an explicit
 * `?status=` filter (validated against this set -- anything outside it is `400 bad_request`, see
 * [PublicApiSupport]) or the default (no filter -- [network.lapis.cloud.server.rpc.GovernanceReads.listMotions]
 * is called with this exact set as `allowedStatuses`, so `SUBMITTED`/`REVIEWED`/
 * `REJECTED_PRELIMINARY`/`WITHDRAWN` are structurally unreachable, not merely absent by default).
 * Public API documentation, not an implementation detail -- see `docs/api/public-api-v1.adoc`'s own
 * `/motions` section.
 */
internal val PUBLIC_API_MOTION_STATUSES: Set<MotionStatus> =
    setOf(MotionStatus.SCHEDULED, MotionStatus.RESOLVED, MotionStatus.REJECTED, MotionStatus.POSTPONED)
