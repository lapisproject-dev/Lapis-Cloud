package network.lapis.cloud.server.dsgvo

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.AttachedImageTable
import network.lapis.cloud.server.gallery.AttachedImageStorage
import network.lapis.cloud.server.gallery.AttachedImageStore
import network.lapis.cloud.shared.domain.ErasureMode
import java.util.Base64
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.222 "Bildergalerie" -- owns [AttachedImageTable] (see `75-image-gallery.kuml.kts` file header "DSGVO"). A picture can show
 * people,
 * and a
 * caption is free text: both are personal data of the UPLOADER.
 *
 * **Export** (Art. 15/20): metadata (`ownerType`, `ownerId`, `position`, `caption`, `altText`, size, `createdAt`) AND the stored
 * picture
 * (base64 +
 * MIME type), oldest first. A missing file reads `"imageMissing": true`. The storage key and any path NEVER appear.
 *
 * **Erasure** is a hard DELETE of the rows AND the files in EVERY [ErasureMode]: a picture has no accountability interest of its own,
 * and
 * the
 * obligation to delete outranks the freezing of an auction that already has bids (the listing then simply shows fewer pictures). The
 * gaps
 * are
 * closed again (the remaining pictures of the same owners are renumbered, two-phase). The hash-chained audit entries stay: they carry
 * no
 * caption,
 * no file name and no key.
 *
 * Files are deleted INSIDE the caller's transaction (this contributor runs in it, like [MemberPhotoPersonalData]): if the transaction
 * rolled back,
 * a row would point to a missing file, which the delivery route answers with 404 and the export reports as `imageMissing` -- the
 * erasure
 * request
 * is repeated then, and the database row stays the authority. The opposite order (files after the commit) is not available to a
 * contributor.
 */
object AttachedImagePersonalData : MemberPersonalDataContributor {
    override val sectionKey = "attachedImages"
    override val displayName = "Hochgeladene Bilder"
    override val coveredTables = setOf(AttachedImageTable)

    override fun exportMember(memberId: Uuid): JsonElement {
        val storage = AttachedImageStorage.fromEnvironment()
        val rows = AttachedImageStore.selectByUploaderInTx(memberId)
        return buildJsonObject {
            put(
                "images",
                JsonArray(
                    rows.map { row ->
                        buildJsonObject {
                            put("ownerType", row.ownerType.name)
                            put("ownerId", row.ownerId.toString())
                            put("position", row.position)
                            row.caption?.let { put("caption", it) }
                            row.altText?.let { put("altText", it) }
                            put("width", row.width)
                            put("height", row.height)
                            put("byteSize", row.byteSize)
                            put("createdAt", row.createdAt.toString())
                            val file = storage.resolve(storageKey = row.storageKey, mimeType = row.mimeType)
                            if (file == null) {
                                put("imageMissing", true)
                            } else {
                                put("mimeType", row.mimeType)
                                put("imageBase64", Base64.getEncoder().encodeToString(file.readBytes()))
                            }
                        }
                    },
                ),
            )
        }
    }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val rows = AttachedImageStore.selectByUploaderInTx(memberId)
        if (rows.isEmpty()) return listOf(TableErasureOutcome(table = "attached_image", rowsDeleted = 0, retentionReason = null))
        val deleted = AttachedImageStore.deleteByUploaderInTx(memberId)
        // Close the gaps of every affected owner (the creator is the only uploader, so these are normally all the owner's pictures).
        rows.map { it.ownerType to it.ownerId }.distinct().forEach { (type, ownerId) ->
            AttachedImageStore.renumberInTx(
                type = type,
                ownerId = ownerId,
                orderedIds = AttachedImageStore.listInTx(type = type, ownerId = ownerId).map { it.id },
            )
        }
        val storage = AttachedImageStorage.fromEnvironment()
        rows.forEach { row ->
            runCatching { storage.delete(row.storageKey) }
                .onFailure { e ->
                    logger.warn(e) { "AttachedImagePersonalData: failed to delete a gallery image file (DB row is authoritative)" }
                }
        }
        return listOf(TableErasureOutcome(table = "attached_image", rowsDeleted = deleted, retentionReason = null))
    }
}
