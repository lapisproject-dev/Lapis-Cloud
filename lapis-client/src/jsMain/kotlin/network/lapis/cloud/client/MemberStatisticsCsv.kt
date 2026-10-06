package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.shared.domain.MemberCountGranularity
import network.lapis.cloud.shared.domain.MemberCountHistoryDto
import network.lapis.cloud.shared.domain.MemberStatus
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

/*
 * Welle V1.9.59 -- the CSV export of the member-count table. Built in the browser from the figures already on the screen (the server
 * has nothing more to give: it only ever returns counts per period). Semicolon-separated and UTF-8 with a byte order mark, the form
 * German spreadsheet programs open correctly. Only static labels and numbers go in -- no free text, so nothing to neutralise.
 */

/** The byte order mark that makes Excel read the file as UTF-8. */
internal const val CSV_BOM = "﻿"

/** The labels the CSV needs, already translated (kept out of the pure function so it stays testable without the I18n runtime). */
internal class StatisticsCsvLabels(
    val period: String,
    val start: String,
    val endExclusive: String,
    val dataBasis: String,
    val recorded: String,
    val reconstructed: String,
    val running: String,
    val status: (MemberStatus) -> String,
)

/** The CSV text of [dto]: a header row, then one row per period, columns as in the table on the screen. */
internal fun memberCountCsv(
    dto: MemberCountHistoryDto,
    labels: StatisticsCsvLabels,
): String {
    val header =
        listOf(labels.period, labels.start, labels.endExclusive) +
            STATISTICS_STATUS_ORDER.map { labels.status(it) } +
            labels.dataBasis
    val rows =
        dto.points.map { point ->
            val basis =
                when {
                    point.current -> labels.running
                    point.reconstructed -> labels.reconstructed
                    else -> labels.recorded
                }
            listOf(
                statisticsPeriodLabel(point.periodStart, dto.granularity, point.current, labels.running),
                isoDay(point.periodStart),
                isoDay(point.periodEnd),
            ) + STATISTICS_STATUS_ORDER.map { (point.counts[it] ?: 0).toString() } + basis
        }
    return CSV_BOM + (listOf(header) + rows).joinToString("\r\n") { cells -> cells.joinToString(";") { csvCell(it) } } + "\r\n"
}

/** A cell, quoted when it contains the separator, a quote or a line break. */
internal fun csvCell(value: String): String =
    if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value

/** `mitgliederentwicklung_{von}_{bis}_{einteilung}.csv` -- the requested range, so two exports of one screen state never differ in name. */
internal fun memberCountCsvFileName(
    from: String,
    to: String,
    granularity: MemberCountGranularity,
): String = "mitgliederentwicklung_${from}_${to}_${statisticsGranularitySlug(granularity)}.csv"

/** Offers [content] as a file download (a Blob behind an anchor click, the object URL is released right after). */
internal fun downloadCsvFile(
    fileName: String,
    content: String,
) {
    val blob = Blob(arrayOf(content), BlobPropertyBag(type = "text/csv;charset=utf-8"))
    val url = URL.createObjectURL(blob)
    val anchor = document.createElement("a") as HTMLAnchorElement
    anchor.href = url
    anchor.download = fileName
    anchor.style.display = "none"
    document.body?.appendChild(anchor)
    anchor.click()
    document.body?.removeChild(anchor)
    URL.revokeObjectURL(url)
}
