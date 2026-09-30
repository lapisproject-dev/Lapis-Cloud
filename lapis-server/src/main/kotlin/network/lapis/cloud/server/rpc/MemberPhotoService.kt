package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.memberphoto.MemberPhotoStorage
import network.lapis.cloud.server.memberphoto.MemberPhotoStore
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberPhotoAuditAction
import network.lapis.cloud.shared.domain.MemberPhotoRules
import network.lapis.cloud.shared.domain.MemberPhotoVisibility
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.OwnMemberPhotoDto
import network.lapis.cloud.shared.rpc.IMemberPhotoService
import network.lapis.cloud.shared.rpc.MemberPhotoConsentOutdatedException
import network.lapis.cloud.shared.rpc.MemberPhotoMissingException
import network.lapis.cloud.shared.rpc.MemberPhotoNotEligibleException
import network.lapis.cloud.shared.rpc.MemberPhotoRateLimitedException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- RPC side of the member photo (bytes travel over
 * `MemberPhotoRoutes`). Every method except [moderationRemovePhoto] acts EXCLUSIVELY on the
 * caller's own photo; none takes a member id.
 *
 * - **Publication** ([setOwnPhotoVisibility] PUBLIC) needs a photo, a consent version equal to
 *   [MemberPhotoRules.CONSENT_TEXT_VERSION] and an eligible status; every publication mints a NEW
 *   token, publishing an already-PUBLIC photo is a no-op. Withdrawal (PRIVATE) and deletion stay
 *   available regardless of the caller's current status.
 * - **State comes from the database only** -- the returned [OwnMemberPhotoDto] is what the client
 *   renders; it never carries the token itself, only the derived public URL.
 * - **Audit** entries (`MEMBER`, [network.lapis.cloud.shared.domain.MemberPhotoAuditSnapshot]) are
 *   written in the same transaction as the state change and never contain token, key or image data.
 * - **Self-healing**: a row whose file is gone is deleted on [getOwnPhoto] (a row without a file
 *   would otherwise show a broken preview forever).
 */
