package network.lapis.cloud.server.ai.retrieval

import network.lapis.cloud.shared.domain.DocumentAccessLevel
import kotlin.uuid.Uuid

/** One retrieved passage plus everything a citation needs. All fields come from stored records. */
internal data class RetrievedChunk(
    val chunkId: Uuid,
    val documentId: Uuid,
    val documentVersionId: Uuid,
    val documentTitle: String,
    val versionNumber: Int,
    val sectionLabel: String?,
    val pageNumber: Int?,
    val text: String,
    val score: Double,
)

/**
 * The only data access the model-facing pipeline has. Implementations MUST enforce, in the query
 * itself and live (never from a copied column): released document, not soft-deleted, chunk belongs
 * to the document's CURRENT version, and the document's access level is in [allowedLevels].
 * Swapping in an embedding/pgvector retriever later happens behind this interface.
 */
internal fun interface KnowledgeRetriever {
    /**
     * [allowedLevels] stems ALWAYS from `CurrentMember.canAccessDocumentAtLevel` -- the retriever
     * trusts no other source for authorization.
     */
    fun search(
        query: String,
        allowedLevels: List<DocumentAccessLevel>,
        topK: Int,
    ): List<RetrievedChunk>
}
