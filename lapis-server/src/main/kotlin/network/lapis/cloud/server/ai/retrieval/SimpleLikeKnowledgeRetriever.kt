package network.lapis.cloud.server.ai.retrieval

import network.lapis.cloud.server.db.generated.AiKnowledgeChunkTable
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Portable retriever: same joins and authorization predicate as the Postgres one
 * ([ChunkQuerySupport]), matching by tokenized `LIKE ... OR ...` and scoring by token overlap in
 * Kotlin. Runs on H2 (every CI test) and on Postgres alike; it is the implementation the contract
 * tests run against and the fallback for a database without full-text search.
 */
internal class SimpleLikeKnowledgeRetriever : KnowledgeRetriever {
    override fun search(
        query: String,
        allowedLevels: List<DocumentAccessLevel>,
        topK: Int,
    ): List<RetrievedChunk> {
        if (allowedLevels.isEmpty() || topK <= 0) return emptyList()
        val tokens = ChunkQuerySupport.tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val anyToken: Op<Boolean> =
            tokens
                .map<String, Op<Boolean>> { token -> AiKnowledgeChunkTable.content.lowerCase() like "%$token%" }
                .reduce { acc, op -> acc or op }
        return transaction {
            ChunkQuerySupport.join
                .select(ChunkQuerySupport.join.columns)
                .where { ChunkQuerySupport.authorizedOp(allowedLevels) and anyToken }
                .limit(CANDIDATE_LIMIT)
                .map { row ->
                    val haystack = row[AiKnowledgeChunkTable.content].lowercase()
                    ChunkQuerySupport.toChunk(row = row, score = tokens.count { it in haystack }.toDouble() / tokens.size)
                }
        }.sortedByDescending { it.score }.take(topK)
    }

    private companion object {
        const val CANDIDATE_LIMIT = 500
    }
}
