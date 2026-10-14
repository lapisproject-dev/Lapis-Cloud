package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.videooverlays.VideoOverlayInstanceLogo
import network.lapis.cloud.server.videooverlays.VideoOverlayStorage
import network.lapis.cloud.server.videooverlays.VideoOverlayStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.VideoOverlayAdminLibraryDto
import network.lapis.cloud.shared.domain.VideoOverlayLibraryDto
import network.lapis.cloud.shared.domain.VideoOverlayRules
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IVideoOverlayService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

// Privacy rule of this file: the logs name counts and exception classes only -- never an overlay name, a token or a member id.
private val logger = KotlinLogging.logger {}

/**
 * V1.9.129 -- see [IVideoOverlayService]. The library is organization-internal: [getLibrary] answers an EMPTY, not-offered library unless
 * the ADMIN switched the option on AND the caller may enter conferences (it never reveals names or paths otherwise). The administration
 * methods are ADMIN-only; each change is audited ([network.lapis.cloud.shared.domain.AuditEntityType.VIDEO_OVERLAY], snapshot = kind, name,
 * sizes only). The instance logo is a synthetic first element that is neither editable nor deletable.
 */
class VideoOverlayService internal constructor(
    private val call: ApplicationCall,
    private val storage: VideoOverlayStorage,
    private val instanceLogo: VideoOverlayInstanceLogo,
) : IVideoOverlayService {
    override suspend fun getLibrary(): VideoOverlayLibraryDto {
        val current = resolveCurrentMember(call)
        return transaction {
            val offered = VideoOverlaysOfferedStore.load()
            val allowed = offered && (current.role == AccountRole.ADMIN || isConferenceEligible(current))
            if (!allowed) return@transaction VideoOverlayLibraryDto(offered = false, items = emptyList())
            val stored = VideoOverlayStore.listOrdered().map { VideoOverlayStore.toItem(it) }
            VideoOverlayLibraryDto(offered = true, items = listOfNotNull(instanceLogo.item) + stored)
        }
    }

    override suspend fun getAdminLibrary(): VideoOverlayAdminLibraryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction { adminLibrary() }
    }

    override suspend fun renameItem(
        id: String,
        name: String,
    ): VideoOverlayAdminLibraryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val uuid = parseId(id)
        val normalized = VideoOverlayRules.normalizeName(name) ?: throw BadRequestException("Invalid name")
        return transaction {
            VideoOverlayStore.lockLibrary()
            val before = VideoOverlayStore.findById(uuid) ?: throw NotFoundException("Not found")
            val beforeJson = VideoOverlayStore.snapshotJson(before)
            VideoOverlayStore.rename(id = uuid, name = normalized)
            val after = VideoOverlayStore.findById(uuid)!!
            audit(current = current, id = uuid, before = beforeJson, after = VideoOverlayStore.snapshotJson(after))
            adminLibrary()
        }
    }

    override suspend fun deleteItem(id: String): VideoOverlayAdminLibraryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val uuid = parseId(id)
        var removedFile: Uuid? = null
        val result =
            transaction {
                VideoOverlayStore.lockLibrary()
                val before = VideoOverlayStore.findById(uuid) ?: throw NotFoundException("Not found")
                val beforeJson = VideoOverlayStore.snapshotJson(before)
                VideoOverlayStore.delete(uuid)
                audit(current = current, id = uuid, before = beforeJson, after = null, action = AuditAction.VOID)
                removedFile = uuid
                adminLibrary()
            }
        // After the commit: the database is authoritative, a leftover file is only logged.
        removedFile?.let { fileId ->
            runCatching { storage.delete(fileId) }.onFailure { e ->
                logger.warn { "Video overlay file not removed: ${e::class.simpleName}" }
            }
        }
        return result
    }

    override suspend fun moveItem(
        id: String,
        up: Boolean,
    ): VideoOverlayAdminLibraryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val uuid = parseId(id)
        return transaction {
            VideoOverlayStore.lockLibrary()
            val row = VideoOverlayStore.findById(uuid) ?: throw NotFoundException("Not found")
            val oldIndex = VideoOverlayStore.positionOf(uuid)
            if (VideoOverlayStore.move(id = uuid, up = up)) {
                // The audit entry records WHAT moved and WHERE TO (old and new index), not an identical before/after.
                val newIndex = if (up) oldIndex - 1 else oldIndex + 1
                audit(
                    current = current,
                    id = uuid,
                    before = VideoOverlayStore.snapshotJsonAt(row = row, position = oldIndex),
                    after = VideoOverlayStore.snapshotJsonAt(row = row, position = newIndex),
                )
            }
            adminLibrary()
        }
    }

    private fun adminLibrary(): VideoOverlayAdminLibraryDto =
        VideoOverlayAdminLibraryDto(
            offered = VideoOverlaysOfferedStore.load(),
            items = VideoOverlayStore.listOrdered().map { VideoOverlayStore.toAdminItem(it) },
            instanceLogoOffered = instanceLogo.item != null,
            instanceLogoReason = instanceLogo.reason,
        )

    private fun audit(
        current: CurrentMember,
        id: Uuid,
        before: String?,
        after: String?,
        action: AuditAction = AuditAction.UPDATE,
    ) {
        VideoOverlayStore.recordAudit(
            actorMemberId = current.memberId,
            actorRole = current.role,
            entityId = id,
            action = action,
            before = before,
            after = after,
            now = DbClock.nowLocalDateTime(),
        )
    }

    /** A UUID or a refusal -- the synthetic instance logo id is not a UUID and therefore never editable. */
    private fun parseId(id: String): Uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: throw BadRequestException("Invalid id")

    private fun isConferenceEligible(current: CurrentMember): Boolean =
        try {
            requireConferenceEligibleMembership(memberId = current.memberId)
            true
        } catch (_: ForbiddenException) {
            false
        }
}
