package network.lapis.cloud.server.member

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.memberbio.MemberPublicBioStore
import network.lapis.cloud.server.memberphoto.MemberPhotoStore
import network.lapis.cloud.server.payment.sepa.revokeMandatesForEndedMembership
import network.lapis.cloud.server.rpc.endAllOpenCommitteeMembershipsForMember
import network.lapis.cloud.server.rpc.requireRegionalChapterBeforeActivation
import network.lapis.cloud.server.rpc.revokeActiveRegionalChapterOfficerGrant
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.ESCALATED_ROLES
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.webhook.WebhookEventPublisher
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DeathDateRules
import network.lapis.cloud.shared.domain.DeathDateViolation
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.PeerActionAuditFacts
import network.lapis.cloud.shared.domain.WebhookEventType
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.LastAdminException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** Outcome of [MemberRoleStatusMutations.applyStatusChangeLocked]. */
internal data class StatusChangeOutcome(
    /** `true` when the status is login-blocking: the caller revokes the target's sessions AFTER the commit. */
    val revokeSessions: Boolean,
)

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- the role and the status change of a member, extracted VERBATIM from
 * `MemberService.updateMemberRole` / `updateMemberStatus` so that the two paths that may take them -- the direct call
 * of an administrator against a non-ADMIN target, and the execution of an APPROVED request against an ADMIN target
 * (`PrivilegedActionService`) -- run exactly the same code (last-admin protection, side effects, audit).
 *
 * Everything here runs INSIDE the caller's transaction with the target's `member` row and the id-ordered union of
 * {target account} U {every ADMIN account} already locked `FOR UPDATE` (see `PeerGuard`), and `AuditLogRecorder.record`
 * is the last lock-taking call. The peer decision is the CALLER's job: these functions assume it was taken.
 */
internal object MemberRoleStatusMutations {
    /**
     * Last-admin protection, race-safe (see the V1.9.x security fixes in the CHANGELOG): after the change at least one ADMIN
     * with a member status that is not login-blocked must remain. The TARGET is excluded from the "other admins" set (it is
     * about to lose its capability regardless of its own status); their CURRENT status is re-read, serialized by the union
     * lock the caller holds.
     */
    fun requireRemainingAdmin(
        lockedAccountRows: List<ResultRow>,
        targetId: Uuid,
    ) {
        val otherAdminMemberIds =
            lockedAccountRows
                .filter { it[AccountTable.role] == AccountRole.ADMIN && it[AccountTable.memberId] != targetId }
                .map { it[AccountTable.memberId] }
        val remainingNonBlockedAdmins =
            if (otherAdminMemberIds.isEmpty()) {
                0L
            } else {
                MemberTable
                    .selectAll()
                    .where {
                        (MemberTable.id inList otherAdminMemberIds) and
                            (MemberTable.status notInList MemberStatusSets.LOGIN_BLOCKED)
                    }.count()
            }
        if (remainingNonBlockedAdmins == 0L) throw LastAdminException()
    }

    /**
     * Changes the role of [targetId] from [currentRole] to [newRole] (the caller verified `newRole != currentRole`). Stamps
     * `account.role_changed_at`. [peerFacts] (when the change was an approved request) goes into the audit snapshot.
     */
    fun applyRoleChangeLocked(
        actor: CurrentMember?,
        targetId: Uuid,
        newRole: AccountRole,
        currentRole: AccountRole,
        memberRow: ResultRow,
        lockedAccountRows: List<ResultRow>,
        now: LocalDateTime,
        peerFacts: PeerActionAuditFacts?,
    ) {
        if (newRole != AccountRole.ADMIN) {
            // Security fix (2026-08-27, MEDIUM): the invariant is "at least one ADMIN with a non-LOGIN_BLOCKED member status
            // remains", NOT merely "a second ADMIN account exists" (see updateMemberRole history in the CHANGELOG).
            requireRemainingAdmin(lockedAccountRows = lockedAccountRows, targetId = targetId)
        }
        AccountTable.update({ AccountTable.memberId eq targetId }) {
            it[role] = newRole
            it[roleChangedAt] = now
        }
        val beforeSnapshot =
            MemberChangeSnapshot(
                displayNameChanged = false,
                emailChanged = false,
                status = memberRow[MemberTable.status],
                role = currentRole,
            )
        val afterSnapshot = beforeSnapshot.copy(role = newRole, peerAction = peerFacts)
        AuditLogRecorder.record(
            actorMemberId = actor?.memberId,
            actorRole = actor?.role,
            entityType = AuditEntityType.MEMBER,
            entityId = targetId,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(MemberChangeSnapshot.serializer(), beforeSnapshot),
            after = Json.encodeToString(MemberChangeSnapshot.serializer(), afterSnapshot),
            occurredAt = now,
        )
    }

