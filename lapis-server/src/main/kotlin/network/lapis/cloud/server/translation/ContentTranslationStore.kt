package network.lapis.cloud.server.translation

import network.lapis.cloud.server.db.generated.ContentTranslationTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.time.ServerClock
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * The ONLY reader and writer of `content_translation` (tripwire: `TranslationStructureTest`). Opens NO transaction itself; the callers do.
 * Typed Exposed builders only.
 */
internal object ContentTranslationStore {
    /** Exact hash matches only: a row whose source hash differs from the current text is never returned. */
    fun findAll(
        type: TranslatableContentType,
        id: Uuid,
        sourceLang: String,
        targetLang: String,
        hashes: Map<TranslatableField, String>,
    ): Map<TranslatableField, String> {
        if (hashes.isEmpty()) return emptyMap()
        val rows =
            ContentTranslationTable
                .selectAll()
                .where {
                    (ContentTranslationTable.contentType eq type.name) and
                        (ContentTranslationTable.contentId eq id) and
                        (ContentTranslationTable.sourceLang eq sourceLang) and
                        (ContentTranslationTable.targetLang eq targetLang) and
                        (ContentTranslationTable.sourceSha256 inList hashes.values.toSet())
                }.toList()
        val result = LinkedHashMap<TranslatableField, String>()
        for (row in rows) {
            val field = TranslatableField.entries.firstOrNull { it.name == row[ContentTranslationTable.field] } ?: continue
            if (hashes[field] == row[ContentTranslationTable.sourceSha256]) result[field] = row[ContentTranslationTable.translatedText]
        }
        return result
    }

    /**
     * [findAll] for many contents of one type in ONE query (feeds). Same exact-hash rule per content and field; contents without any hit are
     * absent from the map. Callers pass a bounded id list (the feeds cap at 50).
     */
    fun findAllForMany(
        type: TranslatableContentType,
        hashesById: Map<Uuid, Map<TranslatableField, String>>,
        sourceLang: String,
        targetLang: String,
    ): Map<Uuid, Map<TranslatableField, String>> {
        val wanted = hashesById.filterValues { it.isNotEmpty() }
        if (wanted.isEmpty()) return emptyMap()
        val allHashes = wanted.values.flatMap { it.values }.toSet()
        val rows =
            ContentTranslationTable
                .selectAll()
                .where {
                    (ContentTranslationTable.contentType eq type.name) and
                        (ContentTranslationTable.contentId inList wanted.keys) and
                        (ContentTranslationTable.sourceLang eq sourceLang) and
                        (ContentTranslationTable.targetLang eq targetLang) and
                        (ContentTranslationTable.sourceSha256 inList allHashes)
                }.toList()
        val result = LinkedHashMap<Uuid, LinkedHashMap<TranslatableField, String>>()
        for (row in rows) {
            val id = row[ContentTranslationTable.contentId]
            val field = TranslatableField.entries.firstOrNull { it.name == row[ContentTranslationTable.field] } ?: continue
            if (wanted[id]?.get(field) == row[ContentTranslationTable.sourceSha256]) {
                result.getOrPut(id) { LinkedHashMap() }[field] = row[ContentTranslationTable.translatedText]
            }
        }
        return result
    }

    /**
     * Inserts each row under its own savepoint and swallows ONLY a unique violation (a concurrent writer produced the same row). On Postgres a
     * failed statement would otherwise poison the whole transaction.
     */
    fun insertIgnoringDuplicates(rows: List<ContentTranslationRow>) {
        val createdAt = ServerClock.now()
        for (row in rows) {
            try {
                withSavepoint(name = "content_translation_insert") {
                    ContentTranslationTable.insert {
                        it[contentType] = row.contentType.name
                        it[contentId] = row.contentId
                        it[field] = row.field.name
                        it[sourceSha256] = row.sourceSha256
                        it[sourceLang] = row.sourceLang
                        it[targetLang] = row.targetLang
                        it[translatedText] = row.translatedText
                        it[modelProfile] = row.modelProfile
                        it[ContentTranslationTable.createdAt] = createdAt
                    }
                }
            } catch (e: Exception) {
                if (!e.isUniqueViolation()) throw e
            }
        }
    }

    fun deleteForContent(
        type: TranslatableContentType,
        id: Uuid,
    ): Int =
        ContentTranslationTable.deleteWhere {
            (ContentTranslationTable.contentType eq type.name) and (ContentTranslationTable.contentId eq id)
        }

    /** Deletes every row whose (field, hash) is not current any more -- also fields that are empty now. */
    fun deleteStale(
        type: TranslatableContentType,
        id: Uuid,
        currentHashes: Map<TranslatableField, String>,
    ): Int {
        val existing =
            ContentTranslationTable
                .selectAll()
                .where { (ContentTranslationTable.contentType eq type.name) and (ContentTranslationTable.contentId eq id) }
                .map { it[ContentTranslationTable.field] to it[ContentTranslationTable.sourceSha256] }
                .toSet()
        var deleted = 0
        for ((fieldName, hash) in existing) {
            val field = TranslatableField.entries.firstOrNull { it.name == fieldName }
            if (field == null || currentHashes[field] != hash) {
                deleted +=
                    ContentTranslationTable.deleteWhere {
                        (contentType eq type.name) and (contentId eq id) and
                            (ContentTranslationTable.field eq fieldName) and (sourceSha256 eq hash)
                    }
            }
        }
        return deleted
    }
}
