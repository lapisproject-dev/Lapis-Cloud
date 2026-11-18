package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.PartgReviewerEventTable
import network.lapis.cloud.server.db.generated.PartgReviewerInvitationTable
import network.lapis.cloud.server.db.generated.PartgReviewerInvitationVersionTable
import network.lapis.cloud.server.db.generated.PartgReviewerSessionTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.310 (R6b) -- the ONLY reader and writer of `partg_reviewer_invitation`, `partg_reviewer_invitation_version`, `partg_reviewer_session` and
 * `partg_reviewer_event` (pinned by `PartgReviewerStoreTripwireTest`; the one exception is the data subject contributor `PartgReportPersonalData`).
 * Every function runs inside the caller's transaction. Only hashes are stored; a function that takes a raw token or a raw code does not exist.
 * Lock order: the invitation row (`lockByTokenHash` / `lockById`), never a counter row (the public path of the auditor takes no counter lock, so
 * the deletion routine, which takes the counters first and then the invitations, cannot deadlock with it). Nothing here logs.
 */
internal object PartgReviewerStore {
    data class InvitationRow(
        val id: Uuid,
        val year: Int,
        val email: String?,
        val linkTokenHash: String,
        val codeHash: String?,
        val codeExpiresAt: LocalDateTime?,
        val codeAttempts: Int,
        val codesBurned: Int,
        val codesSentTotal: Int,
        val codesWindowStart: LocalDateTime?,
        val codesWindowCount: Int,
        val lockedAt: LocalDateTime?,
        val expiresAt: LocalDateTime,
        val revokedAt: LocalDateTime?,
        val revokedBy: Uuid?,
        val endReason: String?,
        val createdBy: Uuid,
        val createdAt: LocalDateTime,
        val lastUsedAt: LocalDateTime?,
    )

    data class SessionRow(
        val id: Uuid,
        val invitationId: Uuid,
        val createdAt: LocalDateTime,
        val lastSeenAt: LocalDateTime,
        val idleExpiresAt: LocalDateTime,
        val absoluteExpiresAt: LocalDateTime,
    )

    data class EventRow(
        val invitationId: Uuid,
        val kind: String,
        val versionId: Uuid?,
        val format: String?,
        val occurredAt: LocalDateTime,
    )

    private fun toInvitation(row: ResultRow) =
        InvitationRow(
            id = row[PartgReviewerInvitationTable.id],
            year = row[PartgReviewerInvitationTable.reportYear],
            email = row[PartgReviewerInvitationTable.inviteeEmail],
            linkTokenHash = row[PartgReviewerInvitationTable.linkTokenHash],
            codeHash = row[PartgReviewerInvitationTable.codeHash],
            codeExpiresAt = row[PartgReviewerInvitationTable.codeExpiresAt],
            codeAttempts = row[PartgReviewerInvitationTable.codeAttempts],
            codesBurned = row[PartgReviewerInvitationTable.codesBurned],
            codesSentTotal = row[PartgReviewerInvitationTable.codesSentTotal],
            codesWindowStart = row[PartgReviewerInvitationTable.codesWindowStart],
            codesWindowCount = row[PartgReviewerInvitationTable.codesWindowCount],
            lockedAt = row[PartgReviewerInvitationTable.lockedAt],
            expiresAt = row[PartgReviewerInvitationTable.expiresAt],
            revokedAt = row[PartgReviewerInvitationTable.revokedAt],
            revokedBy = row[PartgReviewerInvitationTable.revokedBy],
            endReason = row[PartgReviewerInvitationTable.endReason],
            createdBy = row[PartgReviewerInvitationTable.createdBy],
            createdAt = row[PartgReviewerInvitationTable.createdAt],
            lastUsedAt = row[PartgReviewerInvitationTable.lastUsedAt],
        )

    private fun toSession(row: ResultRow) =
        SessionRow(
            id = row[PartgReviewerSessionTable.id],
            invitationId = row[PartgReviewerSessionTable.invitationId],
            createdAt = row[PartgReviewerSessionTable.createdAt],
            lastSeenAt = row[PartgReviewerSessionTable.lastSeenAt],
            idleExpiresAt = row[PartgReviewerSessionTable.idleExpiresAt],
            absoluteExpiresAt = row[PartgReviewerSessionTable.absoluteExpiresAt],
        )

    // ---------------------------------------------------------------- invitations

