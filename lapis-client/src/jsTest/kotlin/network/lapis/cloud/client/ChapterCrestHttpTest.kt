package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.ChapterCrestUploadError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    fun everyCode_hasAMessage_andTheFormatPairSharesOne() {
        ChapterCrestUploadError.entries.forEach { code ->
            assertTrue(chapterCrestUploadErrorMessage(code).isNotBlank(), "message for $code")
        }
        assertEquals(
            chapterCrestUploadErrorMessage(ChapterCrestUploadError.UNSUPPORTED_FORMAT),
            chapterCrestUploadErrorMessage(ChapterCrestUploadError.UNDECODABLE),
        )
        assertNotEquals(
            chapterCrestUploadErrorMessage(ChapterCrestUploadError.FILE_TOO_LARGE),
            chapterCrestUploadErrorMessage(ChapterCrestUploadError.TOO_SMALL),
        )
    }
}
