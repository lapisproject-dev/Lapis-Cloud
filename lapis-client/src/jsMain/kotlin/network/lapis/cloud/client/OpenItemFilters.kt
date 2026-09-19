package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import network.lapis.cloud.shared.domain.CounterpartyKey
import network.lapis.cloud.shared.domain.OpenItemAgingBucket
import network.lapis.cloud.shared.domain.OpenItemAgingBucketDto
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.OpenItemStatusSets
import network.lapis.cloud.shared.domain.OpenItemSummaryDto

/**
 * Welle V1.4.21 "Bedienoberfläche Offene Posten" -- reine, DOM-freie Filter-/Kennzahlen-Logik für
 * `OpenItemsScreen`, direkt unit-testbar (`OpenItemFiltersTest`).
 *
 * **Nie `openAmount` oder `daysOverdue` neu herleiten** (Stolperfalle S5): beides kommt
 * server-berechnet gegen `OpenItemDto.asOf` (Serveruhr, nicht Browseruhr) und wird hier nur
 * gelesen. Eine client-seitige Datumsdifferenz würde am Tagesrand falsche Überfälligkeiten zeigen.
 */
enum class OpenItemSegment { ALL, PAYABLE, RECEIVABLE }

/** `null` = beide Richtungen (RPC-Parameter `direction` von `listOpenItems`/`getOpenItemSummary`). */
fun OpenItemSegment.toDirection(): OpenItemDirection? =
    when (this) {
        OpenItemSegment.ALL -> null
        OpenItemSegment.PAYABLE -> OpenItemDirection.PAYABLE
        OpenItemSegment.RECEIVABLE -> OpenItemDirection.RECEIVABLE
    }

/**
 * Jobs-Ruling 3: Status-Filter, Suche und "nur überfällig" wirken **ausschließlich** auf bereits
 * geladene Zeilen. `listOpenItems` hat weder Status-Filter noch Suche; der Screen sagt das mit
 * Zahlen an ([openItemFilterCounts]), statt eine Vollständigkeit vorzutäuschen.
 */
data class OpenItemListFilter(
    val statuses: Set<OpenItemStatus> = OpenItemStatusSets.SETTLEABLE,
    val search: String = "",
    val onlyOverdue: Boolean = false,
)

/**
 * `true`, sobald SETTLED oder CANCELLED angehakt ist -- dann muss `listOpenItems(onlyOpen = false)`
 * aufgerufen werden, sonst lieferte der Server die geschlossenen Zeilen gar nicht erst.
 */
fun OpenItemListFilter.requiresClosedRows(): Boolean = statuses.any { it in OpenItemStatusSets.CLOSED }

/**
 * Suche über Gegenpartei-Name und Belegnummer. Normalisierung über [CounterpartyKey.of] -- dieselbe
 * Funktion, die der Server für das Netting-Matching nutzt (nie eine zweite Normalisierung):
 * Groß-/Kleinschreibung und Mehrfach-Leerzeichen sind egal.
 */
fun applyOpenItemFilter(
    items: List<OpenItemDto>,
    filter: OpenItemListFilter,
): List<OpenItemDto> {
    val needle = CounterpartyKey.of(filter.search)
    return items.filter { item ->
        item.status in filter.statuses &&
            (!filter.onlyOverdue || (item.daysOverdue > 0 && item.status in OpenItemStatusSets.SETTLEABLE)) &&
            (
                needle.isEmpty() ||
                    CounterpartyKey.of(item.counterpartyName).contains(needle) ||
                    CounterpartyKey.of(item.reference.orEmpty()).contains(needle)
            )
    }
}

/** Normans Etikett "N von M geladen": (sichtbar, geladen). */
data class OpenItemFilterCounts(
    val shown: Int,
    val loaded: Int,
)

/**
 * **Der Bildschirm ruft das nicht auf** (Audit-Fund N11): `OpenItemsScreen.renderList` filtert
 * GENAU EINMAL und leitet `shown`/`loaded` aus demselben Ergebnis ab -- diese Funktion hätte den
 * Filter ein zweites Mal über alle geladenen Zeilen laufen lassen. Sie bleibt als direkt testbare
 * Formulierung derselben Rechnung ([OpenItemFiltersTest]) erhalten; wer sie benutzt, sollte das
 * Filterergebnis nicht daneben noch selbst berechnen.
 */
fun openItemFilterCounts(
    items: List<OpenItemDto>,
    filter: OpenItemListFilter,
): OpenItemFilterCounts = OpenItemFilterCounts(shown = applyOpenItemFilter(items, filter).size, loaded = items.size)

