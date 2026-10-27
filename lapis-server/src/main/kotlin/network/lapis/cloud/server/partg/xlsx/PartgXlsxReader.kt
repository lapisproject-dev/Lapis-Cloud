package network.lapis.cloud.server.partg.xlsx

import network.lapis.cloud.shared.domain.AmountParse
import network.lapis.cloud.shared.domain.PARTG_MAX_AMOUNT_CENTS
import network.lapis.cloud.shared.domain.PartgAmountError
import network.lapis.cloud.shared.domain.PartgImportColumn
import network.lapis.cloud.shared.domain.PartgImportErrorCode
import network.lapis.cloud.shared.domain.PartgImportErrorDto
import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.parseGermanAmount
import java.math.BigDecimal
import java.math.RoundingMode
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/** The parsed content of a template file: all values in cents, the cells that came from a formula, and every error found (at most 50 + a marker). */
internal class PartgXlsxParse(
    val values: Map<Pair<PartgImportColumn, String>, Long>,
    val fromFormula: Set<Pair<PartgImportColumn, String>>,
    val errors: List<PartgImportErrorDto>,
    val recognizedRows: Int,
)

/**
 * V1.9.225 -- reads the first sheet of an `.xlsx` that was written from the template `partg-r1-1`, WITHOUT any spreadsheet library.
 *
 * Hardening: the container goes through [OoxmlZipReader] (entry count, sizes, ratio, names, overlap, bounded inflater); macro workbooks are
 * refused; XML is read with StAX (DTD off, no external entities, a DTD event rejects the file); at most 200,000 elements per file, 200
 * characters per cell text, 5,000 shared strings; only columns A-E and rows up to 500 are read, content below row 500 is an error (nothing is
 * silently cut off). Formulas are NEVER evaluated: a typed-in cell with a formula takes its cached `<v>`. The reader never throws; anything
 * unexpected becomes [PartgImportErrorCode.INCONSISTENT].
 */
internal object PartgXlsxReader {
    const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024
    const val MAX_ELEMENTS = 200_000
    const val MAX_CELL_TEXT = 200
    const val MAX_SHARED_STRINGS = 5_000
    const val MAX_ROW = 500
    const val MAX_ERRORS = 50
    private const val LAST_COLUMN = 5
    private const val TOLERANCE = "0.000001"

