// Articles domain — article (V55__article.sql).
//
// Welle V1.4.34 "Nachrichten-/Artikel-Modul mit redaktionellem Workflow", erweitert Welle V1.4.36
// "Nachrichten-/Artikel-Modul, Folgewelle" -- a single-table domain modeling a self-service
// news/press-release article an ORGANIZATION_MEMBER author drafts, and a BOARD/ADMIN reviewer
// approves/rejects/unpublishes (Vier-Augen-Prinzip -- see
// `network.lapis.cloud.server.rpc.ArticleService.requireNotOwnArticle` KDoc). See
// `network.lapis.cloud.server.rpc.ArticleService` KDoc and
// `network.lapis.cloud.shared.domain.ArticleStatus` KDoc for the full state machine:
//
//   DRAFT/REJECTED --submitArticle--> SUBMITTED --withdrawArticle--> DRAFT
//   SUBMITTED --approveArticle--> PUBLISHED --unpublishArticle--> REJECTED
//   SUBMITTED --rejectArticle--> REJECTED
//
// `slug` is NULL until an article is first approved (`ArticleService.approveArticle` assigns it
// exactly once, then never changes it again -- see `ArticlePolicy.slugFor`). `cover_image_id`
// deliberately has NO foreign key -- same convention as `event.cover_image_id`
// (see 39-events.kuml.kts's own file header): it is an opaque pointer into the article-cover
// storage directory (`article-covers/`, a subdirectory on the same durable volume as
// `documentStorageRoot`/`event-covers/`, see `Application.kt` wiring), not a row in another table.
//
// **V1.4.36 additions (no schema/migration change -- `cover_image_id` already existed):**
// - `IArticleService.listPublishedArticles` -- BOARD/ADMIN-only, newest-`published_at`-first,
//   capped at `ArticleStore.MAX_PAGE_SIZE` (200), backs the board's "Veröffentlicht" tab.
// - The article's title-image URL is status-dependent, computed by
//   `ArticleCoverPolicy.coverImageUrlFor`: PUBLISHED with a slug gets the public, cacheable
//   `/aktuelles/{slug}/bild?v=...` URL; every other status gets the authenticated
//   `/api/articles/{id}/cover?v=...` URL (reachable only by the author or a BOARD/ADMIN reviewer).
// - "Depubliziert" is a CLIENT-SIDE DERIVATION, not a fifth `ArticleStatus` value: a row with
//   `status = REJECTED` AND `published_at IS NOT NULL` was unpublished after having been public;
//   `status = REJECTED` with `published_at IS NULL` was rejected without ever having been public.
//   `ArticleService.rejectArticle` clears `published_at` back to `NULL` on every fresh rejection
//   specifically so this derivation stays unambiguous across a publish → unpublish → resubmit →
//   reject cycle (see that method's own KDoc "Q1").
//
// Two composite/DESC indexes declared on `article` (`idx_article_status_published_at` on
// `(status, published_at DESC)`, `idx_article_author_updated_at` on `(author_id, updated_at
// DESC)`) are NOT reproduced with their DESC ordering below -- kUML's «Index» stereotype only
// carries a plain column-name list, same simplification 39-events.kuml.kts's
// `idx_event_status_starts_at` already establishes for a non-DESC composite index, and the same
// reason `ArticleTable.kt`'s own file-header comment gives for why Exposed's hand-written
// `index {}` DSL isn't wired up here either (needs typed column references, not worth it for a
// verification-only artifact).
//
// No `association(...)` blocks -- every FK on `article` is already generated from its own
// explicit «Column».fkEntity tag above, same mechanism 44-contribution-relief.kuml.kts's and
// 45-travel-expense.kuml.kts's own file headers document for why a redundant `association(...)`
// block would synthesize a SECOND, independently-named FK column.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Articles") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern every other domain
    // file establishes (see e.g. 45-travel-expense.kuml.kts's own `member` stub). Only exists here
    // so UmlToErmTransformer can resolve article.author_id / .reviewed_by within this single-file
    // evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order is load-bearing (mirrors `article.status VARCHAR(10)`, `V55__article.sql`) --
    // append-only, never reorder -- see `ArticleStatus` KDoc for the same rule stated on the
    // Kotlin side.
    val articleStatus = enumOf(name = "ArticleStatus") {
        literal(name = "DRAFT")
        literal(name = "SUBMITTED")
        literal(name = "PUBLISHED")
        literal(name = "REJECTED")
    }

    val article = classOf(name = "Article") {
        stereotype("Entity") { "tableName" to "article"; "kotlinObjectName" to "ArticleTable" }

        stereotype("Index") { "columns" to listOf("status", "published_at"); "name" to "idx_article_status_published_at" }
        stereotype("Index") { "columns" to listOf("author_id", "updated_at"); "name" to "idx_article_author_updated_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // NULL until first approval -- see file header. Unique once assigned (`article_slug_key`).
        attribute(name = "slug", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "slug"; "sqlType" to "VARCHAR(120)" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(140)" }
        }
        attribute(name = "excerpt", type = "String") {
            stereotype("Column") { "columnName" to "excerpt"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "body", type = "String") {
            stereotype("Column") { "columnName" to "body"; "sqlType" to "TEXT" }
        }
        // Opaque pointer into article-cover storage -- deliberately no FK, see file header.
        attribute(name = "coverImageId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "cover_image_id" }
        }
        attribute(name = "authorId", type = "UUID") {
            stereotype("Column") { "columnName" to "author_id"; "fkEntity" to "Member" }
        }
        // Explicit VARCHAR(10) override -- one char of headroom above the current longest literal
        // (SUBMITTED/PUBLISHED, 9 chars each), matching `V53__article.sql` exactly rather than
        // kUML's auto-computed width.
        attribute(name = "status", type = articleStatus) {
            stereotype("Column") {
                "columnName" to "status"
                "sqlType" to "VARCHAR(10)"
                "enumType" to "network.lapis.cloud.shared.domain.ArticleStatus"
            }
        }
        attribute(name = "submittedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "submitted_at" }
        }
        attribute(name = "reviewedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reviewed_by"; "fkEntity" to "Member" }
        }
        attribute(name = "reviewedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reviewed_at" }
        }
        attribute(name = "rejectionReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "rejection_reason"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "publishedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "published_at" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "updated_at" }
        }
    }
}
