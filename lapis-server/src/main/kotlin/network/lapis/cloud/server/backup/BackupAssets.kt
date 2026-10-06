package network.lapis.cloud.server.backup

import network.lapis.cloud.server.images.SvgCrestSanitizer
import network.lapis.cloud.server.images.SvgSanitizeResult
import java.io.File

/** ZIP entry name prefix under which public-asset files (chapter crests, event and article covers) are stored (bundle format 2). */
internal const val ASSET_ENTRY_PREFIX = "assets/"

/** Upper bound of one restored asset file; a stored raster is already re-encoded to at most 1600 px (5 MiB is the upload cap of every cover pipeline). */
internal const val MAX_RESTORED_ASSET_BYTES = 5L * 1024 * 1024

/**
 * A file family that lives OUTSIDE the document blobs but is referenced by an id column of an exported table, and belongs to
 * the public face of the organization (V1.9.65): crests, event covers and article covers. Member photos and conference
 * backgrounds are deliberately NOT here -- they are private (see [OrganizationSchemaCatalog.EXCLUDED_TABLES]).
 */
internal data class BackupAssetKind(
    val folder: String,
    val tableName: String,
    val idColumn: String,
    val extensions: Set<String>,
)

internal val BACKUP_ASSET_KINDS: List<BackupAssetKind> =
    listOf(
        BackupAssetKind(
            folder = "chapter-crests",
            tableName = "regional_chapter",
            idColumn = "crest_image_id",
            extensions = setOf("jpg", "png", "svg"),
        ),
        BackupAssetKind(folder = "event-covers", tableName = "event", idColumn = "cover_image_id", extensions = setOf("jpg", "png")),
        BackupAssetKind(folder = "article-covers", tableName = "article", idColumn = "cover_image_id", extensions = setOf("jpg", "png")),
    )

/** The configured storage roots (`LAPIS_*_STORAGE_ROOT`) of the three asset families. */
class BackupAssetRoots(
    val chapterCrests: File,
    val eventCovers: File,
    val articleCovers: File,
) {
    internal fun rootFor(kind: BackupAssetKind): File =
        when (kind.folder) {
            "chapter-crests" -> chapterCrests
            "event-covers" -> eventCovers
            else -> articleCovers
        }

    companion object {
        /** The defaults when no `LAPIS_*_STORAGE_ROOT` override is set: sub-folders of the document storage. */
        fun under(documentStorageRoot: File): BackupAssetRoots =
            BackupAssetRoots(
                chapterCrests = documentStorageRoot.resolve("chapter-crests"),
                eventCovers = documentStorageRoot.resolve("event-covers"),
                articleCovers = documentStorageRoot.resolve("article-covers"),
            )
    }
}

internal object BackupAssetRules {
    private val NAME = Regex("^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(jpg|png|svg)$")
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    /** A parsed `assets/<folder>/<uuid>.<ext>` entry name, or `null` if it is not strictly of that shape (no sub-path, no traversal). */
    data class ParsedName(
        val kind: BackupAssetKind,
        val id: String,
        val extension: String,
    )

    fun parse(entryName: String): ParsedName? {
        if (!entryName.startsWith(ASSET_ENTRY_PREFIX)) return null
        val parts = entryName.removePrefix(ASSET_ENTRY_PREFIX).split('/')
        if (parts.size != 2) return null
        val kind = BACKUP_ASSET_KINDS.firstOrNull { it.folder == parts[0] } ?: return null
        val match = NAME.matchEntire(parts[1]) ?: return null
        val extension = match.groupValues[2]
        if (extension !in kind.extensions) return null
        return ParsedName(kind = kind, id = match.groupValues[1], extension = extension)
    }

    /**
     * The bytes that may be stored for an entry, or `null` if the content is not acceptable. A manipulated ZIP must not bypass the
     * upload hardening: an SVG goes through the crest sanitizer again and is stored as ITS fresh serialization, a raster must
     * carry the signature of its extension.
     */
    fun acceptableBytes(
        extension: String,
        bytes: ByteArray,
    ): ByteArray? {
        if (bytes.isEmpty() || bytes.size > MAX_RESTORED_ASSET_BYTES) return null
        return when (extension) {
            "svg" ->
                when (val result = SvgCrestSanitizer.sanitize(bytes)) {
                    is SvgSanitizeResult.Accepted -> result.bytes
                    is SvgSanitizeResult.Rejected -> null
                }
            "png" -> if (bytes.startsWith(PNG_SIGNATURE)) bytes else null
            "jpg" -> if (bytes.startsWith(JPEG_SIGNATURE)) bytes else null
            else -> null
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
