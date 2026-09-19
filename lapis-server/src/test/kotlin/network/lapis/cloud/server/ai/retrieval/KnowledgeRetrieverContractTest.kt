package network.lapis.cloud.server.ai.retrieval

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.ai.AiTestFixtures
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import kotlin.uuid.Uuid

private val ALL_LEVELS = DocumentAccessLevel.entries
private val PUBLIC_ONLY = listOf(DocumentAccessLevel.PUBLIC_MEMBERS)

/**
 * Security contract of every [KnowledgeRetriever], executed against [SimpleLikeKnowledgeRetriever]
 * on H2 (the Postgres implementation shares the join/authorization predicate through
 * [ChunkQuerySupport]; its own SQL shape is pinned by `PostgresFullTextRetrieverSqlTest`).
 */
class KnowledgeRetrieverContractTest :
    FunSpec({
        val fixtures = AiTestFixtures()
        val retriever: KnowledgeRetriever = SimpleLikeKnowledgeRetriever()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec { fixtures.dispose() }

        /** A unique word per test keeps the shared H2 database's other rows out of the assertions. */
        fun uniqueWord() =
            "zzq" +
                Uuid
                    .random()
                    .toString()
                    .replace("-", "")
                    .take(12)

        test("only chunks of released documents are found") {
            val word = uniqueWord()
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val released = fixtures.document(author = admin)
            val unreleased = fixtures.document(author = admin)
            fixtures.release(document = released, by = admin)
            fixtures.chunk(document = released, text = "Beitrag $word alpha")
            fixtures.chunk(document = unreleased, text = "Beitrag $word beta")
            val hits = retriever.search(query = word, allowedLevels = PUBLIC_ONLY, topK = 10)
            hits.map { it.documentId } shouldBe listOf(released.id)
        }

        test("soft-deleted documents are never returned") {
            val word = uniqueWord()
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin)
            fixtures.release(document = doc, by = admin)
            fixtures.chunk(document = doc, text = "Text $word")
            retriever.search(query = word, allowedLevels = PUBLIC_ONLY, topK = 10) shouldHaveSize 1
            fixtures.softDelete(doc)
            retriever.search(query = word, allowedLevels = PUBLIC_ONLY, topK = 10).shouldBeEmpty()
        }

        test("chunks of a version that is no longer current (stale index) are never returned") {
            val word = uniqueWord()
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin)
            fixtures.release(document = doc, by = admin)
            fixtures.chunk(document = doc, text = "Alter Text $word")
            retriever.search(query = word, allowedLevels = PUBLIC_ONLY, topK = 10) shouldHaveSize 1
            fixtures.newVersion(document = doc, number = 2, uploadedBy = admin)
            retriever.search(query = word, allowedLevels = PUBLIC_ONLY, topK = 10).shouldBeEmpty()
        }

        test("BYPASS GUARD: re-classifying a document after indexing takes effect immediately, chunks or not") {
            val word = uniqueWord()
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin, level = DocumentAccessLevel.PUBLIC_MEMBERS)
            fixtures.release(document = doc, by = admin)
            fixtures.chunk(document = doc, text = "Vertraulicher Beitrag $word")
            // A plain member's allowed levels: PUBLIC_MEMBERS only.
            retriever.search(query = word, allowedLevels = PUBLIC_ONLY, topK = 10) shouldHaveSize 1
            fixtures.setAccessLevel(document = doc, level = DocumentAccessLevel.BOARD_ONLY)
            // The chunk row still exists, unchanged -- the live join on the document is what excludes it.
            retriever.search(query = word, allowedLevels = PUBLIC_ONLY, topK = 10).shouldBeEmpty()
        }

        test("an ADMIN_ONLY document yields nothing for a board member's levels, but does for an admin's") {
            val word = uniqueWord()
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin, level = DocumentAccessLevel.ADMIN_ONLY)
            fixtures.release(document = doc, by = admin)
            fixtures.chunk(document = doc, text = "Nur für Admins $word")
            val boardLevels = listOf(DocumentAccessLevel.PUBLIC_MEMBERS, DocumentAccessLevel.BOARD_ONLY)
            retriever.search(query = word, allowedLevels = boardLevels, topK = 10).shouldBeEmpty()
            retriever.search(query = word, allowedLevels = ALL_LEVELS, topK = 10) shouldHaveSize 1
        }

        test("an empty level list yields nothing") {
            val word = uniqueWord()
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin)
            fixtures.release(document = doc, by = admin)
            fixtures.chunk(document = doc, text = "Text $word")
            retriever.search(query = word, allowedLevels = emptyList(), topK = 10).shouldBeEmpty()
        }

        test("topK is honored and the better match ranks first") {
            val word = uniqueWord()
            val other = uniqueWord()
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin)
            fixtures.release(document = doc, by = admin)
            fixtures.chunk(document = doc, text = "nur $word", index = 0)
            fixtures.chunk(document = doc, text = "$word und $other", index = 1)
            fixtures.chunk(document = doc, text = "$word noch einmal", index = 2)
            val hits = retriever.search(query = "$word $other", allowedLevels = PUBLIC_ONLY, topK = 2)
            hits shouldHaveSize 2
            hits.first().text shouldBe "$word und $other"
        }

        test("citation metadata comes from the stored records") {
            val word = uniqueWord()
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin, title = "Satzung $word")
            fixtures.release(document = doc, by = admin)
            fixtures.chunk(document = doc, text = "Inhalt $word", label = "§ 3", page = 5)
            val hit = retriever.search(query = word, allowedLevels = PUBLIC_ONLY, topK = 5).single()
            hit.documentTitle shouldBe "Satzung $word"
            hit.versionNumber shouldBe 1
            hit.sectionLabel shouldBe "§ 3"
            hit.pageNumber shouldBe 5
        }

        test("LIKE wildcards and SQL metacharacters in the question are inert") {
            val admin = fixtures.member(role = AccountRole.ADMIN)
            val doc = fixtures.document(author = admin)
            fixtures.release(document = doc, by = admin)
            fixtures.chunk(document = doc, text = "harmloser Inhalt")
            retriever.search(query = "%%% ' OR 1=1 -- ;", allowedLevels = PUBLIC_ONLY, topK = 10).shouldBeEmpty()
        }

        test("tokenizer reduces a question to bounded alphanumeric tokens") {
            val tokens =
                ChunkQuerySupport.tokenize(
                    "Wie hoch ist der Beitrag?! (§ 7) a to_tsquery('x' | y) " + "wort ".repeat(50) + (1..30).joinToString(" ") { "tok$it" },
                )
            (tokens.size <= ChunkQuerySupport.MAX_TOKENS) shouldBe true
            tokens.all { token -> token.all { it.isLetterOrDigit() } && token.length >= 3 } shouldBe true
        }
    })