    fun insertInvitation(
        row: InvitationRow,
        versionIds: Collection<Uuid>,
    ) {
        PartgReviewerInvitationTable.insert {
            it[id] = row.id
            it[reportYear] = row.year
            it[inviteeEmail] = row.email
            it[linkTokenHash] = row.linkTokenHash
            it[codeHash] = row.codeHash
            it[codeExpiresAt] = row.codeExpiresAt
            it[codeAttempts] = row.codeAttempts
            it[codesBurned] = row.codesBurned
            it[codesSentTotal] = row.codesSentTotal
            it[codesWindowStart] = row.codesWindowStart
            it[codesWindowCount] = row.codesWindowCount
            it[lockedAt] = row.lockedAt
            it[expiresAt] = row.expiresAt
            it[revokedAt] = row.revokedAt
            it[revokedBy] = row.revokedBy
            it[endReason] = row.endReason
            it[createdBy] = row.createdBy
            it[createdAt] = row.createdAt
            it[lastUsedAt] = row.lastUsedAt
        }
        PartgReviewerInvitationVersionTable.batchInsert(versionIds.toList()) { versionId ->
            this[PartgReviewerInvitationVersionTable.invitationId] = row.id
            this[PartgReviewerInvitationVersionTable.versionId] = versionId
        }
    }

    /** FOR UPDATE: serialises every parallel try on one invitation (a lost update would allow brute force). */
    fun lockByTokenHash(hash: String): InvitationRow? =
        PartgReviewerInvitationTable
            .selectAll()
            .where { PartgReviewerInvitationTable.linkTokenHash eq hash }
            .forUpdate()
            .singleOrNull()
            ?.let(::toInvitation)

    fun lockById(id: Uuid): InvitationRow? =
        PartgReviewerInvitationTable
            .selectAll()
            .where { PartgReviewerInvitationTable.id eq id }
            .forUpdate()
            .singleOrNull()
            ?.let(::toInvitation)

    fun byId(id: Uuid): InvitationRow? =
        PartgReviewerInvitationTable
            .selectAll()
            .where { PartgReviewerInvitationTable.id eq id }
            .singleOrNull()
            ?.let(::toInvitation)

    fun listForYear(year: Int): List<InvitationRow> =
        PartgReviewerInvitationTable
            .selectAll()
            .where { PartgReviewerInvitationTable.reportYear eq year }
            .orderBy(PartgReviewerInvitationTable.createdAt to SortOrder.DESC, PartgReviewerInvitationTable.id to SortOrder.ASC)
            .map(::toInvitation)

    /** Invitations of [year] that have not ended at [now] (not revoked, not expired). Locked ones count: they still hold a link. */
    fun countOpenForYear(
        year: Int,
        now: LocalDateTime,
    ): Long =
        PartgReviewerInvitationTable
            .selectAll()
            .where {
                (PartgReviewerInvitationTable.reportYear eq year) and
                    PartgReviewerInvitationTable.revokedAt.isNull() and
                    (PartgReviewerInvitationTable.expiresAt greater now)
            }.count()

    /** A new code: the hash, its end, and the counters (attempts back to 0). */
    fun setCode(
        id: Uuid,
        codeHash: String,
        expiresAt: LocalDateTime,
        windowStart: LocalDateTime,
        windowCount: Int,
        sentTotal: Int,
    ) {
        PartgReviewerInvitationTable.update({ PartgReviewerInvitationTable.id eq id }) {
            it[PartgReviewerInvitationTable.codeHash] = codeHash
            it[codeExpiresAt] = expiresAt
            it[codeAttempts] = 0
            it[codesWindowStart] = windowStart
            it[codesWindowCount] = windowCount
            it[codesSentTotal] = sentTotal
        }
    }

    /** One wrong try. [burnCode]: the code is spent (hash gone, attempts back to 0, [burned] counted). [lockedAt]: the invitation is locked. */
    fun recordFailedAttempt(
        id: Uuid,
        attempts: Int,
        burnCode: Boolean,
        burned: Int,
        lockedAt: LocalDateTime?,
    ) {
        PartgReviewerInvitationTable.update({ PartgReviewerInvitationTable.id eq id }) {
            if (burnCode) {
                it[codeHash] = null
                it[codeExpiresAt] = null
                it[codeAttempts] = 0
                it[codesBurned] = burned
            } else {
                it[codeAttempts] = attempts
            }
            if (lockedAt != null) it[PartgReviewerInvitationTable.lockedAt] = lockedAt
        }
    }

