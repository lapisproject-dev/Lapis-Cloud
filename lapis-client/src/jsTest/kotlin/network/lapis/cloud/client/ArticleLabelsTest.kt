package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ArticleStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- covers [ArticleLabels], the pure
 * status-derivation/label/color logic `ArticleEditor`/`ArticlesScreen` build on.
 */
class ArticleLabelsTest {
    private val somePublishedAt = LocalDateTime(2026, 9, 28, 10, 0)

    @Test
    fun displayStatus_rejectedWithoutPublishedAt_isRejected() {
        assertEquals(ArticleDisplayStatus.REJECTED, ArticleLabels.displayStatus(status = ArticleStatus.REJECTED, publishedAt = null))
    }

    @Test
    fun displayStatus_rejectedWithPublishedAt_isDerivedAsUnpublished() {
        // The "Depubliziert" derivation: REJECTED + a non-null publishedAt means the article was
        // public at some point and was taken down, never that it was simply rejected pre-publication.
        assertEquals(
            ArticleDisplayStatus.UNPUBLISHED,
            ArticleLabels.displayStatus(status = ArticleStatus.REJECTED, publishedAt = somePublishedAt),
        )
    }

    @Test
    fun displayStatus_everyOtherStatus_mapsOneToOne() {
        assertEquals(ArticleDisplayStatus.DRAFT, ArticleLabels.displayStatus(status = ArticleStatus.DRAFT, publishedAt = null))
        assertEquals(ArticleDisplayStatus.SUBMITTED, ArticleLabels.displayStatus(status = ArticleStatus.SUBMITTED, publishedAt = null))
        assertEquals(
            ArticleDisplayStatus.PUBLISHED,
            ArticleLabels.displayStatus(status = ArticleStatus.PUBLISHED, publishedAt = somePublishedAt),
        )
    }

    @Test
    fun colorHex_matchesTheFourApprovedHexValues() {
        assertEquals("#6B7280", ArticleLabels.colorHex(ArticleDisplayStatus.DRAFT))
        assertEquals("#2563EB", ArticleLabels.colorHex(ArticleDisplayStatus.SUBMITTED))
        assertEquals("#16A34A", ArticleLabels.colorHex(ArticleDisplayStatus.PUBLISHED))
        assertEquals("#DC2626", ArticleLabels.colorHex(ArticleDisplayStatus.REJECTED))
        assertEquals("#DC2626", ArticleLabels.colorHex(ArticleDisplayStatus.UNPUBLISHED))
    }

    @Test
    fun label_everyDisplayStatus_hasANonBlankLabel() {
        for (status in ArticleDisplayStatus.entries) {
            assertTrue(ArticleLabels.label(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }
}
