package network.lapis.cloud.server.routes

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.GovernanceReads
import network.lapis.cloud.server.rpc.MemberReads
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MotionStatus
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.3.1 "API-Fundament, lesend" -- the FIRST public, read-only REST surface this server exposes
 * for machine-to-machine consumption (as opposed to `SocialPublicRoutes`'/`PublicTransparencyRoutes`'
 * human-facing HTML pages, and unlike every other route family in this codebase, KILUA-RPC-free by
 * design -- a third-party integration should not need this server's own RPC client library).
 *
 * **Auth**: `Authorization: Bearer lapis_<key>` -- see [network.lapis.cloud.server.security.ApiKeyAuth]/
 * [network.lapis.cloud.server.security.ApiKeyStore]. Every handler below follows the SAME fixed
 * order ([publicApiHandler]): set `Cache-Control`/`Vary` -> pre-auth (IP-keyed) rate limit ->
 * resolve+rate-limit the API key ([PublicApiSupport.requirePublicApiPrincipal]) -> parse
 * query/path parameters (any malformed value -> `400 bad_request`, written immediately, never a
 * silent fallback) -> read (own `transaction {}`, opened AFTER the API-key resolution above has
 * already closed its OWN transaction -- see [GovernanceReads]/[network.lapis.cloud.server.security.ApiKeyStore]
 * KDoc "Stolperfalle S16") -> map to the narrower `PublicApi*Dto` shape -> respond as JSON.
 *
 * **Field reduction is deliberate and permanent, not an oversight** -- see `docs/api/public-api-v1.adoc`,
 * especially its `/members` section (id + displayName ONLY, no e-mail/address/status, DSGVO
 * Datenminimierung). [PUBLIC_API_MOTION_STATUSES] additionally makes `/motions`' internal-workflow
 * statuses (`SUBMITTED`/`REVIEWED`/`REJECTED_PRELIMINARY`/`WITHDRAWN`) structurally unreachable, not
 * merely absent by default.
 *
 * Registered in `Application.kt`'s `routing {}` block BEFORE `staticFiles("/", ...)`, directly after
 * `registerPublicTransparencyRoutes(...)` -- same "literal beats catch-all" reasoning every other
 * pre-`staticFiles` route family in this codebase already documents.
 */
fun Route.registerPublicApiRoutes(
    preAuthRateLimiter: FederationInboxRateLimiter,
    postAuthRateLimiter: FederationInboxRateLimiter,
) {
    get("/api/v1/members") {
        call.publicApiHandler(preAuthRateLimiter = preAuthRateLimiter, postAuthRateLimiter = postAuthRateLimiter) {
            val page = call.parsePublicApiPageParams()
            val (items, total) =
                transaction {
                    MemberReads.listActiveMembers(limit = page.limit, offset = page.offset) to MemberReads.countActiveMembers()
                }
            call.respondPublicApiJson(
                serializer = PublicApiMembersPageDto.serializer(),
                value =
                    PublicApiMembersPageDto(
                        items = items.map { PublicApiMemberDto(id = it.id, displayName = it.displayName) },
                        totalCount = total.toInt(),
                        limit = page.limit,
                        offset = page.offset,
                    ),
            )
        }
    }

    get("/api/v1/committees") {
        call.publicApiHandler(preAuthRateLimiter = preAuthRateLimiter, postAuthRateLimiter = postAuthRateLimiter) {
            val activeOnly = (call.request.queryParameters["activeOnly"] ?: "true").toBooleanStrictOrNull() ?: true
            val page = call.parsePublicApiPageParams()
            val (items, total) =
                transaction {
                    GovernanceReads.listCommittees(activeOnly = activeOnly, limit = page.limit, offset = page.offset) to
                        GovernanceReads.countCommittees(activeOnly = activeOnly)
                }
            call.respondPublicApiJson(
                serializer = PublicApiCommitteesPageDto.serializer(),
                value =
                    PublicApiCommitteesPageDto(
                        items =
                            items.map {
                                PublicApiCommitteeDto(
                                    id = it.id,
                                    name = it.name,
                                    type = it.type,
                                    description = it.description,
                                    active = it.active,
                                    quorumPercent = it.quorumPercent,
                                )
                            },
                        totalCount = total.toInt(),
                        limit = page.limit,
                        offset = page.offset,
                    ),
            )
        }
    }

    get("/api/v1/meetings") {
        call.publicApiHandler(preAuthRateLimiter = preAuthRateLimiter, postAuthRateLimiter = postAuthRateLimiter) {
            val rawCommitteeId = call.request.queryParameters["committeeId"]
            val committeeId = rawCommitteeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            if (rawCommitteeId != null && committeeId == null) {
                call.respondPublicApiBadRequest("committeeId must be a valid UUID.")
                return@publicApiHandler
            }
            val rawStatus = call.request.queryParameters["status"]
            val status = rawStatus?.let { r -> MeetingStatus.entries.firstOrNull { it.name == r } }
            if (rawStatus != null && status == null) {
                call.respondPublicApiBadRequest("status must be one of ${MeetingStatus.entries.map { it.name }}.")
                return@publicApiHandler
            }
            val page = call.parsePublicApiPageParams()
            val (items, total) =
                transaction {
                    GovernanceReads.listMeetings(committeeId = committeeId, status = status, limit = page.limit, offset = page.offset) to
                        GovernanceReads.countMeetings(committeeId = committeeId, status = status)
                }
            call.respondPublicApiJson(
                serializer = PublicApiMeetingsPageDto.serializer(),
                value =
                    PublicApiMeetingsPageDto(
                        items = items.map { it.toPublicApiDto() },
                        totalCount = total.toInt(),
                        limit = page.limit,
                        offset = page.offset,
                    ),
            )
        }
    }

    get("/api/v1/meetings/{id}") {
        call.publicApiHandler(preAuthRateLimiter = preAuthRateLimiter, postAuthRateLimiter = postAuthRateLimiter) {
            val id = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            if (id == null) {
                call.respondPublicApiBadRequest("Invalid meeting id.")
                return@publicApiHandler
            }
            val meeting = transaction { GovernanceReads.getMeeting(id = id, resolveMemberNames = false) }
            if (meeting == null) {
                call.respondPublicApiNotFound("Meeting not found.")
                return@publicApiHandler
            }
            call.respondPublicApiJson(serializer = PublicApiMeetingDto.serializer(), value = meeting.toPublicApiDto())
        }
    }

    get("/api/v1/resolutions") {
        call.publicApiHandler(preAuthRateLimiter = preAuthRateLimiter, postAuthRateLimiter = postAuthRateLimiter) {
            val rawCommitteeId = call.request.queryParameters["committeeId"]
            val committeeId = rawCommitteeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            if (rawCommitteeId != null && committeeId == null) {
                call.respondPublicApiBadRequest("committeeId must be a valid UUID.")
                return@publicApiHandler
            }
            val rawMeetingId = call.request.queryParameters["meetingId"]
            val meetingId = rawMeetingId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            if (rawMeetingId != null && meetingId == null) {
                call.respondPublicApiBadRequest("meetingId must be a valid UUID.")
                return@publicApiHandler
            }
            val page = call.parsePublicApiPageParams()
            val (items, total) =
                transaction {
                    GovernanceReads.listResolutions(
                        committeeId = committeeId,
                        meetingId = meetingId,
                        limit = page.limit,
                        offset = page.offset,
                    ) to
                        GovernanceReads.countResolutions(committeeId = committeeId, meetingId = meetingId)
                }
            call.respondPublicApiJson(
                serializer = PublicApiResolutionsPageDto.serializer(),
                value =
                    PublicApiResolutionsPageDto(
                        items =
                            items.map {
                                PublicApiResolutionDto(
                                    id = it.id,
                                    meetingId = it.meetingId,
                                    number = it.number,
                                    title = it.title,
                                    text = it.text,
                                    status = it.status,
                                    resolutionMode = it.resolutionMode,
                                    decidedAt = it.decidedAt,
                                    votesYes = it.votesYes,
                                    votesNo = it.votesNo,
                                    votesAbstain = it.votesAbstain,
                                    quorumMet = it.quorumMet,
                                )
                            },
                        totalCount = total.toInt(),
                        limit = page.limit,
                        offset = page.offset,
                    ),
            )
        }
    }

    get("/api/v1/motions") {
        call.publicApiHandler(preAuthRateLimiter = preAuthRateLimiter, postAuthRateLimiter = postAuthRateLimiter) {
            val rawCommitteeId = call.request.queryParameters["targetCommitteeId"]
            val targetCommitteeId = rawCommitteeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            if (rawCommitteeId != null && targetCommitteeId == null) {
                call.respondPublicApiBadRequest("targetCommitteeId must be a valid UUID.")
                return@publicApiHandler
            }
            val rawStatus = call.request.queryParameters["status"]
            val status = rawStatus?.let { r -> MotionStatus.entries.firstOrNull { it.name == r } }
            if (rawStatus != null && (status == null || status !in PUBLIC_API_MOTION_STATUSES)) {
                call.respondPublicApiBadRequest("status must be one of ${PUBLIC_API_MOTION_STATUSES.map { it.name }}.")
                return@publicApiHandler
            }
            val page = call.parsePublicApiPageParams()
            val (items, total) =
                transaction {
                    GovernanceReads.listMotions(
                        targetCommitteeId = targetCommitteeId,
                        status = status,
                        allowedStatuses = PUBLIC_API_MOTION_STATUSES,
                        limit = page.limit,
                        offset = page.offset,
                    ) to
                        GovernanceReads.countMotions(
                            targetCommitteeId = targetCommitteeId,
                            status = status,
                            allowedStatuses = PUBLIC_API_MOTION_STATUSES,
                        )
                }
            call.respondPublicApiJson(
                serializer = PublicApiMotionsPageDto.serializer(),
                value =
                    PublicApiMotionsPageDto(
                        items =
                            items.map {
                                PublicApiMotionDto(
                                    id = it.id,
                                    targetCommitteeId = it.targetCommitteeId,
                                    targetCommitteeName = it.targetCommitteeName,
                                    targetCommitteeType = it.targetCommitteeType,
                                    title = it.title,
                                    text = it.text,
                                    status = it.status,
                                    submittedAt = it.submittedAt,
                                    meetingId = it.meetingId,
                                    resolutionId = it.resolutionId,
                                    amendsMotionId = it.amendsMotionId,
                                )
                            },
                        totalCount = total.toInt(),
                        limit = page.limit,
                        offset = page.offset,
                    ),
            )
        }
    }
}

private fun MeetingDto.toPublicApiDto(): PublicApiMeetingDto =
    PublicApiMeetingDto(
        id = id,
        committeeId = committeeId,
        committeeName = committeeName,
        title = title,
        scheduledAt = scheduledAt,
        location = location,
        format = format,
        status = status,
    )

/**
 * Shared per-handler skeleton: `Cache-Control`/`Vary` (via [requirePublicApiPrincipal]) -> pre-auth
 * rate limit -> API-key resolution+post-auth rate limit -> [block]. Every `/api/v1` handler above
 * is built on this so the fixed ordering (plan §6/§7) can never drift between endpoints.
 */
private suspend fun ApplicationCall.publicApiHandler(
    preAuthRateLimiter: FederationInboxRateLimiter,
    postAuthRateLimiter: FederationInboxRateLimiter,
    block: suspend () -> Unit,
) {
    applyPublicApiHeaders()
    if (!checkPublicApiPreAuthRateLimit(preAuthRateLimiter)) return
    requirePublicApiPrincipal(postAuthRateLimiter) ?: return
    block()
}

private suspend fun <T> ApplicationCall.respondPublicApiJson(
    serializer: KSerializer<T>,
    value: T,
) {
    respondText(text = Json.encodeToString(serializer, value), contentType = ContentType.Application.Json)
}
