package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.OpenItemAgingBucket
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemSettlementKind
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.ReceivableDunningNoticeStatus

/**
 * Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" -- reine, DOM-freie Label-/Farb-Bausteine,
 * gleiche Grammatik wie `TravelExpenseLabels.kt`: `when` über `entries`, erschöpfend, `tr(...)`.
 *
 * Die Übersetzung der Fachbegriffe (Kreditor/Debitor) steht bewusst NUR hier -- Jobs/Kare-Ruling,
 * siehe `docs/architecture/open-items.adoc`.
 */
fun openItemDirectionLabel(direction: OpenItemDirection): String =
    when (direction) {
        OpenItemDirection.PAYABLE -> tr("Kreditor")
        OpenItemDirection.RECEIVABLE -> tr("Debitor")
    }

fun openItemDirectionColor(direction: OpenItemDirection): String =
    when (direction) {
        OpenItemDirection.PAYABLE -> "dark"
        OpenItemDirection.RECEIVABLE -> "info"
    }

fun openItemStatusLabel(status: OpenItemStatus): String =
    when (status) {
        OpenItemStatus.OPEN -> tr("Offen")
        OpenItemStatus.PARTIALLY_SETTLED -> tr("Teilweise ausgeglichen")
        OpenItemStatus.SETTLED -> tr("Ausgeglichen")
        OpenItemStatus.CANCELLED -> tr("Storniert")
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
        OpenItemSettlementKind.PAYMENT -> tr("Zahlung")
        OpenItemSettlementKind.NETTING -> tr("Verrechnung")
    }

fun openItemAgingBucketLabel(bucket: OpenItemAgingBucket): String =
    when (bucket) {
        OpenItemAgingBucket.NOT_DUE -> tr("Nicht fällig")
        OpenItemAgingBucket.DAYS_1_30 -> tr("1–30 Tage")
        OpenItemAgingBucket.DAYS_31_90 -> tr("31–90 Tage")
        OpenItemAgingBucket.OVER_90 -> tr("Über 90 Tage")
    }

fun receivableDunningNoticeStatusLabel(status: ReceivableDunningNoticeStatus): String =
    when (status) {
        ReceivableDunningNoticeStatus.ISSUED -> tr("Ausgestellt")
        ReceivableDunningNoticeStatus.SKIPPED -> tr("Übersprungen")
        ReceivableDunningNoticeStatus.CANCELLED -> tr("Storniert")
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
fun openItemOverdueLabel(): String = tr("Überfällig")

/** Klartext-Tageszahl neben dem Überfällig-Badge; [daysOverdue] ist server-berechnet. */
fun openItemOverdueDaysLabel(daysOverdue: Int): String = if (daysOverdue == 1) tr("seit 1 Tag") else gettext("seit %1 Tagen", daysOverdue)

/** Segment-Beschriftung der Segmented Control. Fachbegriffe stehen nur in [openItemDirectionLabel]-Nähe. */
fun openItemSegmentLabel(segment: OpenItemSegment): String =
    when (segment) {
        OpenItemSegment.ALL -> tr("Alle")
        OpenItemSegment.PAYABLE -> tr("Kreditoren (Verbindlichkeiten)")
        OpenItemSegment.RECEIVABLE -> tr("Debitoren (Forderungen)")
    }

/** Beschriftung der Kennzahl-Kacheln. */
fun openItemMetricLabel(kind: OpenItemMetricKind): String =
    when (kind) {
        OpenItemMetricKind.PAYABLE_OPEN -> tr("Offene Verbindlichkeiten")
        OpenItemMetricKind.RECEIVABLE_OPEN -> tr("Offene Forderungen")
        OpenItemMetricKind.OVERDUE -> tr("Davon überfällig")
        OpenItemMetricKind.NETTABLE -> tr("Verrechenbare Gegenparteien")
    }

/** Mahnstufen-Spalte: `null` = noch keine Mahnung ausgestellt. */
fun receivableDunningLevelLabel(levelNumber: Int?): String = if (levelNumber == null) "–" else gettext("Stufe %1", levelNumber)
