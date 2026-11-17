package network.lapis.cloud.server.member

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.forMemberUpdate
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ExternalMemberIdAuditEvent
import network.lapis.cloud.shared.domain.ExternalMemberIdAuditFacts
import network.lapis.cloud.shared.domain.ExternalMemberIdRules
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ExternalMemberIdTakenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.307 "Externe Mitglieds-ID" -- the **only** writer of `member.external_member_id` and `member.external_member_id_source`
 * (`ExternalMemberIdWriterTripwireTest`). The one exception, by design: the DSGVO erasure (`FoundationPersonalData`) resets both columns in the
 * same `update` that clears the personal columns.
 *
 * Every function here must run inside an already-open `transaction {}` and takes the member row lock itself (`forMemberUpdate()`), never
 * `forUpdate()` on `MemberTable`. Nothing here ever touches `oidc_subject` or the Keycloak link.
 *
 * Logs and audit entries carry reason codes, the source label and the subject fingerprint -- NEVER the id value (the audit log is hash-chained
 * and cannot be erased, so a value there would be a DSGVO trap).
 */
internal object ExternalMemberIdStore {
    /** What [applyClaim] did. Expected cases are outcomes, never exceptions. */
    sealed interface ClaimOutcome {
        /** The claim is missing, not a string or empty: nothing to do. */
        data object Absent : ClaimOutcome

        /** The claim value violates [ExternalMemberIdRules]: nothing stored. */
        data object Invalid : ClaimOutcome

        data object Unchanged : ClaimOutcome

        /** The id was stored. */
        data object Set : ClaimOutcome

        /** The member already holds a DIFFERENT id (or one of another source): kept, never overwritten. */
        data object Mismatch : ClaimOutcome

        /** Another member of the same source holds this id: nothing stored. */
        data object Duplicate : ClaimOutcome

        data object MemberAnonymized : ClaimOutcome

        data object MemberMissing : ClaimOutcome
    }

    /** Dedup window of the non-state-changing audit entries (mismatch/duplicate): one per member and window, so a login loop cannot flood the chain. */
    private val NOISE_AUDIT_WINDOW = 24.hours

