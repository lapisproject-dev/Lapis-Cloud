package network.lapis.cloud.client

import dev.kilua.rpc.types.toDouble
import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.NoticeCategory
import network.lapis.cloud.shared.domain.NoticeDto
import network.lapis.cloud.shared.domain.NoticeRemovalReason
import network.lapis.cloud.shared.domain.NoticeStatus

/** V1.9.297 -- labels, icons and colours of the bulletin board: one table each, never an inline string at the call site. */
internal fun noticeCategoryLabel(category: NoticeCategory): String =
    when (category) {
        NoticeCategory.SEARCH -> gettext("Suche")
        NoticeCategory.OFFER -> gettext("Angebot")
        NoticeCategory.INFO -> gettext("Info")
        NoticeCategory.OTHER -> gettext("Sonstiges")
    }

/** Font Awesome Free glyphs (the second channel next to the word; colour is never the only signal). */
internal fun noticeCategoryIcon(category: NoticeCategory): String =
    when (category) {
        NoticeCategory.SEARCH -> "fas fa-magnifying-glass"
        NoticeCategory.OFFER -> "fas fa-hand-holding"
        NoticeCategory.INFO -> "fas fa-circle-info"
        NoticeCategory.OTHER -> "fas fa-ellipsis"
    }

internal fun noticeCategoryColor(category: NoticeCategory): String =
    when (category) {
        NoticeCategory.SEARCH -> "primary"
        NoticeCategory.OFFER -> "success"
        NoticeCategory.INFO -> "info"
        NoticeCategory.OTHER -> "secondary"
    }

internal fun noticeStatusLabel(status: NoticeStatus): String =
    when (status) {
        NoticeStatus.ACTIVE -> gettext("Aktiv")
        NoticeStatus.EXPIRED -> gettext("Abgelaufen")
        NoticeStatus.WITHDRAWN -> gettext("Zurückgezogen")
        NoticeStatus.REMOVED -> gettext("Entfernt")
    }

internal fun noticeRemovalReasonLabel(reason: NoticeRemovalReason): String =
    when (reason) {
        NoticeRemovalReason.SPAM -> gettext("Spam")
        NoticeRemovalReason.RULES -> gettext("Verstoß gegen die Regeln der Organisation")
        NoticeRemovalReason.DUPLICATE -> gettext("Doppelter Aushang")
        NoticeRemovalReason.LEGAL -> gettext("Rechtliche Gründe")
    }

/** The expiry hint of a card: "läuft heute ab" on the last day, else the last day itself. Resolved text (it is concatenated, never a `tr` marker). */
internal fun noticeExpiryText(notice: NoticeDto): String =
    if (notice.daysLeft <= 0) {
        gettext("läuft heute ab")
    } else {
        gettext("sichtbar bis %1", formatDate(notice.expiresOn))
    }

/** `true` when a body is long enough to be folded to two lines (a click unfolds it). */
internal fun noticeBodyNeedsFold(body: String): Boolean = body.length > NOTICE_FOLD_CHARS || body.count { it == '\n' } >= 2

internal const val NOTICE_FOLD_CHARS: Int = 160

/** A weight below one cent is not shown (a notice without a stake carries no pill at all). */
internal fun noticeShowsWeight(notice: NoticeDto): Boolean = notice.weightLtr.toDouble() >= 0.01
