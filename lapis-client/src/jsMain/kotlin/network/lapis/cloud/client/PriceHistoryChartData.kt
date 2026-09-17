package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDouble
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.AnchorPolicy
import network.lapis.cloud.shared.domain.PriceSnapshotDto

/**
 * Price-Oracle Kursverlauf-Diagramm, Folgewelle zu `getPriceHistory` (`IPriceOracleService`) --
 * reine, DOM-freie Datenaufbereitung fuer den Chart.js-Bereich in `PriceOracleScreen.kt`. Getrennt
 * von der Rendering-Funktion, damit sie ohne Rendering-Harness (siehe `PriceOracleScreenTest.kt`
 * KDoc "No rendering harness") direkt getestet werden kann -- dieselbe Trennung, die dieses Modul
 * schon bei [network.lapis.cloud.client.estimateLtrMinted] anwendet.
 *
 * Ein Punkt auf der Zeitachse, oder `null` als Luecken-Marker (siehe [buildPriceHistoryChartData] KDoc).
 */
internal data class PriceHistoryPoint(
    /** NUR fuer chart-interne Zwecke (Luecken-Erkennung ueber Zeitdifferenzen, Kategorie-Label
     * fuer die x-Achse) -- NIE fuer eine dem Nutzer angezeigte Zeit verwenden, siehe [dateLabel].
     * Ueber [TimeZone.UTC] aus [PriceHistoryPoint]s [priceTimestamp]-Quelle (server-lokales
     * `LocalDateTime`, siehe `PriceOracleService.getPriceHistory` KDoc) abgeleitet -- eine fest
     * gewaehlte, DST-freie Zone einzig damit, weil fuer Differenzbildung nur ein *konsistenter*
     * Offset ueber alle Punkte hinweg zaehlt, nicht der tatsaechliche Moment. */
    val epochMillis: Long,
    val yValue: Double,
    /** Woertliche, server-formatierte Anzeige-Zeichenkette fuer den Tooltip -- NIE ueber [yValue]/Double nachgerechnet. */
    val tooltipLabel: String,
    /** Lesbarer Zeitstempel fuer den Tooltip-Titel (`dd.MM.yyyy HH:mm`) -- direkt aus den
     * Komponenten des server-gelieferten `LocalDateTime` gebildet (`.day`/`.month`/`.year`/
     * `.hour`/`.minute`), NIE ueber `toInstant(TimeZone.currentSystemDefault())` im Browser
     * nachgerechnet. Der Server erzeugt `priceTimestamp` bereits als
     * `TimeZone.currentSystemDefault()` DES SERVER-PROZESSES (siehe `PriceOracleService.kt`) --
     * eine Re-Interpretation dieses "nackten" `LocalDateTime` mit der Zeitzone DES BROWSERS wuerde
     * bei einer Zeitzonen-Differenz zwischen Server und Operator falsche Zeitpunkte anzeigen
     * (Review-Befund 2026-09-17). Die Komponenten direkt zu formatieren ist zeitzonen-neutral:
     * es zeigt exakt das, was der Server als Wanduhrzeit erfasst hat. */
    val dateLabel: String,
)

internal data class PriceHistoryChartData(
    /** `null`-Eintraege markieren eine Luecke (Chart.js mit `spanGaps = false` bricht die Linie dort ab). */
    val points: List<PriceHistoryPoint?>,
    val gapCount: Int,
    val truncated: Boolean,
    val pointCount: Int,
)

/** Server-seitiger Row-Cap, siehe `PriceOracleService.kt`'s `MAX_PRICE_HISTORY_LIMIT`. Bei exakt
 * dieser Anzahl Zeilen in [rows] gilt die Historie als moeglicherweise am aeltesten Rand
 * abgeschnitten (siehe [IPriceOracleService.getPriceHistory] KDoc "on overflow the NEWEST rows are
 * kept") und [PriceHistoryChartData.truncated] wird `true`. */
internal const val PRICE_HISTORY_MAX_LIMIT = 5_000