    /**
     * Applies the ID-token claim [rawClaimValue] for [memberId]. Runs for every Keycloak login of a member. Never blocks anything: the caller maps
     * the outcome to a log line only. The audit write (a global chain-row lock) is handed to [auditScheduler] instead of being executed inline when
     * the caller must order it after other writes (JIT provisioning: audit entries must come last and after the member's CREATE entry).
     */
    fun applyClaim(
        memberId: Uuid,
        rawClaimValue: Any?,
        source: String,
        subjectFingerprint: String,
        now: LocalDateTime,
        auditScheduler: (() -> Unit) -> Unit = { it() },
    ): ClaimOutcome {
        val raw = (rawClaimValue as? String) ?: return ClaimOutcome.Absent
        if (raw.isBlank()) return ClaimOutcome.Absent
        val id =
            ExternalMemberIdRules.normalizeId(raw)
                ?: run {
                    logger.warn { "EXTERNAL_ID_CLAIM_INVALID subjectFingerprint=$subjectFingerprint" }
                    return ClaimOutcome.Invalid
                }
        val row =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq memberId }
                .forMemberUpdate()
                .singleOrNull()
                ?: return ClaimOutcome.MemberMissing
        if (row[MemberTable.anonymizedAt] != null) return ClaimOutcome.MemberAnonymized
        val storedId = row[MemberTable.externalMemberId]
        val storedSource = row[MemberTable.externalMemberIdSource]
        if (storedId != null) {
            if (storedId == id && storedSource == source) return ClaimOutcome.Unchanged
            logger.warn { "EXTERNAL_ID_CLAIM_MISMATCH memberId=$memberId subjectFingerprint=$subjectFingerprint" }
            auditScheduler {
                recordNoiseAudit(
                    row = row,
                    event = ExternalMemberIdAuditEvent.CLAIM_MISMATCH,
                    source = storedSource,
                    now = now,
                )
            }
            return ClaimOutcome.Mismatch
        }
        val stored =
            try {
                withSavepoint(name = "external_member_id") {
                    MemberTable.update({ MemberTable.id eq memberId }) {
                        it[externalMemberId] = id
                        it[externalMemberIdSource] = source
                    }
                }
                true
            } catch (e: ExposedSQLException) {
                if (!e.isUniqueViolation()) throw e
                false
            }
        if (!stored) {
            logger.warn { "EXTERNAL_ID_DUPLICATE memberId=$memberId subjectFingerprint=$subjectFingerprint" }
            auditScheduler { recordNoiseAudit(row = row, event = ExternalMemberIdAuditEvent.CLAIM_DUPLICATE, source = source, now = now) }
            return ClaimOutcome.Duplicate
        }
        auditScheduler {
            recordAudit(
                actorMemberId = null,
                actorRole = null,
                targetMemberId = memberId,
                targetStatus = row[MemberTable.status],
                facts = ExternalMemberIdAuditFacts(event = ExternalMemberIdAuditEvent.SET_BY_CLAIM, source = source),
                now = now,
            )
        }
        logger.info { "EXTERNAL_ID_SET_BY_CLAIM memberId=$memberId subjectFingerprint=$subjectFingerprint" }
        return ClaimOutcome.Set
    }

    /**
     * BOARD/ADMIN sets or REPLACES the id by hand. [rawId] is validated here ([BadRequestException]); a duplicate within [source] is an
     * [ExternalMemberIdTakenException] (unique violation through a savepoint, so PostgreSQL's transaction stays usable); an anonymised member is a
     * [ConflictException]. A call that changes nothing writes no audit entry.
     */
    fun setManual(
        memberId: Uuid,
        rawId: String,
        source: String,
        actor: CurrentMember,
        now: LocalDateTime,
    ) {
        val id = ExternalMemberIdRules.normalizeId(rawId) ?: throw BadRequestException("Invalid external member id")
        val row = lockRow(memberId)
        if (row[MemberTable.anonymizedAt] != null) throw ConflictException("Member has been anonymized and can no longer be edited")
        val storedId = row[MemberTable.externalMemberId]
        if (storedId == id && row[MemberTable.externalMemberIdSource] == source) return
        try {
            withSavepoint(name = "external_member_id_manual") {
                MemberTable.update({ MemberTable.id eq memberId }) {
                    it[externalMemberId] = id
                    it[externalMemberIdSource] = source
                }
            }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueViolation()) throw ExternalMemberIdTakenException()
            throw e
        }
        recordAudit(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            targetMemberId = memberId,
            targetStatus = row[MemberTable.status],
            facts =
                ExternalMemberIdAuditFacts(
                    event = if (storedId == null) ExternalMemberIdAuditEvent.SET_MANUALLY else ExternalMemberIdAuditEvent.REPLACED_MANUALLY,
                    source = source,
                ),
            now = now,
        )
    }

    /** BOARD/ADMIN removes the id. A no-op (and no audit entry) without one. */
    fun clearManual(
        memberId: Uuid,
        actor: CurrentMember,
        now: LocalDateTime,
    ) {
        val row = lockRow(memberId)
        if (row[MemberTable.externalMemberId] == null) return
        val source = row[MemberTable.externalMemberIdSource]
        MemberTable.update({ MemberTable.id eq memberId }) {
            it[externalMemberId] = null
            it[externalMemberIdSource] = null
        }
        recordAudit(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            targetMemberId = memberId,
            targetStatus = row[MemberTable.status],
            facts = ExternalMemberIdAuditFacts(event = ExternalMemberIdAuditEvent.CLEARED_MANUALLY, source = source),
            now = now,
        )
    }

    private fun lockRow(memberId: Uuid): ResultRow =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .forMemberUpdate()
            .singleOrNull()
            ?: throw NotFoundException("Member not found")

    /** Mismatch/duplicate carry no state change: at most one entry per member and [NOISE_AUDIT_WINDOW] (a login loop must not flood the hash chain). */
    private fun recordNoiseAudit(
        row: ResultRow,
        event: ExternalMemberIdAuditEvent,
        source: String?,
        now: LocalDateTime,
    ) {
        val memberId = row[MemberTable.id]
        val marker = EVENT_MARKERS.getValue(event)
        val cutoff = EmailChangeStore.plus(at = now, duration = -NOISE_AUDIT_WINDOW)
        val already =
            AuditLogEntryTable
                .selectAll()
                .where {
                    (AuditLogEntryTable.entityId eq memberId) and
                        (AuditLogEntryTable.occurredAt greaterEq cutoff) and
                        (AuditLogEntryTable.afterSnapshot like "%$marker%")
                }.limit(1)
                .any()
        if (already) return
        recordAudit(
            actorMemberId = null,
            actorRole = null,
            targetMemberId = memberId,
            targetStatus = row[MemberTable.status],
            facts = ExternalMemberIdAuditFacts(event = event, source = source),
            now = now,
        )
    }

    private val EVENT_MARKERS =
        mapOf(
            ExternalMemberIdAuditEvent.CLAIM_MISMATCH to "member.external_id.claim_mismatch",
            ExternalMemberIdAuditEvent.CLAIM_DUPLICATE to "member.external_id.claim_duplicate",
        )

    /** One hash-chained audit entry, `MEMBER`/`UPDATE`, flags and source only. Call it LAST in the transaction. */
    fun recordAudit(
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
        targetMemberId: Uuid,
        targetStatus: MemberStatus,
        facts: ExternalMemberIdAuditFacts,
        now: LocalDateTime,
    ) {
        val before = MemberChangeSnapshot(displayNameChanged = false, emailChanged = false, status = targetStatus, role = null)
        AuditLogRecorder.record(
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            entityType = AuditEntityType.MEMBER,
            entityId = targetMemberId,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(MemberChangeSnapshot.serializer(), before),
            after = Json.encodeToString(MemberChangeSnapshot.serializer(), before.copy(externalMemberId = facts)),
            occurredAt = now,
        )
    }
}
