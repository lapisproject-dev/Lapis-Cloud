package network.lapis.cloud.server.partg

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.mail.plusDuration
import network.lapis.cloud.server.partg.report.PartgVersionDiff
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgReportFormat
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgReviewerAuditSnapshot
import network.lapis.cloud.shared.domain.PartgReviewerEventKind
import network.lapis.cloud.shared.domain.PartgReviewerFileFormat
import network.lapis.cloud.shared.domain.PartgReviewerRules
import network.lapis.cloud.shared.domain.PartgReviewerSessionInfoDto
import network.lapis.cloud.shared.domain.PartgReviewerVersionDto
import network.lapis.cloud.shared.domain.PartgVersionDiffDto
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** The auditor asked for a version that is not in the scope of their invitation (or does not exist): one and the same 404. */
internal class PartgReviewerNotInScopeException : RuntimeException("Not found")

/**
 * V1.9.310 (R6b) -- the public path of the auditor, pure logic without HTTP types (`PartgReviewerRoutes` is the only caller).
 *
 * THE RULES, all counted under the row lock of the invitation (`lockByTokenHash`, FOR UPDATE, so parallel tries cannot lose an update):
 *  - A code is 6 digits, valid 10 minutes, usable ONCE; asking for a new one makes the old one void. At most 3 codes per hour and 10 per invitation.
 *  - 5 wrong tries burn the code; 3 burned codes lock the invitation (and end every session). Successful logins do not count towards the lock.
 *  - A session lasts 30 minutes idle, 4 hours at most and never longer than the invitation. EVERY request re-checks the invitation (revoked,
 *    locked, expired, party switch) -- a revoke is effective at once.
 *  - Every answer that could tell a valid link from an invalid one is the same: [requestCode] returns nothing, [openSession] returns `null`.
 *  - The auditor reads only versions of their scope; everything else is [PartgReviewerNotInScopeException]. A version is read from the STORED text
 *    (checksum checked again), never recomputed.
 *  - Event and audit entry carry ids and kinds only: no content, no code, no token, no IP, no address, no actor.
 */