/** Summe der überfälligen Buckets ([OpenItemAgingBucket.NOT_DUE] fließt nie ein). */
data class OpenItemOverdueTotal(
    val count: Int,
    val amount: Decimal,
)

/**
 * Anzeige-Aggregat über die server-berechneten Aging-Buckets (Bucket-Grenzen und Beträge kommen vom
 * Server). Das Aufsummieren zweier bis dreier bereits gerundeter Zwei-Nachkommastellen-Beträge ist
 * hier ein reines Darstellungsmittel -- auf zwei Stellen zurückgerundet, damit keine
 * Gleitkomma-Artefakte (`0.30000000000000004`) im Klartext erscheinen.
 */
fun overdueTotal(buckets: List<OpenItemAgingBucketDto>): OpenItemOverdueTotal {
    val overdue = buckets.filter { it.bucket != OpenItemAgingBucket.NOT_DUE }
    return OpenItemOverdueTotal(
        count = overdue.sumOf { it.count },
        amount = Validation.roundToTwoDecimalPlaces(overdue.sumOf { it.totalAmount.toDouble() }).toDecimal(),
    )
}

/** Über beide Richtungen -- ausschließlich für die "Alle"-Sicht. Nie ein Saldo, immer eine Summe. */
fun overdueTotalBothDirections(summary: OpenItemSummaryDto): OpenItemOverdueTotal =
    overdueTotal(
        summary.payableBuckets + summary.receivableBuckets,
    )

/** Summe der Anzahl aller Buckets = Anzahl offener Posten der Richtung. */
fun openItemBucketCount(buckets: List<OpenItemAgingBucketDto>): Int = buckets.sumOf { it.count }

enum class OpenItemMetricKind { PAYABLE_OPEN, RECEIVABLE_OPEN, OVERDUE, NETTABLE }

/** Rams-Ruling: genau drei Kacheln, nie mehr. */
sealed interface OpenItemMetricTile {
    val kind: OpenItemMetricKind

    data class Money(
        override val kind: OpenItemMetricKind,
        val amount: Decimal,
        val count: Int?,
    ) : OpenItemMetricTile

    data class Count(
        override val kind: OpenItemMetricKind,
        val count: Int,
    ) : OpenItemMetricTile
}

/**
 * "Alle": offene Verbindlichkeiten, offene Forderungen, überfällig (beide Richtungen) -- niemals
 * eine saldierte Zahl (Jobs-Ruling, siehe [OpenItemSummaryDto] KDoc).
 * Richtungs-Segment: offen (dieser Richtung), überfällig (dieser Richtung) und -- nur mit
 * Schreibrecht und mindestens einer Verrechnungs-Gegenpartei -- "verrechenbar" als dritte Kachel.
 */
fun openItemMetricTiles(
    segment: OpenItemSegment,
    summary: OpenItemSummaryDto,
    canWrite: Boolean,
): List<OpenItemMetricTile> =
    when (segment) {
        OpenItemSegment.ALL -> {
            val overdue = overdueTotalBothDirections(summary)
            listOf(
                OpenItemMetricTile.Money(
                    OpenItemMetricKind.PAYABLE_OPEN,
                    summary.payableOpenTotal,
                    openItemBucketCount(summary.payableBuckets),
                ),
                OpenItemMetricTile.Money(
                    OpenItemMetricKind.RECEIVABLE_OPEN,
                    summary.receivableOpenTotal,
                    openItemBucketCount(summary.receivableBuckets),
                ),
                OpenItemMetricTile.Money(OpenItemMetricKind.OVERDUE, overdue.amount, overdue.count),
            )
        }
        OpenItemSegment.PAYABLE, OpenItemSegment.RECEIVABLE -> {
            val payable = segment == OpenItemSegment.PAYABLE
            val buckets = if (payable) summary.payableBuckets else summary.receivableBuckets
            val overdue = overdueTotal(buckets)
            buildList {
                add(
                    OpenItemMetricTile.Money(
                        if (payable) OpenItemMetricKind.PAYABLE_OPEN else OpenItemMetricKind.RECEIVABLE_OPEN,
                        if (payable) summary.payableOpenTotal else summary.receivableOpenTotal,
                        openItemBucketCount(buckets),
                    ),
                )
                add(OpenItemMetricTile.Money(OpenItemMetricKind.OVERDUE, overdue.amount, overdue.count))
                if (canWrite && summary.nettingCandidateCounterpartyCount > 0) {
                    add(OpenItemMetricTile.Count(OpenItemMetricKind.NETTABLE, summary.nettingCandidateCounterpartyCount))
                }
            }
        }
    }
