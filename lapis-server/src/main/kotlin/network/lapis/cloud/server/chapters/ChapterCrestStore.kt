package network.lapis.cloud.server.chapters

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.RegionalChapterSnapshot
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.util.Base64
import kotlin.uuid.Uuid

/** Server-side constants of the chapter crest. The client-visible limits live in `RegionalChapterPublicRules`. */
internal object ChapterCrestPolicy {
    /** 32 random bytes = 256 bit, Base64url without padding = exactly 43 characters. */
    const val PUBLIC_TOKEN_BYTES = 32
    val PUBLIC_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")

    const val CONTENT_TYPE_JPEG = "image/jpeg"
    const val CONTENT_TYPE_PNG = "image/png"
    const val CONTENT_TYPE_SVG = "image/svg+xml"

    fun contentTypeOf(format: ChapterCrestFormat): String = format.storedContentType

    fun formatOf(contentType: String): ChapterCrestFormat? = ChapterCrestFormat.fromStoredContentType(contentType)
}

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- every database operation on the crest columns of
 * `regional_chapter`. All functions run inside the caller's `transaction {}`; concurrent writers for
 * ONE chapter are serialized with [lockChapter] (`SELECT ... FOR UPDATE`, the same idiom
 * `RegionalChapterService` uses).
 *
 * The database holds only the server-generated image UUID (the file-name key -- never a path) and
 * the public token. A crest is "set" iff the three `crest_*` columns are all set
 * (`chk_regional_chapter_crest_state`); a NEW token is minted on EVERY upload, so a replaced or
 * removed crest's old URL stops working at once.
 */
internal object ChapterCrestStore {
    private val secureRandom = SecureRandom()

    /** 32 random bytes, Base64url without padding (43 characters). */
    fun newPublicToken(): String {
        val bytes = ByteArray(ChapterCrestPolicy.PUBLIC_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun lockChapter(chapterId: Uuid): ResultRow? =
        RegionalChapterTable
            .selectAll()
            .where { RegionalChapterTable.id eq chapterId }
            .forUpdate()
            .singleOrNull()

    /** Sets the crest and returns the PREVIOUS image id (so the caller deletes that file AFTER commit), or `null` if none was set. */
    fun setCrest(
        chapterId: Uuid,
        imageId: Uuid,
        format: ChapterCrestFormat,
        token: String,
    ): Uuid? {
        val previous = selectCrestImageId(chapterId)
        RegionalChapterTable.update({ RegionalChapterTable.id eq chapterId }) {
            it[crestImageId] = imageId
            it[crestPublicToken] = token
            it[crestContentType] = ChapterCrestPolicy.contentTypeOf(format)
        }
        return previous
    }

    /** Clears the crest and returns the previous image id, or `null` if there was none. */
    fun clearCrest(chapterId: Uuid): Uuid? {
        val previous = selectCrestImageId(chapterId)
        RegionalChapterTable.update({ RegionalChapterTable.id eq chapterId }) {
            it[crestImageId] = null
            it[crestPublicToken] = null
            it[crestContentType] = null
        }
        return previous
    }

    /** The image id and format behind [token] -- every "no" (unknown token, removed crest, corrupt row) is the same `null`. */
    fun findServable(token: String): Pair<Uuid, ChapterCrestFormat>? {
        val row =
            RegionalChapterTable
                .selectAll()
                .where { (RegionalChapterTable.crestPublicToken eq token) and RegionalChapterTable.crestImageId.isNotNull() }
                .singleOrNull() ?: return null
        val imageId = row[RegionalChapterTable.crestImageId] ?: return null
        val format = row[RegionalChapterTable.crestContentType]?.let { ChapterCrestPolicy.formatOf(it) } ?: return null
        return imageId to format
    }

    private fun selectCrestImageId(chapterId: Uuid): Uuid? =
        RegionalChapterTable
            .selectAll()
            .where { RegionalChapterTable.id eq chapterId }
            .singleOrNull()
            ?.get(RegionalChapterTable.crestImageId)

    /** One hash-chained audit entry (`REGIONAL_CHAPTER`, UPDATE) -- booleans only, never the description text, token or file key. */
    fun recordAudit(
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
        chapterId: Uuid,
        name: String,
        before: Pair<Boolean, Boolean>,
        after: Pair<Boolean, Boolean>,
        now: LocalDateTime,
    ) {
        fun snapshot(state: Pair<Boolean, Boolean>) =
            Json.encodeToString(
                RegionalChapterSnapshot.serializer(),
                RegionalChapterSnapshot(name = name, descriptionPresent = state.first, crestPresent = state.second),
            )
        AuditLogRecorder.record(
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            entityType = AuditEntityType.REGIONAL_CHAPTER,
            entityId = chapterId,
            action = AuditAction.UPDATE,
            before = snapshot(before),
            after = snapshot(after),
            occurredAt = now,
        )
    }
}
