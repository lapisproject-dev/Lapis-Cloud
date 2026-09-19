package network.lapis.cloud.server.ai.retrieval

/** Picks the retriever matching the configured database. */
internal object KnowledgeRetrievers {
    fun forCurrentDatabase(env: (String) -> String? = System::getenv): KnowledgeRetriever =
        if (isPostgres(env)) PostgresFullTextKnowledgeRetriever() else SimpleLikeKnowledgeRetriever()

    /** Same probe `DatabaseConfig` uses: `LAPIS_DB_URL` starts with `jdbc:postgresql`. */
    fun isPostgres(env: (String) -> String? = System::getenv): Boolean = env("LAPIS_DB_URL")?.trim()?.startsWith("jdbc:postgresql") == true
}