    private val factory: XMLInputFactory =
        XMLInputFactory.newDefaultFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
            runCatching { setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
            runCatching { setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
            for ((name, value) in listOf<Pair<String, Any>>(
                XMLConstants.FEATURE_SECURE_PROCESSING to true,
                "jdk.xml.entityExpansionLimit" to "1",
                "jdk.xml.maxElementDepth" to "64",
                "jdk.xml.elementAttributeLimit" to "64",
            )) {
                runCatching { setProperty(name, value) }
            }
        }

    private class Rejection(
        val code: PartgImportErrorCode,
    ) : RuntimeException(code.name)

    private fun fail(code: PartgImportErrorCode): Nothing = throw Rejection(code)

    fun parse(
        bytes: ByteArray,
        expectedUnit: PartgUnit,
        expectedYear: Int,
    ): PartgXlsxParse =
        try {
            parseOrThrow(bytes = bytes, expectedUnit = expectedUnit, expectedYear = expectedYear)
        } catch (e: Rejection) {
            failed(e.code)
        } catch (_: Exception) {
            failed(PartgImportErrorCode.INCONSISTENT)
        } catch (_: StackOverflowError) {
            failed(PartgImportErrorCode.INCONSISTENT)
        }

    private fun failed(code: PartgImportErrorCode) =
        PartgXlsxParse(values = emptyMap(), fromFormula = emptySet(), errors = listOf(PartgImportErrorDto(code = code)), recognizedRows = 0)

    // ---- container ----------------------------------------------------------------------------------------------------------

    private fun parseOrThrow(
        bytes: ByteArray,
        expectedUnit: PartgUnit,
        expectedYear: Int,
    ): PartgXlsxParse {
        if (bytes.size < 4 ||
            bytes[0] != 'P'.code.toByte() ||
            bytes[1] != 'K'.code.toByte() ||
            bytes[2].toInt() != 3 ||
            bytes[3].toInt() != 4
        ) {
            fail(PartgImportErrorCode.NOT_A_XLSX)
        }
        val entries =
            when (val zip = OoxmlZipReader.read(bytes)) {
                is OoxmlZipResult.Ok -> zip.entries
                is OoxmlZipResult.Rejected -> fail(zip.reason.code)
            }
        val contentTypes = entries["[Content_Types].xml"]
        if (entries.containsKey("xl/vbaProject.bin") ||
            contentTypes?.toString(Charsets.UTF_8)?.let {
                it.contains("macroEnabled", ignoreCase = true) ||
                    it.contains("vbaProject", ignoreCase = true)
            } ==
            true
        ) {
            fail(PartgImportErrorCode.MACROS_NOT_ALLOWED)
        }
        val workbook = entries["xl/workbook.xml"]
        if (contentTypes == null || workbook == null) fail(PartgImportErrorCode.NOT_A_XLSX)

        val firstSheetRid = readFirstSheetRelationshipId(workbook)
        val relationships = entries["xl/_rels/workbook.xml.rels"]?.let { readRelationships(it) }.orEmpty()
        val sheetTarget = relationships[firstSheetRid]?.let { resolveTarget(it.first) } ?: fail(PartgImportErrorCode.INCONSISTENT)
        if (!Regex("xl/worksheets/sheet[0-9]{1,3}\\.xml").matches(sheetTarget)) fail(PartgImportErrorCode.UNSUPPORTED_FILE)
        val sheetXml = entries[sheetTarget] ?: fail(PartgImportErrorCode.INCONSISTENT)
        val sharedPath =
            relationships.values.firstOrNull { it.second.endsWith("/sharedStrings") }?.let { resolveTarget(it.first) }
                ?: "xl/sharedStrings.xml"
        val stylesPath =
            relationships.values.firstOrNull { it.second.endsWith("/styles") }?.let { resolveTarget(it.first) } ?: "xl/styles.xml"
        val shared = entries[sharedPath]?.let { readSharedStrings(it) }.orEmpty()
        val dateStyles = entries[stylesPath]?.let { readDateStyles(it) }.orEmpty()
        val cells = readSheet(bytes = sheetXml, shared = shared, dateStyles = dateStyles)
        return interpret(cells = cells, expectedUnit = expectedUnit, expectedYear = expectedYear)
    }

    private fun resolveTarget(target: String): String {
        val raw = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
        val out = ArrayDeque<String>()
        raw.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast() else fail(PartgImportErrorCode.UNSUPPORTED_FILE)
                else -> out.addLast(part)
            }
        }
        return out.joinToString("/")
    }

    // ---- XML helpers --------------------------------------------------------------------------------------------------------

    private class Counter {
        var elements = 0

        fun tick() {
            elements += 1
            if (elements > MAX_ELEMENTS) fail(PartgImportErrorCode.TOO_LARGE)
        }
    }

    private inline fun <T> withReader(
        bytes: ByteArray,
        block: (XMLStreamReader) -> T,
    ): T {
        val reader = factory.createXMLStreamReader(bytes.inputStream())
        try {
            return block(reader)
        } catch (_: XMLStreamException) {
            fail(PartgImportErrorCode.INCONSISTENT)
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun XMLStreamReader.guard(counter: Counter) {
        when (eventType) {
            XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE, XMLStreamConstants.ENTITY_DECLARATION ->
                fail(PartgImportErrorCode.INCONSISTENT)
            XMLStreamConstants.START_ELEMENT -> counter.tick()
        }
    }

    private fun XMLStreamReader.attr(local: String): String? {
        for (i in 0 until attributeCount) if (getAttributeLocalName(i) == local) return getAttributeValue(i)
        return null
    }

    private fun readFirstSheetRelationshipId(workbook: ByteArray): String =
        withReader(bytes = workbook) { r ->
            val counter = Counter()
            var found: String? = null
            while (r.hasNext() && found == null) {
                r.next()
                r.guard(counter)
                if (r.eventType == XMLStreamConstants.START_ELEMENT && r.localName == "sheet") found = r.attr("id")
            }
            found ?: fail(PartgImportErrorCode.INCONSISTENT)
        }

    /** relationship id -> (target, type) */
    private fun readRelationships(bytes: ByteArray): Map<String, Pair<String, String>> =
        withReader(bytes = bytes) { r ->
            val counter = Counter()
            val out = LinkedHashMap<String, Pair<String, String>>()
            while (r.hasNext()) {
                r.next()
                r.guard(counter)
                if (r.eventType == XMLStreamConstants.START_ELEMENT && r.localName == "Relationship") {
                    val id = r.attr("Id")
                    val target = r.attr("Target")
                    if (id != null && target != null) out[id] = target to (r.attr("Type") ?: "")
                }
            }
            out
        }

    private fun readSharedStrings(bytes: ByteArray): List<String> =
        withReader(bytes = bytes) { r ->
            val counter = Counter()
            val out = ArrayList<String>()
            var inSi = false
            var inT = false
            var skip = 0
            val current = StringBuilder()
            while (r.hasNext()) {
                r.next()
                r.guard(counter)
                when (r.eventType) {
                    XMLStreamConstants.START_ELEMENT ->
                        when (r.localName) {
                            "si" -> {
                                inSi = true
                                current.setLength(0)
                            }
                            "rPh", "phoneticPr" -> skip += 1
                            "t" -> if (inSi && skip == 0) inT = true
                        }
                    XMLStreamConstants.CHARACTERS ->
                        if (inT) {
                            current.append(r.text)
                            if (current.length > MAX_CELL_TEXT) fail(PartgImportErrorCode.TOO_LARGE)
                        }
                    XMLStreamConstants.END_ELEMENT ->
                        when (r.localName) {
                            "t" -> inT = false
                            "rPh", "phoneticPr" -> if (skip > 0) skip -= 1
                            "si" -> {
                                inSi = false
                                out += current.toString()
                                if (out.size > MAX_SHARED_STRINGS) fail(PartgImportErrorCode.TOO_LARGE)
                            }
                        }
                }
            }
            out
        }

    /** The indexes of `cellXfs` whose number format is a date or time. */
    private fun readDateStyles(bytes: ByteArray): Set<Int> =
        withReader(bytes = bytes) { r ->
            val counter = Counter()
            val formats = HashMap<Int, String>()
            val xfNumFmt = ArrayList<Int>()
            var inCellXfs = false
            while (r.hasNext()) {
                r.next()
                r.guard(counter)
                when (r.eventType) {
                    XMLStreamConstants.START_ELEMENT ->
                        when (r.localName) {
                            "numFmt" -> {
                                val id = r.attr("numFmtId")?.toIntOrNull()
                                val code = r.attr("formatCode")
                                if (id != null && code != null) formats[id] = code
                            }
                            "cellXfs" -> inCellXfs = true
                            "xf" -> if (inCellXfs) xfNumFmt += r.attr("numFmtId")?.toIntOrNull() ?: 0
                        }
                    XMLStreamConstants.END_ELEMENT -> if (r.localName == "cellXfs") inCellXfs = false
                }
            }
            xfNumFmt
                .withIndex()
                .filter { (_, id) -> isDateFormat(id = id, code = formats[id]) }
                .map { it.index }
                .toSet()
        }

    private fun isDateFormat(
        id: Int,
        code: String?,
    ): Boolean {
        if (id in 14..22 || id in 45..47) return true
        if (code == null) return false
        val stripped = code.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^]]*]"), "").replace(Regex("\\\\."), "")
        return stripped.any { it.lowercaseChar() in "dmyh" }
    }

    // ---- sheet -----------------------------------------------------------------------------------------------------------------

    private enum class CellKind { TEXT, NUMBER, BOOL, ERROR }

    private class Cell(
        val row: Int,
        val column: Int,
        val kind: CellKind,
        val text: String,
        val formula: Boolean,
        val date: Boolean,
        val hasValue: Boolean,
    )

    private fun columnIndex(reference: String): Int {
        var n = 0
        for (c in reference) {
            if (c !in 'A'..'Z') break
            n = n * 26 + (c - 'A' + 1)
            if (n > 100_000) return -1
        }
        return n
    }

    /** Rows 1..[MAX_ROW], columns A-E. A cell with content below the limit is returned with row > MAX_ROW (the caller reports it). */
    private fun readSheet(
        bytes: ByteArray,
        shared: List<String>,
        dateStyles: Set<Int>,
    ): Map<Pair<Int, Int>, Cell> =
        withReader(bytes = bytes) { r ->
            val counter = Counter()
            val cells = LinkedHashMap<Pair<Int, Int>, Cell>()
            var rowNumber = 0
            var columnCounter = 0
            var inCell = false
            var cellRow = 0
            var cellCol = 0
            var type = "n"
            var style = 0
            var formula = false
            var inV = false
            var inT = false
            var inIs = false
            val v = StringBuilder()
            val t = StringBuilder()
            var hasContent = false
            while (r.hasNext()) {
                r.next()
                r.guard(counter)
                when (r.eventType) {
                    XMLStreamConstants.START_ELEMENT ->
                        when (r.localName) {
                            "row" -> {
                                rowNumber = r.attr("r")?.toIntOrNull() ?: (rowNumber + 1)
                                columnCounter = 0
                            }
                            "c" -> {
                                inCell = true
                                columnCounter += 1
                                val ref = r.attr("r")
                                cellRow = rowNumber
                                cellCol =
                                    if (ref != null) {
                                        val col = columnIndex(ref)
                                        val rowPart = ref.dropWhile { it in 'A'..'Z' }.toIntOrNull()
                                        if (rowPart != null) cellRow = rowPart
                                        col.also { if (it > 0) columnCounter = it }
                                    } else {
                                        columnCounter
                                    }
                                type = r.attr("t") ?: "n"
                                style = r.attr("s")?.toIntOrNull() ?: 0
                                formula = false
                                hasContent = false
                                v.setLength(0)
                                t.setLength(0)
                            }
                            "f" ->
                                if (inCell) {
                                    formula = true
                                    hasContent = true
                                }
                            "v" -> if (inCell) inV = true
                            "is" ->
                                if (inCell) {
                                    inIs = true
                                    hasContent = true
                                }
                            "t" -> if (inCell && inIs) inT = true
                        }
                    XMLStreamConstants.CHARACTERS ->
                        when {
                            inV -> {
                                v.append(r.text)
                                if (v.length > 400) fail(PartgImportErrorCode.TOO_LARGE)
                            }
                            inT -> {
                                t.append(r.text)
                                if (t.length > MAX_CELL_TEXT) fail(PartgImportErrorCode.TOO_LARGE)
                            }
                        }
                    XMLStreamConstants.END_ELEMENT ->
                        when (r.localName) {
                            "v" -> {
                                inV = false
                                if (v.isNotBlank()) hasContent = true
                            }
                            "t" -> inT = false
                            "is" -> inIs = false
                            "c" -> {
                                inCell = false
                                if (cellCol in 1..LAST_COLUMN && cellRow >= 1 && hasContent) {
                                    val cell =
                                        buildCell(
                                            row = cellRow,
                                            column = cellCol,
                                            type = type,
                                            style = style,
                                            formula = formula,
                                            v = v.toString(),
                                            inline = t.toString(),
                                            shared = shared,
                                            dateStyles = dateStyles,
                                        )
                                    if (cellRow <= MAX_ROW) cells[cellRow to cellCol] = cell else cells[cellRow to cellCol] = cell
                                }
                            }
                        }
                }
            }
            cells
        }

    private fun buildCell(
        row: Int,
        column: Int,
        type: String,
        style: Int,
        formula: Boolean,
        v: String,
        inline: String,
        shared: List<String>,
        dateStyles: Set<Int>,
    ): Cell {
        val value = v.trim()
        return when (type) {
            "s" -> {
                val index = value.toIntOrNull() ?: fail(PartgImportErrorCode.INCONSISTENT)
                if (index !in shared.indices) fail(PartgImportErrorCode.INCONSISTENT)
                Cell(
                    row = row,
                    column = column,
                    kind = CellKind.TEXT,
                    text = shared[index],
                    formula = formula,
                    date = false,
                    hasValue = true,
                )
            }
            "inlineStr" ->
                Cell(
                    row = row,
                    column = column,
                    kind = CellKind.TEXT,
                    text = inline,
                    formula = formula,
                    date = false,
                    hasValue = true,
                )
            "str" ->
                Cell(
                    row = row,
                    column = column,
                    kind = CellKind.TEXT,
                    text = v,
                    formula = formula,
                    date = false,
                    hasValue = v.isNotEmpty(),
                )
            "b" -> Cell(row = row, column = column, kind = CellKind.BOOL, text = value, formula = formula, date = false, hasValue = true)
            "e" -> Cell(row = row, column = column, kind = CellKind.ERROR, text = value, formula = formula, date = false, hasValue = true)
            "d" -> Cell(row = row, column = column, kind = CellKind.NUMBER, text = value, formula = formula, date = true, hasValue = true)
            else ->
                Cell(
                    row = row,
                    column = column,
                    kind = CellKind.NUMBER,
                    text = value,
                    formula = formula,
                    date = style in dateStyles,
                    hasValue = value.isNotEmpty(),
                )
        }
    }

    // ---- interpretation ---------------------------------------------------------------------------------------------------

    private fun interpret(
        cells: Map<Pair<Int, Int>, Cell>,
        expectedUnit: PartgUnit,
        expectedYear: Int,
    ): PartgXlsxParse {
        val errors = mutableListOf<PartgImportErrorDto>()
        var stop = false

        fun error(
            row: Int?,
            column: String?,
            code: PartgImportErrorCode,
            detail: String? = null,
        ) {
            if (stop) return
            if (errors.size >= MAX_ERRORS) {
                errors += PartgImportErrorDto(code = PartgImportErrorCode.TOO_MANY_ERRORS)
                stop = true
                return
            }
            errors += PartgImportErrorDto(row = row, column = column, code = code, detail = detail?.take(64))
        }

        fun cell(
            row: Int,
            col: Int,
        ) = cells[row to col]

        // metadata: version, target unit, year
        if (cell(PartgXlsxLayout.ROW_VERSION, 2)?.text?.trim() != PartgXlsxLayout.TEMPLATE_VERSION ||
            cell(PartgXlsxLayout.ROW_HEADER, 1)?.text?.trim() != "Kennung"
        ) {
            return PartgXlsxParse(
                values = emptyMap(),
                fromFormula = emptySet(),
                errors = listOf(PartgImportErrorDto(code = PartgImportErrorCode.TEMPLATE_VERSION)),
                recognizedRows = 0,
            )
        }
        val fileUnit = cell(PartgXlsxLayout.ROW_UNIT_KEY, 2)?.text?.trim()
        val fileYear = cell(PartgXlsxLayout.ROW_YEAR, 2)?.let { yearOf(it) }
        val unitMatches = fileUnit == expectedUnit.key()
        if (!unitMatches || fileYear != expectedYear) {
            val shownUnit = fileUnit?.takeIf { PartgUnit.parse(it) != null } ?: "?"
            return PartgXlsxParse(
                values = emptyMap(),
                fromFormula = emptySet(),
                errors =
                    listOf(
                        PartgImportErrorDto(
                            row = PartgXlsxLayout.ROW_UNIT_KEY,
                            code = PartgImportErrorCode.TARGET_MISMATCH,
                            detail = "$shownUnit ${fileYear ?: "?"}",
                        ),
                    ),
                recognizedRows = 0,
            )
        }

        val values = LinkedHashMap<Pair<PartgImportColumn, String>, Long>()
        val formulas = LinkedHashSet<Pair<PartgImportColumn, String>>()
        val seen = HashSet<String>()
        var recognized = 0
        val rows = cells.keys.map { it.first }.toSortedSet()
        for (row in rows) {
            if (stop) break
            if (row > MAX_ROW) {
                error(row, null, PartgImportErrorCode.ROW_LIMIT)
                break
            }
            if (row < PartgXlsxLayout.FIRST_POSITION_ROW) continue
            val codeCell = cell(row, 1)
            val code = codeCell?.text?.trim().orEmpty()
            val valueCells =
                listOf(PartgImportColumn.CURRENT to cell(row, 4), PartgImportColumn.PRIOR to cell(row, 5)).filter {
                    it.second !=
                        null
                }
            if (code.isEmpty()) {
                if (valueCells.isNotEmpty()) error(row, "A", PartgImportErrorCode.UNKNOWN_CODE)
                continue
            }
            val position = PartgPositions.find(code)
            if (position == null) {
                error(row, "A", PartgImportErrorCode.UNKNOWN_CODE, code.takeIf { Regex("[A-Za-z0-9-]{1,16}").matches(it) })
                continue
            }
            if (!seen.add(code)) {
                error(row, "A", PartgImportErrorCode.DUPLICATE_CODE, code)
                continue
            }
            recognized += 1
            if (position.kind == PartgKind.SUM) continue
            for ((column, valueCell) in valueCells) {
                val letter = if (column == PartgImportColumn.CURRENT) "D" else "E"
                val cents = centsOf(cell = valueCell!!) { c -> error(row, letter, c, code) } ?: continue
                values[column to code] = cents
                if (valueCell.formula) formulas += column to code
            }
        }
        return PartgXlsxParse(values = values, fromFormula = formulas, errors = errors, recognizedRows = recognized)
    }

    private fun yearOf(cell: Cell): Int? =
        when (cell.kind) {
            CellKind.NUMBER -> runCatching { BigDecimal(cell.text).intValueExact() }.getOrNull()
            CellKind.TEXT -> cell.text.trim().toIntOrNull()
            else -> null
        }

    /** The cents of one value cell, or `null` after [report]ing the reason (also `null` for a blank cell: "do not change"). */
    private inline fun centsOf(
        cell: Cell,
        report: (PartgImportErrorCode) -> Unit,
    ): Long? {
        if (cell.date) {
            report(PartgImportErrorCode.DATE_VALUE)
            return null
        }
        return when (cell.kind) {
            CellKind.BOOL, CellKind.ERROR -> {
                report(PartgImportErrorCode.CELL_ERROR)
                null
            }
            CellKind.TEXT -> {
                if (cell.text.isBlank()) {
                    if (cell.formula) report(PartgImportErrorCode.FORMULA_WITHOUT_RESULT)
                    null
                } else {
                    when (val parsed = parseGermanAmount(cell.text)) {
                        is AmountParse.Ok -> parsed.cents
                        is AmountParse.Error -> {
                            report(
                                when (parsed.reason) {
                                    PartgAmountError.NEGATIVE -> PartgImportErrorCode.NEGATIVE_VALUE
                                    PartgAmountError.TOO_MANY_DECIMALS -> PartgImportErrorCode.TOO_MANY_DECIMALS
                                    PartgAmountError.OVERFLOW -> PartgImportErrorCode.VALUE_TOO_LARGE
                                    else -> PartgImportErrorCode.NOT_A_NUMBER
                                },
                            )
                            null
                        }
                    }
                }
            }
            CellKind.NUMBER -> {
                if (cell.text.isBlank()) {
                    if (cell.formula) report(PartgImportErrorCode.FORMULA_WITHOUT_RESULT)
                    null
                } else {
                    numberToCents(raw = cell.text, report = report)
                }
            }
        }
    }

    /** Excel stores IEEE doubles (`1234.5599999999999`): round to 2 decimals when the deviation is below 1e-6, else refuse. */
    private inline fun numberToCents(
        raw: String,
        report: (PartgImportErrorCode) -> Unit,
    ): Long? {
        val text = raw.trim()
        val decimal =
            if (text.length > 40) {
                null
            } else {
                runCatching { BigDecimal(text) }.getOrNull()
            }
        if (decimal == null) {
            report(PartgImportErrorCode.NOT_A_NUMBER)
            return null
        }
        // DoS guard: refuse extreme exponents BEFORE any arithmetic (setScale on 1E-100000000 would compute 10^1e8).
        if (decimal.scale() < -MAX_ABS_SCALE) {
            report(PartgImportErrorCode.VALUE_TOO_LARGE)
            return null
        }
        if (decimal.scale() > MAX_ABS_SCALE) {
            report(PartgImportErrorCode.NOT_A_NUMBER)
            return null
        }
        if (decimal.precision() - decimal.scale() > 15) {
            report(PartgImportErrorCode.VALUE_TOO_LARGE)
            return null
        }
        val rounded = decimal.setScale(2, RoundingMode.HALF_UP)
        if ((decimal - rounded).abs() >= BigDecimal(TOLERANCE)) {
            report(PartgImportErrorCode.TOO_MANY_DECIMALS)
            return null
        }
        if (rounded.signum() < 0) {
            report(PartgImportErrorCode.NEGATIVE_VALUE)
            return null
        }
        val cents = rounded.movePointRight(2).toBigIntegerExact()
        if (cents > java.math.BigInteger.valueOf(PARTG_MAX_AMOUNT_CENTS)) {
            report(PartgImportErrorCode.VALUE_TOO_LARGE)
            return null
        }
        return cents.toLong()
    }

    private const val MAX_ABS_SCALE = 60

    @Suppress("unused")
    private fun unitOf(raw: String): PartgUnit? = PartgUnit.parse(raw)
}
