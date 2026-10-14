package network.lapis.cloud.server.videooverlays

import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.VideoOverlayAssetTable
import network.lapis.cloud.server.memberphoto.MemberPhotoTestImages
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.VideoOverlayKind
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import kotlin.uuid.Uuid

/** Synthetic overlay images -- no binary fixtures, every byte is built here. */
internal object VideoOverlayTestImages {
    /** A PNG with an alpha channel whose pixels are all fully transparent except a red 40 px bar at the top. */
    fun framePng(
        width: Int = 1920,
        height: Int = 1080,
    ): ByteArray =
        MemberPhotoTestImages.png(
            MemberPhotoTestImages.image(width = width, height = height, type = BufferedImage.TYPE_INT_ARGB) { g ->
                g.color = Color(255, 0, 0, 255)
                g.fillRect(0, 0, width, 40)
            },
        )

    /** An ARGB PNG (so the container HAS an alpha channel) in which EVERY pixel is fully opaque. */
    fun opaqueArgbPng(
        width: Int = 1920,
        height: Int = 1080,
    ): ByteArray =
        MemberPhotoTestImages.png(
            MemberPhotoTestImages.image(width = width, height = height, type = BufferedImage.TYPE_INT_ARGB) { g ->
                g.color = Color(0, 0, 255, 255)
                g.fillRect(0, 0, width, height)
            },
        )

    /** A plain RGB PNG (colour type 2, no alpha). */
    fun rgbPng(
        width: Int = 1920,
        height: Int = 1080,
    ): ByteArray = MemberPhotoTestImages.png(MemberPhotoTestImages.solid(width = width, height = height))

    /** A logo PNG with a half-transparent right half. */
    fun logoPng(
        width: Int = 600,
        height: Int = 300,
    ): ByteArray =
        MemberPhotoTestImages.png(
            MemberPhotoTestImages.image(width = width, height = height, type = BufferedImage.TYPE_INT_ARGB) { g ->
                g.color = Color(255, 0, 0, 128)
                g.fillRect(width / 2, 0, width / 2, height)
            },
        )

    fun logoJpeg(
        width: Int = 600,
        height: Int = 300,
    ): ByteArray = MemberPhotoTestImages.jpeg(MemberPhotoTestImages.solid(width = width, height = height))
}

/** Library state of the specs: wipes the table, the files and the audit entries, switches the option. */
internal object VideoOverlayTestState {
    fun setOffered(offered: Boolean) {
        transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[videoOverlaysOffered] = offered
            }
        }
    }

    fun reset(root: File? = null) {
        transaction {
            VideoOverlayAssetTable.deleteAll()
            AuditLogEntryTable.deleteWhere { AuditLogEntryTable.entityType eq AuditEntityType.VIDEO_OVERLAY }
        }
        root?.listFiles()?.forEach { it.delete() }
    }

    fun rows() = transaction { VideoOverlayAssetTable.selectAll().toList() }

    /** Inserts a library row and writes its file; returns (id, token). */
    fun seed(
        storage: VideoOverlayStorage,
        kind: VideoOverlayKind = VideoOverlayKind.LOGO,
        name: String = "Logo",
        bytes: ByteArray = VideoOverlayTestImages.logoPng(),
    ): Pair<Uuid, String> {
        val id = Uuid.random()
        val token = VideoOverlayStore.newAccessToken()
        storage.write(id = id, format = VideoOverlayFormat.PNG, bytes = bytes)
        transaction {
            VideoOverlayStore.insert(
                id = id,
                kind = kind,
                name = name,
                format = VideoOverlayFormat.PNG,
                width = 600,
                height = 300,
                sizeBytes = bytes.size.toLong(),
                sha256 = "0".repeat(64),
                token = token,
                now = DbClock.nowLocalDateTime(),
            )
        }
        return id to token
    }

    fun auditSnapshots(): List<String> =
        transaction {
            AuditLogEntryTable
                .selectAll()
                .where { AuditLogEntryTable.entityType eq AuditEntityType.VIDEO_OVERLAY }
                .orderBy(AuditLogEntryTable.sequenceNumber)
                .flatMap { listOfNotNull(it[AuditLogEntryTable.beforeSnapshot], it[AuditLogEntryTable.afterSnapshot]) }
        }

    fun auditActions(): List<network.lapis.cloud.shared.domain.AuditAction> =
        transaction {
            AuditLogEntryTable
                .selectAll()
                .where { AuditLogEntryTable.entityType eq AuditEntityType.VIDEO_OVERLAY }
                .orderBy(AuditLogEntryTable.sequenceNumber)
                .map { it[AuditLogEntryTable.action] }
        }
}
