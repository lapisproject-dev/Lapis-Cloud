package network.lapis.cloud.server.ai.kb

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AiKnowledgeChunkTable
import network.lapis.cloud.server.db.generated.AiKnowledgeIndexStateTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.shared.domain.AiIndexStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Extracts, chunks and persists the text of a released document's CURRENT version, synchronously
 * (this repo has no scheduler; the caps in [DocumentTextExtractor]/[TextChunker] bound the cost).
 *
 * **[indexDocument] never throws.** Every outcome is recorded in `ai_knowledge_index_state`;
 * unsupported or failed documents keep zero chunks, so the "searched documents" block can say
 * honestly which document could not be read.
 *
 * The retriever never trusts this index for authorization or freshness (it joins release, deletion
 * flag, access level and current-version LIVE), so a missed [markStale]/[purgeDocument] hook can
 * only cost housekeeping, never leak withdrawn text.
 */
internal class KnowledgeIndexer(
    private val storageRoot: File,
) {
    private class Source(
        val versionId: Uuid?,
        val storageKey: String?,
        val mimeType: String?,
        val fileName: String?,
        val deleted: Boolean,
    )

    fun indexDocument(documentId: Uuid): AiIndexStatus =
        try {
            doIndex(documentId)
        } catch (e: Exception) {
            // Class name only -- an exception message may embed paths.
            logger.warn { "AI-Indexierung fehlgeschlagen (${e::class.simpleName})." }
            runCatching {
                recordState(
                    documentId = documentId,
                    versionId = null,
                    status = AiIndexStatus.FAILED,
                    chunkCount = 0,
                    failureCode = "INDEX_ERROR",
                )
            }
            AiIndexStatus.FAILED
        }

    private fun doIndex(documentId: Uuid): AiIndexStatus {
        if (!KnowledgeReleaseStore.isReleased(documentId)) return AiIndexStatus.NOT_RELEASED
        val source = loadSource(documentId)
        if (source == null || source.deleted) {
            purgeDocument(documentId)
            return AiIndexStatus.NOT_RELEASED
        }
        val versionId = source.versionId
        val storageKey = source.storageKey
        if (versionId == null || storageKey == null) {
            recordState(
                documentId = documentId,
                versionId = null,
                status = AiIndexStatus.PENDING,
                chunkCount = 0,
                failureCode = "NO_VERSION",
            )
            return AiIndexStatus.PENDING
        }
        val root = storageRoot.canonicalFile
        val file = File(root, storageKey).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) {
            recordState(
                documentId = documentId,
                versionId = versionId,
                status = AiIndexStatus.FAILED,
                chunkCount = 0,
                failureCode = "IO_ERROR",
            )
            return AiIndexStatus.FAILED
        }
        return when (
            val extraction =
                DocumentTextExtractor.extract(
                    file = file,
                    mimeType = source.mimeType.orEmpty(),
                    fileName = source.fileName.orEmpty(),
                )
        ) {
            is ExtractionResult.Unsupported -> {
                replaceChunks(documentId = documentId, versionId = versionId, chunks = emptyList())
                recordState(
                    documentId = documentId,
                    versionId = versionId,
                    status = AiIndexStatus.UNSUPPORTED_FORMAT,
                    chunkCount = 0,
                    failureCode = extraction.code.name,
                )
                AiIndexStatus.UNSUPPORTED_FORMAT
            }
            is ExtractionResult.Failed -> {
                replaceChunks(documentId = documentId, versionId = versionId, chunks = emptyList())
                recordState(
                    documentId = documentId,
                    versionId = versionId,
                    status = AiIndexStatus.FAILED,
                    chunkCount = 0,
                    failureCode = extraction.code.name,
                )
                AiIndexStatus.FAILED
            }
            is ExtractionResult.Extracted -> {
                val chunks = TextChunker.chunk(extraction.pages)
                if (chunks.isEmpty()) {
                    replaceChunks(documentId = documentId, versionId = versionId, chunks = emptyList())
                    recordState(
                        documentId = documentId,
                        versionId = versionId,
                        status = AiIndexStatus.UNSUPPORTED_FORMAT,
                        chunkCount = 0,
                        failureCode = UnsupportedReason.NO_EXTRACTABLE_TEXT.name,
                    )
                    AiIndexStatus.UNSUPPORTED_FORMAT
                } else {
                    replaceChunks(documentId = documentId, versionId = versionId, chunks = chunks)
                    recordState(
                        documentId = documentId,
                        versionId = versionId,
                        status = AiIndexStatus.INDEXED,
                        chunkCount = chunks.size,
                        failureCode = null,
                    )
                    AiIndexStatus.INDEXED
                }
            }
        }
    }

    private fun loadSource(documentId: Uuid): Source? =
        transaction {
            val doc = DocumentTable.selectAll().where { DocumentTable.id eq documentId }.singleOrNull() ?: return@transaction null
            val versionId = doc[DocumentTable.currentVersionId]
            val version =
                versionId?.let { id -> DocumentVersionTable.selectAll().where { DocumentVersionTable.id eq id }.singleOrNull() }
            Source(
                versionId = versionId,
                storageKey = version?.get(DocumentVersionTable.storageKey),
                mimeType = version?.get(DocumentVersionTable.mimeType),
                fileName = version?.get(DocumentVersionTable.fileName),
                deleted = doc[DocumentTable.isDeleted],
            )
        }

    private fun replaceChunks(
        documentId: Uuid,
        versionId: Uuid,
        chunks: List<TextChunk>,
    ) {
        val now = DbClock.nowLocalDateTime()
        transaction {
            AiKnowledgeChunkTable.deleteWhere { AiKnowledgeChunkTable.documentId eq documentId }
            if (chunks.isNotEmpty()) {
                AiKnowledgeChunkTable.batchInsert(chunks) { chunk ->
                    this[AiKnowledgeChunkTable.id] = Uuid.random()
                    this[AiKnowledgeChunkTable.documentId] = documentId
                    this[AiKnowledgeChunkTable.documentVersionId] = versionId
                    this[AiKnowledgeChunkTable.chunkIndex] = chunk.index
                    this[AiKnowledgeChunkTable.sectionLabel] = chunk.sectionLabel
                    this[AiKnowledgeChunkTable.pageNumber] = chunk.pageNumber
                    this[AiKnowledgeChunkTable.content] = chunk.text
                    this[AiKnowledgeChunkTable.charCount] = chunk.text.length
                    this[AiKnowledgeChunkTable.createdAt] = now
                }
            }
        }
    }

    companion object {
        /**
         * Cheap marker without extraction, for the upload path: a NEW version exists, so the
         * existing index is stale. Only acts when the document is released.
         */
        fun markStale(documentId: Uuid) {
            if (!KnowledgeReleaseStore.isReleased(documentId)) return
            recordState(
                documentId = documentId,
                versionId = null,
                status = AiIndexStatus.PENDING,
                chunkCount = 0,
                failureCode = null,
                keepVersion = true,
            )
        }

        /** Removes all chunks and the index state of [documentId] (release revoked or document deleted). */
        fun purgeDocument(documentId: Uuid) {
            transaction {
                AiKnowledgeChunkTable.deleteWhere { AiKnowledgeChunkTable.documentId eq documentId }
                AiKnowledgeIndexStateTable.deleteWhere { AiKnowledgeIndexStateTable.documentId eq documentId }
            }
        }

        internal fun recordState(
            documentId: Uuid,
            versionId: Uuid?,
            status: AiIndexStatus,
            chunkCount: Int,
            failureCode: String?,
            keepVersion: Boolean = false,
        ) {
            val now: LocalDateTime = DbClock.nowLocalDateTime()
            val indexedAt = if (status == AiIndexStatus.INDEXED) now else null
            transaction {
                val updated =
                    AiKnowledgeIndexStateTable.update({ AiKnowledgeIndexStateTable.documentId eq documentId }) {
                        if (!keepVersion) it[documentVersionId] = versionId
                        it[AiKnowledgeIndexStateTable.status] = status
                        if (!keepVersion) it[AiKnowledgeIndexStateTable.chunkCount] = chunkCount
                        it[AiKnowledgeIndexStateTable.indexedAt] = indexedAt
                        it[AiKnowledgeIndexStateTable.failureCode] = failureCode
                    }
                if (updated == 0) {
                    AiKnowledgeIndexStateTable.insert {
                        it[id] = Uuid.random()
                        it[AiKnowledgeIndexStateTable.documentId] = documentId
                        it[documentVersionId] = versionId
                        it[AiKnowledgeIndexStateTable.status] = status
                        it[AiKnowledgeIndexStateTable.chunkCount] = chunkCount
                        it[AiKnowledgeIndexStateTable.indexedAt] = indexedAt
                        it[AiKnowledgeIndexStateTable.failureCode] = failureCode
                    }
                }
            }
        }
    }
}
