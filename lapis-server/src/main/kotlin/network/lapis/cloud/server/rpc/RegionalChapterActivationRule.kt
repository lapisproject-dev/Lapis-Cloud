package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.RegionalChapterOfficerTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.RegionalChapterOfficerSnapshot
import network.lapis.cloud.shared.rpc.RegionalChapterRequiredException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" -- once at least one `regional_chapter`
 * row exists AND [enabled] (see [RegionalChapterEnforcementConfig] KDoc "Review-fix reasoning" for
 * why this defaults to off), every member becoming ACTIVE must already have one assigned. Called
 * INSIDE the caller's open transaction, BEFORE the status write, at exactly three places:
 * [RegistrationService.approveApplication], [RegistrationService.createMemberDirect] (as an input
 * check, via [RegistrationService.requireValidRegionalChapterSelection] rather than this function
 * directly), and [MemberService.updateMemberStatus] (only when `newStatus == ACTIVE &&
 * fromStatus != ACTIVE`). A pre-existing ACTIVE member without a chapter (every member that
 * existed before this wave, and -- while [enabled] is `false` -- every member activated since) is
 * left alone -- this rule only ever gates the MOMENT a member BECOMES ACTIVE, never a member who
 * already is.
 */
internal fun requireRegionalChapterBeforeActivation(
    memberId: Uuid,
    enabled: Boolean,
) {
    check(TransactionManager.currentOrNull() != null) {
        "requireRegionalChapterBeforeActivation must be called from inside an already-open transaction {} block"
    }
    if (!enabled) return
    val anyChapterExists = RegionalChapterTable.selectAll().count() > 0
    if (!anyChapterExists) return
    val hasChapter =
        MemberTable
            .select(MemberTable.regionalChapterId)
            .where { MemberTable.id eq memberId }
            .singleOrNull()
            ?.get(MemberTable.regionalChapterId) != null
    if (!hasChapter) throw RegionalChapterRequiredException()
}

/**
 * Welle V1.9.13, decision F1 (Empfehlung "ja") -- a regional-chapter-officer grant loses its
 * PURPOSE the moment its holder leaves [MemberStatus.ACTIVE] (Austritt/Tod/Spender-Rückstufung
 * etc.): the visibility rule ([network.lapis.cloud.server.security.memberVisibility]) already
 * requires `status in MemberStatusSets.ORGANIZATION_MEMBER` independently, so leaving this grant
 * dangling would have no IMMEDIATE effect -- but a later re-activation (e.g. WITHDRAWN -> ACTIVE)
 * would otherwise silently revive a dormant grant the ADMIN never re-decided on. Called from
 * [MemberService.updateMemberStatus] (when `fromStatus == ACTIVE && newStatus != ACTIVE`, actor =
 * the privileged caller) and [RegistrationService.leaveMembership] (actor = the leaving member
 * themselves, a self-service revoke). Idempotent no-op if the member holds no active grant.
 *
 * **Audit-review fix.** Writes the exact same `AuditEntityType.REGIONAL_CHAPTER_OFFICER` /
 * `AuditAction.UPDATE` entry [RegionalChapterService.revokeOfficerGrantRow] writes for an
 * ADMIN-initiated `revokeOfficer`/`assignMemberToChapter` revoke -- before this fix, a grant ended
 * via a status change or self-service leave vanished from the audit trail's perspective (no CREATE
 * counterpart, since the ORIGINAL grant CREATE entry stays, but no matching revoke entry either), so
 * an auditor could not tell WHEN or WHY the access ended, only that it once existed.
 */
internal fun revokeActiveRegionalChapterOfficerGrant(
    memberId: Uuid,
    now: LocalDateTime,
    actorMemberId: Uuid,
    actorRole: AccountRole,
) {
    check(TransactionManager.currentOrNull() != null) {
        "revokeActiveRegionalChapterOfficerGrant must be called from inside an already-open transaction {} block"
    }
    val activeGrant =
        RegionalChapterOfficerTable
            .selectAll()
            .where { RegionalChapterOfficerTable.activeForMemberId eq memberId }
            .singleOrNull() ?: return
    RegionalChapterOfficerTable.update({ RegionalChapterOfficerTable.activeForMemberId eq memberId }) {
        it[revokedAt] = now
        it[activeForMemberId] = null
    }
    AuditLogRecorder.record(
        actorMemberId = actorMemberId,
        actorRole = actorRole,
        entityType = AuditEntityType.REGIONAL_CHAPTER_OFFICER,
        entityId = activeGrant[RegionalChapterOfficerTable.id],
        action = AuditAction.UPDATE,
        before =
            Json.encodeToString(
                RegionalChapterOfficerSnapshot.serializer(),
                RegionalChapterOfficerSnapshot(
                    memberId = memberId.toString(),
                    regionalChapterId = activeGrant[RegionalChapterOfficerTable.regionalChapterId].toString(),
                ),
            ),
        after = null,
        occurredAt = now,
    )
}
