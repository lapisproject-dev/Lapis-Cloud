package network.lapis.cloud.server.videooverlays

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.VideoOverlayAssetTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.VideoOverlayAdminItemDto
import network.lapis.cloud.shared.domain.VideoOverlayItemDto
import network.lapis.cloud.shared.domain.VideoOverlayKind
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.util.Base64
import kotlin.uuid.Uuid

/** Server-side constants of the overlay library. The client-visible limits live in `VideoOverlayRules`. */
internal object VideoOverlayPolicy {
    /** 32 random bytes = 256 bit, Base64url without padding = exactly 43 characters. */
    const val ACCESS_TOKEN_BYTES = 32
    val ACCESS_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
    const val IMAGE_PATH_PREFIX = "/api/video-overlays/"

    fun imagePathFor(token: String): String = IMAGE_PATH_PREFIX + token
}

/** What a stored element is, as far as the file system needs to know. */
internal data class VideoOverlayStoredRef(
    val id: Uuid,
    val format: VideoOverlayFormat,
)

/** What the delivery route needs to serve one element. */
internal data class VideoOverlayServable(
    val id: Uuid,
    val format: VideoOverlayFormat,
)

/**
 * Welle V1.9.129 -- THE ONLY WRITER of `video_overlay_asset` (tripwire `VideoOverlayStoreSingleWriterTripwireTest`). All functions run inside
 * the caller's `transaction {}`. The library is one instance-wide list; concurrent writers are serialized with [lockLibrary]
 * (`SELECT ... FOR UPDATE` on the single `organization_settings` row), which also makes the 50-element limit race-free.
 *
 * The audit snapshot ([snapshotJson]) carries kind, name and sizes only -- never the access token, the hash or any bytes.
 */
internal object VideoOverlayStore {
    private val secureRandom = SecureRandom()

