package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.plusDuration
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgReviewerAuditSnapshot
import network.lapis.cloud.shared.domain.PartgReviewerEventDto
import network.lapis.cloud.shared.domain.PartgReviewerEventKind
import network.lapis.cloud.shared.domain.PartgReviewerFileFormat
import network.lapis.cloud.shared.domain.PartgReviewerInvitationCreatedDto
import network.lapis.cloud.shared.domain.PartgReviewerInvitationDto
import network.lapis.cloud.shared.domain.PartgReviewerInvitationStatus
import network.lapis.cloud.shared.domain.PartgReviewerRules
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * V1.9.310 (R6b) -- what the board does with the auditor's access: invite, list, revoke, erase an address. The RPC service calls the federal gate
 * FIRST (BOARD or ADMIN; a chapter treasurer, a treasurer and a member get 403) and only then delegates here. 60 writes per minute and member (the
 * shared limiter). The link is handed out ONCE, in the answer of [create]; only its hash is stored. An invitation stays valid until it ends even
 * if its author loses the board role; any board member can revoke it.
 */
internal class PartgReviewerOperations(
    private val writeRateLimiter: FederationInboxRateLimiter,
    private val clock: () -> LocalDateTime = { ServerClock.now() },
) {
    fun create(
        current: CurrentMember,
        year: Int,
        versionIds: List<String>,
        validityDays: Int,
        email: String,
    ): PartgReviewerInvitationCreatedDto {
        PartgYearRules.requireValid(year)
        if (validityDays !in PartgReviewerRules.VALIDITY_DAYS) throw PartyReportInvalidInputException("Invalid validity")
        if (versionIds.isEmpty() ||
            versionIds.size > PartgReviewerRules.MAX_VERSIONS
        ) {
            throw PartyReportInvalidInputException("Invalid versions")
        }
        val ids = versionIds.map { parseId(it) }
        if (ids.toSet().size != ids.size) throw PartyReportInvalidInputException("Invalid versions")
        val normalizedEmail = PartgReviewerRules.normalizeEmail(email) ?: throw PartyReportInvalidInputException("Invalid address")
        checkRate(current)
        val now = clock()
        val raw = PartgReviewerTokens.newLinkToken()
        val dto =
            transaction {
                ids.forEach { id ->
                    val meta = PartgVersionStore.versionMeta(id)
                    if (meta == null || meta.year != year || meta.scopeKey != PartgReportScope.ALL_KEY) {
                        throw PartyReportInvalidInputException("Invalid versions")
                    }
                }
                // serialise creates per year: the counter row is the lock (documented order counters -> invitations), so the cap is a hard one
                PartgVersionStore.lockExistingCounter(year = year, scopeKey = PartgReportScope.ALL_KEY)
                if (PartgReviewerStore.countOpenForYear(year = year, now = now) >= PartgReviewerRules.MAX_OPEN_INVITATIONS_PER_YEAR) {
                    throw PartyReportInvalidInputException("Too many open invitations")
                }
                val id = Uuid.random()
                PartgReviewerStore.insertInvitation(
                    row =
                        PartgReviewerStore.InvitationRow(
                            id = id,
                            year = year,
                            email = normalizedEmail,
                            linkTokenHash = PartgReviewerTokens.hash(raw),
                            codeHash = null,
                            codeExpiresAt = null,
                            codeAttempts = 0,
                            codesBurned = 0,
                            codesSentTotal = 0,
                            codesWindowStart = null,
                            codesWindowCount = 0,
                            lockedAt = null,
                            expiresAt = now.plusDuration(validityDays.days),
                            revokedAt = null,
                            revokedBy = null,
                            endReason = null,
                            createdBy = current.memberId,
                            createdAt = now,
                            lastUsedAt = null,
                        ),
                    versionIds = ids,
                )
                PartgReviewerStore.insertEvent(
                    invitationId = id,
                    kind = PartgReviewerEventKind.INVITATION_CREATED.name,
                    versionId = null,
                    format = null,
                    at = now,
                )
                audit(
                    current = current,
                    id = id,
                    action = AuditAction.CREATE,
                    kind = PartgReviewerEventKind.INVITATION_CREATED,
                    now = now,
                    count = ids.size,
                )
                toDtos(rows = listOf(PartgReviewerStore.byId(id) ?: error("invitation vanished after insert")), now = now).single()
            }
        return PartgReviewerInvitationCreatedDto(
            invitation = dto,
            link = "${FederationConfig.publicBaseUrl.trimEnd('/')}/pruefung#t=$raw",
        )
    }

    fun list(
        current: CurrentMember,
        year: Int,
    ): List<PartgReviewerInvitationDto> {
        PartgYearRules.requireValid(year)
        checkRate(current)
        val now = clock()
        return transaction {
            // opportunistic retention: it costs nothing and keeps the addresses of ended invitations from outliving their period
            PartgReviewerStore.nullEmailsEndedBefore(now.plusDuration((-PartgReviewerRules.EMAIL_RETENTION_DAYS).days))
            PartgReviewerStore.purgeExpiredSessions(now)
            toDtos(rows = PartgReviewerStore.listForYear(year), now = now)
        }
    }

    fun revoke(
        current: CurrentMember,
        invitationId: String,
    ): PartgReviewerInvitationDto {
        val id = parseId(invitationId)
        checkRate(current)
        val now = clock()
        return transaction {
            val row = PartgReviewerStore.lockById(id) ?: throw NotFoundException("Invitation not found")
            if (row.revokedAt == null) {
                PartgReviewerStore.revoke(id = id, by = current.memberId, at = now, reason = PartgReviewerStore.END_REVOKED)
                PartgReviewerStore.deleteSessionsOf(id)
                PartgReviewerStore.insertEvent(
                    invitationId = id,
                    kind = PartgReviewerEventKind.INVITATION_REVOKED.name,
                    versionId = null,
                    format = null,
                    at = now,
                )
                audit(current = current, id = id, action = AuditAction.UPDATE, kind = PartgReviewerEventKind.INVITATION_REVOKED, now = now)
            }
            toDtos(rows = listOf(PartgReviewerStore.byId(id) ?: error("invitation vanished")), now = now).single()
        }
    }

    /** Erases [email] from every invitation (case-insensitive), ends the open ones with their sessions. Returns how many invitations were touched. */
    fun erase(
        current: CurrentMember,
        email: String,
    ): Int {
        val normalized = PartgReviewerRules.normalizeEmail(email) ?: throw PartyReportInvalidInputException("Invalid address")
        checkRate(current)
        val now = clock()
        return transaction {
            val ids = PartgReviewerStore.idsWithEmail(normalized)
            ids.forEach { id ->
                val row = PartgReviewerStore.lockById(id) ?: return@forEach
                if (row.revokedAt == null) {
                    PartgReviewerStore.revoke(id = id, by = current.memberId, at = now, reason = PartgReviewerStore.END_ERASED)
                    PartgReviewerStore.deleteSessionsOf(id)
                    PartgReviewerStore.insertEvent(
                        invitationId = id,
                        kind = PartgReviewerEventKind.INVITATION_REVOKED.name,
                        versionId = null,
                        format = null,
                        at = now,
                    )
                }
                PartgReviewerStore.nullEmail(id)
                audit(
                    current = current,
                    id = id,
                    action = AuditAction.VOID,
                    kind = PartgReviewerEventKind.INVITATION_REVOKED,
                    now = now,
                    count = ids.size,
                )
            }
            ids.size
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun toDtos(
        rows: List<PartgReviewerStore.InvitationRow>,
        now: LocalDateTime,
    ): List<PartgReviewerInvitationDto> {
        if (rows.isEmpty()) return emptyList()
        val scopes = PartgReviewerStore.scopeOf(rows.map { it.id })
        val events =
            PartgReviewerStore.eventsOf(
                invitationIds = rows.map { it.id },
                limitPerInvitation = PartgReviewerRules.EVENTS_PER_INVITATION,
            )
        val numbers =
            rows
                .map { it.year }
                .distinct()
                .flatMap { PartgVersionStore.versions(it) }
                .associate { it.id to it.versionNumber }
        return rows.map { row ->
            val scope = scopes[row.id].orEmpty().sortedBy { numbers[it] ?: Int.MAX_VALUE }
            PartgReviewerInvitationDto(
                id = row.id.toString(),
                year = row.year,
                email = row.email,
                versionNumbers = scope.mapNotNull { numbers[it] },
                versionIds = scope.map { it.toString() },
                createdAt = row.createdAt,
                expiresAt = row.expiresAt,
                status = statusOf(row = row, now = now),
                lastUsedAt = row.lastUsedAt,
                events =
                    events[row.id].orEmpty().map {
                        PartgReviewerEventDto(
                            kind = PartgReviewerEventKind.valueOf(it.kind),
                            occurredAt = it.occurredAt,
                            versionNumber = it.versionId?.let { v -> numbers[v] },
                            format = it.format?.let { f -> PartgReviewerFileFormat.valueOf(f) },
                        )
                    },
            )
        }
    }

    private fun statusOf(
        row: PartgReviewerStore.InvitationRow,
        now: LocalDateTime,
    ): PartgReviewerInvitationStatus =
        when {
            row.revokedAt != null -> PartgReviewerInvitationStatus.REVOKED
            row.lockedAt != null -> PartgReviewerInvitationStatus.LOCKED
            now >= row.expiresAt -> PartgReviewerInvitationStatus.EXPIRED
            else -> PartgReviewerInvitationStatus.ACTIVE
        }

    private fun audit(
        current: CurrentMember,
        id: Uuid,
        action: AuditAction,
        kind: PartgReviewerEventKind,
        now: LocalDateTime,
        count: Int? = null,
    ) = AuditLogRecorder.record(
        actorMemberId = current.memberId,
        actorRole = current.role,
        entityType = AuditEntityType.PARTG_REVIEWER_ACCESS,
        entityId = id,
        action = action,
        after =
            Json.encodeToString(
                PartgReviewerAuditSnapshot.serializer(),
                PartgReviewerAuditSnapshot(event = kind, invitationId = id.toString(), count = count),
            ),
        occurredAt = now,
    )

    private fun checkRate(current: CurrentMember) {
        if (!writeRateLimiter.checkAndRecord("partg:write:${current.memberId}")) throw RateLimitedException()
    }

    private fun parseId(raw: String): Uuid =
        if (STRICT_UUID.matches(raw)) Uuid.parse(raw) else throw PartyReportInvalidInputException("Invalid id")

    private companion object {
        val STRICT_UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    }
}
