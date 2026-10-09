package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.events.EventImporter
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventImportPreviewDto
import network.lapis.cloud.shared.domain.EventImportResultDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IEventImportService
import kotlin.uuid.Uuid

/**
 * Welle V1.9.82 -- admin import of past events (see [IEventImportService], `docs/api/event-import.adoc`).
 *
 * **Authorization is the FIRST statement of every method** (a source-scanning tripwire pins that): BOARD/ADMIN only, before any
 * parsing, hashing or rate-limit bookkeeping. The request body is size-limited BEFORE deserialization by [installEventImportBodyLimit].
 */
class EventImportService(
    private val call: ApplicationCall,
    private val previewRateLimiter: FederationInboxRateLimiter,
    private val commitRateLimiter: FederationInboxRateLimiter,
) : IEventImportService {
    override suspend fun previewEventImport(json: String): EventImportPreviewDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
        requireWithinRate(limiter = previewRateLimiter, memberId = current.memberId)
        val wallNow = OrganizationTimeZone.wallNowOf(DbClock.nowLocalDateTime())
        return EventImporter.preview(json = json, wallNow = wallNow, timeZoneId = OrganizationTimeZone.current().id)
    }

    override suspend fun commitEventImport(
        json: String,
        payloadSha256: String,
    ): EventImportResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
        requireWithinRate(limiter = commitRateLimiter, memberId = current.memberId)
        val now = DbClock.nowLocalDateTime()
        return EventImporter.commit(
            json = json,
            payloadSha256 = payloadSha256,
            actorMemberId = current.memberId,
            actorRole = current.role,
            now = now,
            wallNow = OrganizationTimeZone.wallNowOf(now),
        )
    }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        memberId: Uuid,
    ) {
        if (!limiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Zu viele Anfragen -- bitte später erneut versuchen.")
        }
    }
}
