package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MemberPhotoUploadError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Welle V1.9.19 "Mitglieder-Foto" -- status + JSON code -> [MemberPhotoUploadError] -> fixed message. The response body is never displayed. */
class MemberPhotoHttpTest {
    @Test
    fun aSuccessfulStatus_isOk_whateverTheBody() {
        assertEquals(MemberPhotoHttp.Result.Ok, memberPhotoResultOf(status = 200, body = "{}"))
        assertEquals(MemberPhotoHttp.Result.Ok, memberPhotoResultOf(status = 200, body = "<html>garbage</html>"))
    }

    @Test
    fun theJsonCodeWins_overTheStatus() {
        assertEquals(MemberPhotoUploadError.TOO_SMALL, memberPhotoErrorOf(status = 422, body = """{"error":"TOO_SMALL"}"""))
        assertEquals(
            MemberPhotoUploadError.UNSUPPORTED_FORMAT,
            memberPhotoErrorOf(status = 422, body = """{"error":"UNSUPPORTED_FORMAT"}"""),
        )
        assertEquals(MemberPhotoUploadError.RATE_LIMITED, memberPhotoErrorOf(status = 500, body = """{"error":"RATE_LIMITED"}"""))
        assertEquals(MemberPhotoUploadError.NOT_ELIGIBLE, memberPhotoErrorOf(status = 403, body = """{"error":"NOT_ELIGIBLE"}"""))
    }

    @Test
    fun withoutAReadableCode_theStatusDecides_andUnknownIsInvalidRequest() {
        assertEquals(MemberPhotoUploadError.FILE_TOO_LARGE, memberPhotoErrorOf(status = 413, body = ""))
        assertEquals(MemberPhotoUploadError.RATE_LIMITED, memberPhotoErrorOf(status = 429, body = "Too many"))
        assertEquals(MemberPhotoUploadError.INVALID_REQUEST, memberPhotoErrorOf(status = 500, body = "<html>boom</html>"))
        assertEquals(MemberPhotoUploadError.INVALID_REQUEST, memberPhotoErrorOf(status = 418, body = """{"error":"SOMETHING_NEW"}"""))
        assertEquals(MemberPhotoUploadError.INVALID_REQUEST, memberPhotoErrorOf(status = 0, body = ""))
    }

    @Test
    fun aFailedResult_carriesOnlyTheCode_neverTheBody() {
        val result = memberPhotoResultOf(status = 422, body = """{"error":"TOO_SMALL","detail":"<script>alert(1)</script>"}""")
        assertEquals(MemberPhotoHttp.Result.Error(MemberPhotoUploadError.TOO_SMALL), result)
        assertFalse(result.toString().contains("script"))
    }

    @Test
    fun everyCode_hasAMessage_andTheFormatPairSharesOne() {
        MemberPhotoUploadError.entries.forEach { code -> assertTrue(memberPhotoUploadErrorMessage(code).isNotBlank(), "message for $code") }
        assertEquals(
            memberPhotoUploadErrorMessage(MemberPhotoUploadError.UNSUPPORTED_FORMAT),
            memberPhotoUploadErrorMessage(MemberPhotoUploadError.UNDECODABLE),
        )
        assertNotEquals(
            memberPhotoUploadErrorMessage(MemberPhotoUploadError.FILE_TOO_LARGE),
            memberPhotoUploadErrorMessage(MemberPhotoUploadError.TOO_SMALL),
        )
        assertNotEquals(
            memberPhotoUploadErrorMessage(MemberPhotoUploadError.TOO_SMALL),
            memberPhotoUploadErrorMessage(MemberPhotoUploadError.DIMENSIONS_TOO_LARGE),
        )
    }
}
