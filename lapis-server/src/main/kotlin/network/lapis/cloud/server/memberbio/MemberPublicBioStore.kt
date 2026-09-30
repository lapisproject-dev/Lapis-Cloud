package network.lapis.cloud.server.memberbio

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.MemberPublicBioTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.rpc.PublicRankingConsentStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberPublicBioAuditAction
import network.lapis.cloud.shared.domain.MemberPublicBioAuditSnapshot
import network.lapis.cloud.shared.domain.MemberPublicBioRules
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.PublicRankingKind
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- every database operation on `member_public_bio`. All
 * functions must run inside the caller's `transaction {}` (this object never opens one); callers
 * serialize concurrent writers for ONE member with [lockMember] first.
 *
 * Invariant (also enforced by `chk_member_public_bio_consent_state`): the consent columns are BOTH
 * `NULL` (private) or BOTH set (published). A bio is shown publicly only while the consent is
 * EFFECTIVE ([effectivePublicCondition]): set AND recorded under the server's CURRENT consent
 * wording version -- a consent under an older wording is stored but never shown.
 */
internal object MemberPublicBioStore {
    class DeletedRow(
        val wasPublic: Boolean,
        val consentTextVersion: String?,
    )

    fun findByMember(memberId: Uuid): ResultRow? =
        MemberPublicBioTable
            .selectAll()
            .where { MemberPublicBioTable.memberId eq memberId }
            .singleOrNull()

    /** Row lock on the member -- serializes concurrent save/publish/delete (and consent writes) for the same member. */
    fun lockMember(memberId: Uuid): ResultRow? =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .forUpdate()
            .singleOrNull()

    /**
     * The ONE definition of "this bio may be shown publicly right now" (consent set under the CURRENT
     * wording). Used as the JOIN condition of every public reader and by [isEffectivelyPublic] -- a
     * page, an embed feed and the owner's own status line can therefore never disagree.
     */
    fun effectivePublicCondition(): Op<Boolean> =
        MemberPublicBioTable.consentGrantedAt.isNotNull() and
            (MemberPublicBioTable.consentTextVersion eq MemberPublicBioRules.CONSENT_TEXT_VERSION)

    fun isEffectivelyPublic(row: ResultRow?): Boolean =
        row != null &&
            row[MemberPublicBioTable.consentGrantedAt] != null &&
            row[MemberPublicBioTable.consentTextVersion] == MemberPublicBioRules.CONSENT_TEXT_VERSION

    /** Inserts the text, or replaces it on an existing row WITHOUT touching the consent (see service KDoc, Q3). */
    fun upsertText(
        memberId: Uuid,
        text: String,
        now: LocalDateTime,
    ) {
        if (findByMember(memberId) == null) {
            MemberPublicBioTable.insert {
                it[id] = Uuid.random()
                it[MemberPublicBioTable.memberId] = memberId
                it[bioText] = text
                it[updatedAt] = now
                it[consentGrantedAt] = null
                it[consentTextVersion] = null
            }
        } else {
            MemberPublicBioTable.update({ MemberPublicBioTable.memberId eq memberId }) {
                it[bioText] = text
                it[updatedAt] = now
            }
        }
    }

    /** Sets the consent under [version]. `false` if there is no row. Re-publishing refreshes timestamp and version. */
    fun publish(
        memberId: Uuid,
        version: String,
        now: LocalDateTime,
    ): Boolean =
        MemberPublicBioTable.update({ MemberPublicBioTable.memberId eq memberId }) {
            it[consentGrantedAt] = now
            it[consentTextVersion] = version
        } > 0

    /** Clears the consent. `false` if there was no consent (or no row). */
    fun unpublish(memberId: Uuid): Boolean =
        MemberPublicBioTable.update(
            { (MemberPublicBioTable.memberId eq memberId) and MemberPublicBioTable.consentGrantedAt.isNotNull() },
        ) {
            it[consentGrantedAt] = null
            it[consentTextVersion] = null
        } > 0

    /** Deletes the row; `null` if none existed. */
    fun deleteRow(memberId: Uuid): DeletedRow? {
        val row = findByMember(memberId) ?: return null
        MemberPublicBioTable.deleteWhere { MemberPublicBioTable.memberId eq memberId }
        return DeletedRow(
            wasPublic = row[MemberPublicBioTable.consentGrantedAt] != null,
            consentTextVersion = row[MemberPublicBioTable.consentTextVersion],
        )
    }

    /**
     * Second defense layer behind the live status filter of every public reader: when a member's
     * status moves OUT of [MemberStatusSets.ORGANIZATION_MEMBER], a PUBLISHED bio goes back to
     * private AND a granted politician-listing consent is revoked -- so neither silently comes back
     * after a later reactivation. No-op when the new status is still an organization-member status.
     * Call in the SAME transaction as the status write, while holding the member row lock.
     */
    fun revokePublicationOnStatusLoss(
        memberId: Uuid,
        newStatus: MemberStatus,
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
        now: LocalDateTime,
    ) {
        if (newStatus in MemberStatusSets.ORGANIZATION_MEMBER) return
        val row = findByMember(memberId)
        if (row != null && row[MemberPublicBioTable.consentGrantedAt] != null) {
            val version = row[MemberPublicBioTable.consentTextVersion]
            if (unpublish(memberId)) {
                recordAudit(
                    actorMemberId = actorMemberId,
                    actorRole = actorRole,
                    targetMemberId = memberId,
                    targetStatus = newStatus,
                    action = MemberPublicBioAuditAction.REVOKED_ON_STATUS_LOSS,
                    beforePublic = true,
                    beforeVersion = version,
                    afterPublic = false,
                    afterVersion = null,
                    now = now,
                )
                logger.info { "Member public bio publication revoked on status loss: memberId=$memberId" }
            }
        }
        if (PublicRankingConsentStore.revoke(memberId = memberId, kind = PublicRankingKind.POLITICIAN_LISTING, now = now)) {
            logger.info { "Politician listing consent revoked on status loss: memberId=$memberId" }
        }
    }

    /**
     * One hash-chained audit entry under [AuditEntityType.MEMBER]. The snapshot carries the action,
     * the publication state and the consent version -- NEVER the text.
     */
    fun recordAudit(
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
        targetMemberId: Uuid,
        targetStatus: MemberStatus,
        action: MemberPublicBioAuditAction,
        beforePublic: Boolean,
        beforeVersion: String?,
        afterPublic: Boolean,
        afterVersion: String?,
        now: LocalDateTime,
    ) {
        fun snapshot(
            isPublic: Boolean,
            version: String?,
        ) = Json.encodeToString(
            MemberChangeSnapshot.serializer(),
            MemberChangeSnapshot(
                displayNameChanged = false,
                emailChanged = false,
                status = targetStatus,
                role = null,
                memberPublicBio = MemberPublicBioAuditSnapshot(action = action, bioPublic = isPublic, consentTextVersion = version),
            ),
        )
        AuditLogRecorder.record(
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            entityType = AuditEntityType.MEMBER,
            entityId = targetMemberId,
            action = AuditAction.UPDATE,
            before = snapshot(beforePublic, beforeVersion),
            after = snapshot(afterPublic, afterVersion),
            occurredAt = now,
        )
    }
}
