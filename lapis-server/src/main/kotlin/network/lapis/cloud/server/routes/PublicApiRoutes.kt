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
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionDto
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
                        items = items.map { it.toPublicApiDto() },
                        totalCount = total.toInt(),
                        limit = page.limit,
                        offset = page.offset,
                    ),
            )
        }
    }

    get("/api/v1/committees/{id}") {
        call.publicApiHandler(preAuthRateLimiter = preAuthRateLimiter, postAuthRateLimiter = postAuthRateLimiter) {
            val id = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            if (id == null) {
                call.respondPublicApiBadRequest("Invalid committee id.")
                return@publicApiHandler
            }
            // No activeOnly filter here (unlike the list variant's own caller-supplied parameter) --
            // an inactive Committee is already reachable via /api/v1/committees?activeOnly=false, so
            // this single-resource endpoint applying no filter is not a status oracle. See
            // GovernanceReads.getCommittee KDoc.
            val committee = transaction { GovernanceReads.getCommittee(id = id) }
            if (committee == null) {
                call.respondPublicApiNotFound("Committee not found.")
                return@publicApiHandler
            }
            call.respondPublicApiJson(serializer = PublicApiCommitteeDto.serializer(), value = committee.toPublicApiDto())
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
                        items = items.map { it.toPublicApiDto() },
                        totalCount = total.toInt(),
                        limit = page.limit,
                        offset = page.offset,
                    ),
            )
        }
    }

    get("/api/v1/resolutions/{id}") {
        call.publicApiHandler(preAuthRateLimiter = preAuthRateLimiter, postAuthRateLimiter = postAuthRateLimiter) {
            val id = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            if (id == null) {
                call.respondPublicApiBadRequest("Invalid resolution id.")
                return@publicApiHandler
            }
            val resolution = transaction { GovernanceReads.getResolution(id = id) }
            if (resolution == null) {
                call.respondPublicApiNotFound("Resolution not found.")
                return@publicApiHandler
            }
            call.respondPublicApiJson(serializer = PublicApiResolutionDto.serializer(), value = resolution.toPublicApiDto())
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
                        items = items.map { it.toPublicApiDto() },
                        totalCount = total.toInt(),
                        limit = page.limit,
                        offset = page.offset,
                    ),
            )
        }
    }

    get("/api/v1/motions/{id}") {
        call.publicApiHandler(preAuthRateLimiter = preAuthRateLimiter, postAuthRateLimiter = postAuthRateLimiter) {
            val id = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            if (id == null) {
                call.respondPublicApiBadRequest("Invalid motion id.")
                return@publicApiHandler
            }
            // Same hard PUBLIC_API_MOTION_STATUSES intersection as the list variant's own default
            // -- an internal-only status (SUBMITTED/REVIEWED/REJECTED_PRELIMINARY/WITHDRAWN) stays
            // structurally unreachable through this endpoint too, not merely absent by default.
            val motion = transaction { GovernanceReads.getMotion(id = id, allowedStatuses = PUBLIC_API_MOTION_STATUSES) }
            if (motion == null) {
                call.respondPublicApiNotFound("Motion not found.")
                return@publicApiHandler
            }
            call.respondPublicApiJson(serializer = PublicApiMotionDto.serializer(), value = motion.toPublicApiDto())
        }
    }

    get("/api/v1/members/{id}") {
        call.publicApiHandler(preAuthRateLimiter = preAuthRateLimiter, postAuthRateLimiter = postAuthRateLimiter) {
            val id = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            if (id == null) {
                call.respondPublicApiBadRequest("Invalid member id.")
                return@publicApiHandler
            }
            val member = transaction { MemberReads.getActiveMember(id = id) }
            if (member == null) {
                call.respondPublicApiNotFound("Member not found.")
                return@publicApiHandler
            }
            call.respondPublicApiJson(
                serializer = PublicApiMemberDto.serializer(),
                value = PublicApiMemberDto(id = member.id, displayName = member.displayName),
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
 * Welle V1.3.2 "Webhooks" (ausgehend), plan §8.4 -- extracted from what used to be an inline
 * mapping ONLY in the `/api/v1/committees` list handler, so the new `/api/v1/committees/{id}`
 * single-resource handler is GUARANTEED to return the exact same field set (S25 in the plan's
 * Stolperfallen list: a hand-duplicated second mapper is a silent API drift risk). Reines
 * Refactoring -- Feldmengen bleiben byte-identisch.
 */
private fun CommitteeDto.toPublicApiDto(): PublicApiCommitteeDto =
    PublicApiCommitteeDto(
        id = id,
        name = name,
        type = type,
        description = description,
        active = active,
        quorumPercent = quorumPercent,
    )

/** See [CommitteeDto.toPublicApiDto] KDoc -- same extraction, for `/api/v1/resolutions`/`/api/v1/resolutions/{id}`. */
private fun ResolutionDto.toPublicApiDto(): PublicApiResolutionDto =
    PublicApiResolutionDto(
        id = id,
        meetingId = meetingId,
        number = number,
        title = title,
        text = text,
        status = status,
        resolutionMode = resolutionMode,
        decidedAt = decidedAt,
        votesYes = votesYes,
        votesNo = votesNo,
        votesAbstain = votesAbstain,
        quorumMet = quorumMet,
    )

/** See [CommitteeDto.toPublicApiDto] KDoc -- same extraction, for `/api/v1/motions`/`/api/v1/motions/{id}`. */
private fun MotionDto.toPublicApiDto(): PublicApiMotionDto =
    PublicApiMotionDto(
        id = id,
        targetCommitteeId = targetCommitteeId,
        targetCommitteeName = targetCommitteeName,
        targetCommitteeType = targetCommitteeType,
        title = title,
        text = text,
        status = status,
        submittedAt = submittedAt,
        meetingId = meetingId,
        resolutionId = resolutionId,
        amendsMotionId = amendsMotionId,
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
