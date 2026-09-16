package network.lapis.cloud.client

// Welle "Documents Screen -- Icons + menschenlesbare Dateigrößen" -- Design-Team-Review-
// Zusammenfassung (die vier Jobs-Rulings):
// - [formatFileSize] als gemeinsame Norm mit Abschneiden-statt-Runden-Verhalten (Tesler vs. Ive):
//   nie fälschlich über eine Einheitengrenze aufrunden (z. B. "1024,0 MB" statt "1,0 GB").
// - [fileTypeIcon] nur für Versionszeilen, weil `DocumentDto` (anders als `DocumentVersionDto`)
//   keinen `mimeType` trägt (Norman).
// - Fallback-Icon "fas fa-file" -- bewusst NICHT "fas fa-file-lines", das gehört der
//   Sidebar-Navigation (`Sidebar.kt:347`, Eintrag "Dokumente") (Duarte).
// - `receiptSizeLabel`/`receiptIcon` (`TravelExpenseLabels.kt`) und
//   `conferenceRecordingFileSizeLabel` werden in dieser Welle bewusst NICHT hierher migriert --
//   eigener Rundungs-/Warnhinweis-Vertrag, kein Blocker für diese Welle.

/**
 * Menschenlesbare Dateigröße, deutsches Dezimalkomma. Abgeschnitten (nie gerundet), damit z. B.
 * 1023,95 KB nie als "1024,0 KB" über die Einheitengrenze rutscht.
 * < 1024 B -> "N B" (negative Werte auf 0 geklemmt)
 * < 1024 KB -> ganzzahlig "N KB"
 * < 1024 MB -> eine Nachkommastelle "N,N MB"
 * sonst -> eine Nachkommastelle "N,N GB"
 */
fun formatFileSize(bytes: Long): String {
    val clamped = bytes.coerceAtLeast(0L)
    return when {
        clamped < 1024L -> "$clamped B"
        clamped < 1024L * 1024L -> "${clamped / 1024L} KB"
        clamped < 1024L * 1024L * 1024L -> "${truncateOneDecimal(clamped, 1024.0 * 1024.0)} MB"
        else -> "${truncateOneDecimal(clamped, 1024.0 * 1024.0 * 1024.0)} GB"
    }
}

private fun truncateOneDecimal(
    bytes: Long,
    divisor: Double,
): String {
    val tenths = (bytes / divisor * 10).toLong() // Abschneiden via Long-Konversion, nie Runden
    val whole = tenths / 10
    val fraction = tenths % 10
    return "$whole,$fraction"
}

/**
 * MIME-Type -> Font-Awesome-Icon, nur für [network.lapis.cloud.shared.domain.DocumentVersionDto
 * .mimeType] (nicht für `DocumentDto`, das keinen Dateityp kennt -- siehe Norman-Ruling im
 * Design-Review). Case-insensitiv, Charset-Parameter nach ';' wird ignoriert. Fallback +
 * generischer Text-Typ: "fas fa-file" -- bewusst NICHT "fas fa-file-lines" (das gehört der
 * Sidebar-Navigation, Sidebar.kt:347).
 */
fun fileTypeIcon(mimeType: String): String {
    val normalized = mimeType.substringBefore(';').trim().lowercase()
    return when {
        normalized == "application/pdf" -> "fas fa-file-pdf"
        normalized in WORD_MIME_TYPES -> "fas fa-file-word"
        normalized in EXCEL_MIME_TYPES -> "fas fa-file-excel"
        normalized == "text/csv" -> "fas fa-file-csv"
        normalized in POWERPOINT_MIME_TYPES -> "fas fa-file-powerpoint"
        normalized.startsWith("image/") -> "fas fa-file-image"
        normalized in ARCHIVE_MIME_TYPES -> "fas fa-file-zipper"
        normalized.startsWith("text/") -> "fas fa-file"
        else -> "fas fa-file"
    }
}

private val WORD_MIME_TYPES =
    setOf(
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.oasis.opendocument.text",
    )
private val EXCEL_MIME_TYPES =
    setOf(
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.oasis.opendocument.spreadsheet",
    )
private val POWERPOINT_MIME_TYPES =
    setOf(
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.presentation",
    )
private val ARCHIVE_MIME_TYPES =
    setOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/x-7z-compressed",
        "application/x-tar",
        "application/gzip",
        "application/vnd.rar",
    )
