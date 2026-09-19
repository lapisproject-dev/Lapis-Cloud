package network.lapis.cloud.server.ai.kb

import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AiKnowledgeIndexStateTable
import network.lapis.cloud.server.db.generated.AiKnowledgeReleaseTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.shared.domain.AiIndexStatus
import network.lapis.cloud.shared.domain.AiKnowledgeEntryDto
import network.lapis.cloud.shared.domain.AiKnowledgeScopeItemDto
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** Members of the searchable scope, split into "searched" and "could not be searched". */
internal class KnowledgeScope(
    val indexed: List<AiKnowledgeScopeItemDto>,
    val unindexed: List<AiKnowledgeScopeItemDto>,
)

/**
 * Which documents were released to the AI knowledge base, and the read models built on top of
 * that. Authorization (BOARD/ADMIN, `PUBLIC_MEMBERS` only) is the caller's job
 * ([network.lapis.cloud.server.rpc.AiAssistantService]); this store only persists.
 */
internal object KnowledgeReleaseStore {
    fun isReleased(documentId: Uuid): Boolean =
        transaction {
            !AiKnowledgeReleaseTable.selectAll().where { AiKnowledgeReleaseTable.documentId eq documentId }.empty()
        }

    /** Idempotent: a second release of the same document changes nothing. */
    fun release(
        documentId: Uuid,
        releasedBy: Uuid,
    ) {
        val now = DbClock.nowLocalDateTime()
        val created =
            transaction {
                if (AiKnowledgeReleaseTable.selectAll().where { AiKnowledgeReleaseTable.documentId eq documentId }.empty()) {
                    AiKnowledgeReleaseTable.insert {
                        it[id] = Uuid.random()
                        it[AiKnowledgeReleaseTable.documentId] = documentId
                        it[AiKnowledgeReleaseTable.releasedBy] = releasedBy
                        it[releasedAt] = now
                    }
                    true
                } else {
                    false
                }
            }
        if (created) {
            KnowledgeIndexer.recordState(
                documentId = documentId,
                versionId = null,
                status = AiIndexStatus.PENDING,
                chunkCount = 0,
                failureCode = null,
            )
        }
    }

    /** Withdraws the release and removes every chunk and the index state. Idempotent. */
    fun revoke(documentId: Uuid) {
        transaction { AiKnowledgeReleaseTable.deleteWhere { AiKnowledgeReleaseTable.documentId eq documentId } }
        KnowledgeIndexer.purgeDocument(documentId)
    }

    /**
     * Admin view of a single document, or `null` if it does not exist / is soft-deleted / is not at
     * one of [visibleLevels] (a caller must never learn the title of a document above their level).
     */
    fun entryFor(
        documentId: Uuid,
        visibleLevels: List<DocumentAccessLevel>,
    ): AiKnowledgeEntryDto? = listEntries(visibleLevels = visibleLevels).firstOrNull { it.documentId == documentId.toString() }

    /**
     * Admin view: every non-deleted document at one of [visibleLevels] with its knowledge-base
     * state. [visibleLevels] MUST be the caller's document-access levels -- title and access level
     * of a `BOARD_ONLY`/`ADMIN_ONLY` document are as confidential as the document itself.
     */
    fun listEntries(visibleLevels: List<DocumentAccessLevel>): List<AiKnowledgeEntryDto> {
        if (visibleLevels.isEmpty()) return emptyList()
        return transaction {
            val releasedIds = AiKnowledgeReleaseTable.selectAll().map { it[AiKnowledgeReleaseTable.documentId] }.toSet()
            val states = AiKnowledgeIndexStateTable.selectAll().associateBy { it[AiKnowledgeIndexStateTable.documentId] }
            DocumentTable
                .selectAll()
                .where { (DocumentTable.isDeleted eq false) and (DocumentTable.accessLevel inList visibleLevels) }
                .map { doc ->
                    val id = doc[DocumentTable.id]
                    val released = id in releasedIds
                    val state = states[id]
                    AiKnowledgeEntryDto(
                        documentId = id.toString(),
                        title = doc[DocumentTable.title],
                        accessLevel = doc[DocumentTable.accessLevel],
                        released = released,
                        status = if (released) effectiveStatus(doc = doc, state = state) else AiIndexStatus.NOT_RELEASED,
                        chunkCount = if (released) state?.get(AiKnowledgeIndexStateTable.chunkCount) ?: 0 else 0,
                        indexedAt = if (released) state?.get(AiKnowledgeIndexStateTable.indexedAt) else null,
                        releasable = doc[DocumentTable.accessLevel] == DocumentAccessLevel.PUBLIC_MEMBERS,
                    )
                }.sortedBy { it.title.lowercase() }
        }
    }

    /**
     * Member view: released, non-deleted documents at one of [allowedLevels], split into indexed
     * (`INDEXED` AND indexed against the CURRENT version) and not indexable, each with a stable
     * reason code for the latter.
     */
    fun listScope(allowedLevels: List<DocumentAccessLevel>): KnowledgeScope {
        if (allowedLevels.isEmpty()) return KnowledgeScope(indexed = emptyList(), unindexed = emptyList())
        return transaction {
            val states = AiKnowledgeIndexStateTable.selectAll().associateBy { it[AiKnowledgeIndexStateTable.documentId] }
            val docs =
                (DocumentTable innerJoin AiKnowledgeReleaseTable)
                    .selectAll()
                    .where { (DocumentTable.isDeleted eq false) and (DocumentTable.accessLevel inList allowedLevels) }
                    .sortedBy { it[DocumentTable.title].lowercase() }
            // Only the two needed columns, and only for the versions of documents in scope.
            val currentVersionIds = docs.mapNotNull { it[DocumentTable.currentVersionId] }.toSet()
            val versionNumbers =
                if (currentVersionIds.isEmpty()) {
                    emptyMap()
                } else {
                    DocumentVersionTable
                        .select(DocumentVersionTable.id, DocumentVersionTable.versionNumber)
                        .where { DocumentVersionTable.id inList currentVersionIds }
                        .associate { it[DocumentVersionTable.id] to it[DocumentVersionTable.versionNumber] }
                }
            val indexed = mutableListOf<AiKnowledgeScopeItemDto>()
            val unindexed = mutableListOf<AiKnowledgeScopeItemDto>()
            docs
                .forEach { doc ->
                    val status = effectiveStatus(doc = doc, state = states[doc[DocumentTable.id]])
                    val version = doc[DocumentTable.currentVersionId]?.let { versionNumbers[it] } ?: 0
                    val title = doc[DocumentTable.title]
                    if (status == AiIndexStatus.INDEXED) {
                        indexed += AiKnowledgeScopeItemDto(documentTitle = title, versionNumber = version)
                    } else {
                        unindexed += AiKnowledgeScopeItemDto(documentTitle = title, versionNumber = version, unindexedReason = status.name)
                    }
                }
            KnowledgeScope(indexed = indexed, unindexed = unindexed)
        }
    }

    /** `INDEXED` only if the index was built against the document's CURRENT version; a stale index reads as `PENDING`. */
    private fun effectiveStatus(
        doc: ResultRow,
        state: ResultRow?,
    ): AiIndexStatus {
        val status = state?.get(AiKnowledgeIndexStateTable.status) ?: return AiIndexStatus.PENDING
        if (status != AiIndexStatus.INDEXED) return status
        return if (state[AiKnowledgeIndexStateTable.documentVersionId] ==
            doc[DocumentTable.currentVersionId]
        ) {
            status
        } else {
            AiIndexStatus.PENDING
        }
    }
}
