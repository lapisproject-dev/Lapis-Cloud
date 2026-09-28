package network.lapis.cloud.server.articles

import network.lapis.cloud.server.events.EventPolicy
import network.lapis.cloud.shared.domain.ArticleDraftInput
import network.lapis.cloud.shared.rpc.BadRequestException

/**
 * Pure fachlogik for `article` -- no DB access, no transaction, same unit-testable posture
 * [EventPolicy]/`network.lapis.cloud.server.crm.CrmContactPolicy` already establish. Mirrors
 * `V55__article.sql`'s column widths.
 */
object ArticlePolicy {
    const val TITLE_MIN = 5
    const val TITLE_MAX = 140
    const val EXCERPT_MIN = 1
    const val EXCERPT_MAX = 300
    const val BODY_MIN = 1
    const val BODY_MAX = 100_000
    const val REJECTION_REASON_MAX = 1000
    const val UNPUBLISH_REASON_MIN = 10
    const val UNPUBLISH_REASON_MAX = 1000

    /**
     * Runs on EVERY `saveDraft` call, including brand-new empty drafts -- only the upper length
     * ceilings are enforced here (a draft may otherwise be blank/incomplete). The stricter
     * "actually ready to publish" check is [validateForSubmit], run only on `submitArticle`.
     */
    fun validateDraftLengths(input: ArticleDraftInput) {
        if (input.title.length > TITLE_MAX) throw BadRequestException("title exceeds $TITLE_MAX characters")
        if (input.excerpt.length > EXCERPT_MAX) throw BadRequestException("excerpt exceeds $EXCERPT_MAX characters")
        if (input.body.length > BODY_MAX) throw BadRequestException("body exceeds $BODY_MAX characters")
    }

    /** Server-side authority regardless of any client-side pre-check -- see `ArticleService.submitArticle`. */
    fun validateForSubmit(
        title: String,
        excerpt: String,
        body: String,
    ) {
        if (title.length < TITLE_MIN || title.length > TITLE_MAX) {
            throw BadRequestException("title must be $TITLE_MIN..$TITLE_MAX characters")
        }
        if (excerpt.length < EXCERPT_MIN || excerpt.length > EXCERPT_MAX) {
            throw BadRequestException("excerpt must be $EXCERPT_MIN..$EXCERPT_MAX characters")
        }
        if (body.length < BODY_MIN || body.length > BODY_MAX) {
            throw BadRequestException("body must be $BODY_MIN..$BODY_MAX characters")
        }
    }

    fun validateRejectionReason(reason: String?) {
        if (reason != null && reason.length > REJECTION_REASON_MAX) {
            throw BadRequestException("reason exceeds $REJECTION_REASON_MAX characters")
        }
    }

    fun validateUnpublishReason(reason: String) {
        if (reason.length < UNPUBLISH_REASON_MIN || reason.length > UNPUBLISH_REASON_MAX) {
            throw BadRequestException("reason must be $UNPUBLISH_REASON_MIN..$UNPUBLISH_REASON_MAX characters")
        }
    }

    /**
     * Reuses [EventPolicy.slugFor] rather than duplicating the transliteration/collision-suffix
     * logic -- [EventPolicy] is a public (not `internal`) object, so this is a plain cross-package
     * call, not a visibility workaround (Stolperfalle §5 of the implementation plan: extracting a
     * domain-neutral `SlugGenerator` was considered but deferred as out-of-scope-for-this-wave
     * risk-minimization -- this delegation is the risk-lower minimal diff).
     */
    fun slugFor(
        title: String,
        isTaken: (String) -> Boolean,
    ): String = EventPolicy.slugFor(title = title, isTaken = isTaken)
}