    /** 32 random bytes, Base64url without padding (43 characters). */
    fun newAccessToken(): String {
        val bytes = ByteArray(VideoOverlayPolicy.ACCESS_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Serializes every writer of the library for the rest of the transaction. */
    fun lockLibrary() {
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .forUpdate()
            .singleOrNull()
    }

    fun count(): Long = VideoOverlayAssetTable.selectAll().count()

    fun listOrdered(): List<ResultRow> =
        VideoOverlayAssetTable
            .selectAll()
            .orderBy(VideoOverlayAssetTable.sortOrder to SortOrder.ASC, VideoOverlayAssetTable.createdAt to SortOrder.ASC)
            .toList()

    /** The 0-based position of [id] in the ordered library, or -1. */
    fun positionOf(id: Uuid): Int = listOrdered().indexOfFirst { it[VideoOverlayAssetTable.id] == id }

    fun findById(id: Uuid): ResultRow? = VideoOverlayAssetTable.selectAll().where { VideoOverlayAssetTable.id eq id }.singleOrNull()

    /** The element behind [token] -- every "no" (unknown, malformed, deleted, corrupt row) is the same `null`. */
    fun findByToken(token: String): VideoOverlayServable? {
        if (!VideoOverlayPolicy.ACCESS_TOKEN_PATTERN.matches(token)) return null
        val row = VideoOverlayAssetTable.selectAll().where { VideoOverlayAssetTable.accessToken eq token }.singleOrNull() ?: return null
        val format = VideoOverlayFormat.fromExtension(row[VideoOverlayAssetTable.format]) ?: return null
        return VideoOverlayServable(id = row[VideoOverlayAssetTable.id], format = format)
    }

    fun insert(
        id: Uuid,
        kind: VideoOverlayKind,
        name: String,
        format: VideoOverlayFormat,
        width: Int,
        height: Int,
        sizeBytes: Long,
        sha256: String,
        token: String,
        now: LocalDateTime,
    ): ResultRow {
        val maxOrder = VideoOverlayAssetTable.sortOrder.max()
        val nextOrder = (VideoOverlayAssetTable.select(maxOrder).singleOrNull()?.get(maxOrder) ?: -1) + 1
        VideoOverlayAssetTable.insert {
            it[VideoOverlayAssetTable.id] = id
            it[VideoOverlayAssetTable.kind] = kind.name
            it[VideoOverlayAssetTable.name] = name
            it[VideoOverlayAssetTable.format] = format.extension
            it[VideoOverlayAssetTable.width] = width
            it[VideoOverlayAssetTable.height] = height
            it[VideoOverlayAssetTable.sizeBytes] = sizeBytes
            it[VideoOverlayAssetTable.sha256] = sha256
            it[VideoOverlayAssetTable.accessToken] = token
            it[VideoOverlayAssetTable.sortOrder] = nextOrder
            it[VideoOverlayAssetTable.createdAt] = now
        }
        return findById(id)!!
    }

    fun rename(
        id: Uuid,
        name: String,
    ) {
        VideoOverlayAssetTable.update({ VideoOverlayAssetTable.id eq id }) { it[VideoOverlayAssetTable.name] = name }
    }

    /** Deletes the row and returns what the caller needs to delete the file AFTER the commit, or `null` if there was none. */
    fun delete(id: Uuid): VideoOverlayStoredRef? {
        val row = findById(id) ?: return null
        val format = VideoOverlayFormat.fromExtension(row[VideoOverlayAssetTable.format]) ?: VideoOverlayFormat.PNG
        val ref = VideoOverlayStoredRef(id = id, format = format)
        VideoOverlayAssetTable.deleteWhere { VideoOverlayAssetTable.id eq id }
        return ref
    }

    /**
     * Moves [id] one place [up] or down by swapping positions with its neighbour; the whole list is first renumbered `0..n-1`, so a gap or a
     * tie in `sort_order` can never make a move a no-op. Returns `false` when the element does not exist or is already at the edge.
     */
    fun move(
        id: Uuid,
        up: Boolean,
    ): Boolean {
        val ids = listOrdered().map { it[VideoOverlayAssetTable.id] }.toMutableList()
        val index = ids.indexOf(id)
        if (index < 0) return false
        val target = if (up) index - 1 else index + 1
        if (target !in ids.indices) return false
        ids[index] = ids[target].also { ids[target] = ids[index] }
        ids.forEachIndexed { position, element ->
            VideoOverlayAssetTable.update({ VideoOverlayAssetTable.id eq element }) { it[sortOrder] = position }
        }
        return true
    }

    fun toItem(row: ResultRow): VideoOverlayItemDto =
        VideoOverlayItemDto(
            id = row[VideoOverlayAssetTable.id].toString(),
            kind = VideoOverlayKind.valueOf(row[VideoOverlayAssetTable.kind]),
            name = row[VideoOverlayAssetTable.name],
            width = row[VideoOverlayAssetTable.width],
            height = row[VideoOverlayAssetTable.height],
            imagePath = VideoOverlayPolicy.imagePathFor(row[VideoOverlayAssetTable.accessToken]),
        )

    fun toAdminItem(row: ResultRow): VideoOverlayAdminItemDto =
        VideoOverlayAdminItemDto(
            item = toItem(row),
            sizeBytes = row[VideoOverlayAssetTable.sizeBytes],
            createdAt = row[VideoOverlayAssetTable.createdAt],
        )

    /** `{"kind","name","width","height","sizeBytes"}` -- nothing else may ever enter the audit chain. */
    fun snapshotJson(row: ResultRow): String = snapshotWithPosition(row = row, position = null)

    /** Like [snapshotJson] plus the 0-based library position -- for the audit entry of a reorder (before and after differ by it). */
    fun snapshotJsonAt(
        row: ResultRow,
        position: Int,
    ): String = snapshotWithPosition(row = row, position = position)

    private fun snapshotWithPosition(
        row: ResultRow,
        position: Int?,
    ): String =
        Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                buildMap {
                    put("kind", JsonPrimitive(row[VideoOverlayAssetTable.kind]))
                    put("name", JsonPrimitive(row[VideoOverlayAssetTable.name]))
                    put("width", JsonPrimitive(row[VideoOverlayAssetTable.width]))
                    put("height", JsonPrimitive(row[VideoOverlayAssetTable.height]))
                    put("sizeBytes", JsonPrimitive(row[VideoOverlayAssetTable.sizeBytes]))
                    // Library position (0-based index in the ordered list); no personal data. Only set for a reorder.
                    if (position != null) put("position", JsonPrimitive(position))
                },
            ),
        )

    fun recordAudit(
        actorMemberId: Uuid,
        actorRole: AccountRole,
        entityId: Uuid,
        action: AuditAction,
        before: String?,
        after: String?,
        now: LocalDateTime,
    ) {
        AuditLogRecorder.record(
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            entityType = AuditEntityType.VIDEO_OVERLAY,
            entityId = entityId,
            action = action,
            before = before,
            after = after,
            occurredAt = now,
        )
    }
}
