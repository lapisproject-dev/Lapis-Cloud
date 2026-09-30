package network.lapis.cloud.server.memberphoto

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberPhotoAuditAction
import network.lapis.cloud.shared.domain.MemberPhotoAuditSnapshot
import network.lapis.cloud.shared.domain.MemberPhotoRules
import network.lapis.cloud.shared.domain.MemberPhotoVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.util.Base64
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- every database operation on `member_photo`. All functions
 * must run inside the caller's `transaction {}`; callers serialize concurrent writers for ONE
 * member with [lockMember] first.
 *
 * Invariant (also enforced by `chk_member_photo_public_state`): a row is PUBLIC iff it has a
 * public token, a consent timestamp and a consent text version -- [publish] sets all three,
 * [unpublish] clears all three.
 */
internal object MemberPhotoStore {
    /** One process-wide, thread-safe CSPRNG -- `SecureRandom` is documented thread-safe. */
    private val secureRandom = SecureRandom()

    /** What [upsertAfterUpload] replaced, so the caller can delete the old file AFTER commit and audit a reset publication. */
    class UpsertOutcome(
        val previousStorageKey: String?,
        val publicationReset: Boolean,
        val previousConsentTextVersion: String?,
    )

    class DeletedRow(
        val storageKey: String,
        val wasPublic: Boolean,
        val visibility: MemberPhotoVisibility,
        val consentTextVersion: String?,
    )

    fun findByMember(memberId: Uuid): ResultRow? =
        MemberPhotoTable
            .selectAll()
            .where { MemberPhotoTable.memberId eq memberId }
            .singleOrNull()

    /** Row lock on the member -- serializes concurrent upload/publish/delete for the same member. */
    fun lockMember(memberId: Uuid): ResultRow? =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .forUpdate()
            .singleOrNull()

