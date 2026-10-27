package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.PartgImportErrorCode
import network.lapis.cloud.shared.domain.PartgImportRejectionCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** V1.9.225 -- the mapping of status and body of the import routes (no network). */
class PartyReportHttpTest {
    @Test
    fun aPreviewBody_isParsed() {
        val ok =
            PartyReportHttp.parsePreview(
                200,
                """{"token":"t","recognized":42,"changes":[{"positionCode":"E01","column":"CURRENT","newCents":100}],"sheetRevision":3,"priorRevision":1}""",
            )
        val preview = assertIs<PartyReportHttp.PreviewResult.Ok>(ok).preview
        assertEquals("t", preview.token)
        assertEquals(3, preview.sheetRevision)
        assertEquals(1, preview.changes.size)
    }

    @Test
    fun errorsInAPreview_comeWithoutAToken() {
        val ok = PartyReportHttp.parsePreview(200, """{"errors":[{"row":9,"column":"D","code":"NOT_A_NUMBER"}]}""")
        val preview = assertIs<PartyReportHttp.PreviewResult.Ok>(ok).preview
        assertNull(preview.token)
        assertEquals(PartgImportErrorCode.NOT_A_NUMBER, preview.errors.single().code)
    }

    @Test
    fun aRejection_isAStableCode_neverFreeText() {
        val rejected = PartyReportHttp.parsePreview(413, """{"code":"TOO_LARGE"}""")
        assertEquals(PartgImportRejectionCode.TOO_LARGE, assertIs<PartyReportHttp.PreviewResult.Rejected>(rejected).code)
        val garbage = PartyReportHttp.parseApply(500, "<html>boom</html>")
        assertNull(assertIs<PartyReportHttp.ApplyResult.Rejected>(garbage).code)
        val conflict = PartyReportHttp.parseApply(409, """{"code":"REVISION_CONFLICT"}""")
        assertEquals(PartgImportRejectionCode.REVISION_CONFLICT, assertIs<PartyReportHttp.ApplyResult.Rejected>(conflict).code)
    }

    @Test
    fun everyErrorCodeAndRejectionCode_hasAFixedText() {
        PartgImportErrorCode.entries.forEach { assertEquals(false, partgImportErrorText(it).isBlank(), it.name) }
        PartgImportRejectionCode.entries.forEach { assertEquals(false, partgRejectionText(it).isBlank(), it.name) }
        assertEquals(false, partgRejectionText(null).isBlank())
    }

    @Test
    fun templateUrl_carriesYearAndUnit() {
        assertEquals("/api/partg-report/template?year=2025&unit=FEDERAL", PartyReportHttp.templateUrl(2025, "FEDERAL"))
    }
}
