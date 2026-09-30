package network.lapis.cloud.server.dsgvo

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.memberphoto.MemberPhotoStorage
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.Base64
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- owns [MemberPhotoTable]. See `59-member-photo.kuml.kts` file
 * header "DSGVO".
 *
 * **Export** (Art. 15/20) carries the metadata AND the stored JPEG (base64, at most a few hundred KB
 * for an 800x800 JPEG) -- the subject is entitled to get their own picture back. It NEVER carries
 * the public token or the storage key (internal details, and the token is a bearer secret). If the
 * file is gone, `"imageMissing": true` replaces the image data.
 *
 * **Erasure** is a hard DELETE of the row AND the file in EVERY [ErasureMode] -- a profile photo has
 * no accountability/retention interest of its own. The storage root comes from
 * [MemberPhotoStorage.fromEnvironment], the single source of truth shared with `Application`.
 */
object MemberPhotoPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "memberPhoto"
    override val displayName = "Mitgliedsfoto"
    override val coveredTables = setOf(MemberPhotoTable)

    override fun exportMember(memberId: Uuid): JsonElement {
        val row =
            MemberPhotoTable
                .selectAll()
                .where { MemberPhotoTable.memberId eq memberId }
                .singleOrNull()
        return buildJsonObject {
            if (row == null) {
                put("hasPhoto", false)
                return@buildJsonObject
            }
            put("hasPhoto", true)
            put("visibility", row[MemberPhotoTable.visibility].name)
            put("widthPx", row[MemberPhotoTable.widthPx])
            put("heightPx", row[MemberPhotoTable.heightPx])
            put("sizeBytes", row[MemberPhotoTable.sizeBytes])
            put("uploadedAt", row[MemberPhotoTable.uploadedAt].toString())
            row[MemberPhotoTable.consentGrantedAt]?.let { put("consentGrantedAt", it.toString()) }
            row[MemberPhotoTable.consentTextVersion]?.let { put("consentTextVersion", it) }
            val file = MemberPhotoStorage.fromEnvironment().resolve(row[MemberPhotoTable.storageKey])
            if (file == null) {
                put("imageMissing", true)
            } else {
                put("imageJpegBase64", Base64.getEncoder().encodeToString(file.readBytes()))
            }
        }
    }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val keys =
            MemberPhotoTable
                .selectAll()
                .where { MemberPhotoTable.memberId eq memberId }
                .map { it[MemberPhotoTable.storageKey] }
        val storage = MemberPhotoStorage.fromEnvironment()
        keys.forEach { key ->
            runCatching { storage.delete(key) }
                .onFailure { e -> logger.warn(e) { "MemberPhotoPersonalData: failed to delete photo file (DB row is authoritative)" } }
        }
        val deleted = MemberPhotoTable.deleteWhere { MemberPhotoTable.memberId eq memberId }
        return listOf(TableErasureOutcome(table = "member_photo", rowsDeleted = deleted, retentionReason = null))
    }
}
