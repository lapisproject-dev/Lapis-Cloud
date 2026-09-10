package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.BankCsvDialect
import network.lapis.cloud.shared.domain.BankStatementFormat
import network.lapis.cloud.shared.domain.BankStatementImportRejectionDto
import network.lapis.cloud.shared.domain.BankStatementImportWarningCode
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.BankStatementRejectionCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Welle V1.4.5.1.1 -- pure label/color-table coverage, same posture as `DunningLabels.kt`'s own
 * (unnamed) callers: every enum literal has a non-empty, pairwise-distinct label, and the
 * [bankStatementLineStatusColor] "grau ist unerledigt, nicht kaputt" rule (Design-Team, Jobs) is
 * pinned so a later re-color cannot silently turn [BankStatementLineStatus.UNMATCHED] red.
 */
class BankStatementLabelsTest {
    @Test
    fun bankStatementLineStatusLabel_allEntries_areNonEmptyAndPairwiseDistinct() {
        val labels = BankStatementLineStatus.entries.map { bankStatementLineStatusLabel(it) }
        labels.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun bankStatementLineStatusColor_unmatchedIsSecondary_neverDanger() {
        assertEquals("secondary", bankStatementLineStatusColor(BankStatementLineStatus.UNMATCHED))
    }

    @Test
    fun bankStatementLineStatusColor_postedIsSuccess() {
        assertEquals("success", bankStatementLineStatusColor(BankStatementLineStatus.POSTED))
    }

    @Test
    fun bankStatementLineStatusColor_ambiguousIsWarning() {
        assertEquals("warning", bankStatementLineStatusColor(BankStatementLineStatus.AMBIGUOUS))
    }

    @Test
    fun bankStatementLineStatusColor_noEntryIsDanger() {
        BankStatementLineStatus.entries.forEach { status ->
            assertNotEquals("danger", bankStatementLineStatusColor(status))
        }
    }

    @Test
    fun bankStatementFormatLabel_allEntries_areNonEmptyAndPairwiseDistinct() {
        val labels = BankStatementFormat.entries.map { bankStatementFormatLabel(it) }
        labels.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun bankCsvDialectLabel_allEntries_areNonEmptyAndPairwiseDistinct() {
        val labels = BankCsvDialect.entries.map { bankCsvDialectLabel(it) }
        labels.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun bankStatementRejectionMessage_allTenCodes_areNonEmptyAndPairwiseDistinct() {
        val messages =
            BankStatementRejectionCode.entries.map { code ->
                bankStatementRejectionMessage(BankStatementImportRejectionDto(code = code))
            }
        messages.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(messages.size, messages.toSet().size)
    }

    @Test
    fun bankStatementRejectionMessage_formatUnrecognized_withAndWithoutHeaderFields_differ() {
        val without = bankStatementRejectionMessage(BankStatementImportRejectionDto(code = BankStatementRejectionCode.FORMAT_UNRECOGNIZED))
        val with =
            bankStatementRejectionMessage(
                BankStatementImportRejectionDto(
                    code = BankStatementRejectionCode.FORMAT_UNRECOGNIZED,
                    observedHeaderFields = listOf("A", "B"),
                ),
            )
        assertTrue(without != with)
        assertTrue(with.contains("A, B"))
    }

    @Test
    fun bankStatementRejectionMessage_parseFailed_withLineNumber_containsIt() {
        val message =
            bankStatementRejectionMessage(BankStatementImportRejectionDto(code = BankStatementRejectionCode.PARSE_FAILED, lineNumber = 47))
        assertTrue(message.contains("47"))
    }

    @Test
    fun bankStatementRejectionMessage_tooManyLines_containsTheLimit() {
        val message = bankStatementRejectionMessage(BankStatementImportRejectionDto(code = BankStatementRejectionCode.TOO_MANY_LINES))
        assertTrue(message.contains("2000"))
    }

    @Test
    fun bankStatementRejectionMessage_fileTooLarge_containsTheSizeLimit() {
        val message = bankStatementRejectionMessage(BankStatementImportRejectionDto(code = BankStatementRejectionCode.FILE_TOO_LARGE))
        assertTrue(message.contains("5 MB"))
    }

    // Review fix (MAJOR, Welle V1.4.5.1.1 Runde 2): bankStatementImportWarningMessage regression
    // coverage, same shape as bankStatementRejectionMessage's own exhaustiveness test above.
    @Test
    fun bankStatementImportWarningMessage_allThreeCodes_areNonEmptyAndPairwiseDistinct() {
        val messages = BankStatementImportWarningCode.entries.map { code -> bankStatementImportWarningMessage(code) }
        messages.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(messages.size, messages.toSet().size)
    }

    @Test
    fun bankStatementImportWarningMessage_ibanMatchingUnavailable_neverNamesTheEnvVar() {
        // Review fix regression: the raw server warning this code replaces used to name
        // LAPIS_SECRET_ENCRYPTION_KEY directly in the UI -- an internal operability detail.
        val message = bankStatementImportWarningMessage(BankStatementImportWarningCode.IBAN_MATCHING_UNAVAILABLE)
        assertTrue(!message.contains("LAPIS_SECRET_ENCRYPTION_KEY"))
    }
}