    /** The right code was used: it is gone (one use only). */
    fun consumeCode(id: Uuid) {
        PartgReviewerInvitationTable.update({ PartgReviewerInvitationTable.id eq id }) {
            it[codeHash] = null
            it[codeExpiresAt] = null
            it[codeAttempts] = 0
        }
    }

    fun markUsed(
        id: Uuid,
        now: LocalDateTime,
    ) {
        PartgReviewerInvitationTable.update({ PartgReviewerInvitationTable.id eq id }) { it[lastUsedAt] = now }
    }

    /** Ends an invitation: the pending code goes too. [by] is `null` for the system (scope deleted, address erased). */
    fun revoke(
        id: Uuid,
        by: Uuid?,
        at: LocalDateTime,
        reason: String,
    ) {
        PartgReviewerInvitationTable.update({ PartgReviewerInvitationTable.id eq id }) {
            it[revokedAt] = at
            it[revokedBy] = by
            it[endReason] = reason
            it[codeHash] = null
            it[codeExpiresAt] = null
        }
    }

    fun scopeVersionIds(invitationId: Uuid): List<Uuid> =
        PartgReviewerInvitationVersionTable
            .selectAll()
            .where { PartgReviewerInvitationVersionTable.invitationId eq invitationId }
            .map { it[PartgReviewerInvitationVersionTable.versionId] }

    fun scopeOf(invitationIds: Collection<Uuid>): Map<Uuid, List<Uuid>> {
        if (invitationIds.isEmpty()) return emptyMap()
        return PartgReviewerInvitationVersionTable
            .selectAll()
            .where { PartgReviewerInvitationVersionTable.invitationId inList invitationIds.toList() }
            .groupBy({ it[PartgReviewerInvitationVersionTable.invitationId] }, { it[PartgReviewerInvitationVersionTable.versionId] })
    }

    fun inScope(
        invitationId: Uuid,
        versionId: Uuid,
    ): Boolean =
        !PartgReviewerInvitationVersionTable
            .selectAll()
            .where {
                (PartgReviewerInvitationVersionTable.invitationId eq invitationId) and
                    (PartgReviewerInvitationVersionTable.versionId eq versionId)
            }.empty()

    /**
     * The deletion routine of old versions calls this BEFORE it removes [versionIds] (there is no cascade): the scope rows of those versions go,
     * every invitation whose scope becomes empty is ended with `SCOPE_DELETED`, its sessions go and an `INVITATION_REVOKED` event is written.
     * The affected invitation rows are locked in id order. Returns the number of invitations that ended.
     */
    fun detachVersions(
        versionIds: Collection<Uuid>,
        now: LocalDateTime,
    ): Int {
        if (versionIds.isEmpty()) return 0
        val list = versionIds.toList()
        val affected =
            PartgReviewerInvitationVersionTable
                .selectAll()
                .where { PartgReviewerInvitationVersionTable.versionId inList list }
                .map { it[PartgReviewerInvitationVersionTable.invitationId] }
                .distinct()
                .sorted()
        if (affected.isEmpty()) return 0
        affected.forEach { lockById(it) }
        PartgReviewerInvitationVersionTable.deleteWhere { PartgReviewerInvitationVersionTable.versionId inList list }
        var ended = 0
        for (invitationId in affected) {
            val invitation = byId(invitationId) ?: continue
            if (invitation.revokedAt == null && scopeVersionIds(invitationId).isEmpty()) {
                revoke(id = invitationId, by = null, at = now, reason = END_SCOPE_DELETED)
                deleteSessionsOf(invitationId)
                insertEvent(invitationId = invitationId, kind = "INVITATION_REVOKED", versionId = null, format = null, at = now)
                ended++
            }
        }
        return ended
    }

    // ---------------------------------------------------------------- sessions

    fun insertSession(
        id: Uuid,
        tokenHash: String,
        invitationId: Uuid,
        now: LocalDateTime,
        idleExpiresAt: LocalDateTime,
        absoluteExpiresAt: LocalDateTime,
    ) {
        PartgReviewerSessionTable.insert {
            it[PartgReviewerSessionTable.id] = id
            it[PartgReviewerSessionTable.tokenHash] = tokenHash
            it[PartgReviewerSessionTable.invitationId] = invitationId
            it[createdAt] = now
            it[lastSeenAt] = now
            it[PartgReviewerSessionTable.idleExpiresAt] = idleExpiresAt
            it[PartgReviewerSessionTable.absoluteExpiresAt] = absoluteExpiresAt
        }
    }