/**
 * Reine, DOM-freie Aufbereitung von [rows] (bereits aufsteigend nach `priceTimestamp`, wie vom
 * Server garantiert) zu Chart.js-Punkten. Design-Team-Entscheidung (Don Norman): Cache-Plateaus
 * (Gold/Fiat aktualisieren sich alle 12h, der Poller schreibt aber stuendlich) sind KEIN Randfall
 * -- aufeinanderfolgende Zeilen mit identischem `priceTimestamp` werden auf einen Punkt dedupliziert.
 * X-Achse ist `priceTimestamp` (wann der Kurs GALT), nie `capturedAt` (wann wir ihn abschrieben).
 *
 * Luecken-Schwelle: `max(3_600_000, AnchorPolicy.refreshIntervalSeconds(anchor) * 1000L) * 2.5`
 * -- ueber dieser Distanz zwischen zwei (deduplizierten) Punkten wird ein `null`-Punkt eingefuegt,
 * damit Chart.js (`spanGaps = false`) die Linie dort abreisst statt eine erfundene Verbindung ueber
 * eine echte Unterbrechung (Poller zeitweise aus) zu ziehen.
 *
 * [PriceHistoryChartData.truncated] ist `true` genau dann, wenn [rows].size exakt
 * [PRICE_HISTORY_MAX_LIMIT] ist -- der Server schneidet bei Ueberschreiten stillschweigend den
 * AELTESTEN Rand ab, das muss sichtbar gemacht werden statt eine falsche vollstaendige Historie zu
 * suggerieren.
 *
 * Server liefert bereits aufsteigend sortiert -- diese Funktion sortiert NICHT erneut, nur
 * Dedupe/Luecken-Erkennung on top.
 */
internal fun buildPriceHistoryChartData(
    rows: List<PriceSnapshotDto>,
    anchor: AnchorAsset,
    formatTooltip: (Decimal) -> String,
): PriceHistoryChartData {
    val truncated = rows.size == PRICE_HISTORY_MAX_LIMIT

    // 1. Dedupe aufeinanderfolgender Zeilen mit identischem priceTimestamp (erste Zeile behalten --
    //    die Cache-Plateau-Wiederholungen tragen keine zusaetzliche Information).
    val deduped = mutableListOf<PriceSnapshotDto>()
    for (row in rows) {
        val last = deduped.lastOrNull()
        if (last == null || last.priceTimestamp != row.priceTimestamp) {
            deduped.add(row)
        }
    }

    val refreshIntervalMs = AnchorPolicy.refreshIntervalSeconds(anchor) * 1_000L
    val gapThresholdMs = (maxOf(3_600_000L, refreshIntervalMs) * 2.5).toLong()

    val points = mutableListOf<PriceHistoryPoint?>()
    var gapCount = 0
    var previousEpochMillis: Long? = null
    for (row in deduped) {
        // TimeZone.UTC, NICHT currentSystemDefault() (Browser-Zone) -- siehe PriceHistoryPoint.epochMillis
        // KDoc. Nur fuer die relative Luecken-Distanz gebraucht, eine feste Zone reicht dafuer.
        val epochMillis = row.priceTimestamp.toInstant(TimeZone.UTC).toEpochMilliseconds()
        if (previousEpochMillis != null && epochMillis - previousEpochMillis > gapThresholdMs) {
            points.add(null)
            gapCount++
        }
        points.add(
            PriceHistoryPoint(
                epochMillis = epochMillis,
                yValue = row.medianPrice.toDouble(),
                tooltipLabel = formatTooltip(row.medianPrice),
                dateLabel = formatPriceTimestampLabel(row.priceTimestamp),
            ),
        )
        previousEpochMillis = epochMillis
    }

    return PriceHistoryChartData(
        points = points,
        gapCount = gapCount,
        truncated = truncated,
        pointCount = deduped.size,
    )
}

/**
 * `dd.MM.yyyy HH:mm` direkt aus den Komponenten von [timestamp] gebildet -- KEIN Umweg ueber
 * `toInstant(TimeZone...)`/`Clock`, siehe [PriceHistoryPoint.dateLabel] KDoc dafuer, warum das hier
 * zwingend ist. Gleiches manuelles `.padStart`-Idiom wie `ConferenceScreen.kt`'s
 * `conferenceRecordingStartedLabel`/`MemberAnniversariesScreen.kt`'s `formatDayMonth` (kein
 * `String.format`, JVM-only und im jsMain-Target nicht verfuegbar; kein `LocalDateTime.Format`, in
 * diesem Client bislang ungenutzt).
 */
internal fun formatPriceTimestampLabel(timestamp: LocalDateTime): String {
    val day = timestamp.day.toString().padStart(2, '0')
    val monthNumber = timestamp.month.number
    val month = monthNumber.toString().padStart(2, '0')
    val hour = timestamp.hour.toString().padStart(2, '0')
    val minute = timestamp.minute.toString().padStart(2, '0')
    return "$day.$month.${timestamp.year} $hour:$minute"
}
