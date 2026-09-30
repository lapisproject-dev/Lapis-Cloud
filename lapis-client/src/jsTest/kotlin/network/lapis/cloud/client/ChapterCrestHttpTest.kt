package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.ChapterCrestUploadError
import network.lapis.cloud.shared.domain.RegionalChapterPublicRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Welle V1.9.20 "Öffentliche Seiten" -- status + JSON code -> [ChapterCrestUploadError] -> fixed message. The response body is never displayed. */
class ChapterCrestHttpTest {
    @Test
    fun aSuccessfulStatus_isOk_whateverTheBody() {
        assertEquals(ChapterCrestHttp.Result.Ok, chapterCrestResultOf(status = 200, body = "{}"))
        assertEquals(ChapterCrestHttp.Result.Ok, chapterCrestResultOf(status = 200, body = "<html>garbage</html>"))
    }

    @Test
    fun theJsonCodeWins_overTheStatus() {
        assertEquals(ChapterCrestUploadError.TOO_SMALL, chapterCrestErrorOf(status = 422, body = """{"error":"TOO_SMALL"}"""))
        assertEquals(
            ChapterCrestUploadError.UNSUPPORTED_FORMAT,
            chapterCrestErrorOf(status = 415, body = """{"error":"UNSUPPORTED_FORMAT"}"""),
        )
        assertEquals(ChapterCrestUploadError.RATE_LIMITED, chapterCrestErrorOf(status = 500, body = """{"error":"RATE_LIMITED"}"""))
        assertEquals(ChapterCrestUploadError.FORBIDDEN, chapterCrestErrorOf(status = 403, body = """{"error":"FORBIDDEN"}"""))
        assertEquals(ChapterCrestUploadError.NOT_FOUND, chapterCrestErrorOf(status = 404, body = """{"error":"NOT_FOUND"}"""))
    }

    @Test
    fun withoutAReadableCode_theStatusDecides_andUnknownIsInvalidRequest() {
        assertEquals(ChapterCrestUploadError.FILE_TOO_LARGE, chapterCrestErrorOf(status = 413, body = ""))
        assertEquals(ChapterCrestUploadError.UNSUPPORTED_FORMAT, chapterCrestErrorOf(status = 415, body = "Unsupported"))
        assertEquals(ChapterCrestUploadError.RATE_LIMITED, chapterCrestErrorOf(status = 429, body = "Too many"))
        assertEquals(ChapterCrestUploadError.INVALID_REQUEST, chapterCrestErrorOf(status = 500, body = "<html>boom</html>"))
        assertEquals(ChapterCrestUploadError.INVALID_REQUEST, chapterCrestErrorOf(status = 418, body = """{"error":"SOMETHING_NEW"}"""))
        assertEquals(ChapterCrestUploadError.INVALID_REQUEST, chapterCrestErrorOf(status = 0, body = ""))
    }

    @Test
    fun aFailedResult_carriesOnlyTheCode_neverTheBody() {
        val result = chapterCrestResultOf(status = 422, body = """{"error":"TOO_SMALL","detail":"<script>alert(1)</script>"}""")
        assertEquals(ChapterCrestHttp.Result.Error(ChapterCrestUploadError.TOO_SMALL), result)
        assertFalse(result.toString().contains("script"))
    }

    @Test
    fun theSixSvgCodes_areDecodedFromTheJsonBody() {
        listOf(
            ChapterCrestUploadError.SVG_SCRIPT,
            ChapterCrestUploadError.SVG_EXTERNAL_REFERENCE,
            ChapterCrestUploadError.SVG_TEXT_NOT_SUPPORTED,
            ChapterCrestUploadError.SVG_NO_DIMENSIONS,
            ChapterCrestUploadError.SVG_TOO_COMPLEX,
            ChapterCrestUploadError.SVG_UNSUPPORTED_CONTENT,
        ).forEach { code ->
            assertEquals(code, chapterCrestErrorOf(status = 422, body = """{"error":"${code.name}"}"""))
        }
    }

