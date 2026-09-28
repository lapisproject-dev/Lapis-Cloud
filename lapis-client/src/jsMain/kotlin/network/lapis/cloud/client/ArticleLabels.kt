package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ArticleDto
import network.lapis.cloud.shared.domain.ArticleStatus

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- the client's own five-way status
 * presentation. Deliberately NOT a sixth wire value on [ArticleStatus] itself -- [UNPUBLISHED] is a
 * pure CLIENT-SIDE DERIVATION of [ArticleStatus.REJECTED] with a non-null `publishedAt` (see
 * [displayStatus]), same "no fifth server status" decision `ArticleService.rejectArticle`'s own
 * KDoc "Q1" documents on the server side.
 */
enum class ArticleDisplayStatus { DRAFT, SUBMITTED, PUBLISHED, REJECTED, UNPUBLISHED }

/** Pure/testable -- see `ArticleLabelsTest`. */
object ArticleLabels {
    /**
     * `status == REJECTED && publishedAt != null` -> [ArticleDisplayStatus.UNPUBLISHED] (the
     * article was public at some point and was taken down); `status == REJECTED && publishedAt ==
     * null` -> [ArticleDisplayStatus.REJECTED] (never published). Every other [ArticleStatus] maps
     * 1:1 by name.
     */
    fun displayStatus(
        status: ArticleStatus,
        publishedAt: LocalDateTime?,
    ): ArticleDisplayStatus =
        when (status) {
            ArticleStatus.DRAFT -> ArticleDisplayStatus.DRAFT
            ArticleStatus.SUBMITTED -> ArticleDisplayStatus.SUBMITTED
            ArticleStatus.PUBLISHED -> ArticleDisplayStatus.PUBLISHED
            ArticleStatus.REJECTED -> if (publishedAt != null) ArticleDisplayStatus.UNPUBLISHED else ArticleDisplayStatus.REJECTED
        }

    fun displayStatus(dto: ArticleDto): ArticleDisplayStatus = displayStatus(status = dto.status, publishedAt = dto.publishedAt)

    fun label(status: ArticleDisplayStatus): String =
        when (status) {
            ArticleDisplayStatus.DRAFT -> tr("Entwurf")
            ArticleDisplayStatus.SUBMITTED -> tr("Zur Freigabe eingereicht")
            ArticleDisplayStatus.PUBLISHED -> tr("Veröffentlicht")
            ArticleDisplayStatus.REJECTED -> tr("Abgelehnt")
            ArticleDisplayStatus.UNPUBLISHED -> tr("Depubliziert")
        }

    /** DRAFT #6B7280 (grau), SUBMITTED #2563EB (blau), PUBLISHED #16A34A (grün), REJECTED/UNPUBLISHED #DC2626 (rot). */
    fun colorHex(status: ArticleDisplayStatus): String =
        when (status) {
            ArticleDisplayStatus.DRAFT -> "#6B7280"
            ArticleDisplayStatus.SUBMITTED -> "#2563EB"
            ArticleDisplayStatus.PUBLISHED -> "#16A34A"
            ArticleDisplayStatus.REJECTED, ArticleDisplayStatus.UNPUBLISHED -> "#DC2626"
        }
}

/**
 * Sidebar badge label for the board's "Freigabe"-queue count -- same "null/0 -> plain label,
 * >=200 -> '200+'" grammar `reliefSidebarLabel`/`travelExpenseSidebarLabel` already establish
 * (mirrors `ArticleStore.MAX_PAGE_SIZE`). Pure -- see `SidebarLabelsTest`.
 */
internal fun articlesSidebarLabel(openCount: Int?): String =
    when {
        openCount == null || openCount == 0 -> tr("Artikel")
        openCount >= 200 -> gettext("Artikel (%1)", "200+")
        else -> gettext("Artikel (%1)", openCount)
    }
