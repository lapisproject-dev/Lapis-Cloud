package network.lapis.cloud.server.partg.report

import network.lapis.cloud.server.partg.PartgVersionKeys
import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgConsolidatedCell
import network.lapis.cloud.shared.domain.PartgDiffCellDto
import network.lapis.cloud.shared.domain.PartgDiffMarker
import network.lapis.cloud.shared.domain.PartgDiffRowDto
import network.lapis.cloud.shared.domain.PartgDiffValueDto
import network.lapis.cloud.shared.domain.PartgDiffValueState
import network.lapis.cloud.shared.domain.PartgVersionDiffDto

/**
 * V1.9.291 (R6) -- the comparison of two kept versions. PURE: two documents in, one DTO out; no database, no clock.
 *
 * Rows are matched by the stable `unitKey` (a unit renamed in between stays the same row; the label comes from the newer version). The cells
 * compared are the report-year cells (`current`) of the 42 positions per unit, the sum of the units (`SUM`) and the consolidated column
 * (`CONSOLIDATED`). A difference is only a number when both sides are plain values; a partial, a not-entered or a not-computable side gives
 * `null` ("n. b."), never a guessed 0. [PartgVersionDiffDto.rows] holds, per row, only the cells that are not `SAME`.
 */
internal object PartgVersionDiff {
    const val DEFAULT_MAX_ROWS = 10_000

    private const val KIND_UNIT = "UNIT"
    private const val KIND_SUM = "SUM"
    private const val KIND_CONSOLIDATED = "CONSOLIDATED"

    private class RowData(
        val kind: String,
        val key: String,
        val label: String,
        val cells: Map<String, PartgDiffValueDto>,
    )

    @Suppress("LongParameterList")
    fun compute(
        a: PartgReportDocument,
        b: PartgReportDocument,
        versionIdA: String,
        versionIdB: String,
        versionNumberA: Int,
        versionNumberB: Int,
        maxRows: Int = DEFAULT_MAX_ROWS,
    ): PartgVersionDiffDto {
        val rowsA = rowsOf(a)
        val rowsB = rowsOf(b)
        val byKeyA = rowsA.associateBy { it.kind + "|" + it.key }
        val byKeyB = rowsB.associateBy { it.kind + "|" + it.key }
        val ordered = rowsB.map { it.kind + "|" + it.key } + rowsA.map { it.kind + "|" + it.key }.filter { it !in byKeyB }
        val out = ArrayList<PartgDiffRowDto>()
        var truncated = false
        for (rowKey in ordered) {
            if (out.size >= maxRows) {
                truncated = true
                break
            }
            out += diffRow(before = byKeyA[rowKey], after = byKeyB[rowKey])
        }
        return PartgVersionDiffDto(
            versionIdA = versionIdA,
            versionIdB = versionIdB,
            versionNumberA = versionNumberA,
            versionNumberB = versionNumberB,
            year = b.year,
            rows = out,
            truncated = truncated,
            comparable = a.year == b.year && a.scope.key() == b.scope.key(),
        )
    }

    private fun diffRow(
        before: RowData?,
        after: RowData?,
    ): PartgDiffRowDto {
        val template = after ?: before!!
        val marker =
            when {
                before == null -> PartgDiffMarker.ADDED
                after == null -> PartgDiffMarker.REMOVED
                else -> PartgDiffMarker.SAME
            }
        val codes = (after?.cells?.keys.orEmpty() + before?.cells?.keys.orEmpty()).toCollection(LinkedHashSet())
        val cells = ArrayList<PartgDiffCellDto>()
        for (code in codes) {
            val prev = before?.cells?.get(code) ?: ABSENT
            val next = after?.cells?.get(code) ?: ABSENT
            val cellMarker =
                when {
                    prev == next -> PartgDiffMarker.SAME
                    prev.state == PartgDiffValueState.ABSENT -> PartgDiffMarker.ADDED
                    next.state == PartgDiffValueState.ABSENT -> PartgDiffMarker.REMOVED
                    else -> PartgDiffMarker.CHANGED
                }
            if (cellMarker == PartgDiffMarker.SAME) continue
            // an added / removed row lists only what carries a figure: a not-entered cell is no news
            if (marker == PartgDiffMarker.ADDED && next.state == PartgDiffValueState.NOT_ENTERED) continue
            if (marker == PartgDiffMarker.REMOVED && prev.state == PartgDiffValueState.NOT_ENTERED) continue
            cells +=
                PartgDiffCellDto(
                    code = code,
                    before = prev,
                    after = next,
                    deltaCents = delta(before = prev, after = next),
                    marker = cellMarker,
                )
        }
        val rowMarker = if (marker == PartgDiffMarker.SAME && cells.isNotEmpty()) PartgDiffMarker.CHANGED else marker
        return PartgDiffRowDto(rowKind = template.kind, rowKey = template.key, label = template.label, marker = rowMarker, cells = cells)
    }

    private fun delta(
        before: PartgDiffValueDto,
        after: PartgDiffValueDto,
    ): Long? =
        if (before.state == PartgDiffValueState.VALUE && after.state == PartgDiffValueState.VALUE) {
            try {
                Math.subtractExact(after.cents!!, before.cents!!)
            } catch (e: ArithmeticException) {
                null
            }
        } else {
            null
        }

    private val ABSENT = PartgDiffValueDto(state = PartgDiffValueState.ABSENT)

    private fun rowsOf(doc: PartgReportDocument): List<RowData> {
        val rows = ArrayList<RowData>()
        doc.units.forEach { rows += RowData(kind = KIND_UNIT, key = it.unitKey, label = it.label, cells = cellsOf(it.current)) }
        doc.unitSum?.let {
            rows +=
                RowData(kind = KIND_SUM, key = PartgVersionKeys.SUM_ROW_KEY, label = it.label, cells = cellsOf(it.current))
        }
        doc.consolidation?.let { section ->
            rows +=
                RowData(
                    kind = KIND_CONSOLIDATED,
                    key = PartgVersionKeys.CONSOLIDATED_ROW_KEY,
                    label = PartgReportTexts.SHEET_CONSOLIDATION,
                    cells = section.lines.associate { it.code to consolidatedValue(it.consolidated) },
                )
        }
        return rows
    }

    private fun cellsOf(cells: Map<String, PartgCell>): Map<String, PartgDiffValueDto> =
        cells.mapValues { (_, cell) ->
            when (cell) {
                is PartgCell.Value -> PartgDiffValueDto(state = PartgDiffValueState.VALUE, cents = cell.cents)
                is PartgCell.Partial -> PartgDiffValueDto(state = PartgDiffValueState.PARTIAL, cents = cell.cents)
                is PartgCell.NotEntered -> PartgDiffValueDto(state = PartgDiffValueState.NOT_ENTERED)
            }
        }

    private fun consolidatedValue(cell: PartgConsolidatedCell): PartgDiffValueDto =
        when (cell) {
            is PartgConsolidatedCell.Value -> PartgDiffValueDto(state = PartgDiffValueState.VALUE, cents = cell.cents)
            is PartgConsolidatedCell.NotComputable -> PartgDiffValueDto(state = PartgDiffValueState.NOT_COMPUTABLE)
        }
}
