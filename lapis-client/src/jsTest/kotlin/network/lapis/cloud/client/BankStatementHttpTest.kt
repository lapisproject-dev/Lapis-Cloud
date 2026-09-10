package network.lapis.cloud.client

import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.BankCsvDialect
import network.lapis.cloud.shared.domain.BankStatementFormat
import network.lapis.cloud.shared.domain.BankStatementImportRejectionDto
import network.lapis.cloud.shared.domain.BankStatementImportResultDto
import network.lapis.cloud.shared.domain.BankStatementRejectionCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Welle V1.4.5.1.1 -- covers only the pure, DOM/network-independent [parseBankStatementImportOutcome]
 * and [exceedsBankStatementUploadLimit] functions factored out of `BankStatementHttp.kt`: same scope
 * posture as [BackupHttpTest] (no DOM/rendering test harness exists in this module).
 */
class BankStatementHttpTest {
    private fun successBody(): String =
        Json.encodeToString(
            BankStatementImportResultDto.serializer(),
            BankStatementImportResultDto(
                importId = "11111111-1111-1111-1111-111111111111",
                format = BankStatementFormat.CSV,
                dialect = BankCsvDialect.SPARKASSE_CAMT,
                accountIbanMasked = "DE...4711",
                statementFrom = null,
                statementTo = null,
                balanceChecked = true,
                lineCount = 3,
                duplicateCount = 0,
                autoPostedCount = 1,
                ambiguousCount = 0,
                unmatchedCount = 1,
                ignoredCount = 0,
                suggestedCount = 1,
                warnings = emptyList(),
            ),
        )

    private fun rejectionBody(
        code: BankStatementRejectionCode,
        lineNumber: Int? = null,
        rawLineExcerpt: String? = null,
        observedHeaderFields: List<String>? = null,
    ): String =
        Json.encodeToString(
            BankStatementImportRejectionDto.serializer(),
            BankStatementImportRejectionDto(
                code = code,
                lineNumber = lineNumber,
                rawLineExcerpt = rawLineExcerpt,
                observedHeaderFields = observedHeaderFields,
                detail = "technisches Beiwerk, nie angezeigt",
            ),
        )

    @Test
    fun parseBankStatementImportOutcome_200_decodesSuccessBody() {
        val outcome = parseBankStatementImportOutcome(200, successBody())
        assertIs<BankStatementImportOutcome.Success>(outcome)
        assertEquals(3, outcome.result.lineCount)
        assertEquals(1, outcome.result.autoPostedCount)
    }

    @Test
    fun parseBankStatementImportOutcome_200_malformedBodyFallsBackToOtherRatherThanCrashing() {
        val outcome = parseBankStatementImportOutcome(200, "{not valid json")
        assertIs<BankStatementImportOutcome.Other>(outcome)
        assertEquals(200, outcome.status)
    }

    @Test
    fun parseBankStatementImportOutcome_413_isRejectedWithFileTooLarge() {
        val outcome = parseBankStatementImportOutcome(413, rejectionBody(BankStatementRejectionCode.FILE_TOO_LARGE))
        assertIs<BankStatementImportOutcome.Rejected>(outcome)
        assertEquals(413, outcome.status)
        assertEquals(BankStatementRejectionCode.FILE_TOO_LARGE, outcome.rejection.code)
    }

    @Test
    fun parseBankStatementImportOutcome_400_isRejectedWithNoFilePart() {
        val outcome = parseBankStatementImportOutcome(400, rejectionBody(BankStatementRejectionCode.NO_FILE_PART))
        assertIs<BankStatementImportOutcome.Rejected>(outcome)
        assertEquals(BankStatementRejectionCode.NO_FILE_PART, outcome.rejection.code)
    }

    @Test
    fun parseBankStatementImportOutcome_429_isRejectedWithRateLimited() {
        val outcome = parseBankStatementImportOutcome(429, rejectionBody(BankStatementRejectionCode.RATE_LIMITED))
        assertIs<BankStatementImportOutcome.Rejected>(outcome)
        assertEquals(BankStatementRejectionCode.RATE_LIMITED, outcome.rejection.code)
    }

    @Test
    fun parseBankStatementImportOutcome_422_formatUnrecognized_carriesObservedHeaderFields() {
        val outcome =
            parseBankStatementImportOutcome(
                422,
                rejectionBody(BankStatementRejectionCode.FORMAT_UNRECOGNIZED, observedHeaderFields = listOf("Spalte A", "Spalte B")),
            )
        assertIs<BankStatementImportOutcome.Rejected>(outcome)
        assertEquals(BankStatementRejectionCode.FORMAT_UNRECOGNIZED, outcome.rejection.code)
        assertEquals(listOf("Spalte A", "Spalte B"), outcome.rejection.observedHeaderFields)
    }