    /** 32 random bytes, Base64url without padding (43 characters). A NEW token is minted on EVERY publication. */
    fun newPublicToken(): String {
        val bytes = ByteArray(MemberPhotoPolicy.PUBLIC_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Inserts or replaces the member's photo row with the freshly stored file. A replacement ALWAYS
     * resets to PRIVATE (token and consent cleared) -- consent was given for the OLD picture.
     */
    fun upsertAfterUpload(
        memberId: Uuid,
        storageKey: String,
        widthPx: Int,
        heightPx: Int,
        sizeBytes: Long,
        now: LocalDateTime,
    ): UpsertOutcome {
        val existing = findByMember(memberId)
        if (existing == null) {
            MemberPhotoTable.insert {
                it[id] = Uuid.random()
                it[MemberPhotoTable.memberId] = memberId
                it[MemberPhotoTable.storageKey] = storageKey
                it[contentType] = MemberPhotoPolicy.CONTENT_TYPE_JPEG
                it[MemberPhotoTable.widthPx] = widthPx
                it[MemberPhotoTable.heightPx] = heightPx
                it[MemberPhotoTable.sizeBytes] = sizeBytes
                it[uploadedAt] = now
                it[visibility] = MemberPhotoVisibility.PRIVATE
                it[publicToken] = null
                it[consentGrantedAt] = null
                it[consentTextVersion] = null
            }
            return UpsertOutcome(previousStorageKey = null, publicationReset = false, previousConsentTextVersion = null)
        }
        val wasPublic = existing[MemberPhotoTable.visibility] == MemberPhotoVisibility.PUBLIC
        val previousVersion = existing[MemberPhotoTable.consentTextVersion]
        MemberPhotoTable.update({ MemberPhotoTable.memberId eq memberId }) {
            it[MemberPhotoTable.storageKey] = storageKey
            it[MemberPhotoTable.widthPx] = widthPx
            it[MemberPhotoTable.heightPx] = heightPx
            it[MemberPhotoTable.sizeBytes] = sizeBytes
            it[uploadedAt] = now
            it[visibility] = MemberPhotoVisibility.PRIVATE
            it[publicToken] = null
            it[consentGrantedAt] = null
            it[consentTextVersion] = null
        }
        return UpsertOutcome(
            previousStorageKey = existing[MemberPhotoTable.storageKey],
            publicationReset = wasPublic,
            previousConsentTextVersion = previousVersion,
        )
    }

    /** PRIVATE -> PUBLIC with a fresh token. `false` if there was no PRIVATE row to publish (missing or already PUBLIC). */
    fun publish(
        memberId: Uuid,
        consentTextVersion: String,
        now: LocalDateTime,
    ): Boolean {
        val token = newPublicToken()
        return MemberPhotoTable.update(
            {
                (MemberPhotoTable.memberId eq memberId) and (MemberPhotoTable.visibility eq MemberPhotoVisibility.PRIVATE)
            },
        ) {
            it[visibility] = MemberPhotoVisibility.PUBLIC
            it[publicToken] = token
            it[consentGrantedAt] = now
            it[MemberPhotoTable.consentTextVersion] = consentTextVersion
        } > 0
    }

    /** PUBLIC -> PRIVATE, token and consent cleared. `false` if the row was not PUBLIC. */
    fun unpublish(memberId: Uuid): Boolean =
        MemberPhotoTable.update(
            {
                (MemberPhotoTable.memberId eq memberId) and (MemberPhotoTable.visibility eq MemberPhotoVisibility.PUBLIC)
            },
        ) {
            it[visibility] = MemberPhotoVisibility.PRIVATE
            it[publicToken] = null
            it[consentGrantedAt] = null
            it[consentTextVersion] = null
        } > 0

    /** Deletes the row and returns what the caller needs to delete the file after commit and to audit; `null` if none existed. */
    fun deleteRow(memberId: Uuid): DeletedRow? {
        val row = findByMember(memberId) ?: return null
        MemberPhotoTable.deleteWhere { MemberPhotoTable.memberId eq memberId }
        return DeletedRow(
            storageKey = row[MemberPhotoTable.storageKey],
            wasPublic = row[MemberPhotoTable.visibility] == MemberPhotoVisibility.PUBLIC,
            visibility = row[MemberPhotoTable.visibility],
            consentTextVersion = row[MemberPhotoTable.consentTextVersion],
        )
    }

    /**
     * The storage key to serve for [token] -- but ONLY while the photo is PUBLIC and the owning
     * member CURRENTLY has an eligible status. Evaluated live on every request, so a withdrawal or a
     * status loss takes effect immediately; every "no" answer is the same `null`.
     */
    fun findPublicServable(token: String): String? =
        (MemberPhotoTable innerJoin MemberTable)
            .selectAll()
            .where {
                (MemberPhotoTable.publicToken eq token) and
                    (MemberPhotoTable.visibility eq MemberPhotoVisibility.PUBLIC) and
                    (MemberPhotoTable.memberId eq MemberTable.id)
            }.singleOrNull()
            ?.takeIf { it[MemberTable.status] in MemberStatusSets.MEMBER_PHOTO_ELIGIBLE }
            ?.get(MemberPhotoTable.storageKey)

    /**
     * Second defense layer behind the live join in [findPublicServable]: when a member's status moves
     * OUT of [MemberStatusSets.MEMBER_PHOTO_ELIGIBLE], a PUBLIC photo is reset to PRIVATE so that
     * consent and token do not silently come back after a later reactivation. No-op when the new
     * status is eligible or the photo is not PUBLIC. Call in the SAME transaction as the status write.
     */
    fun revokePublicationOnStatusLoss(
        memberId: Uuid,
        newStatus: MemberStatus,
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
        now: LocalDateTime,
    ) {
        if (newStatus in MemberStatusSets.MEMBER_PHOTO_ELIGIBLE) return
        val row = findByMember(memberId) ?: return
        if (row[MemberPhotoTable.visibility] != MemberPhotoVisibility.PUBLIC) return
        val version = row[MemberPhotoTable.consentTextVersion]
        if (unpublish(memberId)) {
            recordAudit(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                targetMemberId = memberId,
                targetStatus = newStatus,
                action = MemberPhotoAuditAction.UNPUBLISHED_BY_STATUS_CHANGE,
                beforeVisibility = MemberPhotoVisibility.PUBLIC,
                beforeVersion = version,
                now = now,
            )
            logger.info { "Member photo publication revoked on status loss: memberId=$memberId" }
        }
    }

    /**
     * One hash-chained audit entry under [AuditEntityType.MEMBER]. The snapshot carries the action,
     * the visibility before/after and the consent text version -- NEVER the token, the storage key
     * or image data.
     */
    fun recordAudit(
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
        targetMemberId: Uuid,
        targetStatus: MemberStatus,
        action: MemberPhotoAuditAction,
        beforeVisibility: MemberPhotoVisibility,
        beforeVersion: String?,
        now: LocalDateTime,
    ) {
        val afterVisibility =
            if (action == MemberPhotoAuditAction.PUBLISHED) MemberPhotoVisibility.PUBLIC else MemberPhotoVisibility.PRIVATE
        val afterVersion = if (action == MemberPhotoAuditAction.PUBLISHED) MemberPhotoRules.CONSENT_TEXT_VERSION else null

        fun snapshot(
            visibility: MemberPhotoVisibility,
            version: String?,
        ) = Json.encodeToString(
            MemberChangeSnapshot.serializer(),
            MemberChangeSnapshot(
                displayNameChanged = false,
                emailChanged = false,
                status = targetStatus,
                role = null,
                memberPhoto = MemberPhotoAuditSnapshot(action = action, visibility = visibility, consentTextVersion = version),
            ),
        )
        AuditLogRecorder.record(
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            entityType = AuditEntityType.MEMBER,
            entityId = targetMemberId,
            action = AuditAction.UPDATE,
            before = snapshot(beforeVisibility, beforeVersion),
            after = snapshot(afterVisibility, afterVersion),
            occurredAt = now,
        )
    }
}