    @Test
    fun everyCode_hasAMessage_forEveryKindOfFile() {
        ChapterCrestUploadError.entries.forEach { code ->
            listOf(null, CrestFileKind.RASTER, CrestFileKind.SVG).forEach { kind ->
                val message = chapterCrestUploadErrorMessage(code = code, kind = kind)
                assertTrue(message.isNotBlank(), "message for $code/$kind")
                // tr() must never be concatenated: a KVision i18n marker in the middle of a sentence is the tell.
                assertFalse(message.contains("###KvI18n", ignoreCase = true) && !message.startsWith("###"), "marker inside $code/$kind")
            }
        }
        assertNotEquals(
            chapterCrestUploadErrorMessage(ChapterCrestUploadError.FILE_TOO_LARGE),
            chapterCrestUploadErrorMessage(ChapterCrestUploadError.TOO_SMALL),
        )
    }

    @Test
    fun theSvgWording_replacesTheRasterWording_whereTheRasterWordingWouldBeWrong() {
        for (code in listOf(
            ChapterCrestUploadError.FILE_TOO_LARGE,
            ChapterCrestUploadError.UNDECODABLE,
            ChapterCrestUploadError.DIMENSIONS_TOO_LARGE,
        )) {
            assertNotEquals(
                chapterCrestUploadErrorMessage(code = code, kind = CrestFileKind.RASTER),
                chapterCrestUploadErrorMessage(code = code, kind = CrestFileKind.SVG),
                "SVG wording for $code",
            )
        }
        assertTrue(chapterCrestUploadErrorMessage(ChapterCrestUploadError.FILE_TOO_LARGE, CrestFileKind.SVG).contains("256 KB"))
        assertTrue(chapterCrestUploadErrorMessage(ChapterCrestUploadError.FILE_TOO_LARGE, CrestFileKind.RASTER).contains("2 MB"))
        assertTrue(chapterCrestUploadErrorMessage(ChapterCrestUploadError.UNSUPPORTED_FORMAT).contains("SVG"))
    }

    @Test
    fun crestFileKindOf_usesTheMimeType_andFallsBackToTheExtensionOnlyWithoutAType() {
        assertEquals(CrestFileKind.RASTER, crestFileKindOf(type = "image/png", name = "a.png"))
        assertEquals(CrestFileKind.RASTER, crestFileKindOf(type = "image/jpeg", name = "a.jpg"))
        assertEquals(CrestFileKind.SVG, crestFileKindOf(type = "image/svg+xml", name = "a.svg"))
        assertEquals(CrestFileKind.SVG, crestFileKindOf(type = "", name = "WAPPEN.SVG"))
        // a declared PNG named .svg.png stays raster, a declared type always wins over the extension
        assertEquals(CrestFileKind.RASTER, crestFileKindOf(type = "image/png", name = "x.svg.png"))
        assertEquals(CrestFileKind.RASTER, crestFileKindOf(type = "image/png", name = "x.svg"))
        assertNull(crestFileKindOf(type = "text/xml", name = "a.svg"))
        assertNull(crestFileKindOf(type = "image/gif", name = "a.gif"))
        assertNull(crestFileKindOf(type = "", name = "a.png"))
    }

    @Test
    fun crestPrecheck_enforcesTheTwoSizeLimitsExactly() {
        val svgLimit = RegionalChapterPublicRules.CREST_SVG_MAX_UPLOAD_BYTES.toDouble()
        val rasterLimit = RegionalChapterPublicRules.CREST_MAX_UPLOAD_BYTES.toDouble()
        assertNull(crestPrecheck(type = "image/svg+xml", name = "a.svg", size = svgLimit))
        assertEquals(ChapterCrestUploadError.FILE_TOO_LARGE, crestPrecheck(type = "image/svg+xml", name = "a.svg", size = svgLimit + 1))
        assertNull(crestPrecheck(type = "image/png", name = "a.png", size = rasterLimit))
        assertEquals(ChapterCrestUploadError.FILE_TOO_LARGE, crestPrecheck(type = "image/png", name = "a.png", size = rasterLimit + 1))
        // a raster file may be larger than the SVG limit
        assertNull(crestPrecheck(type = "image/png", name = "a.png", size = svgLimit + 1))
        assertEquals(ChapterCrestUploadError.UNSUPPORTED_FORMAT, crestPrecheck(type = "image/gif", name = "a.gif", size = 10.0))
    }

    @Test
    fun theFileDialog_offersRasterAndSvg() {
        assertEquals(listOf("image/jpeg", "image/png", "image/svg+xml", ".svg"), CREST_ACCEPT)
    }
}