    @Test
    fun parseBankStatementImportOutcome_422_parseFailed_carriesLineNumberAndRawLineExcerpt() {
        val outcome =
            parseBankStatementImportOutcome(
                422,
                rejectionBody(BankStatementRejectionCode.PARSE_FAILED, lineNumber = 47, rawLineExcerpt = "DE00;...;48,00;EUR"),
            )
        assertIs<BankStatementImportOutcome.Rejected>(outcome)
        assertEquals(47, outcome.rejection.lineNumber)
        assertEquals("DE00;...;48,00;EUR", outcome.rejection.rawLineExcerpt)
    }

    @Test
    fun parseBankStatementImportOutcome_422_mt940BalanceMismatch() {
        val outcome = parseBankStatementImportOutcome(422, rejectionBody(BankStatementRejectionCode.MT940_BALANCE_MISMATCH))
        assertIs<BankStatementImportOutcome.Rejected>(outcome)
        assertEquals(BankStatementRejectionCode.MT940_BALANCE_MISMATCH, outcome.rejection.code)
    }

    @Test
    fun parseBankStatementImportOutcome_422_foreignAccount() {
        val outcome = parseBankStatementImportOutcome(422, rejectionBody(BankStatementRejectionCode.FOREIGN_ACCOUNT))
        assertIs<BankStatementImportOutcome.Rejected>(outcome)
        assertEquals(BankStatementRejectionCode.FOREIGN_ACCOUNT, outcome.rejection.code)
    }

    @Test
    fun parseBankStatementImportOutcome_422_tooManyLines() {
        val outcome = parseBankStatementImportOutcome(422, rejectionBody(BankStatementRejectionCode.TOO_MANY_LINES))
        assertIs<BankStatementImportOutcome.Rejected>(outcome)
        assertEquals(BankStatementRejectionCode.TOO_MANY_LINES, outcome.rejection.code)
    }

    @Test
    fun parseBankStatementImportOutcome_422_controlCharacter_carriesLineNumber() {
        val outcome = parseBankStatementImportOutcome(422, rejectionBody(BankStatementRejectionCode.CONTROL_CHARACTER, lineNumber = 3))
        assertIs<BankStatementImportOutcome.Rejected>(outcome)
        assertEquals(BankStatementRejectionCode.CONTROL_CHARACTER, outcome.rejection.code)
        assertEquals(3, outcome.rejection.lineNumber)
    }

    @Test
    fun parseBankStatementImportOutcome_409_isRejectedWithAlreadyImported() {
        val outcome = parseBankStatementImportOutcome(409, rejectionBody(BankStatementRejectionCode.ALREADY_IMPORTED))
        assertIs<BankStatementImportOutcome.Rejected>(outcome)
        assertEquals(BankStatementRejectionCode.ALREADY_IMPORTED, outcome.rejection.code)
    }

    @Test
    fun parseBankStatementImportOutcome_500_withEmptyBody_isOtherWithNonBlankMessage() {
        val outcome = parseBankStatementImportOutcome(500, "")
        assertIs<BankStatementImportOutcome.Other>(outcome)
        assertEquals(500, outcome.status)
        assertTrue(outcome.message.isNotBlank())
    }

    @Test
    fun parseBankStatementImportOutcome_422_withUnknownEnumLiteral_isOtherNotAnException() {
        // Vorwaerts-Kompatibilitaet (Stolperfalle #2 im Wellen-Plan): ein zukuenftiger elfter Code,
        // den dieser Client-Build noch nicht kennt, darf niemals eine SerializationException aus
        // dieser Funktion werfen.
        val body = """{"code":"SOME_FUTURE_CODE","lineNumber":null,"rawLineExcerpt":null,"observedHeaderFields":null,"detail":null}"""
        val outcome = parseBankStatementImportOutcome(422, body)
        assertIs<BankStatementImportOutcome.Other>(outcome)
        assertEquals(422, outcome.status)
    }

    @Test
    fun exceedsBankStatementUploadLimit_underLimit_isFalse() {
        assertEquals(false, exceedsBankStatementUploadLimit(5L * 1024 * 1024 - 1))
    }

    @Test
    fun exceedsBankStatementUploadLimit_exactlyAtLimit_isFalse() {
        // Grenzwert inklusive -- spiegelt `totalBytes > MAX_UPLOAD_BYTES` im Server (BankStatementRoutes.kt).
        assertEquals(false, exceedsBankStatementUploadLimit(5L * 1024 * 1024))
    }

    @Test
    fun exceedsBankStatementUploadLimit_overLimit_isTrue() {
        assertEquals(true, exceedsBankStatementUploadLimit(5L * 1024 * 1024 + 1))
    }

    @Test
    fun exceedsBankStatementUploadLimit_zero_isFalse() {
        assertEquals(false, exceedsBankStatementUploadLimit(0))
    }
}
