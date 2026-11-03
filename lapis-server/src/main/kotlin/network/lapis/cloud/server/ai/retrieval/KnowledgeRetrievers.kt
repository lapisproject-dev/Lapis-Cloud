package network.lapis.cloud.server.ai.retrieval

import network.lapis.cloud.server.config.SecretEnv

/** Picks the retriever matching the configured database. */
internal object KnowledgeRetrievers {
    fun forCurrentDatabase(env: (String) -> String? = SecretEnv::get): KnowledgeRetriever =
        if (isPostgres(env)) PostgresFullTextKnowledgeRetriever() else SimpleLikeKnowledgeRetriever()

    /** Same probe `DatabaseConfig` uses: `LAPIS_DB_URL` starts with `jdbc:postgresql`. */
    fun isPostgres(env: (String) -> String? = SecretEnv::get): Boolean = env("LAPIS_DB_URL")?.trim()?.startsWith("jdbc:postgresql") == true
}
