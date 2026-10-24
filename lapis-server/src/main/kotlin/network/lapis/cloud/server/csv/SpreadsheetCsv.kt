package network.lapis.cloud.server.csv

/**
 * V1.9.204 -- the one CSV-field writer for files an operator opens in a spreadsheet (extracted from `MemberCsvImport`, which now delegates).
 *
 * A value whose FIRST character is in [FORMULA_TRIGGER_CHARS] gets a leading apostrophe and is ALWAYS quoted: merely quoting a formula value is
 * not enough, because Excel evaluates a quoted cell's leading `=`/`+`/`-`/`@` on import all the same (OWASP CSV injection). Everything else
 * is quoted only when it contains the separator, a quote or a line break.
 */
internal object SpreadsheetCsv {
    /** The classic trigger set; TAB and CR are included because Excel trims leading control characters before sniffing for a formula. */
    val FORMULA_TRIGGER_CHARS: CharArray = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /** The UTF-8 byte order mark that makes Excel read the file as UTF-8 (umlauts). */
    val UTF8_BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun field(
        value: String,
        separator: Char = ';',
    ): String {
        val needsFormulaGuard = value.isNotEmpty() && value[0] in FORMULA_TRIGGER_CHARS
        val guarded = if (needsFormulaGuard) "'$value" else value
        return if (needsFormulaGuard || guarded.any { it == separator || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + guarded.replace("\"", "\"\"") + "\""
        } else {
            guarded
        }
    }
}
