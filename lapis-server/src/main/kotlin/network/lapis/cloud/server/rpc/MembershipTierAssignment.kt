package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberMembershipTierSnapshot
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.4.4.4 "Familienmitgliedschaften" -- the ONE production write path for
 * `member.membership_tier_id` in this entire codebase, called from BOTH
 * [network.lapis.cloud.server.rpc.MemberService.updateMemberMembershipTier] (the manual,
 * TREASURER/ADMIN-vs-BOARD-gated path) and [network.lapis.cloud.server.rpc.MemberFamilyService]
 * (the `addFamilyMember`/`changePayer` side-effect path) -- same "there is deliberately no second
 * implementation" doctrine
 * `network.lapis.cloud.server.payment.sepa.MembershipEndedMandateRevocation` already establishes
 * for a structurally identical "two RPC entry points, one shared write path" shape.
 *
 * Checks **no roles at all** -- role gating is entirely the caller's responsibility, applied at
 * the RPC boundary BEFORE this function is ever reached (see `MemberService
 * .updateMemberMembershipTier`'s own Rollen-Asymmetrie KDoc). Runs inside the CALLER's
 * `transaction {}`, exactly like every [network.lapis.cloud.server.dsgvo.PersonalDataContributor]
 * implementation -- never opens its own.
 *
 * **Ausdrücklich NICHT Teil dieser Welle**: an already-generated `contribution` row is NEVER
 * retroactively deleted, voided, or rewritten by a tier change here. An existing charge is a
 * bookkeeping fact; correcting one is the job of the regular storno/write-off path, which has its
 * own scope and its own review. This function's ENTIRE effect is limited to
 * `member.membership_tier_id` plus exactly one conditional audit entry.
 */
internal object MembershipTierAssignment {
    /**
     * @return `true` iff the tier actually changed (a no-op call -- [newTierId] already equal to
     *   the stored value -- writes nothing and returns `false`, same idempotence discipline
     *   `MemberService.updateMemberStatus`/`updateMemberRole` already establish).
     * @throws ConflictException if the target member has been DSGVO-anonymized.
     * @throws NotFoundException if [targetMemberId] does not resolve to an existing member.
     * @throws BadRequestException if [newTierId] is non-null and does not resolve to an existing
     *   `membership_tier` row.
     */
    fun apply(
        targetMemberId: Uuid,
        newTierId: Uuid?,
        actor: CurrentMember,
        reason: String?,
        familyId: Uuid?,
        now: LocalDateTime,
    ): Boolean {
        val row =
            MemberTable
                .selectAll()
                .where { MemberTable.id eq targetMemberId }
                .forUpdate()
                .singleOrNull() ?: throw NotFoundException("Member $targetMemberId not found")
        if (row[MemberTable.anonymizedAt] != null) {
            throw ConflictException("Member has been anonymized and can no longer be edited")
        }
        val currentTierId = row[MemberTable.membershipTierId]
        if (currentTierId == newTierId) return false

        if (newTierId != null) {
            // Security fix (LOW) -- a membership that has definitively ended (WITHDRAWN/REJECTED/
            // DECEASED, see MemberStatusSets.MEMBERSHIP_ENDED KDoc) must never be handed a REAL tier:
            // the next contribution run would raise a charge against a person who can no longer owe
            // one, with a dunning letter to follow (see DunningService). Same "block the specific
            // terminal statuses, not the general isPrivileged/role gate" posture
            // MemberService.grantMemberAccount already establishes for its own DECEASED check.
            // Deliberately NOT checked when newTierId == null -- REMOVING a tier is always safe
            // regardless of status, and is in fact how a family-dependent assignment (this function's
            // OTHER call site, MemberFamilyService) legitimately nulls a just-ended member's tier.
            if (row[MemberTable.status] in MemberStatusSets.MEMBERSHIP_ENDED) {
                throw ConflictException(
                    "Cannot assign a membership tier to a member whose membership has ended (status=${row[MemberTable.status]})",
                )
            }
            val tierExists = MembershipTierTable.selectAll().where { MembershipTierTable.id eq newTierId }.count() > 0
            if (!tierExists) throw BadRequestException("MembershipTier $newTierId not found")
        }

        MemberTable.update({ MemberTable.id eq targetMemberId }) { it[membershipTierId] = newTierId }

        val beforeSnapshot = MemberMembershipTierSnapshot(membershipTierId = currentTierId?.toString(), familyId = familyId?.toString())
        val afterSnapshot = beforeSnapshot.copy(membershipTierId = newTierId?.toString(), reason = reason)
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.MEMBER,
            entityId = targetMemberId,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(MemberMembershipTierSnapshot.serializer(), beforeSnapshot),
            after = Json.encodeToString(MemberMembershipTierSnapshot.serializer(), afterSnapshot),
            occurredAt = now,
        )
        logger.info {
            "member membership tier changed: actor=${actor.memberId} actorRole=${actor.role} memberId=$targetMemberId " +
                "familyId=$familyId"
        }
        return true
    }
}