internal class PartgReviewerAccess(
    /** Hands the code to the mail system; called AFTER the database transaction committed, never inside it. */
    private val sendCode: (email: String, code: String) -> Unit,
    /** Own permit (1): the auditor cannot starve the board's export, nor the other way round. */
    private val concurrency: Semaphore = Semaphore(permits = 1),
    private val clock: () -> LocalDateTime = { ServerClock.now() },
    /** The renderer of a stored version; a seam for tests (an oversized version), production uses [PartgStoredVersionRenderer.render]. */
    private val renderer: suspend (PartgStoredVersionRenderer.Verified, PartgReportFormat) -> PartgStoredVersionRenderer.Rendered =
        { verified, format -> PartgStoredVersionRenderer.render(verified = verified, format = format) },
) {
    /** A resolved session of an auditor. */
    class ReviewerSession(
        val sessionId: Uuid,
        val invitationId: Uuid,
        val year: Int,
        val invitationExpiresAt: LocalDateTime,
        val sessionEndsAt: LocalDateTime,
    ) {
        fun info() = PartgReviewerSessionInfoDto(year = year, invitationExpiresAt = invitationExpiresAt, sessionEndsAt = sessionEndsAt)
    }

    /** The opened session: the RAW token (for the cookie, shown nowhere else) and how long it can live. */
    class OpenedSession(
        val rawToken: String,
        val maxAgeSeconds: Int,
    )

    private class PendingCode(
        val email: String,
        val code: String,
    )

    // ---------------------------------------------------------------- code + session

    /** Sends a code if (and only if) the invitation is valid and under its limits. Returns nothing: the caller learns nothing. */
    fun requestCode(rawToken: String) {
        val now = clock()
        if (!partyEnabled()) return
        val hash = PartgReviewerTokens.hash(rawToken)
        val pending: PendingCode? =
            transaction {
                val invitation = PartgReviewerStore.lockByTokenHash(hash) ?: return@transaction null
                val email = invitation.email
                if (!usable(invitation = invitation, now = now) || email == null) return@transaction null
                if (PartgReviewerStore.scopeVersionIds(invitation.id).isEmpty()) return@transaction null
                if (invitation.codesSentTotal >= PartgReviewerRules.MAX_CODES_PER_INVITATION) return@transaction null
                val windowStart = invitation.codesWindowStart
                val windowOpen = windowStart != null && now < windowStart.plusDuration(1.hours)
                val windowCount = if (windowOpen) invitation.codesWindowCount else 0
                if (windowCount >= PartgReviewerRules.MAX_CODES_PER_HOUR) return@transaction null
                val code = PartgReviewerTokens.newCode()
                PartgReviewerStore.setCode(
                    id = invitation.id,
                    codeHash = PartgReviewerTokens.codeHash(invitationId = invitation.id, code = code),
                    expiresAt = now.plusDuration(PartgReviewerRules.CODE_VALID_MINUTES.minutes),
                    windowStart = if (windowOpen && windowStart != null) windowStart else now,
                    windowCount = windowCount + 1,
                    sentTotal = invitation.codesSentTotal + 1,
                )
                event(invitationId = invitation.id, kind = PartgReviewerEventKind.CODE_SENT, at = now)
                audit(invitationId = invitation.id, kind = PartgReviewerEventKind.CODE_SENT, at = now)
                PendingCode(email = email, code = code)
            }
        if (pending != null) {
            try {
                sendCode(pending.email, pending.code)
            } catch (e: RuntimeException) {
                // the caller must not learn whether a code went out; neither the address nor the code is logged
                logger.warn { "partg reviewer code could not be handed to the mail system" }
            }
        }
    }

    /**
     * Checks [rawCode] against the pending code of the invitation behind [rawToken] and opens a NEW session on success (the raw token of it is
     * returned). [previousRawSession] is a cookie the caller sent along: it is never taken over; if it belongs to the same invitation its session is
     * deleted (session fixation).
     */
    fun openSession(
        rawToken: String,
        rawCode: String,
        previousRawSession: String?,
    ): OpenedSession? {
        val now = clock()
        val normalized = PartgReviewerRules.normalizeCode(rawCode)
        val hash = PartgReviewerTokens.hash(rawToken)
        if (!partyEnabled()) {
            PartgReviewerTokens.matches(storedHex = null, candidateHex = PartgReviewerTokens.hash(rawCode))
            return null
        }
        return transaction {
            val invitation = PartgReviewerStore.lockByTokenHash(hash)
            val candidate = PartgReviewerTokens.codeHash(invitationId = invitation?.id ?: NO_INVITATION, code = normalized ?: NO_CODE)
            if (invitation == null) {
                PartgReviewerTokens.matches(storedHex = null, candidateHex = candidate)
                return@transaction null
            }
            if (!usable(invitation = invitation, now = now)) {
                PartgReviewerTokens.matches(storedHex = null, candidateHex = candidate)
                return@transaction null
            }
            val codeEnd = invitation.codeExpiresAt
            val pendingHash = if (invitation.codeHash != null && codeEnd != null && now < codeEnd) invitation.codeHash else null
            val matched = PartgReviewerTokens.matches(storedHex = pendingHash, candidateHex = candidate) && normalized != null
            if (pendingHash == null) return@transaction null // nothing to guess: no code is pending, nothing is counted
            if (!matched) {
                failedAttempt(invitation = invitation, now = now)
                return@transaction null
            }
            PartgReviewerStore.consumeCode(invitation.id)
            previousRawSession
                ?.let { PartgReviewerStore.sessionByTokenHash(PartgReviewerTokens.hash(it)) }
                ?.takeIf { it.invitationId == invitation.id }
                ?.let { PartgReviewerStore.deleteSession(it.id) }
            val absolute = minOf(now.plusDuration(PartgReviewerRules.ABSOLUTE_HOURS.hours), invitation.expiresAt)
            val idle = minOf(now.plusDuration(PartgReviewerRules.IDLE_MINUTES.minutes), absolute)
            val raw = PartgReviewerTokens.newLinkToken()
            PartgReviewerStore.insertSession(
                id = Uuid.random(),
                tokenHash = PartgReviewerTokens.hash(raw),
                invitationId = invitation.id,
                now = now,
                idleExpiresAt = idle,
                absoluteExpiresAt = absolute,
            )
            PartgReviewerStore.markUsed(id = invitation.id, now = now)
            event(invitationId = invitation.id, kind = PartgReviewerEventKind.SESSION_OPENED, at = now)
            audit(invitationId = invitation.id, kind = PartgReviewerEventKind.SESSION_OPENED, at = now)
            OpenedSession(rawToken = raw, maxAgeSeconds = secondsBetween(from = now, to = absolute))
        }
    }

    private fun failedAttempt(
        invitation: PartgReviewerStore.InvitationRow,
        now: LocalDateTime,
    ) {
        val attempts = invitation.codeAttempts + 1
        if (attempts < PartgReviewerRules.MAX_CODE_ATTEMPTS) {
            PartgReviewerStore.recordFailedAttempt(
                id = invitation.id,
                attempts = attempts,
                burnCode = false,
                burned = invitation.codesBurned,
                lockedAt = null,
            )
            event(invitationId = invitation.id, kind = PartgReviewerEventKind.CODE_FAILED, at = now)
            return
        }
        val burned = invitation.codesBurned + 1
        val lock = burned >= PartgReviewerRules.MAX_BURNED_CODES
        PartgReviewerStore.recordFailedAttempt(
            id = invitation.id,
            attempts = 0,
            burnCode = true,
            burned = burned,
            lockedAt = if (lock) now else null,
        )
        event(invitationId = invitation.id, kind = PartgReviewerEventKind.CODE_FAILED, at = now)
        audit(invitationId = invitation.id, kind = PartgReviewerEventKind.CODE_FAILED, at = now)
        if (lock) {
            PartgReviewerStore.deleteSessionsOf(invitation.id)
            event(invitationId = invitation.id, kind = PartgReviewerEventKind.INVITATION_LOCKED, at = now)
            audit(invitationId = invitation.id, kind = PartgReviewerEventKind.INVITATION_LOCKED, at = now)
        }
    }

    /** The session behind [rawCookie], or `null` (and the session is gone) when it or its invitation is no longer valid. Re-checked on EVERY call. */
    fun resolveSession(rawCookie: String?): ReviewerSession? {
        if (rawCookie == null || !PartgReviewerTokens.plausibleLinkToken(rawCookie)) return null
        val now = clock()
        val partyOn = partyEnabled()
        return transaction {
            val session = PartgReviewerStore.sessionByTokenHash(PartgReviewerTokens.hash(rawCookie)) ?: return@transaction null
            val invitation = PartgReviewerStore.byId(session.invitationId)
            val valid =
                partyOn &&
                    invitation != null &&
                    usable(invitation = invitation, now = now) &&
                    now < session.idleExpiresAt &&
                    now < session.absoluteExpiresAt
            if (!valid || invitation == null) {
                PartgReviewerStore.deleteSession(session.id)
                return@transaction null
            }
            var idleEnd = session.idleExpiresAt
            if (now >= session.lastSeenAt.plusDuration(TOUCH_EVERY)) {
                idleEnd = minOf(now.plusDuration(PartgReviewerRules.IDLE_MINUTES.minutes), session.absoluteExpiresAt)
                PartgReviewerStore.touchSession(id = session.id, now = now, newIdle = idleEnd)
            }
            ReviewerSession(
                sessionId = session.id,
                invitationId = invitation.id,
                year = invitation.year,
                invitationExpiresAt = invitation.expiresAt,
                sessionEndsAt = minOf(session.absoluteExpiresAt, invitation.expiresAt),
            )
        }
    }

    /** Logs the session out (idempotent). */
    fun endSession(rawCookie: String?) {
        if (rawCookie == null || !PartgReviewerTokens.plausibleLinkToken(rawCookie)) return
        val now = clock()
        transaction {
            val session = PartgReviewerStore.sessionByTokenHash(PartgReviewerTokens.hash(rawCookie)) ?: return@transaction
            PartgReviewerStore.deleteSession(session.id)
            event(invitationId = session.invitationId, kind = PartgReviewerEventKind.SESSION_ENDED, at = now)
            audit(invitationId = session.invitationId, kind = PartgReviewerEventKind.SESSION_ENDED, at = now)
        }
    }

    // ---------------------------------------------------------------- reading

    fun versions(session: ReviewerSession): List<PartgReviewerVersionDto> =
        transaction {
            val scope = PartgReviewerStore.scopeVersionIds(session.invitationId).toSet()
            PartgVersionStore
                .versions(session.year)
                .filter { it.id in scope && it.scopeKey == PartgReportScope.ALL_KEY }
                .sortedBy { it.versionNumber }
                .map {
                    PartgReviewerVersionDto(
                        id = it.id.toString(),
                        versionNumber = it.versionNumber,
                        frozenAt = it.frozenAt,
                        contentHash = it.contentHash,
                        finalized = it.finalizedAt != null,
                    )
                }
        }

    /** The stored JSON text, byte for byte (checksum checked). [download]: logged as a download in the format JSON, else as a view. */
    fun document(
        session: ReviewerSession,
        versionId: Uuid,
        download: Boolean,
    ): PartgStoredVersionRenderer.Verified {
        requireInScope(session = session, versionId = versionId)
        val verified = PartgStoredVersionRenderer.readVerified(versionId)
        record(
            session = session,
            kind = if (download) PartgReviewerEventKind.DOWNLOAD else PartgReviewerEventKind.DOCUMENT_VIEWED,
            versionId = versionId,
            format = if (download) PartgReviewerFileFormat.JSON else null,
        )
        return verified
    }

    /** PDF or XLSX of a version of the scope, rendered with the footer the board's export uses. Needs the auditor permit, else [RateLimitedException]. */
    suspend fun render(
        session: ReviewerSession,
        versionId: Uuid,
        format: PartgReportFormat,
    ): Pair<PartgStoredVersionRenderer.Verified, ByteArray> {
        requireInScope(session = session, versionId = versionId)
        if (!concurrency.tryAcquire()) throw RateLimitedException()
        try {
            val verified = PartgStoredVersionRenderer.readVerified(versionId)
            val rendered = renderer(verified, format)
            record(
                session = session,
                kind = PartgReviewerEventKind.DOWNLOAD,
                versionId = versionId,
                format = if (format == PartgReportFormat.PDF) PartgReviewerFileFormat.PDF else PartgReviewerFileFormat.XLSX,
            )
            return verified to rendered.bytes
        } finally {
            concurrency.release()
        }
    }

    suspend fun compare(
        session: ReviewerSession,
        idA: Uuid,
        idB: Uuid,
    ): PartgVersionDiffDto {
        requireInScope(session = session, versionId = idA)
        requireInScope(session = session, versionId = idB)
        if (!concurrency.tryAcquire()) throw RateLimitedException()
        try {
            val a = PartgStoredVersionRenderer.readVerified(idA)
            val b = PartgStoredVersionRenderer.readVerified(idB)
            val diff =
                withContext(Dispatchers.Default) {
                    PartgVersionDiff.compute(
                        a = PartgVersionFormat.decode(text = a.json, formatVersion = a.meta.formatVersion),
                        b = PartgVersionFormat.decode(text = b.json, formatVersion = b.meta.formatVersion),
                        versionIdA = idA.toString(),
                        versionIdB = idB.toString(),
                        versionNumberA = a.meta.versionNumber,
                        versionNumberB = b.meta.versionNumber,
                    )
                }
            record(session = session, kind = PartgReviewerEventKind.COMPARISON_VIEWED, versionId = idA, format = null)
            return diff
        } finally {
            concurrency.release()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun requireInScope(
        session: ReviewerSession,
        versionId: Uuid,
    ) {
        val inScope =
            transaction {
                PartgReviewerStore.inScope(invitationId = session.invitationId, versionId = versionId) &&
                    PartgVersionStore.versionMeta(versionId)?.let { it.year == session.year && it.scopeKey == PartgReportScope.ALL_KEY } ==
                    true
            }
        if (!inScope) throw PartgReviewerNotInScopeException()
    }

    private fun record(
        session: ReviewerSession,
        kind: PartgReviewerEventKind,
        versionId: Uuid,
        format: PartgReviewerFileFormat?,
    ) {
        val now = clock()
        transaction {
            PartgReviewerStore.markUsed(id = session.invitationId, now = now)
            event(invitationId = session.invitationId, kind = kind, at = now, versionId = versionId, format = format)
            audit(invitationId = session.invitationId, kind = kind, at = now, versionId = versionId, format = format)
        }
    }

    private fun usable(
        invitation: PartgReviewerStore.InvitationRow,
        now: LocalDateTime,
    ): Boolean = invitation.revokedAt == null && invitation.lockedAt == null && now < invitation.expiresAt

    private fun partyEnabled(): Boolean =
        transaction {
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull()
                ?.get(OrganizationSettingsTable.isPoliticalParty) ?: false
        }

    private fun secondsBetween(
        from: LocalDateTime,
        to: LocalDateTime,
    ): Int {
        val seconds = (to.toInstant(TimeZone.UTC) - from.toInstant(TimeZone.UTC)).inWholeSeconds
        return seconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun event(
        invitationId: Uuid,
        kind: PartgReviewerEventKind,
        at: LocalDateTime,
        versionId: Uuid? = null,
        format: PartgReviewerFileFormat? = null,
    ) = PartgReviewerStore.insertEvent(
        invitationId = invitationId,
        kind = kind.name,
        versionId = versionId,
        format = format?.name,
        at = at,
    )

    private fun audit(
        invitationId: Uuid,
        kind: PartgReviewerEventKind,
        at: LocalDateTime,
        versionId: Uuid? = null,
        format: PartgReviewerFileFormat? = null,
    ) = AuditLogRecorder.record(
        actorMemberId = null,
        actorRole = null,
        entityType = AuditEntityType.PARTG_REVIEWER_ACCESS,
        entityId = invitationId,
        action = AuditAction.UPDATE,
        after =
            Json.encodeToString(
                PartgReviewerAuditSnapshot.serializer(),
                PartgReviewerAuditSnapshot(
                    event = kind,
                    invitationId = invitationId.toString(),
                    versionId = versionId?.toString(),
                    format = format,
                ),
            ),
        occurredAt = at,
    )

    private companion object {
        val TOUCH_EVERY = 1.minutes
        val NO_INVITATION: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000000")
        const val NO_CODE = "000000"
    }
}
