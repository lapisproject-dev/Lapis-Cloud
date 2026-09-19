package network.lapis.cloud.server.ai.retrieval

import network.lapis.cloud.server.db.generated.AiKnowledgeChunkTable
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * PostgreSQL full-text retriever (`to_tsvector('german', content) @@ to_tsquery('german', ?)`,
 * ranked by `ts_rank`). Same joins and authorization predicate as [SimpleLikeKnowledgeRetriever]
 * ([ChunkQuerySupport]).
 *
 * **The question is always a bound parameter, never spliced into SQL.** It is first reduced to
 * alphanumeric tokens ([ChunkQuerySupport.tokenize] -- so it cannot carry a tsquery operator) and
 * joined with `|` (OR semantics: a natural-language question would return nothing under
 * `plainto_tsquery`'s AND semantics as soon as one word is absent); binding via
 * [QueryBuilder.registerArgument] is the actual injection barrier, the token reduction is the
 * second one.
 *
 * Works with or without the GIN index [PostgresFullTextIndexInitializer] tries to create -- the
 * index only accelerates.
 *
 * **CI gap, documented on purpose:** this repo has no Testcontainers/embedded Postgres, so CI checks
 * only the SQL shape (`PostgresFullTextRetrieverSqlTest`); the live path is verified by the
 * env-gated `PostgresFullTextRetrieverLiveTest` and must be run once manually against the real
 * instance before first productive use.
 */
internal class PostgresFullTextKnowledgeRetriever : KnowledgeRetriever {
    override fun search(
        query: String,
        allowedLevels: List<DocumentAccessLevel>,
        topK: Int,
    ): List<RetrievedChunk> {
        if (allowedLevels.isEmpty() || topK <= 0) return emptyList()
        val tokens = ChunkQuerySupport.tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val tsQuery = tokens.joinToString(separator = " | ")
        val rank = rankExpression(tsQuery)
        return transaction {
            ChunkQuerySupport.join
                .select(ChunkQuerySupport.join.columns + rank)
                .where { ChunkQuerySupport.authorizedOp(allowedLevels) and matchOp(tsQuery) }
                .orderBy(rank, SortOrder.DESC)
                .limit(topK)
                .map { row -> ChunkQuerySupport.toChunk(row = row, score = row[rank]) }
        }
    }

    internal companion object {
        /** `to_tsvector('german', content) @@ to_tsquery('german', ?)`, [tsQuery] bound as a parameter. */
        fun matchOp(tsQuery: String): Op<Boolean> =
            object : Op<Boolean>() {
                override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                    queryBuilder {
                        append("to_tsvector('german', ")
                        append(AiKnowledgeChunkTable.content)
                        append(") @@ to_tsquery('german', ")
                        registerArgument(TextColumnType(), tsQuery)
                        append(")")
                    }
                }
            }

        /** `ts_rank(to_tsvector('german', content), to_tsquery('german', ?))`, [tsQuery] bound as a parameter. */
        fun rankExpression(tsQuery: String): Expression<Double> =
            object : Expression<Double>() {
                override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                    queryBuilder {
                        append("CAST(ts_rank(to_tsvector('german', ")
                        append(AiKnowledgeChunkTable.content)
                        append("), to_tsquery('german', ")
                        registerArgument(TextColumnType(), tsQuery)
                        append(")) AS DOUBLE PRECISION)")
                    }
                }
            }
    }
}
