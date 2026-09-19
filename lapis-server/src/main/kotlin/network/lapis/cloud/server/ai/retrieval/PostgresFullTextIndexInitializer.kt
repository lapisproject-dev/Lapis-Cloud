package network.lapis.cloud.server.ai.retrieval

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

/**
 * Creates the optional GIN index over `ai_knowledge_chunk.content` on PostgreSQL, once at startup,
 * and only when the AI layer is operational.
 *
 * **Why runtime and not a Flyway migration.** The index is a pure accelerator: the retriever's
 * `to_tsvector(...) @@ to_tsquery(...)` is correct without it. A failing index build is therefore
 * a log line, never a startup failure -- and a second (vendor-specific) Flyway location would touch
 * `DatabaseConfig` and hand H2 an empty location for no gain. (Documented in
 * `docs/architecture/ai-assistant.adoc`.)
 *
 * **`pg_trgm`/`unaccent` are created best-effort but NOT used in this wave.** `unaccent` is not
 * `IMMUTABLE` and cannot go into an index expression without a wrapper function; the `german`
 * text-search configuration already normalizes the statute corpus well enough. They become relevant
 * for typo tolerance in a later wave. Each statement runs in its own `runCatching` (missing
 * privileges are expected on managed databases).
 */
internal object PostgresFullTextIndexInitializer {
    /** Idempotent, best-effort, **never throws**. */
    fun ensureIndexes() {
        listOf(
            "CREATE EXTENSION IF NOT EXISTS unaccent",
            "CREATE EXTENSION IF NOT EXISTS pg_trgm",
            "CREATE INDEX IF NOT EXISTS idx_ai_knowledge_chunk_fts ON ai_knowledge_chunk USING GIN (to_tsvector('german', content))",
        ).forEach { statement ->
            runCatching { transaction { exec(statement) } }
                .onFailure {
                    logger.info { "AI-Volltextindex: Statement übersprungen (${it::class.simpleName}) -- Suche funktioniert auch ohne." }
                }
        }
    }
}