class MemberPhotoService internal constructor(
    private val call: ApplicationCall,
    private val storage: MemberPhotoStorage,
    private val visibilityRateLimiter: FederationInboxRateLimiter,
    private val moderationRateLimiter: FederationInboxRateLimiter,
    private val baseUrl: String,
) : IMemberPhotoService {
    override suspend fun getOwnPhoto(): OwnMemberPhotoDto {
        val current = resolveCurrentMember(call)
        val healedKey: String? =
            transaction {
                val row = MemberPhotoStore.findByMember(current.memberId) ?: return@transaction null
                if (storage.resolve(row[MemberPhotoTable.storageKey]) != null) return@transaction null
                logger.warn { "MemberPhotoService.getOwnPhoto: self-healing a row with a missing file (memberId=${current.memberId})" }
                MemberPhotoStore.deleteRow(current.memberId)?.storageKey
            }
        healedKey?.let { storage.delete(it) }
        return transaction { toDto(MemberPhotoStore.findByMember(current.memberId)) }
    }

    override suspend fun setOwnPhotoVisibility(
        visibility: MemberPhotoVisibility,
        consentTextVersion: String?,
    ): OwnMemberPhotoDto {
        val current = resolveCurrentMember(call)
        checkVisibilityRate(current)
        if (visibility == MemberPhotoVisibility.PUBLIC) {
            if (current.status !in MemberStatusSets.MEMBER_PHOTO_ELIGIBLE) throw MemberPhotoNotEligibleException()
            if (consentTextVersion != MemberPhotoRules.CONSENT_TEXT_VERSION) throw MemberPhotoConsentOutdatedException()
        }
        val now = DbClock.nowLocalDateTime()
        return transaction {
            val member = MemberPhotoStore.lockMember(current.memberId) ?: throw NotFoundException("Member not found")
            val row = MemberPhotoStore.findByMember(current.memberId)
            when (visibility) {
                MemberPhotoVisibility.PUBLIC -> {
                    if (member[MemberTable.status] !in MemberStatusSets.MEMBER_PHOTO_ELIGIBLE) throw MemberPhotoNotEligibleException()
                    if (row == null || storage.resolve(row[MemberPhotoTable.storageKey]) == null) throw MemberPhotoMissingException()
                    if (row[MemberPhotoTable.visibility] != MemberPhotoVisibility.PUBLIC) {
                        MemberPhotoStore.publish(
                            memberId = current.memberId,
                            consentTextVersion = MemberPhotoRules.CONSENT_TEXT_VERSION,
                            now = now,
                        )
                        audit(
                            current = current,
                            member = member,
                            action = MemberPhotoAuditAction.PUBLISHED,
                            beforeVisibility = MemberPhotoVisibility.PRIVATE,
                            beforeVersion = null,
                            now = now,
                        )
                    }
                }
                MemberPhotoVisibility.PRIVATE -> {
                    if (row != null && row[MemberPhotoTable.visibility] == MemberPhotoVisibility.PUBLIC) {
                        val version = row[MemberPhotoTable.consentTextVersion]
                        MemberPhotoStore.unpublish(current.memberId)
                        audit(
                            current = current,
                            member = member,
                            action = MemberPhotoAuditAction.UNPUBLISHED,
                            beforeVisibility = MemberPhotoVisibility.PUBLIC,
                            beforeVersion = version,
                            now = now,
                        )
                    }
                }
            }
            toDto(MemberPhotoStore.findByMember(current.memberId))
        }
    }

    override suspend fun deleteOwnPhoto(): OwnMemberPhotoDto {
        val current = resolveCurrentMember(call)
        checkVisibilityRate(current)
        val now = DbClock.nowLocalDateTime()
        val deleted =
            transaction {
                val member = MemberPhotoStore.lockMember(current.memberId) ?: throw NotFoundException("Member not found")
                val deletedRow = MemberPhotoStore.deleteRow(current.memberId)
                if (deletedRow != null && deletedRow.wasPublic) {
                    audit(
                        current = current,
                        member = member,
                        action = MemberPhotoAuditAction.DELETED_BY_OWNER,
                        beforeVisibility = MemberPhotoVisibility.PUBLIC,
                        beforeVersion = deletedRow.consentTextVersion,
                        now = now,
                    )
                }
                deletedRow
            }
        deleted?.let { storage.delete(it.storageKey) }
        return toDto(null)
    }

    override suspend fun moderationRemovePhoto(memberId: String) {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.BOARD, AccountRole.ADMIN)
        if (!moderationRateLimiter.checkAndRecord("actor:${current.memberId}")) throw MemberPhotoRateLimitedException()
        val targetId = runCatching { Uuid.parse(memberId) }.getOrElse { throw NotFoundException("Member $memberId not found") }
        val now = DbClock.nowLocalDateTime()
        val deleted =
            transaction {
                val member = MemberPhotoStore.lockMember(targetId) ?: return@transaction null
                val deletedRow = MemberPhotoStore.deleteRow(targetId)
                if (deletedRow != null) {
                    MemberPhotoStore.recordAudit(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        targetMemberId = targetId,
                        targetStatus = member[MemberTable.status],
                        action = MemberPhotoAuditAction.REMOVED_BY_MODERATION,
                        beforeVisibility = deletedRow.visibility,
                        beforeVersion = deletedRow.consentTextVersion,
                        now = now,
                    )
                }
                deletedRow
            }
        deleted?.let { storage.delete(it.storageKey) }
        logger.info { "Member photo moderation removal: actor=${current.memberId} removed=${deleted != null}" }
        // Always Unit -- also when nothing existed: no existence oracle, no picture, no metadata.
    }

    private fun checkVisibilityRate(current: CurrentMember) {
        if (!visibilityRateLimiter.checkAndRecord("member:${current.memberId}")) throw MemberPhotoRateLimitedException()
    }

    private fun audit(
        current: CurrentMember,
        member: ResultRow,
        action: MemberPhotoAuditAction,
        beforeVisibility: MemberPhotoVisibility,
        beforeVersion: String?,
        now: LocalDateTime,
    ) = MemberPhotoStore.recordAudit(
        actorMemberId = current.memberId,
        actorRole = current.role,
        targetMemberId = current.memberId,
        targetStatus = member[MemberTable.status],
        action = action,
        beforeVisibility = beforeVisibility,
        beforeVersion = beforeVersion,
        now = now,
    )

    private fun toDto(row: ResultRow?): OwnMemberPhotoDto {
        if (row == null) {
            return OwnMemberPhotoDto(
                hasPhoto = false,
                visibility = MemberPhotoVisibility.PRIVATE,
                widthPx = null,
                heightPx = null,
                uploadedAt = null,
                previewVersion = null,
                publicUrl = null,
                requiredConsentTextVersion = MemberPhotoRules.CONSENT_TEXT_VERSION,
            )
        }
        val visibility = row[MemberPhotoTable.visibility]
        val token = row[MemberPhotoTable.publicToken]
        return OwnMemberPhotoDto(
            hasPhoto = true,
            visibility = visibility,
            widthPx = row[MemberPhotoTable.widthPx],
            heightPx = row[MemberPhotoTable.heightPx],
            uploadedAt = row[MemberPhotoTable.uploadedAt],
            previewVersion = row[MemberPhotoTable.storageKey].take(PREVIEW_VERSION_LENGTH),
            publicUrl = if (visibility == MemberPhotoVisibility.PUBLIC && token != null) "$baseUrl/public/member-photos/$token" else null,
            requiredConsentTextVersion = MemberPhotoRules.CONSENT_TEXT_VERSION,
        )
    }

    private companion object {
        const val PREVIEW_VERSION_LENGTH = 8
    }
}