    /**
     * Changes the status of [targetId] from [fromStatus] to [newStatus] (the caller verified the transition table, the
     * admin-only exit from DECEASED, the death date plausibility, the escalated-target rule and the no-op case).
     * Runs the last-admin protection, the regional-chapter rule, the status write and every side effect.
     */
    @Suppress("LongParameterList", "LongMethod")
    fun applyStatusChangeLocked(
        actor: CurrentMember?,
        targetId: Uuid,
        newStatus: MemberStatus,
        trimmedReason: String?,
        dateOfDeath: LocalDate?,
        row: ResultRow,
        existingRole: AccountRole?,
        lockedAccountRows: List<ResultRow>,
        now: LocalDateTime,
        regionalChapterEnforced: Boolean,
        peerFacts: PeerActionAuditFacts?,
    ): StatusChangeOutcome {
        val fromStatus = row[MemberTable.status]
        // Letzter-Admin-Schutz, race-safe (Security fix 2026-08-27, MEDIUM) -- a login-blocking status revokes an ADMIN's
        // capability exactly as a role downgrade does.
        if (existingRole == AccountRole.ADMIN && newStatus in MemberStatusSets.LOGIN_BLOCKED) {
            requireRemainingAdmin(lockedAccountRows = lockedAccountRows, targetId = targetId)
        }

        // Welle V1.9.13 -- called BEFORE the status write.
        if (newStatus == MemberStatus.ACTIVE) {
            requireRegionalChapterBeforeActivation(memberId = targetId, enabled = regionalChapterEnforced)
        }

        // Welle V1.4.4.5 -- paragraph 38 BGB: clearing date_of_death when LEAVING DECEASED must happen in the SAME update.
        val previousDateOfDeath = row[MemberTable.dateOfDeath]
        MemberTable.update({ MemberTable.id eq targetId }) {
            it[status] = newStatus
            if (newStatus == MemberStatus.DECEASED) {
                it[MemberTable.dateOfDeath] = dateOfDeath
            } else if (fromStatus == MemberStatus.DECEASED) {
                it[MemberTable.dateOfDeath] = null
            }
        }
        // Welle V1.9.59 -- status history, right after the successful write; the caller holds the member row lock. This is the
        // one central path of MemberService.updateMemberStatus, the privileged-action execution and the operator console.
        MemberStatusHistory.recordLocked(
            memberId = targetId,
            newStatus = newStatus,
            now = now,
            source = MemberStatusHistorySource.LIVE,
        )
        val newDateOfDeath = if (newStatus == MemberStatus.DECEASED) dateOfDeath else null

        if (fromStatus == MemberStatus.ACTIVE) {
            // The operator console (no actor) only ever re-activates: leaving ACTIVE needs a person to attribute the cleanup to.
            checkNotNull(actor) { "leaving ACTIVE needs a signed-in actor" }
            revokeActiveRegionalChapterOfficerGrant(
                memberId = targetId,
                now = now,
                actorMemberId = actor.memberId,
                actorRole = actor.role,
            )
        }
        MemberPhotoStore.revokePublicationOnStatusLoss(
            memberId = targetId,
            newStatus = newStatus,
            actorMemberId = actor?.memberId,
            actorRole = actor?.role,
            now = now,
        )
        MemberPublicBioStore.revokePublicationOnStatusLoss(
            memberId = targetId,
            newStatus = newStatus,
            actorMemberId = actor?.memberId,
            actorRole = actor?.role,
            now = now,
        )
        if (newStatus == MemberStatus.ACTIVE) {
            WebhookEventPublisher.publish(eventType = WebhookEventType.MEMBER_CREATED, entityId = targetId, occurredAt = now)
        }
        if (newStatus in MemberStatusSets.MEMBERSHIP_ENDED) {
            checkNotNull(actor) { "ending a membership needs a signed-in actor" }
            endAllOpenCommitteeMembershipsForMember(
                memberId = targetId,
                until = OrganizationTimeZone.dateOf(now),
                current = actor,
            )
            revokeMandatesForEndedMembership(
                memberId = targetId,
                actorMemberId = actor.memberId,
                actorRole = actor.role,
                now = now,
            )
        }

        val beforeSnapshot =
            MemberChangeSnapshot(
                displayNameChanged = false,
                emailChanged = false,
                status = fromStatus,
                role = existingRole,
            )
        val afterSnapshot =
            beforeSnapshot.copy(
                status = newStatus,
                reason = trimmedReason,
                dateOfDeathChanged = newDateOfDeath != previousDateOfDeath,
                peerAction = peerFacts,
            )
        AuditLogRecorder.record(
            actorMemberId = actor?.memberId,
            actorRole = actor?.role,
            entityType = AuditEntityType.MEMBER,
            entityId = targetId,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(MemberChangeSnapshot.serializer(), beforeSnapshot),
            after = Json.encodeToString(MemberChangeSnapshot.serializer(), afterSnapshot),
            occurredAt = now,
        )
        return StatusChangeOutcome(revokeSessions = newStatus in MemberStatusSets.LOGIN_BLOCKED)
    }

    /** The escalated-target rule: BOARD/TREASURER/ADMIN targets may only be handled by an ADMIN caller. */
    fun requireAdminForEscalatedTarget(
        actor: CurrentMember,
        existingRole: AccountRole?,
    ) {
        if (existingRole != null && existingRole in ESCALATED_ROLES) actor.requireRole(AccountRole.ADMIN)
    }

    /** Welle V1.4.4.5 -- shared by the status change (ACTIVE to DECEASED) and the correction of a date of death. */
    fun requirePlausibleDeathDate(
        dateOfDeath: LocalDate?,
        row: ResultRow,
        now: LocalDateTime,
    ) {
        when (
            DeathDateRules.violation(
                dateOfDeath = dateOfDeath,
                dateOfBirth = row[MemberTable.dateOfBirth],
                today = OrganizationTimeZone.dateOf(now),
            )
        ) {
            DeathDateViolation.IN_FUTURE -> throw ConflictException("A date of death cannot be in the future")
            DeathDateViolation.BEFORE_BIRTH -> throw ConflictException("A date of death cannot precede the date of birth")
            null -> Unit
        }
    }
}
