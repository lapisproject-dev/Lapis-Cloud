package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.OpenItemAgingBucket
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemSettlementKind
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.ReceivableDunningNoticeStatus

/**
 * Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" -- reine, DOM-freie Label-/Farb-Bausteine,
 * gleiche Grammatik wie `TravelExpenseLabels.kt`: `when` über `entries`, erschöpfend, `gettext(...)`.
 *
 * Die Übersetzung der Fachbegriffe (Kreditor/Debitor) steht bewusst NUR hier -- Jobs/Kare-Ruling,
 * siehe `docs/architecture/open-items.adoc`.
 */
fun openItemDirectionLabel(direction: OpenItemDirection): String =
    when (direction) {
        OpenItemDirection.PAYABLE -> gettext("Kreditor")
        OpenItemDirection.RECEIVABLE -> gettext("Debitor")
    }

fun openItemDirectionColor(direction: OpenItemDirection): String =
    when (direction) {
        OpenItemDirection.PAYABLE -> "dark"
        OpenItemDirection.RECEIVABLE -> "info"
    }

fun openItemStatusLabel(status: OpenItemStatus): String =
    when (status) {
        OpenItemStatus.OPEN -> gettext("Offen")
        OpenItemStatus.PARTIALLY_SETTLED -> gettext("Teilweise ausgeglichen")
        OpenItemStatus.SETTLED -> gettext("Ausgeglichen")
        OpenItemStatus.CANCELLED -> gettext("Storniert")
    }

fun openItemStatusColor(status: OpenItemStatus): String =
    when (status) {
        OpenItemStatus.OPEN -> "secondary"
        OpenItemStatus.PARTIALLY_SETTLED -> "info"
        OpenItemStatus.SETTLED -> "success"
        OpenItemStatus.CANCELLED -> "dark"
    }

fun openItemSettlementKindLabel(kind: OpenItemSettlementKind): String =
    when (kind) {
        OpenItemSettlementKind.PAYMENT -> gettext("Zahlung")
        OpenItemSettlementKind.NETTING -> gettext("Verrechnung")
    }

fun openItemAgingBucketLabel(bucket: OpenItemAgingBucket): String =
    when (bucket) {
        OpenItemAgingBucket.NOT_DUE -> gettext("Nicht fällig")
        OpenItemAgingBucket.DAYS_1_30 -> gettext("1–30 Tage")
        OpenItemAgingBucket.DAYS_31_90 -> gettext("31–90 Tage")
        OpenItemAgingBucket.OVER_90 -> gettext("Über 90 Tage")
    }

fun receivableDunningNoticeStatusLabel(status: ReceivableDunningNoticeStatus): String =
    when (status) {
        ReceivableDunningNoticeStatus.ISSUED -> gettext("Ausgestellt")
        ReceivableDunningNoticeStatus.SKIPPED -> gettext("Übersprungen")
        ReceivableDunningNoticeStatus.CANCELLED -> gettext("Storniert")
    }

fun receivableDunningNoticeStatusColor(status: ReceivableDunningNoticeStatus): String =
    when (status) {
        ReceivableDunningNoticeStatus.ISSUED -> "info"
        ReceivableDunningNoticeStatus.SKIPPED -> "secondary"
        ReceivableDunningNoticeStatus.CANCELLED -> "dark"
    }

/**
 * Ive-Ruling: GENAU EIN Überfälligkeits-Badge, kein vierstufiges Ampelsystem. Text daneben nennt
 * die exakte Tageszahl im Klartext ([openItemOverdueDaysLabel], aus `OpenItemDto.daysOverdue`) --
 * siehe `OpenItemsScreen.kt` (`appendOpenItemRow`).
 */
fun openItemOverdueLabel(): String = gettext("Überfällig")

/** Klartext-Tageszahl neben dem Überfällig-Badge; [daysOverdue] ist server-berechnet. */
fun openItemOverdueDaysLabel(daysOverdue: Int): String =
    if (daysOverdue ==
        1
    ) {
        gettext("seit 1 Tag")
    } else {
        gettext("seit %1 Tagen", daysOverdue)
    }

/** Segment-Beschriftung der Segmented Control. Fachbegriffe stehen nur in [openItemDirectionLabel]-Nähe. */
fun openItemSegmentLabel(segment: OpenItemSegment): String =
    when (segment) {
        OpenItemSegment.ALL -> gettext("Alle")
        OpenItemSegment.PAYABLE -> gettext("Kreditoren (Verbindlichkeiten)")
        OpenItemSegment.RECEIVABLE -> gettext("Debitoren (Forderungen)")
    }

/** Beschriftung der Kennzahl-Kacheln. */
fun openItemMetricLabel(kind: OpenItemMetricKind): String =
    when (kind) {
        OpenItemMetricKind.PAYABLE_OPEN -> gettext("Offene Verbindlichkeiten")
        OpenItemMetricKind.RECEIVABLE_OPEN -> gettext("Offene Forderungen")
        OpenItemMetricKind.OVERDUE -> gettext("Davon überfällig")
        OpenItemMetricKind.NETTABLE -> gettext("Verrechenbare Gegenparteien")
    }

/** Mahnstufen-Spalte: `null` = noch keine Mahnung ausgestellt. */
fun receivableDunningLevelLabel(levelNumber: Int?): String = if (levelNumber == null) "–" else gettext("Stufe %1", levelNumber)
