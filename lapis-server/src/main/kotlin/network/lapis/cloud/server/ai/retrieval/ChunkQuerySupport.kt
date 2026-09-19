package network.lapis.cloud.server.ai.retrieval

import network.lapis.cloud.server.db.generated.AiKnowledgeChunkTable
import network.lapis.cloud.server.db.generated.AiKnowledgeReleaseTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList

/** Shared join + authorization predicate of both retrievers -- the security core lives in exactly one place. */
internal object ChunkQuerySupport {
    /** Hard cap on query tokens, so a pathological question cannot build an enormous query. */
    const val MAX_TOKENS = 12
    private const val MIN_TOKEN_LENGTH = 3
    private const val MAX_TOKEN_LENGTH = 40
    private val SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Lower-cased alphanumeric tokens (>= 3 chars, distinct, capped). Anything that is not a letter
     * or digit is a separator, so a token can never carry a tsquery operator or a LIKE wildcard.
     */
    fun tokenize(query: String): List<String> =
        query
            .lowercase()
            .split(SEPARATOR)
            .filter { it.length >= MIN_TOKEN_LENGTH }
            .map { it.take(MAX_TOKEN_LENGTH) }
            .distinct()
            .take(MAX_TOKENS)

    val join: Join =
        AiKnowledgeChunkTable
            .join(DocumentTable, JoinType.INNER, AiKnowledgeChunkTable.documentId, DocumentTable.id)
            .join(DocumentVersionTable, JoinType.INNER, AiKnowledgeChunkTable.documentVersionId, DocumentVersionTable.id)
            .join(AiKnowledgeReleaseTable, JoinType.INNER, DocumentTable.id, AiKnowledgeReleaseTable.documentId)

    /**
     * Live authorization/consistency predicate: not soft-deleted, level in [allowedLevels], and the
     * chunk belongs to the document's CURRENT version (a stale index can never surface withdrawn
     * text). The release join above already guarantees the document is still released.
     */
    fun authorizedOp(allowedLevels: List<DocumentAccessLevel>): Op<Boolean> =
        (DocumentTable.isDeleted eq false) and
            (DocumentTable.accessLevel inList allowedLevels) and
            (AiKnowledgeChunkTable.documentVersionId eq DocumentTable.currentVersionId)

    fun toChunk(
        row: ResultRow,
        score: Double,
    ): RetrievedChunk =
        RetrievedChunk(
            chunkId = row[AiKnowledgeChunkTable.id],
            documentId = row[AiKnowledgeChunkTable.documentId],
            documentVersionId = row[AiKnowledgeChunkTable.documentVersionId],
            documentTitle = row[DocumentTable.title],
            versionNumber = row[DocumentVersionTable.versionNumber],
            sectionLabel = row[AiKnowledgeChunkTable.sectionLabel],
            pageNumber = row[AiKnowledgeChunkTable.pageNumber],
            text = row[AiKnowledgeChunkTable.content],
            score = score,
        )
}