    fun sessionByTokenHash(hash: String): SessionRow? =
        PartgReviewerSessionTable
            .selectAll()
            .where { PartgReviewerSessionTable.tokenHash eq hash }
            .singleOrNull()
            ?.let(::toSession)

    fun touchSession(
        id: Uuid,
        now: LocalDateTime,
        newIdle: LocalDateTime,
    ) {
        PartgReviewerSessionTable.update({ PartgReviewerSessionTable.id eq id }) {
            it[lastSeenAt] = now
            it[idleExpiresAt] = newIdle
        }
    }

    fun deleteSession(id: Uuid) {
        PartgReviewerSessionTable.deleteWhere { PartgReviewerSessionTable.id eq id }
    }

    fun deleteSessionsOf(invitationId: Uuid) {
        PartgReviewerSessionTable.deleteWhere { PartgReviewerSessionTable.invitationId eq invitationId }
    }

    /** Sessions past their idle or absolute end (the retention poller). Returns how many went. */
    fun purgeExpiredSessions(now: LocalDateTime): Int =
        PartgReviewerSessionTable.deleteWhere {
            (PartgReviewerSessionTable.idleExpiresAt less now) or (PartgReviewerSessionTable.absoluteExpiresAt less now)
        }

    // ---------------------------------------------------------------- events

    fun insertEvent(
        invitationId: Uuid,
        kind: String,
        versionId: Uuid?,
        format: String?,
        at: LocalDateTime,
    ) {
        PartgReviewerEventTable.insert {
            it[id] = Uuid.random()
            it[PartgReviewerEventTable.invitationId] = invitationId
            it[PartgReviewerEventTable.kind] = kind
            it[PartgReviewerEventTable.versionId] = versionId
            it[PartgReviewerEventTable.format] = format
            it[occurredAt] = at
        }
    }

    /** The newest [limitPerInvitation] events of each invitation, newest first. */
    fun eventsOf(
        invitationIds: Collection<Uuid>,
        limitPerInvitation: Int,
    ): Map<Uuid, List<EventRow>> {
        if (invitationIds.isEmpty()) return emptyMap()
        return PartgReviewerEventTable
            .selectAll()
            .where { PartgReviewerEventTable.invitationId inList invitationIds.toList() }
            .orderBy(PartgReviewerEventTable.occurredAt to SortOrder.DESC, PartgReviewerEventTable.id to SortOrder.ASC)
            .map {
                EventRow(
                    invitationId = it[PartgReviewerEventTable.invitationId],
                    kind = it[PartgReviewerEventTable.kind],
                    versionId = it[PartgReviewerEventTable.versionId],
                    format = it[PartgReviewerEventTable.format],
                    occurredAt = it[PartgReviewerEventTable.occurredAt],
                )
            }.groupBy { it.invitationId }
            .mapValues { (_, rows) -> rows.take(limitPerInvitation) }
    }

    // ---------------------------------------------------------------- personal data (E-mail address of a third party)

    /** Sets the address of every invitation that ended before [cutoff] to NULL. Returns how many. */
    fun nullEmailsEndedBefore(cutoff: LocalDateTime): Int =
        PartgReviewerInvitationTable.update({
            PartgReviewerInvitationTable.inviteeEmail.isNotNull() and
                (
                    (PartgReviewerInvitationTable.revokedAt less cutoff) or
                        (PartgReviewerInvitationTable.revokedAt.isNull() and (PartgReviewerInvitationTable.expiresAt less cutoff))
                )
        }) {
            it[inviteeEmail] = null
        }

    /** Ids of the invitations that carry exactly this (already lower-cased) address. */
    fun idsWithEmail(emailLower: String): List<Uuid> =
        PartgReviewerInvitationTable
            .selectAll()
            .where { PartgReviewerInvitationTable.inviteeEmail eq emailLower }
            .orderBy(PartgReviewerInvitationTable.id to SortOrder.ASC)
            .map { it[PartgReviewerInvitationTable.id] }

    fun nullEmail(id: Uuid) {
        PartgReviewerInvitationTable.update({ PartgReviewerInvitationTable.id eq id }) { it[inviteeEmail] = null }
    }

    const val END_REVOKED = "REVOKED"
    const val END_SCOPE_DELETED = "SCOPE_DELETED"
    const val END_ERASED = "ERASED"
}
