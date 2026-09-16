package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Covers every pure, DOM-independent function in `FileDisplay.kt` -- same scope posture as
 * [TravelExpenseLabelsTest] (no rendering harness exists in this module).
 */
class FileDisplayTest {
    // --- formatFileSize ---------------------------------------------------------------------

    @Test
    fun formatFileSize_zeroBytes() {
        assertEquals("0 B", formatFileSize(0))
    }

    @Test
    fun formatFileSize_negativeIsClampedToZero() {
        assertEquals("0 B", formatFileSize(-5))
    }

    @Test
    fun formatFileSize_bytesJustBelowKbBoundary() {
        assertEquals("1023 B", formatFileSize(1023))
    }

    @Test
    fun formatFileSize_exactlyOneKb() {
        assertEquals("1 KB", formatFileSize(1024))
    }

    @Test
    fun formatFileSize_kbTruncatesInsteadOfRounding() {
        // 1024*1024 - 1 bytes = 1023.999... KB -- must truncate to 1023 KB, never round to 1024 KB.
        assertEquals("1023 KB", formatFileSize(1024 * 1024 - 1))
    }

    @Test
    fun formatFileSize_exactlyOneMb() {
        assertEquals("1,0 MB", formatFileSize(1024L * 1024L))
    }

    @Test
    fun formatFileSize_mbValueTruncatedToOneDecimal() {
        // 1_500_000 / (1024*1024) = 1.430511... -- truncated (never rounded) to 1,4 MB.
        assertEquals("1,4 MB", formatFileSize(1_500_000))
    }

    @Test
    fun formatFileSize_mbJustBelowGbBoundaryTruncatesNotRounds() {
        // 1024^3 - 1 bytes = 1023.99999... MB -- must truncate to 1023,9 MB, never round to 1024,0 MB.
        assertEquals("1023,9 MB", formatFileSize(1024L * 1024L * 1024L - 1))
    }

    @Test
    fun formatFileSize_exactlyOneGb() {
        assertEquals("1,0 GB", formatFileSize(1024L * 1024L * 1024L))
    }

    @Test
    fun formatFileSize_truncatesRatherThanRoundsNearPointNineFiveBoundary() {
        // 1_048_064 bytes = 1.0234375 MB, i.e. 1,0234... -- naive rounding to one decimal would
        // still give 1,0, so pick a value close to a .x5 boundary that rounding would push up a
        // full tenth: 1_468_006 bytes / (1024*1024) = 1.39999... -- truncation gives 1,3 MB, while
        // naive rounding (round-half-up on the second decimal) would give 1,4 MB.
        assertEquals("1,3 MB", formatFileSize(1_468_006))
    }

    // --- fileTypeIcon -------------------------------------------------------------------------

    @Test
    fun fileTypeIcon_pdf() {
        assertEquals("fas fa-file-pdf", fileTypeIcon("application/pdf"))
    }

    @Test
    fun fileTypeIcon_wordVariants() {
        assertEquals("fas fa-file-word", fileTypeIcon("application/msword"))
        assertEquals(
            "fas fa-file-word",
            fileTypeIcon("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        )
        assertEquals("fas fa-file-word", fileTypeIcon("application/vnd.oasis.opendocument.text"))
    }

    @Test
    fun fileTypeIcon_excelVariants() {
        assertEquals("fas fa-file-excel", fileTypeIcon("application/vnd.ms-excel"))
        assertEquals(
            "fas fa-file-excel",
            fileTypeIcon("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        )
        assertEquals("fas fa-file-excel", fileTypeIcon("application/vnd.oasis.opendocument.spreadsheet"))
    }

    @Test
    fun fileTypeIcon_csv() {
        assertEquals("fas fa-file-csv", fileTypeIcon("text/csv"))
    }

    @Test
    fun fileTypeIcon_powerPointVariants() {
        assertEquals("fas fa-file-powerpoint", fileTypeIcon("application/vnd.ms-powerpoint"))
        assertEquals(
            "fas fa-file-powerpoint",
            fileTypeIcon("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        )
        assertEquals("fas fa-file-powerpoint", fileTypeIcon("application/vnd.oasis.opendocument.presentation"))
    }

    @Test
    fun fileTypeIcon_image() {
        assertEquals("fas fa-file-image", fileTypeIcon("image/png"))
        assertEquals("fas fa-file-image", fileTypeIcon("image/jpeg"))
    }

    @Test
    fun fileTypeIcon_archiveVariants() {
        assertEquals("fas fa-file-zipper", fileTypeIcon("application/zip"))
        assertEquals("fas fa-file-zipper", fileTypeIcon("application/x-7z-compressed"))
        assertEquals("fas fa-file-zipper", fileTypeIcon("application/x-tar"))
        assertEquals("fas fa-file-zipper", fileTypeIcon("application/gzip"))
        assertEquals("fas fa-file-zipper", fileTypeIcon("application/vnd.rar"))
    }

    @Test
    fun fileTypeIcon_genericTextFallsBackToGenericFileIcon() {
        assertEquals("fas fa-file", fileTypeIcon("text/plain"))
    }

    @Test
    fun fileTypeIcon_unknownMimeTypeFallsBackToGenericFileIcon() {
        assertEquals("fas fa-file", fileTypeIcon("application/x-completely-unknown"))
    }

    @Test
    fun fileTypeIcon_isCaseInsensitive() {
        assertEquals("fas fa-file-pdf", fileTypeIcon("APPLICATION/PDF"))
    }

    @Test
    fun fileTypeIcon_ignoresCharsetParameter() {
        assertEquals("fas fa-file", fileTypeIcon("text/plain; charset=utf-8"))
    }

    // Kollisionsschutz: "fas fa-file-lines" gehört der Sidebar-Navigation (Sidebar.kt:347,
    // Eintrag "Dokumente") -- fileTypeIcon darf diesen Wert nie zurückgeben.
    @Test
    fun fileTypeIcon_neverReturnsTheSidebarNavigationIcon() {
        val candidates =
            listOf(
                "application/pdf",
                "application/msword",
                "application/vnd.ms-excel",
                "text/csv",
                "application/vnd.ms-powerpoint",
                "image/png",
                "application/zip",
                "text/plain",
                "application/x-completely-unknown",
            )
        candidates.forEach { mimeType ->
            assertNotEquals("fas fa-file-lines", fileTypeIcon(mimeType))
        }
    }
}
